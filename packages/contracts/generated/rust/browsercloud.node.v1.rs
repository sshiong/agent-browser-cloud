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