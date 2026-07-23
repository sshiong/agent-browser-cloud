//! Browser Supervisor。
//!
//! 负责监控浏览器健康状态，包括进程、CDP、页面、内存、CPU 等。

use serde::{Deserialize, Serialize};

/// 浏览器健康状态。
#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum BrowserHealth {
    /// 健康
    Healthy,
    /// 降级
    Degraded(String),
    /// 崩溃
    Crashed(String),
}

/// 浏览器 Supervisor。
#[derive(Default)]
pub struct BrowserSupervisor {}

impl BrowserSupervisor {
    /// 创建新的 Browser Supervisor。
    pub fn new() -> Self {
        Self {}
    }

    /// 获取浏览器健康状态。
    pub async fn health(&self, _session_id: &str) -> anyhow::Result<BrowserHealth> {
        Ok(BrowserHealth::Degraded(
            "CDP and resource probes are not implemented".to_owned(),
        ))
    }
}

#[cfg(test)]
mod tests {
    use super::{BrowserHealth, BrowserSupervisor};

    #[tokio::test]
    async fn does_not_report_healthy_before_probes_exist() {
        let health = BrowserSupervisor::new().health("ses_test").await.unwrap();
        assert!(matches!(health, BrowserHealth::Degraded(_)));
    }
}
