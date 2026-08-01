//! Browser Node Agent 入口。

use anyhow::{Context, Result};
use helper_client::{
    NetworkHelperClient, StorageHelperClient, StorageRestoreStatus, StorageWorkspace,
};
use input_sandbox::{CdpDesktopInput, DesktopInput, InputKey};
use node_contracts::proto::node_control_service_server::{
    NodeControlService as NodeControlServiceRpc, NodeControlServiceServer,
};
use node_contracts::proto::node_event_service_client::NodeEventServiceClient;
use node_contracts::proto::{
    AdjustRuntimeResourcesCommand, AgentActionCommand, AgentActionFailedEvent,
    AgentNavigateCommand, AgentNavigationFailedEvent, BeginHumanTakeoverCommand, BrowserCrashEvent,
    BrowserStateDiffEvent, BrowserStateEvent, BusinessRecoveryActionCommand,
    CaptureObserverScreenshotCommand, CommandAck, CommandEnvelope, DiffTruncatedEvent,
    DispatchRequest, DispatchResponse, EndHumanTakeoverCommand, EventEnvelope, ExecuteInputCommand,
    ExtensionBackgroundPolicy, HumanTakeoverEndedEvent, HumanTakeoverReadyEvent,
    InteractiveTargetState, PingRequest, PingResponse, PresignEvidenceDownloadRequest,
    PresignEvidenceDownloadResponse, PublishRequest, PublishResponse, ReleaseAllInputCommand,
    ReportCapacityRequest, ReportSessionResourcesRequest, RequestStateResyncCommand,
    RuntimeResourcesAdjustedEvent, RuntimeStartedEvent, RuntimeStoppedEvent,
    SessionEvidenceCapturedEvent, StartRuntimeCommand, StopRuntimeCommand, TargetBounds,
    UploadProfileImportRequest, UploadProfileImportResponse,
};
use node_journal::{
    CommandFenceDecision, PersistedAcknowledgement, PersistedCommandResult, RuntimeLease,
    SqliteNodeJournal,
};
use prost::Message;
use remote_desktop_gateway::{DisconnectHandler, RemoteDesktopGateway, RemoteDesktopTicketClaims};
use runtime_supervisor::{
    CgroupV2Config, ChromiumRuntimeSupervisor, DesktopRuntimeConfig, RuntimeResourceLimits,
    RuntimeSpec, RuntimeSupervisor,
};
use session_recorder::{
    EvidenceCapture, EvidenceSpec, RecordingSpec, SessionEvidenceRegistry, SessionRecorderRegistry,
};
use sha2::{Digest, Sha256};
use state_collector::{
    diff_states, BrowserStateCollector, CdpStateCollector, CurrentState, DiffOutcome, StateDiff,
    StateQuality, TabResourcePolicy,
};
use std::collections::{HashMap, HashSet};
use std::net::SocketAddr;
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};
use tokio::io::AsyncWriteExt;
use tokio::sync::{mpsc, Mutex};
use tonic::transport::{Certificate, ClientTlsConfig, Identity, ServerTlsConfig};
use tonic::{Request, Response, Status};
use tracing_subscriber::EnvFilter;

#[derive(Clone)]
struct NodeControlService {
    node_id: String,
    control_plane_event_target: String,
    grpc_tls: Option<Arc<GrpcTlsMaterial>>,
    runtime_supervisor: Arc<ChromiumRuntimeSupervisor>,
    storage_helper: Option<Arc<StorageHelperClient>>,
    profile_workspaces: Arc<Mutex<HashMap<String, ActiveProfileWorkspace>>>,
    network_helper: Option<Arc<NetworkHelperClient>>,
    allow_direct_network: bool,
    extension_root: Option<PathBuf>,
    state_collector: Arc<CdpStateCollector>,
    state_baselines: Arc<Mutex<HashMap<String, CurrentState>>>,
    resync_required: Arc<Mutex<HashSet<String>>>,
    diff_max_bytes: usize,
    diff_max_changes: usize,
    input_brokers: Arc<Mutex<HashMap<String, Arc<CdpDesktopInput>>>>,
    active_human_takeovers: Arc<Mutex<HashSet<String>>>,
    journal: Arc<SqliteNodeJournal>,
    require_route_epoch: bool,
    inflight: Arc<Mutex<HashSet<String>>>,
    event_delivery_locks: Arc<Mutex<HashMap<String, Arc<Mutex<()>>>>>,
    runtime_monitors: Arc<Mutex<HashMap<String, String>>>,
    resource_cpu_baselines: Arc<Mutex<HashMap<String, (u64, Instant)>>>,
    resource_oom_baselines: Arc<Mutex<HashMap<String, u64>>>,
    resource_extension_cpu_baselines: Arc<Mutex<HashMap<String, (u64, Instant)>>>,
    resource_media_cpu_baselines: Arc<Mutex<HashMap<String, (u64, Instant)>>>,
    resource_browser_baselines: Arc<Mutex<HashMap<String, (f64, Instant)>>>,
    resource_io_baselines: Arc<Mutex<HashMap<String, (u64, Instant)>>>,
    agent_action_latencies: Arc<Mutex<HashMap<String, AgentLatencyWindow>>>,
    pending_state_events: Arc<Mutex<HashMap<String, u32>>>,
    success_trace_sampler: SuccessTraceSampler,
    session_recorders: SessionRecorderRegistry,
    session_evidence: SessionEvidenceRegistry,
    profile_import_staging_root: PathBuf,
    inflight_profile_imports: Arc<Mutex<HashSet<String>>>,
    resource_report_interval_probes: u64,
    next_cdp_port: Arc<Mutex<u16>>,
    next_display: Arc<Mutex<u16>>,
    remote_desktop_gateway: Option<RemoteDesktopGateway>,
    desktop_enabled: bool,
}

#[derive(Debug, Default)]
struct AgentLatencyWindow {
    maximum_ms: u32,
    samples: u32,
}

const DISK_DANGER_MIN_AVAILABLE_BYTES: u64 = 64 * 1024 * 1024;

fn classify_resource_danger(
    previous_oom_events: Option<u64>,
    current_oom_events: Option<u64>,
    disk_capacity: Option<(u64, u64)>,
) -> Option<&'static str> {
    if current_oom_events
        .map(|current| current > previous_oom_events.unwrap_or_default())
        .unwrap_or(false)
    {
        return Some("OOM");
    }
    if let Some((available_bytes, total_bytes)) = disk_capacity {
        let below_absolute_floor = available_bytes <= DISK_DANGER_MIN_AVAILABLE_BYTES;
        let below_one_percent =
            total_bytes > 0 && available_bytes.saturating_mul(100) <= total_bytes;
        if below_absolute_floor || below_one_percent {
            return Some("DISK_FULL");
        }
    }
    None
}

fn filesystem_capacity(path: &Path) -> Option<(u64, u64)> {
    let statistics = nix::sys::statvfs::statvfs(path).ok()?;
    let fragment_size = statistics.fragment_size();
    Some((
        filesystem_blocks_to_bytes(statistics.blocks_available(), fragment_size),
        filesystem_blocks_to_bytes(statistics.blocks(), fragment_size),
    ))
}

fn filesystem_blocks_to_bytes<T: Into<u64>>(blocks: T, fragment_size: u64) -> u64 {
    blocks.into().saturating_mul(fragment_size)
}

#[derive(Debug)]
struct EvidenceRequest {
    evidence_kind: &'static str,
    task_id: String,
    step_id: String,
    mandatory: bool,
}

/// Session-scoped actuator for optional successful command traces.
///
/// Failures are emitted directly by `failed` and never enter this sampler. FNV-1a keeps the
/// decision deterministic across retries and Node restarts for the same message ID.
#[derive(Clone, Default)]
struct SuccessTraceSampler {
    percentages: Arc<Mutex<HashMap<String, u32>>>,
}

impl SuccessTraceSampler {
    async fn set(&self, session_id: &str, percentage: u32) -> anyhow::Result<u32> {
        anyhow::ensure!(
            (1..=100).contains(&percentage),
            "success Trace sample percent must be between 1 and 100"
        );
        Ok(self
            .percentages
            .lock()
            .await
            .insert(session_id.to_owned(), percentage)
            .unwrap_or(100))
    }

    async fn percentage(&self, session_id: &str) -> u32 {
        self.percentages
            .lock()
            .await
            .get(session_id)
            .copied()
            .unwrap_or(100)
    }

    async fn remove(&self, session_id: &str) {
        self.percentages.lock().await.remove(session_id);
    }

    async fn should_sample(&self, session_id: &str, trace_id: &str) -> (bool, u32) {
        let percentage = self.percentage(session_id).await;
        if percentage == 100 {
            return (true, percentage);
        }
        let hash = trace_id
            .as_bytes()
            .iter()
            .fold(0xcbf29ce484222325_u64, |hash, byte| {
                (hash ^ u64::from(*byte)).wrapping_mul(0x100000001b3)
            });
        (hash % 100 < u64::from(percentage), percentage)
    }
}

impl AgentLatencyWindow {
    fn record(&mut self, elapsed: Duration) {
        let elapsed_ms = elapsed.as_millis().try_into().unwrap_or(u32::MAX);
        self.maximum_ms = self.maximum_ms.max(elapsed_ms);
        self.samples = self.samples.saturating_add(1);
    }

    fn maximum(self) -> Option<u32> {
        (self.samples > 0).then_some(self.maximum_ms)
    }
}

fn cumulative_rate_per_second(
    current: u64,
    previous: Option<(u64, Instant)>,
    now: Instant,
) -> Option<u64> {
    let (previous_value, previous_at) = previous?;
    if current < previous_value || now <= previous_at {
        return None;
    }
    let elapsed_nanos = now.duration_since(previous_at).as_nanos();
    if elapsed_nanos == 0 {
        return None;
    }
    let bytes = u128::from(current - previous_value);
    Some(
        bytes
            .saturating_mul(1_000_000_000)
            .div_ceil(elapsed_nanos)
            .min(u128::from(u64::MAX)) as u64,
    )
}

fn wall_clock_millis() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis()
        .try_into()
        .unwrap_or(u64::MAX)
}

fn resolve_trusted_extension_dirs(
    extension_root: Option<&Path>,
    extension_ids: &[String],
) -> anyhow::Result<Vec<PathBuf>> {
    if extension_ids.is_empty() {
        return Ok(Vec::new());
    }
    let root = extension_root
        .ok_or_else(|| anyhow::anyhow!("trusted Extension root is not configured"))?;
    let canonical_root =
        std::fs::canonicalize(root).context("trusted Extension root is unavailable")?;
    let mut result = Vec::with_capacity(extension_ids.len());
    for extension_id in extension_ids {
        anyhow::ensure!(
            !extension_id.is_empty()
                && extension_id.len() <= 128
                && extension_id
                    .chars()
                    .all(|value| value.is_ascii_alphanumeric() || "._-".contains(value)),
            "Extension ID is invalid"
        );
        let candidate = root.join(extension_id);
        let metadata = std::fs::symlink_metadata(&candidate)
            .with_context(|| format!("Extension artifact {extension_id} is unavailable"))?;
        anyhow::ensure!(
            metadata.is_dir() && !metadata.file_type().is_symlink(),
            "Extension artifact must be a non-symlink directory"
        );
        let canonical = std::fs::canonicalize(&candidate)?;
        anyhow::ensure!(
            canonical.parent() == Some(canonical_root.as_path()),
            "Extension artifact escaped the trusted root"
        );
        let manifest = canonical.join("manifest.json");
        let manifest_metadata = std::fs::symlink_metadata(&manifest)?;
        anyhow::ensure!(
            manifest_metadata.is_file()
                && !manifest_metadata.file_type().is_symlink()
                && manifest_metadata.len() <= 1024 * 1024,
            "Extension manifest is invalid"
        );
        let manifest_value: serde_json::Value = serde_json::from_slice(&std::fs::read(&manifest)?)?;
        anyhow::ensure!(
            manifest_value.is_object()
                && manifest_value.get("manifest_version").is_some()
                && manifest_value
                    .get("name")
                    .and_then(|value| value.as_str())
                    .is_some()
                && manifest_value
                    .get("version")
                    .and_then(|value| value.as_str())
                    .is_some(),
            "Extension manifest is incomplete"
        );
        result.push(canonical);
    }
    Ok(result)
}

#[derive(Clone)]
struct GrpcTlsMaterial {
    ca_certificate: Vec<u8>,
    certificate: Vec<u8>,
    private_key: Vec<u8>,
    control_plane_server_name: String,
}

#[derive(Clone)]
struct NodeCapacityReporter {
    node_id: String,
    region: String,
    advertised_grpc_target: String,
    control_plane_event_target: String,
    grpc_tls: Option<Arc<GrpcTlsMaterial>>,
    certified_cpu_millis: u32,
    certified_memory_mib: u32,
    certified_pid_count: u32,
    certified_gpu_slots: u32,
    certified_media_slots: u32,
    safety_margin_percent: u32,
    max_sessions: u32,
    supports_desktop: bool,
    supports_gpu: bool,
    supports_media: bool,
    supports_native_os: bool,
    isolation_capable: bool,
    labels: HashMap<String, String>,
    pressure_root: PathBuf,
}

#[derive(Clone, Copy)]
struct RuntimeCgroupCapabilities {
    enforcement: bool,
    io_telemetry: bool,
}

impl NodeCapacityReporter {
    fn from_environment(
        environment: &str,
        node_id: String,
        node_port: u16,
        control_plane_event_target: String,
        grpc_tls: Option<Arc<GrpcTlsMaterial>>,
        supports_desktop: bool,
        cgroup_capabilities: RuntimeCgroupCapabilities,
    ) -> Result<Self> {
        let production = environment.eq_ignore_ascii_case("production");
        let region = std::env::var("NODE_REGION").unwrap_or_else(|_| "local".to_owned());
        let hostname = std::env::var("HOSTNAME").unwrap_or_else(|_| node_id.clone());
        let default_target = if production {
            format!("{hostname}.browser-node.browsercloud-browser-nodes.svc:{node_port}")
        } else {
            format!("127.0.0.1:{node_port}")
        };
        let advertised_grpc_target =
            std::env::var("NODE_ADVERTISED_GRPC_TARGET").unwrap_or(default_target);
        let certified_cpu_millis =
            Self::capacity_u32("NODE_CERTIFIED_CPU_MILLIS", 10_000, production)?;
        let certified_memory_mib =
            Self::capacity_u32("NODE_CERTIFIED_MEMORY_MIB", 16_384, production)?;
        let certified_pid_count = Self::capacity_u32("NODE_CERTIFIED_PID_COUNT", 4096, production)?;
        let certified_gpu_slots = Self::capacity_u32("NODE_CERTIFIED_GPU_SLOTS", 0, false)?;
        let certified_media_slots = Self::capacity_u32("NODE_CERTIFIED_MEDIA_SLOTS", 0, false)?;
        let safety_margin_percent =
            Self::capacity_u32("NODE_SAFETY_MARGIN_PERCENT", 20, production)?;
        let max_sessions = Self::capacity_u32("NODE_MAX_SESSIONS", 10, production)?;
        let supports_gpu = Self::bool_env("NODE_SUPPORTS_GPU", false);
        let supports_media = Self::bool_env("NODE_SUPPORTS_MEDIA", false);
        let supports_native_os = Self::bool_env("NODE_SUPPORTS_NATIVE_OS", false);
        anyhow::ensure!(
            !supports_gpu || certified_gpu_slots > 0,
            "NODE_SUPPORTS_GPU requires NODE_CERTIFIED_GPU_SLOTS"
        );
        anyhow::ensure!(
            !supports_media || (certified_media_slots > 0 && supports_desktop),
            "NODE_SUPPORTS_MEDIA requires certified Slots and the x11vnc desktop encoder runtime"
        );
        anyhow::ensure!(
            !production || cgroup_capabilities.enforcement,
            "production capacity reporting requires cgroup enforcement"
        );
        let pressure_root = std::env::var("NODE_PRESSURE_ROOT")
            .map(PathBuf::from)
            .unwrap_or_else(|_| PathBuf::from("/proc/pressure"));
        if production {
            for resource in ["memory", "cpu", "io"] {
                anyhow::ensure!(
                    pressure_root.join(resource).is_file(),
                    "production PSI source {resource} is unavailable"
                );
            }
        }
        let mut labels = HashMap::new();
        labels.insert("runtime".to_owned(), "chromium".to_owned());
        labels.insert(
            "resourceEnforcement".to_owned(),
            if cgroup_capabilities.enforcement {
                "cgroup-v2"
            } else {
                "none"
            }
            .to_owned(),
        );
        labels.insert(
            "profileIoTelemetry".to_owned(),
            if cgroup_capabilities.io_telemetry {
                "browser-cgroup-io-v1"
            } else {
                "unavailable"
            }
            .to_owned(),
        );
        labels.insert(
            "extensionTelemetry".to_owned(),
            if cgroup_capabilities.enforcement
                && std::env::var("NODE_EXTENSION_ROOT")
                    .map(|value| !value.trim().is_empty())
                    .unwrap_or(false)
            {
                "extension-cgroup-v1"
            } else {
                "unavailable"
            }
            .to_owned(),
        );
        labels.insert(
            "mediaTelemetry".to_owned(),
            if cgroup_capabilities.enforcement && supports_media {
                "x11vnc-cgroup-v1"
            } else {
                "unavailable"
            }
            .to_owned(),
        );
        labels.insert("environment".to_owned(), environment.to_owned());
        labels.insert(
            "safePointBrowserActivity".to_owned(),
            "cdp-network-v1".to_owned(),
        );
        labels.insert(
            "businessRecoveryActions".to_owned(),
            "cdp-low-risk-v1".to_owned(),
        );
        labels.insert(
            "businessRecoveryExtensionActions".to_owned(),
            "cdp-extension-restart-v1".to_owned(),
        );
        labels.insert("startRuntimeGenerationFloor".to_owned(), "v1".to_owned());
        labels.insert("proxyProviderDescriptor".to_owned(), "v1".to_owned());
        labels.insert(
            "profileImport".to_owned(),
            if std::env::var("STORAGE_HELPER_SOCKET")
                .map(|value| !value.trim().is_empty())
                .unwrap_or(false)
                && Self::bool_env("OBJECT_STORAGE_ENABLED", false)
            {
                "checkpoint-stream-v1"
            } else {
                "unavailable"
            }
            .to_owned(),
        );
        let evidence_storage_available = std::env::var("STORAGE_HELPER_SOCKET")
            .map(|value| !value.trim().is_empty())
            .unwrap_or(false)
            && Self::bool_env("OBJECT_STORAGE_ENABLED", false);
        labels.insert(
            "observerEvidence".to_owned(),
            if evidence_storage_available {
                "cdp-s3-v1"
            } else {
                "unavailable"
            }
            .to_owned(),
        );
        labels.insert(
            "evidenceAccess".to_owned(),
            if evidence_storage_available {
                "presigned-get-v1"
            } else {
                "unavailable"
            }
            .to_owned(),
        );
        labels.insert(
            "evidenceRedaction".to_owned(),
            if evidence_storage_available {
                "dom-overlay-script-freeze-v1"
            } else {
                "unavailable"
            }
            .to_owned(),
        );
        Ok(Self {
            node_id,
            region,
            advertised_grpc_target,
            control_plane_event_target,
            grpc_tls,
            certified_cpu_millis,
            certified_memory_mib,
            certified_pid_count,
            certified_gpu_slots,
            certified_media_slots,
            safety_margin_percent,
            max_sessions,
            supports_desktop,
            supports_gpu,
            supports_media,
            supports_native_os,
            isolation_capable: cgroup_capabilities.enforcement,
            labels,
            pressure_root,
        })
    }

    fn capacity_u32(name: &str, local_default: u32, required: bool) -> Result<u32> {
        match std::env::var(name) {
            Ok(value) => value
                .parse::<u32>()
                .with_context(|| format!("{name} must be an unsigned integer")),
            Err(_) if required => anyhow::bail!("{name} is required in production"),
            Err(_) => Ok(local_default),
        }
    }

    fn bool_env(name: &str, default: bool) -> bool {
        std::env::var(name)
            .map(|value| value.eq_ignore_ascii_case("true"))
            .unwrap_or(default)
    }

    fn psi_avg10(&self, resource: &str, category: &str) -> Result<f64> {
        let path = self.pressure_root.join(resource);
        if !path.is_file() {
            return Ok(0.0);
        }
        let contents = std::fs::read_to_string(&path)
            .with_context(|| format!("failed to read {}", path.display()))?;
        let line = contents
            .lines()
            .find(|line| line.starts_with(category))
            .ok_or_else(|| anyhow::anyhow!("{resource} PSI has no {category} sample"))?;
        let value = line
            .split_whitespace()
            .find_map(|field| field.strip_prefix("avg10="))
            .ok_or_else(|| anyhow::anyhow!("{resource} PSI has no avg10 value"))?
            .parse::<f64>()?;
        anyhow::ensure!(
            value.is_finite() && (0.0..=100.0).contains(&value),
            "invalid PSI"
        );
        Ok(value)
    }

    async fn report(&self) -> Result<()> {
        let secure = self.grpc_tls.is_some();
        let target = if self.control_plane_event_target.starts_with("http://")
            || self.control_plane_event_target.starts_with("https://")
        {
            self.control_plane_event_target.clone()
        } else {
            format!(
                "{}://{}",
                if secure { "https" } else { "http" },
                self.control_plane_event_target
            )
        };
        let mut endpoint = tonic::transport::Endpoint::from_shared(target)?
            .connect_timeout(Duration::from_secs(2))
            .timeout(Duration::from_secs(2));
        if let Some(material) = self.grpc_tls.as_ref() {
            endpoint = endpoint.tls_config(material.client_config())?;
        }
        let channel = endpoint.connect().await?;
        let mut client = NodeEventServiceClient::new(channel);
        let response = client
            .report_capacity(ReportCapacityRequest {
                node_id: self.node_id.clone(),
                region: self.region.clone(),
                grpc_target: self.advertised_grpc_target.clone(),
                certified_cpu_millis: self.certified_cpu_millis,
                certified_memory_mib: self.certified_memory_mib,
                certified_pid_count: self.certified_pid_count,
                certified_gpu_slots: self.certified_gpu_slots,
                certified_media_slots: self.certified_media_slots,
                safety_margin_percent: self.safety_margin_percent,
                max_sessions: self.max_sessions,
                supports_desktop: self.supports_desktop,
                supports_gpu: self.supports_gpu,
                supports_media: self.supports_media,
                supports_native_os: self.supports_native_os,
                isolation_capable: self.isolation_capable,
                labels: self.labels.clone(),
                memory_psi_some_avg10: self.psi_avg10("memory", "some")?,
                memory_psi_full_avg10: self.psi_avg10("memory", "full")?,
                cpu_psi_some_avg10: self.psi_avg10("cpu", "some")?,
                io_psi_full_avg10: self.psi_avg10("io", "full")?,
                pressure_reason: String::new(),
            })
            .await?
            .into_inner();
        anyhow::ensure!(
            response.accepted,
            "Control Plane rejected capacity report: {}",
            response.error_code
        );
        tracing::debug!(
            node_id = response.node_id,
            admission_state = response.admission_state,
            pressure_state = response.pressure_state,
            "Browser Node capacity heartbeat accepted"
        );
        Ok(())
    }
}

