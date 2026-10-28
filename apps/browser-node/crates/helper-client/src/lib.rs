//! Fail-closed clients for isolated Node Helper processes.

use helper_contracts::{
    read_frame, write_frame, NetworkCommand, NetworkRequest, NetworkResponse, ObservedNetwork,
    StorageCommand, StorageRequest, StorageResponse, SCHEMA_VERSION,
};
pub use helper_contracts::{
    RecordingUploadHoldScope, StorageCheckpoint, StorageEvidence, StorageEvidenceAccess,
    StorageRecording, StorageRecordingDeletion, StorageRecordingLegalHold,
    StorageRecordingLegalHoldRequest, StorageRecordingLegalHoldState,
    StorageRecordingPlaybackAccess, StorageRecordingSegmentDescriptor,
    StorageRecordingUploadHoldProof, StorageRestoreStatus, StorageWarmTierSync, StorageWorkspace,
};
use std::path::{Path, PathBuf};
use std::time::Duration;
use tokio::net::UnixStream;

#[derive(Debug, Clone)]
pub struct NetworkHelperClient {
    socket_path: PathBuf,
    timeout: Duration,
}

#[derive(Debug, Clone)]
pub struct StorageHelperClient {
    socket_path: PathBuf,
    timeout: Duration,
    workspace_root: PathBuf,
}

impl NetworkHelperClient {
    pub fn new(socket_path: PathBuf, timeout: Duration) -> anyhow::Result<Self> {
        anyhow::ensure!(
            socket_path.is_absolute(),
            "helper socket path must be absolute"
        );
        anyhow::ensure!(!timeout.is_zero(), "helper timeout must be positive");
        Ok(Self {
            socket_path,
            timeout,
        })
    }

    pub fn socket_path(&self) -> &Path {
        &self.socket_path
    }

    pub async fn ping(&self) -> anyhow::Result<()> {
        self.call(NetworkCommand::Ping).await?;
        Ok(())
    }

    pub async fn bind_proxy(
        &self,
        binding_id: &str,
        session_id: &str,
        provider_id: &str,
        expected_exit_ip: &str,
        credential_ref: &str,
    ) -> anyhow::Result<(ObservedNetwork, String)> {
        let response = self
            .call(NetworkCommand::Bind {
                binding_id: binding_id.to_owned(),
                session_id: session_id.to_owned(),
                provider_id: provider_id.to_owned(),
                expected_exit_ip: expected_exit_ip.to_owned(),
                credential_ref: credential_ref.to_owned(),
            })
            .await?;
        Ok((
            response
                .observed
                .ok_or_else(|| anyhow::anyhow!("network helper omitted observed exit"))?,
            response
                .proxy_server
                .ok_or_else(|| anyhow::anyhow!("network helper omitted proxy server"))?,
        ))
    }

    pub async fn verify_exit(&self, session_id: &str) -> anyhow::Result<ObservedNetwork> {
        self.call(NetworkCommand::Verify {
            session_id: session_id.to_owned(),
        })
        .await?
        .observed
        .ok_or_else(|| anyhow::anyhow!("network helper omitted observed exit"))
    }

    pub async fn release(&self, session_id: &str) -> anyhow::Result<()> {
        self.call(NetworkCommand::Release {
            session_id: session_id.to_owned(),
        })
        .await?;
        Ok(())
    }

    async fn call(&self, command: NetworkCommand) -> anyhow::Result<NetworkResponse> {
        let request_id = format!("hipc_{}", uuid::Uuid::new_v4().simple());
        let request = NetworkRequest {
            schema_version: SCHEMA_VERSION,
            request_id: request_id.clone(),
            command,
        };
        let future = async {
            let mut stream = UnixStream::connect(&self.socket_path).await?;
            write_frame(&mut stream, &request).await?;
            let response: NetworkResponse = read_frame(&mut stream).await?;
            anyhow::ensure!(
                response.schema_version == SCHEMA_VERSION,
                "network helper schema mismatch"
            );
            anyhow::ensure!(
                response.request_id == request_id,
                "network helper request ID mismatch"
            );
            anyhow::ensure!(
                response.ok,
                "{}: {}",
                response.error_code.as_deref().unwrap_or("HELPER_REJECTED"),
                response
                    .error_message
                    .as_deref()
                    .unwrap_or("network helper rejected request")
            );
            Ok(response)
        };
        tokio::time::timeout(self.timeout, future)
            .await
            .map_err(|_| anyhow::anyhow!("network helper request timed out"))?
    }
}

impl StorageHelperClient {
    pub fn new(
        socket_path: PathBuf,
        timeout: Duration,
        workspace_root: PathBuf,
    ) -> anyhow::Result<Self> {
        anyhow::ensure!(
            socket_path.is_absolute(),
            "helper socket path must be absolute"
        );
        anyhow::ensure!(
            workspace_root.is_absolute(),
            "storage workspace root must be absolute"
        );
        anyhow::ensure!(!timeout.is_zero(), "helper timeout must be positive");
        Ok(Self {
            socket_path,
            timeout,
            workspace_root,
        })
    }

    pub async fn ping(&self) -> anyhow::Result<()> {
        self.call(StorageCommand::Ping).await?;
        Ok(())
    }

    /// Probes the current helper, including after replacement by an older binary.
    pub async fn recording_legal_hold_supported(&self) -> anyhow::Result<bool> {
        Ok(self
            .call(StorageCommand::Ping)
            .await?
            .recording_legal_hold_supported)
    }

    pub async fn acquire_workspace(
        &self,
        tenant_id: &str,
        profile_id: &str,
        session_id: &str,
    ) -> anyhow::Result<StorageWorkspace> {
        self.acquire_workspace_at_checkpoint(tenant_id, profile_id, session_id, None)
            .await
    }

