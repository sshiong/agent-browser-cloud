//! Cross-process append/seal CAS. No expiry or timeout can clear an active slot.
use super::*;
use object_store::UpdateVersion;

#[derive(Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct SegmentDelta {
    sequence: u64,
    request_sha256: String,
    frames: u64,
    redacted_frames: u64,
    regions: u64,
    started_at_ms: u64,
    ended_at_ms: u64,
}

#[derive(Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct WriterState {
    schema_version: u32,
    scope_sha256: String,
    revision: u64,
    nonce: String,
    committed_segments: u64,
    frames: u64,
    redacted_frames: u64,
    regions: u64,
    min_started_at_ms: Option<u64>,
    max_ended_at_ms: Option<u64>,
    active: Option<SegmentDelta>,
    sealed_manifest_sha256: Option<String>,
}

pub(super) struct WriterPermit {
    state: WriterState,
    version: UpdateVersion,
    replay_only: bool,
}

impl WriterPermit {
    pub(super) fn replay_only(&self) -> bool {
        self.replay_only
    }
}

#[derive(Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct RecordingHeldUploadFinalization {
    pub segment_count: u64,
    pub frame_count: u64,
    pub redacted_frame_count: u64,
    pub redacted_region_count: u64,
    pub redaction_policy_version: u32,
    pub started_at_ms: u64,
    pub ended_at_ms: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct RecordingHeldUploadSealProof {
    pub scope_sha256: String,
    pub manifest_sha256: String,
    pub manifest_bytes: u64,
    pub segment_count: u64,
    pub object_version_count: u64,
    pub inventory_sha256: String,
    pub proof_hash: String,
    pub completed_at_ms: u64,
}

pub struct RecordingHeldUploadSealResult {
    pub commit: RecordingCommitResult,
    pub seal: RecordingHeldUploadSealProof,
}

#[derive(Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct SealCommit {
    schema_version: u32,
    scope_sha256: String,
    manifest_sha256: String,
    manifest_bytes: u64,
    segment_count: u64,
    object_version_count: u64,
    inventory_sha256: String,
    writer_nonce: String,
    writer_revision: u64,
    completed_at_ms: u64,
}

fn hash(value: &str) -> bool {
    value.len() == 64
        && value
            .bytes()
            .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
}

fn validate_writer(state: &WriterState) -> anyhow::Result<()> {
    anyhow::ensure!(
        state.schema_version == 1
            && hash(&state.scope_sha256)
            && state.revision > 0
            && uuid::Uuid::parse_str(&state.nonce).is_ok()
            && state.committed_segments <= 100_000
            && state.redacted_frames <= state.frames
            && !(state.active.is_some() && state.sealed_manifest_sha256.is_some())
            && state
                .sealed_manifest_sha256
                .as_ref()
                .is_none_or(|s| hash(s)),
        "writer state rejected"
    );
    if state.committed_segments == 0 {
        anyhow::ensure!(
            state.frames == 0
                && state.regions == 0
                && state.min_started_at_ms.is_none()
                && state.max_ended_at_ms.is_none(),
            "empty writer counters rejected"
        );
    } else {
        anyhow::ensure!(
            state.frames > 0
                && state.min_started_at_ms.is_some()
                && state.max_ended_at_ms.is_some()
                && state.min_started_at_ms <= state.max_ended_at_ms,
            "writer counters rejected"
        );
    }
    if let Some(active) = &state.active {
        anyhow::ensure!(
            active.sequence == state.committed_segments
                && active.sequence < 100_000
                && hash(&active.request_sha256)
                && active.frames > 0
                && active.redacted_frames <= active.frames
                && active.ended_at_ms >= active.started_at_ms,
            "active writer rejected"
        );
    }
    Ok(())
}

