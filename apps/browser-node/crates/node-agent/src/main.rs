//! Browser Node Agent 入口。

use anyhow::{Context, Result};
use helper_client::NetworkHelperClient;
use input_sandbox::{CdpDesktopInput, DesktopInput, InputKey};
use node_contracts::proto::node_control_service_server::{
    NodeControlService as NodeControlServiceRpc, NodeControlServiceServer,
};
use node_contracts::proto::node_event_service_client::NodeEventServiceClient;
use node_contracts::proto::{
    AgentActionCommand, AgentActionFailedEvent, AgentNavigateCommand, AgentNavigationFailedEvent,
    BeginHumanTakeoverCommand, BrowserCrashEvent, BrowserStateDiffEvent, BrowserStateEvent,
    CommandAck, CommandEnvelope, DiffTruncatedEvent, DispatchRequest, DispatchResponse,
    EndHumanTakeoverCommand, EventEnvelope, ExecuteInputCommand, HumanTakeoverEndedEvent,
    HumanTakeoverReadyEvent, InteractiveTargetState, PingRequest, PingResponse, PublishRequest,
    PublishResponse, ReleaseAllInputCommand, RequestStateResyncCommand, RuntimeStartedEvent,
    RuntimeStoppedEvent, StartRuntimeCommand, StopRuntimeCommand, TargetBounds,
};
use node_journal::{
    PersistedAcknowledgement, PersistedCommandResult, RuntimeLease, SqliteNodeJournal, TermDecision,
};
use prost::Message;
use remote_desktop_gateway::{DisconnectHandler, RemoteDesktopGateway, RemoteDesktopTicketClaims};
use runtime_supervisor::{
    ChromiumRuntimeSupervisor, DesktopRuntimeConfig, RuntimeSpec, RuntimeSupervisor,
};
use state_collector::{
    diff_states, BrowserStateCollector, CdpStateCollector, CurrentState, DiffOutcome, StateDiff,
    StateQuality,
};
use std::collections::{HashMap, HashSet};
use std::net::SocketAddr;
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::{Duration, SystemTime, UNIX_EPOCH};
use storage_helper::{LocalProfileStore, ProfileRestoreStatus, ProfileWorkspace};
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
    profile_store: Arc<LocalProfileStore>,
    profile_workspaces: Arc<Mutex<HashMap<String, ActiveProfileWorkspace>>>,
    network_helper: Option<Arc<NetworkHelperClient>>,
    allow_direct_network: bool,
    state_collector: Arc<CdpStateCollector>,
    state_baselines: Arc<Mutex<HashMap<String, CurrentState>>>,
    resync_required: Arc<Mutex<HashSet<String>>>,
    diff_max_bytes: usize,
    diff_max_changes: usize,
    input_brokers: Arc<Mutex<HashMap<String, Arc<CdpDesktopInput>>>>,
    journal: Arc<SqliteNodeJournal>,
    inflight: Arc<Mutex<HashSet<String>>>,
    monitored_sessions: Arc<Mutex<HashSet<String>>>,
    next_cdp_port: Arc<Mutex<u16>>,
    next_display: Arc<Mutex<u16>>,
    remote_desktop_gateway: Option<RemoteDesktopGateway>,
    desktop_enabled: bool,
}

#[derive(Clone)]
struct GrpcTlsMaterial {
    ca_certificate: Vec<u8>,
    certificate: Vec<u8>,
    private_key: Vec<u8>,
    control_plane_server_name: String,
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
    workspace: ProfileWorkspace,
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
    fn is_valid_session_id(session_id: &str) -> bool {
        session_id.starts_with("ses_")
            && session_id
                .chars()
                .all(|character| character.is_ascii_alphanumeric() || character == '_')
    }

