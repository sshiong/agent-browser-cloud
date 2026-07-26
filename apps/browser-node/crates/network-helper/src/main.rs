use anyhow::Context;
use helper_contracts::{
    read_frame, write_frame, NetworkCommand, NetworkRequest, NetworkResponse, ObservedNetwork,
    SCHEMA_VERSION,
};
use network_helper::{NetworkHelper, StaticProxyConfig, StaticProxyNetworkHelper};
use nix::sys::socket::getsockopt;
use nix::unistd::Uid;
use std::os::unix::fs::{FileTypeExt, PermissionsExt};
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::Duration;
use tokio::net::{UnixListener, UnixStream};
use tracing::{error, info, warn};

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| "network_helper=info".into()),
        )
        .init();

    let socket_path = required_absolute_path("NETWORK_HELPER_SOCKET")?;
    prepare_socket_path(&socket_path).await?;
    let allowed_uid = configured_node_agent_uid()?;
    let helper = Arc::new(StaticProxyNetworkHelper::new(StaticProxyConfig {
        endpoint: required_environment("STATIC_PROXY_ENDPOINT")?,
        expected_exit_ip: required_environment("STATIC_PROXY_EXPECTED_EXIT_IP")?,
        exit_check_url: std::env::var("PROXY_EXIT_CHECK_URL")
            .unwrap_or_else(|_| "http://browsercloud.invalid/exit".to_owned()),
        failure_threshold: std::env::var("PROXY_FAILURE_THRESHOLD")
            .unwrap_or_else(|_| "3".to_owned())
            .parse()
            .context("PROXY_FAILURE_THRESHOLD must be a positive integer")?,
        open_duration: Duration::from_secs(
            std::env::var("PROXY_CIRCUIT_OPEN_SECONDS")
                .unwrap_or_else(|_| "30".to_owned())
                .parse()
                .context("PROXY_CIRCUIT_OPEN_SECONDS must be a positive integer")?,
        ),
    })?);

    let listener = UnixListener::bind(&socket_path)
        .with_context(|| format!("failed to bind {}", socket_path.display()))?;
    tokio::fs::set_permissions(&socket_path, std::fs::Permissions::from_mode(0o660)).await?;
    info!(
        socket = %socket_path.display(),
        allowed_uid,
        "network helper ready"
    );

    loop {
        let (stream, _) = listener.accept().await?;
        let helper = Arc::clone(&helper);
        tokio::spawn(async move {
            if let Err(error) = serve_connection(stream, allowed_uid, helper).await {
                warn!(error = %error, "network helper rejected connection");
            }
        });
    }
}

async fn serve_connection(
    mut stream: UnixStream,
    allowed_uid: u32,
    helper: Arc<StaticProxyNetworkHelper>,
) -> anyhow::Result<()> {
    let peer_uid = platform_peer_uid(&stream).context("cannot inspect helper peer")?;
    anyhow::ensure!(peer_uid == allowed_uid, "helper peer UID is not authorized");

    let request: NetworkRequest = read_frame(&mut stream).await?;
    let response = if request.schema_version != SCHEMA_VERSION {
        rejected(
            request.request_id,
            "SCHEMA_MISMATCH",
            "unsupported helper IPC schema version",
        )
    } else {
        match execute_request(&helper, &request.command).await {
            Ok((observed, proxy_server)) => NetworkResponse {
                schema_version: SCHEMA_VERSION,
                request_id: request.request_id,
                ok: true,
                observed,
                proxy_server,
                error_code: None,
                error_message: None,
            },
            Err(error) => {
                error!(request_id = %request.request_id, error = %error, "network operation failed");
                rejected(
                    request.request_id,
                    "NETWORK_OPERATION_FAILED",
                    "network helper operation failed",
                )
            }
        }
    };
    write_frame(&mut stream, &response).await
}

async fn execute_request(
    helper: &StaticProxyNetworkHelper,
    command: &NetworkCommand,
) -> anyhow::Result<(Option<ObservedNetwork>, Option<String>)> {
    match command {
        NetworkCommand::Ping => Ok((None, None)),
        NetworkCommand::Bind {
            binding_id,
            session_id,
        } => {
            let spec = helper.binding_spec(binding_id, session_id);
            let observed = helper.bind_proxy(spec).await?;
            Ok((
                Some(ObservedNetwork {
                    exit_ip: observed.exit_ip,
                    country: observed.country,
                    asn: observed.asn,
                }),
                Some(helper.proxy_server()),
            ))
        }
        NetworkCommand::Verify { session_id } => {
            let observed = helper.verify_exit(session_id).await?;
            Ok((
                Some(ObservedNetwork {
                    exit_ip: observed.exit_ip,
                    country: observed.country,
                    asn: observed.asn,
                }),
                None,
            ))
        }
        NetworkCommand::Release { session_id } => {
            helper.release(session_id).await?;
            Ok((None, None))
        }
    }
}

