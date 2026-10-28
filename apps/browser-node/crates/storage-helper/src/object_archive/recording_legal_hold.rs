//! Version-scoped S3 Legal Hold executor. Not yet exposed by the control-plane policy API.
//! A conditional object-store journal serializes controllers across processes and Nodes.
//! PREPARED without COMMITTED is deliberately unresolved: a late OFF may still be in flight.

use super::*;
use aws_sdk_s3::config::retry::RetryConfig;
use aws_sdk_s3::types::{ObjectLockLegalHold, ObjectLockLegalHoldStatus};
use object_store::UpdateVersion;

const MAX_JOURNAL_BYTES: u64 = 16 * 1024;

#[derive(Clone, Copy, Serialize, Deserialize, PartialEq, Eq, Debug)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum RecordingLegalHoldState {
    On,
    Off,
}

#[derive(Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(deny_unknown_fields)]
pub struct RecordingLegalHoldRequest {
    pub operation_id: String,
    pub authority_epoch: u64,
    pub tenant_id: String,
    pub profile_id: String,
    pub session_id: String,
    pub recording_id: String,
    pub manifest_sha256: String,
    pub manifest_bytes: u64,
    pub segment_count: u64,
    pub desired_state: RecordingLegalHoldState,
    pub previous_proof_hash: Option<String>,
    pub execution_budget_millis: u64,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct RecordingLegalHoldResult {
    pub operation_id: String,
    pub authority_epoch: u64,
    pub recording_id: String,
    pub state: RecordingLegalHoldState,
    pub object_version_count: u64,
    pub inventory_sha256: String,
    pub proof_hash: String,
    pub completed_at_ms: u64,
}

#[derive(Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct HoldJournal {
    schema_version: u32,
    request: RecordingLegalHoldRequest,
    archive_binding_sha256: String,
    // A unique nonce plus strictly increasing Epoch prevents a journal ETag ABA.
    nonce: String,
    inventory_sha256: String,
    object_version_count: u64,
    completed_at_ms: Option<u64>,
}

impl ObjectArchive {
    pub async fn apply_recording_legal_hold(
        &self,
        request: RecordingLegalHoldRequest,
    ) -> anyhow::Result<RecordingLegalHoldResult> {
        validate_request(&request)
            .map_err(|_| anyhow::anyhow!("RECORDING_HOLD_REQUEST_REJECTED"))?;
        let result = tokio::time::timeout(
            Duration::from_millis(request.execution_budget_millis),
            self.apply_recording_legal_hold_inner(&request),
        )
        .await
        .context("RECORDING_HOLD_RESULT_UNKNOWN: execution deadline")?;
        // Object coordinates, Version IDs and SDK response details stay inside the executor.
        result.map_err(|error| {
            let message = error.to_string();
            let code = message.split(':').next().unwrap_or_default();
            if code.starts_with("RECORDING_HOLD_")
                && code.len() <= 96
                && code
                    .bytes()
                    .all(|byte| byte.is_ascii_uppercase() || byte == b'_')
            {
                anyhow::anyhow!(code.to_owned())
            } else {
                anyhow::anyhow!("RECORDING_HOLD_EXECUTION_REJECTED")
            }
        })
    }

