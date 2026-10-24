//! Monotonic ON-only upload primitive. Control-plane admission and final ownership
//! handoff are intentionally not advertised until their contracts are implemented.
use super::*;
use aws_sdk_s3::config::retry::RetryConfig;
use aws_sdk_s3::types::ObjectLockLegalHoldStatus;

const MAX_BYTES: usize = 16 * 1024 * 1024;
const MAX_JOURNAL_BYTES: usize = 16 * 1024;

#[derive(Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(deny_unknown_fields)]
pub struct RecordingUploadHoldScope {
    pub operation_id: String,
    pub authority_epoch: u64,
    pub placement_epoch: u64,
    pub tenant_id: String,
    pub profile_id: String,
    pub session_id: String,
    pub recording_id: String,
}

pub struct RecordingHeldSegment {
    pub sequence: u64,
    pub content: Bytes,
    pub content_sha256: String,
    pub frame_count: u64,
    pub redacted_frame_count: u64,
    pub redacted_region_count: u64,
    pub redaction_policy_version: u32,
    pub started_at_ms: u64,
    pub ended_at_ms: u64,
}

#[derive(Serialize, Deserialize, PartialEq, Eq, Debug)]
pub struct RecordingHeldSegmentProof {
    pub scope_sha256: String,
    pub segment_sequence: u64,
    pub data_version_id: String,
    pub marker_version_id: String,
    pub proof_hash: String,
}

#[derive(Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct UploadAuthority {
    schema_version: u32,
    scope: RecordingUploadHoldScope,
    archive_binding_sha256: String,
}

#[derive(Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct UploadIntent {
    schema_version: u32,
    scope_sha256: String,
    key_sha256: String,
    content_sha256: String,
    content_bytes: u64,
    nonce: String,
}

impl ObjectArchive {
    pub(super) async fn reject_recording_upload_hold_downgrade(
        &self,
        tenant: &str,
        profile: &str,
        session: &str,
        recording: &str,
    ) -> anyhow::Result<()> {
        let key = Path::from(format!(
            "{}/UPLOAD_HOLD/AUTHORITY",
            self.recording_deletion_key_for(tenant, profile, session, recording)
        ));
        match tokio::time::timeout(self.operation_timeout, self.store.head(&key)).await {
            Ok(Err(ObjectStoreError::NotFound { .. })) => Ok(()),
            _ => anyhow::bail!("RECORDING_UPLOAD_HOLD_DOWNGRADE_REJECTED"),
        }
    }

    /// Trusted-runtime building block, not a public policy bypass. No OFF operation
    /// exists here. Existing recording uploads retain their current behavior.
    pub async fn commit_recording_held_segment(
        &self,
        scope: &RecordingUploadHoldScope,
        segment: RecordingHeldSegment,
    ) -> anyhow::Result<RecordingHeldSegmentProof> {
        tokio::time::timeout(
            Duration::from_secs(60),
            self.commit_recording_held_segment_inner(scope, segment),
        )
        .await
        .map_err(|_| anyhow::anyhow!("RECORDING_UPLOAD_HOLD_RESULT_UNKNOWN"))?
        .map_err(|_| anyhow::anyhow!("RECORDING_UPLOAD_HOLD_REJECTED_OR_UNKNOWN"))
    }

