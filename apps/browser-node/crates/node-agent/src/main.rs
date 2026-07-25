//! Browser Node Agent 入口。

use anyhow::Result;
use input_sandbox::{CdpDesktopInput, DesktopInput, InputKey};
use node_contracts::proto::node_control_service_server::{
    NodeControlService as NodeControlServiceRpc, NodeControlServiceServer,
};
use node_contracts::proto::node_event_service_client::NodeEventServiceClient;
use node_contracts::proto::{
    BeginHumanTakeoverCommand, BrowserCrashEvent, BrowserStateEvent, CommandAck, CommandEnvelope,
    DispatchRequest, DispatchResponse, EndHumanTakeoverCommand, EventEnvelope, ExecuteInputCommand,
    HumanTakeoverEndedEvent, HumanTakeoverReadyEvent, InteractiveTargetState, PingRequest,
    PingResponse, PublishRequest, ReleaseAllInputCommand, RuntimeStartedEvent, RuntimeStoppedEvent,
    StartRuntimeCommand, StopRuntimeCommand, TargetBounds,
};
use node_journal::{
    PersistedAcknowledgement, PersistedCommandResult, RuntimeLease, SqliteNodeJournal, TermDecision,
};
use prost::Message;
use runtime_supervisor::{ChromiumRuntimeSupervisor, RuntimeSpec, RuntimeSupervisor};
use state_collector::{BrowserStateCollector, CdpStateCollector, CurrentState, StateQuality};
use std::collections::{HashMap, HashSet};
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::{Duration, SystemTime, UNIX_EPOCH};
use tokio::sync::Mutex;
use tonic::{Request, Response, Status};
use tracing_subscriber::EnvFilter;

#[derive(Clone)]
struct NodeControlService {
    node_id: String,
    control_plane_event_target: String,
    runtime_root: PathBuf,
    runtime_supervisor: Arc<ChromiumRuntimeSupervisor>,
    state_collector: Arc<CdpStateCollector>,
    input_brokers: Arc<Mutex<HashMap<String, Arc<CdpDesktopInput>>>>,
    journal: Arc<SqliteNodeJournal>,
    inflight: Arc<Mutex<HashSet<String>>>,
    monitored_sessions: Arc<Mutex<HashSet<String>>>,
    next_cdp_port: Arc<Mutex<u16>>,
}

#[derive(Clone)]
struct CommandResult {
    acknowledgement: CommandAck,
    event: Option<EventEnvelope>,
    runtime_lease: Option<RuntimeLease>,
    stop_runtime_lease: bool,
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

