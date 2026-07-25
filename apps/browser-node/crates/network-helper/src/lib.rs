//! Browser Runtime 的静态代理绑定与出口校验。
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
    pub endpoint: String,
    pub expected_exit_ip: String,
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
    config: StaticProxyConfig,
    endpoint: reqwest::Url,
    client: reqwest::Client,
    bindings: Arc<Mutex<HashMap<String, ProxyBindingSpec>>>,
    circuit: Arc<Mutex<CircuitState>>,
}

#[derive(Default)]
struct CircuitState {
    consecutive_failures: u32,
    opened_at: Option<Instant>,
}

impl StaticProxyNetworkHelper {
    pub fn new(config: StaticProxyConfig) -> anyhow::Result<Self> {
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
            bindings: Arc::new(Mutex::new(HashMap::new())),
            circuit: Arc::new(Mutex::new(CircuitState::default())),
        })
    }

    pub fn proxy_server(&self) -> String {
        format!(
            "{}://{}:{}",
            self.endpoint.scheme(),
            self.endpoint.host_str().unwrap_or_default(),
            self.endpoint.port_or_known_default().unwrap_or_default()
        )
    }

    pub fn binding_spec(&self, binding_id: &str, session_id: &str) -> ProxyBindingSpec {
        ProxyBindingSpec {
            binding_id: binding_id.to_owned(),
            session_id: session_id.to_owned(),
            protocol: ProxyProtocol::Http,
            host: self.endpoint.host_str().unwrap_or_default().to_owned(),
            port: self.endpoint.port_or_known_default().unwrap_or_default(),
            credential_ref: String::new(),
        }
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
            spec.credential_ref.is_empty(),
            "credential-backed static proxies are not implemented"
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
        self.validate_binding(&spec)?;
        if let Some(existing) = self.bindings.lock().await.get(&spec.session_id).cloned() {
            anyhow::ensure!(
                existing.binding_id == spec.binding_id,
                "session already has a different proxy binding"
            );
        }
        let observed = self.probe_exit().await?;
        self.bindings
            .lock()
            .await
            .insert(spec.session_id.clone(), spec);
        Ok(observed)
    }

    async fn verify_exit(&self, session_id: &str) -> anyhow::Result<ObservedNetwork> {
        validate_identifier("session_id", session_id)?;
        anyhow::ensure!(
            self.bindings.lock().await.contains_key(session_id),
            "session has no active proxy binding"
        );
        self.probe_exit().await
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

    async fn proxy_fixture() -> (String, tokio::task::JoinHandle<()>) {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let endpoint = format!("http://{}", listener.local_addr().unwrap());
        let task = tokio::spawn(async move {
            while let Ok((mut stream, _)) = listener.accept().await {
                tokio::spawn(async move {
                    let mut request = vec![0_u8; 4096];
                    let _ = stream.read(&mut request).await;
                    let body = br#"{"exitIp":"203.0.113.10","country":"TEST","asn":"AS64500"}"#;
                    let response = format!(
                        "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
                        body.len()
                    );
                    let _ = stream.write_all(response.as_bytes()).await;
                    let _ = stream.write_all(body).await;
                });
            }
        });
        (endpoint, task)
    }

    fn binding(port: u16) -> ProxyBindingSpec {
        ProxyBindingSpec {
            binding_id: "pxy_test".into(),
            session_id: "ses_test".into(),
            protocol: ProxyProtocol::Http,
            host: "127.0.0.1".into(),
            port,
            credential_ref: String::new(),
        }
    }

    #[tokio::test]
    async fn binds_verifies_and_releases_only_after_exit_matches() {
        let (endpoint, server) = proxy_fixture().await;
        let port = reqwest::Url::parse(&endpoint).unwrap().port().unwrap();
        let helper = StaticProxyNetworkHelper::new(StaticProxyConfig {
            endpoint,
            expected_exit_ip: "203.0.113.10".into(),
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
    async fn opens_circuit_and_never_falls_back_to_direct_network() {
        let reservation = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let port = reservation.local_addr().unwrap().port();
        drop(reservation);
        let helper = StaticProxyNetworkHelper::new(StaticProxyConfig {
            endpoint: format!("http://127.0.0.1:{port}"),
            expected_exit_ip: "203.0.113.10".into(),
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