    async fn apply_recording_legal_hold_inner(
        &self,
        request: &RecordingLegalHoldRequest,
    ) -> anyhow::Result<RecordingLegalHoldResult> {
        let base = self.recording_key_for(
            &request.tenant_id,
            &request.profile_id,
            &request.session_id,
            &request.recording_id,
        );
        // Journals are outside the Recording prefix, so deletion cannot erase its own fences.
        let journal_key = Path::from(format!(
            "{}/LEGAL_HOLD",
            self.recording_deletion_key_for(
                &request.tenant_id,
                &request.profile_id,
                &request.session_id,
                &request.recording_id
            )
        ));
        let binding = hex_sha256(&serde_json::to_vec(&(&self.bucket, &self.prefix))?);
        let prior = self.read_hold_journal(&journal_key).await?;
        let mut expected_state = RecordingLegalHoldState::Off;
        let mode = if let Some((journal, version)) = &prior {
            validate_request(&journal.request)?;
            anyhow::ensure!(
                journal.schema_version == 1
                    && journal.archive_binding_sha256 == binding
                    && same_recording(&journal.request, request)
                    && is_hash(&journal.inventory_sha256)
                    && journal.object_version_count > 0
                    && uuid::Uuid::parse_str(&journal.nonce).is_ok(),
                "RECORDING_HOLD_JOURNAL_IDENTITY_REJECTED"
            );
            anyhow::ensure!(
                journal.completed_at_ms.is_some(),
                "RECORDING_HOLD_RESULT_UNKNOWN: unresolved intent"
            );
            if journal.request == *request {
                let versions = self.hold_inventory(&base, request).await?;
                anyhow::ensure!(
                    inventory_hash(&versions)? == journal.inventory_sha256
                        && versions.len() as u64 == journal.object_version_count,
                    "RECORDING_HOLD_INVENTORY_CHANGED"
                );
                self.verify_hold_versions(&versions, request.desired_state)
                    .await?;
                return journal.result();
            }
            anyhow::ensure!(
                request.authority_epoch > journal.request.authority_epoch
                    && request.operation_id != journal.request.operation_id,
                "RECORDING_HOLD_AUTHORITY_FENCED"
            );
            anyhow::ensure!(
                request.previous_proof_hash.as_deref() == Some(journal.proof_hash()?.as_str()),
                "RECORDING_HOLD_PREVIOUS_PROOF_REJECTED"
            );
            expected_state = journal.request.desired_state;
            PutMode::Update(version.clone())
        } else {
            anyhow::ensure!(
                request.desired_state == RecordingLegalHoldState::On
                    && request.previous_proof_hash.is_none(),
                "RECORDING_HOLD_INITIAL_ON_REQUIRED"
            );
            PutMode::Create
        };
        // No request is allowed to act through a deletion intent, including partial deletion.
        let deletion_base = self.recording_deletion_key_for(
            &request.tenant_id,
            &request.profile_id,
            &request.session_id,
            &request.recording_id,
        );
        anyhow::ensure!(
            self.get_optional(&format!("{deletion_base}/PREPARED"))
                .await?
                .is_none()
                && self
                    .get_optional(&format!("{deletion_base}/COMMITTED"))
                    .await?
                    .is_none(),
            "RECORDING_HOLD_DELETION_ALREADY_PREPARED"
        );
        let manifest_bytes = self.get(&format!("{base}/COMMITTED")).await?;
        anyhow::ensure!(
            manifest_bytes.len() as u64 == request.manifest_bytes
                && hex_sha256(&manifest_bytes) == request.manifest_sha256,
            "RECORDING_HOLD_MANIFEST_REJECTED"
        );
        let manifest: StoredRecordingCommitMarker = serde_json::from_slice(&manifest_bytes)?;
        anyhow::ensure!(
            manifest.recording_id == request.recording_id
                && manifest.segment_count == request.segment_count,
            "RECORDING_HOLD_MANIFEST_REJECTED"
        );
        let versions = self.hold_inventory(&base, request).await?;
        let inventory_sha256 = inventory_hash(&versions)?;
        if let Some((journal, _)) = &prior {
            anyhow::ensure!(
                inventory_sha256 == journal.inventory_sha256
                    && versions.len() as u64 == journal.object_version_count,
                "RECORDING_HOLD_INVENTORY_CHANGED"
            );
        }
        // Refuse an unowned/external Hold rather than clearing it later as if it were ours.
        self.verify_hold_versions(&versions, expected_state).await?;
        let mut journal = HoldJournal {
            schema_version: 1,
            request: request.clone(),
            archive_binding_sha256: binding,
            nonce: uuid::Uuid::new_v4().to_string(),
            inventory_sha256,
            object_version_count: versions.len() as u64,
            completed_at_ms: None,
        };
        // A CAS loser never sends a Hold write. An uncertain CAS never sends one either.
        let prepared = self
            .write_hold_journal(&journal_key, &journal, mode)
            .await?;
        if expected_state != request.desired_state {
            // Disable SDK retries: a hidden earlier OFF must not outlive a successful retry.
            let client = aws_sdk_s3::Client::from_conf(
                self.version_store
                    .config()
                    .to_builder()
                    .retry_config(RetryConfig::disabled())
                    .build(),
            );
            for version in &versions {
                tokio::time::timeout(
                    self.operation_timeout,
                    client
                        .put_object_legal_hold()
                        .bucket(&self.bucket)
                        .key(&version.key)
                        .version_id(&version.version_id)
                        .legal_hold(
                            ObjectLockLegalHold::builder()
                                .status(s3_state(request.desired_state))
                                .build(),
                        )
                        .send(),
                )
                .await
                .context("RECORDING_HOLD_RESULT_UNKNOWN: Hold write deadline")?
                .context("RECORDING_HOLD_RESULT_UNKNOWN: Hold write failed")?;
            }
        }
        self.verify_hold_versions(&versions, request.desired_state)
            .await?;
        let final_inventory = self.hold_inventory(&base, request).await?;
        anyhow::ensure!(
            final_inventory == versions,
            "RECORDING_HOLD_RESULT_UNKNOWN: inventory changed"
        );
        journal.completed_at_ms =
            Some(SystemTime::now().duration_since(UNIX_EPOCH)?.as_millis() as u64);
        self.write_hold_journal(&journal_key, &journal, PutMode::Update(prepared))
            .await?;
        journal.result()
    }

    async fn hold_inventory(
        &self,
        base: &str,
        request: &RecordingLegalHoldRequest,
    ) -> anyhow::Result<Vec<RecordingObjectVersion>> {
        let expected = request
            .segment_count
            .checked_mul(2)
            .and_then(|n| n.checked_add(1))
            .ok_or_else(|| anyhow::anyhow!("RECORDING_HOLD_INVENTORY_BOUND"))?;
        let mut versions = self.recording_object_versions(base, expected * 2).await?;
        versions.sort();
        anyhow::ensure!(
            !versions.is_empty()
                && versions.windows(2).all(|pair| pair[0] != pair[1])
                && versions.iter().all(|v| !v.delete_marker
                    && !v.version_id.is_empty()
                    && v.version_id != "null"
                    && v.version_id.len() <= 1024),
            "RECORDING_HOLD_VERSIONED_INVENTORY_REQUIRED"
        );
        let keys = versions.iter().map(|v| v.key.clone()).collect::<Vec<_>>();
        self.validate_recording_object_key_subset(base, request.segment_count, &keys)?;
        anyhow::ensure!(
            keys.iter().collect::<BTreeSet<_>>().len() as u64 == expected,
            "RECORDING_HOLD_INCOMPLETE_INVENTORY"
        );
        Ok(versions)
    }

