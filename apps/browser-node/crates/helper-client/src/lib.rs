//! Fail-closed clients for isolated Node Helper processes.

use helper_contracts::{
    read_frame, write_frame, NetworkCommand, NetworkRequest, NetworkResponse, ObservedNetwork,
    SCHEMA_VERSION,
};
use std::path::{Path, PathBuf};
use std::time::Duration;
use tokio::net::UnixStream;

#[derive(Debug, Clone)]
pub struct NetworkHelperClient {
    socket_path: PathBuf,
    timeout: Duration,
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
}