    async fn commit_recording_held_segment_inner(
        &self,
        scope: &RecordingUploadHoldScope,
        segment: RecordingHeldSegment,
    ) -> anyhow::Result<RecordingHeldSegmentProof> {
        for value in [
            &scope.operation_id,
            &scope.tenant_id,
            &scope.profile_id,
            &scope.session_id,
            &scope.recording_id,
        ] {
            crate::validate_identifier("recording_upload_hold", value)?;
        }
        anyhow::ensure!(
            scope.operation_id.starts_with("rua_")
                && scope.authority_epoch > 0
                && scope.placement_epoch > 0
                && segment.sequence < 100_000
                && !segment.content.is_empty()
                && segment.content.len() <= MAX_BYTES
                && hex_sha256(&segment.content) == segment.content_sha256
                && segment.frame_count > 0
                && segment.redacted_frame_count <= segment.frame_count
                && (segment.redacted_frame_count == 0 || segment.redacted_region_count > 0)
                && (1..=2).contains(&segment.redaction_policy_version)
                && segment.ended_at_ms >= segment.started_at_ms,
            "upload scope or segment rejected"
        );
        let lock = tokio::time::timeout(
            self.operation_timeout,
            self.version_store
                .get_object_lock_configuration()
                .bucket(&self.bucket)
                .send(),
        )
        .await??;
        anyhow::ensure!(
            lock.object_lock_configuration()
                .and_then(|value| value.object_lock_enabled())
                == Some(&aws_sdk_s3::types::ObjectLockEnabled::Enabled),
            "Object Lock required"
        );
        let binding = hex_sha256(&serde_json::to_vec(&(&self.bucket, &self.prefix))?);
        let authority = UploadAuthority {
            schema_version: 1,
            scope: scope.clone(),
            archive_binding_sha256: binding,
        };
        let authority_bytes = serde_json::to_vec(&authority)?;
        anyhow::ensure!(
            authority_bytes.len() <= MAX_JOURNAL_BYTES,
            "authority bound"
        );
        let scope_sha256 = hex_sha256(&authority_bytes);
        let journal_base = format!(
            "{}/UPLOAD_HOLD",
            self.recording_deletion_key_for(
                &scope.tenant_id,
                &scope.profile_id,
                &scope.session_id,
                &scope.recording_id
            )
        );
        // Exact content equality fences every later epoch, placement and controller.
        self.put_upload_journal_immutable(
            &format!("{journal_base}/AUTHORITY"),
            Bytes::from(authority_bytes),
        )
        .await?;
        let base = self.recording_key_for(
            &scope.tenant_id,
            &scope.profile_id,
            &scope.session_id,
            &scope.recording_id,
        );
        // Reject already visible finalization/deletion or final Hold intents.
        // These preflight checks are not a concurrent writer seal: a future handoff
        // must establish quiescence before it can authorize any OFF or deletion.
        for key in [
            format!("{base}/COMMITTED"),
            format!(
                "{}/PREPARED",
                self.recording_deletion_key_for(
                    &scope.tenant_id,
                    &scope.profile_id,
                    &scope.session_id,
                    &scope.recording_id
                )
            ),
            format!(
                "{}/COMMITTED",
                self.recording_deletion_key_for(
                    &scope.tenant_id,
                    &scope.profile_id,
                    &scope.session_id,
                    &scope.recording_id
                )
            ),
            format!(
                "{}/LEGAL_HOLD",
                self.recording_deletion_key_for(
                    &scope.tenant_id,
                    &scope.profile_id,
                    &scope.session_id,
                    &scope.recording_id
                )
            ),
        ] {
            anyhow::ensure!(self.get_optional(&key).await?.is_none(), "upload closed");
        }
        let object_key = format!("{base}/segments/{:020}.ndjson", segment.sequence);
        let marker_key = format!("{base}/segments/{:020}.COMMITTED", segment.sequence);
        let marker = Bytes::from(serde_json::to_vec(&RecordingSegmentMarker {
            recording_id: &scope.recording_id,
            segment_sequence: segment.sequence,
            content_sha256: &segment.content_sha256,
            content_bytes: segment.content.len() as u64,
            frame_count: segment.frame_count,
            redacted_frame_count: segment.redacted_frame_count,
            redacted_region_count: segment.redacted_region_count,
            redaction_policy_version: segment.redaction_policy_version,
            started_at_ms: segment.started_at_ms,
            ended_at_ms: segment.ended_at_ms,
        })?);
        let data_version_id = self
            .put_held_upload_object(&journal_base, &scope_sha256, &object_key, segment.content)
            .await?;
        let marker_version_id = self
            .put_held_upload_object(&journal_base, &scope_sha256, &marker_key, marker)
            .await?;
        let proof_hash = hex_sha256(&serde_json::to_vec(&(
            &scope_sha256,
            segment.sequence,
            &data_version_id,
            &marker_version_id,
        ))?);
        Ok(RecordingHeldSegmentProof {
            scope_sha256,
            segment_sequence: segment.sequence,
            data_version_id,
            marker_version_id,
            proof_hash,
        })
    }