    async fn verify_hold_versions(
        &self,
        versions: &[RecordingObjectVersion],
        state: RecordingLegalHoldState,
    ) -> anyhow::Result<()> {
        let configuration = tokio::time::timeout(
            self.operation_timeout,
            self.version_store
                .get_object_lock_configuration()
                .bucket(&self.bucket)
                .send(),
        )
        .await
        .context("RECORDING_HOLD_BUCKET_CONFIGURATION_UNAVAILABLE")?
        .context("RECORDING_HOLD_BUCKET_CONFIGURATION_UNAVAILABLE")?;
        anyhow::ensure!(
            configuration
                .object_lock_configuration()
                .and_then(|c| c.object_lock_enabled())
                == Some(&aws_sdk_s3::types::ObjectLockEnabled::Enabled),
            "RECORDING_HOLD_OBJECT_LOCK_BUCKET_REQUIRED"
        );
        for version in versions {
            let response = tokio::time::timeout(
                self.operation_timeout,
                self.version_store
                    .get_object_legal_hold()
                    .bucket(&self.bucket)
                    .key(&version.key)
                    .version_id(&version.version_id)
                    .send(),
            )
            .await
            .context("RECORDING_HOLD_READBACK_UNAVAILABLE")?;
            match response {
                Ok(response) => {
                    anyhow::ensure!(
                        response.legal_hold().and_then(|hold| hold.status())
                            == Some(&s3_state(state)),
                        "RECORDING_HOLD_READBACK_MISMATCH"
                    );
                }
                Err(error)
                    if state == RecordingLegalHoldState::Off
                        && error.as_service_error().and_then(|e| e.code())
                            == Some("NoSuchObjectLockConfiguration") =>
                {
                    // MinIO returns this for an existing version with no Hold metadata.
                    // Require a Lock-enabled bucket AND exact-version existence, never any 404.
                    let head = tokio::time::timeout(
                        self.operation_timeout,
                        self.version_store
                            .head_object()
                            .bucket(&self.bucket)
                            .key(&version.key)
                            .version_id(&version.version_id)
                            .send(),
                    )
                    .await
                    .context("RECORDING_HOLD_VERSION_UNAVAILABLE")?
                    .context("RECORDING_HOLD_VERSION_UNAVAILABLE")?;
                    anyhow::ensure!(
                        head.version_id() == Some(version.version_id.as_str()),
                        "RECORDING_HOLD_VERSION_IDENTITY_REJECTED"
                    );
                }
                Err(_) => anyhow::bail!("RECORDING_HOLD_READBACK_UNAVAILABLE"),
            }
        }
        Ok(())
    }

    async fn read_hold_journal(
        &self,
        key: &Path,
    ) -> anyhow::Result<Option<(HoldJournal, UpdateVersion)>> {
        let response = match tokio::time::timeout(self.operation_timeout, self.store.get(key))
            .await
            .context("RECORDING_HOLD_JOURNAL_UNAVAILABLE")?
        {
            Ok(response) => response,
            Err(ObjectStoreError::NotFound { .. }) => return Ok(None),
            Err(_) => anyhow::bail!("RECORDING_HOLD_JOURNAL_UNAVAILABLE"),
        };
        anyhow::ensure!(
            response.meta.size <= MAX_JOURNAL_BYTES && response.meta.e_tag.is_some(),
            "RECORDING_HOLD_JOURNAL_REJECTED"
        );
        let version = UpdateVersion {
            e_tag: response.meta.e_tag.clone(),
            version: response.meta.version.clone(),
        };
        let bytes = tokio::time::timeout(self.operation_timeout, response.bytes())
            .await
            .context("RECORDING_HOLD_JOURNAL_UNAVAILABLE")??;
        anyhow::ensure!(
            bytes.len() as u64 <= MAX_JOURNAL_BYTES,
            "RECORDING_HOLD_JOURNAL_REJECTED"
        );
        Ok(Some((
            serde_json::from_slice(&bytes).context("RECORDING_HOLD_JOURNAL_REJECTED")?,
            version,
        )))
    }

    async fn write_hold_journal(
        &self,
        key: &Path,
        journal: &HoldJournal,
        mode: PutMode,
    ) -> anyhow::Result<UpdateVersion> {
        let bytes = serde_json::to_vec(journal)?;
        anyhow::ensure!(
            bytes.len() as u64 <= MAX_JOURNAL_BYTES,
            "RECORDING_HOLD_JOURNAL_BOUND"
        );
        let result = tokio::time::timeout(
            self.operation_timeout,
            self.store.put_opts(
                key,
                Bytes::from(bytes).into(),
                PutOptions {
                    mode,
                    ..Default::default()
                },
            ),
        )
        .await
        .context("RECORDING_HOLD_RESULT_UNKNOWN: journal write deadline")?
        .map_err(|_| anyhow::anyhow!("RECORDING_HOLD_RESULT_UNKNOWN: journal CAS rejected"))?;
        anyhow::ensure!(
            result.e_tag.is_some(),
            "RECORDING_HOLD_RESULT_UNKNOWN: journal CAS token missing"
        );
        Ok(result.into())
    }
}

