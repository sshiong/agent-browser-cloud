use anyhow::Context;
use helper_contracts::{
    read_frame, write_frame, NetworkCommand, NetworkRequest, NetworkResponse, ObservedNetwork,
    SCHEMA_VERSION,
};
use network_helper::{NetworkHelper, StaticProxyConfig, StaticProxyNetworkHelper};
use nix::sys::socket::getsockopt;
use nix::unistd::Uid;
use serde::Deserialize;
use std::collections::{HashMap, HashSet};
use std::io::Read as _;
use std::os::unix::fs::{FileTypeExt, OpenOptionsExt, PermissionsExt};
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
    let helper = Arc::new(StaticProxyNetworkHelper::new_many(load_provider_configs()?)?);

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
            provider_id,
            expected_exit_ip,
            credential_ref,
        } => {
            let spec = helper.binding_spec(
                binding_id,
                session_id,
                provider_id,
                expected_exit_ip,
                credential_ref,
            )?;
            let observed = helper.bind_proxy(spec).await?;
            Ok((
                Some(ObservedNetwork {
                    exit_ip: observed.exit_ip,
                    country: observed.country,
                    asn: observed.asn,
                }),
                Some(helper.proxy_server_for(session_id).await?),
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
        Err(_) if environment != "local" && environment != "test" => {
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

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct ProviderConfigDocument {
    version: u32,
    providers: Vec<ProviderConfigEntry>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct ProviderConfigEntry {
    provider_id: String,
    endpoint: String,
    expected_exit_ip: String,
    #[serde(default)]
    credential_ref: String,
    #[serde(default)]
    exit_check_url: Option<String>,
    // Control-plane-only routing metadata. Network Helper validates the shared catalog shape but
    // never uses economics to establish or authorize an egress connection.
    #[serde(default)]
    regions: Vec<String>,
    #[serde(default)]
    cost_per_gib_usd: Option<serde_json::Number>,
    #[serde(default)]
    reputation_score: Option<u32>,
    #[serde(default)]
    max_concurrent_sessions: Option<u32>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct CredentialConfigDocument {
    version: u32,
    credentials: Vec<CredentialConfigEntry>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct CredentialConfigEntry {
    provider_id: String,
    credential_ref: String,
    credential_file: PathBuf,
}

fn load_provider_configs() -> anyhow::Result<Vec<StaticProxyConfig>> {
    let failure_threshold = std::env::var("PROXY_FAILURE_THRESHOLD")
        .unwrap_or_else(|_| "3".to_owned())
        .parse()
        .context("PROXY_FAILURE_THRESHOLD must be a positive integer")?;
    let open_duration = Duration::from_secs(
        std::env::var("PROXY_CIRCUIT_OPEN_SECONDS")
            .unwrap_or_else(|_| "30".to_owned())
            .parse()
            .context("PROXY_CIRCUIT_OPEN_SECONDS must be a positive integer")?,
    );
    let default_exit_check_url = std::env::var("PROXY_EXIT_CHECK_URL")
        .unwrap_or_else(|_| "http://browsercloud.invalid/exit".to_owned());

    let Ok(config_path) = std::env::var("PROXY_PROVIDER_CONFIG_FILE") else {
        return Ok(vec![StaticProxyConfig {
            provider_id: std::env::var("STATIC_PROXY_PROVIDER_ID")
                .unwrap_or_else(|_| "static-local".to_owned()),
            endpoint: required_environment("STATIC_PROXY_ENDPOINT")?,
            expected_exit_ip: required_environment("STATIC_PROXY_EXPECTED_EXIT_IP")?,
            credential_ref: std::env::var("STATIC_PROXY_CREDENTIAL_REF").unwrap_or_default(),
            credential_file: std::env::var("STATIC_PROXY_CREDENTIAL_FILE")
                .ok()
                .filter(|value| !value.trim().is_empty())
                .map(PathBuf::from),
            exit_check_url: default_exit_check_url,
            failure_threshold,
            open_duration,
        }]);
    };

    let path = PathBuf::from(config_path);
    let body = read_private_config(&path, "proxy provider config", 1024 * 1024)?;
    let document: ProviderConfigDocument =
        serde_json::from_slice(&body).context("proxy provider config is invalid JSON")?;
    anyhow::ensure!(
        document.version == 1,
        "unsupported proxy provider config version"
    );
    anyhow::ensure!(
        !document.providers.is_empty() && document.providers.len() <= 256,
        "proxy provider config must contain between 1 and 256 providers"
    );
    let credential_files = load_credential_files()?;
    let mut matched_credentials = HashSet::new();
    let configs = document
        .providers
        .into_iter()
        .map(|provider| {
            let _routing_metadata = (
                provider.regions,
                provider.cost_per_gib_usd,
                provider.reputation_score,
                provider.max_concurrent_sessions,
            );
            let credential_key = (
                provider.provider_id.clone(),
                provider.credential_ref.clone(),
            );
            let credential_file = credential_files.get(&credential_key).cloned();
            if credential_file.is_some() {
                matched_credentials.insert(credential_key);
            }
            StaticProxyConfig {
                provider_id: provider.provider_id,
                endpoint: provider.endpoint,
                expected_exit_ip: provider.expected_exit_ip,
                credential_ref: provider.credential_ref,
                credential_file,
                exit_check_url: provider
                    .exit_check_url
                    .unwrap_or_else(|| default_exit_check_url.clone()),
                failure_threshold,
                open_duration,
            }
        })
        .collect::<Vec<_>>();
    anyhow::ensure!(
        matched_credentials.len() == credential_files.len(),
        "proxy credential config contains an entry that does not match the provider catalog"
    );
    Ok(configs)
}

fn load_credential_files() -> anyhow::Result<HashMap<(String, String), PathBuf>> {
    let Ok(config_path) = std::env::var("PROXY_CREDENTIAL_CONFIG_FILE") else {
        return Ok(HashMap::new());
    };
    let path = PathBuf::from(config_path);
    let body = read_private_config(&path, "proxy credential config", 1024 * 1024)?;
    parse_credential_files(&body)
}

fn parse_credential_files(body: &[u8]) -> anyhow::Result<HashMap<(String, String), PathBuf>> {
    let document: CredentialConfigDocument =
        serde_json::from_slice(body).context("proxy credential config is invalid JSON")?;
    anyhow::ensure!(
        document.version == 1,
        "unsupported proxy credential config version"
    );
    anyhow::ensure!(
        !document.credentials.is_empty() && document.credentials.len() <= 256,
        "proxy credential config must contain between 1 and 256 credentials"
    );
    let mut credential_files = HashMap::new();
    for credential in document.credentials {
        anyhow::ensure!(
            !credential.provider_id.trim().is_empty(),
            "proxy credential providerId cannot be empty"
        );
        anyhow::ensure!(
            !credential.credential_ref.trim().is_empty(),
            "proxy credential credentialRef cannot be empty"
        );
        anyhow::ensure!(
            credential.credential_file.is_absolute(),
            "proxy credentialFile must be an absolute path"
        );
        let key = (credential.provider_id, credential.credential_ref);
        anyhow::ensure!(
            credential_files
                .insert(key, credential.credential_file)
                .is_none(),
            "proxy credential config contains a duplicate providerId/credentialRef"
        );
    }
    Ok(credential_files)
}

fn read_private_config(path: &Path, label: &str, maximum_bytes: u64) -> anyhow::Result<Vec<u8>> {
    anyhow::ensure!(path.is_absolute(), "{label} path must be absolute");
    let file = std::fs::OpenOptions::new()
        .read(true)
        .custom_flags(nix::libc::O_NOFOLLOW | nix::libc::O_CLOEXEC)
        .open(path)
        .with_context(|| format!("cannot securely open {label} {}", path.display()))?;
    let metadata = file
        .metadata()
        .with_context(|| format!("cannot inspect {label} {}", path.display()))?;
    anyhow::ensure!(
        metadata.file_type().is_file(),
        "{label} must be a regular file, not a symlink"
    );
    anyhow::ensure!(metadata.len() > 0, "{label} cannot be empty");
    anyhow::ensure!(
        metadata.len() <= maximum_bytes,
        "{label} exceeds its size limit"
    );
    anyhow::ensure!(
        metadata.permissions().mode() & 0o037 == 0,
        "{label} must allow at most owner read/write and group read"
    );
    let mut body = Vec::with_capacity(metadata.len() as usize);
    file.take(maximum_bytes + 1)
        .read_to_end(&mut body)
        .with_context(|| format!("cannot read {label} {}", path.display()))?;
    anyhow::ensure!(
        body.len() as u64 <= maximum_bytes,
        "{label} grew while reading"
    );
    Ok(body)
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

    #[test]
    fn accepts_control_plane_routing_metadata_in_the_shared_provider_catalog() {
        let document: ProviderConfigDocument = serde_json::from_str(
            r#"{
              "version": 1,
              "providers": [{
                "providerId": "provider-a",
                "endpoint": "http://127.0.0.1:8080",
                "expectedExitIp": "203.0.113.10",
                "credentialRef": "vault://tenant/proxy/a",
                "regions": ["singapore"],
                "costPerGibUsd": 0.1250,
                "reputationScore": 91,
                "maxConcurrentSessions": 400
              }]
            }"#,
        )
        .unwrap();

        assert_eq!(document.providers[0].regions, ["singapore"]);
        assert_eq!(document.providers[0].reputation_score, Some(91));
        assert_eq!(document.providers[0].max_concurrent_sessions, Some(400));
        assert!(document.providers[0].cost_per_gib_usd.is_some());
    }

    #[test]
    fn keeps_commercial_proxy_credentials_out_of_the_shared_provider_catalog() {
        let catalog = serde_json::from_str::<ProviderConfigDocument>(
            r#"{
              "version": 1,
              "providers": [{
                "providerId": "provider-a",
                "endpoint": "http://proxy.example.test:8080",
                "expectedExitIp": "203.0.113.10",
                "credentialRef": "vault://tenant/proxy/a"
              }]
            }"#,
        )
        .unwrap();
        let credentials = parse_credential_files(
            br#"{
              "version": 1,
              "credentials": [{
                "providerId": "provider-a",
                "credentialRef": "vault://tenant/proxy/a",
                "credentialFile": "/run/browsercloud/proxy-credentials/provider-a.json"
              }]
            }"#,
        )
        .unwrap();

        assert_eq!(catalog.providers.len(), 1);
        assert_eq!(
            credentials.get(&("provider-a".to_owned(), "vault://tenant/proxy/a".to_owned())),
            Some(&PathBuf::from(
                "/run/browsercloud/proxy-credentials/provider-a.json"
            ))
        );
        assert!(serde_json::from_str::<ProviderConfigDocument>(
            r#"{
              "version": 1,
              "providers": [{
                "providerId": "provider-a",
                "endpoint": "http://proxy.example.test:8080",
                "expectedExitIp": "203.0.113.10",
                "credentialFile": "/secret.json"
              }]
            }"#
        )
        .is_err());
    }

    #[test]
    fn rejects_duplicate_or_relative_commercial_proxy_credential_mappings() {
        let duplicate = br#"{
          "version": 1,
          "credentials": [
            {"providerId":"provider-a","credentialRef":"ref-a","credentialFile":"/a.json"},
            {"providerId":"provider-a","credentialRef":"ref-a","credentialFile":"/b.json"}
          ]
        }"#;
        assert!(parse_credential_files(duplicate)
            .unwrap_err()
            .to_string()
            .contains("duplicate"));

        let relative = br#"{
          "version": 1,
          "credentials": [
            {"providerId":"provider-a","credentialRef":"ref-a","credentialFile":"a.json"}
          ]
        }"#;
        assert!(parse_credential_files(relative)
            .unwrap_err()
            .to_string()
            .contains("absolute"));
    }

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
                provider_id: "static-test".to_owned(),
                endpoint: "http://127.0.0.1:9".to_owned(),
                expected_exit_ip: "203.0.113.10".to_owned(),
                credential_ref: String::new(),
                credential_file: None,
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