    pub async fn acquire_workspace_at_checkpoint(
        &self,
        tenant_id: &str,
        profile_id: &str,
        session_id: &str,
        checkpoint_id: Option<&str>,
    ) -> anyhow::Result<StorageWorkspace> {
        let workspace = self
            .call(StorageCommand::Acquire {
                tenant_id: tenant_id.to_owned(),
                profile_id: profile_id.to_owned(),
                session_id: session_id.to_owned(),
                checkpoint_id: checkpoint_id.map(str::to_owned),
            })
            .await?
            .workspace
            .ok_or_else(|| anyhow::anyhow!("storage helper omitted workspace"))?;
        self.validate_workspace(&workspace, tenant_id, profile_id, session_id)?;
        Ok(workspace)
    }

    pub async fn checkpoint(
        &self,
        workspace: &StorageWorkspace,
        runtime_build_id: &str,
    ) -> anyhow::Result<StorageCheckpoint> {
        self.validate_workspace(
            workspace,
            &workspace.tenant_id,
            &workspace.profile_id,
            &workspace.session_id,
        )?;
        self.call(StorageCommand::Checkpoint {
            tenant_id: workspace.tenant_id.clone(),
            profile_id: workspace.profile_id.clone(),
            session_id: workspace.session_id.clone(),
            runtime_build_id: runtime_build_id.to_owned(),
        })
        .await?
        .checkpoint
        .ok_or_else(|| anyhow::anyhow!("storage helper omitted checkpoint"))
    }

    pub async fn sync_warm_tier(
        &self,
        workspace: &StorageWorkspace,
    ) -> anyhow::Result<StorageWarmTierSync> {
        self.validate_workspace(
            workspace,
            &workspace.tenant_id,
            &workspace.profile_id,
            &workspace.session_id,
        )?;
        self.call(StorageCommand::SyncWarmTier {
            tenant_id: workspace.tenant_id.clone(),
            profile_id: workspace.profile_id.clone(),
            session_id: workspace.session_id.clone(),
        })
        .await?
        .warm_tier_sync
        .ok_or_else(|| anyhow::anyhow!("storage helper omitted Warm Tier sync result"))
    }

    #[allow(clippy::too_many_arguments)]
    pub async fn import_checkpoint(
        &self,
        tenant_id: &str,
        profile_id: &str,
        import_id: &str,
        checkpoint_id: &str,
        runtime_build_id: &str,
        archive_sha256: &str,
        archive_size_bytes: u64,
    ) -> anyhow::Result<StorageCheckpoint> {
        validate_identifier("tenant_id", tenant_id)?;
        validate_identifier("profile_id", profile_id)?;
        validate_identifier("import_id", import_id)?;
        validate_identifier("checkpoint_id", checkpoint_id)?;
        validate_identifier("runtime_build_id", runtime_build_id)?;
        anyhow::ensure!(
            archive_sha256.len() == 64
                && archive_sha256
                    .chars()
                    .all(|character| character.is_ascii_hexdigit()),
            "archive_sha256 is invalid"
        );
        self.call(StorageCommand::ImportCheckpoint {
            tenant_id: tenant_id.to_owned(),
            profile_id: profile_id.to_owned(),
            import_id: import_id.to_owned(),
            checkpoint_id: checkpoint_id.to_owned(),
            runtime_build_id: runtime_build_id.to_owned(),
            archive_sha256: archive_sha256.to_ascii_lowercase(),
            archive_size_bytes,
        })
        .await?
        .checkpoint
        .ok_or_else(|| anyhow::anyhow!("storage helper omitted imported checkpoint"))
    }

    pub async fn prepare_recording(
        &self,
        workspace: &StorageWorkspace,
        recording_id: &str,
    ) -> anyhow::Result<StorageRecording> {
        self.validate_workspace(
            workspace,
            &workspace.tenant_id,
            &workspace.profile_id,
            &workspace.session_id,
        )?;
        validate_identifier("recording_id", recording_id)?;
        self.call(StorageCommand::PrepareRecording {
            tenant_id: workspace.tenant_id.clone(),
            profile_id: workspace.profile_id.clone(),
            session_id: workspace.session_id.clone(),
            recording_id: recording_id.to_owned(),
        })
        .await?
        .recording
        .ok_or_else(|| anyhow::anyhow!("storage helper omitted recording preparation"))
    }

    #[allow(clippy::too_many_arguments)]
    pub async fn commit_recording_segment(
        &self,
        workspace: &StorageWorkspace,
        recording_id: &str,
        segment_sequence: u64,
        content_sha256: &str,
        content_bytes: u64,
        frame_count: u64,
        redacted_frame_count: u64,
        redacted_region_count: u64,
        redaction_policy_version: u32,
        started_at_ms: u64,
        ended_at_ms: u64,
    ) -> anyhow::Result<StorageRecording> {
        self.validate_workspace(
            workspace,
            &workspace.tenant_id,
            &workspace.profile_id,
            &workspace.session_id,
        )?;
        validate_identifier("recording_id", recording_id)?;
        self.call(StorageCommand::CommitRecordingSegment {
            tenant_id: workspace.tenant_id.clone(),
            profile_id: workspace.profile_id.clone(),
            session_id: workspace.session_id.clone(),
            recording_id: recording_id.to_owned(),
            segment_sequence,
            content_sha256: content_sha256.to_owned(),
            content_bytes,
            frame_count,
            redacted_frame_count,
            redacted_region_count,
            redaction_policy_version,
            started_at_ms,
            ended_at_ms,
        })
        .await?
        .recording
        .ok_or_else(|| anyhow::anyhow!("storage helper omitted recording segment result"))
    }

