//! Fail-closed clients for isolated Node Helper processes.

use helper_contracts::{
    read_frame, write_frame, NetworkCommand, NetworkRequest, NetworkResponse, ObservedNetwork,
    StorageCommand, StorageRequest, StorageResponse, SCHEMA_VERSION,
};
pub use helper_contracts::{
    StorageCheckpoint, StorageEvidence, StorageRecording, StorageRestoreStatus, StorageWorkspace,
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
    ) -> anyhow::Result<(ObservedNetwork, String)> {
        let response = self
            .call(NetworkCommand::Bind {
                binding_id: binding_id.to_owned(),
                session_id: session_id.to_owned(),
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
            started_at_ms,
            ended_at_ms,
        })
        .await?
        .recording
        .ok_or_else(|| anyhow::anyhow!("storage helper omitted recording segment result"))
    }

    #[allow(clippy::too_many_arguments)]
    pub async fn complete_recording(
        &self,
        workspace: &StorageWorkspace,
        recording_id: &str,
        segment_count: u64,
        frame_count: u64,
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
}