    async fn put_held_upload_object(
        &self,
        journal_base: &str,
        scope_sha256: &str,
        key: &str,
        content: Bytes,
    ) -> anyhow::Result<String> {
        let key_sha256 = hex_sha256(key.as_bytes());
        let content_sha256 = hex_sha256(&content);
        let intent_key = Path::from(format!("{journal_base}/writes/{key_sha256}/PREPARED"));
        let proposed = UploadIntent {
            schema_version: 1,
            scope_sha256: scope_sha256.into(),
            key_sha256: key_sha256.clone(),
            content_sha256: content_sha256.clone(),
            content_bytes: content.len() as u64,
            nonce: uuid::Uuid::new_v4().to_string(),
        };
        let bytes = serde_json::to_vec(&proposed)?;
        let created = tokio::time::timeout(
            self.operation_timeout,
            self.store.put_opts(
                &intent_key,
                Bytes::from(bytes).into(),
                PutOptions {
                    mode: PutMode::Create,
                    ..Default::default()
                },
            ),
        )
        .await?;
        let (intent, may_send) = match created {
            Ok(_) => (proposed, true),
            Err(ObjectStoreError::AlreadyExists { .. }) => {
                let bytes = self.read_upload_journal(&intent_key).await?;
                let existing: UploadIntent = serde_json::from_slice(&bytes)?;
                anyhow::ensure!(
                    existing.schema_version == 1
                        && existing.scope_sha256 == scope_sha256
                        && existing.key_sha256 == key_sha256
                        && existing.content_sha256 == content_sha256
                        && existing.content_bytes == content.len() as u64
                        && uuid::Uuid::parse_str(&existing.nonce).is_ok(),
                    "intent identity rejected"
                );
                (existing, false)
            }
            Err(_) => anyhow::bail!("intent result unknown"),
        };
        let client = aws_sdk_s3::Client::from_conf(
            self.version_store
                .config()
                .to_builder()
                .retry_config(RetryConfig::disabled())
                .build(),
        );
        if may_send {
            // ON is atomic with the first create-only PUT, never a later repair call.
            // Once PREPARED exists, every retry is read-only, including after a crash
            // before dispatch. Missing bytes then remain unknown rather than resent.
            tokio::time::timeout(
                self.operation_timeout,
                client
                    .put_object()
                    .bucket(&self.bucket)
                    .key(key)
                    .if_none_match("*")
                    .object_lock_legal_hold_status(ObjectLockLegalHoldStatus::On)
                    .metadata("recording-upload-nonce", &intent.nonce)
                    .metadata("recording-upload-scope", scope_sha256)
                    .metadata("recording-upload-sha256", &content_sha256)
                    .body(ByteStream::from(content.clone()))
                    .send(),
            )
            .await??;
        }
        let head = tokio::time::timeout(
            self.operation_timeout,
            client.head_object().bucket(&self.bucket).key(key).send(),
        )
        .await??;
        let id = head
            .version_id()
            .filter(|id| !id.is_empty() && *id != "null" && id.len() <= 1024)
            .ok_or_else(|| anyhow::anyhow!("version required"))?;
        let metadata = head
            .metadata()
            .ok_or_else(|| anyhow::anyhow!("ownership required"))?;
        anyhow::ensure!(
            metadata.get("recording-upload-nonce") == Some(&intent.nonce)
                && metadata.get("recording-upload-scope").map(String::as_str) == Some(scope_sha256)
                && metadata.get("recording-upload-sha256") == Some(&content_sha256)
                && head.content_length() == Some(content.len() as i64),
            "ownership rejected"
        );
        let held = tokio::time::timeout(
            self.operation_timeout,
            client
                .get_object_legal_hold()
                .bucket(&self.bucket)
                .key(key)
                .version_id(id)
                .send(),
        )
        .await??;
        anyhow::ensure!(
            held.legal_hold().and_then(|hold| hold.status())
                == Some(&ObjectLockLegalHoldStatus::On),
            "native ON readback required"
        );
        let object = tokio::time::timeout(
            self.operation_timeout,
            client
                .get_object()
                .bucket(&self.bucket)
                .key(key)
                .version_id(id)
                .send(),
        )
        .await??;
        anyhow::ensure!(
            object.content_length() == Some(content.len() as i64),
            "content bound"
        );
        let read = tokio::time::timeout(self.operation_timeout, object.body.collect())
            .await??
            .into_bytes();
        anyhow::ensure!(read == content, "version content rejected");
        // Immutable proof records contain the exact version; they never authorize OFF.
        self.put_upload_journal_immutable(
            &format!("{journal_base}/writes/{key_sha256}/COMMITTED"),
            Bytes::from(serde_json::to_vec(&(&intent, id))?),
        )
        .await?;
        Ok(id.to_owned())
    }