    pub async fn commit_recording_held_segment(
        &self,
        workspace: &StorageWorkspace,
        scope: &RecordingUploadHoldScope,
        segment: &StorageRecordingSegmentDescriptor,
    ) -> anyhow::Result<StorageRecording> {
        self.validate_workspace(
            workspace,
            &scope.tenant_id,
            &scope.profile_id,
            &scope.session_id,
        )?;
        validate_identifier("operation_id", &scope.operation_id)?;
        validate_identifier("recording_id", &scope.recording_id)?;
        let hash = |value: &str| {
            value.len() == 64
                && value
                    .bytes()
                    .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
        };
        anyhow::ensure!(
            scope.operation_id.starts_with("rua_")
                && scope.authority_epoch > 0
                && scope.placement_epoch > 0
                && segment.sequence < 100_000
                && (1..=16 * 1024 * 1024).contains(&segment.content_bytes)
                && hash(&segment.content_sha256)
                && segment.frame_count > 0
                && segment.redacted_frame_count <= segment.frame_count
                && (segment.redacted_frame_count == 0 || segment.redacted_region_count > 0)
                && segment.redaction_policy_version == 2
                && segment.ended_at_ms >= segment.started_at_ms,
            "RECORDING_UPLOAD_HOLD_REQUEST_REJECTED"
        );
        let result = self
            .call(StorageCommand::CommitRecordingHeldSegment {
                scope: scope.clone(),
                segment: segment.clone(),
            })
            .await
            .map_err(|_| anyhow::anyhow!("RECORDING_UPLOAD_HOLD_RESULT_UNAVAILABLE"))?
            .recording
            .ok_or_else(|| anyhow::anyhow!("RECORDING_UPLOAD_HOLD_PROOF_REQUIRED"))?;
        let proof = result
            .upload_hold
            .as_ref()
            .ok_or_else(|| anyhow::anyhow!("RECORDING_UPLOAD_HOLD_PROOF_REQUIRED"))?;
        anyhow::ensure!(
            result.recording_id == scope.recording_id
                && result.segment_sequence == Some(segment.sequence)
                && result.content_bytes == segment.content_bytes
                && result.frame_count == segment.frame_count
                && result.redacted_frame_count == segment.redacted_frame_count
                && result.redacted_region_count == segment.redacted_region_count
                && result.redaction_policy_version == segment.redaction_policy_version
                && !result.completed
                && result.upload_ack_sha256.is_none()
                && result.object_key.is_none()
                && result.manifest_sha256.is_none()
                && result.manifest_bytes == 0
                && proof.operation_id == scope.operation_id
                && proof.authority_epoch == scope.authority_epoch
                && proof.placement_epoch == scope.placement_epoch
                && proof.object_version_count == 2
                && hash(&proof.scope_sha256)
                && hash(&proof.proof_hash),
            "RECORDING_UPLOAD_HOLD_PROOF_REJECTED"
        );
        Ok(result)
    }

    pub async fn acknowledge_recording_held_segment(
        &self,
        workspace: &StorageWorkspace,
        ack: &helper_contracts::RecordingUploadJournalAcknowledgement,
    ) -> anyhow::Result<()> {
        let r = &ack.receipt;
        let s = &r.scope;
        let d = &r.segment;
        self.validate_workspace(workspace, &s.tenant_id, &s.profile_id, &s.session_id)?;
        anyhow::ensure!(
            r.sha256()? == ack.journal_receipt_sha256,
            "RECORDING_UPLOAD_ACK_HASH_REJECTED"
        );
        let actual = self
            .call(StorageCommand::AcknowledgeRecordingHeldSegment {
                acknowledgement: ack.clone(),
            })
            .await
            .map_err(|_| anyhow::anyhow!("RECORDING_UPLOAD_ACK_RESULT_UNAVAILABLE"))?
            .recording
            .ok_or_else(|| anyhow::anyhow!("RECORDING_UPLOAD_ACK_REQUIRED"))?;
        let expected = StorageRecording {
            recording_id: s.recording_id.clone(),
            segment_sequence: Some(d.sequence),
            object_key: None,
            content_bytes: d.content_bytes,
            frame_count: d.frame_count,
            redacted_frame_count: d.redacted_frame_count,
            redacted_region_count: d.redacted_region_count,
            redaction_policy_version: d.redaction_policy_version,
            manifest_sha256: None,
            manifest_bytes: 0,
            completed: false,
            upload_hold: Some(r.proof.clone()),
            upload_ack_sha256: Some(ack.journal_receipt_sha256.clone()),
        };
        anyhow::ensure!(actual == expected, "RECORDING_UPLOAD_ACK_RESPONSE_REJECTED");
        Ok(())
    }

    #[allow(clippy::too_many_arguments)]
    pub async fn complete_recording(
        &self,
        workspace: &StorageWorkspace,
        recording_id: &str,
        segment_count: u64,
        frame_count: u64,
        redacted_frame_count: u64,
        redacted_region_count: u64,
        redaction_policy_version: u32,
        started_at_ms: u64,
        ended_at_ms: u64,
    ) -> anyhow::Result<StorageRecording> {
        self.validate_workspace(
            workspace,
            &workspace.tenant_id,
            &workspace.profile_id,
            &workspace.session_id,
        )?;
        validate_identifier("recording_id", recording_id)?;
        self.call(StorageCommand::CompleteRecording {
            tenant_id: workspace.tenant_id.clone(),
            profile_id: workspace.profile_id.clone(),
            session_id: workspace.session_id.clone(),
            recording_id: recording_id.to_owned(),
            segment_count,
            frame_count,
            redacted_frame_count,
            redacted_region_count,
            redaction_policy_version,
            started_at_ms,
            ended_at_ms,
        })
        .await?
        .recording
        .ok_or_else(|| anyhow::anyhow!("storage helper omitted recording completion"))
    }

    #[allow(clippy::too_many_arguments)]
    pub async fn commit_evidence(
        &self,
        workspace: &StorageWorkspace,
        evidence_id: &str,
        evidence_kind: &str,
        content_sha256: &str,
        content_bytes: u64,
        captured_at_ms: u64,
    ) -> anyhow::Result<StorageEvidence> {
        self.validate_workspace(
            workspace,
            &workspace.tenant_id,
            &workspace.profile_id,
            &workspace.session_id,
        )?;
        validate_identifier("evidence_id", evidence_id)?;
        self.call(StorageCommand::CommitEvidence {
            tenant_id: workspace.tenant_id.clone(),
            profile_id: workspace.profile_id.clone(),
            session_id: workspace.session_id.clone(),
            evidence_id: evidence_id.to_owned(),
            evidence_kind: evidence_kind.to_owned(),
            content_sha256: content_sha256.to_owned(),
            content_bytes,
            captured_at_ms,
        })
        .await?
        .evidence
        .ok_or_else(|| anyhow::anyhow!("storage helper omitted evidence result"))
    }

