//! Browser Node Agent 入口。

use anyhow::Result;
use node_contracts::proto::node_control_service_server::{
    NodeControlService as NodeControlServiceRpc, NodeControlServiceServer,
};
use node_contracts::proto::node_event_service_client::NodeEventServiceClient;
use node_contracts::proto::{
    CommandAck, CommandEnvelope, DispatchRequest, DispatchResponse, EventEnvelope, PingRequest,
    PingResponse, PublishRequest, RuntimeStartedEvent, RuntimeStoppedEvent, StartRuntimeCommand,
    StopRuntimeCommand,
};
use prost::Message;
use runtime_supervisor::{ChromiumRuntimeSupervisor, RuntimeSpec, RuntimeSupervisor};
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
    completed: Arc<Mutex<HashMap<String, CommandResult>>>,
    inflight: Arc<Mutex<HashSet<String>>>,
    coordinator_terms: Arc<Mutex<HashMap<String, i64>>>,
    event_sequences: Arc<Mutex<HashMap<String, i64>>>,
    next_cdp_port: Arc<Mutex<u16>>,
}

#[derive(Clone)]
struct CommandResult {
    acknowledgement: CommandAck,
    event: Option<EventEnvelope>,
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

    async fn next_event_sequence(&self, session_id: &str) -> i64 {
        let mut sequences = self.event_sequences.lock().await;
        let sequence = sequences.entry(session_id.to_owned()).or_default();
        *sequence += 1;
        *sequence
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

    fn result(acknowledgement: CommandAck, event: Option<EventEnvelope>) -> CommandResult {
        CommandResult {
            acknowledgement,
            event,
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
                                let sequence = self.next_event_sequence(&command.session_id).await;
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
                                Self::result(
                                    Self::ack(&command.message_id, true, "", ""),
                                    Some(event),
                                )
                            }
                            Err(error) => self.failed(command, error),
                        }
                    }
                    Err(error) => self.failed(command, error.into()),
                }
            }
            "StopRuntime" => match StopRuntimeCommand::decode(command.payload.as_slice()) {
                Ok(payload) => match self.runtime_supervisor.stop(&command.session_id).await {
                    Ok(()) => {
                        let sequence = self.next_event_sequence(&command.session_id).await;
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
                        Self::result(Self::ack(&command.message_id, true, "", ""), Some(event))
                    }
                    Err(error) => self.failed(command, error),
                },
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

    async fn redeliver(&self, result: &CommandResult) -> CommandAck {
        let mut acknowledgement = result.acknowledgement.clone();
        acknowledgement.duplicate = true;
        if let Some(event) = result.event.clone() {
            if let Err(error) = self.publish_event(event).await {
                tracing::warn!(error = %error, "Failed to redeliver Node Event");
                return Self::ack(
                    &acknowledgement.message_id,
                    false,
                    "EVENT_DELIVERY_FAILED",
                    "node event delivery failed",
                );
            }
        }
        acknowledgement
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
            .completed
            .lock()
            .await
            .get(&command.message_id)
            .cloned();
        if let Some(previous) = previous {
            let duplicate = self.redeliver(&previous).await;
            return Ok(Response::new(DispatchResponse {
                acknowledgement: Some(duplicate),
            }));
        }

        {
            let mut terms = self.coordinator_terms.lock().await;
            let current = terms.entry(command.session_id.clone()).or_default();
            if command.coordinator_term < *current {
                return Ok(Response::new(DispatchResponse {
                    acknowledgement: Some(Self::ack(
                        &command.message_id,
                        false,
                        "STALE_COORDINATOR_TERM",
                        "coordinator term is older than the last accepted term",
                    )),
                }));
            }
            *current = command.coordinator_term;
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

        let result = self.execute(&command).await;
        self.inflight.lock().await.remove(&command.message_id);
        if result.acknowledgement.accepted
            || result.acknowledgement.error_code == "UNSUPPORTED_COMMAND"
        {
            self.completed
                .lock()
                .await
                .insert(command.message_id.clone(), result.clone());
        }

        let mut acknowledgement = result.acknowledgement;
        if acknowledgement.accepted {
            if let Some(event) = result.event {
                if let Err(error) = self.publish_event(event).await {
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

    let service = NodeControlService {
        node_id,
        control_plane_event_target,
        runtime_root,
        runtime_supervisor: Arc::new(ChromiumRuntimeSupervisor::new(PathBuf::from(
            chromium_binary,
        ))),
        completed: Arc::new(Mutex::new(HashMap::new())),
        inflight: Arc::new(Mutex::new(HashSet::new())),
        coordinator_terms: Arc::new(Mutex::new(HashMap::new())),
        event_sequences: Arc::new(Mutex::new(HashMap::new())),
        next_cdp_port: Arc::new(Mutex::new(10_000)),
    };
    let address = ([0, 0, 0, 0], node_port).into();

    tracing::info!(%address, "Browser Node Agent gRPC server started");
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