impl GrpcTlsMaterial {
    fn from_environment(environment: &str) -> Result<Option<Self>> {
        let enabled = std::env::var("GRPC_TLS_ENABLED")
            .map(|value| value.eq_ignore_ascii_case("true"))
            .unwrap_or(false);
        anyhow::ensure!(
            !environment.eq_ignore_ascii_case("production") || enabled,
            "Internal gRPC mTLS is mandatory in production"
        );
        if !enabled {
            return Ok(None);
        }
        let ca_path = std::env::var("GRPC_TLS_CA_CERT")
            .map_err(|_| anyhow::anyhow!("GRPC_TLS_CA_CERT is required"))?;
        let certificate_path = std::env::var("GRPC_TLS_CERT")
            .map_err(|_| anyhow::anyhow!("GRPC_TLS_CERT is required"))?;
        let private_key_path = std::env::var("GRPC_TLS_KEY")
            .map_err(|_| anyhow::anyhow!("GRPC_TLS_KEY is required"))?;
        let material = Self {
            ca_certificate: std::fs::read(ca_path)?,
            certificate: std::fs::read(certificate_path)?,
            private_key: std::fs::read(private_key_path)?,
            control_plane_server_name: std::env::var("CONTROL_PLANE_TLS_SERVER_NAME")
                .unwrap_or_else(|_| "control-plane.internal".to_owned()),
        };
        anyhow::ensure!(
            !material.ca_certificate.is_empty()
                && !material.certificate.is_empty()
                && !material.private_key.is_empty(),
            "gRPC TLS material cannot be empty"
        );
        Ok(Some(material))
    }

    fn client_config(&self) -> ClientTlsConfig {
        ClientTlsConfig::new()
            .ca_certificate(Certificate::from_pem(&self.ca_certificate))
            .identity(Identity::from_pem(&self.certificate, &self.private_key))
            .domain_name(&self.control_plane_server_name)
    }

    fn server_config(&self) -> ServerTlsConfig {
        ServerTlsConfig::new()
            .identity(Identity::from_pem(&self.certificate, &self.private_key))
            .client_ca_root(Certificate::from_pem(&self.ca_certificate))
    }
}

#[derive(Clone)]
struct ActiveProfileWorkspace {
    workspace: StorageWorkspace,
    runtime_build_id: String,
}

struct DesktopDisconnectPublisher {
    sender: mpsc::Sender<RemoteDesktopTicketClaims>,
}

#[tonic::async_trait]
impl DisconnectHandler for DesktopDisconnectPublisher {
    async fn disconnected(&self, claims: &RemoteDesktopTicketClaims) {
        if self.sender.send(claims.clone()).await.is_err() {
            tracing::warn!(
                session_id = claims.session_id,
                "Remote desktop disconnect processor is unavailable"
            );
        }
    }
}

#[derive(Clone)]
struct CommandResult {
    acknowledgement: CommandAck,
    event: Option<EventEnvelope>,
    runtime_lease: Option<RuntimeLease>,
    stop_runtime_lease: bool,
    state_baseline: Option<CurrentState>,
}

impl NodeControlService {
    fn evidence_request(
        command: &CommandEnvelope,
        result: &CommandResult,
    ) -> Option<EvidenceRequest> {
        if command.command_type == "CaptureObserverScreenshot" {
            if !result.acknowledgement.accepted {
                return None;
            }
            let payload =
                CaptureObserverScreenshotCommand::decode(command.payload.as_slice()).ok()?;
            return Some(EvidenceRequest {
                evidence_kind: "OBSERVER_MANUAL",
                task_id: payload.capture_id,
                step_id: "observer".to_owned(),
                mandatory: true,
            });
        }
        let event_type = result.event.as_ref()?.event_type.as_str();
        match command.command_type.as_str() {
            "AgentAction" => {
                let payload = AgentActionCommand::decode(command.payload.as_slice()).ok()?;
                Some(EvidenceRequest {
                    evidence_kind: if event_type == "AgentActionFailed" {
                        "AGENT_ACTION_FAILURE"
                    } else if event_type == "BrowserStateUpdated" {
                        "AGENT_ACTION_SUCCESS"
                    } else {
                        return None;
                    },
                    task_id: payload.task_id,
                    step_id: payload.step_id,
                    mandatory: event_type == "AgentActionFailed",
                })
            }
            "AgentNavigate" => {
                let payload = AgentNavigateCommand::decode(command.payload.as_slice()).ok()?;
                Some(EvidenceRequest {
                    evidence_kind: if event_type == "AgentNavigationFailed" {
                        "AGENT_NAVIGATION_FAILURE"
                    } else if event_type == "BrowserStateUpdated" {
                        "AGENT_NAVIGATION_SUCCESS"
                    } else {
                        return None;
                    },
                    task_id: payload.task_id,
                    step_id: payload.step_id,
                    mandatory: event_type == "AgentNavigationFailed",
                })
            }
            _ => None,
        }
    }

    async fn capture_and_publish_evidence(
        &self,
        command: &CommandEnvelope,
        request: EvidenceRequest,
    ) -> anyhow::Result<()> {
        let capture = self
            .session_evidence
            .capture(
                &command.session_id,
                &command.message_id,
                request.evidence_kind,
                request.mandatory,
            )
            .await;
        let (
            evidence_id,
            content_sha256,
            content_bytes,
            object_key,
            captured_at_ms,
            result,
            error_code,
            redaction_state,
            redacted_region_count,
        ) = match capture {
            Ok(EvidenceCapture::Skipped { .. }) => return Ok(()),
            Ok(EvidenceCapture::Committed(summary)) => (
                summary.evidence_id,
                summary.content_sha256,
                summary.content_bytes,
                summary.object_key,
                summary.captured_at_ms as i64,
                "COMMITTED".to_owned(),
                String::new(),
                summary.redaction_state,
                summary.redacted_region_count,
            ),
            Err(error) => {
                tracing::warn!(
                    session_id = command.session_id,
                    command_id = command.message_id,
                    error = %error,
                    "Session screenshot evidence capture failed"
                );
                let message = error.to_string();
                let error_code = if message.contains("sensitive redaction") {
                    "SENSITIVE_REDACTION_FAILED"
                } else if message.contains("Object Storage") || message.contains("storage helper") {
                    "OBJECT_STORAGE_UNAVAILABLE"
                } else if message.contains("CDP") {
                    "CDP_CAPTURE_FAILED"
                } else {
                    "EVIDENCE_CAPTURE_FAILED"
                };
                (
                    format!("evd_{}", uuid::Uuid::new_v4().simple()),
                    String::new(),
                    0,
                    String::new(),
                    wall_clock_millis() as i64,
                    "FAILED".to_owned(),
                    error_code.to_owned(),
                    "FAILED_CLOSED".to_owned(),
                    0,
                )
            }
        };
        self.record_and_publish_background_event(
            &command.tenant_id,
            &command.session_id,
            command.coordinator_term,
            command.context_epoch,
            "SessionEvidenceCaptured",
            SessionEvidenceCapturedEvent {
                session_id: command.session_id.clone(),
                evidence_id,
                evidence_kind: request.evidence_kind.to_owned(),
                task_id: request.task_id,
                step_id: request.step_id,
                command_id: command.message_id.clone(),
                content_sha256,
                content_bytes,
                object_key,
                captured_at_ms,
                mandatory: request.mandatory,
                result,
                error_code,
                redaction_state,
                redacted_region_count,
            },
        )
        .await
    }

    fn is_valid_session_id(session_id: &str) -> bool {
        session_id.starts_with("ses_")
            && session_id
                .chars()
                .all(|character| character.is_ascii_alphanumeric() || character == '_')
    }

    fn resolve_extension_dirs(&self, extension_ids: &[String]) -> anyhow::Result<Vec<PathBuf>> {
        resolve_trusted_extension_dirs(self.extension_root.as_deref(), extension_ids)
    }

    async fn allocate_cdp_port(&self) -> anyhow::Result<u16> {
        for _ in 10_000..=19_999 {
            let candidate = {
                let mut port = self.next_cdp_port.lock().await;
                let candidate = *port;
                *port = if *port >= 19_999 { 10_000 } else { *port + 1 };
                candidate
            };
            if std::net::TcpListener::bind(("127.0.0.1", candidate)).is_ok() {
                return Ok(candidate);
            }
        }
        anyhow::bail!("no loopback CDP port is available in the configured range")
    }

    async fn allocate_display(&self) -> String {
        let mut display = self.next_display.lock().await;
        let allocated = *display;
        *display = if *display >= 999 { 100 } else { *display + 1 };
        format!(":{allocated}")
    }

    fn allocate_loopback_port() -> anyhow::Result<u16> {
        let listener = std::net::TcpListener::bind("127.0.0.1:0")?;
        Ok(listener.local_addr()?.port())
    }

    async fn release_start_resources(&self, session_id: &str, workspace: &StorageWorkspace) {
        if let Some(network_helper) = self.network_helper.as_ref() {
            if let Err(error) = network_helper.release(session_id).await {
                tracing::warn!(session_id, error = %error, "Failed to release proxy binding");
            }
        }
        if let Some(storage_helper) = self.storage_helper.as_ref() {
            if let Err(error) = storage_helper.release(workspace).await {
                tracing::warn!(session_id, error = %error, "Failed to release profile writer");
            }
        }
    }

    async fn next_event_sequence(&self, session_id: &str) -> anyhow::Result<i64> {
        self.journal.next_event_sequence(session_id).await
    }

    async fn current_coordinator_term(
        &self,
        session_id: &str,
        fallback: i64,
    ) -> anyhow::Result<i64> {
        Ok(self
            .journal
            .current_coordinator_term(session_id)
            .await?
            .unwrap_or(fallback))
    }

    fn ack(message_id: &str, accepted: bool, error_code: &str, error_message: &str) -> CommandAck {
        CommandAck {
            message_id: message_id.to_owned(),
            accepted,
            duplicate: false,
            error_code: error_code.to_owned(),
            error_message: error_message.to_owned(),
        }
    }

    fn parse_input_key(value: &str) -> anyhow::Result<InputKey> {
        match value {
            "SHIFT" | "Shift" => Ok(InputKey::Shift),
            "CONTROL" | "Control" => Ok(InputKey::Control),
            "ALT" | "Alt" => Ok(InputKey::Alt),
            "META" | "Meta" => Ok(InputKey::Meta),
            "ENTER" | "Enter" => Ok(InputKey::Enter),
            "TAB" | "Tab" => Ok(InputKey::Tab),
            "ESCAPE" | "Escape" => Ok(InputKey::Escape),
            "BACKSPACE" | "Backspace" => Ok(InputKey::Backspace),
            "DELETE" | "Delete" => Ok(InputKey::Delete),
            "ARROW_UP" | "ArrowUp" => Ok(InputKey::ArrowUp),
            "ARROW_DOWN" | "ArrowDown" => Ok(InputKey::ArrowDown),
            "ARROW_LEFT" | "ArrowLeft" => Ok(InputKey::ArrowLeft),
            "ARROW_RIGHT" | "ArrowRight" => Ok(InputKey::ArrowRight),
            _ => {
                anyhow::ensure!(
                    !value.is_empty() && value.chars().count() <= 32,
                    "input key must contain 1 to 32 characters"
                );
                Ok(InputKey::Character(value.to_owned()))
            }
        }
    }

    fn result(acknowledgement: CommandAck, event: Option<EventEnvelope>) -> CommandResult {
        CommandResult {
            acknowledgement,
            event,
            runtime_lease: None,
            stop_runtime_lease: false,
            state_baseline: None,
        }
    }

    fn state_result(
        acknowledgement: CommandAck,
        event: EventEnvelope,
        state_baseline: CurrentState,
    ) -> CommandResult {
        CommandResult {
            acknowledgement,
            event: Some(event),
            runtime_lease: None,
            stop_runtime_lease: false,
            state_baseline: Some(state_baseline),
        }
    }

    fn runtime_started_result(
        acknowledgement: CommandAck,
        event: EventEnvelope,
        runtime_lease: RuntimeLease,
    ) -> CommandResult {
        CommandResult {
            acknowledgement,
            event: Some(event),
            runtime_lease: Some(runtime_lease),
            stop_runtime_lease: false,
            state_baseline: None,
        }
    }

    fn runtime_stopped_result(acknowledgement: CommandAck, event: EventEnvelope) -> CommandResult {
        CommandResult {
            acknowledgement,
            event: Some(event),
            runtime_lease: None,
            stop_runtime_lease: true,
            state_baseline: None,
        }
    }

    fn event(
        command: &CommandEnvelope,
        event_type: &str,
        sequence: i64,
        payload: impl Message,
    ) -> EventEnvelope {
        EventEnvelope {
            event_id: format!("evt_{}", command.message_id),
            event_type: event_type.to_owned(),
            tenant_id: command.tenant_id.clone(),
            session_id: command.session_id.clone(),
            coordinator_term: command.coordinator_term,
            context_epoch: command.context_epoch,
            operation_epoch: command.operation_epoch,
            sequence,
            payload: payload.encode_to_vec(),
        }
    }

    async fn agent_navigation_failed(
        &self,
        command: &CommandEnvelope,
        payload: &AgentNavigateCommand,
        error_code: &str,
    ) -> CommandResult {
        let sequence = match self.next_event_sequence(&command.session_id).await {
            Ok(sequence) => sequence,
            Err(error) => return self.failed(command, error),
        };
        let event = Self::event(
            command,
            "AgentNavigationFailed",
            sequence,
            AgentNavigationFailedEvent {
                session_id: command.session_id.clone(),
                task_id: payload.task_id.clone(),
                step_id: payload.step_id.clone(),
                error_code: error_code.to_owned(),
            },
        );
        Self::result(Self::ack(&command.message_id, true, "", ""), Some(event))
    }

    async fn agent_action_failed(
        &self,
        command: &CommandEnvelope,
        payload: &AgentActionCommand,
        error_code: &str,
    ) -> CommandResult {
        let sequence = match self.next_event_sequence(&command.session_id).await {
            Ok(sequence) => sequence,
            Err(error) => return self.failed(command, error),
        };
        let event = Self::event(
            command,
            "AgentActionFailed",
            sequence,
            AgentActionFailedEvent {
                session_id: command.session_id.clone(),
                task_id: payload.task_id.clone(),
                step_id: payload.step_id.clone(),
                tool_id: payload.tool_id.clone(),
                error_code: error_code.to_owned(),
            },
        );
        Self::result(Self::ack(&command.message_id, true, "", ""), Some(event))
    }

    fn browser_state_payload(state: CurrentState) -> BrowserStateEvent {
        BrowserStateEvent {
            session_id: state.session_id,
            state_version: state.state_version,
            target_revision: state.target_revision,
            url: state.url,
            title: state.title,
            state_quality: match state.quality {
                StateQuality::Complete => "COMPLETE",
                StateQuality::DepthLimited => "DEPTH_LIMITED",
                StateQuality::Resyncing => "RESYNCING",
                StateQuality::Degraded => "DEGRADED",
                StateQuality::Invalid => "INVALID",
            }
            .to_owned(),
            content_hash: state.content_hash,
            targets: state
                .targets
                .into_iter()
                .map(Self::interactive_target_payload)
                .collect(),
            snapshot_kind: "PERIODIC".to_owned(),
            requested_root_ref: String::new(),
        }
    }

    fn interactive_target_payload(
        target: state_collector::InteractiveTarget,
    ) -> InteractiveTargetState {
        InteractiveTargetState {
            target_ref: target.target_ref,
            role: target.role,
            name: target.name,
            bounds: target.bounds.map(|bounds| TargetBounds {
                x: bounds.x,
                y: bounds.y,
                width: bounds.width,
                height: bounds.height,
            }),
            enabled: target.enabled,
            visible: target.visible,
            sensitive: target.sensitive,
        }
    }

    fn state_diff_payload(diff: StateDiff) -> BrowserStateDiffEvent {
        BrowserStateDiffEvent {
            session_id: diff.session_id,
            base_state_version: diff.base_state_version,
            state_version: diff.state_version,
            target_revision: diff.target_revision,
            url: diff.url,
            title: diff.title,
            state_quality: match diff.quality {
                StateQuality::Complete => "COMPLETE",
                StateQuality::DepthLimited => "DEPTH_LIMITED",
                StateQuality::Resyncing => "RESYNCING",
                StateQuality::Degraded => "DEGRADED",
                StateQuality::Invalid => "INVALID",
            }
            .to_owned(),
            content_hash: diff.content_hash,
            upserted_targets: diff
                .upserted_targets
                .into_iter()
                .map(Self::interactive_target_payload)
                .collect(),
            removed_target_refs: diff.removed_target_refs,
        }
    }

    async fn publish_event_receipt(&self, event: EventEnvelope) -> anyhow::Result<PublishResponse> {
        tokio::time::timeout(Duration::from_secs(5), async {
            let secure = self.grpc_tls.is_some();
            let target = if self.control_plane_event_target.starts_with("http://")
                || self.control_plane_event_target.starts_with("https://")
            {
                self.control_plane_event_target.clone()
            } else {
                format!(
                    "{}://{}",
                    if secure { "https" } else { "http" },
                    self.control_plane_event_target
                )
            };
            let mut endpoint = tonic::transport::Endpoint::from_shared(target)?
                .connect_timeout(Duration::from_secs(2))
                .timeout(Duration::from_secs(2));
            if let Some(material) = self.grpc_tls.as_ref() {
                endpoint = endpoint.tls_config(material.client_config())?;
            }
            let channel = endpoint.connect().await?;
            let mut client = NodeEventServiceClient::new(channel);
            Ok::<_, anyhow::Error>(
                client
                    .publish(PublishRequest { event: Some(event) })
                    .await?
                    .into_inner(),
            )
        })
        .await
        .context("Node Event publish timed out")?
    }

    async fn report_session_resources(
        &self,
        tenant_id: &str,
        session_id: &str,
        context_epoch: i64,
        include_resource_metrics: bool,
        danger_override: Option<&str>,
    ) -> anyhow::Result<()> {
        let metrics = self.runtime_supervisor.metrics(session_id).await?;
        let browser_metrics = tokio::time::timeout(
            Duration::from_secs(2),
            self.state_collector.collect_resource_metrics(session_id),
        )
        .await
        .ok()
        .and_then(Result::ok);
        let cpu_percent = if let Some(current_usage_micros) = metrics.cumulative_cpu_usage_micros {
            let now = Instant::now();
            let previous = self
                .resource_cpu_baselines
                .lock()
                .await
                .insert(session_id.to_owned(), (current_usage_micros, now));
            previous
                .and_then(|(previous_usage_micros, previous_at)| {
                    let elapsed_micros = now.duration_since(previous_at).as_micros() as f64;
                    let cpu_limit_cores = f64::from(metrics.cpu_limit_millis) / 1000.0;
                    (elapsed_micros > 0.0 && cpu_limit_cores > 0.0).then(|| {
                        (current_usage_micros.saturating_sub(previous_usage_micros) as f64
                            / elapsed_micros
                            / cpu_limit_cores
                            * 100.0)
                            .clamp(0.0, 100.0)
                    })
                })
                .unwrap_or_else(|| f64::from(metrics.cpu_usage_percent).clamp(0.0, 100.0))
        } else {
            f64::from(metrics.cpu_usage_percent).clamp(0.0, 100.0)
        };
        let main_thread_blocked_ms = if let Some(current_duration_ms) = browser_metrics
            .as_ref()
            .and_then(|metrics| metrics.main_thread_task_duration_ms)
        {
            let now = Instant::now();
            let previous = self
                .resource_browser_baselines
                .lock()
                .await
                .insert(session_id.to_owned(), (current_duration_ms, now));
            previous.and_then(|(previous_duration_ms, previous_at)| {
                if !current_duration_ms.is_finite()
                    || current_duration_ms < previous_duration_ms
                    || now <= previous_at
                {
                    return None;
                }
                Some(
                    (current_duration_ms - previous_duration_ms)
                        .round()
                        .clamp(0.0, f64::from(u32::MAX)) as u32,
                )
            })
        } else {
            None
        };
        let profile_io_bytes_per_second =
            if let Some(current_io_bytes) = metrics.cumulative_browser_io_bytes {
                let now = Instant::now();
                let previous = self
                    .resource_io_baselines
                    .lock()
                    .await
                    .insert(session_id.to_owned(), (current_io_bytes, now));
                cumulative_rate_per_second(current_io_bytes, previous, now)
            } else {
                None
            };
        let extension_cpu_percent =
            if let Some(current_usage_micros) = metrics.cumulative_extension_cpu_usage_micros {
                let now = Instant::now();
                let previous = self
                    .resource_extension_cpu_baselines
                    .lock()
                    .await
                    .insert(session_id.to_owned(), (current_usage_micros, now));
                previous.and_then(|(previous_usage_micros, previous_at)| {
                    let elapsed_micros = now.duration_since(previous_at).as_micros() as f64;
                    let cpu_limit_cores = f64::from(metrics.cpu_limit_millis) / 1000.0;
                    (elapsed_micros > 0.0 && cpu_limit_cores > 0.0).then(|| {
                        (current_usage_micros.saturating_sub(previous_usage_micros) as f64
                            / elapsed_micros
                            / cpu_limit_cores
                            * 100.0)
                            .clamp(0.0, 100.0)
                    })
                })
            } else {
                None
            };
        let media_encoder_percent =
            if let Some(current_usage_micros) = metrics.cumulative_media_cpu_usage_micros {
                let now = Instant::now();
                let previous = self
                    .resource_media_cpu_baselines
                    .lock()
                    .await
                    .insert(session_id.to_owned(), (current_usage_micros, now));
                previous.and_then(|(previous_usage_micros, previous_at)| {
                    let elapsed_micros = now.duration_since(previous_at).as_micros() as f64;
                    let allocated_cores = f64::from(metrics.media_encoder_slots);
                    (elapsed_micros > 0.0 && allocated_cores > 0.0).then(|| {
                        (current_usage_micros.saturating_sub(previous_usage_micros) as f64
                            / elapsed_micros
                            / allocated_cores
                            * 100.0)
                            .clamp(0.0, 100.0)
                    })
                })
            } else {
                None
            };
        let agent_action_latency_ms = if include_resource_metrics {
            self.agent_action_latencies
                .lock()
                .await
                .remove(session_id)
                .and_then(AgentLatencyWindow::maximum)
        } else {
            None
        };
        let state_diff_queue_depth = self
            .pending_state_events
            .lock()
            .await
            .get(session_id)
            .copied()
            .unwrap_or_default();
        let remote_desktop_frame_age_ms = self
            .remote_desktop_gateway
            .as_ref()
            .and_then(|gateway| gateway.frame_age_ms(session_id));
        let observed_at_ms = SystemTime::now()
            .duration_since(UNIX_EPOCH)?
            .as_millis()
            .try_into()
            .context("resource sample timestamp exceeds i64")?;
        let input_ledger = match self.input_brokers.lock().await.get(session_id).cloned() {
            Some(input) => Some(input.ledger_snapshot().await),
            None => None,
        };
        let browser_safety = self
            .state_collector
            .browser_safety_observation(session_id)
            .await;
        let current_oom_events = match (metrics.memory_oom_events, metrics.memory_oom_kill_events) {
            (Some(oom), Some(oom_kill)) => Some(oom.saturating_add(oom_kill)),
            (Some(oom), None) => Some(oom),
            (None, Some(oom_kill)) => Some(oom_kill),
            (None, None) => None,
        };
        let previous_oom_events = if let Some(current) = current_oom_events {
            self.resource_oom_baselines
                .lock()
                .await
                .insert(session_id.to_owned(), current)
        } else {
            None
        };
        let profile_filesystem = self
            .profile_workspaces
            .lock()
            .await
            .get(session_id)
            .map(|active| PathBuf::from(&active.workspace.ephemeral_dir));
        let danger_event = danger_override.unwrap_or_else(|| {
            classify_resource_danger(
                previous_oom_events,
                current_oom_events,
                profile_filesystem.as_deref().and_then(filesystem_capacity),
            )
            .unwrap_or_default()
        });
        let secure = self.grpc_tls.is_some();
        let target = if self.control_plane_event_target.starts_with("http://")
            || self.control_plane_event_target.starts_with("https://")
        {
            self.control_plane_event_target.clone()
        } else {
            format!(
                "{}://{}",
                if secure { "https" } else { "http" },
                self.control_plane_event_target
            )
        };
        let mut endpoint = tonic::transport::Endpoint::from_shared(target)?
            .connect_timeout(Duration::from_secs(2))
            .timeout(Duration::from_secs(2));
        if let Some(material) = self.grpc_tls.as_ref() {
            endpoint = endpoint.tls_config(material.client_config())?;
        }
        let channel = endpoint.connect().await?;
        let mut client = NodeEventServiceClient::new(channel);
        let response = client
            .report_session_resources(ReportSessionResourcesRequest {
                node_id: self.node_id.clone(),
                tenant_id: tenant_id.to_owned(),
                session_id: session_id.to_owned(),
                context_epoch,
                observed_at_ms,
                cpu_percent: include_resource_metrics.then_some(cpu_percent),
                memory_rss_mib: include_resource_metrics
                    .then_some(metrics.resident_memory_bytes.div_ceil(1024 * 1024)),
                memory_psi_some_avg10: include_resource_metrics
                    .then_some(metrics.memory_psi_some_avg10)
                    .flatten(),
                renderer_count: include_resource_metrics
                    .then(|| {
                        browser_metrics
                            .as_ref()
                            .and_then(|metrics| metrics.renderer_count)
                    })
                    .flatten(),
                tab_count: include_resource_metrics
                    .then(|| browser_metrics.as_ref().map(|metrics| metrics.tab_count))
                    .flatten(),
                main_thread_blocked_ms: include_resource_metrics
                    .then_some(main_thread_blocked_ms)
                    .flatten(),
                agent_action_latency_ms: include_resource_metrics
                    .then_some(agent_action_latency_ms)
                    .flatten(),
                state_diff_queue_depth: include_resource_metrics.then_some(state_diff_queue_depth),
                profile_io_bytes_per_second: include_resource_metrics
                    .then_some(profile_io_bytes_per_second)
                    .flatten(),
                extension_cpu_percent: include_resource_metrics
                    .then_some(extension_cpu_percent)
                    .flatten(),
                extension_memory_mib: include_resource_metrics
                    .then(|| {
                        metrics
                            .extension_memory_bytes
                            .map(|bytes| bytes.div_ceil(1024 * 1024))
                    })
                    .flatten(),
                remote_desktop_frame_age_ms: include_resource_metrics
                    .then_some(remote_desktop_frame_age_ms)
                    .flatten(),
                media_encoder_percent: include_resource_metrics
                    .then_some(media_encoder_percent)
                    .flatten(),
                danger_event: danger_event.to_owned(),
                input_active: input_ledger.as_ref().map(|ledger| ledger.has_any_input()),
                active_drag: input_ledger.as_ref().map(|ledger| ledger.active_drag),
                pressed_key_count: input_ledger
                    .as_ref()
                    .map(|ledger| ledger.pressed_keys.len().try_into().unwrap_or(u32::MAX)),
                pressed_button_count: input_ledger
                    .as_ref()
                    .map(|ledger| ledger.pressed_buttons.len().try_into().unwrap_or(u32::MAX)),
                active_upload_count: browser_safety
                    .fresh
                    .then_some(browser_safety.active_upload_count),
                active_download_count: browser_safety
                    .fresh
                    .then_some(browser_safety.active_download_count),
                active_form_submission_count: browser_safety
                    .fresh
                    .then_some(browser_safety.active_form_submission_count),
            })
            .await?
            .into_inner();
        anyhow::ensure!(
            response.accepted,
            "Control Plane rejected Session resource sample: {}",
            response.error_code
        );
        Ok(())
    }