    #[allow(clippy::too_many_arguments)]
    pub async fn sign_evidence_download(
        &self,
        tenant_id: &str,
        profile_id: &str,
        session_id: &str,
        evidence_id: &str,
        content_sha256: &str,
        content_bytes: u64,
        expires_in_seconds: u32,
    ) -> anyhow::Result<StorageEvidenceAccess> {
        validate_identifier("tenant_id", tenant_id)?;
        validate_identifier("profile_id", profile_id)?;
        validate_identifier("session_id", session_id)?;
        validate_identifier("evidence_id", evidence_id)?;
        anyhow::ensure!(
            content_sha256.len() == 64
                && content_sha256
                    .chars()
                    .all(|character| character.is_ascii_hexdigit()),
            "evidence SHA-256 is invalid"
        );
        anyhow::ensure!(
            content_bytes > 0 && content_bytes <= 8 * 1024 * 1024,
            "evidence size is invalid"
        );
        anyhow::ensure!(
            (30..=120).contains(&expires_in_seconds),
            "evidence access duration is invalid"
        );
        self.call(StorageCommand::SignEvidenceDownload {
            tenant_id: tenant_id.to_owned(),
            profile_id: profile_id.to_owned(),
            session_id: session_id.to_owned(),
            evidence_id: evidence_id.to_owned(),
            content_sha256: content_sha256.to_ascii_lowercase(),
            content_bytes,
            expires_in_seconds,
        })
        .await?
        .evidence_access
        .ok_or_else(|| anyhow::anyhow!("storage helper omitted evidence access result"))
    }

    #[allow(clippy::too_many_arguments)]
    pub async fn sign_recording_playback(
        &self,
        tenant_id: &str,
        profile_id: &str,
        session_id: &str,
        recording_id: &str,
        manifest_sha256: &str,
        manifest_bytes: u64,
        segment_count: u64,
        frame_count: u64,
        redacted_frame_count: u64,
        redacted_region_count: u64,
        redaction_policy_version: u32,
        started_at_ms: u64,
        ended_at_ms: u64,
        segment_offset: u64,
        segment_limit: u32,
        expires_in_seconds: u32,
    ) -> anyhow::Result<StorageRecordingPlaybackAccess> {
        validate_identifier("tenant_id", tenant_id)?;
        validate_identifier("profile_id", profile_id)?;
        validate_identifier("session_id", session_id)?;
        validate_identifier("recording_id", recording_id)?;
        anyhow::ensure!(
            manifest_sha256.len() == 64
                && manifest_sha256
                    .chars()
                    .all(|character| character.is_ascii_hexdigit()),
            "recording manifest SHA-256 is invalid"
        );
        anyhow::ensure!(
            manifest_bytes > 0
                && segment_offset <= segment_count
                && (1..=24).contains(&segment_limit)
                && redacted_frame_count <= frame_count
                && (1..=2).contains(&redaction_policy_version)
                && ended_at_ms >= started_at_ms
                && (30..=120).contains(&expires_in_seconds),
            "recording playback request is invalid"
        );
        self.call(StorageCommand::SignRecordingPlayback {
            tenant_id: tenant_id.to_owned(),
            profile_id: profile_id.to_owned(),
            session_id: session_id.to_owned(),
            recording_id: recording_id.to_owned(),
            manifest_sha256: manifest_sha256.to_ascii_lowercase(),
            manifest_bytes,
            segment_count,
            frame_count,
            redacted_frame_count,
            redacted_region_count,
            redaction_policy_version,
            started_at_ms,
            ended_at_ms,
            segment_offset,
            segment_limit,
            expires_in_seconds,
        })
        .await?
        .recording_playback_access
        .ok_or_else(|| anyhow::anyhow!("storage helper omitted recording playback result"))
    }

    #[allow(clippy::too_many_arguments)]
    pub async fn delete_recording(
        &self,
        deletion_job_id: &str,
        deletion_epoch: u64,
        tenant_id: &str,
        profile_id: &str,
        session_id: &str,
        recording_id: &str,
        manifest_sha256: &str,
        manifest_bytes: u64,
        segment_count: u64,
    ) -> anyhow::Result<StorageRecordingDeletion> {
        validate_identifier("deletion_job_id", deletion_job_id)?;
        validate_identifier("tenant_id", tenant_id)?;
        validate_identifier("profile_id", profile_id)?;
        validate_identifier("session_id", session_id)?;
        validate_identifier("recording_id", recording_id)?;
        anyhow::ensure!(deletion_epoch > 0, "recording deletion epoch is invalid");
        anyhow::ensure!(
            manifest_sha256.len() == 64
                && manifest_sha256
                    .chars()
                    .all(|character| character.is_ascii_hexdigit()),
            "recording manifest SHA-256 is invalid"
        );
        anyhow::ensure!(
            manifest_bytes > 0 && segment_count <= 100_000,
            "recording deletion bounds are invalid"
        );
        self.call(StorageCommand::DeleteRecording {
            deletion_job_id: deletion_job_id.to_owned(),
            deletion_epoch,
            tenant_id: tenant_id.to_owned(),
            profile_id: profile_id.to_owned(),
            session_id: session_id.to_owned(),
            recording_id: recording_id.to_owned(),
            manifest_sha256: manifest_sha256.to_ascii_lowercase(),
            manifest_bytes,
            segment_count,
        })
        .await?
        .recording_deletion
        .ok_or_else(|| anyhow::anyhow!("storage helper omitted recording deletion result"))
    }