impl ObjectArchive {
    async fn read_recording_writer(
        &self,
        base: &str,
    ) -> anyhow::Result<Option<(WriterState, UpdateVersion)>> {
        let key = Path::from(format!("{base}/WRITER"));
        let response =
            match tokio::time::timeout(self.operation_timeout, self.store.get(&key)).await? {
                Ok(value) => value,
                Err(ObjectStoreError::NotFound { .. }) => return Ok(None),
                Err(_) => anyhow::bail!("writer unavailable"),
            };
        anyhow::ensure!(
            response.meta.size <= MAX_JOURNAL_BYTES as u64 && response.meta.e_tag.is_some(),
            "writer bound or CAS token missing"
        );
        let version = UpdateVersion {
            e_tag: response.meta.e_tag.clone(),
            version: response.meta.version.clone(),
        };
        let bytes = tokio::time::timeout(self.operation_timeout, response.bytes()).await??;
        anyhow::ensure!(bytes.len() <= MAX_JOURNAL_BYTES, "writer bound");
        let state: WriterState = serde_json::from_slice(&bytes)?;
        validate_writer(&state)?;
        Ok(Some((state, version)))
    }

    async fn write_recording_writer(
        &self,
        base: &str,
        state: &WriterState,
        mode: PutMode,
    ) -> anyhow::Result<UpdateVersion> {
        validate_writer(state)?;
        let bytes = serde_json::to_vec(state)?;
        anyhow::ensure!(bytes.len() <= MAX_JOURNAL_BYTES, "writer bound");
        let result = tokio::time::timeout(
            self.operation_timeout,
            self.store.put_opts(
                &Path::from(format!("{base}/WRITER")),
                Bytes::from(bytes).into(),
                PutOptions {
                    mode,
                    ..Default::default()
                },
            ),
        )
        .await?
        .map_err(|_| anyhow::anyhow!("writer CAS result unknown"))?;
        anyhow::ensure!(result.e_tag.is_some(), "writer CAS token missing");
        Ok(result.into())
    }

    pub(super) async fn acquire_recording_upload_writer(
        &self,
        base: &str,
        scope: &str,
        marker: &RecordingSegmentMarker<'_>,
    ) -> anyhow::Result<WriterPermit> {
        let delta = SegmentDelta {
            sequence: marker.segment_sequence,
            request_sha256: hex_sha256(&serde_json::to_vec(marker)?),
            frames: marker.frame_count,
            redacted_frames: marker.redacted_frame_count,
            regions: marker.redacted_region_count,
            started_at_ms: marker.started_at_ms,
            ended_at_ms: marker.ended_at_ms,
        };
        let prior = self.read_recording_writer(base).await?;
        let (mut state, mode) = if let Some((state, version)) = prior {
            anyhow::ensure!(
                state.scope_sha256 == scope && state.sealed_manifest_sha256.is_none(),
                "writer sealed or scope fenced"
            );
            if delta.sequence < state.committed_segments {
                return Ok(WriterPermit {
                    state,
                    version,
                    replay_only: true,
                });
            }
            if let Some(active) = &state.active {
                anyhow::ensure!(
                    active.sequence == delta.sequence
                        && active.request_sha256 == delta.request_sha256,
                    "writer busy"
                );
                return Ok(WriterPermit {
                    state,
                    version,
                    replay_only: false,
                });
            }
            anyhow::ensure!(
                delta.sequence == state.committed_segments,
                "writer sequence gap"
            );
            (state, PutMode::Update(version))
        } else {
            anyhow::ensure!(delta.sequence == 0, "initial writer sequence required");
            // A deleted/hidden WRITER or orphaned v2 write intent is unknown, not
            // a fresh recording. Never recreate a lineage after its fence vanished.
            for prefix in [format!("{base}/WRITER"), format!("{base}/writes/")] {
                let history = tokio::time::timeout(
                    self.operation_timeout,
                    self.version_store
                        .list_object_versions()
                        .bucket(&self.bucket)
                        .prefix(prefix)
                        .max_keys(1)
                        .send(),
                )
                .await??;
                anyhow::ensure!(
                    history.versions().is_empty()
                        && history.delete_markers().is_empty()
                        && history.is_truncated() != Some(true),
                    "writer history is unresolved"
                );
            }
            (
                WriterState {
                    schema_version: 1,
                    scope_sha256: scope.into(),
                    revision: 0,
                    nonce: String::new(),
                    committed_segments: 0,
                    frames: 0,
                    redacted_frames: 0,
                    regions: 0,
                    min_started_at_ms: None,
                    max_ended_at_ms: None,
                    active: None,
                    sealed_manifest_sha256: None,
                },
                PutMode::Create,
            )
        };
        state.revision = state
            .revision
            .checked_add(1)
            .ok_or_else(|| anyhow::anyhow!("writer revision exhausted"))?;
        state.nonce = uuid::Uuid::new_v4().to_string();
        state.active = Some(delta);
        let version = self.write_recording_writer(base, &state, mode).await?;
        Ok(WriterPermit {
            state,
            version,
            replay_only: false,
        })
    }

