//! Storage Helper。
//!
//! 负责管理 Profile 存储。

use serde::{Deserialize, Serialize};

/// Profile 检查点清单。
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ProfileCheckpointManifest {
    pub checkpoint_id: String,
    pub checkpoint_epoch: u64,
    pub profile_id: String,
    pub runtime_build_id: String,
    pub profile_write_epoch: u64,
    pub files: Vec<CheckpointFile>,
    pub content_hash: String,
    pub committed: bool,
}

/// 检查点文件。
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CheckpointFile {
    pub relative_path: String,
    pub size: u64,
    pub sha256: String,
}
