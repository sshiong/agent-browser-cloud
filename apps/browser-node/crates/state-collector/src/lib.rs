//! State Collector。
//!
//! 负责采集浏览器当前状态。

use async_trait::async_trait;
use serde::{Deserialize, Serialize};

/// 交互目标。
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct InteractiveTarget {
    /// 目标引用
    pub target_ref: String,
    /// 角色
    pub role: String,
    /// 名称
    pub name: Option<String>,
    /// 边界
    pub bounds: Option<Bounds>,
    /// 是否启用
    pub enabled: bool,
    /// 是否可见
    pub visible: bool,
}

/// 边界。
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Bounds {
    pub x: f64,
    pub y: f64,
    pub width: f64,
    pub height: f64,
}

/// 状态质量。
#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum StateQuality {
    Complete,
    DepthLimited,
    Resyncing,
    Degraded,
    Invalid,
}

/// 当前状态。
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CurrentState {
    /// Session ID
    pub session_id: String,
    /// 状态版本
    pub state_version: u64,
    /// 目标版本
    pub target_revision: u64,
    /// URL
    pub url: String,
    /// 标题
    pub title: String,
    /// 交互目标列表
    pub targets: Vec<InteractiveTarget>,
    /// 状态质量
    pub quality: StateQuality,
    /// 内容哈希
    pub content_hash: String,
}

/// 浏览器状态采集器 trait。
#[async_trait]
pub trait BrowserStateCollector: Send + Sync {
    /// 采集当前状态。
    async fn collect_current_state(&self, session_id: &str) -> anyhow::Result<CurrentState>;

    /// 重新同步区域。
    async fn resync_region(&self, session_id: &str, root_ref: &str)
        -> anyhow::Result<CurrentState>;
}
