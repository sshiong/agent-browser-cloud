//! Node Contracts。
//!
//! Browser Node 的契约定义。

use serde::{Deserialize, Serialize};

/// 由正式 Protobuf 契约生成的 Node RPC 类型。
// Tonic's generated Rust 1.98 client methods return tonic::Status by value. The generated source
// is outside this crate and cannot box that stable public error without forking the generator.
#[allow(clippy::result_large_err)]
pub mod proto {
    tonic::include_proto!("browsercloud.node.v1");
}

/// Node 命令。
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct NodeCommand {
    pub message_id: String,
    pub command_type: String,
    pub session_id: String,
    pub coordinator_term: u64,
    pub context_epoch: u64,
    pub operation_epoch: u64,
    pub idempotency_key: String,
    pub payload: Vec<u8>,
}

/// Node 命令类型。
pub mod command_types {
    pub const START_RUNTIME: &str = "StartRuntime";
    pub const STOP_RUNTIME: &str = "StopRuntime";
    pub const RELEASE_ALL_INPUT: &str = "ReleaseAllInput";
}