    #[allow(clippy::too_many_arguments)]
    async fn rollback_resource_adjustment(
        &self,
        session_id: &str,
        previous_runtime: RuntimeResourceLimits,
        previous_state_collector_budget_percent: u32,
        previous_remote_desktop_bitrate_kbps: u32,
        previous_observer_frame_rate_fps: u32,
        previous_video_recording_enabled: bool,
        previous_success_screenshot_sample_percent: u32,
        previous_tab_resource_policy: TabResourcePolicy,
    ) {
        if let Err(error) = self
            .session_evidence
            .set_success_sample_percent(session_id, previous_success_screenshot_sample_percent)
            .await
        {
            tracing::error!(
                session_id,
                error = %error,
                "Screenshot evidence sampling rollback failed"
            );
        }
        if let Err(error) = self
            .session_recorders
            .set_enabled(session_id, previous_video_recording_enabled)
            .await
        {
            tracing::error!(
                session_id,
                error = %error,
                "Video recording rollback failed"
            );
        }
        if let Err(error) = self
            .state_collector
            .set_tab_resource_policy(session_id, previous_tab_resource_policy)
            .await
        {
            tracing::error!(
                session_id,
                error = %error,
                "Tab resource policy rollback failed"
            );
        }
        if let Some(gateway) = self
            .remote_desktop_gateway
            .as_ref()
            .filter(|gateway| gateway.bitrate_limit_kbps(session_id).is_some())
        {
            if let Err(error) =
                gateway.set_bitrate_limit(session_id, previous_remote_desktop_bitrate_kbps)
            {
                tracing::error!(
                    session_id,
                    error = %error,
                    "Remote Desktop bitrate rollback failed"
                );
            }
            if let Err(error) =
                gateway.set_observer_frame_rate(session_id, previous_observer_frame_rate_fps)
            {
                tracing::error!(
                    session_id,
                    error = %error,
                    "Observer frame-rate rollback failed"
                );
            }
        }
        if let Err(error) = self
            .state_collector
            .set_resource_budget(session_id, previous_state_collector_budget_percent)
            .await
        {
            tracing::error!(
                session_id,
                error = %error,
                "State Collector budget rollback failed"
            );
        }
        if let Err(error) = self
            .runtime_supervisor
            .adjust_resources(session_id, previous_runtime)
            .await
        {
            tracing::error!(
                session_id,
                error = %error,
                "Cgroup resource rollback failed"
            );
        }
    }

    async fn publish_and_mark(&self, event: EventEnvelope) -> anyhow::Result<()> {
        let event_id = event.event_id.clone();
        let delivery_lock = {
            let mut locks = self.event_delivery_locks.lock().await;
            locks
                .entry(event_id.clone())
                .or_insert_with(|| Arc::new(Mutex::new(())))
                .clone()
        };
        let delivery_guard = delivery_lock.lock().await;
        match self.journal.is_event_delivered(&event_id).await {
            Ok(true) => {
                drop(delivery_guard);
                self.release_event_delivery_lock(&event_id, &delivery_lock)
                    .await;
                return Ok(());
            }
            Ok(false) => {}
            Err(error) => {
                drop(delivery_guard);
                self.release_event_delivery_lock(&event_id, &delivery_lock)
                    .await;
                return Err(error);
            }
        }
        let backlog_session =
            Self::is_state_backlog_event(&event).then(|| event.session_id.clone());
        let result = async {
            let acknowledgement = self.publish_event_receipt(event).await?;
            anyhow::ensure!(
                acknowledgement.accepted
                    || matches!(
                        acknowledgement.error_code.as_str(),
                        "STALE_HUMAN_TAKEOVER" | "STALE_COORDINATOR_TERM"
                    ),
                "Control Plane rejected Node Event: {}",
                acknowledgement.error_code
            );
            self.journal.mark_event_delivered(&event_id).await?;
            if let Some(session_id) = backlog_session {
                self.decrement_pending_state_event(&session_id).await;
            }
            Ok(())
        }
        .await;
        drop(delivery_guard);
        self.release_event_delivery_lock(&event_id, &delivery_lock)
            .await;
        result
    }

    async fn release_event_delivery_lock(&self, event_id: &str, delivery_lock: &Arc<Mutex<()>>) {
        let mut locks = self.event_delivery_locks.lock().await;
        if Arc::strong_count(delivery_lock) == 2
            && locks
                .get(event_id)
                .is_some_and(|current| Arc::ptr_eq(current, delivery_lock))
        {
            locks.remove(event_id);
        }
    }

    fn is_state_backlog_event(event: &EventEnvelope) -> bool {
        matches!(
            event.event_type.as_str(),
            "BrowserStateDiff" | "DiffTruncated"
        )
    }

    async fn increment_pending_state_event(&self, session_id: &str) {
        let mut depths = self.pending_state_events.lock().await;
        let depth = depths.entry(session_id.to_owned()).or_default();
        *depth = depth.saturating_add(1);
    }

    async fn decrement_pending_state_event(&self, session_id: &str) {
        let mut depths = self.pending_state_events.lock().await;
        if let Some(depth) = depths.get_mut(session_id) {
            *depth = depth.saturating_sub(1);
            if *depth == 0 {
                depths.remove(session_id);
            }
        }
    }

    async fn rebuild_pending_state_event_depths(&self) {
        const STARTUP_SCAN_LIMIT: usize = 10_000;
        let pending = match self.journal.pending_events(STARTUP_SCAN_LIMIT).await {
            Ok(pending) => pending,
            Err(error) => {
                tracing::error!(error = %error, "Failed to rebuild State Diff queue depth");
                return;
            }
        };
        let capped = pending.len() == STARTUP_SCAN_LIMIT;
        let mut rebuilt = HashMap::<String, u32>::new();
        for result in pending {
            let Some(payload) = result.event_payload else {
                continue;
            };
            let Ok(event) = EventEnvelope::decode(payload.as_slice()) else {
                continue;
            };
            if Self::is_state_backlog_event(&event) {
                let depth = rebuilt.entry(event.session_id).or_default();
                *depth = depth.saturating_add(1);
            }
        }
        *self.pending_state_events.lock().await = rebuilt;
        if capped {
            tracing::warn!(
                limit = STARTUP_SCAN_LIMIT,
                "State Diff queue depth startup scan reached its safety cap"
            );
        }
    }

    fn persisted(result: &CommandResult) -> PersistedCommandResult {
        PersistedCommandResult {
            acknowledgement: PersistedAcknowledgement {
                message_id: result.acknowledgement.message_id.clone(),
                accepted: result.acknowledgement.accepted,
                error_code: result.acknowledgement.error_code.clone(),
                error_message: result.acknowledgement.error_message.clone(),
            },
            event_id: result.event.as_ref().map(|event| event.event_id.clone()),
            event_payload: result.event.as_ref().map(|event| event.encode_to_vec()),
            event_delivered: false,
        }
    }

    fn acknowledgement(persisted: &PersistedCommandResult, duplicate: bool) -> CommandAck {
        CommandAck {
            message_id: persisted.acknowledgement.message_id.clone(),
            accepted: persisted.acknowledgement.accepted,
            duplicate,
            error_code: persisted.acknowledgement.error_code.clone(),
            error_message: persisted.acknowledgement.error_message.clone(),
        }
    }

    async fn execute_takeover_barrier(
        &self,
        command: &CommandEnvelope,
        payload_session_id: &str,
        user_id: &str,
        begin: bool,
    ) -> CommandResult {
        if payload_session_id != command.session_id {
            return self.failed(
                command,
                anyhow::anyhow!("takeover payload session_id does not match envelope"),
            );
        }
        if user_id.is_empty() || user_id.chars().count() > 128 {
            return self.failed(
                command,
                anyhow::anyhow!("takeover user_id must contain 1 to 128 characters"),
            );
        }
        let input = self
            .input_brokers
            .lock()
            .await
            .get(&command.session_id)
            .cloned();
        let Some(input) = input else {
            return self.failed(
                command,
                anyhow::anyhow!("input broker is not available for session"),
            );
        };
        if let Err(error) = input.release_all().await {
            return self.failed(command, error);
        }
        let state = match self
            .state_collector
            .collect_current_state(&command.session_id)
            .await
        {
            Ok(state) => {
                self.state_baselines
                    .lock()
                    .await
                    .insert(command.session_id.clone(), state.clone());
                self.resync_required
                    .lock()
                    .await
                    .remove(&command.session_id);
                Self::browser_state_payload(state)
            }
            Err(error) => return self.failed(command, error),
        };
        let sequence = match self.next_event_sequence(&command.session_id).await {
            Ok(sequence) => sequence,
            Err(error) => return self.failed(command, error),
        };
        if begin {
            self.active_human_takeovers
                .lock()
                .await
                .insert(command.session_id.clone());
        } else {
            self.active_human_takeovers
                .lock()
                .await
                .remove(&command.session_id);
        }
        let event = if begin {
            Self::event(
                command,
                "HumanTakeoverReady",
                sequence,
                HumanTakeoverReadyEvent {
                    session_id: command.session_id.clone(),
                    user_id: user_id.to_owned(),
                    state: Some(state),
                },
            )
        } else {
            Self::event(
                command,
                "HumanTakeoverEnded",
                sequence,
                HumanTakeoverEndedEvent {
                    session_id: command.session_id.clone(),
                    user_id: user_id.to_owned(),
                    state: Some(state),
                    reason: "USER_RELEASE".to_owned(),
                },
            )
        };
        Self::result(Self::ack(&command.message_id, true, "", ""), Some(event))
    }

    async fn handle_desktop_disconnect(
        &self,
        claims: RemoteDesktopTicketClaims,
    ) -> anyhow::Result<()> {
        let mut last_error = None;
        let mut state = None;
        for attempt in 1..=5 {
            let input = self
                .input_brokers
                .lock()
                .await
                .get(&claims.session_id)
                .cloned();
            let cdp_release = match input {
                Some(input) => input.release_all().await,
                None => Ok(()),
            };
            let x11_release = self
                .runtime_supervisor
                .release_desktop_input(&claims.session_id)
                .await;
            let collected = self
                .state_collector
                .collect_current_state(&claims.session_id)
                .await;
            match (cdp_release, x11_release, collected) {
                (Ok(()), Ok(()), Ok(collected)) => {
                    self.state_baselines
                        .lock()
                        .await
                        .insert(claims.session_id.clone(), collected.clone());
                    self.resync_required.lock().await.remove(&claims.session_id);
                    state = Some(Self::browser_state_payload(collected));
                    break;
                }
                (cdp, x11, collected) => {
                    last_error = Some(anyhow::anyhow!(
                        "desktop disconnect barrier attempt {attempt} failed: cdp={:?}, x11={:?}, state={:?}",
                        cdp.err(),
                        x11.err(),
                        collected.err()
                    ));
                    tokio::time::sleep(Duration::from_millis(100 * attempt)).await;
                }
            }
        }
        let state = state.ok_or_else(|| {
            last_error.unwrap_or_else(|| anyhow::anyhow!("desktop disconnect barrier failed"))
        })?;
        self.active_human_takeovers
            .lock()
            .await
            .remove(&claims.session_id);
        let sequence = self.next_event_sequence(&claims.session_id).await?;
        let event_id = format!("evt_desktop_disconnect_{}", uuid::Uuid::new_v4().simple());
        let message_id = format!("desktop_disconnect_{}", uuid::Uuid::new_v4().simple());
        let event = EventEnvelope {
            event_id: event_id.clone(),
            event_type: "HumanTakeoverEnded".to_owned(),
            tenant_id: claims.tenant_id.clone(),
            session_id: claims.session_id.clone(),
            coordinator_term: claims.coordinator_term,
            context_epoch: claims.context_epoch,
            operation_epoch: claims.operation_epoch as i64,
            sequence,
            payload: HumanTakeoverEndedEvent {
                session_id: claims.session_id.clone(),
                user_id: claims.actor_id.clone(),
                state: Some(state),
                reason: "GATEWAY_DISCONNECT".to_owned(),
            }
            .encode_to_vec(),
        };
        let persisted = PersistedCommandResult {
            acknowledgement: PersistedAcknowledgement {
                message_id,
                accepted: true,
                error_code: String::new(),
                error_message: String::new(),
            },
            event_id: Some(event_id),
            event_payload: Some(event.encode_to_vec()),
            event_delivered: false,
        };
        self.journal.record_command_result(&persisted).await?;
        if let Err(error) = self.publish_and_mark(event).await {
            tracing::warn!(
                session_id = claims.session_id,
                error = %error,
                "Desktop disconnect event queued for redelivery"
            );
        }
        Ok(())
    }

    async fn execute_agent_action(
        &self,
        payload: &AgentActionCommand,
    ) -> anyhow::Result<CurrentState> {
        match payload.tool_id.as_str() {
            "CLICK_TARGET" | "TYPE_TEXT" => {
                let target = self
                    .state_collector
                    .resolve_target(
                        &payload.session_id,
                        &payload.target_ref,
                        payload.target_revision,
                    )
                    .await?;
                if payload.tool_id == "TYPE_TEXT" {
                    anyhow::ensure!(!target.sensitive, "sensitive target is forbidden");
                    anyhow::ensure!(
                        matches!(target.role.as_str(), "textbox" | "combobox"),
                        "type target role is not supported"
                    );
                    anyhow::ensure!(
                        payload.sealed_text.is_empty() && !payload.text.is_empty(),
                        "type text must be materialized only for Node dispatch"
                    );
                } else {
                    anyhow::ensure!(
                        payload.text.is_empty() && payload.sealed_text.is_empty(),
                        "click target cannot carry text"
                    );
                }
                let center_x = target.bounds.x + target.bounds.width / 2.0;
                let center_y = target.bounds.y + target.bounds.height / 2.0;
                anyhow::ensure!(
                    center_x.is_finite()
                        && center_y.is_finite()
                        && center_x >= 0.0
                        && center_y >= 0.0
                        && center_x <= i32::MAX as f64
                        && center_y <= i32::MAX as f64,
                    "target center is outside the input coordinate range"
                );
                let input = self
                    .input_brokers
                    .lock()
                    .await
                    .get(&payload.session_id)
                    .cloned()
                    .ok_or_else(|| anyhow::anyhow!("input broker is unavailable"))?;
                let base = input.ledger_snapshot().await.last_sequence;
                let sequence = |offset: u64| {
                    base.checked_add(offset)
                        .ok_or_else(|| anyhow::anyhow!("input sequence overflow"))
                };
                input
                    .mouse_move(
                        center_x.round() as i32,
                        center_y.round() as i32,
                        sequence(1)?,
                    )
                    .await?;
                input.mouse_down(0, sequence(2)?).await?;
                input.mouse_up(0, sequence(3)?).await?;
                if payload.tool_id == "TYPE_TEXT" {
                    input.key_down(InputKey::Control, sequence(4)?).await?;
                    input
                        .key_down(InputKey::Character("a".to_owned()), sequence(5)?)
                        .await?;
                    input
                        .key_up(InputKey::Character("a".to_owned()), sequence(6)?)
                        .await?;
                    input.key_up(InputKey::Control, sequence(7)?).await?;
                    input.insert_text(&payload.text, sequence(8)?).await?;
                }
            }
            "SCROLL" => {
                anyhow::ensure!(
                    payload.target_ref.is_empty()
                        && payload.target_revision == 0
                        && payload.text.is_empty()
                        && payload.sealed_text.is_empty(),
                    "scroll action contains unsupported fields"
                );
                self.state_collector
                    .scroll(&payload.session_id, payload.scroll_delta_y)
                    .await?;
            }
            "WAIT_FOR" => {
                anyhow::ensure!(
                    (100..=10_000).contains(&payload.timeout_ms),
                    "wait timeout is invalid"
                );
                let deadline =
                    tokio::time::Instant::now() + Duration::from_millis(payload.timeout_ms.into());
                let mut previous_hash: Option<String> = None;
                loop {
                    let state = self
                        .state_collector
                        .collect_current_state(&payload.session_id)
                        .await?;
                    let satisfied = match payload.wait_condition.as_str() {
                        "STATE_CHANGED" => state.content_hash != payload.base_content_hash,
                        "STATE_STABLE" => {
                            let stable = previous_hash
                                .as_ref()
                                .is_some_and(|previous| previous == &state.content_hash);
                            previous_hash = Some(state.content_hash.clone());
                            stable
                        }
                        "TARGET_PRESENT" => self
                            .state_collector
                            .resolve_target(
                                &payload.session_id,
                                &payload.target_ref,
                                state.target_revision,
                            )
                            .await
                            .is_ok(),
                        _ => anyhow::bail!("wait condition is unsupported"),
                    };
                    if satisfied {
                        break;
                    }
                    anyhow::ensure!(
                        tokio::time::Instant::now() < deadline,
                        "wait condition timed out"
                    );
                    tokio::time::sleep(Duration::from_millis(100)).await;
                }
            }
            _ => anyhow::bail!("agent action tool is unsupported"),
        }
        let state = self
            .state_collector
            .collect_current_state(&payload.session_id)
            .await?;
        anyhow::ensure!(
            state.state_version > payload.base_state_version,
            "post-action state version did not advance"
        );
        Ok(state)
    }

