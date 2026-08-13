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
pub struct ProbeProxyBindingRequest {
    #[prost(string, tag="1")]
    pub probe_id: ::prost::alloc::string::String,
    #[prost(string, tag="2")]
    pub tenant_id: ::prost::alloc::string::String,
    #[prost(string, tag="3")]
    pub binding_profile_id: ::prost::alloc::string::String,
    #[prost(string, tag="4")]
    pub provider_id: ::prost::alloc::string::String,
    #[prost(string, tag="5")]
    pub expected_exit_ip: ::prost::alloc::string::String,
    #[prost(string, tag="6")]
    pub credential_ref: ::prost::alloc::string::String,
}
#[allow(clippy::derive_partial_eq_without_eq)]
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct ProbeProxyBindingResponse {
    #[prost(string, tag="1")]
    pub probe_id: ::prost::alloc::string::String,
    #[prost(string, tag="2")]
    pub binding_profile_id: ::prost::alloc::string::String,
    #[prost(string, tag="3")]
    pub node_id: ::prost::alloc::string::String,
    #[prost(bool, tag="4")]
    pub succeeded: bool,
    #[prost(uint32, tag="5")]
    pub latency_ms: u32,
    #[prost(string, optional, tag="6")]
    pub observed_exit_ip: ::core::option::Option<::prost::alloc::string::String>,
    #[prost(string, tag="7")]
    pub error_code: ::prost::alloc::string::String,
}
#[allow(clippy::derive_partial_eq_without_eq)]
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct UploadProfileImportRequest {
    #[prost(string, tag="1")]
    pub import_id: ::prost::alloc::string::String,
    #[prost(string, tag="2")]
    pub tenant_id: ::prost::alloc::string::String,
    #[prost(string, tag="3")]
    pub profile_id: ::prost::alloc::string::String,
    #[prost(string, tag="4")]
    pub checkpoint_id: ::prost::alloc::string::String,
    #[prost(string, tag="5")]
    pub runtime_build_id: ::prost::alloc::string::String,
    #[prost(string, tag="6")]
    pub archive_sha256: ::prost::alloc::string::String,
    #[prost(uint64, tag="7")]
    pub archive_size_bytes: u64,
    #[prost(uint64, tag="8")]
    pub offset: u64,
    #[prost(bytes="vec", tag="9")]
    pub data: ::prost::alloc::vec::Vec<u8>,
}
#[allow(clippy::derive_partial_eq_without_eq)]
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct UploadProfileImportResponse {
    #[prost(string, tag="1")]
    pub import_id: ::prost::alloc::string::String,
    #[prost(string, tag="2")]
    pub node_id: ::prost::alloc::string::String,
    #[prost(string, tag="3")]
    pub profile_id: ::prost::alloc::string::String,
    #[prost(string, tag="4")]
    pub checkpoint_id: ::prost::alloc::string::String,
    #[prost(uint64, tag="5")]
    pub checkpoint_epoch: u64,
    #[prost(uint64, tag="6")]
    pub profile_write_epoch: u64,
    #[prost(uint64, tag="7")]
    pub core_size_bytes: u64,
    #[prost(uint64, tag="8")]
    pub checkpoint_file_count: u64,
    #[prost(string, tag="9")]
    pub archive_sha256: ::prost::alloc::string::String,
    #[prost(uint64, tag="10")]
    pub archive_size_bytes: u64,
}
#[allow(clippy::derive_partial_eq_without_eq)]
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct PresignEvidenceDownloadRequest {
    #[prost(string, tag="1")]
    pub grant_id: ::prost::alloc::string::String,
    #[prost(string, tag="2")]
    pub tenant_id: ::prost::alloc::string::String,
    #[prost(string, tag="3")]
    pub profile_id: ::prost::alloc::string::String,
    #[prost(string, tag="4")]
    pub session_id: ::prost::alloc::string::String,
    #[prost(string, tag="5")]
    pub evidence_id: ::prost::alloc::string::String,
    #[prost(string, tag="6")]
    pub content_sha256: ::prost::alloc::string::String,
    #[prost(uint64, tag="7")]
    pub content_bytes: u64,
    #[prost(uint32, tag="8")]
    pub expires_in_seconds: u32,
}
#[allow(clippy::derive_partial_eq_without_eq)]
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct PresignEvidenceDownloadResponse {
    #[prost(string, tag="1")]
    pub grant_id: ::prost::alloc::string::String,
    #[prost(string, tag="2")]
    pub node_id: ::prost::alloc::string::String,
    #[prost(string, tag="3")]
    pub evidence_id: ::prost::alloc::string::String,
    #[prost(string, tag="4")]
    pub download_url: ::prost::alloc::string::String,
    #[prost(int64, tag="5")]
    pub expires_at_ms: i64,
}
#[allow(clippy::derive_partial_eq_without_eq)]
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct PresignProfileExportDownloadRequest {
    #[prost(string, tag="1")]
    pub grant_id: ::prost::alloc::string::String,
    #[prost(string, tag="2")]
    pub tenant_id: ::prost::alloc::string::String,
    #[prost(string, tag="3")]
    pub profile_id: ::prost::alloc::string::String,
    #[prost(string, tag="4")]
    pub checkpoint_id: ::prost::alloc::string::String,
    #[prost(uint32, tag="5")]
    pub expires_in_seconds: u32,
}
#[allow(clippy::derive_partial_eq_without_eq)]
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct PresignProfileExportDownloadResponse {
    #[prost(string, tag="1")]
    pub grant_id: ::prost::alloc::string::String,
    #[prost(string, tag="2")]
    pub node_id: ::prost::alloc::string::String,
    #[prost(string, tag="3")]
    pub profile_id: ::prost::alloc::string::String,
    #[prost(string, tag="4")]
    pub checkpoint_id: ::prost::alloc::string::String,
    #[prost(string, tag="5")]
    pub archive_sha256: ::prost::alloc::string::String,
    #[prost(uint64, tag="6")]
    pub archive_size_bytes: u64,
    #[prost(string, tag="7")]
    pub download_url: ::prost::alloc::string::String,
    #[prost(int64, tag="8")]
    pub expires_at_ms: i64,
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
    /// 持续 CDP Browser/Network 观察器的活动计数。三项必须同时出现；
    /// 缺失表示观察器尚未形成可信快照，Control Plane 必须按能力标签 fail-closed。
    #[prost(uint32, optional, tag="28")]
    pub active_upload_count: ::core::option::Option<u32>,
    #[prost(uint32, optional, tag="29")]
    pub active_download_count: ::core::option::Option<u32>,
    #[prost(uint32, optional, tag="30")]
    pub active_form_submission_count: ::core::option::Option<u32>,
    /// Credential-free active exit observation. The Network Helper performs the request through the
    /// already-bound Provider route; the Node Agent only reports the bounded result. All four fields
    /// are additive so an N-1 Control Plane safely ignores them during rolling upgrades.
    #[prost(bool, optional, tag="31")]
    pub proxy_probe_succeeded: ::core::option::Option<bool>,
    #[prost(uint32, optional, tag="32")]
    pub proxy_probe_latency_ms: ::core::option::Option<u32>,
    #[prost(string, optional, tag="33")]
    pub proxy_observed_exit_ip: ::core::option::Option<::prost::alloc::string::String>,
    #[prost(string, tag="34")]
    pub proxy_probe_error_code: ::prost::alloc::string::String,
    /// Node 读取运行时实际已生效配置，而不是回显最后一条命令。整组字段必须同时出现；
    /// Control Plane 用该快照恢复永久丢失的资源调整 ACK，并检测 PostgreSQL/Node 漂移。
    /// 字段保持 additive，N-1 Node 缺失整组时 Control Plane 仅跳过 Readback 对账。
    #[prost(string, optional, tag="40")]
    pub actual_resource_class: ::core::option::Option<::prost::alloc::string::String>,
    #[prost(uint32, optional, tag="41")]
    pub actual_cpu_millis: ::core::option::Option<u32>,
    #[prost(uint32, optional, tag="42")]
    pub actual_memory_request_mib: ::core::option::Option<u32>,
    #[prost(uint32, optional, tag="43")]
    pub actual_memory_limit_mib: ::core::option::Option<u32>,
    #[prost(uint32, optional, tag="44")]
    pub actual_pid_limit: ::core::option::Option<u32>,
    #[prost(uint32, optional, tag="45")]
    pub actual_tab_budget: ::core::option::Option<u32>,
    #[prost(uint32, optional, tag="46")]
    pub actual_state_collector_budget_percent: ::core::option::Option<u32>,
    #[prost(uint32, optional, tag="47")]
    pub actual_remote_desktop_bitrate_kbps: ::core::option::Option<u32>,
    #[prost(uint32, optional, tag="48")]
    pub actual_extension_cpu_weight: ::core::option::Option<u32>,
    #[prost(uint32, optional, tag="49")]
    pub actual_media_encoder_slots: ::core::option::Option<u32>,
    #[prost(bool, optional, tag="50")]
    pub actual_freeze_background_tabs: ::core::option::Option<bool>,
    #[prost(bool, optional, tag="51")]
    pub actual_block_new_tabs: ::core::option::Option<bool>,
    #[prost(message, optional, tag="52")]
    pub actual_extension_background_policy: ::core::option::Option<ExtensionBackgroundPolicy>,
    #[prost(uint32, optional, tag="53")]
    pub actual_success_trace_sample_percent: ::core::option::Option<u32>,
    #[prost(uint32, optional, tag="54")]
    pub actual_observer_frame_rate_fps: ::core::option::Option<u32>,
    #[prost(bool, optional, tag="55")]
    pub actual_video_recording_enabled: ::core::option::Option<bool>,
    #[prost(uint32, optional, tag="56")]
    pub actual_success_screenshot_sample_percent: ::core::option::Option<u32>,
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
    /// PostgreSQL authoritative route fencing. Zero is accepted only during N/N-1 rollout.
    #[prost(int64, tag="13")]
    pub route_epoch: i64,
    #[prost(int32, tag="14")]
    pub coordinator_shard_id: i32,
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
    /// 仅传递已由 Control Plane 接纳、并由 Node 从可信目录解析的扩展标识。
    #[prost(string, repeated, tag="20")]
    pub extension_ids: ::prost::alloc::vec::Vec<::prost::alloc::string::String>,
    /// cgroup v2 cpu.weight，N-1 Node 会安全忽略。
    #[prost(uint32, optional, tag="21")]
    pub extension_cpu_weight: ::core::option::Option<u32>,
    /// 当前可用的编码并发 Slot；与 Placement 预留上限分离。
    #[prost(uint32, optional, tag="22")]
    pub media_encoder_slots: ::core::option::Option<u32>,
    #[prost(bool, optional, tag="23")]
    pub freeze_background_tabs: ::core::option::Option<bool>,
    #[prost(bool, optional, tag="24")]
    pub block_new_tabs: ::core::option::Option<bool>,
    /// 只有 Control Plane 已确认非特权的 Extension 才会出现在此策略中。
    #[prost(message, optional, tag="25")]
    pub extension_background_policy: ::core::option::Option<ExtensionBackgroundPolicy>,
    /// 只作用于可丢弃的成功命令 Trace；失败、Crash、Audit、Operation 和 Billing 不采样。
    #[prost(uint32, optional, tag="26")]
    pub success_trace_sample_percent: ::core::option::Option<u32>,
    /// 受控 Observer/VNC Server → Client 转发上限；无桌面为 0，有桌面为 1..60。
    #[prost(uint32, optional, tag="27")]
    pub observer_frame_rate_fps: ::core::option::Option<u32>,
    /// 独立于 Observer 的 CDP Pixel Recording；缺失表示关闭。
    #[prost(bool, optional, tag="28")]
    pub video_recording_enabled: ::core::option::Option<bool>,
    /// 成功 Agent 动作的截图证据采样率；失败证据不受此字段影响并始终尝试捕获。
    #[prost(uint32, optional, tag="29")]
    pub success_screenshot_sample_percent: ::core::option::Option<u32>,
    /// Control Plane 已提交的 Browser 世代下界；跨 Node 恢复必须从更大世代启动。
    /// N-1 Node 不识别时安全忽略，但不得用于跨 Node 迁移目标。
    #[prost(uint64, tag="30")]
    pub minimum_browser_generation: u64,
    /// 以下字段都是非 Secret 绑定描述。credential_ref 仅是不透明引用，Network Helper
    /// 只可将它映射到本机已配置的 Provider Route；Node Agent 不得读取 Secret 正文。
    #[prost(string, optional, tag="31")]
    pub proxy_provider_id: ::core::option::Option<::prost::alloc::string::String>,
    #[prost(string, optional, tag="32")]
    pub proxy_expected_exit_ip: ::core::option::Option<::prost::alloc::string::String>,
    #[prost(string, optional, tag="33")]
    pub proxy_credential_ref: ::core::option::Option<::prost::alloc::string::String>,
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
/// A committed, transaction-barriered Profile delta written to Region Warm Tier. The Browser Node
/// publishes this only after the isolated Storage Helper has atomically installed the manifest and
/// COMMITTED marker. No Profile bytes or storage credentials cross this event boundary.
#[allow(clippy::derive_partial_eq_without_eq)]
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct ProfileWarmTierSyncedEvent {
    #[prost(string, tag="1")]
    pub session_id: ::prost::alloc::string::String,
    #[prost(string, tag="2")]
    pub node_id: ::prost::alloc::string::String,
    #[prost(string, tag="3")]
    pub profile_id: ::prost::alloc::string::String,
    #[prost(uint64, tag="4")]
    pub profile_write_epoch: u64,
    #[prost(uint64, tag="5")]
    pub journal_sequence: u64,
    #[prost(string, tag="6")]
    pub transaction_barrier: ::prost::alloc::string::String,
    #[prost(uint64, tag="7")]
    pub changed_file_count: u64,
    #[prost(uint64, tag="8")]
    pub deleted_file_count: u64,
    #[prost(uint64, tag="9")]
    pub reused_chunk_count: u64,
    #[prost(uint64, tag="10")]
    pub uploaded_bytes: u64,
    #[prost(uint64, tag="11")]
    pub deferred_group_count: u64,
    #[prost(string, tag="12")]
    pub manifest_sha256: ::prost::alloc::string::String,
    #[prost(int64, tag="13")]
    pub committed_at_ms: i64,
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
    /// 只调整 Extension 子 cgroup 权重，不改变运行中的扩展集合。
    #[prost(uint32, optional, tag="15")]
    pub extension_cpu_weight: ::core::option::Option<u32>,
    /// 只调整 Media Encoder 子 cgroup 的当前 Slot，不改变 Placement 预留上限。
    #[prost(uint32, optional, tag="16")]
    pub media_encoder_slots: ::core::option::Option<u32>,
    /// 资源达到上限时冻结后台 Page Target；Node 必须通过 CDP 执行成功后才 ACK。
    #[prost(bool, optional, tag="17")]
    pub freeze_background_tabs: ::core::option::Option<bool>,
    /// 以命令执行时的 Page Target 为允许集合，持续关闭之后新建的 Page Target。
    #[prost(bool, optional, tag="18")]
    pub block_new_tabs: ::core::option::Option<bool>,
    #[prost(message, optional, tag="19")]
    pub extension_background_policy: ::core::option::Option<ExtensionBackgroundPolicy>,
    /// 当前 Runtime 已加载的可信扩展集合，用于 Node 校验暂停策略不能越权。
    #[prost(string, repeated, tag="20")]
    pub extension_ids: ::prost::alloc::vec::Vec<::prost::alloc::string::String>,
    /// 1..100；缺失时保持当前值，以支持 N/N-1 滚动升级。
    #[prost(uint32, optional, tag="21")]
    pub success_trace_sample_percent: ::core::option::Option<u32>,
    /// 0 表示无桌面；有桌面时 1..60。缺失时保持当前值。
    #[prost(uint32, optional, tag="22")]
    pub observer_frame_rate_fps: ::core::option::Option<u32>,
    /// Node 必须等待真实 CDP start/stop 与 Storage Helper 提交完成后才 ACK。
    #[prost(bool, optional, tag="23")]
    pub video_recording_enabled: ::core::option::Option<bool>,
    /// 1..100；仅控制成功动作截图，失败证据保持强制捕获。
    #[prost(uint32, optional, tag="24")]
    pub success_screenshot_sample_percent: ::core::option::Option<u32>,
}
/// Browser Node 通过各 Extension background/service-worker Target 的 Debugger
/// pause/resume 执行，避免直接卸载扩展或修改扩展集合。
#[allow(clippy::derive_partial_eq_without_eq)]
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct ExtensionBackgroundPolicy {
    #[prost(string, repeated, tag="1")]
    pub paused_extension_ids: ::prost::alloc::vec::Vec<::prost::alloc::string::String>,
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
    #[prost(uint32, optional, tag="21")]
    pub old_extension_cpu_weight: ::core::option::Option<u32>,
    #[prost(uint32, optional, tag="22")]
    pub new_extension_cpu_weight: ::core::option::Option<u32>,
    #[prost(uint32, optional, tag="23")]
    pub old_media_encoder_slots: ::core::option::Option<u32>,
    #[prost(uint32, optional, tag="24")]
    pub new_media_encoder_slots: ::core::option::Option<u32>,
    #[prost(bool, optional, tag="25")]
    pub old_freeze_background_tabs: ::core::option::Option<bool>,
    #[prost(bool, optional, tag="26")]
    pub new_freeze_background_tabs: ::core::option::Option<bool>,
    #[prost(bool, optional, tag="27")]
    pub old_block_new_tabs: ::core::option::Option<bool>,
    #[prost(bool, optional, tag="28")]
    pub new_block_new_tabs: ::core::option::Option<bool>,
    #[prost(message, optional, tag="29")]
    pub old_extension_background_policy: ::core::option::Option<ExtensionBackgroundPolicy>,
    #[prost(message, optional, tag="30")]
    pub new_extension_background_policy: ::core::option::Option<ExtensionBackgroundPolicy>,
    #[prost(uint32, optional, tag="31")]
    pub old_success_trace_sample_percent: ::core::option::Option<u32>,
    #[prost(uint32, optional, tag="32")]
    pub new_success_trace_sample_percent: ::core::option::Option<u32>,
    #[prost(uint32, optional, tag="33")]
    pub old_observer_frame_rate_fps: ::core::option::Option<u32>,
    #[prost(uint32, optional, tag="34")]
    pub new_observer_frame_rate_fps: ::core::option::Option<u32>,
    #[prost(bool, optional, tag="35")]
    pub old_video_recording_enabled: ::core::option::Option<bool>,
    #[prost(bool, optional, tag="36")]
    pub new_video_recording_enabled: ::core::option::Option<bool>,
    #[prost(uint32, optional, tag="37")]
    pub old_success_screenshot_sample_percent: ::core::option::Option<u32>,
    #[prost(uint32, optional, tag="38")]
    pub new_success_screenshot_sample_percent: ::core::option::Option<u32>,
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
/// Revoke exactly one remote desktop participant. This command is independent from Agent and
/// HumanTakeover operations; it must never release another participant or stop the Browser.
#[allow(clippy::derive_partial_eq_without_eq)]
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct RevokeRemoteDesktopConnectionCommand {
    #[prost(string, tag="1")]
    pub session_id: ::prost::alloc::string::String,
    #[prost(string, tag="2")]
    pub connection_id: ::prost::alloc::string::String,
    #[prost(string, tag="3")]
    pub reason: ::prost::alloc::string::String,
    #[prost(string, tag="4")]
    pub revoked_by: ::prost::alloc::string::String,
}
/// Authoritative remote desktop connection lifecycle emitted by the Browser Node. CONNECTED and
/// DISCONNECTED originate from the WebSocket gateway; REVOKED is the acknowledgement of an exact
/// administrator command. Actor/access fields may be empty only for an already-gone revocation.
#[allow(clippy::derive_partial_eq_without_eq)]
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct RemoteDesktopParticipantEvent {
    #[prost(string, tag="1")]
    pub session_id: ::prost::alloc::string::String,
    #[prost(string, tag="2")]
    pub connection_id: ::prost::alloc::string::String,
    #[prost(string, tag="3")]
    pub actor_id: ::prost::alloc::string::String,
    #[prost(string, tag="4")]
    pub access_mode: ::prost::alloc::string::String,
    #[prost(bool, tag="5")]
    pub view_only: bool,
    #[prost(string, tag="6")]
    pub state: ::prost::alloc::string::String,
    #[prost(string, tag="7")]
    pub reason: ::prost::alloc::string::String,
    #[prost(int64, tag="8")]
    pub observed_at_ms: i64,
    #[prost(string, tag="9")]
    pub revoked_by: ::prost::alloc::string::String,
    /// Monotonic counters for this exact connection. Replayed or duplicated events must be merged
    /// by maximum value, never summed blindly. N-1 Control Planes safely ignore these fields.
    #[prost(uint64, tag="10")]
    pub forwarded_bytes: u64,
    #[prost(uint64, tag="11")]
    pub quota_wait_millis: u64,
    #[prost(uint64, tag="12")]
    pub throttled_batches: u64,
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
    /// PERIODIC 或 FULL_RESYNC；原生 REGION_RESYNC 使用 BrowserStateDiffEvent。
    #[prost(string, tag="9")]
    pub snapshot_kind: ::prost::alloc::string::String,
    #[prost(string, tag="10")]
    pub requested_root_ref: ::prost::alloc::string::String,
    /// 由 Runtime.evaluate 读取的 document.readyState；旧 Node 为空。
    #[prost(string, tag="11")]
    pub document_ready_state: ::prost::alloc::string::String,
    /// 自最近一次 CDP Network 活动结束后的安静时长；存在在途请求时为 0。
    #[prost(uint64, tag="12")]
    pub network_quiet_millis: u64,
    /// 仅当本 Runtime 代持续 Network 观察从未断线时为 true。
    #[prost(bool, tag="13")]
    pub network_evidence_fresh: bool,
}
/// 显式 FULL Resync 的有界流式传输。Begin 声明不可变清单，Chunk 只承载状态
/// protobuf 字节，Commit 允许 Control Plane 在校验全部分块和整流 SHA-256 后原子发布。
/// 周期 State 和 Agent 动作确认仍使用 BrowserStateUpdated，保持 N/N-1 兼容。
#[allow(clippy::derive_partial_eq_without_eq)]
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct BrowserStateSnapshotBeginEvent {
    #[prost(string, tag="1")]
    pub session_id: ::prost::alloc::string::String,
    #[prost(string, tag="2")]
    pub snapshot_id: ::prost::alloc::string::String,
    #[prost(uint64, tag="3")]
    pub state_version: u64,
    #[prost(uint64, tag="4")]
    pub target_revision: u64,
    #[prost(uint32, tag="5")]
    pub total_chunks: u32,
    #[prost(uint64, tag="6")]
    pub total_bytes: u64,
    #[prost(string, tag="7")]
    pub payload_sha256: ::prost::alloc::string::String,
    #[prost(string, tag="8")]
    pub snapshot_kind: ::prost::alloc::string::String,
    /// Browser Runtime cgroup 在本次采集期间的累计 CPU 时间增量；无委派 cgroup 的
    /// N-1/本地 Node 留空，Control Plane 保留准入时的保守预留。
    #[prost(uint64, optional, tag="9")]
    pub collection_cpu_millis: ::core::option::Option<u64>,
}
#[allow(clippy::derive_partial_eq_without_eq)]
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct BrowserStateSnapshotChunkEvent {
    #[prost(string, tag="1")]
    pub session_id: ::prost::alloc::string::String,
    #[prost(string, tag="2")]
    pub snapshot_id: ::prost::alloc::string::String,
    #[prost(uint32, tag="3")]
    pub chunk_index: u32,
    #[prost(uint32, tag="4")]
    pub total_chunks: u32,
    #[prost(bytes="vec", tag="5")]
    pub data: ::prost::alloc::vec::Vec<u8>,
    #[prost(string, tag="6")]
    pub chunk_sha256: ::prost::alloc::string::String,
}
#[allow(clippy::derive_partial_eq_without_eq)]
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct BrowserStateSnapshotCommitEvent {
    #[prost(string, tag="1")]
    pub session_id: ::prost::alloc::string::String,
    #[prost(string, tag="2")]
    pub snapshot_id: ::prost::alloc::string::String,
    #[prost(uint32, tag="3")]
    pub total_chunks: u32,
    #[prost(uint64, tag="4")]
    pub total_bytes: u64,
    #[prost(string, tag="5")]
    pub payload_sha256: ::prost::alloc::string::String,
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
/// Business Recovery 只允许 Control Plane 从版本化应用契约解析出的低风险动作。
/// target_url 对 RELOAD / REFRESH_SESSION / RESTART_EXTENSION 为空；
/// 导航动作必须是契约内规范化 URL；extension_id 只允许契约绑定的 Chromium ID。
#[allow(clippy::derive_partial_eq_without_eq)]
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct BusinessRecoveryActionCommand {
    #[prost(string, tag="1")]
    pub session_id: ::prost::alloc::string::String,
    #[prost(string, tag="2")]
    pub action_id: ::prost::alloc::string::String,
    #[prost(string, tag="3")]
    pub action: ::prost::alloc::string::String,
    #[prost(string, tag="4")]
    pub target_url: ::prost::alloc::string::String,
    #[prost(uint64, tag="5")]
    pub base_state_version: u64,
    #[prost(string, tag="6")]
    pub extension_id: ::prost::alloc::string::String,
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
/// User-authorized single-use click bound to a current Challenge Event and visual target anchor.
/// The Browser Node never accepts text, coordinates without a target_ref, multiple actions, or an
/// automatic retry budget through this command.
#[allow(clippy::derive_partial_eq_without_eq)]
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct HumanAssistClickCommand {
    #[prost(string, tag="1")]
    pub session_id: ::prost::alloc::string::String,
    #[prost(string, tag="2")]
    pub challenge_event_id: ::prost::alloc::string::String,
    #[prost(string, tag="3")]
    pub intent_id: ::prost::alloc::string::String,
    #[prost(string, tag="4")]
    pub target_ref: ::prost::alloc::string::String,
    #[prost(uint64, tag="5")]
    pub target_revision: u64,
    #[prost(uint64, tag="6")]
    pub base_state_version: u64,
    #[prost(string, tag="7")]
    pub base_content_hash: ::prost::alloc::string::String,
    #[prost(uint32, tag="8")]
    pub allowed_action_count: u32,
    #[prost(double, tag="9")]
    pub expected_x: f64,
    #[prost(double, tag="10")]
    pub expected_y: f64,
    #[prost(double, tag="11")]
    pub expected_width: f64,
    #[prost(double, tag="12")]
    pub expected_height: f64,
    #[prost(string, tag="13")]
    pub visual_anchor_hash: ::prost::alloc::string::String,
}
#[allow(clippy::derive_partial_eq_without_eq)]
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct HumanAssistFailedEvent {
    #[prost(string, tag="1")]
    pub session_id: ::prost::alloc::string::String,
    #[prost(string, tag="2")]
    pub challenge_event_id: ::prost::alloc::string::String,
    #[prost(string, tag="3")]
    pub intent_id: ::prost::alloc::string::String,
    #[prost(string, tag="4")]
    pub error_code: ::prost::alloc::string::String,
}
/// Administrator-requested, read-only Observer screenshot. The request contains no arbitrary CDP
/// method or Object Storage coordinate.
#[allow(clippy::derive_partial_eq_without_eq)]
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct CaptureObserverScreenshotCommand {
    #[prost(string, tag="1")]
    pub session_id: ::prost::alloc::string::String,
    #[prost(string, tag="2")]
    pub capture_id: ::prost::alloc::string::String,
}
/// Agent 动作完成后由独立 CDP 截图数据面产生。对象由 Storage Helper 提交，
/// Node 不持有 Bucket 凭据；失败事件也会持久化，避免把缺失证据伪装成成功。
#[allow(clippy::derive_partial_eq_without_eq)]
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct SessionEvidenceCapturedEvent {
    #[prost(string, tag="1")]
    pub session_id: ::prost::alloc::string::String,
    #[prost(string, tag="2")]
    pub evidence_id: ::prost::alloc::string::String,
    #[prost(string, tag="3")]
    pub evidence_kind: ::prost::alloc::string::String,
    #[prost(string, tag="4")]
    pub task_id: ::prost::alloc::string::String,
    #[prost(string, tag="5")]
    pub step_id: ::prost::alloc::string::String,
    #[prost(string, tag="6")]
    pub command_id: ::prost::alloc::string::String,
    #[prost(string, tag="7")]
    pub content_sha256: ::prost::alloc::string::String,
    #[prost(uint64, tag="8")]
    pub content_bytes: u64,
    #[prost(string, tag="9")]
    pub object_key: ::prost::alloc::string::String,
    #[prost(int64, tag="10")]
    pub captured_at_ms: i64,
    #[prost(bool, tag="11")]
    pub mandatory: bool,
    #[prost(string, tag="12")]
    pub result: ::prost::alloc::string::String,
    #[prost(string, tag="13")]
    pub error_code: ::prost::alloc::string::String,
    /// MASKED or NOT_REQUIRED for newly committed evidence; FAILED_CLOSED on capture failure.
    /// Empty is reserved for N-1 Nodes and is persisted as LEGACY_UNVERIFIED.
    #[prost(string, tag="14")]
    pub redaction_state: ::prost::alloc::string::String,
    #[prost(uint32, tag="15")]
    pub redacted_region_count: u32,
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
    #[prost(string, tag="11")]
    pub document_ready_state: ::prost::alloc::string::String,
    #[prost(uint64, tag="12")]
    pub network_quiet_millis: u64,
    #[prost(bool, tag="13")]
    pub network_evidence_fresh: bool,
    /// 空值表示旧版周期 Diff；REGION_RESYNC 表示受 root_ref 约束的原子区域替换。
    #[prost(string, tag="14")]
    pub snapshot_kind: ::prost::alloc::string::String,
    #[prost(string, tag="15")]
    pub requested_root_ref: ::prost::alloc::string::String,
    /// 仅 REGION_RESYNC 设置；用于关联 State Resync Admission 预留与实际结算。
    /// N-1 Node 留空时，Control Plane 只接受可由 evt_cmd_* Event ID 安全恢复的请求 ID。
    #[prost(string, tag="16")]
    pub resync_request_id: ::prost::alloc::string::String,
    #[prost(uint64, optional, tag="17")]
    pub collection_cpu_millis: ::core::option::Option<u64>,
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