    pub(super) async fn release_recording_upload_writer(
        &self,
        base: &str,
        permit: &WriterPermit,
    ) -> anyhow::Result<()> {
        if permit.replay_only {
            return Ok(());
        }
        let mut state = permit.state.clone();
        let active = state
            .active
            .take()
            .ok_or_else(|| anyhow::anyhow!("writer slot required"))?;
        state.committed_segments = state
            .committed_segments
            .checked_add(1)
            .ok_or_else(|| anyhow::anyhow!("writer count overflow"))?;
        state.frames = state
            .frames
            .checked_add(active.frames)
            .ok_or_else(|| anyhow::anyhow!("writer frames overflow"))?;
        state.redacted_frames = state
            .redacted_frames
            .checked_add(active.redacted_frames)
            .ok_or_else(|| anyhow::anyhow!("writer frames overflow"))?;
        state.regions = state
            .regions
            .checked_add(active.regions)
            .ok_or_else(|| anyhow::anyhow!("writer regions overflow"))?;
        state.min_started_at_ms = Some(
            state
                .min_started_at_ms
                .map_or(active.started_at_ms, |v| v.min(active.started_at_ms)),
        );
        state.max_ended_at_ms = Some(
            state
                .max_ended_at_ms
                .map_or(active.ended_at_ms, |v| v.max(active.ended_at_ms)),
        );
        state.revision = state
            .revision
            .checked_add(1)
            .ok_or_else(|| anyhow::anyhow!("writer revision exhausted"))?;
        state.nonce = uuid::Uuid::new_v4().to_string();
        self.write_recording_writer(base, &state, PutMode::Update(permit.version.clone()))
            .await?;
        Ok(())
    }

    pub async fn seal_recording_held_upload(
        &self,
        scope: &RecordingUploadHoldScope,
        finalization: &RecordingHeldUploadFinalization,
    ) -> anyhow::Result<RecordingHeldUploadSealResult> {
        tokio::time::timeout(
            Duration::from_secs(120),
            self.seal_recording_held_upload_inner(scope, finalization),
        )
        .await
        .map_err(|_| anyhow::anyhow!("RECORDING_UPLOAD_SEAL_UNKNOWN"))?
        .map_err(|_| anyhow::anyhow!("RECORDING_UPLOAD_SEAL_REJECTED_OR_UNKNOWN"))
    }

