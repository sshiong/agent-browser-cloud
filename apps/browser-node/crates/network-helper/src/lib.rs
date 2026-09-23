//! Browser Runtime 的代理 Provider 绑定与出口校验。
//!
//! 代理校验失败时绝不返回“可直连”结果；连续失败会打开本地 Provider Circuit Breaker。

use async_trait::async_trait;
use base64::Engine;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::io::Read as _;
use std::net::IpAddr;
use std::os::unix::fs::{OpenOptionsExt, PermissionsExt};
use std::path::PathBuf;
use std::sync::Arc;
use std::time::{Duration, Instant};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::{Mutex, OnceCell};
use zeroize::Zeroize;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum ProxyProtocol {
    Http,
    HttpsConnect,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ProxyBindingSpec {
    pub binding_id: String,
    pub session_id: String,
    pub provider_id: String,
    pub expected_exit_ip: String,
    pub protocol: ProxyProtocol,
    pub host: String,
    pub port: u16,
    pub credential_ref: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct ObservedNetwork {
    pub exit_ip: String,
    pub country: String,
    pub asn: String,
}

#[derive(Debug, Clone)]
pub struct StaticProxyConfig {
    pub provider_id: String,
    pub endpoint: String,
    pub expected_exit_ip: String,
    pub credential_ref: String,
    pub credential_file: Option<PathBuf>,
    pub exit_check_url: String,
    pub failure_threshold: u32,
    pub open_duration: Duration,
}

#[async_trait]
pub trait NetworkHelper: Send + Sync {
    async fn bind_proxy(&self, spec: ProxyBindingSpec) -> anyhow::Result<ObservedNetwork>;
    async fn verify_exit(&self, session_id: &str) -> anyhow::Result<ObservedNetwork>;
    async fn release(&self, session_id: &str) -> anyhow::Result<()>;
}

#[derive(Clone)]
pub struct StaticProxyNetworkHelper {
    providers: Arc<HashMap<(String, String), Arc<ProviderRuntime>>>,
    default_provider: Option<Arc<ProviderRuntime>>,
    bindings: Arc<Mutex<HashMap<String, ProxyBindingSpec>>>,
}

struct ProviderRuntime {
    config: StaticProxyConfig,
    endpoint: reqwest::Url,
    client: reqwest::Client,
    credential: Option<Arc<ProxyCredential>>,
    authenticated_relay: OnceCell<AuthenticatedProxyRelay>,
    circuit: Mutex<CircuitState>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct ProxyCredentialDocument {
    username: String,
    password: String,
}

impl Zeroize for ProxyCredentialDocument {
    fn zeroize(&mut self) {
        self.username.zeroize();
        self.password.zeroize();
    }
}

impl Drop for ProxyCredentialDocument {
    fn drop(&mut self) {
        self.zeroize();
    }
}

#[derive(Clone)]
struct ProxyCredential {
    username: String,
    password: String,
    authorization_header: String,
}

impl Drop for ProxyCredential {
    fn drop(&mut self) {
        self.username.zeroize();
        self.password.zeroize();
        self.authorization_header.zeroize();
    }
}

struct AuthenticatedProxyRelay {
    proxy_server: String,
    task: tokio::task::JoinHandle<()>,
}

impl Drop for AuthenticatedProxyRelay {
    fn drop(&mut self) {
        self.task.abort();
    }
}

#[derive(Default)]
struct CircuitState {
    consecutive_failures: u32,
    opened_at: Option<Instant>,
}

impl StaticProxyNetworkHelper {
    pub fn new(config: StaticProxyConfig) -> anyhow::Result<Self> {
        Self::new_many(vec![config])
    }

    pub fn new_many(configs: Vec<StaticProxyConfig>) -> anyhow::Result<Self> {
        anyhow::ensure!(
            !configs.is_empty(),
            "at least one proxy provider is required"
        );
        let mut providers = HashMap::new();
        for config in configs {
            validate_identifier("provider_id", &config.provider_id)?;
            anyhow::ensure!(
                config.credential_ref.len() <= 1024,
                "proxy credential reference is too long"
            );
            let key = (config.provider_id.clone(), config.credential_ref.clone());
            anyhow::ensure!(
                !providers.contains_key(&key),
                "duplicate proxy provider and credential reference"
            );
            providers.insert(key, Arc::new(ProviderRuntime::new(config)?));
        }
        let default_provider = if providers.len() == 1 {
            providers.values().next().cloned()
        } else {
            None
        };
        Ok(Self {
            providers: Arc::new(providers),
            default_provider,
            bindings: Arc::new(Mutex::new(HashMap::new())),
        })
    }

    pub fn binding_spec(
        &self,
        binding_id: &str,
        session_id: &str,
        provider_id: &str,
        expected_exit_ip: &str,
        credential_ref: &str,
    ) -> anyhow::Result<ProxyBindingSpec> {
        validate_identifier("binding_id", binding_id)?;
        validate_identifier("session_id", session_id)?;
        let provider = self.resolve_requested_provider(provider_id, credential_ref)?;
        if !expected_exit_ip.is_empty() {
            let requested_exit: IpAddr = expected_exit_ip.parse()?;
            let configured_exit: IpAddr = provider.config.expected_exit_ip.parse()?;
            anyhow::ensure!(
                requested_exit == configured_exit,
                "proxy expected exit does not match the configured provider"
            );
        }
        Ok(ProxyBindingSpec {
            binding_id: binding_id.to_owned(),
            session_id: session_id.to_owned(),
            provider_id: provider.config.provider_id.clone(),
            expected_exit_ip: provider.config.expected_exit_ip.clone(),
            protocol: ProxyProtocol::Http,
            host: provider.endpoint.host_str().unwrap_or_default().to_owned(),
            port: provider
                .endpoint
                .port_or_known_default()
                .unwrap_or_default(),
            credential_ref: provider.config.credential_ref.clone(),
        })
    }

    pub async fn proxy_server_for(&self, session_id: &str) -> anyhow::Result<String> {
        validate_identifier("session_id", session_id)?;
        let spec = self
            .bindings
            .lock()
            .await
            .get(session_id)
            .cloned()
            .ok_or_else(|| anyhow::anyhow!("session has no active proxy binding"))?;
        let provider = self.resolve_bound_provider(&spec)?;
        provider.proxy_server().await
    }

    fn resolve_requested_provider(
        &self,
        provider_id: &str,
        credential_ref: &str,
    ) -> anyhow::Result<Arc<ProviderRuntime>> {
        if provider_id.is_empty() && credential_ref.is_empty() {
            return self.default_provider.clone().ok_or_else(|| {
                anyhow::anyhow!("proxy provider is required when multiple providers are configured")
            });
        }
        validate_identifier("provider_id", provider_id)?;
        if let Some(provider) = self
            .providers
            .get(&(provider_id.to_owned(), credential_ref.to_owned()))
        {
            return Ok(Arc::clone(provider));
        }
        if credential_ref.is_empty() {
            let mut candidates = self
                .providers
                .iter()
                .filter(|((configured_provider_id, _), _)| configured_provider_id == provider_id)
                .map(|(_, provider)| Arc::clone(provider));
            if let Some(provider) = candidates.next() {
                anyhow::ensure!(
                    candidates.next().is_none(),
                    "proxy credential reference is required for this provider"
                );
                return Ok(provider);
            }
        }
        anyhow::bail!("proxy provider or credential reference is not configured")
    }

    fn resolve_bound_provider(
        &self,
        spec: &ProxyBindingSpec,
    ) -> anyhow::Result<Arc<ProviderRuntime>> {
        let provider = self
            .providers
            .get(&(spec.provider_id.clone(), spec.credential_ref.clone()))
            .cloned()
            .ok_or_else(|| {
                anyhow::anyhow!("proxy provider or credential reference is not configured")
            })?;
        provider.validate_binding(spec)?;
        Ok(provider)
    }
}

impl ProviderRuntime {
    fn new(config: StaticProxyConfig) -> anyhow::Result<Self> {
        anyhow::ensure!(
            config.failure_threshold > 0,
            "proxy failure threshold must be positive"
        );
        anyhow::ensure!(
            !config.open_duration.is_zero(),
            "proxy circuit open duration must be positive"
        );
        let endpoint = validate_proxy_endpoint(&config.endpoint)?;
        let expected_exit: IpAddr = config.expected_exit_ip.parse()?;
        anyhow::ensure!(
            !expected_exit.is_unspecified(),
            "expected proxy exit IP cannot be unspecified"
        );
        let check_url = reqwest::Url::parse(&config.exit_check_url)?;
        anyhow::ensure!(
            matches!(check_url.scheme(), "http" | "https"),
            "proxy exit check URL must use HTTP(S)"
        );
        let credential = config
            .credential_file
            .as_ref()
            .map(load_proxy_credential)
            .transpose()?
            .map(Arc::new);
        let mut proxy = reqwest::Proxy::all(endpoint.as_str())?;
        if let Some(credential) = credential.as_ref() {
            proxy = proxy.basic_auth(&credential.username, &credential.password);
        }
        let client = reqwest::Client::builder()
            .proxy(proxy)
            .connect_timeout(Duration::from_secs(3))
            .timeout(Duration::from_secs(5))
            .build()?;
        Ok(Self {
            config,
            endpoint,
            client,
            credential,
            authenticated_relay: OnceCell::new(),
            circuit: Mutex::new(CircuitState::default()),
        })
    }

    async fn proxy_server(&self) -> anyhow::Result<String> {
        let Some(credential) = self.credential.as_ref() else {
            return Ok(format!(
                "{}://{}:{}",
                self.endpoint.scheme(),
                self.endpoint.host_str().unwrap_or_default(),
                self.endpoint.port_or_known_default().unwrap_or_default()
            ));
        };
        let relay = self
            .authenticated_relay
            .get_or_try_init(|| start_authenticated_proxy_relay(&self.endpoint, credential))
            .await?;
        Ok(relay.proxy_server.clone())
    }

    async fn ensure_circuit_closed(&self) -> anyhow::Result<()> {
        let mut circuit = self.circuit.lock().await;
        let Some(opened_at) = circuit.opened_at else {
            return Ok(());
        };
        if opened_at.elapsed() < self.config.open_duration {
            anyhow::bail!("proxy provider circuit is open");
        }
        circuit.opened_at = None;
        circuit.consecutive_failures = 0;
        Ok(())
    }

    async fn record_success(&self) {
        let mut circuit = self.circuit.lock().await;
        circuit.consecutive_failures = 0;
        circuit.opened_at = None;
    }

    async fn record_failure(&self) {
        let mut circuit = self.circuit.lock().await;
        circuit.consecutive_failures = circuit.consecutive_failures.saturating_add(1);
        if circuit.consecutive_failures >= self.config.failure_threshold {
            circuit.opened_at = Some(Instant::now());
        }
    }

    async fn probe_exit(&self) -> anyhow::Result<ObservedNetwork> {
        self.ensure_circuit_closed().await?;
        let result = async {
            let response = self
                .client
                .get(&self.config.exit_check_url)
                .send()
                .await?
                .error_for_status()?;
            let observed: ObservedNetwork = response.json().await?;
            let observed_ip: IpAddr = observed.exit_ip.parse()?;
            let expected_ip: IpAddr = self.config.expected_exit_ip.parse()?;
            anyhow::ensure!(
                observed_ip == expected_ip,
                "proxy exit IP does not match the allocated endpoint"
            );
            anyhow::ensure!(
                !observed.country.trim().is_empty() && !observed.asn.trim().is_empty(),
                "proxy exit metadata is incomplete"
            );
            Ok(observed)
        }
        .await;
        match result {
            Ok(observed) => {
                self.record_success().await;
                Ok(observed)
            }
            Err(error) => {
                self.record_failure().await;
                Err(error)
            }
        }
    }

    fn validate_binding(&self, spec: &ProxyBindingSpec) -> anyhow::Result<()> {
        validate_identifier("binding_id", &spec.binding_id)?;
        validate_identifier("session_id", &spec.session_id)?;
        anyhow::ensure!(
            spec.provider_id == self.config.provider_id,
            "proxy provider does not match the configured provider"
        );
        anyhow::ensure!(
            spec.expected_exit_ip == self.config.expected_exit_ip,
            "proxy expected exit does not match the configured provider"
        );
        anyhow::ensure!(
            spec.credential_ref == self.config.credential_ref,
            "proxy credential reference is not resolved by this helper"
        );
        anyhow::ensure!(
            spec.protocol == ProxyProtocol::Http,
            "only HTTP static proxies are supported"
        );
        anyhow::ensure!(
            self.endpoint.host_str() == Some(spec.host.as_str())
                && self.endpoint.port_or_known_default() == Some(spec.port),
            "proxy binding does not match the configured static provider"
        );
        Ok(())
    }
}

#[async_trait]
impl NetworkHelper for StaticProxyNetworkHelper {
    async fn bind_proxy(&self, spec: ProxyBindingSpec) -> anyhow::Result<ObservedNetwork> {
        let provider = self.resolve_bound_provider(&spec)?;
        if let Some(existing) = self.bindings.lock().await.get(&spec.session_id).cloned() {
            anyhow::ensure!(
                existing.binding_id == spec.binding_id
                    && existing.provider_id == spec.provider_id
                    && existing.credential_ref == spec.credential_ref,
                "session already has a different proxy binding"
            );
        }
        let observed = provider.probe_exit().await?;
        self.bindings
            .lock()
            .await
            .insert(spec.session_id.clone(), spec);
        Ok(observed)
    }

    async fn verify_exit(&self, session_id: &str) -> anyhow::Result<ObservedNetwork> {
        validate_identifier("session_id", session_id)?;
        let spec = self
            .bindings
            .lock()
            .await
            .get(session_id)
            .cloned()
            .ok_or_else(|| anyhow::anyhow!("session has no active proxy binding"))?;
        self.resolve_bound_provider(&spec)?.probe_exit().await
    }

    async fn release(&self, session_id: &str) -> anyhow::Result<()> {
        validate_identifier("session_id", session_id)?;
        self.bindings.lock().await.remove(session_id);
        Ok(())
    }
}

fn load_proxy_credential(path: &PathBuf) -> anyhow::Result<ProxyCredential> {
    anyhow::ensure!(path.is_absolute(), "proxy credential file must be absolute");
    let file = std::fs::OpenOptions::new()
        .read(true)
        .custom_flags(nix::libc::O_NOFOLLOW | nix::libc::O_CLOEXEC)
        .open(path)?;
    let metadata = file.metadata()?;
    anyhow::ensure!(
        metadata.file_type().is_file(),
        "proxy credential file must be a regular file, not a symlink"
    );
    anyhow::ensure!(
        metadata.len() > 0 && metadata.len() <= 16 * 1024,
        "proxy credential file must contain between 1 byte and 16 KiB"
    );
    anyhow::ensure!(
        metadata.permissions().mode() & 0o037 == 0,
        "proxy credential file must allow at most owner read/write and group read"
    );
    let mut credential_bytes = Vec::with_capacity(metadata.len() as usize);
    file.take(16 * 1024 + 1)
        .read_to_end(&mut credential_bytes)?;
    anyhow::ensure!(
        credential_bytes.len() <= 16 * 1024,
        "proxy credential file exceeds 16 KiB while reading"
    );
    let parsed: Result<ProxyCredentialDocument, _> = serde_json::from_slice(&credential_bytes);
    credential_bytes.zeroize();
    let mut document = parsed.map_err(|_| anyhow::anyhow!("proxy credential file is invalid"))?;
    anyhow::ensure!(
        !document.username.is_empty()
            && document.username.len() <= 512
            && !document.username.contains(':')
            && !document.username.chars().any(char::is_control),
        "proxy credential username is invalid"
    );
    anyhow::ensure!(
        !document.password.is_empty()
            && document.password.len() <= 4096
            && !document.password.chars().any(char::is_control),
        "proxy credential password is invalid"
    );
    let mut plaintext = format!("{}:{}", document.username, document.password);
    let encoded = base64::engine::general_purpose::STANDARD.encode(plaintext.as_bytes());
    plaintext.zeroize();
    Ok(ProxyCredential {
        username: std::mem::take(&mut document.username),
        password: std::mem::take(&mut document.password),
        authorization_header: format!("Basic {encoded}"),
    })
}

async fn start_authenticated_proxy_relay(
    endpoint: &reqwest::Url,
    credential: &Arc<ProxyCredential>,
) -> anyhow::Result<AuthenticatedProxyRelay> {
    let upstream_host = endpoint
        .host_str()
        .ok_or_else(|| anyhow::anyhow!("proxy provider endpoint has no host"))?
        .to_owned();
    let upstream_port = endpoint
        .port_or_known_default()
        .ok_or_else(|| anyhow::anyhow!("proxy provider endpoint has no port"))?;
    let listener = TcpListener::bind("127.0.0.1:0").await?;
    let local_address = listener.local_addr()?;
    let credential = Arc::clone(credential);
    let task = tokio::spawn(async move {
        loop {
            let Ok((client, _)) = listener.accept().await else {
                break;
            };
            let host = upstream_host.clone();
            let credential = Arc::clone(&credential);
            tokio::spawn(async move {
                if let Err(error) = authenticated_proxy_connection(
                    client,
                    host.as_str(),
                    upstream_port,
                    credential.authorization_header.as_str(),
                )
                .await
                {
                    tracing::warn!(error = %error, "authenticated proxy relay connection failed");
                }
            });
        }
    });
    Ok(AuthenticatedProxyRelay {
        proxy_server: format!("http://127.0.0.1:{}", local_address.port()),
        task,
    })
}

async fn authenticated_proxy_connection(
    mut client: TcpStream,
    upstream_host: &str,
    upstream_port: u16,
    authorization_header: &str,
) -> anyhow::Result<()> {
    const MAX_HEADER_BYTES: usize = 64 * 1024;
    let mut request = Vec::with_capacity(4096);
    let header_end = loop {
        if let Some(position) = request.windows(4).position(|window| window == b"\r\n\r\n") {
            break position;
        }
        anyhow::ensure!(
            request.len() < MAX_HEADER_BYTES,
            "proxy request headers exceed 64 KiB"
        );
        let mut chunk = [0_u8; 4096];
        let count = tokio::time::timeout(Duration::from_secs(5), client.read(&mut chunk)).await??;
        anyhow::ensure!(count > 0, "proxy client closed before sending headers");
        request.extend_from_slice(&chunk[..count]);
    };
    let header_text = std::str::from_utf8(&request[..header_end])?;
    anyhow::ensure!(
        !header_text.lines().any(|line| {
            line.split_once(':')
                .is_some_and(|(name, _)| name.eq_ignore_ascii_case("proxy-authorization"))
        }),
        "browser-supplied proxy authorization is forbidden"
    );
    let mut forwarded = Vec::with_capacity(request.len() + authorization_header.len() + 64);
    forwarded.extend_from_slice(&request[..header_end]);
    forwarded.extend_from_slice(b"\r\nProxy-Authorization: ");
    forwarded.extend_from_slice(authorization_header.as_bytes());
    forwarded.extend_from_slice(b"\r\nProxy-Connection: close");
    forwarded.extend_from_slice(&request[header_end..]);

    let mut upstream = tokio::time::timeout(
        Duration::from_secs(5),
        TcpStream::connect((upstream_host, upstream_port)),
    )
    .await??;
    upstream.write_all(&forwarded).await?;
    tokio::io::copy_bidirectional(&mut client, &mut upstream).await?;
    Ok(())
}

fn validate_proxy_endpoint(value: &str) -> anyhow::Result<reqwest::Url> {
    let endpoint = reqwest::Url::parse(value)?;
    anyhow::ensure!(
        endpoint.scheme() == "http",
        "static proxy endpoint must use HTTP"
    );
    anyhow::ensure!(
        endpoint.host_str().is_some() && endpoint.port_or_known_default().is_some(),
        "static proxy endpoint must include a host and port"
    );
    anyhow::ensure!(
        endpoint.username().is_empty()
            && endpoint.password().is_none()
            && endpoint.query().is_none()
            && endpoint.fragment().is_none()
            && matches!(endpoint.path(), "" | "/"),
        "static proxy endpoint must not embed credentials, path, query, or fragment"
    );
    Ok(endpoint)
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
    use std::os::unix::fs::PermissionsExt;
    use std::sync::atomic::{AtomicU64, Ordering};
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    use tokio::net::TcpListener;

    static TEST_SEQUENCE: AtomicU64 = AtomicU64::new(1);

    async fn proxy_fixture(exit_ip: &'static str) -> (String, tokio::task::JoinHandle<()>) {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let endpoint = format!("http://{}", listener.local_addr().unwrap());
        let task = tokio::spawn(async move {
            while let Ok((mut stream, _)) = listener.accept().await {
                tokio::spawn(async move {
                    let mut request = vec![0_u8; 4096];
                    let _ = stream.read(&mut request).await;
                    let body =
                        format!(r#"{{"exitIp":"{exit_ip}","country":"TEST","asn":"AS64500"}}"#);
                    let response = format!(
                        "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
                        body.len()
                    );
                    let _ = stream.write_all(response.as_bytes()).await;
                    let _ = stream.write_all(body.as_bytes()).await;
                });
            }
        });
        (endpoint, task)
    }

    async fn authenticated_proxy_fixture(
        exit_ip: &'static str,
        expected_authorization: &'static str,
    ) -> (String, Arc<AtomicU64>, tokio::task::JoinHandle<()>) {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let endpoint = format!("http://{}", listener.local_addr().unwrap());
        let accepted = Arc::new(AtomicU64::new(0));
        let accepted_for_task = Arc::clone(&accepted);
        let task = tokio::spawn(async move {
            while let Ok((mut stream, _)) = listener.accept().await {
                let accepted = Arc::clone(&accepted_for_task);
                tokio::spawn(async move {
                    let mut request = vec![0_u8; 8192];
                    let count = stream.read(&mut request).await.unwrap_or_default();
                    let request = String::from_utf8_lossy(&request[..count]);
                    let authorized = request.lines().any(|line| {
                        line.split_once(':').is_some_and(|(name, value)| {
                            name.eq_ignore_ascii_case("Proxy-Authorization")
                                && value.trim() == expected_authorization
                        })
                    });
                    if authorized {
                        accepted.fetch_add(1, Ordering::Relaxed);
                    }
                    let status = if authorized {
                        "200 OK"
                    } else {
                        "407 Proxy Authentication Required"
                    };
                    let body =
                        format!(r#"{{"exitIp":"{exit_ip}","country":"TEST","asn":"AS64500"}}"#);
                    let response = format!(
                        "HTTP/1.1 {status}\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
                        body.len()
                    );
                    let _ = stream.write_all(response.as_bytes()).await;
                    let _ = stream.write_all(body.as_bytes()).await;
                });
            }
        });
        (endpoint, accepted, task)
    }

    fn credential_file(username: &str, password: &str) -> PathBuf {
        let path = std::env::temp_dir().join(format!(
            "browsercloud-proxy-credential-{}-{}.json",
            std::process::id(),
            TEST_SEQUENCE.fetch_add(1, Ordering::Relaxed)
        ));
        std::fs::write(
            &path,
            serde_json::json!({"username": username, "password": password}).to_string(),
        )
        .unwrap();
        std::fs::set_permissions(&path, std::fs::Permissions::from_mode(0o600)).unwrap();
        path
    }

    fn binding(port: u16) -> ProxyBindingSpec {
        ProxyBindingSpec {
            binding_id: "pxy_test".into(),
            session_id: "ses_test".into(),
            provider_id: "static-test".into(),
            expected_exit_ip: "203.0.113.10".into(),
            protocol: ProxyProtocol::Http,
            host: "127.0.0.1".into(),
            port,
            credential_ref: String::new(),
        }
    }

    #[tokio::test]
    async fn binds_verifies_and_releases_only_after_exit_matches() {
        let (endpoint, server) = proxy_fixture("203.0.113.10").await;
        let port = reqwest::Url::parse(&endpoint).unwrap().port().unwrap();
        let helper = StaticProxyNetworkHelper::new(StaticProxyConfig {
            provider_id: "static-test".into(),
            endpoint,
            expected_exit_ip: "203.0.113.10".into(),
            credential_ref: String::new(),
            credential_file: None,
            exit_check_url: "http://browsercloud.invalid/exit".into(),
            failure_threshold: 3,
            open_duration: Duration::from_secs(30),
        })
        .unwrap();

        let observed = helper.bind_proxy(binding(port)).await.unwrap();
        assert_eq!(observed.exit_ip, "203.0.113.10");
        assert_eq!(helper.verify_exit("ses_test").await.unwrap().asn, "AS64500");
        helper.release("ses_test").await.unwrap();
        assert!(helper.verify_exit("ses_test").await.is_err());
        server.abort();
    }

    #[tokio::test]
    async fn selects_provider_by_opaque_credential_reference() {
        let (first_endpoint, first_server) = proxy_fixture("203.0.113.10").await;
        let (second_endpoint, second_server) = proxy_fixture("203.0.113.20").await;
        let second_port = reqwest::Url::parse(&second_endpoint)
            .unwrap()
            .port()
            .unwrap();
        let helper = StaticProxyNetworkHelper::new_many(vec![
            StaticProxyConfig {
                provider_id: "provider-a".into(),
                endpoint: first_endpoint,
                expected_exit_ip: "203.0.113.10".into(),
                credential_ref: "vault://tenant/proxy/a".into(),
                credential_file: None,
                exit_check_url: "http://browsercloud.invalid/exit".into(),
                failure_threshold: 3,
                open_duration: Duration::from_secs(30),
            },
            StaticProxyConfig {
                provider_id: "provider-b".into(),
                endpoint: second_endpoint,
                expected_exit_ip: "203.0.113.20".into(),
                credential_ref: "vault://tenant/proxy/b".into(),
                credential_file: None,
                exit_check_url: "http://browsercloud.invalid/exit".into(),
                failure_threshold: 3,
                open_duration: Duration::from_secs(30),
            },
        ])
        .unwrap();

        let spec = helper
            .binding_spec(
                "pxy_second",
                "ses_second",
                "provider-b",
                "203.0.113.20",
                "vault://tenant/proxy/b",
            )
            .unwrap();
        assert_eq!(spec.port, second_port);
        let observed = helper.bind_proxy(spec).await.unwrap();
        assert_eq!(observed.exit_ip, "203.0.113.20");
        assert!(helper
            .proxy_server_for("ses_second")
            .await
            .unwrap()
            .ends_with(&format!(":{second_port}")));
        assert!(helper
            .binding_spec(
                "pxy_wrong",
                "ses_wrong",
                "provider-b",
                "203.0.113.20",
                "vault://tenant/proxy/a",
            )
            .is_err());
        first_server.abort();
        second_server.abort();
    }

    #[tokio::test]
    async fn authenticates_commercial_http_proxy_without_exposing_secret_to_browser() {
        let credential_path = credential_file("commercial-user", "commercial-password");
        let expected = "Basic Y29tbWVyY2lhbC11c2VyOmNvbW1lcmNpYWwtcGFzc3dvcmQ=";
        let (endpoint, accepted, server) =
            authenticated_proxy_fixture("203.0.113.30", expected).await;
        let helper = StaticProxyNetworkHelper::new(StaticProxyConfig {
            provider_id: "commercial-test".into(),
            endpoint: endpoint.clone(),
            expected_exit_ip: "203.0.113.30".into(),
            credential_ref: "vault://tenant/proxy/commercial".into(),
            credential_file: Some(credential_path.clone()),
            exit_check_url: "http://browsercloud.invalid/exit".into(),
            failure_threshold: 3,
            open_duration: Duration::from_secs(30),
        })
        .unwrap();
        let upstream_port = reqwest::Url::parse(&endpoint).unwrap().port().unwrap();
        let spec = ProxyBindingSpec {
            binding_id: "pxy_commercial".into(),
            session_id: "ses_commercial".into(),
            provider_id: "commercial-test".into(),
            expected_exit_ip: "203.0.113.30".into(),
            protocol: ProxyProtocol::Http,
            host: "127.0.0.1".into(),
            port: upstream_port,
            credential_ref: "vault://tenant/proxy/commercial".into(),
        };

        helper.bind_proxy(spec).await.unwrap();
        let browser_proxy = helper.proxy_server_for("ses_commercial").await.unwrap();
        assert!(browser_proxy.starts_with("http://127.0.0.1:"));
        assert_ne!(browser_proxy, endpoint);
        assert!(!browser_proxy.contains("commercial-user"));
        assert!(!browser_proxy.contains("commercial-password"));

        let relay_url = reqwest::Url::parse(&browser_proxy).unwrap();
        let mut browser =
            TcpStream::connect((relay_url.host_str().unwrap(), relay_url.port().unwrap()))
                .await
                .unwrap();
        browser
            .write_all(
                b"GET http://browsercloud.invalid/exit HTTP/1.1\r\nHost: browsercloud.invalid\r\n\r\n",
            )
            .await
            .unwrap();
        let mut response = Vec::new();
        browser.read_to_end(&mut response).await.unwrap();
        assert!(String::from_utf8_lossy(&response).contains("200 OK"));
        assert_eq!(accepted.load(Ordering::Relaxed), 2);

        let mut connect_client =
            TcpStream::connect((relay_url.host_str().unwrap(), relay_url.port().unwrap()))
                .await
                .unwrap();
        connect_client
            .write_all(b"CONNECT target.example:443 HTTP/1.1\r\nHost: target.example:443\r\n\r\n")
            .await
            .unwrap();
        let mut connect_response = Vec::new();
        connect_client
            .read_to_end(&mut connect_response)
            .await
            .unwrap();
        assert!(String::from_utf8_lossy(&connect_response).contains("200 OK"));
        assert_eq!(accepted.load(Ordering::Relaxed), 3);

        let mut forged_client =
            TcpStream::connect((relay_url.host_str().unwrap(), relay_url.port().unwrap()))
                .await
                .unwrap();
        forged_client
            .write_all(
                b"GET http://browsercloud.invalid/exit HTTP/1.1\r\nHost: browsercloud.invalid\r\nProxy-Authorization: Basic attacker\r\n\r\n",
            )
            .await
            .unwrap();
        forged_client.shutdown().await.unwrap();
        let mut forged_response = Vec::new();
        forged_client
            .read_to_end(&mut forged_response)
            .await
            .unwrap();
        assert!(forged_response.is_empty());
        assert_eq!(accepted.load(Ordering::Relaxed), 3);

        server.abort();
        let _ = std::fs::remove_file(credential_path);
    }

    #[test]
    fn rejects_world_readable_or_malformed_commercial_proxy_credentials() {
        let world_readable = credential_file("user", "password");
        std::fs::set_permissions(&world_readable, std::fs::Permissions::from_mode(0o604)).unwrap();
        let error = match load_proxy_credential(&world_readable) {
            Ok(_) => panic!("world-readable proxy credentials must be rejected"),
            Err(error) => error,
        };
        assert!(error.to_string().contains("group read"));
        let _ = std::fs::remove_file(world_readable);

        let group_writable = credential_file("user", "password");
        std::fs::set_permissions(&group_writable, std::fs::Permissions::from_mode(0o660)).unwrap();
        assert!(load_proxy_credential(&group_writable).is_err());
        let _ = std::fs::remove_file(group_writable);

        let symlink_target = credential_file("user", "password");
        let symlink_path = symlink_target.with_extension("symlink.json");
        std::os::unix::fs::symlink(&symlink_target, &symlink_path).unwrap();
        assert!(load_proxy_credential(&symlink_path).is_err());
        let _ = std::fs::remove_file(symlink_path);
        let _ = std::fs::remove_file(symlink_target);

        let malformed = credential_file("user:name", "password\nvalue");
        assert!(load_proxy_credential(&malformed).is_err());
        let _ = std::fs::remove_file(malformed);
    }

    #[test]
    fn only_single_provider_can_resolve_an_n_minus_one_empty_descriptor() {
        let single = StaticProxyNetworkHelper::new(StaticProxyConfig {
            provider_id: "provider-a".into(),
            endpoint: "http://127.0.0.1:8001".into(),
            expected_exit_ip: "203.0.113.10".into(),
            credential_ref: "vault://tenant/proxy/a".into(),
            credential_file: None,
            exit_check_url: "http://browsercloud.invalid/exit".into(),
            failure_threshold: 3,
            open_duration: Duration::from_secs(30),
        })
        .unwrap();
        assert_eq!(
            single
                .binding_spec("pxy_legacy", "ses_legacy", "", "", "")
                .unwrap()
                .provider_id,
            "provider-a"
        );

        let multiple = StaticProxyNetworkHelper::new_many(vec![
            StaticProxyConfig {
                provider_id: "provider-a".into(),
                endpoint: "http://127.0.0.1:8001".into(),
                expected_exit_ip: "203.0.113.10".into(),
                credential_ref: String::new(),
                credential_file: None,
                exit_check_url: "http://browsercloud.invalid/exit".into(),
                failure_threshold: 3,
                open_duration: Duration::from_secs(30),
            },
            StaticProxyConfig {
                provider_id: "provider-b".into(),
                endpoint: "http://127.0.0.1:8002".into(),
                expected_exit_ip: "203.0.113.20".into(),
                credential_ref: String::new(),
                credential_file: None,
                exit_check_url: "http://browsercloud.invalid/exit".into(),
                failure_threshold: 3,
                open_duration: Duration::from_secs(30),
            },
        ])
        .unwrap();
        assert!(multiple
            .binding_spec("pxy_legacy", "ses_legacy", "", "", "")
            .is_err());
    }

    #[tokio::test]
    async fn opens_circuit_and_never_falls_back_to_direct_network() {
        let reservation = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let port = reservation.local_addr().unwrap().port();
        drop(reservation);
        let helper = StaticProxyNetworkHelper::new(StaticProxyConfig {
            provider_id: "static-test".into(),
            endpoint: format!("http://127.0.0.1:{port}"),
            expected_exit_ip: "203.0.113.10".into(),
            credential_ref: String::new(),
            credential_file: None,
            exit_check_url: "http://browsercloud.invalid/exit".into(),
            failure_threshold: 2,
            open_duration: Duration::from_secs(30),
        })
        .unwrap();

        assert!(helper.bind_proxy(binding(port)).await.is_err());
        assert!(helper.bind_proxy(binding(port)).await.is_err());
        let error = helper.bind_proxy(binding(port)).await.unwrap_err();
        assert!(error.to_string().contains("circuit is open"));
    }
}