    pub async fn apply_recording_legal_hold(
        &self,
        request: helper_contracts::StorageRecordingLegalHoldRequest,
    ) -> anyhow::Result<helper_contracts::StorageRecordingLegalHold> {
        for value in [
            &request.operation_id,
            &request.tenant_id,
            &request.profile_id,
            &request.session_id,
            &request.recording_id,
        ] {
            validate_identifier("recording_hold", value)?;
        }
        let valid_hash = |value: &str| {
            value.len() == 64
                && value
                    .bytes()
                    .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte))
        };
        anyhow::ensure!(
            request.authority_epoch > 0
                && request.manifest_bytes > 0
                && request.segment_count <= 100_000
                && valid_hash(&request.manifest_sha256)
                && request
                    .previous_proof_hash
                    .as_deref()
                    .is_none_or(valid_hash)
                && (request.desired_state != helper_contracts::StorageRecordingLegalHoldState::Off
                    || request.previous_proof_hash.is_some())
                && (1000..=120_000).contains(&request.execution_budget_millis),
            "RECORDING_HOLD_REQUEST_REJECTED"
        );
        let scoped = Self {
            timeout: Duration::from_millis(request.execution_budget_millis + 1000),
            ..self.clone()
        };
        let response = scoped
            .call(StorageCommand::ApplyRecordingLegalHold {
                request: request.clone(),
            })
            .await?
            .recording_legal_hold
            .ok_or_else(|| anyhow::anyhow!("RECORDING_HOLD_RESPONSE_MISSING"))?;
        let expected = request.segment_count.saturating_mul(2).saturating_add(1);
        anyhow::ensure!(
            response.operation_id == request.operation_id
                && response.authority_epoch == request.authority_epoch
                && response.recording_id == request.recording_id
                && response.state == request.desired_state
                && valid_hash(&response.proof_hash)
                && valid_hash(&response.inventory_sha256)
                && response.completed_at_ms > 0
                && response.object_version_count >= expected
                && response.object_version_count <= expected * 2,
            "RECORDING_HOLD_RESPONSE_REJECTED"
        );
        Ok(response)
    }

    pub async fn sign_profile_export_download(
        &self,
        tenant_id: &str,
        profile_id: &str,
        checkpoint_id: &str,
        expires_in_seconds: u32,
    ) -> anyhow::Result<helper_contracts::StorageProfileExportAccess> {
        validate_identifier("tenant_id", tenant_id)?;
        validate_identifier("profile_id", profile_id)?;
        validate_identifier("checkpoint_id", checkpoint_id)?;
        anyhow::ensure!(
            (30..=120).contains(&expires_in_seconds),
            "Profile export access duration is invalid"
        );
        self.call(StorageCommand::SignProfileExportDownload {
            tenant_id: tenant_id.to_owned(),
            profile_id: profile_id.to_owned(),
            checkpoint_id: checkpoint_id.to_owned(),
            expires_in_seconds,
        })
        .await?
        .profile_export_access
        .ok_or_else(|| anyhow::anyhow!("storage helper omitted Profile export access result"))
    }

    pub async fn release(&self, workspace: &StorageWorkspace) -> anyhow::Result<()> {
        self.validate_workspace(
            workspace,
            &workspace.tenant_id,
            &workspace.profile_id,
            &workspace.session_id,
        )?;
        self.call(StorageCommand::Release {
            tenant_id: workspace.tenant_id.clone(),
            profile_id: workspace.profile_id.clone(),
            session_id: workspace.session_id.clone(),
        })
        .await?;
        Ok(())
    }

    fn validate_workspace(
        &self,
        workspace: &StorageWorkspace,
        tenant_id: &str,
        profile_id: &str,
        session_id: &str,
    ) -> anyhow::Result<()> {
        anyhow::ensure!(
            workspace.tenant_id == tenant_id
                && workspace.profile_id == profile_id
                && workspace.session_id == session_id,
            "storage helper workspace identity mismatch"
        );
        validate_identifier("tenant_id", tenant_id)?;
        validate_identifier("profile_id", profile_id)?;
        validate_identifier("session_id", session_id)?;
        let expected = self
            .workspace_root
            .join("tenants")
            .join(tenant_id)
            .join("profiles")
            .join(profile_id)
            .join("workspaces")
            .join(session_id);
        let expected_core = expected.join("core");
        let expected_ephemeral = expected.join("ephemeral");
        anyhow::ensure!(
            Path::new(&workspace.core_dir) == expected_core
                && Path::new(&workspace.ephemeral_dir) == expected_ephemeral,
            "storage helper returned a workspace outside the allowed root"
        );
        anyhow::ensure!(
            workspace.profile_write_epoch > 0,
            "storage helper returned an invalid write epoch"
        );
        Ok(())
    }

    async fn call(&self, command: StorageCommand) -> anyhow::Result<StorageResponse> {
        let request_id = format!("hipc_{}", uuid::Uuid::new_v4().simple());
        let request = StorageRequest {
            schema_version: SCHEMA_VERSION,
            request_id: request_id.clone(),
            command,
        };
        let future = async {
            let mut stream = UnixStream::connect(&self.socket_path).await?;
            write_frame(&mut stream, &request).await?;
            let response: StorageResponse = read_frame(&mut stream).await?;
            anyhow::ensure!(
                response.schema_version == SCHEMA_VERSION,
                "storage helper schema mismatch"
            );
            anyhow::ensure!(
                response.request_id == request_id,
                "storage helper request ID mismatch"
            );
            anyhow::ensure!(
                response.ok,
                "{}: {}",
                response.error_code.as_deref().unwrap_or("HELPER_REJECTED"),
                response
                    .error_message
                    .as_deref()
                    .unwrap_or("storage helper rejected request")
            );
            Ok(response)
        };
        tokio::time::timeout(self.timeout, future)
            .await
            .map_err(|_| anyhow::anyhow!("storage helper request timed out"))?
    }
}

