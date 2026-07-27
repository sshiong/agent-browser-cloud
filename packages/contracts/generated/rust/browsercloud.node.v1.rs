// @generated
#[allow(clippy::derive_partial_eq_without_eq)]
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct PingRequest {
    #[prost(string, tag="1")]
    pub caller_id: ::prost::alloc::string::String,
}
#[allow(clippy::derive_partial_eq_without_eq)]
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct PingResponse {
    #[prost(string, tag="1")]
    pub node_id: ::prost::alloc::string::String,
    #[prost(string, tag="2")]
    pub service_version: ::prost::alloc::string::String,
    #[prost(int64, tag="3")]
    pub unix_time_ms: i64,
}
#[allow(clippy::derive_partial_eq_without_eq)]
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct CommandAck {
    #[prost(string, tag="1")]
    pub message_id: ::prost::alloc::string::String,
    #[prost(bool, tag="2")]
    pub accepted: bool,
    #[prost(bool, tag="3")]
    pub duplicate: bool,
    #[prost(string, tag="4")]
    pub error_code: ::prost::alloc::string::String,
    #[prost(string, tag="5")]
    pub error_message: ::prost::alloc::string::String,
}
#[allow(clippy::derive_partial_eq_without_eq)]
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct DispatchRequest {
    #[prost(message, optional, tag="1")]
    pub command: ::core::option::Option<CommandEnvelope>,
}
#[allow(clippy::derive_partial_eq_without_eq)]
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct DispatchResponse {
    #[prost(message, optional, tag="1")]
    pub acknowledgement: ::core::option::Option<CommandAck>,
}
#[allow(clippy::derive_partial_eq_without_eq)]
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct PublishRequest {
    #[prost(message, optional, tag="1")]
    pub event: ::core::option::Option<EventEnvelope>,
}
#[allow(clippy::derive_partial_eq_without_eq)]
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct PublishResponse {
    #[prost(string, tag="1")]
    pub event_id: ::prost::alloc::string::String,
    #[prost(bool, tag="2")]
    pub accepted: bool,
    #[prost(bool, tag="3")]
    pub duplicate: bool,
    #[prost(string, tag="4")]
    pub error_code: ::prost::alloc::string::String,
    #[prost(string, tag="5")]
    pub error_message: ::prost::alloc::string::String,
}
#[allow(clippy::derive_partial_eq_without_eq)]
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct ReportCapacityRequest {
    #[prost(string, tag="1")]
    pub node_id: ::prost::alloc::string::String,
    #[prost(string, tag="2")]
    pub region: ::prost::alloc::string::String,
    #[prost(string, tag="3")]
    pub grpc_target: ::prost::alloc::string::String,
    #[prost(uint32, tag="4")]
    pub certified_cpu_millis: u32,
    #[prost(uint32, tag="5")]
    pub certified_memory_mib: u32,
    #[prost(uint32, tag="6")]
    pub certified_pid_count: u32,
    #[prost(uint32, tag="7")]
    pub certified_gpu_slots: u32,
    #[prost(uint32, tag="8")]
    pub safety_margin_percent: u32,
    #[prost(uint32, tag="9")]
    pub max_sessions: u32,
    #[prost(bool, tag="10")]
    pub supports_desktop: bool,
    #[prost(bool, tag="11")]
    pub supports_gpu: bool,
    #[prost(bool, tag="12")]
    pub supports_native_os: bool,
    #[prost(bool, tag="13")]
    pub isolation_capable: bool,
    #[prost(map="string, string", tag="14")]
    pub labels: ::std::collections::HashMap<::prost::alloc::string::String, ::prost::alloc::string::String>,
    #[prost(uint32, tag="15")]
    pub certified_media_slots: u32,
    #[prost(bool, tag="16")]
    pub supports_media: bool,
    #[prost(double, tag="20")]
    pub memory_psi_some_avg10: f64,
    #[prost(double, tag="21")]
    pub memory_psi_full_avg10: f64,
    #[prost(double, tag="22")]
    pub cpu_psi_some_avg10: f64,
    #[prost(double, tag="23")]
    pub io_psi_full_avg10: f64,
    #[prost(string, tag="24")]
    pub pressure_reason: ::prost::alloc::string::String,
}
#[allow(clippy::derive_partial_eq_without_eq)]
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct ReportCapacityResponse {
    #[prost(string, tag="1")]
    pub node_id: ::prost::alloc::string::String,
    #[prost(bool, tag="2")]
    pub accepted: bool,
    #[prost(string, tag="3")]
    pub admission_state: ::prost::alloc::string::String,
    #[prost(string, tag="4")]
    pub pressure_state: ::prost::alloc::string::String,
    #[prost(string, tag="5")]
    pub error_code: ::prost::alloc::string::String,
    #[prost(string, tag="6")]
    pub error_message: ::prost::alloc::string::String,
}
#[allow(clippy::derive_partial_eq_without_eq)]
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct ReportSessionResourcesRequest {
    #[prost(string, tag="1")]
    pub node_id: ::prost::alloc::string::String,
    #[prost(string, tag="2")]
    pub tenant_id: ::prost::alloc::string::String,
    #[prost(string, tag="3")]
    pub session_id: ::prost::alloc::string::String,
    #[prost(int64, tag="4")]
    pub context_epoch: i64,
    #[prost(int64, tag="5")]
    pub observed_at_ms: i64,
    #[prost(double, optional, tag="10")]
    pub cpu_percent: ::core::option::Option<f64>,
    #[prost(uint64, optional, tag="11")]
    pub memory_rss_mib: ::core::option::Option<u64>,
    #[prost(double, optional, tag="12")]
    pub memory_psi_some_avg10: ::core::option::Option<f64>,
    #[prost(uint32, optional, tag="13")]
    pub renderer_count: ::core::option::Option<u32>,
    #[prost(uint32, optional, tag="14")]
    pub tab_count: ::core::option::Option<u32>,
    #[prost(uint32, optional, tag="15")]
    pub main_thread_blocked_ms: ::core::option::Option<u32>,
    #[prost(uint32, optional, tag="16")]
    pub agent_action_latency_ms: ::core::option::Option<u32>,
    #[prost(uint32, optional, tag="17")]
    pub state_diff_queue_depth: ::core::option::Option<u32>,
    #[prost(uint64, optional, tag="18")]
    pub profile_io_bytes_per_second: ::core::option::Option<u64>,
    #[prost(double, optional, tag="19")]
    pub extension_cpu_percent: ::core::option::Option<f64>,
    #[prost(uint64, optional, tag="20")]
    pub extension_memory_mib: ::core::option::Option<u64>,
    #[prost(uint32, optional, tag="21")]
    pub remote_desktop_frame_age_ms: ::core::option::Option<u32>,
    #[prost(double, optional, tag="22")]
    pub media_encoder_percent: ::core::option::Option<f64>,
    #[prost(string, tag="23")]
    pub danger_event: ::prost::alloc::string::String,
    #[prost(bool, optional, tag="24")]
    pub input_active: ::core::option::Option<bool>,
    #[prost(bool, optional, tag="25")]
    pub active_drag: ::core::option::Option<bool>,
    #[prost(uint32, optional, tag="26")]
    pub pressed_key_count: ::core::option::Option<u32>,
    #[prost(uint32, optional, tag="27")]
    pub pressed_button_count: ::core::option::Option<u32>,
}
#[allow(clippy::derive_partial_eq_without_eq)]
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct ReportSessionResourcesResponse {
    #[prost(string, tag="1")]
    pub session_id: ::prost::alloc::string::String,
    #[prost(bool, tag="2")]
    pub accepted: bool,
    #[prost(string, tag="3")]
    pub error_code: ::prost::alloc::string::String,
    #[prost(string, tag="4")]
    pub error_message: ::prost::alloc::string::String,
}
/// 命令信封
#[allow(clippy::derive_partial_eq_without_eq)]
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct CommandEnvelope {
    #[prost(string, tag="1")]
    pub message_id: ::prost::alloc::string::String,
    #[prost(string, tag="2")]
    pub command_type: ::prost::alloc::string::String,
    #[prost(string, tag="3")]
    pub tenant_id: ::prost::alloc::string::String,
    #[prost(string, tag="4")]
    pub session_id: ::prost::alloc::string::String,
    /// 版本控制
    #[prost(int64, tag="10")]
    pub coordinator_term: i64,
    #[prost(int64, tag="11")]
    pub context_epoch: i64,
    #[prost(int64, tag="12")]
    pub operation_epoch: i64,
    /// 幂等
    #[prost(string, tag="20")]
    pub idempotency_key: ::prost::alloc::string::String,
    #[prost(bytes="vec", tag="21")]
    pub payload: ::prost::alloc::vec::Vec<u8>,
}
/// 事件信封
#[allow(clippy::derive_partial_eq_without_eq)]
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct EventEnvelope {
    #[prost(string, tag="1")]
    pub event_id: ::prost::alloc::string::String,
    #[prost(string, tag="2")]
    pub event_type: ::prost::alloc::string::String,
    #[prost(string, tag="3")]
    pub tenant_id: ::prost::alloc::string::String,
    #[prost(string, tag="4")]
    pub session_id: ::prost::alloc::string::String,
    /// 版本控制
    #[prost(int64, tag="10")]
    pub coordinator_term: i64,
    #[prost(int64, tag="11")]
    pub context_epoch: i64,
    #[prost(int64, tag="12")]
    pub operation_epoch: i64,
    #[prost(int64, tag="13")]
    pub sequence: i64,
    #[prost(bytes="vec", tag="20")]
    pub payload: ::prost::alloc::vec::Vec<u8>,
}
/// 启动 Runtime 命令
#[allow(clippy::derive_partial_eq_without_eq)]
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct StartRuntimeCommand {
    #[prost(string, tag="1")]
    pub session_id: ::prost::alloc::string::String,
    #[prost(string, tag="2")]
    pub runtime_build_id: ::prost::alloc::string::String,
    #[prost(string, tag="3")]
    pub profile_id: ::prost::alloc::string::String,
    #[prost(string, tag="4")]
    pub display: ::prost::alloc::string::String,
    #[prost(int32, tag="5")]
    pub cdp_port: i32,
    #[prost(string, tag="6")]
    pub proxy_binding_id: ::prost::alloc::string::String,
    #[prost(string, tag="7")]
    pub resource_class: ::prost::alloc::string::String,
    #[prost(uint32, tag="8")]
    pub cpu_millis: u32,
    #[prost(uint32, tag="9")]
    pub memory_request_mib: u32,
    #[prost(uint32, tag="10")]
    pub memory_limit_mib: u32,
    #[prost(uint32, tag="11")]
    pub pid_limit: u32,
    #[prost(uint32, tag="12")]
    pub tab_budget: u32,
    #[prost(bool, tag="13")]
    pub desktop_required: bool,
    #[prost(bool, tag="14")]
    pub gpu_required: bool,
    #[prost(bool, tag="15")]
    pub native_os_required: bool,
    #[prost(bool, tag="16")]
    pub isolation_required: bool,
    #[prost(string, tag="17")]
    pub profile_checkpoint_id: ::prost::alloc::string::String,
    /// N-1 Node 不识别时安全忽略；缺失表示使用 Node 安全默认值。
    #[prost(uint32, optional, tag="18")]
    pub state_collector_budget_percent: ::core::option::Option<u32>,
    #[prost(uint32, optional, tag="19")]
    pub remote_desktop_bitrate_kbps: ::core::option::Option<u32>,
}
/// Runtime 启动事件
#[allow(clippy::derive_partial_eq_without_eq)]
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct RuntimeStartedEvent {
    #[prost(string, tag="1")]
    pub session_id: ::prost::alloc::string::String,
    #[prost(uint32, tag="2")]
    pub pid: u32,
    #[prost(uint64, tag="3")]
    pub browser_generation: u64,
    #[prost(string, tag="4")]
    pub cdp_endpoint: ::prost::alloc::string::String,
    #[prost(string, tag="5")]
    pub node_id: ::prost::alloc::string::String,
    #[prost(string, tag="6")]
    pub runtime_build_id: ::prost::alloc::string::String,
    #[prost(string, tag="7")]
    pub proxy_binding_id: ::prost::alloc::string::String,
    #[prost(string, tag="8")]
    pub exit_ip: ::prost::alloc::string::String,
    #[prost(string, tag="9")]
    pub exit_country: ::prost::alloc::string::String,
    #[prost(string, tag="10")]
    pub exit_asn: ::prost::alloc::string::String,
}
/// 停止 Runtime 命令
#[allow(clippy::derive_partial_eq_without_eq)]
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct StopRuntimeCommand {
    #[prost(string, tag="1")]
    pub session_id: ::prost::alloc::string::String,
    #[prost(string, tag="2")]
    pub reason: ::prost::alloc::string::String,
}
/// Runtime 停止事件
#[allow(clippy::derive_partial_eq_without_eq)]
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct RuntimeStoppedEvent {
    #[prost(string, tag="1")]
    pub session_id: ::prost::alloc::string::String,
    #[prost(string, tag="2")]
    pub reason: ::prost::alloc::string::String,
    #[prost(int32, tag="3")]
    pub exit_code: i32,
    #[prost(string, tag="4")]
    pub profile_id: ::prost::alloc::string::String,
    #[prost(string, tag="5")]
    pub checkpoint_id: ::prost::alloc::string::String,
    #[prost(uint64, tag="6")]
    pub checkpoint_epoch: u64,
    #[prost(uint64, tag="7")]
    pub profile_write_epoch: u64,
    #[prost(uint64, tag="8")]
    pub core_size_bytes: u64,
    #[prost(uint64, tag="9")]
    pub checkpoint_file_count: u64,
    #[prost(string, tag="10")]
    pub restore_status: ::prost::alloc::string::String,
}
/// 对运行中的 Runtime 执行同节点资源调整。资源调整只由 Control Plane 发起。
#[allow(clippy::derive_partial_eq_without_eq)]
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct AdjustRuntimeResourcesCommand {
    #[prost(string, tag="1")]
    pub session_id: ::prost::alloc::string::String,
    #[prost(string, tag="2")]
    pub resource_class: ::prost::alloc::string::String,
    #[prost(uint32, tag="3")]
    pub cpu_millis: u32,
    #[prost(uint32, tag="4")]
    pub memory_request_mib: u32,
    #[prost(uint32, tag="5")]
    pub memory_limit_mib: u32,
    #[prost(uint32, tag="6")]
    pub pid_limit: u32,
    #[prost(uint32, tag="7")]
    pub tab_budget: u32,
    #[prost(string, tag="8")]
    pub reason: ::prost::alloc::string::String,
    #[prost(bool, tag="9")]
    pub desktop_required: bool,
    #[prost(bool, tag="10")]
    pub gpu_required: bool,
    #[prost(bool, tag="11")]
    pub native_os_required: bool,
    #[prost(bool, tag="12")]
    pub isolation_required: bool,
    /// optional 保证 N/N-1 滚动升级：缺失时保持当前非 Cgroup 配置。
    #[prost(uint32, optional, tag="13")]
    pub state_collector_budget_percent: ::core::option::Option<u32>,
    #[prost(uint32, optional, tag="14")]
    pub remote_desktop_bitrate_kbps: ::core::option::Option<u32>,
}
/// Node 完成 cgroup 调整后返回的权威确认；Control Plane 收到前不得更新当前分配。
#[allow(clippy::derive_partial_eq_without_eq)]
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct RuntimeResourcesAdjustedEvent {
    #[prost(string, tag="1")]
    pub session_id: ::prost::alloc::string::String,
    #[prost(string, tag="2")]
    pub node_id: ::prost::alloc::string::String,
    #[prost(string, tag="3")]
    pub old_resource_class: ::prost::alloc::string::String,
    #[prost(uint32, tag="4")]
    pub old_cpu_millis: u32,
    #[prost(uint32, tag="5")]
    pub old_memory_request_mib: u32,
    #[prost(uint32, tag="6")]
    pub old_memory_limit_mib: u32,
    #[prost(uint32, tag="7")]
    pub old_pid_limit: u32,
    #[prost(uint32, tag="8")]
    pub old_tab_budget: u32,
    #[prost(string, tag="9")]
    pub new_resource_class: ::prost::alloc::string::String,
    #[prost(uint32, tag="10")]
    pub new_cpu_millis: u32,
    #[prost(uint32, tag="11")]
    pub new_memory_request_mib: u32,
    #[prost(uint32, tag="12")]
    pub new_memory_limit_mib: u32,
    #[prost(uint32, tag="13")]
    pub new_pid_limit: u32,
    #[prost(uint32, tag="14")]
    pub new_tab_budget: u32,
    #[prost(string, tag="15")]
    pub reason: ::prost::alloc::string::String,
    #[prost(string, tag="16")]
    pub operation_id: ::prost::alloc::string::String,
    /// N-1 Node 不会上报这些字段；Control Plane 此时只提交已确认的 Cgroup 调整。
    #[prost(uint32, optional, tag="17")]
    pub old_state_collector_budget_percent: ::core::option::Option<u32>,
    #[prost(uint32, optional, tag="18")]
    pub old_remote_desktop_bitrate_kbps: ::core::option::Option<u32>,
    #[prost(uint32, optional, tag="19")]
    pub new_state_collector_budget_percent: ::core::option::Option<u32>,
    #[prost(uint32, optional, tag="20")]
    pub new_remote_desktop_bitrate_kbps: ::core::option::Option<u32>,
}
/// Browser Crash 事件
#[allow(clippy::derive_partial_eq_without_eq)]
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct BrowserCrashEvent {
    #[prost(string, tag="1")]
    pub session_id: ::prost::alloc::string::String,
    #[prost(string, tag="2")]
    pub crash_type: ::prost::alloc::string::String,
    #[prost(string, tag="3")]
    pub reason: ::prost::alloc::string::String,
    #[prost(int64, tag="4")]
    pub detected_at_ms: i64,
}
/// 释放所有输入命令
#[allow(clippy::derive_partial_eq_without_eq)]
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct ReleaseAllInputCommand {
    #[prost(string, tag="1")]
    pub session_id: ::prost::alloc::string::String,
    #[prost(string, tag="2")]
    pub reason: ::prost::alloc::string::String,
}
#[allow(clippy::derive_partial_eq_without_eq)]
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct BeginHumanTakeoverCommand {
    #[prost(string, tag="1")]
    pub session_id: ::prost::alloc::string::String,
    #[prost(string, tag="2")]
    pub user_id: ::prost::alloc::string::String,
}
#[allow(clippy::derive_partial_eq_without_eq)]
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct EndHumanTakeoverCommand {
    #[prost(string, tag="1")]
    pub session_id: ::prost::alloc::string::String,
    #[prost(string, tag="2")]
    pub user_id: ::prost::alloc::string::String,
}
#[allow(clippy::derive_partial_eq_without_eq)]
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct HumanTakeoverReadyEvent {
    #[prost(string, tag="1")]
    pub session_id: ::prost::alloc::string::String,
    #[prost(string, tag="2")]
    pub user_id: ::prost::alloc::string::String,
    #[prost(message, optional, tag="3")]
    pub state: ::core::option::Option<BrowserStateEvent>,
}
#[allow(clippy::derive_partial_eq_without_eq)]
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct HumanTakeoverEndedEvent {
    #[prost(string, tag="1")]
    pub session_id: ::prost::alloc::string::String,
    #[prost(string, tag="2")]
    pub user_id: ::prost::alloc::string::String,
    #[prost(message, optional, tag="3")]
    pub state: ::core::option::Option<BrowserStateEvent>,
    /// USER_RELEASE 表示显式结束；GATEWAY_DISCONNECT 表示远程桌面数据面断线。
    #[prost(string, tag="4")]
    pub reason: ::prost::alloc::string::String,
}
/// 单个有序输入命令。每个 CommandEnvelope 只承载一个动作，便于幂等重放。
#[allow(clippy::derive_partial_eq_without_eq)]
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct ExecuteInputCommand {
    #[prost(string, tag="1")]
    pub session_id: ::prost::alloc::string::String,
    #[prost(uint64, tag="2")]
    pub sequence: u64,
    #[prost(oneof="execute_input_command::Action", tags="10, 11, 12, 13, 14")]
    pub action: ::core::option::Option<execute_input_command::Action>,
}
/// Nested message and enum types in `ExecuteInputCommand`.
pub mod execute_input_command {
    #[allow(clippy::derive_partial_eq_without_eq)]
#[derive(Clone, PartialEq, ::prost::Oneof)]
    pub enum Action {
        #[prost(message, tag="10")]
        MouseMove(super::MouseMoveInput),
        #[prost(message, tag="11")]
        MouseDown(super::MouseButtonInput),
        #[prost(message, tag="12")]
        MouseUp(super::MouseButtonInput),
        #[prost(message, tag="13")]
        KeyDown(super::KeyInput),
        #[prost(message, tag="14")]
        KeyUp(super::KeyInput),
    }
}
#[allow(clippy::derive_partial_eq_without_eq)]
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct MouseMoveInput {
    #[prost(int32, tag="1")]
    pub x: i32,
    #[prost(int32, tag="2")]
    pub y: i32,
}
#[allow(clippy::derive_partial_eq_without_eq)]
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct MouseButtonInput {
    #[prost(uint32, tag="1")]
    pub button: u32,
}
#[allow(clippy::derive_partial_eq_without_eq)]
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct KeyInput {
    #[prost(string, tag="1")]
    pub key: ::prost::alloc::string::String,
}
#[allow(clippy::derive_partial_eq_without_eq)]
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct BrowserStateEvent {
    #[prost(string, tag="1")]
    pub session_id: ::prost::alloc::string::String,
    #[prost(uint64, tag="2")]
    pub state_version: u64,
    #[prost(uint64, tag="3")]
    pub target_revision: u64,
    #[prost(string, tag="4")]
    pub url: ::prost::alloc::string::String,
    #[prost(string, tag="5")]
    pub title: ::prost::alloc::string::String,
    #[prost(string, tag="6")]
    pub state_quality: ::prost::alloc::string::String,
    #[prost(string, tag="7")]
    pub content_hash: ::prost::alloc::string::String,
    #[prost(message, repeated, tag="8")]
    pub targets: ::prost::alloc::vec::Vec<InteractiveTargetState>,
    /// PERIODIC、FULL_RESYNC 或 REGION_RESYNC_FULL_FALLBACK。
    #[prost(string, tag="9")]
    pub snapshot_kind: ::prost::alloc::string::String,
    #[prost(string, tag="10")]
    pub requested_root_ref: ::prost::alloc::string::String,
}
/// Control Plane 请求重建 State。REGION 在首版无法安全裁剪时必须显式回退 FULL。
#[allow(clippy::derive_partial_eq_without_eq)]
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct RequestStateResyncCommand {
    #[prost(string, tag="1")]
    pub session_id: ::prost::alloc::string::String,
    #[prost(string, tag="2")]
    pub mode: ::prost::alloc::string::String,
    #[prost(string, tag="3")]
    pub root_ref: ::prost::alloc::string::String,
    #[prost(string, tag="4")]
    pub reason: ::prost::alloc::string::String,
}
/// 由 Control Plane Agent Executor 授权并通过 Exclusive Operation 投递的导航命令。
/// Capability Token 只在 Control Plane 内消费，绝不下发到 Browser Node。
#[allow(clippy::derive_partial_eq_without_eq)]
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct AgentNavigateCommand {
    #[prost(string, tag="1")]
    pub session_id: ::prost::alloc::string::String,
    #[prost(string, tag="2")]
    pub task_id: ::prost::alloc::string::String,
    #[prost(string, tag="3")]
    pub step_id: ::prost::alloc::string::String,
    #[prost(string, tag="4")]
    pub url: ::prost::alloc::string::String,
    #[prost(uint64, tag="5")]
    pub base_state_version: u64,
}
#[allow(clippy::derive_partial_eq_without_eq)]
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct AgentNavigationFailedEvent {
    #[prost(string, tag="1")]
    pub session_id: ::prost::alloc::string::String,
    #[prost(string, tag="2")]
    pub task_id: ::prost::alloc::string::String,
    #[prost(string, tag="3")]
    pub step_id: ::prost::alloc::string::String,
    #[prost(string, tag="4")]
    pub error_code: ::prost::alloc::string::String,
}
/// 受限 Agent Target/Input 命令。sealed_text 在 Outbox 中保持加密，
/// Dispatcher 只在发送前解封到 text；Node 从不接受任意 CDP/Shell 指令。
#[allow(clippy::derive_partial_eq_without_eq)]
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct AgentActionCommand {
    #[prost(string, tag="1")]
    pub session_id: ::prost::alloc::string::String,
    #[prost(string, tag="2")]
    pub task_id: ::prost::alloc::string::String,
    #[prost(string, tag="3")]
    pub step_id: ::prost::alloc::string::String,
    #[prost(string, tag="4")]
    pub tool_id: ::prost::alloc::string::String,
    #[prost(string, tag="5")]
    pub target_ref: ::prost::alloc::string::String,
    #[prost(uint64, tag="6")]
    pub target_revision: u64,
    #[prost(string, tag="7")]
    pub sealed_text: ::prost::alloc::string::String,
    #[prost(string, tag="8")]
    pub text: ::prost::alloc::string::String,
    #[prost(int32, tag="9")]
    pub scroll_delta_y: i32,
    #[prost(string, tag="10")]
    pub wait_condition: ::prost::alloc::string::String,
    #[prost(uint32, tag="11")]
    pub timeout_ms: u32,
    #[prost(uint64, tag="12")]
    pub base_state_version: u64,
    #[prost(string, tag="13")]
    pub base_content_hash: ::prost::alloc::string::String,
}
#[allow(clippy::derive_partial_eq_without_eq)]
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct AgentActionFailedEvent {
    #[prost(string, tag="1")]
    pub session_id: ::prost::alloc::string::String,
    #[prost(string, tag="2")]
    pub task_id: ::prost::alloc::string::String,
    #[prost(string, tag="3")]
    pub step_id: ::prost::alloc::string::String,
    #[prost(string, tag="4")]
    pub tool_id: ::prost::alloc::string::String,
    #[prost(string, tag="5")]
    pub error_code: ::prost::alloc::string::String,
}
#[allow(clippy::derive_partial_eq_without_eq)]
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct BrowserStateDiffEvent {
    #[prost(string, tag="1")]
    pub session_id: ::prost::alloc::string::String,
    #[prost(uint64, tag="2")]
    pub base_state_version: u64,
    #[prost(uint64, tag="3")]
    pub state_version: u64,
    #[prost(uint64, tag="4")]
    pub target_revision: u64,
    #[prost(string, tag="5")]
    pub url: ::prost::alloc::string::String,
    #[prost(string, tag="6")]
    pub title: ::prost::alloc::string::String,
    #[prost(string, tag="7")]
    pub state_quality: ::prost::alloc::string::String,
    #[prost(string, tag="8")]
    pub content_hash: ::prost::alloc::string::String,
    #[prost(message, repeated, tag="9")]
    pub upserted_targets: ::prost::alloc::vec::Vec<InteractiveTargetState>,
    #[prost(string, repeated, tag="10")]
    pub removed_target_refs: ::prost::alloc::vec::Vec<::prost::alloc::string::String>,
}
#[allow(clippy::derive_partial_eq_without_eq)]
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct DiffTruncatedEvent {
    #[prost(string, tag="1")]
    pub session_id: ::prost::alloc::string::String,
    #[prost(string, tag="2")]
    pub reason: ::prost::alloc::string::String,
    #[prost(uint64, tag="3")]
    pub last_good_state_version: u64,
    #[prost(uint64, tag="4")]
    pub current_state_version: u64,
    #[prost(string, tag="5")]
    pub affected_root: ::prost::alloc::string::String,
    #[prost(uint64, tag="6")]
    pub estimated_targets: u64,
}
#[allow(clippy::derive_partial_eq_without_eq)]
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct InteractiveTargetState {
    #[prost(string, tag="1")]
    pub target_ref: ::prost::alloc::string::String,
    #[prost(string, tag="2")]
    pub role: ::prost::alloc::string::String,
    #[prost(string, optional, tag="3")]
    pub name: ::core::option::Option<::prost::alloc::string::String>,
    #[prost(message, optional, tag="4")]
    pub bounds: ::core::option::Option<TargetBounds>,
    #[prost(bool, tag="5")]
    pub enabled: bool,
    #[prost(bool, tag="6")]
    pub visible: bool,
    /// Password/OTP 等目标只暴露敏感标志，不暴露名称或值。
    #[prost(bool, tag="7")]
    pub sensitive: bool,
}
#[allow(clippy::derive_partial_eq_without_eq)]
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct TargetBounds {
    #[prost(double, tag="1")]
    pub x: f64,
    #[prost(double, tag="2")]
    pub y: f64,
    #[prost(double, tag="3")]
    pub width: f64,
    #[prost(double, tag="4")]
    pub height: f64,
}
include!("browsercloud.node.v1.tonic.rs");
// @@protoc_insertion_point(module)