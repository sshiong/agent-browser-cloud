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