    async fn execute(&self, command: &CommandEnvelope) -> CommandResult {
        match command.command_type.as_str() {
            "StartRuntime" => {
                let payload = StartRuntimeCommand::decode(command.payload.as_slice());
                match payload {
                    Ok(payload) => {
                        if payload.session_id != command.session_id {
                            return self.failed(
                                command,
                                anyhow::anyhow!(
                                    "runtime payload session_id does not match envelope"
                                ),
                            );
                        }
                        let state_collector_budget_percent =
                            payload.state_collector_budget_percent.unwrap_or(100);
                        let remote_desktop_bitrate_kbps =
                            payload.remote_desktop_bitrate_kbps.unwrap_or(0);
                        let extension_cpu_weight = payload.extension_cpu_weight.unwrap_or(100);
                        let media_encoder_slots = payload.media_encoder_slots.unwrap_or(0);
                        let freeze_background_tabs =
                            payload.freeze_background_tabs.unwrap_or(false);
                        let block_new_tabs = payload.block_new_tabs.unwrap_or(false);
                        let success_trace_sample_percent =
                            payload.success_trace_sample_percent.unwrap_or(100);
                        let observer_frame_rate_fps = payload
                            .observer_frame_rate_fps
                            .unwrap_or(if payload.desktop_required { 30 } else { 0 });
                        let video_recording_enabled =
                            payload.video_recording_enabled.unwrap_or(false);
                        let success_screenshot_sample_percent =
                            payload.success_screenshot_sample_percent.unwrap_or(100);
                        let paused_extension_ids = payload
                            .extension_background_policy
                            .as_ref()
                            .map(|policy| policy.paused_extension_ids.clone())
                            .unwrap_or_default();
                        if payload.resource_class == "L0"
                            || payload.cpu_millis == 0
                            || payload.memory_request_mib == 0
                            || payload.memory_limit_mib < payload.memory_request_mib
                            || payload.pid_limit < 32
                            || payload.tab_budget == 0
                            || !(10..=100).contains(&state_collector_budget_percent)
                            || !(1..=100).contains(&success_trace_sample_percent)
                            || !(1..=100).contains(&success_screenshot_sample_percent)
                            || observer_frame_rate_fps > 60
                            || remote_desktop_bitrate_kbps > 100_000
                            || (payload.desktop_required
                                && !(250..=100_000).contains(&remote_desktop_bitrate_kbps))
                            || (!payload.desktop_required && remote_desktop_bitrate_kbps != 0)
                            || (payload.desktop_required
                                && !(1..=60).contains(&observer_frame_rate_fps))
                            || (!payload.desktop_required && observer_frame_rate_fps != 0)
                            || !(1..=10_000).contains(&extension_cpu_weight)
                            || media_encoder_slots > 32
                            || paused_extension_ids.len()
                                != paused_extension_ids.iter().collect::<HashSet<_>>().len()
                            || !payload
                                .extension_ids
                                .iter()
                                .collect::<HashSet<_>>()
                                .is_superset(&paused_extension_ids.iter().collect::<HashSet<_>>())
                        {
                            return self.failed(
                                command,
                                anyhow::anyhow!("runtime resource limit payload is invalid"),
                            );
                        }
                        let extension_dirs =
                            match self.resolve_extension_dirs(&payload.extension_ids) {
                                Ok(directories) => directories,
                                Err(error) => return self.failed(command, error),
                            };
                        if payload.desktop_required && !self.desktop_enabled {
                            return self.failed(
                                command,
                                anyhow::anyhow!(
                                    "desktop-required placement reached a headless node"
                                ),
                            );
                        }
                        if media_encoder_slots > 0 && !self.desktop_enabled {
                            return self.failed(
                                command,
                                anyhow::anyhow!(
                                    "Media Encoder placement reached a Node without the encoder runtime"
                                ),
                            );
                        }
                        if payload.gpu_required || payload.native_os_required {
                            return self.failed(
                                command,
                                anyhow::anyhow!(
                                    "placement requires a Node capability not supported by this agent"
                                ),
                            );
                        }
                        let Some(storage_helper) = self.storage_helper.as_ref() else {
                            return self.failed(
                                command,
                                anyhow::anyhow!("storage helper is not configured"),
                            );
                        };
                        let workspace = match storage_helper
                            .acquire_workspace_at_checkpoint(
                                &command.tenant_id,
                                &payload.profile_id,
                                &command.session_id,
                                (!payload.profile_checkpoint_id.is_empty())
                                    .then_some(payload.profile_checkpoint_id.as_str()),
                            )
                            .await
                        {
                            Ok(workspace) => workspace,
                            Err(error) => return self.failed(command, error),
                        };
                        let (observed_network, proxy_server) = if payload
                            .proxy_binding_id
                            .is_empty()
                        {
                            if !self.allow_direct_network {
                                self.release_start_resources(&command.session_id, &workspace)
                                    .await;
                                return self.failed(
                                        command,
                                        anyhow::anyhow!(
                                            "direct network is disabled and no proxy binding was supplied"
                                        ),
                                    );
                            }
                            (None, None)
                        } else {
                            let Some(network_helper) = self.network_helper.as_ref() else {
                                self.release_start_resources(&command.session_id, &workspace)
                                    .await;
                                return self.failed(
                                        command,
                                        anyhow::anyhow!(
                                            "proxy binding supplied but static provider is not configured"
                                        ),
                                    );
                            };
                            match network_helper
                                .bind_proxy(
                                    &payload.proxy_binding_id,
                                    &command.session_id,
                                    payload.proxy_provider_id.as_deref().unwrap_or_default(),
                                    payload
                                        .proxy_expected_exit_ip
                                        .as_deref()
                                        .unwrap_or_default(),
                                    payload.proxy_credential_ref.as_deref().unwrap_or_default(),
                                )
                                .await
                            {
                                Ok((observed, proxy_server)) => {
                                    (Some(observed), Some(proxy_server))
                                }
                                Err(error) => {
                                    self.release_start_resources(&command.session_id, &workspace)
                                        .await;
                                    return self.failed(command, error);
                                }
                            }
                        };
                        let cdp_port = match self.allocate_cdp_port().await {
                            Ok(port) => port,
                            Err(error) => {
                                self.release_start_resources(&command.session_id, &workspace)
                                    .await;
                                return self.failed(command, error);
                            }
                        };
                        let runtime_build_id = payload.runtime_build_id;
                        self.runtime_supervisor
                            .ensure_generation_at_least(
                                &command.session_id,
                                payload.minimum_browser_generation,
                            )
                            .await;
                        let desktop_processes_required =
                            payload.desktop_required || media_encoder_slots > 0;
                        let (display, vnc_port) = if self.desktop_enabled
                            && desktop_processes_required
                        {
                            let vnc_port = match Self::allocate_loopback_port() {
                                Ok(port) => port,
                                Err(error) => {
                                    self.release_start_resources(&command.session_id, &workspace)
                                        .await;
                                    return self.failed(command, error);
                                }
                            };
                            (self.allocate_display().await, Some(vnc_port))
                        } else {
                            (payload.display, None)
                        };
                        match self
                            .runtime_supervisor
                            .start(RuntimeSpec {
                                session_id: command.session_id.clone(),
                                runtime_build_id: runtime_build_id.clone(),
                                profile_dir: PathBuf::from(&workspace.core_dir),
                                cache_dir: PathBuf::from(&workspace.ephemeral_dir).join("cache"),
                                proxy_server,
                                display,
                                cdp_port,
                                vnc_port,
                                extension_dirs,
                                resource_limits: RuntimeResourceLimits {
                                    resource_class: payload.resource_class,
                                    cpu_millis: payload.cpu_millis,
                                    memory_request_mib: payload.memory_request_mib,
                                    memory_limit_mib: payload.memory_limit_mib,
                                    pid_limit: payload.pid_limit,
                                    tab_budget: payload.tab_budget,
                                    extension_cpu_weight,
                                    media_encoder_slots,
                                    desktop_required: payload.desktop_required,
                                    gpu_required: payload.gpu_required,
                                    native_os_required: payload.native_os_required,
                                    isolation_required: payload.isolation_required,
                                },
                            })
                            .await
                        {
                            Ok(handle) => {
                                if payload.desktop_required {
                                    let Some(gateway) = self.remote_desktop_gateway.as_ref() else {
                                        let _ =
                                            self.runtime_supervisor.stop(&command.session_id).await;
                                        self.release_start_resources(
                                            &command.session_id,
                                            &workspace,
                                        )
                                        .await;
                                        return self.failed(
                                            command,
                                            anyhow::anyhow!(
                                                "Remote Desktop Gateway is unavailable"
                                            ),
                                        );
                                    };
                                    let Some(endpoint) = handle.vnc_endpoint.as_ref() else {
                                        let _ =
                                            self.runtime_supervisor.stop(&command.session_id).await;
                                        self.release_start_resources(
                                            &command.session_id,
                                            &workspace,
                                        )
                                        .await;
                                        return self.failed(
                                            command,
                                            anyhow::anyhow!(
                                                "desktop Runtime did not expose a VNC endpoint"
                                            ),
                                        );
                                    };
                                    let endpoint = match endpoint.parse::<SocketAddr>() {
                                        Ok(endpoint) => endpoint,
                                        Err(error) => {
                                            let _ = self
                                                .runtime_supervisor
                                                .stop(&command.session_id)
                                                .await;
                                            self.release_start_resources(
                                                &command.session_id,
                                                &workspace,
                                            )
                                            .await;
                                            return self.failed(command, error.into());
                                        }
                                    };
                                    if let Err(error) =
                                        gateway.register_session(&command.session_id, endpoint)
                                    {
                                        let _ =
                                            self.runtime_supervisor.stop(&command.session_id).await;
                                        self.release_start_resources(
                                            &command.session_id,
                                            &workspace,
                                        )
                                        .await;
                                        return self.failed(command, error);
                                    }
                                    if let Err(error) = gateway.set_bitrate_limit(
                                        &command.session_id,
                                        remote_desktop_bitrate_kbps,
                                    ) {
                                        gateway.unregister_session(&command.session_id);
                                        let _ =
                                            self.runtime_supervisor.stop(&command.session_id).await;
                                        self.release_start_resources(
                                            &command.session_id,
                                            &workspace,
                                        )
                                        .await;
                                        return self.failed(command, error);
                                    }
                                    if let Err(error) = gateway.set_observer_frame_rate(
                                        &command.session_id,
                                        observer_frame_rate_fps,
                                    ) {
                                        gateway.unregister_session(&command.session_id);
                                        let _ =
                                            self.runtime_supervisor.stop(&command.session_id).await;
                                        self.release_start_resources(
                                            &command.session_id,
                                            &workspace,
                                        )
                                        .await;
                                        return self.failed(command, error);
                                    }
                                }
                                if let Err(error) = self
                                    .state_collector
                                    .register_runtime(&command.session_id, &handle.cdp_endpoint)
                                    .await
                                {
                                    if let Some(gateway) = self.remote_desktop_gateway.as_ref() {
                                        gateway.unregister_session(&command.session_id);
                                    }
                                    let _ = self.runtime_supervisor.stop(&command.session_id).await;
                                    self.release_start_resources(&command.session_id, &workspace)
                                        .await;
                                    return self.failed(command, error);
                                }
                                if let Err(error) = self
                                    .state_collector
                                    .set_resource_budget(
                                        &command.session_id,
                                        state_collector_budget_percent,
                                    )
                                    .await
                                {
                                    if let Some(gateway) = self.remote_desktop_gateway.as_ref() {
                                        gateway.unregister_session(&command.session_id);
                                    }
                                    self.state_collector
                                        .unregister_runtime(&command.session_id)
                                        .await;
                                    let _ = self.runtime_supervisor.stop(&command.session_id).await;
                                    self.release_start_resources(&command.session_id, &workspace)
                                        .await;
                                    return self.failed(command, error);
                                }
                                if let Err(error) = self
                                    .state_collector
                                    .set_tab_resource_policy(
                                        &command.session_id,
                                        TabResourcePolicy {
                                            tab_budget: payload.tab_budget,
                                            freeze_background_tabs,
                                            block_new_tabs,
                                            paused_extension_ids,
                                        },
                                    )
                                    .await
                                {
                                    if let Some(gateway) = self.remote_desktop_gateway.as_ref() {
                                        gateway.unregister_session(&command.session_id);
                                    }
                                    self.state_collector
                                        .unregister_runtime(&command.session_id)
                                        .await;
                                    let _ = self.runtime_supervisor.stop(&command.session_id).await;
                                    self.release_start_resources(&command.session_id, &workspace)
                                        .await;
                                    return self.failed(command, error);
                                }
                                if let Err(error) = self
                                    .state_collector
                                    .start_safety_monitor(&command.session_id)
                                    .await
                                {
                                    if let Some(gateway) = self.remote_desktop_gateway.as_ref() {
                                        gateway.unregister_session(&command.session_id);
                                    }
                                    self.state_collector
                                        .unregister_runtime(&command.session_id)
                                        .await;
                                    let _ = self.runtime_supervisor.stop(&command.session_id).await;
                                    self.release_start_resources(&command.session_id, &workspace)
                                        .await;
                                    return self.failed(command, error);
                                }
                                if let Err(error) = self
                                    .session_recorders
                                    .register(
                                        RecordingSpec {
                                            session_id: command.session_id.clone(),
                                            cdp_endpoint: handle.cdp_endpoint.clone(),
                                            workspace: workspace.clone(),
                                            storage_helper: Arc::clone(storage_helper),
                                        },
                                        video_recording_enabled,
                                    )
                                    .await
                                {
                                    if let Some(gateway) = self.remote_desktop_gateway.as_ref() {
                                        gateway.unregister_session(&command.session_id);
                                    }
                                    self.state_collector
                                        .unregister_runtime(&command.session_id)
                                        .await;
                                    let _ = self.runtime_supervisor.stop(&command.session_id).await;
                                    self.release_start_resources(&command.session_id, &workspace)
                                        .await;
                                    return self.failed(command, error);
                                }
                                if let Err(error) = self
                                    .session_evidence
                                    .register(
                                        EvidenceSpec {
                                            session_id: command.session_id.clone(),
                                            cdp_endpoint: handle.cdp_endpoint.clone(),
                                            workspace: workspace.clone(),
                                            storage_helper: Arc::clone(storage_helper),
                                        },
                                        success_screenshot_sample_percent,
                                    )
                                    .await
                                {
                                    if let Some(gateway) = self.remote_desktop_gateway.as_ref() {
                                        gateway.unregister_session(&command.session_id);
                                    }
                                    self.state_collector
                                        .unregister_runtime(&command.session_id)
                                        .await;
                                    let _ = self
                                        .session_recorders
                                        .unregister(&command.session_id)
                                        .await;
                                    let _ = self.runtime_supervisor.stop(&command.session_id).await;
                                    self.release_start_resources(&command.session_id, &workspace)
                                        .await;
                                    return self.failed(command, error);
                                }
                                let input = match CdpDesktopInput::connect(&handle.cdp_endpoint)
                                    .await
                                {
                                    Ok(input) => Arc::new(input),
                                    Err(error) => {
                                        if let Some(gateway) = self.remote_desktop_gateway.as_ref()
                                        {
                                            gateway.unregister_session(&command.session_id);
                                        }
                                        self.state_collector
                                            .unregister_runtime(&command.session_id)
                                            .await;
                                        let _ = self
                                            .session_recorders
                                            .unregister(&command.session_id)
                                            .await;
                                        self.session_evidence.unregister(&command.session_id).await;
                                        let _ =
                                            self.runtime_supervisor.stop(&command.session_id).await;
                                        self.release_start_resources(
                                            &command.session_id,
                                            &workspace,
                                        )
                                        .await;
                                        return self.failed(command, error);
                                    }
                                };
                                self.input_brokers
                                    .lock()
                                    .await
                                    .insert(command.session_id.clone(), input);
                                self.profile_workspaces.lock().await.insert(
                                    command.session_id.clone(),
                                    ActiveProfileWorkspace {
                                        workspace: workspace.clone(),
                                        runtime_build_id: runtime_build_id.clone(),
                                    },
                                );
                                let runtime_lease = RuntimeLease {
                                    session_id: command.session_id.clone(),
                                    tenant_id: command.tenant_id.clone(),
                                    runtime_build_id: runtime_build_id.clone(),
                                    coordinator_term: command.coordinator_term,
                                    context_epoch: command.context_epoch.saturating_add(1),
                                    browser_generation: handle.browser_generation,
                                    pid: handle.pid,
                                    process_started_at: handle.process_started_at,
                                };
                                let sequence = match self
                                    .next_event_sequence(&command.session_id)
                                    .await
                                {
                                    Ok(sequence) => sequence,
                                    Err(error) => {
                                        if let Some(gateway) = self.remote_desktop_gateway.as_ref()
                                        {
                                            gateway.unregister_session(&command.session_id);
                                        }
                                        self.input_brokers.lock().await.remove(&command.session_id);
                                        self.state_collector
                                            .unregister_runtime(&command.session_id)
                                            .await;
                                        let _ = self
                                            .session_recorders
                                            .unregister(&command.session_id)
                                            .await;
                                        self.session_evidence.unregister(&command.session_id).await;
                                        let _ =
                                            self.runtime_supervisor.stop(&command.session_id).await;
                                        self.profile_workspaces
                                            .lock()
                                            .await
                                            .remove(&command.session_id);
                                        self.release_start_resources(
                                            &command.session_id,
                                            &workspace,
                                        )
                                        .await;
                                        return self.failed(command, error);
                                    }
                                };
                                let event = Self::event(
                                    command,
                                    "RuntimeStarted",
                                    sequence,
                                    RuntimeStartedEvent {
                                        session_id: command.session_id.clone(),
                                        pid: handle.pid,
                                        browser_generation: handle.browser_generation,
                                        cdp_endpoint: handle.cdp_endpoint,
                                        node_id: self.node_id.clone(),
                                        runtime_build_id,
                                        proxy_binding_id: payload.proxy_binding_id,
                                        exit_ip: observed_network
                                            .as_ref()
                                            .map(|network| network.exit_ip.clone())
                                            .unwrap_or_default(),
                                        exit_country: observed_network
                                            .as_ref()
                                            .map(|network| network.country.clone())
                                            .unwrap_or_default(),
                                        exit_asn: observed_network
                                            .map(|network| network.asn)
                                            .unwrap_or_default(),
                                    },
                                );
                                if let Err(error) = self
                                    .success_trace_sampler
                                    .set(&command.session_id, success_trace_sample_percent)
                                    .await
                                {
                                    if let Some(gateway) = self.remote_desktop_gateway.as_ref() {
                                        gateway.unregister_session(&command.session_id);
                                    }
                                    self.input_brokers.lock().await.remove(&command.session_id);
                                    self.state_collector
                                        .unregister_runtime(&command.session_id)
                                        .await;
                                    let _ = self
                                        .session_recorders
                                        .unregister(&command.session_id)
                                        .await;
                                    self.session_evidence.unregister(&command.session_id).await;
                                    let _ = self.runtime_supervisor.stop(&command.session_id).await;
                                    self.profile_workspaces
                                        .lock()
                                        .await
                                        .remove(&command.session_id);
                                    self.release_start_resources(&command.session_id, &workspace)
                                        .await;
                                    return self.failed(command, error);
                                }
                                Self::runtime_started_result(
                                    Self::ack(&command.message_id, true, "", ""),
                                    event,
                                    runtime_lease,
                                )
                            }
                            Err(error) => {
                                self.release_start_resources(&command.session_id, &workspace)
                                    .await;
                                self.failed(command, error)
                            }
                        }
                    }
                    Err(error) => self.failed(command, error.into()),
                }
            }
            "StopRuntime" => match StopRuntimeCommand::decode(command.payload.as_slice()) {
                Ok(payload) => {
                    if let Some(gateway) = self.remote_desktop_gateway.as_ref() {
                        gateway.unregister_session(&command.session_id);
                    }
                    if let Some(input) = self.input_brokers.lock().await.remove(&command.session_id)
                    {
                        if let Err(error) = input.release_all().await {
                            return self.failed(command, error);
                        }
                    }
                    if let Err(error) = self.session_recorders.unregister(&command.session_id).await
                    {
                        return self.failed(command, error);
                    }
                    self.session_evidence.unregister(&command.session_id).await;
                    self.state_collector
                        .unregister_runtime(&command.session_id)
                        .await;
                    self.state_baselines
                        .lock()
                        .await
                        .remove(&command.session_id);
                    self.resync_required
                        .lock()
                        .await
                        .remove(&command.session_id);
                    match self.runtime_supervisor.stop(&command.session_id).await {
                        Ok(()) => {
                            tracing::info!(
                                session_id = %command.session_id,
                                "Runtime stopped; beginning durable cleanup"
                            );
                            let sequence = match self.next_event_sequence(&command.session_id).await
                            {
                                Ok(sequence) => sequence,
                                Err(error) => return self.failed(command, error),
                            };
                            tracing::info!(
                                session_id = %command.session_id,
                                sequence,
                                "Reserved RuntimeStopped event sequence"
                            );
                            let active_profile = self
                                .profile_workspaces
                                .lock()
                                .await
                                .get(&command.session_id)
                                .cloned();
                            let (
                                profile_id,
                                checkpoint_id,
                                checkpoint_epoch,
                                profile_write_epoch,
                                core_size_bytes,
                                checkpoint_file_count,
                                restore_status,
                            ) = match active_profile {
                                Some(active_profile) => {
                                    let Some(storage_helper) = self.storage_helper.as_ref() else {
                                        return self.failed(
                                            command,
                                            anyhow::anyhow!("storage helper is not configured"),
                                        );
                                    };
                                    tracing::info!(
                                        session_id = %command.session_id,
                                        "Creating durable Profile checkpoint"
                                    );
                                    let checkpoint = match storage_helper
                                        .checkpoint(
                                            &active_profile.workspace,
                                            &active_profile.runtime_build_id,
                                        )
                                        .await
                                    {
                                        Ok(checkpoint) => checkpoint,
                                        Err(error) => return self.failed(command, error),
                                    };
                                    tracing::info!(
                                        session_id = %command.session_id,
                                        checkpoint_id = %checkpoint.checkpoint_id,
                                        "Durable Profile checkpoint committed"
                                    );
                                    if let Err(error) =
                                        storage_helper.release(&active_profile.workspace).await
                                    {
                                        return self.failed(command, error);
                                    }
                                    self.profile_workspaces
                                        .lock()
                                        .await
                                        .remove(&command.session_id);
                                    let restore_status = match active_profile
                                        .workspace
                                        .restore_status
                                    {
                                        StorageRestoreStatus::Empty => "EMPTY",
                                        StorageRestoreStatus::TechnicalReady => "TECHNICAL_READY",
                                    };
                                    (
                                        active_profile.workspace.profile_id,
                                        checkpoint.checkpoint_id,
                                        checkpoint.checkpoint_epoch,
                                        checkpoint.profile_write_epoch,
                                        checkpoint.core_size_bytes,
                                        checkpoint.checkpoint_file_count,
                                        restore_status,
                                    )
                                }
                                None => (String::new(), String::new(), 0, 0, 0, 0, "EMPTY"),
                            };
                            if let Some(network_helper) = self.network_helper.as_ref() {
                                if let Err(error) =
                                    network_helper.release(&command.session_id).await
                                {
                                    return self.failed(command, error);
                                }
                            }
                            tracing::info!(
                                session_id = %command.session_id,
                                "Runtime cleanup committed; emitting RuntimeStopped"
                            );
                            let event = Self::event(
                                command,
                                "RuntimeStopped",
                                sequence,
                                RuntimeStoppedEvent {
                                    session_id: command.session_id.clone(),
                                    reason: payload.reason,
                                    exit_code: 0,
                                    profile_id,
                                    checkpoint_id,
                                    checkpoint_epoch,
                                    profile_write_epoch,
                                    core_size_bytes,
                                    checkpoint_file_count,
                                    restore_status: restore_status.to_owned(),
                                },
                            );
                            Self::runtime_stopped_result(
                                Self::ack(&command.message_id, true, "", ""),
                                event,
                            )
                        }
                        Err(error) => self.failed(command, error),
                    }
                }
                Err(error) => self.failed(command, error.into()),
            },
            "AdjustRuntimeResources" => {
                match AdjustRuntimeResourcesCommand::decode(command.payload.as_slice()) {
                    Ok(payload) => {
                        if payload.session_id != command.session_id {
                            return self.failed(
                                command,
                                anyhow::anyhow!(
                                    "resource payload session_id does not match envelope"
                                ),
                            );
                        }
                        let current_limits = match self
                            .runtime_supervisor
                            .current_resource_limits(&command.session_id)
                            .await
                        {
                            Ok(limits) => limits,
                            Err(error) => return self.failed(command, error),
                        };
                        let next = RuntimeResourceLimits {
                            resource_class: payload.resource_class.clone(),
                            cpu_millis: payload.cpu_millis,
                            memory_request_mib: payload.memory_request_mib,
                            memory_limit_mib: payload.memory_limit_mib,
                            pid_limit: payload.pid_limit,
                            tab_budget: payload.tab_budget,
                            extension_cpu_weight: payload
                                .extension_cpu_weight
                                .unwrap_or(current_limits.extension_cpu_weight),
                            media_encoder_slots: payload
                                .media_encoder_slots
                                .unwrap_or(current_limits.media_encoder_slots),
                            desktop_required: payload.desktop_required,
                            gpu_required: payload.gpu_required,
                            native_os_required: payload.native_os_required,
                            isolation_required: payload.isolation_required,
                        };
                        let previous_state_collector_budget_percent = self
                            .state_collector
                            .resource_budget_percent(&command.session_id)
                            .await;
                        let previous_tab_resource_policy = self
                            .state_collector
                            .tab_resource_policy(&command.session_id)
                            .await
                            .unwrap_or(TabResourcePolicy {
                                tab_budget: current_limits.tab_budget,
                                freeze_background_tabs: false,
                                block_new_tabs: false,
                                paused_extension_ids: Vec::new(),
                            });
                        let next_state_collector_budget_percent = payload
                            .state_collector_budget_percent
                            .unwrap_or(previous_state_collector_budget_percent);
                        let previous_success_trace_sample_percent = self
                            .success_trace_sampler
                            .percentage(&command.session_id)
                            .await;
                        let next_success_trace_sample_percent = payload
                            .success_trace_sample_percent
                            .unwrap_or(previous_success_trace_sample_percent);
                        let previous_success_screenshot_sample_percent = match self
                            .session_evidence
                            .success_sample_percent(&command.session_id)
                            .await
                        {
                            Some(value) => value,
                            None => {
                                return self.failed(
                                    command,
                                    anyhow::anyhow!("Screenshot evidence actuator is unavailable"),
                                )
                            }
                        };
                        let next_success_screenshot_sample_percent = payload
                            .success_screenshot_sample_percent
                            .unwrap_or(previous_success_screenshot_sample_percent);
                        if !(10..=100).contains(&next_state_collector_budget_percent) {
                            return self.failed(
                                command,
                                anyhow::anyhow!(
                                    "State Collector budget must be between 10 and 100 percent"
                                ),
                            );
                        }
                        if !(1..=100).contains(&next_success_trace_sample_percent) {
                            return self.failed(
                                command,
                                anyhow::anyhow!(
                                    "success Trace sample percent must be between 1 and 100"
                                ),
                            );
                        }
                        if !(1..=100).contains(&next_success_screenshot_sample_percent) {
                            return self.failed(
                                command,
                                anyhow::anyhow!(
                                    "success screenshot sample percent must be between 1 and 100"
                                ),
                            );
                        }
                        let registered_remote_desktop_bitrate_kbps = self
                            .remote_desktop_gateway
                            .as_ref()
                            .and_then(|gateway| gateway.bitrate_limit_kbps(&command.session_id));
                        let previous_remote_desktop_bitrate_kbps =
                            registered_remote_desktop_bitrate_kbps.unwrap_or_default();
                        let next_remote_desktop_bitrate_kbps = payload
                            .remote_desktop_bitrate_kbps
                            .unwrap_or(previous_remote_desktop_bitrate_kbps);
                        let registered_observer_frame_rate_fps =
                            self.remote_desktop_gateway.as_ref().and_then(|gateway| {
                                gateway.observer_frame_rate_fps(&command.session_id)
                            });
                        let previous_observer_frame_rate_fps =
                            registered_observer_frame_rate_fps.unwrap_or_default();
                        let next_observer_frame_rate_fps = payload
                            .observer_frame_rate_fps
                            .unwrap_or(previous_observer_frame_rate_fps);
                        let previous_video_recording_enabled =
                            match self.session_recorders.enabled(&command.session_id).await {
                                Some(enabled) => enabled,
                                None => {
                                    return self.failed(
                                        command,
                                        anyhow::anyhow!("Video recording actuator is unavailable"),
                                    )
                                }
                            };
                        let next_video_recording_enabled = payload
                            .video_recording_enabled
                            .unwrap_or(previous_video_recording_enabled);
                        let next_tab_resource_policy = TabResourcePolicy {
                            tab_budget: payload.tab_budget,
                            freeze_background_tabs: payload
                                .freeze_background_tabs
                                .unwrap_or(previous_tab_resource_policy.freeze_background_tabs),
                            block_new_tabs: payload
                                .block_new_tabs
                                .unwrap_or(previous_tab_resource_policy.block_new_tabs),
                            paused_extension_ids: payload
                                .extension_background_policy
                                .as_ref()
                                .map(|policy| policy.paused_extension_ids.clone())
                                .unwrap_or_else(|| {
                                    previous_tab_resource_policy.paused_extension_ids.clone()
                                }),
                        };
                        if next_tab_resource_policy.paused_extension_ids.len()
                            != next_tab_resource_policy
                                .paused_extension_ids
                                .iter()
                                .collect::<HashSet<_>>()
                                .len()
                            || (payload.extension_background_policy.is_some()
                                && !payload
                                    .extension_ids
                                    .iter()
                                    .collect::<HashSet<_>>()
                                    .is_superset(
                                        &next_tab_resource_policy
                                            .paused_extension_ids
                                            .iter()
                                            .collect::<HashSet<_>>(),
                                    ))
                        {
                            return self.failed(
                                command,
                                anyhow::anyhow!(
                                    "Extension background policy is invalid for this Runtime"
                                ),
                            );
                        }
                        if next_remote_desktop_bitrate_kbps != 0
                            && !(250..=100_000).contains(&next_remote_desktop_bitrate_kbps)
                        {
                            return self.failed(
                                command,
                                anyhow::anyhow!(
                                    "Remote Desktop bitrate must be zero or between 250 and 100000 Kbps"
                                ),
                            );
                        }
                        if next_remote_desktop_bitrate_kbps != 0
                            && registered_remote_desktop_bitrate_kbps.is_none()
                        {
                            return self.failed(
                                command,
                                anyhow::anyhow!("Remote Desktop bitrate actuator is unavailable"),
                            );
                        }
                        if (registered_observer_frame_rate_fps.is_some()
                            && !(1..=60).contains(&next_observer_frame_rate_fps))
                            || (registered_observer_frame_rate_fps.is_none()
                                && next_observer_frame_rate_fps != 0)
                        {
                            return self.failed(
                                command,
                                anyhow::anyhow!(
                                    "Observer frame rate must match the registered desktop data plane"
                                ),
                            );
                        }
                        let runtime_adjustment = match self
                            .runtime_supervisor
                            .adjust_resources(&command.session_id, next)
                            .await
                        {
                            Ok(adjustment) => adjustment,
                            Err(error) => return self.failed(command, error),
                        };
                        let previous = runtime_adjustment.previous;
                        let applied = runtime_adjustment.applied;
                        let previous_extension_cpu_weight = previous.extension_cpu_weight;
                        let applied_extension_cpu_weight = applied.extension_cpu_weight;
                        let previous_media_encoder_slots = previous.media_encoder_slots;
                        let applied_media_encoder_slots = applied.media_encoder_slots;
                        if let Err(error) = self
                            .state_collector
                            .set_resource_budget(
                                &command.session_id,
                                next_state_collector_budget_percent,
                            )
                            .await
                        {
                            self.rollback_resource_adjustment(
                                &command.session_id,
                                previous.clone(),
                                previous_state_collector_budget_percent,
                                previous_remote_desktop_bitrate_kbps,
                                previous_observer_frame_rate_fps,
                                previous_video_recording_enabled,
                                previous_success_screenshot_sample_percent,
                                previous_tab_resource_policy.clone(),
                            )
                            .await;
                            return self.failed(command, error);
                        }
                        if let Some(gateway) = self
                            .remote_desktop_gateway
                            .as_ref()
                            .filter(|_| registered_remote_desktop_bitrate_kbps.is_some())
                        {
                            if let Err(error) = gateway.set_bitrate_limit(
                                &command.session_id,
                                next_remote_desktop_bitrate_kbps,
                            ) {
                                self.rollback_resource_adjustment(
                                    &command.session_id,
                                    previous.clone(),
                                    previous_state_collector_budget_percent,
                                    previous_remote_desktop_bitrate_kbps,
                                    previous_observer_frame_rate_fps,
                                    previous_video_recording_enabled,
                                    previous_success_screenshot_sample_percent,
                                    previous_tab_resource_policy.clone(),
                                )
                                .await;
                                return self.failed(command, error);
                            }
                            if let Err(error) = gateway.set_observer_frame_rate(
                                &command.session_id,
                                next_observer_frame_rate_fps,
                            ) {
                                self.rollback_resource_adjustment(
                                    &command.session_id,
                                    previous.clone(),
                                    previous_state_collector_budget_percent,
                                    previous_remote_desktop_bitrate_kbps,
                                    previous_observer_frame_rate_fps,
                                    previous_video_recording_enabled,
                                    previous_success_screenshot_sample_percent,
                                    previous_tab_resource_policy.clone(),
                                )
                                .await;
                                return self.failed(command, error);
                            }
                        }
                        if let Err(error) = self
                            .state_collector
                            .set_tab_resource_policy(
                                &command.session_id,
                                next_tab_resource_policy.clone(),
                            )
                            .await
                        {
                            self.rollback_resource_adjustment(
                                &command.session_id,
                                previous.clone(),
                                previous_state_collector_budget_percent,
                                previous_remote_desktop_bitrate_kbps,
                                previous_observer_frame_rate_fps,
                                previous_video_recording_enabled,
                                previous_success_screenshot_sample_percent,
                                previous_tab_resource_policy.clone(),
                            )
                            .await;
                            return self.failed(command, error);
                        }
                        if let Err(error) = self
                            .session_recorders
                            .set_enabled(&command.session_id, next_video_recording_enabled)
                            .await
                        {
                            self.rollback_resource_adjustment(
                                &command.session_id,
                                previous.clone(),
                                previous_state_collector_budget_percent,
                                previous_remote_desktop_bitrate_kbps,
                                previous_observer_frame_rate_fps,
                                previous_video_recording_enabled,
                                previous_success_screenshot_sample_percent,
                                previous_tab_resource_policy.clone(),
                            )
                            .await;
                            return self.failed(command, error);
                        }
                        if let Err(error) = self
                            .session_evidence
                            .set_success_sample_percent(
                                &command.session_id,
                                next_success_screenshot_sample_percent,
                            )
                            .await
                        {
                            self.rollback_resource_adjustment(
                                &command.session_id,
                                previous.clone(),
                                previous_state_collector_budget_percent,
                                previous_remote_desktop_bitrate_kbps,
                                previous_observer_frame_rate_fps,
                                previous_video_recording_enabled,
                                previous_success_screenshot_sample_percent,
                                previous_tab_resource_policy.clone(),
                            )
                            .await;
                            return self.failed(command, error);
                        }
                        let sequence = match self.next_event_sequence(&command.session_id).await {
                            Ok(sequence) => sequence,
                            Err(error) => {
                                self.rollback_resource_adjustment(
                                    &command.session_id,
                                    previous,
                                    previous_state_collector_budget_percent,
                                    previous_remote_desktop_bitrate_kbps,
                                    previous_observer_frame_rate_fps,
                                    previous_video_recording_enabled,
                                    previous_success_screenshot_sample_percent,
                                    previous_tab_resource_policy.clone(),
                                )
                                .await;
                                return self.failed(command, error);
                            }
                        };
                        if let Err(error) = self
                            .success_trace_sampler
                            .set(&command.session_id, next_success_trace_sample_percent)
                            .await
                        {
                            self.rollback_resource_adjustment(
                                &command.session_id,
                                previous.clone(),
                                previous_state_collector_budget_percent,
                                previous_remote_desktop_bitrate_kbps,
                                previous_observer_frame_rate_fps,
                                previous_video_recording_enabled,
                                previous_success_screenshot_sample_percent,
                                previous_tab_resource_policy.clone(),
                            )
                            .await;
                            return self.failed(command, error);
                        }
                        let event = Self::event(
                            command,
                            "RuntimeResourcesAdjusted",
                            sequence,
                            RuntimeResourcesAdjustedEvent {
                                session_id: command.session_id.clone(),
                                node_id: self.node_id.clone(),
                                old_resource_class: previous.resource_class,
                                old_cpu_millis: previous.cpu_millis,
                                old_memory_request_mib: previous.memory_request_mib,
                                old_memory_limit_mib: previous.memory_limit_mib,
                                old_pid_limit: previous.pid_limit,
                                old_tab_budget: previous.tab_budget,
                                new_resource_class: applied.resource_class,
                                new_cpu_millis: applied.cpu_millis,
                                new_memory_request_mib: applied.memory_request_mib,
                                new_memory_limit_mib: applied.memory_limit_mib,
                                new_pid_limit: applied.pid_limit,
                                new_tab_budget: applied.tab_budget,
                                reason: payload.reason,
                                operation_id: command.idempotency_key.clone(),
                                old_state_collector_budget_percent: Some(
                                    previous_state_collector_budget_percent,
                                ),
                                old_remote_desktop_bitrate_kbps: Some(
                                    previous_remote_desktop_bitrate_kbps,
                                ),
                                new_state_collector_budget_percent: Some(
                                    next_state_collector_budget_percent,
                                ),
                                new_remote_desktop_bitrate_kbps: Some(
                                    next_remote_desktop_bitrate_kbps,
                                ),
                                old_extension_cpu_weight: Some(previous_extension_cpu_weight),
                                new_extension_cpu_weight: Some(applied_extension_cpu_weight),
                                old_media_encoder_slots: Some(previous_media_encoder_slots),
                                new_media_encoder_slots: Some(applied_media_encoder_slots),
                                old_freeze_background_tabs: Some(
                                    previous_tab_resource_policy.freeze_background_tabs,
                                ),
                                new_freeze_background_tabs: Some(
                                    next_tab_resource_policy.freeze_background_tabs,
                                ),
                                old_block_new_tabs: Some(
                                    previous_tab_resource_policy.block_new_tabs,
                                ),
                                new_block_new_tabs: Some(next_tab_resource_policy.block_new_tabs),
                                old_extension_background_policy: Some(ExtensionBackgroundPolicy {
                                    paused_extension_ids: previous_tab_resource_policy
                                        .paused_extension_ids,
                                }),
                                new_extension_background_policy: Some(ExtensionBackgroundPolicy {
                                    paused_extension_ids: next_tab_resource_policy
                                        .paused_extension_ids,
                                }),
                                old_success_trace_sample_percent: Some(
                                    previous_success_trace_sample_percent,
                                ),
                                new_success_trace_sample_percent: Some(
                                    next_success_trace_sample_percent,
                                ),
                                old_observer_frame_rate_fps: Some(previous_observer_frame_rate_fps),
                                new_observer_frame_rate_fps: Some(next_observer_frame_rate_fps),
                                old_video_recording_enabled: Some(previous_video_recording_enabled),
                                new_video_recording_enabled: Some(next_video_recording_enabled),
                                old_success_screenshot_sample_percent: Some(
                                    previous_success_screenshot_sample_percent,
                                ),
                                new_success_screenshot_sample_percent: Some(
                                    next_success_screenshot_sample_percent,
                                ),
                            },
                        );
                        Self::result(Self::ack(&command.message_id, true, "", ""), Some(event))
                    }
                    Err(error) => self.failed(command, error.into()),
                }
            }
            "ExecuteInput" => match ExecuteInputCommand::decode(command.payload.as_slice()) {
                Ok(payload) => {
                    if payload.session_id != command.session_id {
                        return self.failed(
                            command,
                            anyhow::anyhow!("input payload session_id does not match envelope"),
                        );
                    }
                    let input = self
                        .input_brokers
                        .lock()
                        .await
                        .get(&command.session_id)
                        .cloned();
                    let Some(input) = input else {
                        return self.failed(
                            command,
                            anyhow::anyhow!("input broker is not available for session"),
                        );
                    };
                    let result = match payload.action {
                        Some(node_contracts::proto::execute_input_command::Action::MouseMove(
                            action,
                        )) => input.mouse_move(action.x, action.y, payload.sequence).await,
                        Some(node_contracts::proto::execute_input_command::Action::MouseDown(
                            action,
                        )) => match u8::try_from(action.button) {
                            Ok(button) => input.mouse_down(button, payload.sequence).await,
                            Err(error) => Err(error.into()),
                        },
                        Some(node_contracts::proto::execute_input_command::Action::MouseUp(
                            action,
                        )) => match u8::try_from(action.button) {
                            Ok(button) => input.mouse_up(button, payload.sequence).await,
                            Err(error) => Err(error.into()),
                        },
                        Some(node_contracts::proto::execute_input_command::Action::KeyDown(
                            action,
                        )) => match Self::parse_input_key(&action.key) {
                            Ok(key) => input.key_down(key, payload.sequence).await,
                            Err(error) => Err(error),
                        },
                        Some(node_contracts::proto::execute_input_command::Action::KeyUp(
                            action,
                        )) => match Self::parse_input_key(&action.key) {
                            Ok(key) => input.key_up(key, payload.sequence).await,
                            Err(error) => Err(error),
                        },
                        None => Err(anyhow::anyhow!("input action is required")),
                    };
                    match result {
                        Ok(()) => Self::result(Self::ack(&command.message_id, true, "", ""), None),
                        Err(error) => self.failed(command, error),
                    }
                }
                Err(error) => self.failed(command, error.into()),
            },
            "BeginHumanTakeover" => {
                match BeginHumanTakeoverCommand::decode(command.payload.as_slice()) {
                    Ok(payload) => {
                        self.execute_takeover_barrier(
                            command,
                            &payload.session_id,
                            &payload.user_id,
                            true,
                        )
                        .await
                    }
                    Err(error) => self.failed(command, error.into()),
                }
            }
            "EndHumanTakeover" => {
                match EndHumanTakeoverCommand::decode(command.payload.as_slice()) {
                    Ok(payload) => {
                        self.execute_takeover_barrier(
                            command,
                            &payload.session_id,
                            &payload.user_id,
                            false,
                        )
                        .await
                    }
                    Err(error) => self.failed(command, error.into()),
                }
            }
            "RequestStateResync" => {
                match RequestStateResyncCommand::decode(command.payload.as_slice()) {
                    Ok(payload) => {
                        if payload.session_id != command.session_id {
                            return self.failed(
                                command,
                                anyhow::anyhow!(
                                    "state resync payload session_id does not match envelope"
                                ),
                            );
                        }
                        if !matches!(payload.mode.as_str(), "FULL" | "REGION") {
                            return self.failed(
                                command,
                                anyhow::anyhow!("state resync mode must be FULL or REGION"),
                            );
                        }
                        if payload.mode == "REGION"
                            && (payload.root_ref.is_empty()
                                || payload.root_ref.chars().count() > 512)
                        {
                            return self.failed(
                                command,
                                anyhow::anyhow!("REGION state resync requires a bounded root_ref"),
                            );
                        }
                        let collected = if payload.mode == "REGION" {
                            self.state_collector
                                .resync_region(&command.session_id, &payload.root_ref)
                                .await
                        } else {
                            self.state_collector.resync_full(&command.session_id).await
                        };
                        let state = match collected {
                            Ok(state) => state,
                            Err(error) => return self.failed(command, error),
                        };
                        let sequence = match self.next_event_sequence(&command.session_id).await {
                            Ok(sequence) => sequence,
                            Err(error) => return self.failed(command, error),
                        };
                        let mut state_payload = Self::browser_state_payload(state.clone());
                        state_payload.snapshot_kind = if payload.mode == "REGION" {
                            "REGION_RESYNC_FULL_FALLBACK".to_owned()
                        } else {
                            "FULL_RESYNC".to_owned()
                        };
                        state_payload.requested_root_ref = payload.root_ref;
                        let event =
                            Self::event(command, "BrowserStateUpdated", sequence, state_payload);
                        Self::state_result(
                            Self::ack(&command.message_id, true, "", ""),
                            event,
                            state,
                        )
                    }
                    Err(error) => self.failed(command, error.into()),
                }
            }
            "BusinessRecoveryAction" => {
                match BusinessRecoveryActionCommand::decode(command.payload.as_slice()) {
                    Ok(payload) => {
                        if payload.session_id != command.session_id
                            || !payload.action_id.starts_with("bra_")
                            || payload.action_id.chars().count() > 36
                            || !matches!(
                                payload.action.as_str(),
                                "RELOAD"
                                    | "NAVIGATE_HOME"
                                    | "REOPEN_KNOWN_ROUTE"
                                    | "REFRESH_SESSION"
                                    | "RESTART_EXTENSION"
                            )
                        {
                            return self.failed(
                                command,
                                anyhow::anyhow!("Business Recovery action payload is invalid"),
                            );
                        }
                        let execution = match payload.action.as_str() {
                            "RELOAD" => {
                                if !payload.target_url.is_empty()
                                    || !payload.extension_id.is_empty()
                                {
                                    return self.failed(
                                        command,
                                        anyhow::anyhow!("reload action must not include a target"),
                                    );
                                }
                                self.state_collector
                                    .reload(&command.session_id, false)
                                    .await
                            }
                            "REFRESH_SESSION" => {
                                if !payload.target_url.is_empty()
                                    || !payload.extension_id.is_empty()
                                {
                                    return self.failed(
                                        command,
                                        anyhow::anyhow!(
                                            "refresh session action must not include a target"
                                        ),
                                    );
                                }
                                self.state_collector.reload(&command.session_id, true).await
                            }
                            "RESTART_EXTENSION" => {
                                if !payload.target_url.is_empty()
                                    || payload.extension_id.len() != 32
                                    || !payload
                                        .extension_id
                                        .bytes()
                                        .all(|character| (b'a'..=b'p').contains(&character))
                                {
                                    return self.failed(
                                        command,
                                        anyhow::anyhow!(
                                            "restart extension action target is invalid"
                                        ),
                                    );
                                }
                                self.state_collector
                                    .restart_extension(&command.session_id, &payload.extension_id)
                                    .await
                            }
                            "NAVIGATE_HOME" | "REOPEN_KNOWN_ROUTE" => {
                                if !payload.extension_id.is_empty() {
                                    return self.failed(
                                        command,
                                        anyhow::anyhow!(
                                            "navigation recovery action must not include an extension"
                                        ),
                                    );
                                }
                                let target = match reqwest::Url::parse(&payload.target_url) {
                                    Ok(target)
                                        if matches!(target.scheme(), "http" | "https")
                                            && target.host_str().is_some()
                                            && target.username().is_empty()
                                            && target.password().is_none() =>
                                    {
                                        target
                                    }
                                    _ => {
                                        return self.failed(
                                            command,
                                            anyhow::anyhow!(
                                                "Business Recovery target URL is invalid"
                                            ),
                                        )
                                    }
                                };
                                self.state_collector
                                    .navigate(&command.session_id, target.as_str())
                                    .await
                            }
                            _ => unreachable!(),
                        };
                        if let Err(error) = execution {
                            return self.failed(command, error);
                        }
                        let state =
                            match self.state_collector.resync_full(&command.session_id).await {
                                Ok(state) if state.state_version > payload.base_state_version => {
                                    state
                                }
                                Ok(_) => {
                                    return self.failed(
                                        command,
                                        anyhow::anyhow!(
                                            "Business Recovery state did not advance after action"
                                        ),
                                    )
                                }
                                Err(error) => return self.failed(command, error),
                            };
                        let sequence = match self.next_event_sequence(&command.session_id).await {
                            Ok(sequence) => sequence,
                            Err(error) => return self.failed(command, error),
                        };
                        let mut state_payload = Self::browser_state_payload(state.clone());
                        state_payload.snapshot_kind = "BUSINESS_RECOVERY_ACTION".to_owned();
                        state_payload.requested_root_ref = payload.action_id;
                        let event =
                            Self::event(command, "BrowserStateUpdated", sequence, state_payload);
                        Self::state_result(
                            Self::ack(&command.message_id, true, "", ""),
                            event,
                            state,
                        )
                    }
                    Err(error) => self.failed(command, error.into()),
                }
            }
            "AgentNavigate" => match AgentNavigateCommand::decode(command.payload.as_slice()) {
                Ok(payload) => {
                    if payload.session_id != command.session_id {
                        return self.failed(
                            command,
                            anyhow::anyhow!(
                                "agent navigation payload session_id does not match envelope"
                            ),
                        );
                    }
                    if !payload.task_id.starts_with("agt_")
                        || payload.task_id.chars().count() > 128
                        || !payload.step_id.starts_with("step_")
                        || payload.step_id.chars().count() > 128
                        || payload.url.chars().count() > 8192
                    {
                        return self.failed(
                            command,
                            anyhow::anyhow!("agent navigation payload is invalid"),
                        );
                    }
                    let target = match reqwest::Url::parse(&payload.url) {
                        Ok(target)
                            if matches!(target.scheme(), "http" | "https")
                                && target.host_str().is_some()
                                && target.username().is_empty()
                                && target.password().is_none() =>
                        {
                            target
                        }
                        _ => {
                            return self.failed(
                                command,
                                anyhow::anyhow!("agent navigation URL is invalid"),
                            )
                        }
                    };
                    if self
                        .state_collector
                        .navigate(&command.session_id, target.as_str())
                        .await
                        .is_err()
                    {
                        return self
                            .agent_navigation_failed(command, &payload, "NAVIGATION_FAILED")
                            .await;
                    }
                    let state = match self.state_collector.resync_full(&command.session_id).await {
                        Ok(state) => state,
                        Err(_) => {
                            return self
                                .agent_navigation_failed(
                                    command,
                                    &payload,
                                    "NAVIGATION_STATE_UNAVAILABLE",
                                )
                                .await
                        }
                    };
                    if state.state_version <= payload.base_state_version {
                        return self
                            .agent_navigation_failed(
                                command,
                                &payload,
                                "NAVIGATION_STATE_NOT_ADVANCED",
                            )
                            .await;
                    }
                    let sequence = match self.next_event_sequence(&command.session_id).await {
                        Ok(sequence) => sequence,
                        Err(error) => return self.failed(command, error),
                    };
                    let mut state_payload = Self::browser_state_payload(state.clone());
                    state_payload.snapshot_kind = "AGENT_NAVIGATION".to_owned();
                    let event =
                        Self::event(command, "BrowserStateUpdated", sequence, state_payload);
                    Self::state_result(Self::ack(&command.message_id, true, "", ""), event, state)
                }
                Err(error) => self.failed(command, error.into()),
            },
            "AgentAction" => match AgentActionCommand::decode(command.payload.as_slice()) {
                Ok(payload) => {
                    if payload.session_id != command.session_id
                        || !payload.task_id.starts_with("agt_")
                        || payload.task_id.chars().count() > 128
                        || !payload.step_id.starts_with("step_")
                        || payload.step_id.chars().count() > 128
                        || !matches!(
                            payload.tool_id.as_str(),
                            "CLICK_TARGET" | "TYPE_TEXT" | "SCROLL" | "WAIT_FOR"
                        )
                    {
                        return self
                            .failed(command, anyhow::anyhow!("agent action payload is invalid"));
                    }
                    let action_started = Instant::now();
                    let action_result = self.execute_agent_action(&payload).await;
                    self.agent_action_latencies
                        .lock()
                        .await
                        .entry(command.session_id.clone())
                        .or_default()
                        .record(action_started.elapsed());
                    let state = match action_result {
                        Ok(state) => state,
                        Err(error) => {
                            tracing::warn!(
                                task_id = payload.task_id,
                                step_id = payload.step_id,
                                tool_id = payload.tool_id,
                                error = %error,
                                "Agent action failed"
                            );
                            let code = if payload.tool_id == "WAIT_FOR" {
                                "WAIT_CONDITION_FAILED"
                            } else if error.to_string().contains("target")
                                || error.to_string().contains("sensitive")
                            {
                                "ACTION_PRECONDITION_FAILED"
                            } else {
                                "ACTION_EXECUTION_FAILED"
                            };
                            return self.agent_action_failed(command, &payload, code).await;
                        }
                    };
                    let sequence = match self.next_event_sequence(&command.session_id).await {
                        Ok(sequence) => sequence,
                        Err(error) => return self.failed(command, error),
                    };
                    let mut state_payload = Self::browser_state_payload(state.clone());
                    state_payload.snapshot_kind = format!("AGENT_{}", payload.tool_id);
                    let event =
                        Self::event(command, "BrowserStateUpdated", sequence, state_payload);
                    Self::state_result(Self::ack(&command.message_id, true, "", ""), event, state)
                }
                Err(error) => self.failed(command, error.into()),
            },
            "CaptureObserverScreenshot" => {
                match CaptureObserverScreenshotCommand::decode(command.payload.as_slice()) {
                    Ok(payload) => {
                        if payload.session_id != command.session_id
                            || !payload.capture_id.starts_with("cap_")
                            || payload.capture_id.len() > 128
                            || !payload.capture_id.chars().all(|character| {
                                character.is_ascii_alphanumeric() || character == '_'
                            })
                        {
                            return self.failed(
                                command,
                                anyhow::anyhow!("Observer screenshot payload is invalid"),
                            );
                        }
                        if self
                            .active_human_takeovers
                            .lock()
                            .await
                            .contains(&command.session_id)
                        {
                            return Self::result(
                                Self::ack(
                                    &command.message_id,
                                    false,
                                    "HUMAN_TAKEOVER_ACTIVE",
                                    "Observer screenshot is disabled during HumanTakeover",
                                ),
                                None,
                            );
                        }
                        Self::result(Self::ack(&command.message_id, true, "", ""), None)
                    }
                    Err(error) => self.failed(command, error.into()),
                }
            }
            "ReleaseAllInput" => match ReleaseAllInputCommand::decode(command.payload.as_slice()) {
                Ok(payload) => {
                    if payload.session_id != command.session_id {
                        return self.failed(
                            command,
                            anyhow::anyhow!(
                                "release input payload session_id does not match envelope"
                            ),
                        );
                    }
                    let input = self
                        .input_brokers
                        .lock()
                        .await
                        .get(&command.session_id)
                        .cloned();
                    match input {
                        Some(input) => match input.release_all().await {
                            Ok(()) => {
                                Self::result(Self::ack(&command.message_id, true, "", ""), None)
                            }
                            Err(error) => self.failed(command, error),
                        },
                        None => Self::result(Self::ack(&command.message_id, true, "", ""), None),
                    }
                }
                Err(error) => self.failed(command, error.into()),
            },
            _ => Self::result(
                Self::ack(
                    &command.message_id,
                    false,
                    "UNSUPPORTED_COMMAND",
                    "command type is not supported by this node version",
                ),
                None,
            ),
        }
    }