fn rejected(request_id: String, code: &str, message: &str) -> NetworkResponse {
    NetworkResponse {
        schema_version: SCHEMA_VERSION,
        request_id,
        ok: false,
        observed: None,
        proxy_server: None,
        error_code: Some(code.to_owned()),
        error_message: Some(message.to_owned()),
    }
}

fn configured_node_agent_uid() -> anyhow::Result<u32> {
    let environment = std::env::var("APP_ENVIRONMENT").unwrap_or_else(|_| "local".to_owned());
    match std::env::var("NODE_AGENT_UID") {
        Ok(value) => value.parse().context("NODE_AGENT_UID must be an integer"),
        Err(_) if environment.eq_ignore_ascii_case("production") => {
            anyhow::bail!("NODE_AGENT_UID is required in production")
        }
        Err(_) => Ok(Uid::current().as_raw()),
    }
}

#[cfg(any(target_os = "linux", target_os = "android"))]
fn platform_peer_uid(stream: &UnixStream) -> anyhow::Result<u32> {
    let credentials = getsockopt(stream, nix::sys::socket::sockopt::PeerCredentials)?;
    Ok(credentials.uid())
}

#[cfg(any(
    target_os = "macos",
    target_os = "ios",
    target_os = "freebsd",
    target_os = "dragonfly"
))]
fn platform_peer_uid(stream: &UnixStream) -> anyhow::Result<u32> {
    let credentials = getsockopt(stream, nix::sys::socket::sockopt::LocalPeerCred)?;
    Ok(credentials.uid())
}

fn required_environment(name: &str) -> anyhow::Result<String> {
    let value = std::env::var(name)
        .with_context(|| format!("{name} is required"))?
        .trim()
        .to_owned();
    anyhow::ensure!(!value.is_empty(), "{name} cannot be empty");
    Ok(value)
}

fn required_absolute_path(name: &str) -> anyhow::Result<PathBuf> {
    let path = PathBuf::from(required_environment(name)?);
    anyhow::ensure!(path.is_absolute(), "{name} must be an absolute path");
    Ok(path)
}

async fn prepare_socket_path(socket_path: &Path) -> anyhow::Result<()> {
    let parent = socket_path
        .parent()
        .ok_or_else(|| anyhow::anyhow!("helper socket must have a parent directory"))?;
    tokio::fs::create_dir_all(parent).await?;
    match tokio::fs::symlink_metadata(socket_path).await {
        Ok(metadata) => {
            anyhow::ensure!(
                metadata.file_type().is_socket(),
                "refusing to replace a non-socket helper path"
            );
            tokio::fs::remove_file(socket_path).await?;
        }
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => {}
        Err(error) => return Err(error.into()),
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicU64, Ordering};

    static TEST_SEQUENCE: AtomicU64 = AtomicU64::new(1);

    #[tokio::test]
    async fn rejects_a_peer_whose_kernel_uid_is_not_allowed() {
        let socket_path = std::env::temp_dir().join(format!(
            "browsercloud-network-helper-peer-{}-{}.sock",
            std::process::id(),
            TEST_SEQUENCE.fetch_add(1, Ordering::Relaxed)
        ));
        let listener = UnixListener::bind(&socket_path).unwrap();
        let client_path = socket_path.clone();
        let client = tokio::spawn(async move { UnixStream::connect(client_path).await.unwrap() });
        let (stream, _) = listener.accept().await.unwrap();
        let connected_client = client.await.unwrap();
        let helper = Arc::new(
            StaticProxyNetworkHelper::new(StaticProxyConfig {
                endpoint: "http://127.0.0.1:9".to_owned(),
                expected_exit_ip: "203.0.113.10".to_owned(),
                exit_check_url: "http://browsercloud.invalid/exit".to_owned(),
                failure_threshold: 1,
                open_duration: Duration::from_secs(1),
            })
            .unwrap(),
        );
        let disallowed_uid = Uid::current().as_raw().saturating_add(1);
        let error = serve_connection(stream, disallowed_uid, helper)
            .await
            .unwrap_err();
        assert!(error.to_string().contains("UID is not authorized"));
        drop(connected_client);
        drop(listener);
        let _ = std::fs::remove_file(socket_path);
    }
}
