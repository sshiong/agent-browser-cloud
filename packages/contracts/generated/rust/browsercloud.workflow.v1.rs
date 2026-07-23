// @generated
#[allow(clippy::derive_partial_eq_without_eq)]
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct WorkflowExecution {
    #[prost(string, tag="1")]
    pub workflow_id: ::prost::alloc::string::String,
    #[prost(string, tag="2")]
    pub session_id: ::prost::alloc::string::String,
    #[prost(string, tag="3")]
    pub operation_id: ::prost::alloc::string::String,
    #[prost(string, tag="4")]
    pub workflow_type: ::prost::alloc::string::String,
    #[prost(int32, tag="5")]
    pub attempt: i32,
    #[prost(int32, tag="6")]
    pub priority: i32,
    #[prost(enumeration="WorkflowState", tag="7")]
    pub state: i32,
    #[prost(string, tag="8")]
    pub phase: ::prost::alloc::string::String,
    #[prost(string, tag="9")]
    pub worker_id: ::prost::alloc::string::String,
    #[prost(int64, tag="10")]
    pub coordinator_term: i64,
    #[prost(int64, tag="11")]
    pub context_epoch: i64,
    #[prost(int64, tag="12")]
    pub operation_epoch: i64,
    #[prost(int64, tag="13")]
    pub cancellation_epoch: i64,
    #[prost(int64, tag="20")]
    pub dispatched_at_ms: i64,
    #[prost(int64, tag="21")]
    pub started_at_ms: i64,
    #[prost(int64, tag="22")]
    pub heartbeat_at_ms: i64,
    #[prost(int64, tag="23")]
    pub phase_deadline_ms: i64,
    #[prost(int64, tag="24")]
    pub operation_deadline_ms: i64,
    #[prost(string, tag="30")]
    pub idempotency_key: ::prost::alloc::string::String,
    #[prost(string, tag="31")]
    pub external_receipt: ::prost::alloc::string::String,
    #[prost(string, tag="32")]
    pub failure_reason: ::prost::alloc::string::String,
}
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash, PartialOrd, Ord, ::prost::Enumeration)]
#[repr(i32)]
pub enum WorkflowState {
    Unspecified = 0,
    Pending = 1,
    Dispatched = 2,
    Running = 3,
    Completing = 4,
    Completed = 5,
    Failed = 6,
    Cancelled = 7,
    TimedOut = 8,
    Orphaned = 9,
    Compensating = 10,
    Compensated = 11,
    DeadLetter = 12,
}
impl WorkflowState {
    /// String value of the enum field names used in the ProtoBuf definition.
    ///
    /// The values are not transformed in any way and thus are considered stable
    /// (if the ProtoBuf definition does not change) and safe for programmatic use.
    pub fn as_str_name(&self) -> &'static str {
        match self {
            WorkflowState::Unspecified => "WORKFLOW_STATE_UNSPECIFIED",
            WorkflowState::Pending => "WORKFLOW_STATE_PENDING",
            WorkflowState::Dispatched => "WORKFLOW_STATE_DISPATCHED",
            WorkflowState::Running => "WORKFLOW_STATE_RUNNING",
            WorkflowState::Completing => "WORKFLOW_STATE_COMPLETING",
            WorkflowState::Completed => "WORKFLOW_STATE_COMPLETED",
            WorkflowState::Failed => "WORKFLOW_STATE_FAILED",
            WorkflowState::Cancelled => "WORKFLOW_STATE_CANCELLED",
            WorkflowState::TimedOut => "WORKFLOW_STATE_TIMED_OUT",
            WorkflowState::Orphaned => "WORKFLOW_STATE_ORPHANED",
            WorkflowState::Compensating => "WORKFLOW_STATE_COMPENSATING",
            WorkflowState::Compensated => "WORKFLOW_STATE_COMPENSATED",
            WorkflowState::DeadLetter => "WORKFLOW_STATE_DEAD_LETTER",
        }
    }
    /// Creates an enum from field names used in the ProtoBuf definition.
    pub fn from_str_name(value: &str) -> ::core::option::Option<Self> {
        match value {
            "WORKFLOW_STATE_UNSPECIFIED" => Some(Self::Unspecified),
            "WORKFLOW_STATE_PENDING" => Some(Self::Pending),
            "WORKFLOW_STATE_DISPATCHED" => Some(Self::Dispatched),
            "WORKFLOW_STATE_RUNNING" => Some(Self::Running),
            "WORKFLOW_STATE_COMPLETING" => Some(Self::Completing),
            "WORKFLOW_STATE_COMPLETED" => Some(Self::Completed),
            "WORKFLOW_STATE_FAILED" => Some(Self::Failed),
            "WORKFLOW_STATE_CANCELLED" => Some(Self::Cancelled),
            "WORKFLOW_STATE_TIMED_OUT" => Some(Self::TimedOut),
            "WORKFLOW_STATE_ORPHANED" => Some(Self::Orphaned),
            "WORKFLOW_STATE_COMPENSATING" => Some(Self::Compensating),
            "WORKFLOW_STATE_COMPENSATED" => Some(Self::Compensated),
            "WORKFLOW_STATE_DEAD_LETTER" => Some(Self::DeadLetter),
            _ => None,
        }
    }
}
// @@protoc_insertion_point(module)