    fn failed(&self, command: &CommandEnvelope, error: anyhow::Error) -> CommandResult {
        tracing::warn!(
            message_id = %command.message_id,
            session_id = %command.session_id,
            error = %error,
            "Node command failed"
        );
        Self::result(
            Self::ack(
                &command.message_id,
                false,
                "NODE_COMMAND_FAILED",
                "node command failed",
            ),
            None,
        )
    }

    async fn redeliver(&self, result: &PersistedCommandResult) -> CommandAck {
        let acknowledgement = Self::acknowledgement(result, true);
        if !result.event_delivered {
            let event = result
                .event_payload
                .as_deref()
                .map(EventEnvelope::decode)
                .transpose();
            let event = match event {
                Ok(event) => event,
                Err(error) => {
                    tracing::error!(
                        message_id = %acknowledgement.message_id,
                        error = %error,
                        "Persisted Node Event is corrupt"
                    );
                    return Self::ack(
                        &acknowledgement.message_id,
                        false,
                        "JOURNAL_CORRUPT",
                        "persisted node event is corrupt",
                    );
                }
            };
            if let Some(event) = event {
                if let Err(error) = self.publish_and_mark(event).await {
                    tracing::warn!(error = %error, "Failed to redeliver Node Event");
                    return Self::ack(
                        &acknowledgement.message_id,
                        false,
                        "EVENT_DELIVERY_FAILED",
                        "node event delivery failed",
                    );
                }
            } else if result.event_id.is_some() {
                return Self::ack(
                    &acknowledgement.message_id,
                    false,
                    "JOURNAL_CORRUPT",
                    "persisted node event payload is missing",
                );
            }
        }
        acknowledgement
    }