fn validate_identifier(name: &str, value: &str) -> anyhow::Result<()> {
    anyhow::ensure!(
        !value.is_empty()
            && value.len() <= 128
            && value.chars().all(
                |character| character.is_ascii_alphanumeric() || matches!(character, '_' | '-')
            ),
        "{name} is invalid"
    );
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use helper_contracts::{read_frame, write_frame, NetworkRequest, SCHEMA_VERSION};
    use std::sync::atomic::{AtomicU64, Ordering};
    use tokio::net::UnixListener;

    static TEST_SEQUENCE: AtomicU64 = AtomicU64::new(1);

    fn socket_path(name: &str) -> PathBuf {
        std::env::temp_dir().join(format!(
            "browsercloud-helper-client-{name}-{}-{}.sock",
            std::process::id(),
            TEST_SEQUENCE.fetch_add(1, Ordering::Relaxed)
        ))
    }

    #[tokio::test]
    async fn correlates_response_to_request() {
        let path = socket_path("correlation");
        let listener = UnixListener::bind(&path).unwrap();
        let server = tokio::spawn(async move {
            let (mut stream, _) = listener.accept().await.unwrap();
            let request: NetworkRequest = read_frame(&mut stream).await.unwrap();
            write_frame(
                &mut stream,
                &NetworkResponse {
                    schema_version: SCHEMA_VERSION,
                    request_id: format!("{}_wrong", request.request_id),
                    ok: true,
                    observed: None,
                    proxy_server: None,
                    error_code: None,
                    error_message: None,
                },
            )
            .await
            .unwrap();
        });
        let client = NetworkHelperClient::new(path.clone(), Duration::from_secs(1)).unwrap();
        let error = client.ping().await.unwrap_err();
        assert!(error.to_string().contains("request ID mismatch"));
        server.await.unwrap();
        let _ = std::fs::remove_file(path);
    }

    #[tokio::test]
    async fn times_out_when_helper_does_not_respond() {
        let path = socket_path("timeout");
        let listener = UnixListener::bind(&path).unwrap();
        let server = tokio::spawn(async move {
            let (_stream, _) = listener.accept().await.unwrap();
            tokio::time::sleep(Duration::from_secs(1)).await;
        });
        let client = NetworkHelperClient::new(path.clone(), Duration::from_millis(20)).unwrap();
        let error = client.ping().await.unwrap_err();
        assert!(error.to_string().contains("timed out"));
        server.abort();
        let _ = std::fs::remove_file(path);
    }

    #[tokio::test]
    async fn probes_each_helper_generation_and_rejects_untrusted_acknowledgements() {
        let path = socket_path("hold-probe");
        let listener = UnixListener::bind(&path).unwrap();
        let server = tokio::spawn(async move {
            for mode in [
                "supported",
                "legacy",
                "disabled",
                "wrong-id",
                "wrong-schema",
                "rejected",
            ] {
                let (mut stream, _) = listener.accept().await.unwrap();
                let request: StorageRequest = read_frame(&mut stream).await.unwrap();
                assert!(matches!(request.command, StorageCommand::Ping));
                let mut response = serde_json::json!({
                    "schemaVersion": SCHEMA_VERSION, "requestId": request.request_id, "ok": true
                });
                match mode {
                    "supported" => response["recordingLegalHoldSupported"] = true.into(),
                    "disabled" => response["recordingLegalHoldSupported"] = false.into(),
                    "wrong-id" => response["requestId"] = "unrelated".into(),
                    "wrong-schema" => response["schemaVersion"] = 99.into(),
                    "rejected" => response["ok"] = false.into(),
                    _ => {}
                }
                write_frame(&mut stream, &response).await.unwrap();
            }
        });
        let client =
            StorageHelperClient::new(path.clone(), Duration::from_secs(1), std::env::temp_dir())
                .unwrap();
        assert!(client.recording_legal_hold_supported().await.unwrap());
        assert!(!client.recording_legal_hold_supported().await.unwrap());
        assert!(!client.recording_legal_hold_supported().await.unwrap());
        for _ in 0..3 {
            assert!(client.recording_legal_hold_supported().await.is_err());
        }
        server.await.unwrap();
        let _ = std::fs::remove_file(path);
    }

    fn hold_request() -> helper_contracts::StorageRecordingLegalHoldRequest {
        helper_contracts::StorageRecordingLegalHoldRequest {
            operation_id: "hold-owned".into(),
            authority_epoch: 1,
            tenant_id: "tenant-test".into(),
            profile_id: "profile-test".into(),
            session_id: "session-test".into(),
            recording_id: "recording-test".into(),
            manifest_sha256: "a".repeat(64),
            manifest_bytes: 12,
            segment_count: 1,
            desired_state: helper_contracts::StorageRecordingLegalHoldState::On,
            previous_proof_hash: None,
            execution_budget_millis: 1000,
        }
    }

    #[tokio::test]
    async fn rejects_unbound_or_incomplete_hold_acknowledgements() {
        for mismatch in [
            "epoch",
            "state",
            "operation",
            "recording",
            "count",
            "proof",
            "inventory",
            "timestamp",
            "missing",
            "none",
        ] {
            let path = socket_path("hold-ack");
            let listener = UnixListener::bind(&path).unwrap();
            let server = tokio::spawn(async move {
                let (mut stream, _) = listener.accept().await.unwrap();
                let incoming: StorageRequest = read_frame(&mut stream).await.unwrap();
                let StorageCommand::ApplyRecordingLegalHold { request } = incoming.command else {
                    panic!("owned hold command missing")
                };
                let mut hold = helper_contracts::StorageRecordingLegalHold {
                    operation_id: request.operation_id,
                    authority_epoch: request.authority_epoch,
                    recording_id: request.recording_id,
                    state: helper_contracts::StorageRecordingLegalHoldState::On,
                    object_version_count: 3,
                    proof_hash: "b".repeat(64),
                    inventory_sha256: "c".repeat(64),
                    completed_at_ms: 123,
                };
                match mismatch {
                    "epoch" => hold.authority_epoch = 2,
                    "state" => hold.state = helper_contracts::StorageRecordingLegalHoldState::Off,
                    "operation" => hold.operation_id = "other".into(),
                    "recording" => hold.recording_id = "other".into(),
                    "count" => hold.object_version_count = 0,
                    "proof" => hold.proof_hash = "invalid".into(),
                    "inventory" => hold.inventory_sha256 = "invalid".into(),
                    "timestamp" => hold.completed_at_ms = 0,
                    _ => {}
                }
                let response = StorageResponse {
                    schema_version: SCHEMA_VERSION,
                    request_id: incoming.request_id,
                    ok: true,
                    workspace: None,
                    checkpoint: None,
                    warm_tier_sync: None,
                    recording: None,
                    evidence: None,
                    evidence_access: None,
                    recording_playback_access: None,
                    recording_deletion: None,
                    profile_export_access: None,
                    recording_legal_hold_supported: false,
                    recording_legal_hold: if mismatch == "missing" {
                        None
                    } else {
                        Some(hold)
                    },
                    error_code: None,
                    error_message: None,
                };
                write_frame(&mut stream, &response).await.unwrap();
            });
            let client = StorageHelperClient::new(
                path.clone(),
                Duration::from_secs(1),
                std::env::temp_dir(),
            )
            .unwrap();
            let result = client.apply_recording_legal_hold(hold_request()).await;
            if mismatch == "none" {
                assert!(result.is_ok());
            } else {
                assert!(result.is_err(), "mismatch accepted: {mismatch}");
            }
            server.await.unwrap();
            let _ = std::fs::remove_file(path);
        }
    }

    #[tokio::test]
    async fn rejects_invalid_hold_before_opening_a_socket() {
        let path = socket_path("hold-invalid");
        let client =
            StorageHelperClient::new(path, Duration::from_secs(1), std::env::temp_dir()).unwrap();
        let mut request = hold_request();
        request.authority_epoch = 0;
        assert!(client
            .apply_recording_legal_hold(request)
            .await
            .unwrap_err()
            .to_string()
            .contains("REQUEST_REJECTED"));
    }

    #[test]
    fn rejects_storage_workspace_path_escape() {
        let root = std::env::temp_dir().join("browsercloud-storage-client-root");
        let client = StorageHelperClient::new(
            root.join("storage.sock"),
            Duration::from_secs(1),
            root.clone(),
        )
        .unwrap();
        let workspace = StorageWorkspace {
            tenant_id: "tenant-test".to_owned(),
            profile_id: "profile-test".to_owned(),
            session_id: "session-test".to_owned(),
            core_dir: "/etc".to_owned(),
            ephemeral_dir: root.join("ephemeral").to_string_lossy().into_owned(),
            profile_write_epoch: 1,
            restored_checkpoint_id: None,
            restore_status: StorageRestoreStatus::Empty,
        };
        let error = client
            .validate_workspace(&workspace, "tenant-test", "profile-test", "session-test")
            .unwrap_err();
        assert!(error.to_string().contains("outside the allowed root"));
    }

    fn upload_fixture(
        root: &Path,
    ) -> (
        StorageWorkspace,
        RecordingUploadHoldScope,
        StorageRecordingSegmentDescriptor,
    ) {
        let scope = RecordingUploadHoldScope {
            operation_id: "rua_owned_client".into(),
            authority_epoch: 2,
            placement_epoch: 3,
            tenant_id: "tenant-upload".into(),
            profile_id: "profile-upload".into(),
            session_id: "ses_upload".into(),
            recording_id: "rec_upload".into(),
        };
        let path = root.join("tenants/tenant-upload/profiles/profile-upload/workspaces/ses_upload");
        let workspace = StorageWorkspace {
            tenant_id: scope.tenant_id.clone(),
            profile_id: scope.profile_id.clone(),
            session_id: scope.session_id.clone(),
            core_dir: path.join("core").to_string_lossy().into_owned(),
            ephemeral_dir: path.join("ephemeral").to_string_lossy().into_owned(),
            profile_write_epoch: 1,
            restored_checkpoint_id: None,
            restore_status: StorageRestoreStatus::Empty,
        };
        let segment = StorageRecordingSegmentDescriptor {
            sequence: 4,
            content_sha256: "a".repeat(64),
            content_bytes: 1200,
            frame_count: 1,
            redacted_frame_count: 1,
            redacted_region_count: 3,
            redaction_policy_version: 2,
            started_at_ms: 1,
            ended_at_ms: 2,
        };
        (workspace, scope, segment)
    }

    #[tokio::test]
    async fn held_upload_requires_exact_proof_and_never_downgrades() {
        for mismatch in [
            "valid",
            "operation",
            "authority",
            "placement",
            "count",
            "scope",
            "proof",
            "recording",
            "sequence",
            "bytes",
            "redaction",
            "completed",
            "object-key",
            "missing",
            "old-helper",
        ] {
            let path = socket_path("uh");
            let listener = UnixListener::bind(&path).unwrap();
            let server = tokio::spawn(async move {
                let (mut stream, _) = listener.accept().await.unwrap();
                let request: StorageRequest = read_frame(&mut stream).await.unwrap();
                let StorageCommand::CommitRecordingHeldSegment { scope, segment } = request.command
                else {
                    panic!("held upload was downgraded");
                };
                let mut result = serde_json::json!({ "schemaVersion": SCHEMA_VERSION,
                    "requestId": request.request_id, "ok": true,
                    "recording": { "recordingId": scope.recording_id, "segmentSequence": segment.sequence,
                        "contentBytes": segment.content_bytes, "frameCount": segment.frame_count,
                        "redactedFrameCount": segment.redacted_frame_count, "redactedRegionCount": segment.redacted_region_count,
                        "redactionPolicyVersion": segment.redaction_policy_version, "completed": false,
                        "uploadHold": { "operationId": scope.operation_id, "authorityEpoch": scope.authority_epoch,
                            "placementEpoch": scope.placement_epoch, "scopeSha256": "b".repeat(64),
                            "proofHash": "c".repeat(64), "objectVersionCount": 2 } } });
                match mismatch {
                    "operation" => {
                        result["recording"]["uploadHold"]["operationId"] = "rua_other".into()
                    }
                    "authority" => result["recording"]["uploadHold"]["authorityEpoch"] = 9.into(),
                    "placement" => result["recording"]["uploadHold"]["placementEpoch"] = 9.into(),
                    "count" => result["recording"]["uploadHold"]["objectVersionCount"] = 1.into(),
                    "scope" => result["recording"]["uploadHold"]["scopeSha256"] = "bad".into(),
                    "proof" => result["recording"]["uploadHold"]["proofHash"] = "bad".into(),
                    "recording" => result["recording"]["recordingId"] = "rec_other".into(),
                    "sequence" => result["recording"]["segmentSequence"] = 5.into(),
                    "bytes" => result["recording"]["contentBytes"] = 1.into(),
                    "redaction" => result["recording"]["redactionPolicyVersion"] = 1.into(),
                    "completed" => result["recording"]["completed"] = true.into(),
                    "object-key" => result["recording"]["objectKey"] = "unexpected".into(),
                    "missing" => result["recording"]
                        .as_object_mut()
                        .unwrap()
                        .remove("uploadHold")
                        .map(|_| ())
                        .unwrap(),
                    "old-helper" => {
                        result["ok"] = false.into();
                        result["errorCode"] = "UNKNOWN_COMMAND".into();
                    }
                    _ => {}
                }
                write_frame(&mut stream, &result).await.unwrap();
                assert!(
                    tokio::time::timeout(Duration::from_millis(40), listener.accept())
                        .await
                        .is_err(),
                    "held upload retried or fell back"
                );
            });
            let root = std::env::temp_dir();
            let client =
                StorageHelperClient::new(path.clone(), Duration::from_secs(1), root.clone())
                    .unwrap();
            let (workspace, scope, segment) = upload_fixture(&root);
            assert_eq!(
                client
                    .commit_recording_held_segment(&workspace, &scope, &segment)
                    .await
                    .is_ok(),
                mismatch == "valid"
            );
            server.await.unwrap();
            std::fs::remove_file(path).unwrap();
        }
    }

    #[tokio::test]
    async fn held_upload_rejects_wrong_workspace_and_legacy_privacy_before_connecting() {
        let root = std::env::temp_dir();
        let client = StorageHelperClient::new(
            socket_path("absent"),
            Duration::from_millis(100),
            root.clone(),
        )
        .unwrap();
        let (workspace, mut scope, mut segment) = upload_fixture(&root);
        scope.tenant_id = "other".into();
        assert!(client
            .commit_recording_held_segment(&workspace, &scope, &segment)
            .await
            .unwrap_err()
            .to_string()
            .contains("workspace identity mismatch"));
        scope.tenant_id = workspace.tenant_id.clone();
        segment.redaction_policy_version = 1;
        assert!(client
            .commit_recording_held_segment(&workspace, &scope, &segment)
            .await
            .unwrap_err()
            .to_string()
            .contains("REQUEST_REJECTED"));
    }

    #[tokio::test]
    async fn held_upload_ack_requires_exact_cleanup_receipt_without_fallback() {
        for mismatch in ["valid", "missing", "hash", "proof", "sequence", "legacy"] {
            let path = socket_path("ua");
            let listener = UnixListener::bind(&path).unwrap();
            let root = std::env::temp_dir();
            let (workspace, scope, segment) = upload_fixture(&root);
            let receipt = helper_contracts::RecordingUploadReceipt {
                schema_version: 1,
                proof: StorageRecordingUploadHoldProof {
                    operation_id: scope.operation_id.clone(),
                    authority_epoch: scope.authority_epoch,
                    placement_epoch: scope.placement_epoch,
                    scope_sha256: "a".repeat(64),
                    proof_hash: "b".repeat(64),
                    object_version_count: 2,
                },
                scope,
                segment,
            };
            let ack = helper_contracts::RecordingUploadJournalAcknowledgement {
                journal_receipt_sha256: receipt.sha256().unwrap(),
                receipt,
            };
            let server = tokio::spawn(async move {
                let (mut stream, _) = listener.accept().await.unwrap();
                let request: StorageRequest = read_frame(&mut stream).await.unwrap();
                let StorageCommand::AcknowledgeRecordingHeldSegment {
                    acknowledgement: ack,
                } = request.command
                else {
                    panic!("ACK downgraded");
                };
                let r = &ack.receipt;
                let d = &r.segment;
                let mut recording = StorageRecording {
                    recording_id: r.scope.recording_id.clone(),
                    segment_sequence: Some(d.sequence),
                    object_key: None,
                    content_bytes: d.content_bytes,
                    frame_count: d.frame_count,
                    redacted_frame_count: d.redacted_frame_count,
                    redacted_region_count: d.redacted_region_count,
                    redaction_policy_version: d.redaction_policy_version,
                    manifest_sha256: None,
                    manifest_bytes: 0,
                    completed: false,
                    upload_hold: Some(r.proof.clone()),
                    upload_ack_sha256: Some(ack.journal_receipt_sha256),
                };
                match mismatch {
                    "missing" => recording.upload_ack_sha256 = None,
                    "hash" => recording.upload_ack_sha256 = Some("d".repeat(64)),
                    "proof" => recording.upload_hold.as_mut().unwrap().proof_hash = "e".repeat(64),
                    "sequence" => recording.segment_sequence = Some(d.sequence + 1),
                    _ => {}
                }
                let response = serde_json::json!({ "schemaVersion": SCHEMA_VERSION, "requestId":request.request_id,
                    "ok": mismatch != "legacy", "recording": recording });
                write_frame(&mut stream, &response).await.unwrap();
                assert!(
                    tokio::time::timeout(Duration::from_millis(40), listener.accept())
                        .await
                        .is_err()
                );
            });
            let client =
                StorageHelperClient::new(path.clone(), Duration::from_secs(1), root).unwrap();
            assert_eq!(
                client
                    .acknowledge_recording_held_segment(&workspace, &ack)
                    .await
                    .is_ok(),
                mismatch == "valid"
            );
            server.await.unwrap();
            std::fs::remove_file(path).unwrap();
        }
    }
}