impl HoldJournal {
    fn proof_hash(&self) -> anyhow::Result<String> {
        Ok(hex_sha256(&serde_json::to_vec(self)?))
    }
    fn result(&self) -> anyhow::Result<RecordingLegalHoldResult> {
        Ok(RecordingLegalHoldResult {
            operation_id: self.request.operation_id.clone(),
            authority_epoch: self.request.authority_epoch,
            recording_id: self.request.recording_id.clone(),
            state: self.request.desired_state,
            object_version_count: self.object_version_count,
            inventory_sha256: self.inventory_sha256.clone(),
            proof_hash: self.proof_hash()?,
            completed_at_ms: self
                .completed_at_ms
                .ok_or_else(|| anyhow::anyhow!("RECORDING_HOLD_RESULT_UNKNOWN"))?,
        })
    }
}

fn s3_state(state: RecordingLegalHoldState) -> ObjectLockLegalHoldStatus {
    match state {
        RecordingLegalHoldState::On => ObjectLockLegalHoldStatus::On,
        RecordingLegalHoldState::Off => ObjectLockLegalHoldStatus::Off,
    }
}

fn validate_request(request: &RecordingLegalHoldRequest) -> anyhow::Result<()> {
    for value in [
        &request.operation_id,
        &request.tenant_id,
        &request.profile_id,
        &request.session_id,
        &request.recording_id,
    ] {
        crate::validate_identifier("recording_hold", value)?;
    }
    anyhow::ensure!(
        request.authority_epoch > 0
            && request.manifest_bytes > 0
            && request.segment_count <= 100_000
            && is_hash(&request.manifest_sha256)
            && request.previous_proof_hash.as_deref().is_none_or(is_hash)
            && (1000..=120_000).contains(&request.execution_budget_millis),
        "RECORDING_HOLD_REQUEST_REJECTED"
    );
    Ok(())
}

fn is_hash(value: &str) -> bool {
    value.len() == 64
        && value
            .bytes()
            .all(|c| c.is_ascii_digit() || (b'a'..=b'f').contains(&c))
}

fn same_recording(left: &RecordingLegalHoldRequest, right: &RecordingLegalHoldRequest) -> bool {
    left.tenant_id == right.tenant_id
        && left.profile_id == right.profile_id
        && left.session_id == right.session_id
        && left.recording_id == right.recording_id
        && left.manifest_sha256 == right.manifest_sha256
        && left.manifest_bytes == right.manifest_bytes
        && left.segment_count == right.segment_count
}