    async fn redeliver_pending_events(&self) {
        let pending = match self.journal.pending_events(100).await {
            Ok(pending) => pending,
            Err(error) => {
                tracing::error!(error = %error, "Failed to scan pending Node Events");
                return;
            }
        };
        for result in pending {
            let acknowledgement = self.redeliver(&result).await;
            if !acknowledgement.accepted {
                tracing::warn!(
                    message_id = %acknowledgement.message_id,
                    error_code = %acknowledgement.error_code,
                    "Pending Node Event remains undelivered"
                );
            }
        }
    }

    async fn begin_runtime_monitor(&self, command: &CommandEnvelope) {
        let monitor_token = command.message_id.clone();
        let mut runtime_monitors = self.runtime_monitors.lock().await;
        if runtime_monitors.contains_key(&command.session_id) {
            return;
        }
        runtime_monitors.insert(command.session_id.clone(), monitor_token.clone());
        drop(runtime_monitors);
        let service = self.clone();
        let session_id = command.session_id.clone();
        let tenant_id = command.tenant_id.clone();
        let coordinator_term = command.coordinator_term;
        // RuntimeStarted 提交时 Control Plane 将 Context Epoch 提升 1。
        let running_context_epoch = command.context_epoch.saturating_add(1);
        self.state_baselines.lock().await.remove(&session_id);
        self.resync_required.lock().await.remove(&session_id);
        tokio::spawn(async move {
            let mut interval = tokio::time::interval(Duration::from_secs(1));
            let mut probe_count = 0_u64;
            let mut degraded_probe_count = 0_u32;
            loop {
                interval.tick().await;
                if service.runtime_monitors.lock().await.get(&session_id) != Some(&monitor_token) {
                    return;
                }
                let health = match service.runtime_supervisor.health(&session_id).await {
                    Ok(health) => health,
                    Err(error) => {
                        tracing::warn!(
                            session_id,
                            error = %error,
                            "Runtime health probe failed"
                        );
                        continue;
                    }
                };
                if service.runtime_monitors.lock().await.get(&session_id) != Some(&monitor_token) {
                    return;
                }
                match health {
                    runtime_supervisor::RuntimeHealth::Healthy => {
                        degraded_probe_count = 0;
                        if let Some(input) =
                            service.input_brokers.lock().await.get(&session_id).cloned()
                        {
                            if let Err(error) = input.release_if_idle(Duration::from_secs(5)).await
                            {
                                tracing::warn!(
                                    session_id,
                                    error = %error,
                                    "Input release watchdog failed"
                                );
                            }
                        }
                        probe_count += 1;
                        // Safe Point must not wait for a deliberately long telemetry interval.
                        // Always publish the first complete observation after five healthy probes;
                        // production keeps the normal five-second cadence, while capacity/failure
                        // tests may use a longer steady-state interval without losing fail-closed
                        // startup coverage.
                        let regular_resource_report =
                            probe_count.is_multiple_of(service.resource_report_interval_probes);
                        if probe_count == 5 || regular_resource_report {
                            if let Err(error) = service
                                .report_session_resources(
                                    &tenant_id,
                                    &session_id,
                                    running_context_epoch,
                                    regular_resource_report,
                                    None,
                                )
                                .await
                            {
                                tracing::warn!(
                                    session_id,
                                    error = %error,
                                    "Session resource telemetry report failed"
                                );
                            }
                        }
                        let state_collection_interval = service
                            .state_collector
                            .collection_interval_probes(&session_id)
                            .await;
                        if probe_count.is_multiple_of(state_collection_interval) {
                            if service.resync_required.lock().await.contains(&session_id) {
                                continue;
                            }
                            match service
                                .state_collector
                                .collect_current_state(&session_id)
                                .await
                            {
                                Ok(state) => {
                                    let coordinator_term = match service
                                        .current_coordinator_term(&session_id, coordinator_term)
                                        .await
                                    {
                                        Ok(term) => term,
                                        Err(error) => {
                                            tracing::warn!(
                                                session_id,
                                                error = %error,
                                                "Coordinator Term lookup deferred"
                                            );
                                            continue;
                                        }
                                    };
                                    let previous = service
                                        .state_baselines
                                        .lock()
                                        .await
                                        .get(&session_id)
                                        .cloned();
                                    let result = match previous {
                                        None => {
                                            service
                                                .record_and_publish_state(
                                                    &tenant_id,
                                                    &session_id,
                                                    coordinator_term,
                                                    running_context_epoch,
                                                    state.clone(),
                                                )
                                                .await
                                        }
                                        Some(previous)
                                            if previous.content_hash == state.content_hash =>
                                        {
                                            continue;
                                        }
                                        Some(previous) => {
                                            let (diff_max_bytes, diff_max_changes) = service
                                                .state_collector
                                                .bounded_diff_limits(
                                                    &session_id,
                                                    service.diff_max_bytes,
                                                    service.diff_max_changes,
                                                )
                                                .await;
                                            match diff_states(
                                                &previous,
                                                &state,
                                                diff_max_bytes,
                                                diff_max_changes,
                                            ) {
                                                Ok(DiffOutcome::Diff(diff)) => {
                                                    service
                                                        .record_and_publish_state_diff(
                                                            &tenant_id,
                                                            &session_id,
                                                            coordinator_term,
                                                            running_context_epoch,
                                                            diff,
                                                        )
                                                        .await
                                                }
                                                Ok(DiffOutcome::Truncated(truncated)) => {
                                                    let result = service
                                                        .record_and_publish_diff_truncated(
                                                            &tenant_id,
                                                            &session_id,
                                                            coordinator_term,
                                                            running_context_epoch,
                                                            truncated,
                                                        )
                                                        .await;
                                                    if result.is_ok() {
                                                        service
                                                            .resync_required
                                                            .lock()
                                                            .await
                                                            .insert(session_id.clone());
                                                    }
                                                    result
                                                }
                                                Err(error) => Err(error),
                                            }
                                        }
                                    };
                                    if let Err(error) = result {
                                        tracing::warn!(
                                            session_id,
                                            error = %error,
                                            "Failed to queue Browser state change"
                                        );
                                    } else if !service
                                        .resync_required
                                        .lock()
                                        .await
                                        .contains(&session_id)
                                    {
                                        service
                                            .state_baselines
                                            .lock()
                                            .await
                                            .insert(session_id.clone(), state);
                                    }
                                }
                                Err(error) => {
                                    tracing::debug!(
                                        session_id,
                                        error = %error,
                                        "Browser state probe deferred"
                                    );
                                }
                            }
                        }
                    }
                    runtime_supervisor::RuntimeHealth::Degraded(reason) => {
                        tracing::warn!(session_id, reason = %reason, "Runtime health is degraded");
                        degraded_probe_count = degraded_probe_count.saturating_add(1);
                        if degraded_probe_count == 5 {
                            if let Err(error) = service
                                .report_session_resources(
                                    &tenant_id,
                                    &session_id,
                                    running_context_epoch,
                                    true,
                                    Some("BROWSER_UNRESPONSIVE"),
                                )
                                .await
                            {
                                tracing::warn!(
                                    session_id,
                                    error = %error,
                                    "Browser unresponsive danger report failed"
                                );
                            }
                        }
                    }
                    runtime_supervisor::RuntimeHealth::Crashed(reason) => {
                        tracing::warn!(
                            session_id,
                            reason = %reason,
                            "Runtime health monitor detected a Browser crash"
                        );
                        let mut runtime_monitors = service.runtime_monitors.lock().await;
                        if runtime_monitors.get(&session_id) == Some(&monitor_token) {
                            runtime_monitors.remove(&session_id);
                        }
                        drop(runtime_monitors);
                        service
                            .resource_cpu_baselines
                            .lock()
                            .await
                            .remove(&session_id);
                        service
                            .resource_oom_baselines
                            .lock()
                            .await
                            .remove(&session_id);
                        service
                            .resource_extension_cpu_baselines
                            .lock()
                            .await
                            .remove(&session_id);
                        service
                            .resource_media_cpu_baselines
                            .lock()
                            .await
                            .remove(&session_id);
                        service
                            .resource_browser_baselines
                            .lock()
                            .await
                            .remove(&session_id);
                        service
                            .resource_io_baselines
                            .lock()
                            .await
                            .remove(&session_id);
                        service
                            .agent_action_latencies
                            .lock()
                            .await
                            .remove(&session_id);
                        service
                            .pending_state_events
                            .lock()
                            .await
                            .remove(&session_id);
                        if let Some(input) = service.input_brokers.lock().await.remove(&session_id)
                        {
                            if let Err(error) = input.release_all().await {
                                tracing::debug!(
                                    session_id,
                                    error = %error,
                                    "Failed to release Browser input after crash"
                                );
                            }
                        }
                        service
                            .state_collector
                            .unregister_runtime(&session_id)
                            .await;
                        if let Err(error) = service.session_recorders.unregister(&session_id).await
                        {
                            tracing::error!(
                                session_id,
                                error = %error,
                                "Failed to finalize Session recording after Browser crash"
                            );
                        }
                        service.session_evidence.unregister(&session_id).await;
                        if let Some(gateway) = service.remote_desktop_gateway.as_ref() {
                            gateway.unregister_session(&session_id);
                        }
                        let result = match service
                            .current_coordinator_term(&session_id, coordinator_term)
                            .await
                        {
                            Ok(current_term) => {
                                service
                                    .record_and_publish_crash(
                                        &tenant_id,
                                        &session_id,
                                        current_term,
                                        running_context_epoch,
                                        &reason,
                                    )
                                    .await
                            }
                            Err(error) => Err(error),
                        };
                        if let Err(error) = result {
                            tracing::error!(
                                session_id,
                                error = %error,
                                "Failed to persist Browser crash event"
                            );
                        }
                        return;
                    }
                }
            }
        });
    }

    async fn record_and_publish_state(
        &self,
        tenant_id: &str,
        session_id: &str,
        coordinator_term: i64,
        context_epoch: i64,
        state: CurrentState,
    ) -> anyhow::Result<()> {
        let event_id = format!("evt_state_{}", uuid::Uuid::new_v4().simple());
        let message_id = format!("probe_{}", uuid::Uuid::new_v4().simple());
        let sequence = self.next_event_sequence(session_id).await?;
        let event = EventEnvelope {
            event_id: event_id.clone(),
            event_type: "BrowserStateUpdated".to_owned(),
            tenant_id: tenant_id.to_owned(),
            session_id: session_id.to_owned(),
            coordinator_term,
            context_epoch,
            operation_epoch: 0,
            sequence,
            payload: Self::browser_state_payload(state).encode_to_vec(),
        };
        let persisted = PersistedCommandResult {
            acknowledgement: PersistedAcknowledgement {
                message_id,
                accepted: true,
                error_code: String::new(),
                error_message: String::new(),
            },
            event_id: Some(event_id),
            event_payload: Some(event.encode_to_vec()),
            event_delivered: false,
        };
        self.journal.record_command_result(&persisted).await?;
        if let Err(error) = self.publish_and_mark(event).await {
            tracing::debug!(
                session_id,
                error = %error,
                "Browser state event queued for redelivery"
            );
        }
        Ok(())
    }

    async fn record_and_publish_state_diff(
        &self,
        tenant_id: &str,
        session_id: &str,
        coordinator_term: i64,
        context_epoch: i64,
        diff: StateDiff,
    ) -> anyhow::Result<()> {
        self.record_and_publish_background_event(
            tenant_id,
            session_id,
            coordinator_term,
            context_epoch,
            "BrowserStateDiff",
            Self::state_diff_payload(diff),
        )
        .await
    }

    async fn record_and_publish_diff_truncated(
        &self,
        tenant_id: &str,
        session_id: &str,
        coordinator_term: i64,
        context_epoch: i64,
        truncated: state_collector::DiffTruncated,
    ) -> anyhow::Result<()> {
        self.record_and_publish_background_event(
            tenant_id,
            session_id,
            coordinator_term,
            context_epoch,
            "DiffTruncated",
            DiffTruncatedEvent {
                session_id: truncated.session_id,
                reason: truncated.reason,
                last_good_state_version: truncated.last_good_state_version,
                current_state_version: truncated.current_state_version,
                affected_root: truncated.affected_root,
                estimated_targets: truncated.estimated_targets as u64,
            },
        )
        .await
    }

    async fn record_and_publish_background_event(
        &self,
        tenant_id: &str,
        session_id: &str,
        coordinator_term: i64,
        context_epoch: i64,
        event_type: &str,
        payload: impl Message,
    ) -> anyhow::Result<()> {
        let event_id = format!("evt_state_{}", uuid::Uuid::new_v4().simple());
        let message_id = format!("probe_{}", uuid::Uuid::new_v4().simple());
        let sequence = self.next_event_sequence(session_id).await?;
        let event = EventEnvelope {
            event_id: event_id.clone(),
            event_type: event_type.to_owned(),
            tenant_id: tenant_id.to_owned(),
            session_id: session_id.to_owned(),
            coordinator_term,
            context_epoch,
            operation_epoch: 0,
            sequence,
            payload: payload.encode_to_vec(),
        };
        let persisted = PersistedCommandResult {
            acknowledgement: PersistedAcknowledgement {
                message_id,
                accepted: true,
                error_code: String::new(),
                error_message: String::new(),
            },
            event_id: Some(event_id),
            event_payload: Some(event.encode_to_vec()),
            event_delivered: false,
        };
        self.increment_pending_state_event(session_id).await;
        if let Err(error) = self.journal.record_command_result(&persisted).await {
            self.decrement_pending_state_event(session_id).await;
            return Err(error);
        }
        if let Err(error) = self.publish_and_mark(event).await {
            tracing::debug!(
                session_id,
                error = %error,
                "Browser state event queued for redelivery"
            );
        }
        Ok(())
    }

    async fn record_and_publish_crash(
        &self,
        tenant_id: &str,
        session_id: &str,
        coordinator_term: i64,
        context_epoch: i64,
        reason: &str,
    ) -> anyhow::Result<()> {
        let event_id = format!("evt_crash_{}", uuid::Uuid::new_v4().simple());
        let message_id = format!("probe_{}", uuid::Uuid::new_v4().simple());
        let sequence = self.next_event_sequence(session_id).await?;
        let detected_at_ms = SystemTime::now().duration_since(UNIX_EPOCH)?.as_millis() as i64;
        let crash_type = if reason.starts_with("OOM:") {
            "OOM"
        } else {
            "BROWSER_PROCESS_EXIT"
        };
        let event = EventEnvelope {
            event_id: event_id.clone(),
            event_type: "BrowserCrashed".to_owned(),
            tenant_id: tenant_id.to_owned(),
            session_id: session_id.to_owned(),
            coordinator_term,
            context_epoch,
            operation_epoch: 0,
            sequence,
            payload: BrowserCrashEvent {
                session_id: session_id.to_owned(),
                crash_type: crash_type.to_owned(),
                reason: reason.chars().take(512).collect(),
                detected_at_ms,
            }
            .encode_to_vec(),
        };
        let persisted = PersistedCommandResult {
            acknowledgement: PersistedAcknowledgement {
                message_id,
                accepted: true,
                error_code: String::new(),
                error_message: String::new(),
            },
            event_id: Some(event_id),
            event_payload: Some(event.encode_to_vec()),
            event_delivered: false,
        };
        self.journal
            .record_crash_and_stop_runtime(session_id, &persisted)
            .await?;
        if let Err(error) = self.publish_and_mark(event).await {
            tracing::warn!(
                session_id,
                error = %error,
                "Browser crash event queued for redelivery"
            );
        }
        Ok(())
    }

    async fn dispatch_durable(
        &self,
        command: CommandEnvelope,
    ) -> Result<Response<DispatchResponse>, Status> {
        let previous = self
            .journal
            .command_result(&command.message_id)
            .await
            .map_err(|error| {
                tracing::error!(error = %error, "Failed to read Node Journal");
                Status::internal("node journal unavailable")
            })?;
        if let Some(previous) = previous {
            let duplicate = self.redeliver(&previous).await;
            return Ok(Response::new(DispatchResponse {
                acknowledgement: Some(duplicate),
            }));
        }

        match self
            .journal
            .validate_and_record_command_fence(
                &command.session_id,
                command.route_epoch,
                command.coordinator_shard_id,
                command.coordinator_term,
                self.require_route_epoch,
            )
            .await
            .map_err(|error| {
                tracing::error!(error = %error, "Failed to update Coordinator command fences");
                Status::internal("node journal unavailable")
            })? {
            CommandFenceDecision::Accepted | CommandFenceDecision::LegacyAccepted => {}
            CommandFenceDecision::RouteMissing => {
                return Ok(Response::new(DispatchResponse {
                    acknowledgement: Some(Self::ack(
                        &command.message_id,
                        false,
                        "ROUTE_EPOCH_REQUIRED",
                        "route epoch is required or a routed command was already accepted",
                    )),
                }));
            }
            CommandFenceDecision::RouteStale { .. } => {
                return Ok(Response::new(DispatchResponse {
                    acknowledgement: Some(Self::ack(
                        &command.message_id,
                        false,
                        "STALE_ROUTE_EPOCH",
                        "route epoch is older than the last accepted epoch",
                    )),
                }));
            }
            CommandFenceDecision::ShardMismatch { .. } => {
                return Ok(Response::new(DispatchResponse {
                    acknowledgement: Some(Self::ack(
                        &command.message_id,
                        false,
                        "WRONG_COORDINATOR_SHARD",
                        "coordinator shard does not match the accepted route",
                    )),
                }));
            }
            CommandFenceDecision::TermStale { .. } => {
                return Ok(Response::new(DispatchResponse {
                    acknowledgement: Some(Self::ack(
                        &command.message_id,
                        false,
                        "STALE_COORDINATOR_TERM",
                        "coordinator term is older than the last accepted term",
                    )),
                }));
            }
        }

        {
            let mut inflight = self.inflight.lock().await;
            if !inflight.insert(command.message_id.clone()) {
                return Ok(Response::new(DispatchResponse {
                    acknowledgement: Some(Self::ack(
                        &command.message_id,
                        false,
                        "COMMAND_IN_PROGRESS",
                        "command side effects are still being committed",
                    )),
                }));
            }
        }

        let message_id = command.message_id.clone();
        let result = self.execute_and_commit_dispatch(command).await;
        self.inflight.lock().await.remove(&message_id);
        result
    }

