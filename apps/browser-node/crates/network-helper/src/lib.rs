//! Browser Runtime 的代理 Provider 绑定与出口校验。
//!
//! 代理校验失败时绝不返回“可直连”结果；连续失败会打开本地 Provider Circuit Breaker。

use async_trait::async_trait;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::net::IpAddr;
use std::sync::Arc;
use std::time::{Duration, Instant};
use tokio::sync::Mutex;

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
    circuit: Mutex<CircuitState>,
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
        Ok(provider.proxy_server())
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
        let proxy = reqwest::Proxy::all(endpoint.as_str())?;
        let client = reqwest::Client::builder()
            .proxy(proxy)
            .connect_timeout(Duration::from_secs(3))
            .timeout(Duration::from_secs(5))
            .build()?;
        Ok(Self {
            config,
            endpoint,
            client,
            circuit: Mutex::new(CircuitState::default()),
        })
    }

    fn proxy_server(&self) -> String {
        format!(
            "{}://{}:{}",
            self.endpoint.scheme(),
            self.endpoint.host_str().unwrap_or_default(),
            self.endpoint.port_or_known_default().unwrap_or_default()
        )
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
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    use tokio::net::TcpListener;

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
                exit_check_url: "http://browsercloud.invalid/exit".into(),
                failure_threshold: 3,
                open_duration: Duration::from_secs(30),
            },
            StaticProxyConfig {
                provider_id: "provider-b".into(),
                endpoint: second_endpoint,
                expected_exit_ip: "203.0.113.20".into(),
                credential_ref: "vault://tenant/proxy/b".into(),
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

    #[test]
    fn only_single_provider_can_resolve_an_n_minus_one_empty_descriptor() {
        let single = StaticProxyNetworkHelper::new(StaticProxyConfig {
            provider_id: "provider-a".into(),
            endpoint: "http://127.0.0.1:8001".into(),
            expected_exit_ip: "203.0.113.10".into(),
            credential_ref: "vault://tenant/proxy/a".into(),
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
                exit_check_url: "http://browsercloud.invalid/exit".into(),
                failure_threshold: 3,
                open_duration: Duration::from_secs(30),
            },
            StaticProxyConfig {
                provider_id: "provider-b".into(),
                endpoint: "http://127.0.0.1:8002".into(),
                expected_exit_ip: "203.0.113.20".into(),
                credential_ref: String::new(),
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