    async fn allocate_cdp_port(&self) -> u16 {
        let mut port = self.next_cdp_port.lock().await;
        let allocated = *port;
        *port = if *port >= 19_999 { 10_000 } else { *port + 1 };
        allocated
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

    async fn release_start_resources(&self, session_id: &str, workspace: &ProfileWorkspace) {
        if let Some(network_helper) = self.network_helper.as_ref() {
            if let Err(error) = network_helper.release(session_id).await {
                tracing::warn!(session_id, error = %error, "Failed to release proxy binding");
            }
        }
        if let Err(error) = self.profile_store.release_writer(workspace).await {
            tracing::warn!(session_id, error = %error, "Failed to release profile writer");
        }
    }

    async fn next_event_sequence(&self, session_id: &str) -> anyhow::Result<i64> {
        self.journal.next_event_sequence(session_id).await
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
        Ok(client
            .publish(PublishRequest { event: Some(event) })
            .await?
            .into_inner())
    }

    async fn publish_and_mark(&self, event: EventEnvelope) -> anyhow::Result<()> {
        let event_id = event.event_id.clone();
        let acknowledgement = self.publish_event_receipt(event).await?;
        anyhow::ensure!(
            acknowledgement.accepted || acknowledgement.error_code == "STALE_HUMAN_TAKEOVER",
            "Control Plane rejected Node Event: {}",
            acknowledgement.error_code
        );
        self.journal.mark_event_delivered(&event_id).await
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
                        let workspace = match self
                            .profile_store
                            .acquire_workspace(
                                &command.tenant_id,
                                &payload.profile_id,
                                &command.session_id,
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
                                .bind_proxy(&payload.proxy_binding_id, &command.session_id)
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
                        let cdp_port = self.allocate_cdp_port().await;
                        let runtime_build_id = payload.runtime_build_id;
                        let (display, vnc_port) = if self.desktop_enabled {
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
                                profile_dir: workspace.core_dir.clone(),
                                cache_dir: workspace.ephemeral_dir.join("cache"),
                                proxy_server,
                                display,
                                cdp_port,
                                vnc_port,
                            })
                            .await
                        {
                            Ok(handle) => {
                                if let (Some(gateway), Some(endpoint)) = (
                                    self.remote_desktop_gateway.as_ref(),
                                    handle.vnc_endpoint.as_ref(),
                                ) {
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
                            let sequence = match self.next_event_sequence(&command.session_id).await
                            {
                                Ok(sequence) => sequence,
                                Err(error) => return self.failed(command, error),
                            };
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
                                    let checkpoint = match self
                                        .profile_store
                                        .checkpoint(
                                            &active_profile.workspace,
                                            &active_profile.runtime_build_id,
                                        )
                                        .await
                                    {
                                        Ok(checkpoint) => checkpoint,
                                        Err(error) => return self.failed(command, error),
                                    };
                                    if let Err(error) = self
                                        .profile_store
                                        .release_writer(&active_profile.workspace)
                                        .await
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
                                        ProfileRestoreStatus::Empty => "EMPTY",
                                        ProfileRestoreStatus::TechnicalReady => "TECHNICAL_READY",
                                    };
                                    (
                                        active_profile.workspace.profile_id,
                                        checkpoint.checkpoint_id,
                                        checkpoint.checkpoint_epoch,
                                        checkpoint.profile_write_epoch,
                                        checkpoint.core_size_bytes,
                                        checkpoint.files.len() as u64,
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
                    let state = match self.execute_agent_action(&payload).await {
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
        if !self
            .monitored_sessions
            .lock()
            .await
            .insert(command.session_id.clone())
        {
            return;
        }
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
            loop {
                interval.tick().await;
                if !service
                    .monitored_sessions
                    .lock()
                    .await
                    .contains(&session_id)
                {
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
                match health {
                    runtime_supervisor::RuntimeHealth::Healthy => {
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
                        if probe_count.is_multiple_of(2) {
                            if service.resync_required.lock().await.contains(&session_id) {
                                continue;
                            }
                            match service
                                .state_collector
                                .collect_current_state(&session_id)
                                .await
                            {
                                Ok(state) => {
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
                                            match diff_states(
                                                &previous,
                                                &state,
                                                service.diff_max_bytes,
                                                service.diff_max_changes,
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
                    }
                    runtime_supervisor::RuntimeHealth::Crashed(reason) => {
                        service.monitored_sessions.lock().await.remove(&session_id);
                        if let Some(gateway) = service.remote_desktop_gateway.as_ref() {
                            gateway.unregister_session(&session_id);
                        }
                        if let Err(error) = service
                            .record_and_publish_crash(
                                &tenant_id,
                                &session_id,
                                coordinator_term,
                                running_context_epoch,
                                &reason,
                            )
                            .await
                        {
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
                crash_type: "BROWSER_PROCESS_EXIT".to_owned(),
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
            if let Err(error) = self
                .record_and_publish_crash(
                    &lease.tenant_id,
                    &lease.session_id,
                    lease.coordinator_term,
                    lease.context_epoch,
                    &reason,
                )
                .await
            {
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
            .validate_and_record_term(&command.session_id, command.coordinator_term)
            .await
            .map_err(|error| {
                tracing::error!(error = %error, "Failed to update Coordinator Term");
                Status::internal("node journal unavailable")
            })? {
            TermDecision::Stale { .. } => {
                return Ok(Response::new(DispatchResponse {
                    acknowledgement: Some(Self::ack(
                        &command.message_id,
                        false,
                        "STALE_COORDINATOR_TERM",
                        "coordinator term is older than the last accepted term",
                    )),
                }));
            }
            TermDecision::Accepted => {}
        }

        {
            let mut inflight = self.inflight.lock().await;
            if !inflight.insert(command.message_id.clone()) {
                let mut acknowledgement = Self::ack(&command.message_id, true, "", "");
                acknowledgement.duplicate = true;
                return Ok(Response::new(DispatchResponse {
                    acknowledgement: Some(acknowledgement),
                }));
            }
        }

        if command.command_type == "StopRuntime" {
            self.monitored_sessions
                .lock()
                .await
                .remove(&command.session_id);
        }
        let result = self.execute(&command).await;
        self.inflight.lock().await.remove(&command.message_id);
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
                    let _ = self.runtime_supervisor.stop(&command.session_id).await;
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
            if command.command_type == "StartRuntime" {
                self.begin_runtime_monitor(&command).await;
            }
        }
        Ok(Response::new(DispatchResponse {
            acknowledgement: Some(acknowledgement),
        }))
    }
}

#[tokio::main]
async fn main() -> Result<()> {
    tracing_subscriber::fmt()
        .with_env_filter(
            EnvFilter::try_from_default_env().unwrap_or_else(|_| EnvFilter::new("info")),
        )
        .init();

    let chromium_binary = std::env::var("CHROMIUM_PATH").unwrap_or_else(|_| "chromium".to_owned());
    let node_port = std::env::var("NODE_AGENT_PORT")
        .unwrap_or_else(|_| "9090".to_owned())
        .parse::<u16>()?;
    let node_id = std::env::var("NODE_ID").unwrap_or_else(|_| "node-local-1".to_owned());
    let control_plane_event_target =
        std::env::var("CONTROL_PLANE_EVENT_TARGET").unwrap_or_else(|_| "127.0.0.1:9091".to_owned());
    let runtime_root = std::env::var("RUNTIME_ROOT")
        .map(PathBuf::from)
        .unwrap_or_else(|_| Path::new("/tmp/browsercloud-runtime").to_path_buf());
    tokio::fs::create_dir_all(&runtime_root).await?;
    let journal_path = std::env::var("NODE_JOURNAL_PATH")
        .map(PathBuf::from)
        .unwrap_or_else(|_| runtime_root.join("node-journal.sqlite3"));
    let journal = Arc::new(SqliteNodeJournal::open(journal_path).await?);
    let profile_storage_root = std::env::var("PROFILE_STORAGE_ROOT")
        .map(PathBuf::from)
        .unwrap_or_else(|_| runtime_root.join("profile-storage"));
    let profile_store = Arc::new(LocalProfileStore::open(profile_storage_root).await?);
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
    let mut runtime_supervisor = ChromiumRuntimeSupervisor::new(PathBuf::from(chromium_binary));
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
    let environment = std::env::var("APP_ENVIRONMENT").unwrap_or_else(|_| "local".to_owned());
    let grpc_tls = GrpcTlsMaterial::from_environment(&environment)?.map(Arc::new);
    let allow_direct_network = std::env::var("ALLOW_DIRECT_NETWORK")
        .map(|value| value.eq_ignore_ascii_case("true"))
        .unwrap_or(false);
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
    let remote_desktop_gateway =
        RemoteDesktopGateway::new(ticket_secret, allowed_origins, disconnect_handler)?;
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
    let service = NodeControlService {
        node_id,
        control_plane_event_target,
        grpc_tls: grpc_tls.clone(),
        runtime_supervisor: runtime_supervisor.clone(),
        profile_store,
        profile_workspaces: Arc::new(Mutex::new(HashMap::new())),
        network_helper,
        allow_direct_network,
        state_collector: Arc::new(CdpStateCollector::new()),
        state_baselines: Arc::new(Mutex::new(HashMap::new())),
        resync_required: Arc::new(Mutex::new(HashSet::new())),
        diff_max_bytes,
        diff_max_changes,
        input_brokers,
        journal,
        inflight: Arc::new(Mutex::new(HashSet::new())),
        monitored_sessions: Arc::new(Mutex::new(HashSet::new())),
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
        .add_service(NodeControlServiceServer::new(service))
        .serve_with_shutdown(address, shutdown_signal())
        .await?;
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
    use node_contracts::proto::{PublishResponse, RuntimeStoppedEvent};
    use std::time::{SystemTime, UNIX_EPOCH};
    use tokio::sync::mpsc;

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
    }

    fn temporary_path(name: &str) -> PathBuf {
        let nonce = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        std::env::temp_dir().join(format!("browsercloud-node-agent-{name}-{nonce}"))
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
            event_type: "RuntimeStopped".into(),
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
            profile_store: Arc::new(
                LocalProfileStore::open(root.join("profiles"))
                    .await
                    .unwrap(),
            ),
            profile_workspaces: Arc::new(Mutex::new(HashMap::new())),
            network_helper: None,
            allow_direct_network: true,
            state_collector: Arc::new(CdpStateCollector::new()),
            state_baselines: Arc::new(Mutex::new(HashMap::new())),
            resync_required: Arc::new(Mutex::new(HashSet::new())),
            diff_max_bytes: 60_000,
            diff_max_changes: 200,
            input_brokers: Arc::new(Mutex::new(HashMap::new())),
            journal: reopened.clone(),
            inflight: Arc::new(Mutex::new(HashSet::new())),
            monitored_sessions: Arc::new(Mutex::new(HashSet::new())),
            next_cdp_port: Arc::new(Mutex::new(10_000)),
            next_display: Arc::new(Mutex::new(100)),
            remote_desktop_gateway: None,
            desktop_enabled: false,
        };

        // Give the local gRPC listener one scheduler turn before redelivery.
        tokio::time::sleep(Duration::from_millis(50)).await;
        service.redeliver_pending_events().await;
        let received = tokio::time::timeout(Duration::from_secs(2), receiver.recv())
            .await
            .unwrap()
            .unwrap();
        assert_eq!(received.event_id, "evt_restart_1");
        assert!(reopened.pending_events(10).await.unwrap().is_empty());

        server.abort();
        let _ = tokio::fs::remove_dir_all(root).await;
    }
}