    async fn execute_and_commit_dispatch(
        &self,
        command: CommandEnvelope,
    ) -> Result<Response<DispatchResponse>, Status> {
        if command.command_type == "StopRuntime" {
            self.runtime_monitors
                .lock()
                .await
                .remove(&command.session_id);
            self.resource_cpu_baselines
                .lock()
                .await
                .remove(&command.session_id);
            self.resource_oom_baselines
                .lock()
                .await
                .remove(&command.session_id);
            self.resource_extension_cpu_baselines
                .lock()
                .await
                .remove(&command.session_id);
            self.resource_media_cpu_baselines
                .lock()
                .await
                .remove(&command.session_id);
            self.resource_browser_baselines
                .lock()
                .await
                .remove(&command.session_id);
            self.resource_io_baselines
                .lock()
                .await
                .remove(&command.session_id);
            self.agent_action_latencies
                .lock()
                .await
                .remove(&command.session_id);
            self.pending_state_events
                .lock()
                .await
                .remove(&command.session_id);
        }
        let result = self.execute(&command).await;
        let evidence_request = Self::evidence_request(&command, &result);
        if result.acknowledgement.accepted
            || result.acknowledgement.error_code == "UNSUPPORTED_COMMAND"
        {
            let persisted = Self::persisted(&result);
            let persistence = if let Some(lease) = result.runtime_lease.as_ref() {
                self.journal
                    .record_command_result_and_start_runtime(&persisted, lease)
                    .await
            } else if result.stop_runtime_lease {
                self.journal
                    .record_command_result_and_stop_runtime(&persisted, &command.session_id)
                    .await
            } else {
                self.journal.record_command_result(&persisted).await
            };
            if let Err(error) = persistence {
                if result.runtime_lease.is_some() {
                    if let Some(gateway) = self.remote_desktop_gateway.as_ref() {
                        gateway.unregister_session(&command.session_id);
                    }
                    self.input_brokers.lock().await.remove(&command.session_id);
                    self.state_collector
                        .unregister_runtime(&command.session_id)
                        .await;
                    let _ = self.session_recorders.unregister(&command.session_id).await;
                    self.session_evidence.unregister(&command.session_id).await;
                    let _ = self.runtime_supervisor.stop(&command.session_id).await;
                    self.success_trace_sampler.remove(&command.session_id).await;
                }
                tracing::error!(
                    message_id = %command.message_id,
                    error = %error,
                    "Failed to persist Node command result"
                );
                return Err(Status::internal("node journal unavailable"));
            }
        }

        if let Some(state) = result.state_baseline.as_ref() {
            self.state_baselines
                .lock()
                .await
                .insert(command.session_id.clone(), state.clone());
            self.resync_required
                .lock()
                .await
                .remove(&command.session_id);
        }

        let mut acknowledgement = result.acknowledgement;
        if acknowledgement.accepted {
            if let Some(event) = result.event {
                if let Err(error) = self.publish_and_mark(event).await {
                    tracing::warn!(
                        message_id = %command.message_id,
                        error = %error,
                        "Failed to deliver Node Event"
                    );
                    acknowledgement = Self::ack(
                        &command.message_id,
                        false,
                        "EVENT_DELIVERY_FAILED",
                        "node event delivery failed",
                    );
                }
            }
            if acknowledgement.accepted {
                if let Some(request) = evidence_request {
                    if let Err(error) = self.capture_and_publish_evidence(&command, request).await {
                        tracing::warn!(
                            message_id = %command.message_id,
                            session_id = %command.session_id,
                            error = %error,
                            "Session evidence event queued or rejected"
                        );
                    }
                }
            }
            if command.command_type == "StartRuntime" {
                self.begin_runtime_monitor(&command).await;
            }
        }
        if acknowledgement.accepted {
            let (sampled, percentage) = self
                .success_trace_sampler
                .should_sample(&command.session_id, &command.message_id)
                .await;
            if sampled {
                tracing::info!(
                    target: "browsercloud.success_trace",
                    trace_id = %command.message_id,
                    session_id = %command.session_id,
                    command_type = %command.command_type,
                    success_trace_sample_percent = percentage,
                    "Browser Node command completed"
                );
            }
            if command.command_type == "StopRuntime" {
                self.success_trace_sampler.remove(&command.session_id).await;
            }
        }
        Ok(Response::new(DispatchResponse {
            acknowledgement: Some(acknowledgement),
        }))
    }

    async fn reconcile_runtime_leases(&self) {
        let leases = match self.journal.active_runtime_leases().await {
            Ok(leases) => leases,
            Err(error) => {
                tracing::error!(error = %error, "Failed to read Runtime leases for reconciliation");
                return;
            }
        };
        for lease in leases {
            self.runtime_supervisor
                .ensure_generation_at_least(&lease.session_id, lease.browser_generation)
                .await;
            match self
                .runtime_supervisor
                .terminate_orphan(lease.pid, lease.process_started_at)
                .await
            {
                Ok(true) => tracing::warn!(
                    session_id = %lease.session_id,
                    pid = lease.pid,
                    "Terminated orphan Runtime during Node reconciliation"
                ),
                Ok(false) => {}
                Err(error) => tracing::warn!(
                    session_id = %lease.session_id,
                    pid = lease.pid,
                    error = %error,
                    "Skipped orphan Runtime termination"
                ),
            }
            let reason = format!(
                "Node restarted while Runtime lease for pid {} was active",
                lease.pid
            );
            let result = match self
                .current_coordinator_term(&lease.session_id, lease.coordinator_term)
                .await
            {
                Ok(current_term) => {
                    self.record_and_publish_crash(
                        &lease.tenant_id,
                        &lease.session_id,
                        current_term,
                        lease.context_epoch,
                        &reason,
                    )
                    .await
                }
                Err(error) => Err(error),
            };
            if let Err(error) = result {
                tracing::error!(
                    session_id = %lease.session_id,
                    error = %error,
                    "Runtime lease reconciliation failed"
                );
            }
        }
    }
}

#[tonic::async_trait]
impl NodeControlServiceRpc for NodeControlService {
    async fn ping(&self, _request: Request<PingRequest>) -> Result<Response<PingResponse>, Status> {
        let unix_time_ms = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map_err(|_| Status::internal("system clock before unix epoch"))?
            .as_millis() as i64;
        Ok(Response::new(PingResponse {
            node_id: self.node_id.clone(),
            service_version: env!("CARGO_PKG_VERSION").to_owned(),
            unix_time_ms,
        }))
    }

    async fn dispatch(
        &self,
        request: Request<DispatchRequest>,
    ) -> Result<Response<DispatchResponse>, Status> {
        let command = request
            .into_inner()
            .command
            .ok_or_else(|| Status::invalid_argument("command is required"))?;
        if !Self::is_valid_session_id(&command.session_id) {
            return Err(Status::invalid_argument("invalid session_id"));
        }
        if command.message_id.is_empty() || command.idempotency_key.is_empty() {
            return Err(Status::invalid_argument(
                "message_id and idempotency_key are required",
            ));
        }

        let service = self.clone();
        tokio::spawn(async move { service.dispatch_durable(command).await })
            .await
            .map_err(|error| {
                tracing::error!(error = %error, "Node command execution task failed");
                Status::internal("node command execution failed")
            })?
    }

    async fn upload_profile_import(
        &self,
        request: Request<tonic::Streaming<UploadProfileImportRequest>>,
    ) -> Result<Response<UploadProfileImportResponse>, Status> {
        const MAX_IMPORT_BYTES: u64 = 256 * 1024 * 1024;
        const MAX_CHUNK_BYTES: usize = 1024 * 1024;

        let Some(storage_helper) = self.storage_helper.as_ref() else {
            return Err(Status::failed_precondition(
                "Profile Import requires the Storage Helper",
            ));
        };
        let mut stream = request.into_inner();
        let first = stream
            .message()
            .await?
            .ok_or_else(|| Status::invalid_argument("Profile Import stream is empty"))?;
        validate_profile_import_chunk(&first, MAX_IMPORT_BYTES, MAX_CHUNK_BYTES)
            .map_err(Status::invalid_argument)?;
        if first.offset != 0 {
            return Err(Status::invalid_argument(
                "Profile Import must start at offset zero",
            ));
        }
        let inflight_import_id = first.import_id.clone();

        {
            let mut inflight = self.inflight_profile_imports.lock().await;
            if !inflight.insert(inflight_import_id.clone()) {
                return Err(Status::already_exists(
                    "Profile Import is already being uploaded",
                ));
            }
        }

        let result = async {
            tokio::fs::create_dir_all(&self.profile_import_staging_root)
                .await
                .map_err(|error| {
                    tracing::error!(error = %error, "Profile Import staging root is unavailable");
                    Status::internal("Profile Import staging is unavailable")
                })?;
            let path = self
                .profile_import_staging_root
                .join(format!("{}.tar.zst", first.import_id));
            let mut file = tokio::fs::OpenOptions::new()
                .write(true)
                .create(true)
                .truncate(true)
                .open(&path)
                .await
                .map_err(|error| {
                    tracing::error!(error = %error, "Cannot open Profile Import staging file");
                    Status::internal("Profile Import staging is unavailable")
                })?;
            let mut hasher = Sha256::new();
            let mut written = 0_u64;
            let expected = ProfileImportMetadata::from(&first);
            let mut chunk = Some(first);
            while let Some(current) = chunk {
                validate_profile_import_chunk(&current, MAX_IMPORT_BYTES, MAX_CHUNK_BYTES)
                    .map_err(Status::invalid_argument)?;
                if !expected.matches(&current) || current.offset != written {
                    return Err(Status::invalid_argument(
                        "Profile Import chunk metadata or offset changed",
                    ));
                }
                written = written
                    .checked_add(current.data.len() as u64)
                    .ok_or_else(|| Status::invalid_argument("Profile Import size overflow"))?;
                if written > expected.archive_size_bytes {
                    return Err(Status::invalid_argument(
                        "Profile Import exceeds the declared size",
                    ));
                }
                file.write_all(&current.data).await.map_err(|error| {
                    tracing::error!(error = %error, "Cannot write Profile Import staging file");
                    Status::internal("Profile Import staging write failed")
                })?;
                hasher.update(&current.data);
                chunk = stream.message().await?;
            }
            file.sync_all().await.map_err(|error| {
                tracing::error!(error = %error, "Cannot sync Profile Import staging file");
                Status::internal("Profile Import staging sync failed")
            })?;
            drop(file);
            let observed_sha256 = format!("{:x}", hasher.finalize());
            if written != expected.archive_size_bytes
                || observed_sha256 != expected.archive_sha256.to_ascii_lowercase()
            {
                let _ = tokio::fs::remove_file(&path).await;
                return Err(Status::invalid_argument(
                    "Profile Import size or SHA-256 does not match",
                ));
            }
            let checkpoint = storage_helper
                .import_checkpoint(
                    &expected.tenant_id,
                    &expected.profile_id,
                    &expected.import_id,
                    &expected.checkpoint_id,
                    &expected.runtime_build_id,
                    &observed_sha256,
                    written,
                )
                .await
                .map_err(|error| {
                    tracing::warn!(
                        import_id = %expected.import_id,
                        error = %error,
                        "Storage Helper rejected Profile Import"
                    );
                    Status::failed_precondition("Profile Import validation or commit failed")
                })?;
            Ok(UploadProfileImportResponse {
                import_id: expected.import_id,
                node_id: self.node_id.clone(),
                profile_id: expected.profile_id,
                checkpoint_id: checkpoint.checkpoint_id,
                checkpoint_epoch: checkpoint.checkpoint_epoch,
                profile_write_epoch: checkpoint.profile_write_epoch,
                core_size_bytes: checkpoint.core_size_bytes,
                checkpoint_file_count: checkpoint.checkpoint_file_count,
                archive_sha256: observed_sha256,
                archive_size_bytes: written,
            })
        }
        .await;
        self.inflight_profile_imports
            .lock()
            .await
            .remove(&inflight_import_id);
        result.map(Response::new)
    }

    async fn presign_evidence_download(
        &self,
        request: Request<PresignEvidenceDownloadRequest>,
    ) -> Result<Response<PresignEvidenceDownloadResponse>, Status> {
        let request = request.into_inner();
        let valid_identifier = |value: &str, prefix: Option<&str>| {
            !value.is_empty()
                && value.len() <= 128
                && prefix
                    .map(|expected| value.starts_with(expected))
                    .unwrap_or(true)
                && value.chars().all(|character| {
                    character.is_ascii_alphanumeric() || matches!(character, '_' | '-')
                })
        };
        if !valid_identifier(&request.grant_id, Some("egr_"))
            || !valid_identifier(&request.tenant_id, None)
            || !valid_identifier(&request.profile_id, None)
            || !valid_identifier(&request.session_id, Some("ses_"))
            || !valid_identifier(&request.evidence_id, Some("evd_"))
            || request.content_sha256.len() != 64
            || !request
                .content_sha256
                .chars()
                .all(|character| character.is_ascii_hexdigit())
            || request.content_bytes == 0
            || request.content_bytes > 8 * 1024 * 1024
            || !(30..=120).contains(&request.expires_in_seconds)
        {
            return Err(Status::invalid_argument(
                "Evidence access request is invalid",
            ));
        }
        let storage_helper = self.storage_helper.as_ref().ok_or_else(|| {
            Status::failed_precondition("Evidence access requires the Storage Helper")
        })?;
        let access = storage_helper
            .sign_evidence_download(
                &request.tenant_id,
                &request.profile_id,
                &request.session_id,
                &request.evidence_id,
                &request.content_sha256.to_ascii_lowercase(),
                request.content_bytes,
                request.expires_in_seconds,
            )
            .await
            .map_err(|error| {
                tracing::warn!(
                    grant_id = %request.grant_id,
                    evidence_id = %request.evidence_id,
                    error = %error,
                    "Storage Helper rejected evidence access"
                );
                Status::failed_precondition("Evidence object is unavailable or invalid")
            })?;
        if access.evidence_id != request.evidence_id || access.download_url.len() > 8192 {
            return Err(Status::internal(
                "Storage Helper evidence access acknowledgement mismatch",
            ));
        }
        Ok(Response::new(PresignEvidenceDownloadResponse {
            grant_id: request.grant_id,
            node_id: self.node_id.clone(),
            evidence_id: access.evidence_id,
            download_url: access.download_url,
            expires_at_ms: access.expires_at_ms as i64,
        }))
    }
}

#[derive(Clone)]
struct ProfileImportMetadata {
    import_id: String,
    tenant_id: String,
    profile_id: String,
    checkpoint_id: String,
    runtime_build_id: String,
    archive_sha256: String,
    archive_size_bytes: u64,
}

impl From<&UploadProfileImportRequest> for ProfileImportMetadata {
    fn from(value: &UploadProfileImportRequest) -> Self {
        Self {
            import_id: value.import_id.clone(),
            tenant_id: value.tenant_id.clone(),
            profile_id: value.profile_id.clone(),
            checkpoint_id: value.checkpoint_id.clone(),
            runtime_build_id: value.runtime_build_id.clone(),
            archive_sha256: value.archive_sha256.clone(),
            archive_size_bytes: value.archive_size_bytes,
        }
    }
}

impl ProfileImportMetadata {
    fn matches(&self, value: &UploadProfileImportRequest) -> bool {
        self.import_id == value.import_id
            && self.tenant_id == value.tenant_id
            && self.profile_id == value.profile_id
            && self.checkpoint_id == value.checkpoint_id
            && self.runtime_build_id == value.runtime_build_id
            && self
                .archive_sha256
                .eq_ignore_ascii_case(&value.archive_sha256)
            && self.archive_size_bytes == value.archive_size_bytes
    }
}

fn validate_profile_import_chunk(
    value: &UploadProfileImportRequest,
    max_import_bytes: u64,
    max_chunk_bytes: usize,
) -> Result<(), &'static str> {
    let valid_identifier = |candidate: &str, prefix: Option<&str>| {
        !candidate.is_empty()
            && candidate.len() <= 128
            && prefix
                .map(|expected| candidate.starts_with(expected))
                .unwrap_or(true)
            && candidate.chars().all(|character| {
                character.is_ascii_alphanumeric() || matches!(character, '_' | '-')
            })
    };
    if !valid_identifier(&value.import_id, Some("pim_"))
        || !valid_identifier(&value.tenant_id, None)
        || !valid_identifier(&value.profile_id, None)
        || !valid_identifier(&value.checkpoint_id, Some("chk_"))
        || !valid_identifier(&value.runtime_build_id, None)
        || value.archive_sha256.len() != 64
        || !value
            .archive_sha256
            .chars()
            .all(|character| character.is_ascii_hexdigit())
        || value.archive_size_bytes == 0
        || value.archive_size_bytes > max_import_bytes
        || value.data.is_empty()
        || value.data.len() > max_chunk_bytes
    {
        return Err("Profile Import chunk is invalid or exceeds its bound");
    }
    Ok(())
}

