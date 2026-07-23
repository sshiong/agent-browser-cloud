// @generated
/// Session 会话上下文
#[allow(clippy::derive_partial_eq_without_eq)]
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct SessionContext {
    #[prost(string, tag="1")]
    pub session_id: ::prost::alloc::string::String,
    #[prost(string, tag="2")]
    pub tenant_id: ::prost::alloc::string::String,
    #[prost(string, tag="3")]
    pub profile_id: ::prost::alloc::string::String,
    #[prost(string, tag="4")]
    pub node_id: ::prost::alloc::string::String,
    #[prost(string, tag="5")]
    pub runtime_build_id: ::prost::alloc::string::String,
    #[prost(string, tag="6")]
    pub proxy_binding_id: ::prost::alloc::string::String,
    #[prost(string, tag="7")]
    pub isolation_profile_id: ::prost::alloc::string::String,
    /// 版本控制
    #[prost(int64, tag="10")]
    pub coordinator_term: i64,
    #[prost(int64, tag="11")]
    pub context_epoch: i64,
    #[prost(int64, tag="12")]
    pub browser_generation: i64,
    #[prost(int64, tag="13")]
    pub network_revision: i64,
    /// 状态
    #[prost(string, tag="20")]
    pub resource_class: ::prost::alloc::string::String,
    #[prost(enumeration="SessionState", tag="21")]
    pub state: i32,
    #[prost(string, tag="22")]
    pub policy_hash: ::prost::alloc::string::String,
    /// 时间戳
    #[prost(int64, tag="30")]
    pub created_at_ms: i64,
    #[prost(int64, tag="31")]
    pub updated_at_ms: i64,
}
/// 排他操作
#[allow(clippy::derive_partial_eq_without_eq)]
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct ExclusiveOperation {
    #[prost(string, tag="1")]
    pub operation_id: ::prost::alloc::string::String,
    #[prost(string, tag="2")]
    pub session_id: ::prost::alloc::string::String,
    #[prost(enumeration="OwnerType", tag="3")]
    pub owner_type: i32,
    #[prost(enumeration="OperationMode", tag="4")]
    pub mode: i32,
    #[prost(int32, tag="5")]
    pub priority: i32,
    #[prost(string, tag="6")]
    pub actor_id: ::prost::alloc::string::String,
    /// 版本控制
    #[prost(int64, tag="10")]
    pub coordinator_term: i64,
    #[prost(int64, tag="11")]
    pub context_epoch: i64,
    #[prost(int64, tag="12")]
    pub operation_epoch: i64,
    #[prost(string, tag="13")]
    pub workflow_id: ::prost::alloc::string::String,
    /// 状态
    #[prost(enumeration="OperationPhase", tag="20")]
    pub phase: i32,
    #[prost(enumeration="OperationState", tag="21")]
    pub state: i32,
    #[prost(int64, tag="22")]
    pub deadline_epoch_ms: i64,
    #[prost(bool, tag="23")]
    pub cancellable: bool,
    #[prost(bool, tag="24")]
    pub preemptible: bool,
    /// 能力
    #[prost(string, repeated, tag="30")]
    pub allowed_capabilities: ::prost::alloc::vec::Vec<::prost::alloc::string::String>,
}
/// 状态指针
#[allow(clippy::derive_partial_eq_without_eq)]
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct StateCursor {
    #[prost(string, tag="1")]
    pub session_id: ::prost::alloc::string::String,
    #[prost(int64, tag="2")]
    pub current_state_version: i64,
    #[prost(string, tag="3")]
    pub current_state_hash: ::prost::alloc::string::String,
    #[prost(enumeration="StateQuality", tag="4")]
    pub state_quality: i32,
    /// 版本控制
    #[prost(int64, tag="10")]
    pub browser_generation: i64,
    #[prost(int64, tag="11")]
    pub coordinator_term: i64,
    #[prost(int64, tag="12")]
    pub context_epoch: i64,
    #[prost(int64, tag="13")]
    pub target_revision: i64,
    #[prost(int64, tag="14")]
    pub network_revision: i64,
    /// 检查点
    #[prost(string, tag="20")]
    pub last_checkpoint_id: ::prost::alloc::string::String,
    #[prost(int64, tag="21")]
    pub last_checkpoint_version: i64,
    #[prost(int64, tag="22")]
    pub pending_event_count: i64,
    #[prost(int64, tag="30")]
    pub updated_at_ms: i64,
}
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash, PartialOrd, Ord, ::prost::Enumeration)]
#[repr(i32)]
pub enum SessionState {
    Unspecified = 0,
    Created = 1,
    Starting = 2,
    Running = 3,
    Degraded = 4,
    Hibernating = 5,
    Hibernated = 6,
    Recovering = 7,
    Terminating = 8,
    Terminated = 9,
    Failed = 10,
}
impl SessionState {
    /// String value of the enum field names used in the ProtoBuf definition.
    ///
    /// The values are not transformed in any way and thus are considered stable
    /// (if the ProtoBuf definition does not change) and safe for programmatic use.
    pub fn as_str_name(&self) -> &'static str {
        match self {
            SessionState::Unspecified => "SESSION_STATE_UNSPECIFIED",
            SessionState::Created => "SESSION_STATE_CREATED",
            SessionState::Starting => "SESSION_STATE_STARTING",
            SessionState::Running => "SESSION_STATE_RUNNING",
            SessionState::Degraded => "SESSION_STATE_DEGRADED",
            SessionState::Hibernating => "SESSION_STATE_HIBERNATING",
            SessionState::Hibernated => "SESSION_STATE_HIBERNATED",
            SessionState::Recovering => "SESSION_STATE_RECOVERING",
            SessionState::Terminating => "SESSION_STATE_TERMINATING",
            SessionState::Terminated => "SESSION_STATE_TERMINATED",
            SessionState::Failed => "SESSION_STATE_FAILED",
        }
    }
    /// Creates an enum from field names used in the ProtoBuf definition.
    pub fn from_str_name(value: &str) -> ::core::option::Option<Self> {
        match value {
            "SESSION_STATE_UNSPECIFIED" => Some(Self::Unspecified),
            "SESSION_STATE_CREATED" => Some(Self::Created),
            "SESSION_STATE_STARTING" => Some(Self::Starting),
            "SESSION_STATE_RUNNING" => Some(Self::Running),
            "SESSION_STATE_DEGRADED" => Some(Self::Degraded),
            "SESSION_STATE_HIBERNATING" => Some(Self::Hibernating),
            "SESSION_STATE_HIBERNATED" => Some(Self::Hibernated),
            "SESSION_STATE_RECOVERING" => Some(Self::Recovering),
            "SESSION_STATE_TERMINATING" => Some(Self::Terminating),
            "SESSION_STATE_TERMINATED" => Some(Self::Terminated),
            "SESSION_STATE_FAILED" => Some(Self::Failed),
            _ => None,
        }
    }
}
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash, PartialOrd, Ord, ::prost::Enumeration)]
#[repr(i32)]
pub enum OwnerType {
    Unspecified = 0,
    Agent = 1,
    Human = 2,
    System = 3,
}
impl OwnerType {
    /// String value of the enum field names used in the ProtoBuf definition.
    ///
    /// The values are not transformed in any way and thus are considered stable
    /// (if the ProtoBuf definition does not change) and safe for programmatic use.
    pub fn as_str_name(&self) -> &'static str {
        match self {
            OwnerType::Unspecified => "OWNER_TYPE_UNSPECIFIED",
            OwnerType::Agent => "OWNER_TYPE_AGENT",
            OwnerType::Human => "OWNER_TYPE_HUMAN",
            OwnerType::System => "OWNER_TYPE_SYSTEM",
        }
    }
    /// Creates an enum from field names used in the ProtoBuf definition.
    pub fn from_str_name(value: &str) -> ::core::option::Option<Self> {
        match value {
            "OWNER_TYPE_UNSPECIFIED" => Some(Self::Unspecified),
            "OWNER_TYPE_AGENT" => Some(Self::Agent),
            "OWNER_TYPE_HUMAN" => Some(Self::Human),
            "OWNER_TYPE_SYSTEM" => Some(Self::System),
            _ => None,
        }
    }
}
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash, PartialOrd, Ord, ::prost::Enumeration)]
#[repr(i32)]
pub enum OperationMode {
    Unspecified = 0,
    AgentInteractive = 1,
    HumanTakeover = 2,
    HumanAssist = 3,
    Quiesce = 4,
    Snapshot = 5,
    Hibernate = 6,
    Recovery = 7,
    ProxyTransition = 8,
    ExtensionMaintenance = 9,
    Termination = 10,
}
impl OperationMode {
    /// String value of the enum field names used in the ProtoBuf definition.
    ///
    /// The values are not transformed in any way and thus are considered stable
    /// (if the ProtoBuf definition does not change) and safe for programmatic use.
    pub fn as_str_name(&self) -> &'static str {
        match self {
            OperationMode::Unspecified => "OPERATION_MODE_UNSPECIFIED",
            OperationMode::AgentInteractive => "OPERATION_MODE_AGENT_INTERACTIVE",
            OperationMode::HumanTakeover => "OPERATION_MODE_HUMAN_TAKEOVER",
            OperationMode::HumanAssist => "OPERATION_MODE_HUMAN_ASSIST",
            OperationMode::Quiesce => "OPERATION_MODE_QUIESCE",
            OperationMode::Snapshot => "OPERATION_MODE_SNAPSHOT",
            OperationMode::Hibernate => "OPERATION_MODE_HIBERNATE",
            OperationMode::Recovery => "OPERATION_MODE_RECOVERY",
            OperationMode::ProxyTransition => "OPERATION_MODE_PROXY_TRANSITION",
            OperationMode::ExtensionMaintenance => "OPERATION_MODE_EXTENSION_MAINTENANCE",
            OperationMode::Termination => "OPERATION_MODE_TERMINATION",
        }
    }
    /// Creates an enum from field names used in the ProtoBuf definition.
    pub fn from_str_name(value: &str) -> ::core::option::Option<Self> {
        match value {
            "OPERATION_MODE_UNSPECIFIED" => Some(Self::Unspecified),
            "OPERATION_MODE_AGENT_INTERACTIVE" => Some(Self::AgentInteractive),
            "OPERATION_MODE_HUMAN_TAKEOVER" => Some(Self::HumanTakeover),
            "OPERATION_MODE_HUMAN_ASSIST" => Some(Self::HumanAssist),
            "OPERATION_MODE_QUIESCE" => Some(Self::Quiesce),
            "OPERATION_MODE_SNAPSHOT" => Some(Self::Snapshot),
            "OPERATION_MODE_HIBERNATE" => Some(Self::Hibernate),
            "OPERATION_MODE_RECOVERY" => Some(Self::Recovery),
            "OPERATION_MODE_PROXY_TRANSITION" => Some(Self::ProxyTransition),
            "OPERATION_MODE_EXTENSION_MAINTENANCE" => Some(Self::ExtensionMaintenance),
            "OPERATION_MODE_TERMINATION" => Some(Self::Termination),
            _ => None,
        }
    }
}
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash, PartialOrd, Ord, ::prost::Enumeration)]
#[repr(i32)]
pub enum OperationPhase {
    Unspecified = 0,
    Preparing = 1,
    Executing = 2,
    Flushing = 3,
    Uploading = 4,
    Verifying = 5,
    Completing = 6,
}
impl OperationPhase {
    /// String value of the enum field names used in the ProtoBuf definition.
    ///
    /// The values are not transformed in any way and thus are considered stable
    /// (if the ProtoBuf definition does not change) and safe for programmatic use.
    pub fn as_str_name(&self) -> &'static str {
        match self {
            OperationPhase::Unspecified => "OPERATION_PHASE_UNSPECIFIED",
            OperationPhase::Preparing => "OPERATION_PHASE_PREPARING",
            OperationPhase::Executing => "OPERATION_PHASE_EXECUTING",
            OperationPhase::Flushing => "OPERATION_PHASE_FLUSHING",
            OperationPhase::Uploading => "OPERATION_PHASE_UPLOADING",
            OperationPhase::Verifying => "OPERATION_PHASE_VERIFYING",
            OperationPhase::Completing => "OPERATION_PHASE_COMPLETING",
        }
    }
    /// Creates an enum from field names used in the ProtoBuf definition.
    pub fn from_str_name(value: &str) -> ::core::option::Option<Self> {
        match value {
            "OPERATION_PHASE_UNSPECIFIED" => Some(Self::Unspecified),
            "OPERATION_PHASE_PREPARING" => Some(Self::Preparing),
            "OPERATION_PHASE_EXECUTING" => Some(Self::Executing),
            "OPERATION_PHASE_FLUSHING" => Some(Self::Flushing),
            "OPERATION_PHASE_UPLOADING" => Some(Self::Uploading),
            "OPERATION_PHASE_VERIFYING" => Some(Self::Verifying),
            "OPERATION_PHASE_COMPLETING" => Some(Self::Completing),
            _ => None,
        }
    }
}
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash, PartialOrd, Ord, ::prost::Enumeration)]
#[repr(i32)]
pub enum OperationState {
    Unspecified = 0,
    Active = 1,
    Committed = 2,
    Aborted = 3,
    TimedOut = 4,
}
impl OperationState {
    /// String value of the enum field names used in the ProtoBuf definition.
    ///
    /// The values are not transformed in any way and thus are considered stable
    /// (if the ProtoBuf definition does not change) and safe for programmatic use.
    pub fn as_str_name(&self) -> &'static str {
        match self {
            OperationState::Unspecified => "OPERATION_STATE_UNSPECIFIED",
            OperationState::Active => "OPERATION_STATE_ACTIVE",
            OperationState::Committed => "OPERATION_STATE_COMMITTED",
            OperationState::Aborted => "OPERATION_STATE_ABORTED",
            OperationState::TimedOut => "OPERATION_STATE_TIMED_OUT",
        }
    }
    /// Creates an enum from field names used in the ProtoBuf definition.
    pub fn from_str_name(value: &str) -> ::core::option::Option<Self> {
        match value {
            "OPERATION_STATE_UNSPECIFIED" => Some(Self::Unspecified),
            "OPERATION_STATE_ACTIVE" => Some(Self::Active),
            "OPERATION_STATE_COMMITTED" => Some(Self::Committed),
            "OPERATION_STATE_ABORTED" => Some(Self::Aborted),
            "OPERATION_STATE_TIMED_OUT" => Some(Self::TimedOut),
            _ => None,
        }
    }
}
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash, PartialOrd, Ord, ::prost::Enumeration)]
#[repr(i32)]
pub enum StateQuality {
    Unspecified = 0,
    Complete = 1,
    DepthLimited = 2,
    Resyncing = 3,
    Degraded = 4,
    Invalid = 5,
    VisionRequired = 6,
    HumanRequired = 7,
}
impl StateQuality {
    /// String value of the enum field names used in the ProtoBuf definition.
    ///
    /// The values are not transformed in any way and thus are considered stable
    /// (if the ProtoBuf definition does not change) and safe for programmatic use.
    pub fn as_str_name(&self) -> &'static str {
        match self {
            StateQuality::Unspecified => "STATE_QUALITY_UNSPECIFIED",
            StateQuality::Complete => "STATE_QUALITY_COMPLETE",
            StateQuality::DepthLimited => "STATE_QUALITY_DEPTH_LIMITED",
            StateQuality::Resyncing => "STATE_QUALITY_RESYNCING",
            StateQuality::Degraded => "STATE_QUALITY_DEGRADED",
            StateQuality::Invalid => "STATE_QUALITY_INVALID",
            StateQuality::VisionRequired => "STATE_QUALITY_VISION_REQUIRED",
            StateQuality::HumanRequired => "STATE_QUALITY_HUMAN_REQUIRED",
        }
    }
    /// Creates an enum from field names used in the ProtoBuf definition.
    pub fn from_str_name(value: &str) -> ::core::option::Option<Self> {
        match value {
            "STATE_QUALITY_UNSPECIFIED" => Some(Self::Unspecified),
            "STATE_QUALITY_COMPLETE" => Some(Self::Complete),
            "STATE_QUALITY_DEPTH_LIMITED" => Some(Self::DepthLimited),
            "STATE_QUALITY_RESYNCING" => Some(Self::Resyncing),
            "STATE_QUALITY_DEGRADED" => Some(Self::Degraded),
            "STATE_QUALITY_INVALID" => Some(Self::Invalid),
            "STATE_QUALITY_VISION_REQUIRED" => Some(Self::VisionRequired),
            "STATE_QUALITY_HUMAN_REQUIRED" => Some(Self::HumanRequired),
            _ => None,
        }
    }
}
// @@protoc_insertion_point(module)
