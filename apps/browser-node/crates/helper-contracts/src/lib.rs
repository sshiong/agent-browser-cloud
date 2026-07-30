//! Fixed, bounded IPC schema shared by unprivileged Node Agent and privileged Helpers.

use anyhow::Context;
use serde::{de::DeserializeOwned, Deserialize, Serialize};
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};

pub const SCHEMA_VERSION: u16 = 1;
pub const MAX_FRAME_BYTES: usize = 64 * 1024;

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct NetworkRequest {
    pub schema_version: u16,
    pub request_id: String,
    pub command: NetworkCommand,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "SCREAMING_SNAKE_CASE")]
pub enum NetworkCommand {
    Ping,
    Bind {
        binding_id: String,
        session_id: String,
        #[serde(default)]
        provider_id: String,
        #[serde(default)]
        expected_exit_ip: String,
        #[serde(default)]
        credential_ref: String,
    },
    Verify {
        session_id: String,
    },
    Release {
        session_id: String,
    },
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct NetworkResponse {
    pub schema_version: u16,
    pub request_id: String,
    pub ok: bool,
    pub observed: Option<ObservedNetwork>,
    pub proxy_server: Option<String>,
    pub error_code: Option<String>,
    pub error_message: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct ObservedNetwork {
    pub exit_ip: String,
    pub country: String,
    pub asn: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct StorageRequest {
    pub schema_version: u16,
    pub request_id: String,
    pub command: StorageCommand,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "SCREAMING_SNAKE_CASE")]
pub enum StorageCommand {
    Ping,
    Acquire {
        tenant_id: String,
        profile_id: String,
        session_id: String,
        #[serde(default)]
        checkpoint_id: Option<String>,
    },
    Checkpoint {
        tenant_id: String,
        profile_id: String,
        session_id: String,
        runtime_build_id: String,
    },
    ImportCheckpoint {
        tenant_id: String,
        profile_id: String,
        import_id: String,
        checkpoint_id: String,
        runtime_build_id: String,
        archive_sha256: String,
        archive_size_bytes: u64,
    },
    PrepareRecording {
        tenant_id: String,
        profile_id: String,
        session_id: String,
        recording_id: String,
    },
    CommitRecordingSegment {
        tenant_id: String,
        profile_id: String,
        session_id: String,
        recording_id: String,
        segment_sequence: u64,
        content_sha256: String,
        content_bytes: u64,
        frame_count: u64,
        started_at_ms: u64,
        ended_at_ms: u64,
    },
    CompleteRecording {
        tenant_id: String,
        profile_id: String,
        session_id: String,
        recording_id: String,
        segment_count: u64,
        frame_count: u64,
        started_at_ms: u64,
        ended_at_ms: u64,
    },
    CommitEvidence {
        tenant_id: String,
        profile_id: String,
        session_id: String,
        evidence_id: String,
        evidence_kind: String,
        content_sha256: String,
        content_bytes: u64,
        captured_at_ms: u64,
    },
    Release {
        tenant_id: String,
        profile_id: String,
        session_id: String,
    },
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct StorageResponse {
    pub schema_version: u16,
    pub request_id: String,
    pub ok: bool,
    pub workspace: Option<StorageWorkspace>,
    pub checkpoint: Option<StorageCheckpoint>,
    #[serde(default)]
    pub recording: Option<StorageRecording>,
    #[serde(default)]
    pub evidence: Option<StorageEvidence>,
    pub error_code: Option<String>,
    pub error_message: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct StorageWorkspace {
    pub tenant_id: String,
    pub profile_id: String,
    pub session_id: String,
    pub core_dir: String,
    pub ephemeral_dir: String,
    pub profile_write_epoch: u64,
    pub restored_checkpoint_id: Option<String>,
    pub restore_status: StorageRestoreStatus,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum StorageRestoreStatus {
    Empty,
    TechnicalReady,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct StorageCheckpoint {
    pub checkpoint_id: String,
    pub checkpoint_epoch: u64,
    pub profile_write_epoch: u64,
    pub core_size_bytes: u64,
    pub checkpoint_file_count: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct StorageRecording {
    pub recording_id: String,
    pub segment_sequence: Option<u64>,
    pub object_key: Option<String>,
    pub content_bytes: u64,
    pub frame_count: u64,
    pub completed: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct StorageEvidence {
    pub evidence_id: String,
    pub object_key: String,
    pub content_sha256: String,
    pub content_bytes: u64,
    pub captured_at_ms: u64,
    pub committed: bool,
}

pub async fn write_frame<T, W>(writer: &mut W, value: &T) -> anyhow::Result<()>
where
    T: Serialize,
    W: AsyncWrite + Unpin,
{
    let payload = serde_json::to_vec(value)?;
    anyhow::ensure!(
        payload.len() <= MAX_FRAME_BYTES,
        "helper IPC frame exceeds maximum size"
    );
    writer.write_u32(payload.len() as u32).await?;
    writer.write_all(&payload).await?;
    writer.flush().await?;
    Ok(())
}

pub async fn read_frame<T, R>(reader: &mut R) -> anyhow::Result<T>
where
    T: DeserializeOwned,
    R: AsyncRead + Unpin,
{
    let length = reader.read_u32().await? as usize;
    anyhow::ensure!(
        length > 0 && length <= MAX_FRAME_BYTES,
        "invalid helper IPC frame length"
    );
    let mut payload = vec![0_u8; length];
    reader
        .read_exact(&mut payload)
        .await
        .context("helper IPC frame ended early")?;
    Ok(serde_json::from_slice(&payload)?)
}

#[cfg(test)]
mod tests {
    use super::*;
    use tokio::io::{duplex, AsyncWriteExt};

    #[tokio::test]
    async fn round_trips_a_versioned_bounded_frame() {
        let (mut writer, mut reader) = duplex(4096);
        let request = NetworkRequest {
            schema_version: SCHEMA_VERSION,
            request_id: "hipc_test".to_owned(),
            command: NetworkCommand::Release {
                session_id: "ses_test".to_owned(),
            },
        };
        let send = tokio::spawn(async move { write_frame(&mut writer, &request).await });
        let decoded: NetworkRequest = read_frame(&mut reader).await.unwrap();
        send.await.unwrap().unwrap();
        assert_eq!(decoded.schema_version, SCHEMA_VERSION);
        assert_eq!(decoded.request_id, "hipc_test");
        assert!(matches!(
            decoded.command,
            NetworkCommand::Release { session_id } if session_id == "ses_test"
        ));
    }

    #[tokio::test]
    async fn round_trips_a_storage_command_without_profile_content() {
        let (mut writer, mut reader) = duplex(4096);
        let request = StorageRequest {
            schema_version: SCHEMA_VERSION,
            request_id: "hipc_storage_test".to_owned(),
            command: StorageCommand::Checkpoint {
                tenant_id: "tenant-test".to_owned(),
                profile_id: "profile-test".to_owned(),
                session_id: "session-test".to_owned(),
                runtime_build_id: "runtime-test".to_owned(),
            },
        };
        let send = tokio::spawn(async move { write_frame(&mut writer, &request).await });
        let decoded: StorageRequest = read_frame(&mut reader).await.unwrap();
        send.await.unwrap().unwrap();
        assert_eq!(decoded.schema_version, SCHEMA_VERSION);
        assert!(matches!(
            decoded.command,
            StorageCommand::Checkpoint { runtime_build_id, .. }
                if runtime_build_id == "runtime-test"
        ));
    }

    #[tokio::test]
    async fn rejects_oversized_frames_before_allocation() {
        let (mut writer, mut reader) = duplex(16);
        writer
            .write_u32((MAX_FRAME_BYTES + 1) as u32)
            .await
            .unwrap();
        let result: anyhow::Result<NetworkRequest> = read_frame(&mut reader).await;
        assert!(result
            .unwrap_err()
            .to_string()
            .contains("invalid helper IPC frame length"));
    }
}