#[tokio::main]
async fn main() -> Result<()> {
    rustls::crypto::ring::default_provider()
        .install_default()
        .map_err(|_| anyhow::anyhow!("failed to install the process rustls crypto provider"))?;
    tracing_subscriber::fmt()
        .with_env_filter(
            EnvFilter::try_from_default_env().unwrap_or_else(|_| EnvFilter::new("info")),
        )
        .init();
    nix::sys::stat::umask(nix::sys::stat::Mode::from_bits_truncate(0o007));

    let chromium_binary = std::env::var("CHROMIUM_PATH").unwrap_or_else(|_| "chromium".to_owned());
    let node_port = std::env::var("NODE_AGENT_PORT")
        .unwrap_or_else(|_| "9090".to_owned())
        .parse::<u16>()?;
    let node_id = std::env::var("NODE_ID").unwrap_or_else(|_| {
        let hostname = std::env::var("HOSTNAME").unwrap_or_else(|_| "local".to_owned());
        let normalized = hostname
            .chars()
            .map(|character| {
                if character.is_ascii_alphanumeric() || matches!(character, '_' | '-') {
                    character
                } else {
                    '_'
                }
            })
            .collect::<String>();
        format!("node_{normalized}")
    });
    anyhow::ensure!(
        node_id.starts_with("node_")
            && node_id.len() <= 128
            && node_id.chars().all(
                |character| character.is_ascii_alphanumeric() || matches!(character, '_' | '-')
            ),
        "NODE_ID must use the node_ prefix and contain only letters, numbers, '_' or '-'"
    );
    let control_plane_event_target =
        std::env::var("CONTROL_PLANE_EVENT_TARGET").unwrap_or_else(|_| "127.0.0.1:9091".to_owned());
    let runtime_root = std::env::var("RUNTIME_ROOT")
        .map(PathBuf::from)
        .unwrap_or_else(|_| Path::new("/tmp/browsercloud-runtime").to_path_buf());
    let environment = std::env::var("APP_ENVIRONMENT").unwrap_or_else(|_| "local".to_owned());
    let require_route_epoch = std::env::var("NODE_REQUIRE_ROUTE_EPOCH")
        .map(|value| value.eq_ignore_ascii_case("true"))
        .unwrap_or(false);
    tokio::fs::create_dir_all(&runtime_root).await?;
    let journal_path = std::env::var("NODE_JOURNAL_PATH")
        .map(PathBuf::from)
        .unwrap_or_else(|_| runtime_root.join("node-journal.sqlite3"));
    let journal = Arc::new(SqliteNodeJournal::open(journal_path).await?);
    let profile_storage_root = std::env::var("PROFILE_STORAGE_ROOT")
        .map(PathBuf::from)
        .unwrap_or_else(|_| runtime_root.join("profile-storage"));
    let profile_import_staging_root = profile_storage_root.join(".imports");
    tokio::fs::create_dir_all(&profile_import_staging_root).await?;
    let extension_root = std::env::var("NODE_EXTENSION_ROOT")
        .ok()
        .map(|value| value.trim().to_owned())
        .filter(|value| !value.is_empty())
        .map(PathBuf::from);
    if let Some(root) = extension_root.as_ref() {
        anyhow::ensure!(
            root.is_absolute() && root.is_dir(),
            "NODE_EXTENSION_ROOT must be an existing absolute directory"
        );
    }
    let storage_helper_socket = std::env::var("STORAGE_HELPER_SOCKET")
        .unwrap_or_default()
        .trim()
        .to_owned();
    let input_brokers = Arc::new(Mutex::new(HashMap::new()));
    let diff_max_bytes = std::env::var("STATE_DIFF_MAX_BYTES")
        .unwrap_or_else(|_| "60000".to_owned())
        .parse::<usize>()?;
    let diff_max_changes = std::env::var("STATE_DIFF_MAX_CHANGES")
        .unwrap_or_else(|_| "200".to_owned())
        .parse::<usize>()?;
    anyhow::ensure!(
        (1024..=60_000).contains(&diff_max_bytes) && diff_max_changes > 0,
        "State Diff limits are invalid"
    );
    let resource_report_interval_probes = std::env::var("SESSION_RESOURCE_REPORT_INTERVAL_SECONDS")
        .unwrap_or_else(|_| "5".to_owned())
        .parse::<u64>()?;
    anyhow::ensure!(
        (5..=3600).contains(&resource_report_interval_probes),
        "SESSION_RESOURCE_REPORT_INTERVAL_SECONDS must be between 5 and 3600"
    );
    let mut runtime_supervisor = ChromiumRuntimeSupervisor::new(PathBuf::from(chromium_binary));
    let runtime_cgroup_root = std::env::var("RUNTIME_CGROUP_ROOT")
        .unwrap_or_default()
        .trim()
        .to_owned();
    let cgroup_enabled = !runtime_cgroup_root.is_empty();
    let cgroup_io_enabled = cgroup_enabled
        && std::fs::read_to_string(Path::new(&runtime_cgroup_root).join("cgroup.controllers"))
            .map(|controllers| controllers.split_whitespace().any(|value| value == "io"))
            .unwrap_or(false);
    if environment.eq_ignore_ascii_case("production") {
        anyhow::ensure!(
            !runtime_cgroup_root.is_empty(),
            "RUNTIME_CGROUP_ROOT is required in production"
        );
    }
    if !runtime_cgroup_root.is_empty() {
        runtime_supervisor = runtime_supervisor.with_cgroup_v2(CgroupV2Config {
            root: PathBuf::from(runtime_cgroup_root),
        })?;
    }
    let desktop_config = match (std::env::var("XVFB_PATH"), std::env::var("X11VNC_PATH")) {
        (Ok(xvfb_binary), Ok(x11vnc_binary)) => Some(DesktopRuntimeConfig {
            xvfb_binary: PathBuf::from(xvfb_binary),
            x11vnc_binary: PathBuf::from(x11vnc_binary),
            width: std::env::var("REMOTE_DESKTOP_WIDTH")
                .unwrap_or_else(|_| "1440".to_owned())
                .parse()?,
            height: std::env::var("REMOTE_DESKTOP_HEIGHT")
                .unwrap_or_else(|_| "900".to_owned())
                .parse()?,
            depth: std::env::var("REMOTE_DESKTOP_DEPTH")
                .unwrap_or_else(|_| "24".to_owned())
                .parse()?,
        }),
        (Err(_), Err(_)) => None,
        _ => anyhow::bail!("XVFB_PATH and X11VNC_PATH must be configured together"),
    };
    let desktop_enabled = desktop_config.is_some();
    if let Some(config) = desktop_config {
        runtime_supervisor = runtime_supervisor.with_desktop(config)?;
    }
    let runtime_supervisor = Arc::new(runtime_supervisor);
    let (desktop_disconnect_sender, mut desktop_disconnect_receiver) =
        mpsc::channel::<RemoteDesktopTicketClaims>(128);
    let disconnect_handler = Arc::new(DesktopDisconnectPublisher {
        sender: desktop_disconnect_sender,
    });
    let grpc_tls = GrpcTlsMaterial::from_environment(&environment)?.map(Arc::new);
    let capacity_reporter = NodeCapacityReporter::from_environment(
        &environment,
        node_id.clone(),
        node_port,
        control_plane_event_target.clone(),
        grpc_tls.clone(),
        desktop_enabled,
        RuntimeCgroupCapabilities {
            enforcement: cgroup_enabled,
            io_telemetry: cgroup_io_enabled,
        },
    )?;
    let allow_direct_network = std::env::var("ALLOW_DIRECT_NETWORK")
        .map(|value| value.eq_ignore_ascii_case("true"))
        .unwrap_or(false);
    if environment.eq_ignore_ascii_case("production") {
        anyhow::ensure!(
            !storage_helper_socket.is_empty(),
            "STORAGE_HELPER_SOCKET is required in production"
        );
    }
    let storage_helper = if storage_helper_socket.is_empty() {
        None
    } else {
        let timeout = Duration::from_millis(
            std::env::var("STORAGE_HELPER_TIMEOUT_MS")
                .unwrap_or_else(|_| "30000".to_owned())
                .parse()?,
        );
        let client = StorageHelperClient::new(
            PathBuf::from(storage_helper_socket),
            timeout,
            profile_storage_root,
        )?;
        let startup_timeout = Duration::from_millis(
            std::env::var("STORAGE_HELPER_STARTUP_TIMEOUT_MS")
                .unwrap_or_else(|_| {
                    if environment.eq_ignore_ascii_case("production") {
                        "30000".to_owned()
                    } else {
                        "5000".to_owned()
                    }
                })
                .parse()?,
        );
        let startup_deadline = tokio::time::Instant::now() + startup_timeout;
        loop {
            match client.ping().await {
                Ok(()) => break,
                Err(error) if tokio::time::Instant::now() < startup_deadline => {
                    tracing::warn!(error = %error, "waiting for storage helper");
                    tokio::time::sleep(Duration::from_millis(100)).await;
                }
                Err(error) => {
                    return Err(error).context("storage helper startup check failed");
                }
            }
        }
        Some(Arc::new(client))
    };
    let network_helper_socket = std::env::var("NETWORK_HELPER_SOCKET")
        .unwrap_or_default()
        .trim()
        .to_owned();
    if environment.eq_ignore_ascii_case("production") {
        anyhow::ensure!(
            !allow_direct_network,
            "ALLOW_DIRECT_NETWORK cannot be enabled in production"
        );
        anyhow::ensure!(
            !network_helper_socket.is_empty(),
            "NETWORK_HELPER_SOCKET is required in production"
        );
    }
    let network_helper = if network_helper_socket.is_empty() {
        None
    } else {
        let timeout = Duration::from_millis(
            std::env::var("NETWORK_HELPER_TIMEOUT_MS")
                .unwrap_or_else(|_| "5000".to_owned())
                .parse()?,
        );
        let client = NetworkHelperClient::new(PathBuf::from(network_helper_socket), timeout)?;
        let startup_timeout = Duration::from_millis(
            std::env::var("NETWORK_HELPER_STARTUP_TIMEOUT_MS")
                .unwrap_or_else(|_| {
                    if environment.eq_ignore_ascii_case("production") {
                        "30000".to_owned()
                    } else {
                        "5000".to_owned()
                    }
                })
                .parse()?,
        );
        let startup_deadline = tokio::time::Instant::now() + startup_timeout;
        loop {
            match client.ping().await {
                Ok(()) => break,
                Err(error) if tokio::time::Instant::now() < startup_deadline => {
                    tracing::warn!(error = %error, "waiting for network helper");
                    tokio::time::sleep(Duration::from_millis(100)).await;
                }
                Err(error) => {
                    return Err(error).context("network helper startup check failed");
                }
            }
        }
        Some(Arc::new(client))
    };
    let local_ticket_secret = "browsercloud-local-remote-desktop-ticket-secret-v1";
    let ticket_secret = std::env::var("REMOTE_DESKTOP_TICKET_SECRET")
        .unwrap_or_else(|_| local_ticket_secret.to_owned());
    let allowed_origins = std::env::var("REMOTE_DESKTOP_ALLOWED_ORIGINS")
        .unwrap_or_default()
        .split(',')
        .map(str::trim)
        .filter(|origin| !origin.is_empty())
        .map(ToOwned::to_owned)
        .collect::<Vec<_>>();
    if environment.eq_ignore_ascii_case("production") {
        anyhow::ensure!(
            ticket_secret != local_ticket_secret,
            "REMOTE_DESKTOP_TICKET_SECRET must be overridden in production"
        );
        anyhow::ensure!(
            !allowed_origins.is_empty(),
            "REMOTE_DESKTOP_ALLOWED_ORIGINS is required in production"
        );
    }
    let remote_desktop_disconnect_grace = Duration::from_millis(
        std::env::var("REMOTE_DESKTOP_DISCONNECT_GRACE_MS")
            .unwrap_or_else(|_| "2000".to_owned())
            .parse()?,
    );
    let remote_desktop_heartbeat_interval = Duration::from_millis(
        std::env::var("REMOTE_DESKTOP_HEARTBEAT_INTERVAL_MS")
            .unwrap_or_else(|_| "10000".to_owned())
            .parse()?,
    );
    let remote_desktop_client_liveness_timeout = Duration::from_millis(
        std::env::var("REMOTE_DESKTOP_CLIENT_LIVENESS_TIMEOUT_MS")
            .unwrap_or_else(|_| "30000".to_owned())
            .parse()?,
    );
    if environment.eq_ignore_ascii_case("production") {
        anyhow::ensure!(
            remote_desktop_disconnect_grace >= Duration::from_millis(500)
                && remote_desktop_disconnect_grace <= Duration::from_secs(10),
            "REMOTE_DESKTOP_DISCONNECT_GRACE_MS must be between 500 and 10000 in production"
        );
        anyhow::ensure!(
            remote_desktop_heartbeat_interval >= Duration::from_secs(1)
                && remote_desktop_heartbeat_interval <= Duration::from_secs(60),
            "REMOTE_DESKTOP_HEARTBEAT_INTERVAL_MS must be between 1000 and 60000 in production"
        );
        anyhow::ensure!(
            remote_desktop_client_liveness_timeout >= Duration::from_secs(5)
                && remote_desktop_client_liveness_timeout <= Duration::from_secs(120),
            "REMOTE_DESKTOP_CLIENT_LIVENESS_TIMEOUT_MS must be between 5000 and 120000 in production"
        );
    }
    let remote_desktop_gateway = RemoteDesktopGateway::new_with_timeouts(
        ticket_secret,
        allowed_origins,
        disconnect_handler,
        remote_desktop_disconnect_grace,
        remote_desktop_heartbeat_interval,
        remote_desktop_client_liveness_timeout,
    )?;
    let remote_desktop_port = std::env::var("REMOTE_DESKTOP_GATEWAY_PORT")
        .unwrap_or_else(|_| "6080".to_owned())
        .parse::<u16>()?;
    let remote_desktop_listener =
        tokio::net::TcpListener::bind(SocketAddr::from(([0, 0, 0, 0], remote_desktop_port)))
            .await?;
    let gateway_server = remote_desktop_gateway.clone();
    tokio::spawn(async move {
        if let Err(error) = gateway_server.serve(remote_desktop_listener).await {
            tracing::error!(error = %error, "Remote desktop gateway stopped");
        }
    });
    tokio::spawn(async move {
        loop {
            let delay = match capacity_reporter.report().await {
                Ok(()) => Duration::from_secs(15),
                Err(error) => {
                    tracing::warn!(error = %error, "Browser Node capacity heartbeat failed");
                    Duration::from_secs(1)
                }
            };
            tokio::time::sleep(delay).await;
        }
    });
    let service = NodeControlService {
        node_id,
        control_plane_event_target,
        grpc_tls: grpc_tls.clone(),
        runtime_supervisor: runtime_supervisor.clone(),
        storage_helper,
        profile_workspaces: Arc::new(Mutex::new(HashMap::new())),
        network_helper,
        allow_direct_network,
        extension_root,
        state_collector: Arc::new(CdpStateCollector::new()),
        state_baselines: Arc::new(Mutex::new(HashMap::new())),
        resync_required: Arc::new(Mutex::new(HashSet::new())),
        diff_max_bytes,
        diff_max_changes,
        input_brokers,
        active_human_takeovers: Arc::new(Mutex::new(HashSet::new())),
        journal,
        require_route_epoch,
        inflight: Arc::new(Mutex::new(HashSet::new())),
        event_delivery_locks: Arc::new(Mutex::new(HashMap::new())),
        runtime_monitors: Arc::new(Mutex::new(HashMap::new())),
        resource_cpu_baselines: Arc::new(Mutex::new(HashMap::new())),
        resource_oom_baselines: Arc::new(Mutex::new(HashMap::new())),
        resource_extension_cpu_baselines: Arc::new(Mutex::new(HashMap::new())),
        resource_media_cpu_baselines: Arc::new(Mutex::new(HashMap::new())),
        resource_browser_baselines: Arc::new(Mutex::new(HashMap::new())),
        resource_io_baselines: Arc::new(Mutex::new(HashMap::new())),
        agent_action_latencies: Arc::new(Mutex::new(HashMap::new())),
        pending_state_events: Arc::new(Mutex::new(HashMap::new())),
        success_trace_sampler: SuccessTraceSampler::default(),
        session_recorders: SessionRecorderRegistry::default(),
        session_evidence: SessionEvidenceRegistry::default(),
        profile_import_staging_root,
        inflight_profile_imports: Arc::new(Mutex::new(HashSet::new())),
        resource_report_interval_probes,
        next_cdp_port: Arc::new(Mutex::new(10_000)),
        next_display: Arc::new(Mutex::new(100)),
        remote_desktop_gateway: Some(remote_desktop_gateway),
        desktop_enabled,
    };
    let desktop_disconnect_service = service.clone();
    tokio::spawn(async move {
        while let Some(claims) = desktop_disconnect_receiver.recv().await {
            if let Err(error) = desktop_disconnect_service
                .handle_desktop_disconnect(claims.clone())
                .await
            {
                tracing::error!(
                    session_id = claims.session_id,
                    error = %error,
                    "Remote desktop disconnect barrier failed"
                );
            }
        }
    });
    let address = ([0, 0, 0, 0], node_port).into();

    tracing::info!(%address, "Browser Node Agent gRPC server started");
    let redelivery_service = service.clone();
    tokio::spawn(async move {
        let mut interval = tokio::time::interval(Duration::from_secs(1));
        redelivery_service
            .rebuild_pending_state_event_depths()
            .await;
        redelivery_service.redeliver_pending_events().await;
        redelivery_service.reconcile_runtime_leases().await;
        loop {
            interval.tick().await;
            redelivery_service.redeliver_pending_events().await;
        }
    });
    let mut grpc_server = tonic::transport::Server::builder();
    if let Some(material) = grpc_tls.as_ref() {
        grpc_server = grpc_server.tls_config(material.server_config())?;
    }
    grpc_server
        .add_service(NodeControlServiceServer::new(service.clone()))
        .serve_with_shutdown(address, shutdown_signal())
        .await?;
    // A process-level shutdown is not a Browser crash. Stop health monitors before terminating
    // their Runtime children so they cannot enqueue a second recovery while the Node is exiting.
    service.runtime_monitors.lock().await.clear();
    service.resource_cpu_baselines.lock().await.clear();
    service.resource_oom_baselines.lock().await.clear();
    service
        .resource_extension_cpu_baselines
        .lock()
        .await
        .clear();
    service.resource_media_cpu_baselines.lock().await.clear();
    service.resource_browser_baselines.lock().await.clear();
    service.resource_io_baselines.lock().await.clear();
    service.agent_action_latencies.lock().await.clear();
    service.pending_state_events.lock().await.clear();
    runtime_supervisor.stop_all().await;
    Ok(())
}

async fn shutdown_signal() {
    #[cfg(unix)]
    {
        let terminate = tokio::signal::unix::signal(tokio::signal::unix::SignalKind::terminate());
        match terminate {
            Ok(mut terminate) => {
                tokio::select! {
                    result = tokio::signal::ctrl_c() => {
                        if let Err(error) = result {
                            tracing::error!(%error, "Failed to install Ctrl-C handler");
                        }
                    }
                    _ = terminate.recv() => {}
                }
            }
            Err(error) => {
                tracing::error!(%error, "Failed to install SIGTERM handler");
                let _ = tokio::signal::ctrl_c().await;
            }
        }
    }
    #[cfg(not(unix))]
    if let Err(error) = tokio::signal::ctrl_c().await {
        tracing::error!(%error, "Failed to install shutdown signal handler");
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use node_contracts::proto::node_event_service_server::{
        NodeEventService, NodeEventServiceServer,
    };
    use node_contracts::proto::{
        PublishResponse, ReportCapacityRequest, ReportCapacityResponse,
        ReportSessionResourcesRequest, ReportSessionResourcesResponse, RuntimeStoppedEvent,
    };
    use std::time::{SystemTime, UNIX_EPOCH};
    use tokio::sync::mpsc;

    #[test]
    fn resource_danger_requires_an_oom_counter_increase() {
        assert_eq!(classify_resource_danger(None, Some(0), None), None);
        assert_eq!(classify_resource_danger(Some(4), Some(4), None), None);
        assert_eq!(
            classify_resource_danger(Some(4), Some(5), None),
            Some("OOM")
        );
    }

    #[test]
    fn resource_danger_detects_a_nearly_exhausted_profile_filesystem() {
        assert_eq!(
            classify_resource_danger(Some(0), Some(0), Some((32 * 1024 * 1024, 10_000_000_000))),
            Some("DISK_FULL")
        );
        assert_eq!(
            classify_resource_danger(Some(0), Some(0), Some((2_000_000_000, 10_000_000_000))),
            None
        );
    }

    struct CapturingEventService {
        sender: mpsc::Sender<EventEnvelope>,
    }

    #[tonic::async_trait]
    impl NodeEventService for CapturingEventService {
        async fn publish(
            &self,
            request: Request<PublishRequest>,
        ) -> Result<Response<PublishResponse>, Status> {
            let event = request
                .into_inner()
                .event
                .ok_or_else(|| Status::invalid_argument("event is required"))?;
            self.sender
                .send(event.clone())
                .await
                .map_err(|_| Status::unavailable("capture channel closed"))?;
            Ok(Response::new(PublishResponse {
                event_id: event.event_id,
                accepted: true,
                duplicate: false,
                error_code: String::new(),
                error_message: String::new(),
            }))
        }

        async fn report_capacity(
            &self,
            request: Request<ReportCapacityRequest>,
        ) -> Result<Response<ReportCapacityResponse>, Status> {
            let report = request.into_inner();
            Ok(Response::new(ReportCapacityResponse {
                node_id: report.node_id,
                accepted: true,
                admission_state: "OPEN".to_owned(),
                pressure_state: "NORMAL".to_owned(),
                error_code: String::new(),
                error_message: String::new(),
            }))
        }

        async fn report_session_resources(
            &self,
            request: Request<ReportSessionResourcesRequest>,
        ) -> Result<Response<ReportSessionResourcesResponse>, Status> {
            let report = request.into_inner();
            Ok(Response::new(ReportSessionResourcesResponse {
                session_id: report.session_id,
                accepted: true,
                error_code: String::new(),
                error_message: String::new(),
            }))
        }
    }

    fn temporary_path(name: &str) -> PathBuf {
        let nonce = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        std::env::temp_dir().join(format!("browsercloud-node-agent-{name}-{nonce}"))
    }

    #[test]
    fn agent_latency_window_reports_interval_maximum_once() {
        let mut window = AgentLatencyWindow::default();
        assert_eq!(window.maximum_ms, 0);
        window.record(Duration::from_millis(17));
        window.record(Duration::from_millis(42));
        assert_eq!(window.maximum(), Some(42));
    }

    #[test]
    fn derives_browser_io_rate_from_a_monotonic_cgroup_counter() {
        let now = Instant::now();
        assert_eq!(
            cumulative_rate_per_second(16_000, Some((1_000, now - Duration::from_secs(5))), now),
            Some(3_000)
        );
        assert_eq!(
            cumulative_rate_per_second(999, Some((1_000, now - Duration::from_secs(5))), now),
            None,
            "a reset Runtime generation must establish a new baseline"
        );
        assert_eq!(cumulative_rate_per_second(1_000, None, now), None);
    }

    #[tokio::test]
    async fn success_trace_sampler_applies_a_deterministic_session_policy() {
        let sampler = SuccessTraceSampler::default();
        assert_eq!(sampler.percentage("ses_trace").await, 100);
        assert_eq!(
            sampler.should_sample("ses_trace", "cmd_always").await,
            (true, 100)
        );

        assert_eq!(sampler.set("ses_trace", 10).await.unwrap(), 100);
        let first = sampler.should_sample("ses_trace", "cmd_stable").await;
        let repeated = sampler.should_sample("ses_trace", "cmd_stable").await;
        assert_eq!(first, repeated);
        assert_eq!(first.1, 10);

        let mut sampled = 0;
        for index in 0..1000 {
            if sampler
                .should_sample("ses_trace", &format!("cmd_{index}"))
                .await
                .0
            {
                sampled += 1;
            }
        }
        assert!(
            (70..=130).contains(&sampled),
            "10 percent policy sampled {sampled} of 1000 stable trace IDs"
        );
        assert!(sampler.set("ses_trace", 0).await.is_err());
        sampler.remove("ses_trace").await;
        assert_eq!(sampler.percentage("ses_trace").await, 100);
    }

    #[test]
    #[cfg(unix)]
    fn resolves_only_direct_non_symlink_extensions_from_the_trusted_root() {
        let root = temporary_path("trusted-extensions");
        let extension = root.join("accepted.extension");
        std::fs::create_dir_all(&extension).unwrap();
        std::fs::write(
            extension.join("manifest.json"),
            r#"{"manifest_version":3,"name":"Accepted","version":"1.0.0"}"#,
        )
        .unwrap();

        let resolved =
            resolve_trusted_extension_dirs(Some(&root), &["accepted.extension".to_owned()])
                .unwrap();
        assert_eq!(resolved, vec![std::fs::canonicalize(&extension).unwrap()]);
        assert!(resolve_trusted_extension_dirs(None, &["accepted.extension".to_owned()]).is_err());
        assert!(resolve_trusted_extension_dirs(Some(&root), &["..".to_owned()]).is_err());

        let linked = root.join("linked.extension");
        std::os::unix::fs::symlink(&extension, &linked).unwrap();
        assert!(
            resolve_trusted_extension_dirs(Some(&root), &["linked.extension".to_owned()]).is_err()
        );
        std::fs::remove_dir_all(root).unwrap();
    }

    #[tokio::test]
    async fn redelivers_persisted_event_after_journal_reopen() {
        let reservation = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let address = reservation.local_addr().unwrap();
        drop(reservation);
        let (sender, mut receiver) = mpsc::channel(4);
        let server = tokio::spawn(async move {
            tonic::transport::Server::builder()
                .add_service(NodeEventServiceServer::new(CapturingEventService {
                    sender,
                }))
                .serve(address)
                .await
                .unwrap();
        });

        let root = temporary_path("redelivery");
        let database = root.join("node-journal.sqlite3");
        let original = SqliteNodeJournal::open(&database).await.unwrap();
        let event = EventEnvelope {
            event_id: "evt_restart_1".into(),
            event_type: "BrowserStateDiff".into(),
            tenant_id: "tenant-test".into(),
            session_id: "ses_restart".into(),
            coordinator_term: 3,
            context_epoch: 4,
            operation_epoch: 5,
            sequence: 6,
            payload: RuntimeStoppedEvent {
                session_id: "ses_restart".into(),
                reason: "test".into(),
                exit_code: 0,
                profile_id: "profile-test".into(),
                checkpoint_id: "chk-test".into(),
                checkpoint_epoch: 1,
                profile_write_epoch: 1,
                core_size_bytes: 0,
                checkpoint_file_count: 0,
                restore_status: "EMPTY".into(),
            }
            .encode_to_vec(),
        };
        original
            .record_command_result(&PersistedCommandResult {
                acknowledgement: PersistedAcknowledgement {
                    message_id: "msg_restart_1".into(),
                    accepted: true,
                    error_code: String::new(),
                    error_message: String::new(),
                },
                event_id: Some(event.event_id.clone()),
                event_payload: Some(event.encode_to_vec()),
                event_delivered: false,
            })
            .await
            .unwrap();
        drop(original);

        let reopened = Arc::new(SqliteNodeJournal::open(&database).await.unwrap());
        let service = NodeControlService {
            node_id: "node-test".into(),
            control_plane_event_target: address.to_string(),
            grpc_tls: None,
            runtime_supervisor: Arc::new(ChromiumRuntimeSupervisor::new(PathBuf::from(
                "/missing/chromium",
            ))),
            storage_helper: None,
            profile_workspaces: Arc::new(Mutex::new(HashMap::new())),
            network_helper: None,
            allow_direct_network: true,
            extension_root: None,
            state_collector: Arc::new(CdpStateCollector::new()),
            state_baselines: Arc::new(Mutex::new(HashMap::new())),
            resync_required: Arc::new(Mutex::new(HashSet::new())),
            diff_max_bytes: 60_000,
            diff_max_changes: 200,
            input_brokers: Arc::new(Mutex::new(HashMap::new())),
            active_human_takeovers: Arc::new(Mutex::new(HashSet::new())),
            journal: reopened.clone(),
            require_route_epoch: false,
            inflight: Arc::new(Mutex::new(HashSet::new())),
            event_delivery_locks: Arc::new(Mutex::new(HashMap::new())),
            runtime_monitors: Arc::new(Mutex::new(HashMap::new())),
            resource_cpu_baselines: Arc::new(Mutex::new(HashMap::new())),
            resource_oom_baselines: Arc::new(Mutex::new(HashMap::new())),
            resource_extension_cpu_baselines: Arc::new(Mutex::new(HashMap::new())),
            resource_media_cpu_baselines: Arc::new(Mutex::new(HashMap::new())),
            resource_browser_baselines: Arc::new(Mutex::new(HashMap::new())),
            resource_io_baselines: Arc::new(Mutex::new(HashMap::new())),
            agent_action_latencies: Arc::new(Mutex::new(HashMap::new())),
            pending_state_events: Arc::new(Mutex::new(HashMap::new())),
            success_trace_sampler: SuccessTraceSampler::default(),
            session_recorders: SessionRecorderRegistry::default(),
            session_evidence: SessionEvidenceRegistry::default(),
            resource_report_interval_probes: 5,
            next_cdp_port: Arc::new(Mutex::new(10_000)),
            next_display: Arc::new(Mutex::new(100)),
            remote_desktop_gateway: None,
            desktop_enabled: false,
            profile_import_staging_root: root.join("profile-imports"),
            inflight_profile_imports: Arc::new(Mutex::new(HashSet::new())),
        };

        // Give the local gRPC listener one scheduler turn before redelivery.
        tokio::time::sleep(Duration::from_millis(50)).await;
        service.rebuild_pending_state_event_depths().await;
        assert_eq!(
            service
                .pending_state_events
                .lock()
                .await
                .get("ses_restart")
                .copied(),
            Some(1)
        );
        service.redeliver_pending_events().await;
        let received = tokio::time::timeout(Duration::from_secs(2), receiver.recv())
            .await
            .unwrap()
            .unwrap();
        assert_eq!(received.event_id, "evt_restart_1");
        assert!(reopened.pending_events(10).await.unwrap().is_empty());
        assert!(service.pending_state_events.lock().await.is_empty());

        server.abort();
        let _ = tokio::fs::remove_dir_all(root).await;
    }
}
