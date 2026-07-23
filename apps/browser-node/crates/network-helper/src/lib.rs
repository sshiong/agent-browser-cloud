//! Network Helper。
//!
//! 负责管理网络代理绑定。

use async_trait::async_trait;
use serde::{Deserialize, Serialize};

/// 代理协议。
#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum ProxyProtocol {
    Http,
    HttpsConnect,
    Socks5,
}

/// 代理绑定规格。
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ProxyBindingSpec {
    pub binding_id: String,
    pub session_id: String,
    pub protocol: ProxyProtocol,
    pub host: String,
    pub port: u16,
    pub credential_ref: String,
}

/// 观察到的网络状态。
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ObservedNetwork {
    pub exit_ip: String,
    pub country: String,
    pub asn: String,
}

/// 网络 Helper trait。
#[async_trait]
pub trait NetworkHelper: Send + Sync {
    /// 绑定代理。
    async fn bind_proxy(&self, spec: ProxyBindingSpec) -> anyhow::Result<ObservedNetwork>;

    /// 验证出口。
    async fn verify_exit(&self, session_id: &str) -> anyhow::Result<ObservedNetwork>;

    /// 释放代理。
    async fn release(&self, session_id: &str) -> anyhow::Result<()>;
}