    async fn seal_recording_held_upload_inner(
        &self,
        scope: &RecordingUploadHoldScope,
        f: &RecordingHeldUploadFinalization,
    ) -> anyhow::Result<RecordingHeldUploadSealResult> {
        for value in [
            &scope.tenant_id,
            &scope.profile_id,
            &scope.session_id,
            &scope.recording_id,
            &scope.operation_id,
        ] {
            crate::validate_identifier("upload_seal", value)?;
        }
        anyhow::ensure!(
            scope.operation_id.starts_with("rua_")
                && scope.authority_epoch > 0
                && scope.placement_epoch > 0
                && (1..=100_000).contains(&f.segment_count)
                && f.frame_count > 0
                && f.redacted_frame_count <= f.frame_count
                && (f.redacted_frame_count == 0 || f.redacted_region_count > 0)
                && f.redaction_policy_version == 2
                && f.ended_at_ms >= f.started_at_ms,
            "seal request rejected"
        );
        let journal = format!(
            "{}/UPLOAD_HOLD",
            self.recording_deletion_key_for(
                &scope.tenant_id,
                &scope.profile_id,
                &scope.session_id,
                &scope.recording_id
            )
        );
        let authority = UploadAuthority {
            schema_version: 2,
            scope: scope.clone(),
            archive_binding_sha256: hex_sha256(&serde_json::to_vec(&(&self.bucket, &self.prefix))?),
        };
        let bytes = serde_json::to_vec(&authority)?;
        anyhow::ensure!(
            self.read_upload_journal(&Path::from(format!("{journal}/AUTHORITY")))
                .await?
                .as_ref()
                == bytes,
            "seal requires v2 authority"
        );
        let scope_hash = hex_sha256(&bytes);
        self.reject_upload_seal_mutation_intents(scope).await?;
        let manifest = Bytes::from(serde_json::to_vec(&RecordingCommitMarker {
            recording_id: &scope.recording_id,
            segment_count: f.segment_count,
            frame_count: f.frame_count,
            redacted_frame_count: f.redacted_frame_count,
            redacted_region_count: f.redacted_region_count,
            redaction_policy_version: f.redaction_policy_version,
            started_at_ms: f.started_at_ms,
            ended_at_ms: f.ended_at_ms,
        })?);
        let manifest_hash = hex_sha256(&manifest);
        let (mut state, version) = self
            .read_recording_writer(&journal)
            .await?
            .ok_or_else(|| anyhow::anyhow!("writer required"))?;
        anyhow::ensure!(
            state.scope_sha256 == scope_hash
                && state.active.is_none()
                && state.committed_segments == f.segment_count
                && state.frames == f.frame_count
                && state.redacted_frames == f.redacted_frame_count
                && state.regions == f.redacted_region_count
                && state
                    .min_started_at_ms
                    .is_some_and(|v| f.started_at_ms <= v)
                && state.max_ended_at_ms.is_some_and(|v| f.ended_at_ms >= v),
            "writer busy or final counters changed"
        );
        if let Some(sealed) = &state.sealed_manifest_sha256 {
            anyhow::ensure!(*sealed == manifest_hash, "seal request immutable");
        } else {
            state.sealed_manifest_sha256 = Some(manifest_hash.clone());
            state.revision = state
                .revision
                .checked_add(1)
                .ok_or_else(|| anyhow::anyhow!("writer revision exhausted"))?;
            state.nonce = uuid::Uuid::new_v4().to_string();
            self.write_recording_writer(&journal, &state, PutMode::Update(version))
                .await?;
        }
        let base = self.recording_key_for(
            &scope.tenant_id,
            &scope.profile_id,
            &scope.session_id,
            &scope.recording_id,
        );
        let key = format!("{base}/COMMITTED");
        self.put_held_upload_object(&journal, &scope_hash, &key, manifest.clone(), false)
            .await?;
        let mut expected = Vec::new();
        let mut frames = 0u64;
        let mut redacted = 0u64;
        let mut regions = 0u64;
        for sequence in 0..f.segment_count {
            let data_key = format!("{base}/segments/{sequence:020}.ndjson");
            let marker_key = format!("{base}/segments/{sequence:020}.COMMITTED");
            let (data, data_id) = self
                .verify_owned_upload_commit(&journal, &scope_hash, &data_key)
                .await?;
            let (marker, marker_id) = self
                .verify_owned_upload_commit(&journal, &scope_hash, &marker_key)
                .await?;
            anyhow::ensure!(
                marker.content_bytes <= MAX_JOURNAL_BYTES as u64,
                "marker bound"
            );
            let object = tokio::time::timeout(
                self.operation_timeout,
                self.version_store
                    .get_object()
                    .bucket(&self.bucket)
                    .key(&marker_key)
                    .version_id(&marker_id)
                    .send(),
            )
            .await??;
            anyhow::ensure!(
                object.content_length() == Some(marker.content_bytes as i64),
                "marker size changed"
            );
            let bytes = tokio::time::timeout(self.operation_timeout, object.body.collect())
                .await??
                .into_bytes();
            anyhow::ensure!(
                bytes.len() as u64 == marker.content_bytes
                    && hex_sha256(&bytes) == marker.content_sha256,
                "marker changed"
            );
            let stored: StoredRecordingSegmentMarker = serde_json::from_slice(&bytes)?;
            anyhow::ensure!(
                stored.recording_id == scope.recording_id
                    && stored.segment_sequence == sequence
                    && stored.content_sha256 == data.content_sha256
                    && stored.content_bytes == data.content_bytes
                    && stored.redaction_policy_version == 2
                    && stored.frame_count > 0
                    && stored.redacted_frame_count <= stored.frame_count
                    && stored.started_at_ms >= f.started_at_ms
                    && stored.ended_at_ms <= f.ended_at_ms
                    && stored.ended_at_ms >= stored.started_at_ms,
                "owned marker rejected"
            );
            frames = frames
                .checked_add(stored.frame_count)
                .ok_or_else(|| anyhow::anyhow!("frame overflow"))?;
            redacted = redacted
                .checked_add(stored.redacted_frame_count)
                .ok_or_else(|| anyhow::anyhow!("frame overflow"))?;
            regions = regions
                .checked_add(stored.redacted_region_count)
                .ok_or_else(|| anyhow::anyhow!("region overflow"))?;
            expected.push(RecordingObjectVersion {
                key: data_key,
                version_id: data_id,
                delete_marker: false,
            });
            expected.push(RecordingObjectVersion {
                key: marker_key,
                version_id: marker_id,
                delete_marker: false,
            });
        }
        anyhow::ensure!(
            (frames, redacted, regions)
                == (
                    f.frame_count,
                    f.redacted_frame_count,
                    f.redacted_region_count
                ),
            "final counters rejected"
        );
        let (_, id) = self
            .verify_owned_upload_commit(&journal, &scope_hash, &key)
            .await?;
        expected.push(RecordingObjectVersion {
            key: key.clone(),
            version_id: id,
            delete_marker: false,
        });
        expected.sort();
        let mut actual = self
            .recording_object_versions(&base, f.segment_count * 2 + 1)
            .await?;
        actual.sort();
        anyhow::ensure!(actual == expected, "unowned versions or inventory changed");
        let inventory_hash = hex_sha256(&serde_json::to_vec(
            &expected
                .iter()
                .map(|v| (&v.key, &v.version_id))
                .collect::<Vec<_>>(),
        )?);
        let proof_key = format!("{journal}/SEALED");
        self.reject_upload_seal_mutation_intents(scope).await?;
        let commit = if let Some(existing) = self
            .read_upload_journal_optional(&Path::from(proof_key.as_str()))
            .await?
        {
            anyhow::ensure!(existing.len() <= MAX_JOURNAL_BYTES, "seal bound");
            let proof: SealCommit = serde_json::from_slice(&existing)?;
            anyhow::ensure!(
                proof.schema_version == 1
                    && proof.scope_sha256 == scope_hash
                    && proof.manifest_sha256 == manifest_hash
                    && proof.manifest_bytes == manifest.len() as u64
                    && proof.segment_count == f.segment_count
                    && proof.object_version_count == expected.len() as u64
                    && proof.inventory_sha256 == inventory_hash
                    && proof.writer_nonce == state.nonce
                    && proof.writer_revision == state.revision
                    && proof.completed_at_ms > 0,
                "seal proof rejected"
            );
            proof
        } else {
            let proof = SealCommit {
                schema_version: 1,
                scope_sha256: scope_hash,
                manifest_sha256: manifest_hash.clone(),
                manifest_bytes: manifest.len() as u64,
                segment_count: f.segment_count,
                object_version_count: expected.len() as u64,
                inventory_sha256: inventory_hash,
                writer_nonce: state.nonce,
                writer_revision: state.revision,
                completed_at_ms: u64::try_from(
                    SystemTime::now().duration_since(UNIX_EPOCH)?.as_millis(),
                )?,
            };
            let mut final_inventory = self
                .recording_object_versions(&base, f.segment_count * 2 + 1)
                .await?;
            final_inventory.sort();
            anyhow::ensure!(final_inventory == expected, "seal inventory changed");
            self.put_upload_journal_immutable(&proof_key, Bytes::from(serde_json::to_vec(&proof)?))
                .await?;
            proof
        };
        let proof_hash = hex_sha256(&serde_json::to_vec(&commit)?);
        Ok(RecordingHeldUploadSealResult {
            commit: RecordingCommitResult {
                object_key: key,
                manifest_sha256: manifest_hash,
                manifest_bytes: manifest.len() as u64,
            },
            seal: RecordingHeldUploadSealProof {
                scope_sha256: commit.scope_sha256,
                manifest_sha256: commit.manifest_sha256,
                manifest_bytes: commit.manifest_bytes,
                segment_count: commit.segment_count,
                object_version_count: commit.object_version_count,
                inventory_sha256: commit.inventory_sha256,
                proof_hash,
                completed_at_ms: commit.completed_at_ms,
            },
        })
    }

