//! Node Journal。
//!
//! 负责记录 Browser Node 的执行日志，用于故障恢复和审计。

use async_trait::async_trait;
use serde::{Deserialize, Serialize};

/// Node 事件。
#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum NodeEvent {
    RuntimeStarted {
        session_id: String,
        node_id: String,
        runtime_build_id: String,
        pid: u32,
        browser_generation: u64,
        cdp_endpoint: String,
    },
    RuntimeStopped {
        session_id: String,
        reason: String,
        exit_code: i32,
    },
    RuntimeCrashed {
        session_id: String,
        crash_type: String,
        reason: String,
    },
}

/// Node Journal trait。
///
/// 有界、追加式日志，用于节点故障对账。
#[async_trait]
pub trait NodeJournal: Send + Sync {
    /// 检查消息是否已处理。
    async fn was_processed(&self, message_id: &str) -> anyhow::Result<bool>;

    /// 获取之前的处理结果。
    async fn previous_result(&self, message_id: &str) -> anyhow::Result<NodeEvent>;

    /// 验证 Coordinator Term。
    async fn validate_term(&self, session_id: &str, coordinator_term: u64) -> anyhow::Result<()>;

    /// 记录处理结果。
    async fn record_result(&self, event: &NodeEvent) -> anyhow::Result<()>;
}