    async fn read_upload_journal(&self, key: &Path) -> anyhow::Result<Bytes> {
        let result = tokio::time::timeout(self.operation_timeout, self.store.get(key)).await??;
        anyhow::ensure!(
            result.meta.size <= MAX_JOURNAL_BYTES as u64,
            "journal bound"
        );
        let bytes = tokio::time::timeout(self.operation_timeout, result.bytes()).await??;
        anyhow::ensure!(bytes.len() <= MAX_JOURNAL_BYTES, "journal bound");
        Ok(bytes)
    }

    async fn put_upload_journal_immutable(&self, key: &str, bytes: Bytes) -> anyhow::Result<()> {
        anyhow::ensure!(bytes.len() <= MAX_JOURNAL_BYTES, "journal bound");
        let key = Path::from(key);
        match tokio::time::timeout(
            self.operation_timeout,
            self.store.put_opts(
                &key,
                bytes.clone().into(),
                PutOptions {
                    mode: PutMode::Create,
                    ..Default::default()
                },
            ),
        )
        .await?
        {
            Ok(_) => Ok(()),
            Err(ObjectStoreError::AlreadyExists { .. }) => {
                anyhow::ensure!(
                    self.read_upload_journal(&key).await? == bytes,
                    "journal identity changed"
                );
                Ok(())
            }
            Err(_) => anyhow::bail!("journal result unknown"),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn run_owned<F: std::future::Future<Output = ()> + Send + 'static>(
        make: impl FnOnce() -> F + Send + 'static,
    ) {
        // Same stack budget as the production Helper, including SDK debug futures.
        std::thread::Builder::new()
            .stack_size(8 * 1024 * 1024)
            .spawn(move || {
                tokio::runtime::Builder::new_multi_thread()
                    .worker_threads(2)
                    .thread_stack_size(8 * 1024 * 1024)
                    .enable_all()
                    .build()
                    .unwrap()
                    .block_on(make());
            })
            .unwrap()
            .join()
            .unwrap();
    }

    fn archive() -> ObjectArchive {
        ObjectArchive::s3(S3ArchiveConfig {
            bucket: std::env::var("TEST_OBJECT_STORAGE_BUCKET").unwrap(),
            region: "us-east-1".into(),
            endpoint: std::env::var("TEST_OBJECT_STORAGE_ENDPOINT").unwrap(),
            access_key_id: std::env::var("TEST_OBJECT_STORAGE_ACCESS_KEY_ID").unwrap(),
            secret_access_key: std::env::var("TEST_OBJECT_STORAGE_SECRET_ACCESS_KEY").unwrap(),
            prefix: "upload-hold-acceptance".into(),
            connect_timeout: Duration::from_secs(1),
            operation_timeout: Duration::from_secs(3),
            allow_http: true,
            profile_crypto: ProfileArchiveCrypto::for_test(
                "test-key-v1",
                &[("test-key-v1", [0x41; 32])],
            ),
        })
        .unwrap()
    }

    fn scope() -> RecordingUploadHoldScope {
        RecordingUploadHoldScope {
            operation_id: format!("rua_{}", uuid::Uuid::new_v4().simple()),
            authority_epoch: 1,
            placement_epoch: 3,
            tenant_id: "tenant-upload-test".into(),
            profile_id: "profile-upload-test".into(),
            session_id: "ses_upload_test".into(),
            recording_id: format!("rec_{}", uuid::Uuid::new_v4().simple()),
        }
    }

    fn segment(sequence: u64) -> RecordingHeldSegment {
        let content = Bytes::from_static(
            br#"{"capturedAtMs":1,"redactionState":"MASKED","data":"owned-synthetic-pixels"}"#,
        );
        RecordingHeldSegment {
            sequence,
            content_sha256: hex_sha256(&content),
            content,
            frame_count: 1,
            redacted_frame_count: 1,
            redacted_region_count: 1,
            redaction_policy_version: 1,
            started_at_ms: 1,
            ended_at_ms: 2,
        }
    }

    fn base(archive: &ObjectArchive, scope: &RecordingUploadHoldScope) -> String {
        archive.recording_key_for(
            &scope.tenant_id,
            &scope.profile_id,
            &scope.session_id,
            &scope.recording_id,
        )
    }

    #[test]
    #[ignore = "requires owned Object Lock-enabled TEST_OBJECT_STORAGE_*"]
    fn real_s3_upload_hold_atomic_versions_and_read_only_replay() {
        run_owned(|| async {
            let archive = archive();
            let scope = scope();
            let proof = archive
                .commit_recording_held_segment(&scope, segment(0))
                .await
                .unwrap();
            let prefix = base(&archive, &scope);
            assert!(archive
                .get_optional(&format!("{prefix}/COMMITTED"))
                .await
                .unwrap()
                .is_none());
            let versions = archive
                .recording_object_versions(&prefix, 10)
                .await
                .unwrap();
            assert_eq!(versions.len(), 2);
            for version in &versions {
                let native = archive
                    .version_store
                    .get_object_legal_hold()
                    .bucket(&archive.bucket)
                    .key(&version.key)
                    .version_id(&version.version_id)
                    .send()
                    .await
                    .unwrap();
                assert_eq!(
                    native.legal_hold().unwrap().status(),
                    Some(&ObjectLockLegalHoldStatus::On)
                );
                assert!(archive
                    .version_store
                    .delete_object()
                    .bucket(&archive.bucket)
                    .key(&version.key)
                    .version_id(&version.version_id)
                    .send()
                    .await
                    .is_err());
            }
            let restarted = super::tests::archive();
            let replay = restarted
                .commit_recording_held_segment(&scope, segment(0))
                .await
                .unwrap();
            assert_eq!(replay, proof);
            assert_eq!(
                restarted
                    .recording_object_versions(&prefix, 10)
                    .await
                    .unwrap()
                    .len(),
                2
            );
            let mut changed = scope.clone();
            changed.placement_epoch += 1;
            assert!(archive
                .commit_recording_held_segment(&changed, segment(1))
                .await
                .is_err());
            changed = scope.clone();
            changed.authority_epoch += 1;
            assert!(archive
                .commit_recording_held_segment(&changed, segment(1))
                .await
                .is_err());
            let mut bad = segment(0);
            bad.content = Bytes::from_static(b"changed");
            bad.content_sha256 = hex_sha256(&bad.content);
            assert!(archive
                .commit_recording_held_segment(&scope, bad)
                .await
                .is_err());
            assert_eq!(
                archive
                    .recording_object_versions(&prefix, 10)
                    .await
                    .unwrap()
                    .len(),
                2
            );
            let old = segment(1);
            assert!(archive
                .commit_recording_segment(
                    &scope.tenant_id,
                    &scope.profile_id,
                    &scope.session_id,
                    &scope.recording_id,
                    1,
                    old.content,
                    &old.content_sha256,
                    1,
                    1,
                    1,
                    1,
                    1,
                    2
                )
                .await
                .is_err());
            assert!(archive
                .complete_recording(
                    &scope.tenant_id,
                    &scope.profile_id,
                    &scope.session_id,
                    &scope.recording_id,
                    1,
                    1,
                    1,
                    1,
                    1,
                    1,
                    2
                )
                .await
                .is_err());
            assert_eq!(
                archive
                    .recording_object_versions(&prefix, 10)
                    .await
                    .unwrap()
                    .len(),
                2
            );
        });
    }

    #[test]
    #[ignore = "requires owned Object Lock-enabled TEST_OBJECT_STORAGE_*"]
    fn real_s3_upload_hold_lost_receipt_reconciles_exact_owned_version() {
        run_owned(|| async {
            let archive = archive();
            let scope = scope();
            let segment = segment(0);
            let authority = UploadAuthority {
                schema_version: 1,
                scope: scope.clone(),
                archive_binding_sha256: hex_sha256(
                    &serde_json::to_vec(&(&archive.bucket, &archive.prefix)).unwrap(),
                ),
            };
            let scope_hash = hex_sha256(&serde_json::to_vec(&authority).unwrap());
            let prefix = base(&archive, &scope);
            let key = format!("{prefix}/segments/{:020}.ndjson", 0);
            let key_hash = hex_sha256(key.as_bytes());
            let intent = UploadIntent {
                schema_version: 1,
                scope_sha256: scope_hash.clone(),
                key_sha256: key_hash.clone(),
                content_sha256: segment.content_sha256.clone(),
                content_bytes: segment.content.len() as u64,
                nonce: uuid::Uuid::new_v4().to_string(),
            };
            let journal = format!(
                "{}/UPLOAD_HOLD/writes/{key_hash}",
                archive.recording_deletion_key_for(
                    &scope.tenant_id,
                    &scope.profile_id,
                    &scope.session_id,
                    &scope.recording_id
                )
            );
            archive
                .put_upload_journal_immutable(
                    &format!("{journal}/PREPARED"),
                    Bytes::from(serde_json::to_vec(&intent).unwrap()),
                )
                .await
                .unwrap();
            // Simulate a real committed PUT whose reply/proof was lost. Recovery may
            // inspect this version but cannot resend: create-only PUT would reject.
            let applied = archive
                .version_store
                .put_object()
                .bucket(&archive.bucket)
                .key(&key)
                .if_none_match("*")
                .object_lock_legal_hold_status(ObjectLockLegalHoldStatus::On)
                .metadata("recording-upload-nonce", &intent.nonce)
                .metadata("recording-upload-scope", &scope_hash)
                .metadata("recording-upload-sha256", &segment.content_sha256)
                .body(ByteStream::from(segment.content))
                .send()
                .await
                .unwrap();
            assert!(archive
                .get_optional(&format!("{journal}/COMMITTED"))
                .await
                .unwrap()
                .is_none());
            let proof = super::tests::archive()
                .commit_recording_held_segment(&scope, super::tests::segment(0))
                .await
                .unwrap();
            assert_eq!(Some(proof.data_version_id.as_str()), applied.version_id());
            assert_eq!(
                archive
                    .recording_object_versions(&prefix, 10)
                    .await
                    .unwrap()
                    .len(),
                2
            );
            assert!(archive
                .get_optional(&format!("{journal}/COMMITTED"))
                .await
                .unwrap()
                .is_some());
        });
    }

    #[test]
    #[ignore = "requires owned Object Lock-enabled TEST_OBJECT_STORAGE_*"]
    fn real_s3_upload_hold_prepared_without_dispatch_never_resends() {
        run_owned(|| async {
            let archive = archive();
            let scope = scope();
            let segment = segment(0);
            let authority = UploadAuthority {
                schema_version: 1,
                scope: scope.clone(),
                archive_binding_sha256: hex_sha256(
                    &serde_json::to_vec(&(&archive.bucket, &archive.prefix)).unwrap(),
                ),
            };
            let scope_hash = hex_sha256(&serde_json::to_vec(&authority).unwrap());
            let prefix = base(&archive, &scope);
            let key = format!("{prefix}/segments/{:020}.ndjson", 0);
            let key_hash = hex_sha256(key.as_bytes());
            let intent = UploadIntent {
                schema_version: 1,
                scope_sha256: scope_hash,
                key_sha256: key_hash.clone(),
                content_sha256: segment.content_sha256,
                content_bytes: segment.content.len() as u64,
                nonce: uuid::Uuid::new_v4().to_string(),
            };
            let journal = format!(
                "{}/UPLOAD_HOLD/writes/{key_hash}",
                archive.recording_deletion_key_for(
                    &scope.tenant_id,
                    &scope.profile_id,
                    &scope.session_id,
                    &scope.recording_id
                )
            );
            archive
                .put_upload_journal_immutable(
                    &format!("{journal}/PREPARED"),
                    Bytes::from(serde_json::to_vec(&intent).unwrap()),
                )
                .await
                .unwrap();
            for _ in 0..2 {
                assert!(super::tests::archive()
                    .commit_recording_held_segment(&scope, super::tests::segment(0))
                    .await
                    .is_err());
                assert!(archive
                    .recording_object_versions(&prefix, 10)
                    .await
                    .unwrap()
                    .is_empty());
                assert!(archive
                    .get_optional(&format!("{journal}/COMMITTED"))
                    .await
                    .unwrap()
                    .is_none());
            }
        });
    }

    #[test]
    #[ignore = "requires owned Object Lock-enabled TEST_OBJECT_STORAGE_*"]
    fn real_s3_upload_hold_foreign_on_is_never_adopted() {
        run_owned(|| async {
            let archive = archive();
            let scope = scope();
            let segment = segment(0);
            let prefix = base(&archive, &scope);
            let key = format!("{prefix}/segments/{:020}.ndjson", 0);
            let foreign = archive
                .version_store
                .put_object()
                .bucket(&archive.bucket)
                .key(&key)
                .object_lock_legal_hold_status(ObjectLockLegalHoldStatus::On)
                .body(ByteStream::from(segment.content))
                .send()
                .await
                .unwrap();
            let id = foreign.version_id().unwrap();
            assert!(archive
                .commit_recording_held_segment(&scope, super::tests::segment(0))
                .await
                .is_err());
            assert!(archive
                .commit_recording_held_segment(&scope, super::tests::segment(0))
                .await
                .is_err());
            assert_eq!(
                archive
                    .recording_object_versions(&prefix, 10)
                    .await
                    .unwrap()
                    .len(),
                1
            );
            let held = archive
                .version_store
                .get_object_legal_hold()
                .bucket(&archive.bucket)
                .key(&key)
                .version_id(id)
                .send()
                .await
                .unwrap();
            assert_eq!(
                held.legal_hold().unwrap().status(),
                Some(&ObjectLockLegalHoldStatus::On)
            );
        });
    }

    #[test]
    #[ignore = "requires owned Object Lock-enabled TEST_OBJECT_STORAGE_*"]
    fn real_s3_upload_hold_concurrent_controllers_create_one_version() {
        run_owned(|| async {
            let a = archive();
            let b = archive();
            let scope = scope();
            let (left, right) = tokio::join!(
                a.commit_recording_held_segment(&scope, segment(0)),
                b.commit_recording_held_segment(&scope, segment(0))
            );
            assert!(left.is_ok() || right.is_ok());
            let proof = a
                .commit_recording_held_segment(&scope, segment(0))
                .await
                .unwrap();
            for result in [left, right].into_iter().flatten() {
                assert_eq!(result, proof);
            }
            assert_eq!(
                a.recording_object_versions(&base(&a, &scope), 10)
                    .await
                    .unwrap()
                    .len(),
                2
            );
        });
    }
}