    fn profile_dir(&self, session_id: &str) -> PathBuf {
        self.runtime_root.join(session_id).join("profile")
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
        }
    }

    fn runtime_stopped_result(acknowledgement: CommandAck, event: EventEnvelope) -> CommandResult {
        CommandResult {
            acknowledgement,
            event: Some(event),
            runtime_lease: None,
            stop_runtime_lease: true,
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
                .map(|target| InteractiveTargetState {
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
                })
                .collect(),
        }
    }

    async fn publish_event(&self, event: EventEnvelope) -> anyhow::Result<()> {
        let target = if self.control_plane_event_target.starts_with("http://")
            || self.control_plane_event_target.starts_with("https://")
        {
            self.control_plane_event_target.clone()
        } else {
            format!("http://{}", self.control_plane_event_target)
        };
        let channel = tonic::transport::Endpoint::from_shared(target)?
            .connect_timeout(Duration::from_secs(2))
            .timeout(Duration::from_secs(2))
            .connect()
            .await?;
        let mut client = NodeEventServiceClient::new(channel);
        let acknowledgement = client
            .publish(PublishRequest { event: Some(event) })
            .await?
            .into_inner();
        anyhow::ensure!(
            acknowledgement.accepted,
            "Control Plane rejected Node Event: {}",
            acknowledgement.error_code
        );
        Ok(())
    }

    async fn publish_and_mark(&self, event: EventEnvelope) -> anyhow::Result<()> {
        let event_id = event.event_id.clone();
        self.publish_event(event).await?;
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
            Ok(state) => Self::browser_state_payload(state),
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
                },
            )
        };
        Self::result(Self::ack(&command.message_id, true, "", ""), Some(event))
    }

    async fn execute(&self, command: &CommandEnvelope) -> CommandResult {
        match command.command_type.as_str() {
            "StartRuntime" => {
                let payload = StartRuntimeCommand::decode(command.payload.as_slice());
                match payload {
                    Ok(payload) => {
                        let cdp_port = self.allocate_cdp_port().await;
                        let runtime_build_id = payload.runtime_build_id;
                        match self
                            .runtime_supervisor
                            .start(RuntimeSpec {
                                session_id: command.session_id.clone(),
                                runtime_build_id: runtime_build_id.clone(),
                                profile_dir: self.profile_dir(&command.session_id),
                                display: payload.display,
                                cdp_port,
                            })
                            .await
                        {
                            Ok(handle) => {
                                if let Err(error) = self
                                    .state_collector
                                    .register_runtime(&command.session_id, &handle.cdp_endpoint)
                                    .await
                                {
                                    let _ = self.runtime_supervisor.stop(&command.session_id).await;
                                    return self.failed(command, error);
                                }
                                let input = match CdpDesktopInput::connect(&handle.cdp_endpoint)
                                    .await
                                {
                                    Ok(input) => Arc::new(input),
                                    Err(error) => {
                                        self.state_collector
                                            .unregister_runtime(&command.session_id)
                                            .await;
                                        let _ =
                                            self.runtime_supervisor.stop(&command.session_id).await;
                                        return self.failed(command, error);
                                    }
                                };
                                self.input_brokers
                                    .lock()
                                    .await
                                    .insert(command.session_id.clone(), input);
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
                                let sequence =
                                    match self.next_event_sequence(&command.session_id).await {
                                        Ok(sequence) => sequence,
                                        Err(error) => return self.failed(command, error),
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
                                    },
                                );
                                Self::runtime_started_result(
                                    Self::ack(&command.message_id, true, "", ""),
                                    event,
                                    runtime_lease,
                                )
                            }
                            Err(error) => self.failed(command, error),
                        }
                    }
                    Err(error) => self.failed(command, error.into()),
                }
            }
            "StopRuntime" => match StopRuntimeCommand::decode(command.payload.as_slice()) {
                Ok(payload) => {
                    if let Some(input) = self.input_brokers.lock().await.remove(&command.session_id)
                    {
                        if let Err(error) = input.release_all().await {
                            return self.failed(command, error);
                        }
                    }
                    self.state_collector
                        .unregister_runtime(&command.session_id)
                        .await;
                    match self.runtime_supervisor.stop(&command.session_id).await {
                        Ok(()) => {
                            let sequence = match self.next_event_sequence(&command.session_id).await
                            {
                                Ok(sequence) => sequence,
                                Err(error) => return self.failed(command, error),
                            };
                            let event = Self::event(
                                command,
                                "RuntimeStopped",
                                sequence,
                                RuntimeStoppedEvent {
                                    session_id: command.session_id.clone(),
                                    reason: payload.reason,
                                    exit_code: 0,
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
        tokio::spawn(async move {
            let mut interval = tokio::time::interval(Duration::from_secs(1));
            let mut probe_count = 0_u64;
            let mut last_state_hash = String::new();
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
                            match service
                                .state_collector
                                .collect_current_state(&session_id)
                                .await
                            {
                                Ok(state) if state.content_hash != last_state_hash => {
                                    last_state_hash = state.content_hash.clone();
                                    if let Err(error) = service
                                        .record_and_publish_state(
                                            &tenant_id,
                                            &session_id,
                                            coordinator_term,
                                            running_context_epoch,
                                            state,
                                        )
                                        .await
                                    {
                                        tracing::warn!(
                                            session_id,
                                            error = %error,
                                            "Failed to queue Browser state event"
                                        );
                                    }
                                }
                                Ok(_) => {}
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

    let service = NodeControlService {
        node_id,
        control_plane_event_target,
        runtime_root,
        runtime_supervisor: Arc::new(ChromiumRuntimeSupervisor::new(PathBuf::from(
            chromium_binary,
        ))),
        state_collector: Arc::new(CdpStateCollector::new()),
        input_brokers: Arc::new(Mutex::new(HashMap::new())),
        journal,
        inflight: Arc::new(Mutex::new(HashSet::new())),
        monitored_sessions: Arc::new(Mutex::new(HashSet::new())),
        next_cdp_port: Arc::new(Mutex::new(10_000)),
    };
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
    tonic::transport::Server::builder()
        .add_service(NodeControlServiceServer::new(service))
        .serve_with_shutdown(address, async {
            if let Err(error) = tokio::signal::ctrl_c().await {
                tracing::error!(%error, "Failed to install shutdown signal handler");
            }
        })
        .await?;
    Ok(())
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
            runtime_root: root.clone(),
            runtime_supervisor: Arc::new(ChromiumRuntimeSupervisor::new(PathBuf::from(
                "/missing/chromium",
            ))),
            state_collector: Arc::new(CdpStateCollector::new()),
            input_brokers: Arc::new(Mutex::new(HashMap::new())),
            journal: reopened.clone(),
            inflight: Arc::new(Mutex::new(HashSet::new())),
            monitored_sessions: Arc::new(Mutex::new(HashSet::new())),
            next_cdp_port: Arc::new(Mutex::new(10_000)),
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