    async fn verify_owned_upload_commit(
        &self,
        journal: &str,
        scope: &str,
        key: &str,
    ) -> anyhow::Result<(UploadIntent, String)> {
        let key_hash = hex_sha256(key.as_bytes());
        let bytes = self
            .read_upload_journal(&Path::from(format!(
                "{journal}/writes/{key_hash}/COMMITTED"
            )))
            .await?;
        let (intent, id): (UploadIntent, String) = serde_json::from_slice(&bytes)?;
        anyhow::ensure!(
            intent.schema_version == 1
                && intent.scope_sha256 == scope
                && intent.key_sha256 == key_hash
                && hash(&intent.content_sha256)
                && (1..=MAX_BYTES as u64).contains(&intent.content_bytes)
                && uuid::Uuid::parse_str(&intent.nonce).is_ok()
                && !id.is_empty()
                && id != "null"
                && id.len() <= 1024,
            "owned version rejected"
        );
        let prepared = self
            .read_upload_journal(&Path::from(format!("{journal}/writes/{key_hash}/PREPARED")))
            .await?;
        anyhow::ensure!(
            prepared.as_ref() == serde_json::to_vec(&intent)?,
            "owned intent changed"
        );
        let head = tokio::time::timeout(
            self.operation_timeout,
            self.version_store
                .head_object()
                .bucket(&self.bucket)
                .key(key)
                .version_id(&id)
                .send(),
        )
        .await??;
        let metadata = head
            .metadata()
            .ok_or_else(|| anyhow::anyhow!("ownership missing"))?;
        anyhow::ensure!(
            head.version_id() == Some(id.as_str())
                && head.content_length() == Some(intent.content_bytes as i64)
                && metadata.get("recording-upload-nonce") == Some(&intent.nonce)
                && metadata.get("recording-upload-scope").map(String::as_str) == Some(scope)
                && metadata.get("recording-upload-sha256") == Some(&intent.content_sha256),
            "owned version identity changed"
        );
        let hold = tokio::time::timeout(
            self.operation_timeout,
            self.version_store
                .get_object_legal_hold()
                .bucket(&self.bucket)
                .key(key)
                .version_id(&id)
                .send(),
        )
        .await??;
        anyhow::ensure!(
            hold.legal_hold().and_then(|h| h.status()) == Some(&ObjectLockLegalHoldStatus::On),
            "owned version not ON"
        );
        Ok((intent, id))
    }

    async fn reject_upload_seal_mutation_intents(
        &self,
        scope: &RecordingUploadHoldScope,
    ) -> anyhow::Result<()> {
        let base = self.recording_deletion_key_for(
            &scope.tenant_id,
            &scope.profile_id,
            &scope.session_id,
            &scope.recording_id,
        );
        for suffix in ["PREPARED", "COMMITTED", "LEGAL_HOLD"] {
            anyhow::ensure!(
                self.get_optional(&format!("{base}/{suffix}"))
                    .await?
                    .is_none(),
                "seal mutation already prepared"
            );
        }
        Ok(())
    }
}