fn inventory_hash(versions: &[RecordingObjectVersion]) -> anyhow::Result<String> {
    Ok(hex_sha256(&serde_json::to_vec(
        &versions
            .iter()
            .map(|v| (&v.key, &v.version_id))
            .collect::<Vec<_>>(),
    )?))
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::{Read, Write};
    use std::net::{SocketAddr, TcpListener, TcpStream};
    use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};

    fn request() -> RecordingLegalHoldRequest {
        RecordingLegalHoldRequest {
            operation_id: "hold-owned-1".into(),
            authority_epoch: 1,
            tenant_id: "tenant-test".into(),
            profile_id: "profile-test".into(),
            session_id: "session-test".into(),
            recording_id: format!("rec-{}", uuid::Uuid::new_v4().simple()),
            manifest_sha256: "a".repeat(64),
            manifest_bytes: 1,
            segment_count: 1,
            desired_state: RecordingLegalHoldState::On,
            previous_proof_hash: None,
            execution_budget_millis: 10_000,
        }
    }

    #[test]
    fn rejects_unscoped_or_unbounded_hold_requests() {
        let original = request();
        assert!(validate_request(&original).is_ok());
        for invalid in ["../other", "", "tenant/other", "tenant\nother"] {
            let mut r = original.clone();
            r.tenant_id = invalid.into();
            assert!(validate_request(&r).is_err());
        }
        let mut r = original.clone();
        r.authority_epoch = 0;
        assert!(validate_request(&r).is_err());
        r = original.clone();
        r.segment_count = 100_001;
        assert!(validate_request(&r).is_err());
        r = original.clone();
        r.execution_budget_millis = 120_001;
        assert!(validate_request(&r).is_err());
        r = original.clone();
        r.previous_proof_hash = Some("broken".into());
        assert!(validate_request(&r).is_err());
        r = original;
        r.manifest_sha256 = "A".repeat(64);
        assert!(validate_request(&r).is_err());
    }

    #[test]
    fn incomplete_journal_never_becomes_a_proof() {
        let mut journal = HoldJournal {
            schema_version: 1,
            request: request(),
            archive_binding_sha256: "a".repeat(64),
            nonce: uuid::Uuid::new_v4().to_string(),
            inventory_sha256: "b".repeat(64),
            object_version_count: 3,
            completed_at_ms: None,
        };
        assert!(journal.result().is_err());
        journal.completed_at_ms = Some(123);
        let proof = journal.result().unwrap().proof_hash;
        journal.request.authority_epoch += 1;
        assert_ne!(journal.result().unwrap().proof_hash, proof);
        assert!(serde_json::from_str::<HoldJournal>("{\"unexpected\":1}").is_err());
    }

    fn archive(endpoint: String) -> ObjectArchive {
        archive_with_operation_timeout(endpoint, Duration::from_millis(500))
    }

    fn archive_with_operation_timeout(
        endpoint: String,
        operation_timeout: Duration,
    ) -> ObjectArchive {
        ObjectArchive::s3(S3ArchiveConfig {
            bucket: std::env::var("TEST_OBJECT_STORAGE_BUCKET").unwrap(),
            region: "us-east-1".into(),
            endpoint,
            access_key_id: std::env::var("TEST_OBJECT_STORAGE_ACCESS_KEY_ID").unwrap(),
            secret_access_key: std::env::var("TEST_OBJECT_STORAGE_SECRET_ACCESS_KEY").unwrap(),
            prefix: "legal-hold-acceptance".into(),
            connect_timeout: Duration::from_millis(500),
            operation_timeout,
            allow_http: true,
            profile_crypto: ProfileArchiveCrypto::for_test(
                "test-key-v1",
                &[("test-key-v1", [0x41; 32])],
            ),
        })
        .unwrap()
    }

    async fn fixture(archive: &ObjectArchive) -> RecordingLegalHoldRequest {
        let mut r = request();
        let content = Bytes::from_static(br#"{"capturedAtMs":1,"cdpSessionId":7,"format":"jpeg","redactionState":"MASKED","redactedRegionCount":2,"redactionPolicyVersion":1,"data":"/9j/"}"#);
        archive
            .commit_recording_segment(
                &r.tenant_id,
                &r.profile_id,
                &r.session_id,
                &r.recording_id,
                0,
                content.clone(),
                &hex_sha256(&content),
                1,
                1,
                2,
                1,
                1,
                2,
            )
            .await
            .unwrap();
        let completed = archive
            .complete_recording(
                &r.tenant_id,
                &r.profile_id,
                &r.session_id,
                &r.recording_id,
                1,
                1,
                1,
                2,
                1,
                1,
                2,
            )
            .await
            .unwrap();
        r.manifest_sha256 = completed.manifest_sha256;
        r.manifest_bytes = completed.manifest_bytes;
        r
    }

    fn next(
        r: &RecordingLegalHoldRequest,
        proof: &RecordingLegalHoldResult,
    ) -> RecordingLegalHoldRequest {
        let mut result = r.clone();
        result.authority_epoch += 1;
        result.operation_id = format!("hold-owned-{}", result.authority_epoch);
        result.desired_state = match r.desired_state {
            RecordingLegalHoldState::On => RecordingLegalHoldState::Off,
            RecordingLegalHoldState::Off => RecordingLegalHoldState::On,
        };
        result.previous_proof_hash = Some(proof.proof_hash.clone());
        result
    }

    #[tokio::test]
    #[ignore = "requires an owned Object Lock-enabled TEST_OBJECT_STORAGE_* bucket"]
    async fn real_s3_version_hold_on_off_receipts_and_fencing() {
        let endpoint = std::env::var("TEST_OBJECT_STORAGE_ENDPOINT").unwrap();
        let archive = archive(endpoint);
        let r = fixture(&archive).await;
        // An older retained data version must be protected along with current objects.
        let base =
            archive.recording_key_for(&r.tenant_id, &r.profile_id, &r.session_id, &r.recording_id);
        let data_key = format!("{base}/segments/{:020}.ndjson", 0);
        let original = archive.get(&data_key).await.unwrap();
        archive
            .version_store
            .put_object()
            .bucket(&archive.bucket)
            .key(&data_key)
            .body(ByteStream::from(original.clone()))
            .send()
            .await
            .unwrap();
        let on = archive.apply_recording_legal_hold(r.clone()).await.unwrap();
        assert_eq!(on.object_version_count, 4);
        assert_eq!(
            archive.apply_recording_legal_hold(r.clone()).await.unwrap(),
            on
        );
        let base =
            archive.recording_key_for(&r.tenant_id, &r.profile_id, &r.session_id, &r.recording_id);
        let versions = archive.hold_inventory(&base, &r).await.unwrap();
        let denied = archive
            .version_store
            .delete_object()
            .bucket(&archive.bucket)
            .key(&versions[0].key)
            .version_id(&versions[0].version_id)
            .send()
            .await
            .unwrap_err();
        assert!(matches!(
            denied.as_service_error().and_then(|e| e.code()),
            Some("AccessDenied" | "InvalidRequest")
        ));
        archive
            .verify_hold_versions(&versions, RecordingLegalHoldState::On)
            .await
            .unwrap();
        let off_request = next(&r, &on);
        let off = archive
            .apply_recording_legal_hold(off_request.clone())
            .await
            .unwrap();
        assert_eq!(off.state, RecordingLegalHoldState::Off);
        assert!(archive
            .apply_recording_legal_hold(r.clone())
            .await
            .unwrap_err()
            .to_string()
            .contains("FENCED"));
        let on_request = next(&off_request, &off);
        let re_on = archive
            .apply_recording_legal_hold(on_request.clone())
            .await
            .unwrap();
        let final_off_request = next(&on_request, &re_on);
        archive
            .apply_recording_legal_hold(final_off_request)
            .await
            .unwrap();
        let deleted = archive
            .delete_recording(RecordingDeletionRequest {
                deletion_job_id: "owned-delete",
                deletion_epoch: 1,
                tenant_id: &r.tenant_id,
                profile_id: &r.profile_id,
                session_id: &r.session_id,
                recording_id: &r.recording_id,
                manifest_sha256: &r.manifest_sha256,
                manifest_bytes: r.manifest_bytes,
                segment_count: r.segment_count,
            })
            .await
            .unwrap();
        assert_eq!(deleted.deleted_object_count, 3);

        let other = fixture(&archive).await;
        let other_base = archive.recording_key_for(
            &other.tenant_id,
            &other.profile_id,
            &other.session_id,
            &other.recording_id,
        );
        let versions = archive.hold_inventory(&other_base, &other).await.unwrap();
        archive
            .version_store
            .put_object_legal_hold()
            .bucket(&archive.bucket)
            .key(&versions[0].key)
            .version_id(&versions[0].version_id)
            .legal_hold(
                ObjectLockLegalHold::builder()
                    .status(ObjectLockLegalHoldStatus::On)
                    .build(),
            )
            .send()
            .await
            .unwrap();
        assert!(archive
            .apply_recording_legal_hold(other)
            .await
            .unwrap_err()
            .to_string()
            .contains("READBACK_MISMATCH"));
    }

    #[tokio::test]
    #[ignore = "requires an owned Object Lock-enabled TEST_OBJECT_STORAGE_* bucket"]
    async fn real_s3_concurrent_controllers_cas_and_scope_rejections() {
        let endpoint = std::env::var("TEST_OBJECT_STORAGE_ENDPOINT").unwrap();
        let first = archive(endpoint.clone());
        let second = archive(endpoint);
        let r = fixture(&first).await;
        let mut rival = r.clone();
        rival.operation_id = "rival-controller".into();
        let (left, right) = tokio::join!(
            first.apply_recording_legal_hold(r.clone()),
            second.apply_recording_legal_hold(rival.clone())
        );
        assert_ne!(
            left.is_ok(),
            right.is_ok(),
            "Only one controller may commit an initial intent"
        );
        let (winner_request, winner) = match (left, right) {
            (Ok(proof), Err(_)) => (r, proof),
            (Err(_), Ok(proof)) => (rival, proof),
            _ => unreachable!(),
        };
        let off = next(&winner_request, &winner);
        let mut wrong_proof = off.clone();
        wrong_proof.previous_proof_hash = Some("0".repeat(64));
        assert!(second
            .apply_recording_legal_hold(wrong_proof)
            .await
            .unwrap_err()
            .to_string()
            .contains("PROOF_REJECTED"));
        let mut wrong_manifest = off.clone();
        wrong_manifest.manifest_sha256 = "0".repeat(64);
        assert!(second
            .apply_recording_legal_hold(wrong_manifest)
            .await
            .unwrap_err()
            .to_string()
            .contains("IDENTITY_REJECTED"));
        let mut wrong_tenant = off.clone();
        wrong_tenant.tenant_id = "tenant-other".into();
        assert!(second
            .apply_recording_legal_hold(wrong_tenant)
            .await
            .is_err());
        assert_eq!(
            first
                .apply_recording_legal_hold(winner_request)
                .await
                .unwrap(),
            winner
        );
        second.apply_recording_legal_hold(off).await.unwrap();
    }

    struct DeferredOffProxy {
        endpoint: String,
        arm: Arc<AtomicBool>,
        delayed: Arc<AtomicUsize>,
        forwarded: Arc<AtomicBool>,
        responses: Arc<std::sync::Mutex<Vec<(String, u16)>>>,
        stop: Arc<AtomicBool>,
        thread: Option<std::thread::JoinHandle<()>>,
    }

    impl DeferredOffProxy {
        fn start(endpoint: &str) -> Self {
            let url = reqwest::Url::parse(endpoint).unwrap();
            assert_eq!(url.scheme(), "http");
            assert_eq!(url.host_str(), Some("127.0.0.1"));
            let target: SocketAddr = format!("127.0.0.1:{}", url.port().unwrap())
                .parse()
                .unwrap();
            let listener = TcpListener::bind("127.0.0.1:0").unwrap();
            let endpoint = format!("http://{}", listener.local_addr().unwrap());
            listener.set_nonblocking(true).unwrap();
            let arm = Arc::new(AtomicBool::new(false));
            let delayed = Arc::new(AtomicUsize::new(0));
            let forwarded = Arc::new(AtomicBool::new(false));
            let responses = Arc::new(std::sync::Mutex::new(Vec::new()));
            let captured = responses.clone();
            let stop = Arc::new(AtomicBool::new(false));
            let (a, d, f, s) = (
                arm.clone(),
                delayed.clone(),
                forwarded.clone(),
                stop.clone(),
            );
            let thread = std::thread::spawn(move || {
                let mut handlers = Vec::new();
                while !s.load(Ordering::SeqCst) {
                    match listener.accept() {
                        Ok((mut stream, _)) => {
                            let (a, d, f) = (a.clone(), d.clone(), f.clone());
                            let captured = captured.clone();
                            handlers.push(std::thread::spawn(move || {
                                stream
                                    .set_read_timeout(Some(Duration::from_secs(5)))
                                    .unwrap();
                                let mut headers = Vec::new();
                                while !headers.ends_with(b"\r\n\r\n") {
                                    let mut byte = [0];
                                    if stream.read_exact(&mut byte).is_err() {
                                        return;
                                    }
                                    headers.push(byte[0]);
                                    assert!(headers.len() <= 65536);
                                }
                                let text = String::from_utf8(headers).unwrap();
                                assert!(!text
                                    .to_ascii_lowercase()
                                    .contains("transfer-encoding: chunked"));
                                let length = text
                                    .lines()
                                    .find_map(|line| {
                                        line.split_once(':')
                                            .filter(|(k, _)| {
                                                k.eq_ignore_ascii_case("content-length")
                                            })
                                            .map(|(_, v)| v.trim().parse::<usize>().unwrap())
                                    })
                                    .unwrap_or(0);
                                assert!(length <= 65536);
                                if text.to_ascii_lowercase().contains("expect: 100-continue") {
                                    let _ = stream.write_all(b"HTTP/1.1 100 Continue\r\n\r\n");
                                }
                                let mut body = vec![0; length];
                                if stream.read_exact(&mut body).is_err() {
                                    return;
                                }
                                let legal_hold_put = text.starts_with("PUT ")
                                    && text
                                        .lines()
                                        .next()
                                        .unwrap()
                                        .split_whitespace()
                                        .nth(1)
                                        .and_then(|target| target.split_once('?'))
                                        .is_some_and(|(_, query)| {
                                            query.split('&').any(|item| {
                                                item == "legal-hold" || item == "legal-hold="
                                            })
                                        });
                                let delayed = legal_hold_put && a.load(Ordering::SeqCst);
                                if delayed {
                                    d.fetch_add(1, Ordering::SeqCst);
                                    // Exceed the fault-specific 5s operation budget while allowing
                                    // ordinary proxied metadata reads to finish before injection.
                                    std::thread::sleep(Duration::from_millis(6500));
                                }
                                let mut remote =
                                    TcpStream::connect_timeout(&target, Duration::from_secs(5))
                                        .unwrap();
                                remote
                                    .set_read_timeout(Some(Duration::from_secs(5)))
                                    .unwrap();
                                for line in text.lines() {
                                    if !line.is_empty()
                                        && !line.to_ascii_lowercase().starts_with("connection:")
                                        && !line.to_ascii_lowercase().starts_with("expect:")
                                    {
                                        write!(remote, "{line}\r\n").unwrap();
                                    }
                                }
                                remote.write_all(b"Connection: close\r\n\r\n").unwrap();
                                remote.write_all(&body).unwrap();
                                // Forward even after the client has abandoned its request.
                                let mut response = Vec::new();
                                // Read one framed response; do not wait for persistent-connection EOF.
                                while !response.ends_with(b"\r\n\r\n") {
                                    let mut byte = [0];
                                    remote.read_exact(&mut byte).unwrap();
                                    response.push(byte[0]);
                                    assert!(response.len() <= 65536);
                                }
                                let header = String::from_utf8(response.clone()).unwrap();
                                let content_length = header
                                    .lines()
                                    .find_map(|line| {
                                        line.split_once(':')
                                            .filter(|(name, _)| {
                                                name.eq_ignore_ascii_case("content-length")
                                            })
                                            .map(|(_, value)| {
                                                value.trim().parse::<usize>().unwrap()
                                            })
                                    })
                                    .unwrap_or(0);
                                assert!(content_length <= 1024 * 1024);
                                if text.starts_with("HEAD ") {
                                    // HEAD advertises the object length but has no message body.
                                } else if header
                                    .to_ascii_lowercase()
                                    .contains("transfer-encoding: chunked")
                                {
                                    loop {
                                        let mut line = Vec::new();
                                        while !line.ends_with(b"\r\n") {
                                            let mut byte = [0];
                                            remote.read_exact(&mut byte).unwrap();
                                            line.push(byte[0]);
                                            assert!(line.len() <= 1024);
                                        }
                                        let size = usize::from_str_radix(
                                            String::from_utf8_lossy(&line)
                                                .trim()
                                                .split(';')
                                                .next()
                                                .unwrap(),
                                            16,
                                        )
                                        .unwrap();
                                        assert!(size <= 1024 * 1024);
                                        response.extend_from_slice(&line);
                                        if size == 0 {
                                            loop {
                                                let mut trailer = Vec::new();
                                                while !trailer.ends_with(b"\r\n") {
                                                    let mut byte = [0];
                                                    remote.read_exact(&mut byte).unwrap();
                                                    trailer.push(byte[0]);
                                                    assert!(trailer.len() <= 65536);
                                                }
                                                response.extend_from_slice(&trailer);
                                                if trailer == b"\r\n" {
                                                    break;
                                                }
                                            }
                                            break;
                                        }
                                        let mut chunk = vec![0; size + 2];
                                        remote.read_exact(&mut chunk).unwrap();
                                        assert!(chunk.ends_with(b"\r\n"));
                                        response.extend_from_slice(&chunk);
                                        assert!(response.len() <= 2 * 1024 * 1024);
                                    }
                                } else {
                                    let mut content = vec![0; content_length];
                                    remote.read_exact(&mut content).unwrap();
                                    response.extend_from_slice(&content);
                                }
                                let status = String::from_utf8_lossy(&response)
                                    .lines()
                                    .next()
                                    .and_then(|line| line.split_whitespace().nth(1))
                                    .and_then(|code| code.parse::<u16>().ok())
                                    .unwrap_or(0);
                                let category = if legal_hold_put {
                                    "hold-put"
                                } else if text.starts_with("PUT ") {
                                    "journal-put"
                                } else {
                                    "read"
                                };
                                captured.lock().unwrap().push((category.to_owned(), status));
                                if delayed {
                                    assert!(response.starts_with(b"HTTP/1.1 200"));
                                    f.store(true, Ordering::SeqCst);
                                }
                                let _ = stream.write_all(&response);
                            }));
                        }
                        Err(e) if e.kind() == std::io::ErrorKind::WouldBlock => {
                            std::thread::sleep(Duration::from_millis(5))
                        }
                        Err(e) => panic!("owned proxy accept: {e}"),
                    }
                }
                for handler in handlers {
                    handler.join().unwrap();
                }
            });
            Self {
                endpoint,
                arm,
                delayed,
                forwarded,
                responses,
                stop,
                thread: Some(thread),
            }
        }
    }

    impl Drop for DeferredOffProxy {
        fn drop(&mut self) {
            self.stop.store(true, Ordering::SeqCst);
            let result = self.thread.take().unwrap().join();
            if !std::thread::panicking() {
                result.unwrap();
            }
        }
    }

    #[tokio::test]
    #[ignore = "requires an owned Object Lock-enabled TEST_OBJECT_STORAGE_* bucket"]
    async fn real_s3_late_off_cannot_cross_an_unknown_intent() {
        let endpoint = std::env::var("TEST_OBJECT_STORAGE_ENDPOINT").unwrap();
        let direct = archive(endpoint.clone());
        let r = fixture(&direct).await;
        let proxy = DeferredOffProxy::start(&endpoint);
        let proxied =
            archive_with_operation_timeout(proxy.endpoint.clone(), Duration::from_secs(5));
        // Establish ON directly. The proxy is scoped to the OFF fault being tested.
        let on = direct.apply_recording_legal_hold(r.clone()).await.unwrap();
        proxy.arm.store(true, Ordering::SeqCst);
        let off_request = next(&r, &on);
        let off_error = proxied
            .apply_recording_legal_hold(off_request.clone())
            .await
            .unwrap_err()
            .to_string();
        assert!(
            off_error.contains("UNKNOWN"),
            "{off_error}; owned_proxy_statuses={:?}",
            proxy.responses.lock().unwrap()
        );
        // A different client/Node shares the remote journal, even while the first OFF is pending.
        let mut new_on = off_request;
        new_on.authority_epoch += 1;
        new_on.operation_id = "hold-new-on".into();
        new_on.desired_state = RecordingLegalHoldState::On;
        assert!(direct
            .apply_recording_legal_hold(new_on)
            .await
            .unwrap_err()
            .to_string()
            .contains("UNKNOWN"));
        for _ in 0..60 {
            if proxy.forwarded.load(Ordering::SeqCst) {
                break;
            }
            tokio::time::sleep(Duration::from_millis(50)).await;
        }
        assert!(proxy.forwarded.load(Ordering::SeqCst));
        assert!(proxy
            .responses
            .lock()
            .unwrap()
            .iter()
            .any(|(category, status)| category == "hold-put" && *status == 200));
        assert_eq!(
            proxy.delayed.load(Ordering::SeqCst),
            1,
            "Hold write must not be silently retried"
        );
        let base =
            direct.recording_key_for(&r.tenant_id, &r.profile_id, &r.session_id, &r.recording_id);
        let versions = direct.hold_inventory(&base, &r).await.unwrap();
        let mut off_count = 0;
        let mut on_count = 0;
        for version in versions {
            let response = direct
                .version_store
                .get_object_legal_hold()
                .bucket(&direct.bucket)
                .key(version.key)
                .version_id(version.version_id)
                .send()
                .await
                .unwrap();
            match response.legal_hold().and_then(|hold| hold.status()) {
                Some(ObjectLockLegalHoldStatus::Off) => off_count += 1,
                Some(ObjectLockLegalHoldStatus::On) => on_count += 1,
                _ => panic!("Owned fixture omitted its explicit Hold status"),
            }
        }
        assert_eq!(
            (off_count, on_count),
            (1, 2),
            "A real late OFF must reach MinIO after client timeout"
        );

        assert!(direct
            .apply_recording_legal_hold(r)
            .await
            .unwrap_err()
            .to_string()
            .contains("UNKNOWN"));
    }
}
