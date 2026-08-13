//! Browser Node 的受控远程桌面数据面。
//!
//! 浏览器只连接此 WebSocket 网关，VNC TCP 端口始终限制在 Node 回环地址。
//! 网关校验 Control Plane 签发的短期 HMAC 票据，阻止票据重放，并为每个 Session
//! 提供有界的多协作者连接。VNC 始终是 Agent 的观察/辅助通道，真人输入短时优先。
//! 普通远程桌面连接允许与 Agent 协作；只有真实 RFB 输入会触发短时真人优先窗口。

use anyhow::Context;
use async_trait::async_trait;
use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use base64::Engine;
use futures_util::{SinkExt, StreamExt};
use hmac::{Hmac, Mac};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::collections::{HashMap, HashSet};
use std::net::SocketAddr;
use std::sync::{Arc, Mutex, RwLock};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::{broadcast, mpsc, watch};
use tokio_tungstenite::tungstenite::handshake::server::{ErrorResponse, Request, Response};
use tokio_tungstenite::tungstenite::http::header::{ORIGIN, SEC_WEBSOCKET_PROTOCOL};
use tokio_tungstenite::tungstenite::http::StatusCode;
use tokio_tungstenite::tungstenite::Message;
use tokio_tungstenite::{accept_hdr_async, WebSocketStream};

type HmacSha256 = Hmac<Sha256>;

const MAX_TICKET_LIFETIME_SECONDS: u64 = 120;
const MAX_VNC_FRAME_BYTES: usize = 1024 * 1024;
const DEFAULT_DISCONNECT_GRACE: Duration = Duration::from_secs(2);
const HEARTBEAT_INTERVAL: Duration = Duration::from_secs(10);
const CLIENT_LIVENESS_TIMEOUT: Duration = Duration::from_secs(30);
const MAX_ACTIVE_CONNECTIONS_PER_SESSION: usize = 8;
const SHARED_FRAME_QUEUE_CAPACITY: usize = 4;
const SHARED_INPUT_QUEUE_CAPACITY: usize = 32;
const MAX_RFB_SERVER_MESSAGE_BYTES: usize = 32 * 1024 * 1024;
const SLOW_CLIENT_WRITE_TIMEOUT: Duration = Duration::from_secs(5);
const DEFAULT_ACTOR_BITRATE_LIMIT_KBPS: u32 = 8_000;
const DEFAULT_ACTOR_FRAME_RATE_LIMIT_FPS: u32 = 30;
const RFB_VERSION_3_8: &[u8; 12] = b"RFB 003.008\n";
// noVNC 1.7 uses this full-colour little-endian format after ServerInit. The hub owns one
// upstream pixel format, so every downstream sees this exact canonical representation.
const SHARED_PIXEL_FORMAT: [u8; 16] = [32, 24, 0, 1, 0, 255, 0, 255, 0, 255, 0, 8, 16, 0, 0, 0];

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct RemoteDesktopTicketClaims {
    pub tenant_id: String,
    pub session_id: String,
    pub actor_id: String,
    #[serde(default)]
    pub connection_id: String,
    pub coordinator_term: i64,
    pub context_epoch: i64,
    pub operation_epoch: u64,
    #[serde(default = "default_access_mode")]
    pub access_mode: String,
    #[serde(default)]
    pub view_only: bool,
    #[serde(default = "default_actor_bitrate_limit_kbps")]
    pub actor_bitrate_limit_kbps: u32,
    #[serde(default = "default_actor_frame_rate_limit_fps")]
    pub actor_frame_rate_limit_fps: u32,
    pub expires_at_epoch_seconds: u64,
    pub nonce: String,
}

fn default_access_mode() -> String {
    // Missing accessMode can only come from an older Control Plane during a rolling upgrade.
    // Fail collaborative: opening VNC must never acquire exclusive control merely because an
    // additive claim is absent.
    "COLLABORATIVE".to_owned()
}

fn default_actor_bitrate_limit_kbps() -> u32 {
    DEFAULT_ACTOR_BITRATE_LIMIT_KBPS
}

fn default_actor_frame_rate_limit_fps() -> u32 {
    DEFAULT_ACTOR_FRAME_RATE_LIMIT_FPS
}

#[async_trait]
pub trait DisconnectHandler: Send + Sync {
    async fn disconnected(&self, claims: &RemoteDesktopTicketClaims);

    async fn connection_changed(
        &self,
        _claims: &RemoteDesktopTicketClaims,
        _state: &str,
        _reason: &str,
        _usage: RemoteDesktopUsageCounters,
    ) {
    }
}

/// Monotonic, connection-scoped counters produced by the real RFB forwarding path. They are
/// reported cumulatively so the Control Plane can merge retries without double billing.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct RemoteDesktopUsageCounters {
    pub forwarded_bytes: u64,
    pub quota_wait_millis: u64,
    pub throttled_batches: u64,
}

#[derive(Default)]
pub struct NoopDisconnectHandler;

#[async_trait]
impl DisconnectHandler for NoopDisconnectHandler {
    async fn disconnected(&self, _claims: &RemoteDesktopTicketClaims) {}
}

struct GatewayState {
    ticket_secret: Vec<u8>,
    allowed_origins: HashSet<String>,
    vnc_endpoints: RwLock<HashMap<String, SocketAddr>>,
    shared_hubs: Mutex<HashMap<String, Arc<SharedRfbHub>>>,
    active_connections: Mutex<HashMap<String, SessionConnectionState>>,
    last_server_frame_at: Mutex<HashMap<String, Instant>>,
    last_human_input_at: Mutex<HashMap<String, Instant>>,
    bitrate_limits_kbps: Mutex<HashMap<String, u32>>,
    observer_frame_rates_fps: Mutex<HashMap<String, u32>>,
    actor_forwarding: Mutex<HashMap<ActorQuotaKey, ActorForwardingState>>,
    connection_usage: Mutex<HashMap<String, RemoteDesktopUsageCounters>>,
    used_nonces: Mutex<HashMap<String, u64>>,
    disconnect_grace: Duration,
    heartbeat_interval: Duration,
    client_liveness_timeout: Duration,
    disconnect_handler: Arc<dyn DisconnectHandler>,
}

#[derive(Debug, Clone, PartialEq, Eq, Hash)]
struct ActorQuotaKey {
    tenant_id: String,
    session_id: String,
    actor_id: String,
}

impl From<&RemoteDesktopTicketClaims> for ActorQuotaKey {
    fn from(claims: &RemoteDesktopTicketClaims) -> Self {
        Self {
            tenant_id: claims.tenant_id.clone(),
            session_id: claims.session_id.clone(),
            actor_id: claims.actor_id.clone(),
        }
    }
}

#[derive(Debug, Default)]
struct ActorForwardingState {
    bitrate_ready_at: Option<Instant>,
    frame_ready_at: Option<Instant>,
}

#[derive(Debug, Default)]
struct SessionConnectionState {
    generation: u64,
    leases_by_connection_id: HashMap<String, ConnectionLease>,
}

#[derive(Debug)]
struct ConnectionLease {
    claims: RemoteDesktopTicketClaims,
    revoke: watch::Sender<bool>,
}

#[derive(Debug, Clone)]
enum SharedRfbHubStatus {
    Connecting,
    Ready(Arc<RfbServerInit>),
    Failed(Arc<str>),
}

#[derive(Debug)]
struct RfbServerInit {
    wire_bytes: Vec<u8>,
    width: u16,
    height: u16,
    pixel_format: [u8; 16],
    bytes_per_pixel: usize,
}

#[derive(Debug)]
struct SharedRfbHub {
    endpoint: SocketAddr,
    input: mpsc::Sender<Vec<u8>>,
    refresh: mpsc::Sender<()>,
    frames: broadcast::Sender<Arc<Vec<u8>>>,
    latest_frame: Mutex<Option<Arc<Vec<u8>>>>,
    status: watch::Receiver<SharedRfbHubStatus>,
    shutdown: watch::Sender<bool>,
}

struct SharedRfbHubTask {
    input: mpsc::Receiver<Vec<u8>>,
    refresh: mpsc::Receiver<()>,
    frames: broadcast::Sender<Arc<Vec<u8>>>,
    hub: Arc<SharedRfbHub>,
    status: watch::Sender<SharedRfbHubStatus>,
    shutdown: watch::Receiver<bool>,
}

#[derive(Clone)]
pub struct RemoteDesktopGateway {
    state: Arc<GatewayState>,
}

#[derive(Debug)]
struct AuthorizedConnection {
    claims: RemoteDesktopTicketClaims,
    vnc_endpoint: SocketAddr,
    revocation: watch::Receiver<bool>,
}

#[derive(Debug)]
struct HandshakeRejection {
    status: StatusCode,
    message: &'static str,
}

impl HandshakeRejection {
    fn into_response(self) -> ErrorResponse {
        tokio_tungstenite::tungstenite::http::Response::builder()
            .status(self.status)
            .header("Content-Type", "text/plain; charset=utf-8")
            .body(Some(self.message.to_owned()))
            .expect("static remote desktop rejection is valid")
    }
}

impl RemoteDesktopGateway {
    pub fn new(
        ticket_secret: impl Into<Vec<u8>>,
        allowed_origins: impl IntoIterator<Item = String>,
        disconnect_handler: Arc<dyn DisconnectHandler>,
    ) -> anyhow::Result<Self> {
        Self::with_disconnect_grace(
            ticket_secret,
            allowed_origins,
            disconnect_handler,
            DEFAULT_DISCONNECT_GRACE,
        )
    }

    fn with_disconnect_grace(
        ticket_secret: impl Into<Vec<u8>>,
        allowed_origins: impl IntoIterator<Item = String>,
        disconnect_handler: Arc<dyn DisconnectHandler>,
        disconnect_grace: Duration,
    ) -> anyhow::Result<Self> {
        Self::new_with_timeouts(
            ticket_secret,
            allowed_origins,
            disconnect_handler,
            disconnect_grace,
            HEARTBEAT_INTERVAL,
            CLIENT_LIVENESS_TIMEOUT,
        )
    }

    pub fn new_with_timeouts(
        ticket_secret: impl Into<Vec<u8>>,
        allowed_origins: impl IntoIterator<Item = String>,
        disconnect_handler: Arc<dyn DisconnectHandler>,
        disconnect_grace: Duration,
        heartbeat_interval: Duration,
        client_liveness_timeout: Duration,
    ) -> anyhow::Result<Self> {
        let ticket_secret = ticket_secret.into();
        anyhow::ensure!(
            ticket_secret.len() >= 32,
            "remote desktop ticket secret must contain at least 32 bytes"
        );
        anyhow::ensure!(
            !disconnect_grace.is_zero(),
            "remote desktop disconnect grace must be positive"
        );
        anyhow::ensure!(
            !heartbeat_interval.is_zero(),
            "remote desktop heartbeat interval must be positive"
        );
        anyhow::ensure!(
            client_liveness_timeout >= heartbeat_interval.saturating_mul(2),
            "remote desktop client liveness timeout must cover at least two heartbeats"
        );
        Ok(Self {
            state: Arc::new(GatewayState {
                ticket_secret,
                allowed_origins: allowed_origins.into_iter().collect(),
                vnc_endpoints: RwLock::new(HashMap::new()),
                shared_hubs: Mutex::new(HashMap::new()),
                active_connections: Mutex::new(HashMap::new()),
                last_server_frame_at: Mutex::new(HashMap::new()),
                last_human_input_at: Mutex::new(HashMap::new()),
                bitrate_limits_kbps: Mutex::new(HashMap::new()),
                observer_frame_rates_fps: Mutex::new(HashMap::new()),
                actor_forwarding: Mutex::new(HashMap::new()),
                connection_usage: Mutex::new(HashMap::new()),
                used_nonces: Mutex::new(HashMap::new()),
                disconnect_grace,
                heartbeat_interval,
                client_liveness_timeout,
                disconnect_handler,
            }),
        })
    }

    pub fn register_session(
        &self,
        session_id: &str,
        vnc_endpoint: SocketAddr,
    ) -> anyhow::Result<()> {
        validate_session_id(session_id)?;
        anyhow::ensure!(
            vnc_endpoint.ip().is_loopback(),
            "VNC endpoint must use a loopback address"
        );
        let previous = self
            .state
            .vnc_endpoints
            .write()
            .expect("VNC endpoint lock poisoned")
            .insert(session_id.to_owned(), vnc_endpoint);
        if previous.is_some_and(|previous| previous != vnc_endpoint) {
            self.stop_shared_hub(session_id);
        }
        self.state
            .bitrate_limits_kbps
            .lock()
            .expect("bitrate limit lock poisoned")
            .entry(session_id.to_owned())
            .or_insert(0);
        self.state
            .observer_frame_rates_fps
            .lock()
            .expect("Observer frame-rate lock poisoned")
            .entry(session_id.to_owned())
            .or_insert(30);
        Ok(())
    }

    pub fn unregister_session(&self, session_id: &str) {
        self.state
            .vnc_endpoints
            .write()
            .expect("VNC endpoint lock poisoned")
            .remove(session_id);
        self.state
            .actor_forwarding
            .lock()
            .expect("actor forwarding lock poisoned")
            .retain(|key, _| key.session_id != session_id);
        self.stop_shared_hub(session_id);
        self.state
            .last_server_frame_at
            .lock()
            .expect("frame timestamp lock poisoned")
            .remove(session_id);
        self.state
            .last_human_input_at
            .lock()
            .expect("human input timestamp lock poisoned")
            .remove(session_id);
        self.state
            .bitrate_limits_kbps
            .lock()
            .expect("bitrate limit lock poisoned")
            .remove(session_id);
        self.state
            .observer_frame_rates_fps
            .lock()
            .expect("Observer frame-rate lock poisoned")
            .remove(session_id);
        if let Some(connections) = self
            .state
            .active_connections
            .lock()
            .expect("active connection lock poisoned")
            .remove(session_id)
        {
            for lease in connections.leases_by_connection_id.into_values() {
                let _ = lease.revoke.send(true);
            }
        }
    }

    fn stop_shared_hub(&self, session_id: &str) {
        if let Some(hub) = self
            .state
            .shared_hubs
            .lock()
            .expect("shared RFB hub lock poisoned")
            .remove(session_id)
        {
            let _ = hub.shutdown.send(true);
        }
    }

    fn shared_hub(&self, session_id: &str, endpoint: SocketAddr) -> Arc<SharedRfbHub> {
        let mut hubs = self
            .state
            .shared_hubs
            .lock()
            .expect("shared RFB hub lock poisoned");
        if let Some(existing) = hubs.get(session_id) {
            if existing.endpoint == endpoint
                && !matches!(&*existing.status.borrow(), SharedRfbHubStatus::Failed(_))
            {
                return existing.clone();
            }
            let _ = existing.shutdown.send(true);
        }

        let (input, input_receiver) = mpsc::channel(SHARED_INPUT_QUEUE_CAPACITY);
        let (refresh, refresh_receiver) = mpsc::channel(SHARED_FRAME_QUEUE_CAPACITY);
        let (frames, _) = broadcast::channel(SHARED_FRAME_QUEUE_CAPACITY);
        let (status_sender, status) = watch::channel(SharedRfbHubStatus::Connecting);
        let (shutdown, shutdown_receiver) = watch::channel(false);
        let hub = Arc::new(SharedRfbHub {
            endpoint,
            input,
            refresh,
            frames: frames.clone(),
            latest_frame: Mutex::new(None),
            status,
            shutdown,
        });
        hubs.insert(session_id.to_owned(), hub.clone());

        let state = self.state.clone();
        let session_id = session_id.to_owned();
        let task_hub = hub.clone();
        tokio::spawn(async move {
            if let Err(error) = run_shared_rfb_hub(
                state.clone(),
                &session_id,
                endpoint,
                SharedRfbHubTask {
                    input: input_receiver,
                    refresh: refresh_receiver,
                    frames,
                    hub: task_hub,
                    status: status_sender.clone(),
                    shutdown: shutdown_receiver,
                },
            )
            .await
            {
                let detail: Arc<str> = Arc::from(error.to_string());
                let _ = status_sender.send(SharedRfbHubStatus::Failed(detail));
            }
        });
        hub
    }

    /// 在线调整 VNC Server → WebSocket Client 的单 Session 速率边界。
    ///
    /// `0` 表示该 Session 未启用桌面速率限制；非零值限制在 250—100000 Kbps。
    pub fn set_bitrate_limit(&self, session_id: &str, bitrate_kbps: u32) -> anyhow::Result<u32> {
        anyhow::ensure!(
            bitrate_kbps == 0 || (250..=100_000).contains(&bitrate_kbps),
            "remote desktop bitrate must be zero or between 250 and 100000 Kbps"
        );
        anyhow::ensure!(
            self.state
                .vnc_endpoints
                .read()
                .expect("VNC endpoint lock poisoned")
                .contains_key(session_id),
            "remote desktop session is not registered"
        );
        Ok(self
            .state
            .bitrate_limits_kbps
            .lock()
            .expect("bitrate limit lock poisoned")
            .insert(session_id.to_owned(), bitrate_kbps)
            .unwrap_or(0))
    }

    pub fn bitrate_limit_kbps(&self, session_id: &str) -> Option<u32> {
        self.state
            .bitrate_limits_kbps
            .lock()
            .expect("bitrate limit lock poisoned")
            .get(session_id)
            .copied()
    }

    /// 在线限制受控 VNC Observer 数据面向客户端转发数据批次的最大频率。
    ///
    /// VNC TCP 流不暴露编码帧边界，因此这里在安全边界内对 Server → Client
    /// 转发批次做节流；它不会延迟 Client → Server 的 Human Input。
    pub fn set_observer_frame_rate(
        &self,
        session_id: &str,
        frame_rate_fps: u32,
    ) -> anyhow::Result<u32> {
        anyhow::ensure!(
            (1..=60).contains(&frame_rate_fps),
            "Observer frame rate must be between 1 and 60 FPS"
        );
        anyhow::ensure!(
            self.state
                .vnc_endpoints
                .read()
                .expect("VNC endpoint lock poisoned")
                .contains_key(session_id),
            "remote desktop session is not registered"
        );
        Ok(self
            .state
            .observer_frame_rates_fps
            .lock()
            .expect("Observer frame-rate lock poisoned")
            .insert(session_id.to_owned(), frame_rate_fps)
            .unwrap_or(30))
    }

    pub fn observer_frame_rate_fps(&self, session_id: &str) -> Option<u32> {
        self.state
            .observer_frame_rates_fps
            .lock()
            .expect("Observer frame-rate lock poisoned")
            .get(session_id)
            .copied()
    }

    #[cfg(test)]
    fn server_bitrate_delay(&self, session_id: &str, bytes: usize) -> Duration {
        let bitrate_kbps = self.bitrate_limit_kbps(session_id).unwrap_or_default();
        if bitrate_kbps == 0 || bytes == 0 {
            return Duration::ZERO;
        }
        let seconds = bytes as f64 * 8.0 / (f64::from(bitrate_kbps) * 1_000.0);
        Duration::from_secs_f64(seconds)
    }

    #[cfg(test)]
    fn observer_frame_rate_delay(
        &self,
        session_id: &str,
        last_forwarded_at: Option<Instant>,
    ) -> Duration {
        let frame_rate_fps = self.observer_frame_rate_fps(session_id).unwrap_or(30);
        let minimum_interval = Duration::from_secs_f64(1.0 / f64::from(frame_rate_fps));
        if let Some(last_forwarded_at) = last_forwarded_at {
            let elapsed = last_forwarded_at.elapsed();
            if elapsed < minimum_interval {
                return minimum_interval - elapsed;
            }
        }
        Duration::ZERO
    }

    fn reserve_server_forwarding(
        &self,
        claims: &RemoteDesktopTicketClaims,
        bytes: usize,
    ) -> Duration {
        let session_bitrate = self
            .bitrate_limit_kbps(&claims.session_id)
            .unwrap_or_default();
        let bitrate_kbps = if session_bitrate == 0 {
            claims.actor_bitrate_limit_kbps
        } else {
            session_bitrate.min(claims.actor_bitrate_limit_kbps)
        };
        let frame_rate_fps = self
            .observer_frame_rate_fps(&claims.session_id)
            .unwrap_or(30)
            .min(claims.actor_frame_rate_limit_fps);
        let now = Instant::now();
        let bitrate_duration = if bytes == 0 {
            Duration::ZERO
        } else {
            Duration::from_secs_f64(bytes as f64 * 8.0 / (f64::from(bitrate_kbps) * 1_000.0))
        };
        let frame_interval = Duration::from_secs_f64(1.0 / f64::from(frame_rate_fps));
        let mut actors = self
            .state
            .actor_forwarding
            .lock()
            .expect("actor forwarding lock poisoned");
        let actor = actors.entry(claims.into()).or_default();
        let bitrate_ready_at = actor.bitrate_ready_at.unwrap_or(now).max(now) + bitrate_duration;
        let frame_ready_at = actor.frame_ready_at.unwrap_or(now).max(now);
        actor.bitrate_ready_at = Some(bitrate_ready_at);
        actor.frame_ready_at = Some(frame_ready_at + frame_interval);
        bitrate_ready_at
            .max(frame_ready_at)
            .saturating_duration_since(now)
    }

    fn record_server_forwarded(&self, connection_id: &str, bytes: usize, quota_wait: Duration) {
        let mut usage = self
            .state
            .connection_usage
            .lock()
            .expect("remote desktop usage lock poisoned");
        let counters = usage.entry(connection_id.to_owned()).or_default();
        counters.forwarded_bytes = counters.forwarded_bytes.saturating_add(bytes as u64);
        counters.quota_wait_millis = counters
            .quota_wait_millis
            .saturating_add(quota_wait.as_millis().try_into().unwrap_or(u64::MAX));
        if !quota_wait.is_zero() {
            counters.throttled_batches = counters.throttled_batches.saturating_add(1);
        }
    }

    fn connection_usage(&self, connection_id: &str) -> RemoteDesktopUsageCounters {
        self.state
            .connection_usage
            .lock()
            .expect("remote desktop usage lock poisoned")
            .get(connection_id)
            .copied()
            .unwrap_or_default()
    }

    fn take_connection_usage(&self, connection_id: &str) -> RemoteDesktopUsageCounters {
        self.state
            .connection_usage
            .lock()
            .expect("remote desktop usage lock poisoned")
            .remove(connection_id)
            .unwrap_or_default()
    }

    /// 返回当前活跃远程桌面连接距离最近一批 VNC Server 数据的年龄。
    ///
    /// 没有活跃客户端时返回 `None`，避免把未使用远程桌面的 Session 误判为帧阻塞。
    pub fn frame_age_ms(&self, session_id: &str) -> Option<u32> {
        if self.active_connection_count(session_id) == 0 {
            return None;
        }
        self.state
            .last_server_frame_at
            .lock()
            .expect("frame timestamp lock poisoned")
            .get(session_id)
            .map(|observed| {
                observed
                    .elapsed()
                    .as_millis()
                    .try_into()
                    .unwrap_or(u32::MAX)
            })
    }

    /// 返回当前 Session 的已授权活跃桌面连接数。
    ///
    /// 该值有严格上限，避免多个 Viewer 线性放大 x11vnc 编码和 Node 网络负载。
    pub fn active_connection_count(&self, session_id: &str) -> usize {
        self.state
            .active_connections
            .lock()
            .expect("active connection lock poisoned")
            .get(session_id)
            .map(|connections| connections.leases_by_connection_id.len())
            .unwrap_or_default()
    }

    /// Revokes one exact connection without affecting Agent execution or other participants.
    pub fn revoke_connection(
        &self,
        session_id: &str,
        connection_id: &str,
    ) -> Option<RemoteDesktopTicketClaims> {
        self.state
            .active_connections
            .lock()
            .expect("active connection lock poisoned")
            .get(session_id)
            .and_then(|connections| connections.leases_by_connection_id.get(connection_id))
            .map(|lease| {
                let _ = lease.revoke.send(true);
                lease.claims.clone()
            })
    }

    /// 真人最近产生键鼠或剪贴板输入时返回 `true`。
    ///
    /// 仅建立或保持 VNC 观察连接不会激活该窗口，因此 Agent 可继续操作并被真人观察。
    pub fn human_input_active(&self, session_id: &str, idle_window: Duration) -> bool {
        self.state
            .last_human_input_at
            .lock()
            .expect("human input timestamp lock poisoned")
            .get(session_id)
            .is_some_and(|observed| observed.elapsed() <= idle_window)
    }

    fn mark_human_input(&self, session_id: &str) {
        self.state
            .last_human_input_at
            .lock()
            .expect("human input timestamp lock poisoned")
            .insert(session_id.to_owned(), Instant::now());
    }

    pub async fn serve(self, listener: TcpListener) -> anyhow::Result<()> {
        loop {
            let (stream, peer) = listener.accept().await?;
            let gateway = self.clone();
            tokio::spawn(async move {
                if let Err(error) = gateway.handle_connection(stream).await {
                    tracing::debug!(peer = %peer, error = %error, "Remote desktop connection closed");
                }
            });
        }
    }

    // tungstenite 的固定握手回调类型以完整 HTTP Response 作为 Err，无法在此处缩小。
    #[allow(clippy::result_large_err)]
    async fn handle_connection(&self, stream: TcpStream) -> anyhow::Result<()> {
        let authorization = Arc::new(Mutex::new(None));
        let callback_authorization = authorization.clone();
        let state = self.state.clone();
        let websocket =
            accept_hdr_async(stream, move |request: &Request, mut response: Response| {
                match authorize(&state, request) {
                    Ok(connection) => {
                        *callback_authorization
                            .lock()
                            .expect("authorization lock poisoned") = Some(connection);
                        if request
                            .headers()
                            .get(SEC_WEBSOCKET_PROTOCOL)
                            .and_then(|value| value.to_str().ok())
                            .is_some_and(|value| {
                                value.split(',').any(|protocol| protocol.trim() == "binary")
                            })
                        {
                            response.headers_mut().insert(
                                SEC_WEBSOCKET_PROTOCOL,
                                "binary".parse().expect("binary protocol is valid"),
                            );
                        }
                        Ok(response)
                    }
                    Err(rejection) => Err(rejection.into_response()),
                }
            })
            .await;

        let authorized = authorization
            .lock()
            .expect("authorization lock poisoned")
            .take();
        let Some(authorized) = authorized else {
            websocket.context("remote desktop WebSocket handshake rejected")?;
            anyhow::bail!("handshake completed without authorization");
        };

        let result = match websocket {
            Ok(websocket) => self.proxy(websocket, &authorized).await,
            Err(error) => Err(error.into()),
        };
        let disconnect_reason = if *authorized.revocation.borrow() {
            "ADMIN_REVOKED"
        } else {
            "CLIENT_DISCONNECTED"
        };
        let (disconnected_generation, actor_still_connected) = {
            let mut sessions = self
                .state
                .active_connections
                .lock()
                .expect("active connection lock poisoned");
            let generation =
                sessions
                    .get_mut(&authorized.claims.session_id)
                    .and_then(|connections| {
                        connections
                            .leases_by_connection_id
                            .remove(&authorized.claims.connection_id)?;
                        connections.generation = connections.generation.saturating_add(1);
                        Some(connections.generation)
                    });
            let actor_still_connected =
                sessions
                    .get(&authorized.claims.session_id)
                    .is_some_and(|connections| {
                        connections.leases_by_connection_id.values().any(|lease| {
                            lease.claims.tenant_id == authorized.claims.tenant_id
                                && lease.claims.actor_id == authorized.claims.actor_id
                        })
                    });
            (generation, actor_still_connected)
        };
        if !actor_still_connected {
            self.state
                .actor_forwarding
                .lock()
                .expect("actor forwarding lock poisoned")
                .remove(&ActorQuotaKey::from(&authorized.claims));
        }
        let usage = self.take_connection_usage(&authorized.claims.connection_id);
        self.state
            .disconnect_handler
            .connection_changed(&authorized.claims, "DISCONNECTED", disconnect_reason, usage)
            .await;
        if let Some(disconnected_generation) = disconnected_generation {
            let state = self.state.clone();
            let claims = authorized.claims.clone();
            tokio::spawn(async move {
                tokio::time::sleep(state.disconnect_grace).await;
                let last_connection_still_gone = state
                    .active_connections
                    .lock()
                    .expect("active connection lock poisoned")
                    .get(&claims.session_id)
                    .is_some_and(|connections| {
                        connections.leases_by_connection_id.is_empty()
                            && connections.generation == disconnected_generation
                    });
                if last_connection_still_gone {
                    if let Some(hub) = state
                        .shared_hubs
                        .lock()
                        .expect("shared RFB hub lock poisoned")
                        .remove(&claims.session_id)
                    {
                        let _ = hub.shutdown.send(true);
                    }
                    state.disconnect_handler.disconnected(&claims).await;
                }
            });
        }
        result
    }

    async fn proxy(
        &self,
        mut websocket: WebSocketStream<TcpStream>,
        authorized: &AuthorizedConnection,
    ) -> anyhow::Result<()> {
        let hub = self.shared_hub(&authorized.claims.session_id, authorized.vnc_endpoint);
        let server_init = wait_for_shared_hub(&hub).await?;
        let mut pending_client_bytes = Vec::new();
        complete_downstream_rfb_handshake(
            &mut websocket,
            &mut pending_client_bytes,
            &server_init.wire_bytes,
        )
        .await?;
        self.state
            .disconnect_handler
            .connection_changed(
                &authorized.claims,
                "CONNECTED",
                "RFB_FANOUT_ATTACHED",
                RemoteDesktopUsageCounters::default(),
            )
            .await;
        self.state
            .last_server_frame_at
            .lock()
            .expect("frame timestamp lock poisoned")
            .insert(authorized.claims.session_id.clone(), Instant::now());
        let mut heartbeat = tokio::time::interval_at(
            tokio::time::Instant::now() + self.state.heartbeat_interval,
            self.state.heartbeat_interval,
        );
        heartbeat.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
        let mut usage_report = tokio::time::interval_at(
            tokio::time::Instant::now() + Duration::from_secs(5),
            Duration::from_secs(5),
        );
        usage_report.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
        let mut last_client_activity = tokio::time::Instant::now();
        // Subscribe before reading the cached baseline. An update racing this read
        // may be delivered twice, but it cannot be missed; subscribing afterwards
        // could leave a late joiner on a stale baseline indefinitely.
        let mut frames = hub.frames.subscribe();
        let mut pending_server_payload = hub
            .latest_frame
            .lock()
            .expect("shared RFB latest frame lock poisoned")
            .clone();
        let mut pending_server_ready_at = tokio::time::Instant::now()
            + pending_server_payload
                .as_ref()
                .map(|payload| self.reserve_server_forwarding(&authorized.claims, payload.len()))
                .unwrap_or_default();
        let mut pending_server_quota_wait =
            pending_server_ready_at.saturating_duration_since(tokio::time::Instant::now());
        let mut last_reported_usage = RemoteDesktopUsageCounters::default();
        let mut input_parser =
            RfbClientMessageParser::new(pending_client_bytes, server_init.pixel_format);
        match hub.refresh.try_send(()) {
            Ok(()) | Err(mpsc::error::TrySendError::Full(())) => {}
            Err(mpsc::error::TrySendError::Closed(())) => {
                anyhow::bail!("shared RFB refresh queue closed");
            }
        }
        let mut hub_status = hub.status.clone();
        let mut revocation = authorized.revocation.clone();
        loop {
            tokio::select! {
                changed = revocation.changed() => {
                    if changed.is_err() || *revocation.borrow() {
                        break;
                    }
                }
                changed = hub_status.changed() => {
                    if changed.is_err() {
                        anyhow::bail!("shared remote desktop upstream status closed");
                    }
                    if let SharedRfbHubStatus::Failed(detail) = &*hub_status.borrow() {
                        anyhow::bail!("shared remote desktop upstream failed: {detail}");
                    }
                }
                _ = heartbeat.tick() => {
                    anyhow::ensure!(
                        last_client_activity.elapsed() < self.state.client_liveness_timeout,
                        "remote desktop client heartbeat timed out"
                    );
                    websocket.send(Message::Ping(Vec::new())).await?;
                }
                _ = usage_report.tick() => {
                    let usage = self.connection_usage(&authorized.claims.connection_id);
                    if usage != last_reported_usage {
                        self.state.disconnect_handler
                            .connection_changed(
                                &authorized.claims,
                                "CONNECTED",
                                "USAGE_HEARTBEAT",
                                usage,
                            )
                            .await;
                        last_reported_usage = usage;
                    }
                }
                frame = frames.recv(), if pending_server_payload.is_none() => {
                    let frame = match frame {
                        Ok(frame) => frame,
                        Err(broadcast::error::RecvError::Lagged(_)) => {
                            anyhow::bail!("remote desktop client is too slow for bounded fan-out");
                        }
                        Err(broadcast::error::RecvError::Closed) => {
                            anyhow::bail!("shared remote desktop upstream closed");
                        }
                    };
                    self.state
                        .last_server_frame_at
                        .lock()
                        .expect("frame timestamp lock poisoned")
                        .insert(authorized.claims.session_id.clone(), Instant::now());
                    pending_server_quota_wait =
                        self.reserve_server_forwarding(&authorized.claims, frame.len());
                    pending_server_ready_at =
                        tokio::time::Instant::now() + pending_server_quota_wait;
                    pending_server_payload = Some(frame);
                }
                _ = tokio::time::sleep_until(pending_server_ready_at), if pending_server_payload.is_some() => {
                    let payload = pending_server_payload
                        .take()
                        .expect("guarded pending Observer payload");
                    tokio::time::timeout(
                        SLOW_CLIENT_WRITE_TIMEOUT,
                        websocket.send(Message::Binary(payload.as_ref().clone())),
                    )
                    .await
                    .context("remote desktop client write timed out")??;
                    self.record_server_forwarded(
                        &authorized.claims.connection_id,
                        payload.len(),
                        pending_server_quota_wait,
                    );
                    pending_server_quota_wait = Duration::ZERO;
                }
                message = websocket.next() => {
                    match message {
                        Some(Ok(Message::Binary(payload))) => {
                            last_client_activity = tokio::time::Instant::now();
                            anyhow::ensure!(
                                payload.len() <= MAX_VNC_FRAME_BYTES,
                                "VNC client frame exceeds 1 MiB"
                            );
                            for message in input_parser.ingest(&payload)? {
                                anyhow::ensure!(
                                    !authorized.claims.view_only || !message.human_input,
                                    "view-only remote desktop attempted human input"
                                );
                                if message.human_input {
                                    self.mark_human_input(&authorized.claims.session_id);
                                }
                                if message.forward {
                                    tokio::time::timeout(
                                        Duration::from_millis(250),
                                        hub.input.send(message.bytes),
                                    )
                                    .await
                                    .context("shared RFB input queue timed out")?
                                    .context("shared RFB input queue closed")?;
                                }
                                if message.refresh {
                                    match hub.refresh.try_send(()) {
                                        Ok(()) | Err(mpsc::error::TrySendError::Full(())) => {}
                                        Err(mpsc::error::TrySendError::Closed(())) => {
                                            anyhow::bail!("shared RFB refresh queue closed");
                                        }
                                    }
                                }
                            }
                        }
                        Some(Ok(Message::Ping(payload))) => {
                            last_client_activity = tokio::time::Instant::now();
                            websocket.send(Message::Pong(payload)).await?;
                        }
                        Some(Ok(Message::Pong(_))) => {
                            last_client_activity = tokio::time::Instant::now();
                        }
                        Some(Ok(Message::Close(_))) | None => break,
                        Some(Ok(Message::Text(_))) => {
                            anyhow::bail!("text WebSocket frames are not accepted");
                        }
                        Some(Ok(_)) => {}
                        Some(Err(error)) => return Err(error.into()),
                    }
                }
            }
        }
        let _ = websocket.close(None).await;
        Ok(())
    }
}

#[derive(Debug)]
struct RfbClientMessageParser {
    buffered: Vec<u8>,
    canonical_pixel_format: [u8; 16],
}

#[derive(Debug)]
struct ParsedRfbClientMessage {
    bytes: Vec<u8>,
    human_input: bool,
    forward: bool,
    refresh: bool,
}

impl RfbClientMessageParser {
    fn new(buffered: Vec<u8>, canonical_pixel_format: [u8; 16]) -> Self {
        Self {
            buffered,
            canonical_pixel_format,
        }
    }

    /// 识别 RFB Client → Server 的 KeyEvent、PointerEvent 与 ClientCutText。
    /// 未知消息失败关闭，不把未解析字节混入共享上游会话。
    fn ingest(&mut self, payload: &[u8]) -> anyhow::Result<Vec<ParsedRfbClientMessage>> {
        self.buffered.extend_from_slice(payload);
        if self.buffered.len() > MAX_VNC_FRAME_BYTES {
            self.buffered.clear();
            anyhow::bail!("buffered VNC client messages exceed 1 MiB");
        }

        let mut parsed = Vec::new();
        while let Some(message_type) = self.buffered.first().copied() {
            let (message_length, human_input, forward, refresh) = match message_type {
                0 => (20, false, false, false), // canonical pixel format is owned by the hub
                2 => {
                    if self.buffered.len() < 4 {
                        break;
                    }
                    let encoding_count = u16::from_be_bytes([self.buffered[2], self.buffered[3]]);
                    (
                        4usize.saturating_add(usize::from(encoding_count).saturating_mul(4)),
                        false,
                        false,
                        false,
                    )
                }
                3 => (10, false, false, true), // hub coalesces update requests
                4 => (8, true, true, false),   // KeyEvent
                5 => (6, true, true, false),   // PointerEvent
                6 => {
                    if self.buffered.len() < 8 {
                        break;
                    }
                    let text_length = u32::from_be_bytes([
                        self.buffered[4],
                        self.buffered[5],
                        self.buffered[6],
                        self.buffered[7],
                    ]) as usize;
                    (8usize.saturating_add(text_length), true, true, false)
                }
                150 => (10, false, false, true), // hub owns continuous updates
                _ => {
                    self.buffered.clear();
                    anyhow::bail!("unsupported RFB client message type {message_type}");
                }
            };
            if message_length > MAX_VNC_FRAME_BYTES {
                self.buffered.clear();
                anyhow::bail!("VNC client message exceeds 1 MiB");
            }
            if self.buffered.len() < message_length {
                break;
            }
            if message_type == 0 {
                anyhow::ensure!(
                    self.buffered[4..20] == self.canonical_pixel_format,
                    "RFB client requested an incompatible shared pixel format"
                );
            }
            if message_type == 2 {
                let supports_raw = self.buffered[4..message_length]
                    .chunks_exact(4)
                    .any(|encoding| encoding == [0, 0, 0, 0]);
                anyhow::ensure!(
                    supports_raw,
                    "RFB client must advertise Raw encoding for shared fan-out"
                );
            }
            let bytes = self.buffered.drain(..message_length).collect();
            parsed.push(ParsedRfbClientMessage {
                bytes,
                human_input,
                forward,
                refresh,
            });
        }
        Ok(parsed)
    }
}

async fn wait_for_shared_hub(hub: &SharedRfbHub) -> anyhow::Result<Arc<RfbServerInit>> {
    let mut status = hub.status.clone();
    loop {
        let current = status.borrow().clone();
        match current {
            SharedRfbHubStatus::Ready(server_init) => return Ok(server_init),
            SharedRfbHubStatus::Failed(detail) => {
                anyhow::bail!("shared remote desktop upstream failed: {detail}")
            }
            SharedRfbHubStatus::Connecting => {}
        }
        tokio::time::timeout(Duration::from_secs(5), status.changed())
            .await
            .context("shared remote desktop upstream handshake timed out")?
            .context("shared remote desktop upstream status closed")?;
    }
}

async fn complete_downstream_rfb_handshake(
    websocket: &mut WebSocketStream<TcpStream>,
    pending: &mut Vec<u8>,
    server_init: &[u8],
) -> anyhow::Result<()> {
    websocket
        .send(Message::Binary(RFB_VERSION_3_8.to_vec()))
        .await?;
    let version = read_downstream_bytes(websocket, pending, 12).await?;
    anyhow::ensure!(
        version == RFB_VERSION_3_8,
        "remote desktop client must use RFB 3.8"
    );

    websocket.send(Message::Binary(vec![1, 1])).await?;
    let security = read_downstream_bytes(websocket, pending, 1).await?;
    anyhow::ensure!(
        security == [1],
        "remote desktop client rejected None security"
    );
    websocket.send(Message::Binary(vec![0, 0, 0, 0])).await?;

    let client_init = read_downstream_bytes(websocket, pending, 1).await?;
    anyhow::ensure!(
        matches!(client_init.as_slice(), [0] | [1]),
        "invalid RFB ClientInit shared flag"
    );
    websocket
        .send(Message::Binary(server_init.to_vec()))
        .await?;
    Ok(())
}

async fn read_downstream_bytes(
    websocket: &mut WebSocketStream<TcpStream>,
    pending: &mut Vec<u8>,
    required: usize,
) -> anyhow::Result<Vec<u8>> {
    while pending.len() < required {
        let message = tokio::time::timeout(Duration::from_secs(5), websocket.next())
            .await
            .context("RFB downstream handshake timed out")?
            .context("RFB downstream closed during handshake")??;
        match message {
            Message::Binary(bytes) => {
                anyhow::ensure!(
                    bytes.len() <= MAX_VNC_FRAME_BYTES,
                    "VNC frame exceeds 1 MiB"
                );
                pending.extend_from_slice(&bytes);
            }
            Message::Ping(payload) => websocket.send(Message::Pong(payload)).await?,
            Message::Pong(_) => {}
            Message::Close(_) => anyhow::bail!("RFB downstream closed during handshake"),
            Message::Text(_) => anyhow::bail!("text WebSocket frames are not accepted"),
            _ => {}
        }
    }
    Ok(pending.drain(..required).collect())
}

async fn run_shared_rfb_hub(
    state: Arc<GatewayState>,
    session_id: &str,
    endpoint: SocketAddr,
    mut task: SharedRfbHubTask,
) -> anyhow::Result<()> {
    let mut vnc = TcpStream::connect(endpoint)
        .await
        .context("registered VNC endpoint is unavailable")?;
    let server_init = Arc::new(perform_upstream_rfb_handshake(&mut vnc).await?);
    let full_framebuffer_request = framebuffer_update_request(&server_init, false);
    let incremental_framebuffer_request = framebuffer_update_request(&server_init, true);
    let mut framebuffer = RfbFramebuffer::new(&server_init)?;
    vnc.write_all(&full_framebuffer_request).await?;
    let initial_frame = Arc::new(read_upstream_server_message(&mut vnc, &server_init).await?);
    framebuffer.apply(&initial_frame)?;
    *task
        .hub
        .latest_frame
        .lock()
        .expect("shared RFB latest frame lock poisoned") = Some(framebuffer.full_update());
    let _ = task.frames.send(initial_frame);
    task.status
        .send_replace(SharedRfbHubStatus::Ready(server_init.clone()));
    state
        .last_server_frame_at
        .lock()
        .expect("frame timestamp lock poisoned")
        .insert(session_id.to_owned(), Instant::now());
    let mut update_request_in_flight = false;

    loop {
        tokio::select! {
            changed = task.shutdown.changed() => {
                if changed.is_err() || *task.shutdown.borrow() {
                    break;
                }
            }
            Some(bytes) = task.input.recv() => {
                vnc.write_all(&bytes).await?;
            }
            Some(()) = task.refresh.recv() => {
                // Every noVNC viewer asks for the next update after consuming the same
                // broadcast frame. They all refer to one shared upstream generation, so
                // collapse them into at most one in-flight request. Forwarding every
                // viewer request would create a feedback multiplier and evict healthy
                // viewers from the bounded broadcast queue.
                if take_coalesced_refresh(&mut task.refresh, &mut update_request_in_flight) {
                    vnc.write_all(&incremental_framebuffer_request).await?;
                }
            }
            message = read_upstream_server_message(&mut vnc, &server_init) => {
                let message = Arc::new(message?);
                if message.first() == Some(&0) {
                    update_request_in_flight = false;
                    framebuffer.apply(&message)?;
                    *task
                        .hub
                        .latest_frame
                        .lock()
                        .expect("shared RFB latest frame lock poisoned") = Some(framebuffer.full_update());
                }
                state
                    .last_server_frame_at
                    .lock()
                    .expect("frame timestamp lock poisoned")
                    .insert(session_id.to_owned(), Instant::now());
                let _ = task.frames.send(message);
            }
        }
    }
    Ok(())
}

fn take_coalesced_refresh(
    refresh: &mut mpsc::Receiver<()>,
    update_request_in_flight: &mut bool,
) -> bool {
    while refresh.try_recv().is_ok() {}
    if *update_request_in_flight {
        false
    } else {
        *update_request_in_flight = true;
        true
    }
}

#[derive(Debug)]
struct RfbFramebuffer {
    width: usize,
    height: usize,
    bytes_per_pixel: usize,
    pixels: Vec<u8>,
}

impl RfbFramebuffer {
    fn new(server_init: &RfbServerInit) -> anyhow::Result<Self> {
        let width = usize::from(server_init.width);
        let height = usize::from(server_init.height);
        let bytes = width
            .checked_mul(height)
            .and_then(|pixels| pixels.checked_mul(server_init.bytes_per_pixel))
            .context("RFB framebuffer byte size overflow")?;
        anyhow::ensure!(
            bytes <= MAX_RFB_SERVER_MESSAGE_BYTES,
            "RFB framebuffer exceeds 32 MiB"
        );
        Ok(Self {
            width,
            height,
            bytes_per_pixel: server_init.bytes_per_pixel,
            pixels: vec![0; bytes],
        })
    }

    fn apply(&mut self, message: &[u8]) -> anyhow::Result<()> {
        anyhow::ensure!(
            message.len() >= 4 && message[0] == 0,
            "not a framebuffer update"
        );
        let rectangles = u16::from_be_bytes([message[2], message[3]]);
        let mut offset = 4;
        for _ in 0..rectangles {
            anyhow::ensure!(
                message.len().saturating_sub(offset) >= 12,
                "truncated RFB rectangle"
            );
            let rectangle = &message[offset..offset + 12];
            offset += 12;
            let x = usize::from(u16::from_be_bytes([rectangle[0], rectangle[1]]));
            let y = usize::from(u16::from_be_bytes([rectangle[2], rectangle[3]]));
            let width = usize::from(u16::from_be_bytes([rectangle[4], rectangle[5]]));
            let height = usize::from(u16::from_be_bytes([rectangle[6], rectangle[7]]));
            let encoding = i32::from_be_bytes(rectangle[8..12].try_into().expect("encoding"));
            anyhow::ensure!(
                encoding == 0,
                "non-Raw rectangle cannot update shared baseline"
            );
            anyhow::ensure!(
                x.checked_add(width)
                    .is_some_and(|right| right <= self.width)
                    && y.checked_add(height)
                        .is_some_and(|bottom| bottom <= self.height),
                "RFB rectangle exceeds framebuffer bounds"
            );
            let row_bytes = width
                .checked_mul(self.bytes_per_pixel)
                .context("RFB rectangle row overflow")?;
            let rectangle_bytes = row_bytes
                .checked_mul(height)
                .context("RFB rectangle byte overflow")?;
            anyhow::ensure!(
                message.len().saturating_sub(offset) >= rectangle_bytes,
                "truncated RFB rectangle pixels"
            );
            for row in 0..height {
                let source = offset + row * row_bytes;
                let target = ((y + row) * self.width + x) * self.bytes_per_pixel;
                self.pixels[target..target + row_bytes]
                    .copy_from_slice(&message[source..source + row_bytes]);
            }
            offset += rectangle_bytes;
        }
        anyhow::ensure!(
            offset == message.len(),
            "RFB framebuffer update has trailing bytes"
        );
        Ok(())
    }

    fn full_update(&self) -> Arc<Vec<u8>> {
        let mut message = Vec::with_capacity(16 + self.pixels.len());
        message.extend_from_slice(&[0, 0, 0, 1, 0, 0, 0, 0]);
        message.extend_from_slice(&(self.width as u16).to_be_bytes());
        message.extend_from_slice(&(self.height as u16).to_be_bytes());
        message.extend_from_slice(&0_i32.to_be_bytes());
        message.extend_from_slice(&self.pixels);
        Arc::new(message)
    }
}

async fn perform_upstream_rfb_handshake(vnc: &mut TcpStream) -> anyhow::Result<RfbServerInit> {
    let mut version = [0_u8; 12];
    vnc.read_exact(&mut version).await?;
    anyhow::ensure!(
        version.starts_with(b"RFB 003.") && version[11] == b'\n',
        "x11vnc returned an invalid RFB version"
    );
    vnc.write_all(RFB_VERSION_3_8).await?;

    let security_count = vnc.read_u8().await? as usize;
    anyhow::ensure!(
        security_count > 0,
        "x11vnc rejected RFB security negotiation"
    );
    anyhow::ensure!(
        security_count <= 32,
        "x11vnc returned too many security types"
    );
    let mut security_types = vec![0_u8; security_count];
    vnc.read_exact(&mut security_types).await?;
    anyhow::ensure!(
        security_types.contains(&1),
        "x11vnc must expose None security behind the loopback gateway"
    );
    vnc.write_u8(1).await?;
    anyhow::ensure!(
        vnc.read_u32().await? == 0,
        "x11vnc security handshake failed"
    );
    vnc.write_u8(1).await?;

    let width = vnc.read_u16().await?;
    let height = vnc.read_u16().await?;
    anyhow::ensure!(
        width > 0 && height > 0,
        "x11vnc returned an empty framebuffer"
    );
    let mut upstream_pixel_format = [0_u8; 16];
    vnc.read_exact(&mut upstream_pixel_format).await?;
    let name_length = vnc.read_u32().await? as usize;
    anyhow::ensure!(name_length <= 4096, "x11vnc desktop name is too large");
    let mut name = vec![0_u8; name_length];
    vnc.read_exact(&mut name).await?;

    let mut set_pixel_format = [0_u8; 20];
    set_pixel_format[4..].copy_from_slice(&SHARED_PIXEL_FORMAT);
    vnc.write_all(&set_pixel_format).await?;
    // Raw is deliberately chosen: it has complete, deterministic message boundaries, so a
    // late subscriber never depends on compression state owned by an earlier viewer.
    vnc.write_all(&[2, 0, 0, 1, 0, 0, 0, 0]).await?;

    let mut wire_bytes = Vec::with_capacity(24 + name.len());
    wire_bytes.extend_from_slice(&width.to_be_bytes());
    wire_bytes.extend_from_slice(&height.to_be_bytes());
    wire_bytes.extend_from_slice(&SHARED_PIXEL_FORMAT);
    wire_bytes.extend_from_slice(&(name.len() as u32).to_be_bytes());
    wire_bytes.extend_from_slice(&name);
    Ok(RfbServerInit {
        wire_bytes,
        width,
        height,
        pixel_format: SHARED_PIXEL_FORMAT,
        bytes_per_pixel: 4,
    })
}

fn framebuffer_update_request(server_init: &RfbServerInit, incremental: bool) -> [u8; 10] {
    let width = server_init.width.to_be_bytes();
    let height = server_init.height.to_be_bytes();
    [
        3,
        u8::from(incremental),
        0,
        0,
        0,
        0,
        width[0],
        width[1],
        height[0],
        height[1],
    ]
}

async fn read_upstream_server_message(
    vnc: &mut TcpStream,
    server_init: &RfbServerInit,
) -> anyhow::Result<Vec<u8>> {
    let message_type = vnc.read_u8().await?;
    let mut message = vec![message_type];
    match message_type {
        0 => read_framebuffer_update(vnc, server_init, &mut message).await?,
        2 => {} // Bell
        3 => {
            let mut header = [0_u8; 7];
            vnc.read_exact(&mut header).await?;
            message.extend_from_slice(&header);
            let length =
                u32::from_be_bytes(header[3..7].try_into().expect("four-byte length")) as usize;
            anyhow::ensure!(length <= MAX_VNC_FRAME_BYTES, "ServerCutText exceeds 1 MiB");
            read_exact_bounded(vnc, &mut message, length).await?;
        }
        _ => anyhow::bail!("unsupported RFB server message type {message_type}"),
    }
    Ok(message)
}

async fn read_framebuffer_update(
    vnc: &mut TcpStream,
    server_init: &RfbServerInit,
    message: &mut Vec<u8>,
) -> anyhow::Result<()> {
    let mut header = [0_u8; 3];
    vnc.read_exact(&mut header).await?;
    message.extend_from_slice(&header);
    let rectangles = u16::from_be_bytes([header[1], header[2]]);
    for _ in 0..rectangles {
        let mut rectangle = [0_u8; 12];
        vnc.read_exact(&mut rectangle).await?;
        message.extend_from_slice(&rectangle);
        let width = usize::from(u16::from_be_bytes([rectangle[4], rectangle[5]]));
        let height = usize::from(u16::from_be_bytes([rectangle[6], rectangle[7]]));
        let encoding = i32::from_be_bytes(rectangle[8..12].try_into().expect("four-byte encoding"));
        anyhow::ensure!(encoding == 0, "x11vnc violated negotiated Raw encoding");
        let bytes = width
            .checked_mul(height)
            .and_then(|pixels| pixels.checked_mul(server_init.bytes_per_pixel))
            .context("RFB rectangle byte size overflow")?;
        read_exact_bounded(vnc, message, bytes).await?;
    }
    Ok(())
}

async fn read_exact_bounded(
    vnc: &mut TcpStream,
    message: &mut Vec<u8>,
    bytes: usize,
) -> anyhow::Result<()> {
    anyhow::ensure!(
        message.len().saturating_add(bytes) <= MAX_RFB_SERVER_MESSAGE_BYTES,
        "RFB server message exceeds 32 MiB"
    );
    let offset = message.len();
    message.resize(offset + bytes, 0);
    vnc.read_exact(&mut message[offset..]).await?;
    Ok(())
}

fn authorize(
    state: &GatewayState,
    request: &Request,
) -> Result<AuthorizedConnection, HandshakeRejection> {
    validate_origin(state, request)?;
    let session_id = request
        .uri()
        .path()
        .strip_prefix("/desktop/v1/sessions/")
        .filter(|value| !value.contains('/'))
        .ok_or_else(|| rejection(StatusCode::NOT_FOUND, "unknown remote desktop route"))?;
    validate_session_id(session_id)
        .map_err(|_| rejection(StatusCode::BAD_REQUEST, "invalid session id"))?;
    let ticket = request
        .uri()
        .query()
        .and_then(|query| {
            query.split('&').find_map(|part| {
                part.strip_prefix("ticket=")
                    .filter(|ticket| !ticket.is_empty())
            })
        })
        .ok_or_else(|| rejection(StatusCode::UNAUTHORIZED, "connection ticket is required"))?;
    let mut claims = verify_ticket(&state.ticket_secret, ticket)
        .map_err(|_| rejection(StatusCode::UNAUTHORIZED, "connection ticket is invalid"))?;
    // Rolling upgrades can still deliver an older explicitly signed takeover ticket. Desktop
    // connectivity is no longer an exclusive ownership primitive: normalize it before admission
    // so it cannot revoke viewers or disconnect the Agent-side workflow.
    if claims.access_mode == "EXCLUSIVE_TAKEOVER" {
        claims.access_mode = "COLLABORATIVE".to_owned();
    }
    if claims.connection_id.is_empty() {
        let nonce_hash = format!("{:x}", Sha256::digest(claims.nonce.as_bytes()));
        claims.connection_id = format!("rdc_{}", &nonce_hash[..20]);
    }
    if claims.session_id != session_id {
        return Err(rejection(
            StatusCode::FORBIDDEN,
            "connection ticket session mismatch",
        ));
    }
    let now = unix_seconds();
    if claims.expires_at_epoch_seconds <= now
        || claims.expires_at_epoch_seconds > now + MAX_TICKET_LIFETIME_SECONDS
    {
        return Err(rejection(
            StatusCode::UNAUTHORIZED,
            "connection ticket is expired",
        ));
    }
    if claims.tenant_id.is_empty()
        || claims.actor_id.is_empty()
        || !claims.connection_id.starts_with("rdc_")
        || claims.connection_id.len() != 24
        || !claims
            .connection_id
            .chars()
            .all(|character| character.is_ascii_alphanumeric() || character == '_')
        || claims.coordinator_term < 0
        || claims.context_epoch <= 0
        || claims.operation_epoch == 0
        || claims.operation_epoch > i64::MAX as u64
        || !matches!(
            claims.access_mode.as_str(),
            "COLLABORATIVE" | "EXCLUSIVE_TAKEOVER"
        )
        || !(250..=100_000).contains(&claims.actor_bitrate_limit_kbps)
        || !(1..=60).contains(&claims.actor_frame_rate_limit_fps)
        || claims.nonce.len() < 16
        || claims.nonce.len() > 128
    {
        return Err(rejection(
            StatusCode::UNAUTHORIZED,
            "connection ticket claims are invalid",
        ));
    }
    let vnc_endpoint = state
        .vnc_endpoints
        .read()
        .expect("VNC endpoint lock poisoned")
        .get(session_id)
        .copied()
        .ok_or_else(|| {
            rejection(
                StatusCode::SERVICE_UNAVAILABLE,
                "remote desktop is not ready",
            )
        })?;

    {
        let mut nonces = state.used_nonces.lock().expect("nonce lock poisoned");
        nonces.retain(|_, expires_at| *expires_at > now);
        if nonces.contains_key(&claims.nonce) {
            return Err(rejection(
                StatusCode::CONFLICT,
                "connection ticket was already used",
            ));
        }
        nonces.insert(claims.nonce.clone(), claims.expires_at_epoch_seconds);
    }
    {
        let mut sessions = state
            .active_connections
            .lock()
            .expect("active connection lock poisoned");
        let connections = sessions.entry(session_id.to_owned()).or_default();
        if connections.leases_by_connection_id.len() >= MAX_ACTIVE_CONNECTIONS_PER_SESSION {
            return Err(rejection(
                StatusCode::TOO_MANY_REQUESTS,
                "remote desktop collaborator limit reached",
            ));
        }
        let (revoke, revocation) = watch::channel(false);
        connections.leases_by_connection_id.insert(
            claims.connection_id.clone(),
            ConnectionLease {
                claims: claims.clone(),
                revoke,
            },
        );
        connections.generation = connections.generation.saturating_add(1);
        Ok(AuthorizedConnection {
            claims,
            vnc_endpoint,
            revocation,
        })
    }
}

fn validate_origin(state: &GatewayState, request: &Request) -> Result<(), HandshakeRejection> {
    if state.allowed_origins.is_empty() {
        return Ok(());
    }
    let origin = request
        .headers()
        .get(ORIGIN)
        .and_then(|value| value.to_str().ok())
        .ok_or_else(|| rejection(StatusCode::FORBIDDEN, "WebSocket origin is required"))?;
    if !state.allowed_origins.contains(origin) {
        return Err(rejection(
            StatusCode::FORBIDDEN,
            "WebSocket origin is not allowed",
        ));
    }
    Ok(())
}

fn verify_ticket(secret: &[u8], ticket: &str) -> anyhow::Result<RemoteDesktopTicketClaims> {
    let (payload, signature) = ticket
        .split_once('.')
        .ok_or_else(|| anyhow::anyhow!("ticket must contain payload and signature"))?;
    let signature = URL_SAFE_NO_PAD.decode(signature)?;
    let mut mac = HmacSha256::new_from_slice(secret)?;
    mac.update(payload.as_bytes());
    mac.verify_slice(&signature)?;
    let payload = URL_SAFE_NO_PAD.decode(payload)?;
    Ok(serde_json::from_slice(&payload)?)
}

fn validate_session_id(session_id: &str) -> anyhow::Result<()> {
    anyhow::ensure!(
        session_id.starts_with("ses_")
            && session_id.len() <= 128
            && session_id
                .chars()
                .all(|character| character.is_ascii_alphanumeric() || character == '_'),
        "invalid session id"
    );
    Ok(())
}

fn unix_seconds() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs()
}

fn rejection(status: StatusCode, message: &'static str) -> HandshakeRejection {
    HandshakeRejection { status, message }
}

#[cfg(test)]
mod tests {
    use super::*;
    use futures_util::{SinkExt, StreamExt};
    use std::sync::atomic::{AtomicUsize, Ordering};
    use tokio::sync::oneshot;
    use tokio_tungstenite::connect_async;
    use tokio_tungstenite::tungstenite::client::IntoClientRequest;

    const SECRET: &str = "test-remote-desktop-ticket-secret-32-bytes";

    struct CountDisconnects(AtomicUsize);

    #[async_trait]
    impl DisconnectHandler for CountDisconnects {
        async fn disconnected(&self, _claims: &RemoteDesktopTicketClaims) {
            self.0.fetch_add(1, Ordering::SeqCst);
        }
    }

    fn ticket(session_id: &str, nonce: &str) -> String {
        ticket_with_options(session_id, nonce, "COLLABORATIVE", false)
    }

    fn ticket_with_mode(session_id: &str, nonce: &str, access_mode: &str) -> String {
        ticket_with_options(session_id, nonce, access_mode, false)
    }

    fn ticket_with_options(
        session_id: &str,
        nonce: &str,
        access_mode: &str,
        view_only: bool,
    ) -> String {
        let connection_hash = format!("{:x}", Sha256::digest(nonce.as_bytes()));
        let claims = RemoteDesktopTicketClaims {
            tenant_id: "tenant-test".to_owned(),
            session_id: session_id.to_owned(),
            actor_id: "user-test".to_owned(),
            connection_id: format!("rdc_{}", &connection_hash[..20]),
            coordinator_term: 3,
            context_epoch: 4,
            operation_epoch: 7,
            access_mode: access_mode.to_owned(),
            view_only,
            actor_bitrate_limit_kbps: DEFAULT_ACTOR_BITRATE_LIMIT_KBPS,
            actor_frame_rate_limit_fps: DEFAULT_ACTOR_FRAME_RATE_LIMIT_FPS,
            expires_at_epoch_seconds: unix_seconds() + 60,
            nonce: nonce.to_owned(),
        };
        let payload = URL_SAFE_NO_PAD.encode(serde_json::to_vec(&claims).unwrap());
        let mut mac = HmacSha256::new_from_slice(SECRET.as_bytes()).unwrap();
        mac.update(payload.as_bytes());
        let signature = URL_SAFE_NO_PAD.encode(mac.finalize().into_bytes());
        format!("{payload}.{signature}")
    }

    async fn accept_test_rfb_upstream(
        listener: TcpListener,
        accepted: Arc<AtomicUsize>,
        input_sender: Option<oneshot::Sender<Vec<u8>>>,
        mut release_second_frame: Option<oneshot::Receiver<()>>,
    ) {
        let (mut stream, _) = listener.accept().await.unwrap();
        accepted.fetch_add(1, Ordering::SeqCst);
        stream.write_all(RFB_VERSION_3_8).await.unwrap();
        let mut version = [0_u8; 12];
        stream.read_exact(&mut version).await.unwrap();
        assert_eq!(&version, RFB_VERSION_3_8);
        stream.write_all(&[1, 1]).await.unwrap();
        assert_eq!(stream.read_u8().await.unwrap(), 1);
        stream.write_u32(0).await.unwrap();
        assert_eq!(stream.read_u8().await.unwrap(), 1);
        let pixel_format = [32, 24, 0, 1, 0, 255, 0, 255, 0, 255, 16, 8, 0, 0, 0, 0];
        stream.write_u16(1).await.unwrap();
        stream.write_u16(1).await.unwrap();
        stream.write_all(&pixel_format).await.unwrap();
        stream.write_u32(4).await.unwrap();
        stream.write_all(b"test").await.unwrap();

        let mut set_pixel_format = [0_u8; 20];
        stream.read_exact(&mut set_pixel_format).await.unwrap();
        assert_eq!(set_pixel_format[0], 0);
        assert_eq!(&set_pixel_format[4..], &SHARED_PIXEL_FORMAT);
        let mut set_encodings = [0_u8; 8];
        stream.read_exact(&mut set_encodings).await.unwrap();
        assert_eq!(set_encodings, [2, 0, 0, 1, 0, 0, 0, 0]);
        let mut update_request = [0_u8; 10];
        stream.read_exact(&mut update_request).await.unwrap();
        assert_eq!(update_request[0], 3);
        assert_eq!(update_request[1], 0);
        stream
            .write_all(&test_raw_framebuffer_update([1, 2, 3, 4]))
            .await
            .unwrap();

        let mut input_sender = input_sender;
        while let Ok(message_type) = stream.read_u8().await {
            match message_type {
                3 => {
                    let mut request = [0_u8; 9];
                    stream.read_exact(&mut request).await.unwrap();
                    if request[0] == 0 && release_second_frame.is_some() {
                        let release = release_second_frame
                            .as_mut()
                            .expect("guarded second-frame release");
                        if release.await.is_ok() {
                            stream
                                .write_all(&test_raw_framebuffer_update([5, 6, 7, 8]))
                                .await
                                .unwrap();
                        }
                        release_second_frame = None;
                    } else if request[0] == 0 {
                        stream
                            .write_all(&test_raw_framebuffer_update([1, 2, 3, 4]))
                            .await
                            .unwrap();
                    }
                }
                4 => {
                    let mut message = vec![message_type; 8];
                    stream.read_exact(&mut message[1..]).await.unwrap();
                    if let Some(sender) = input_sender.take() {
                        sender.send(message).unwrap();
                    }
                }
                other => panic!("unexpected test RFB client message {other}"),
            }
        }
    }

    fn test_raw_framebuffer_update(pixel: [u8; 4]) -> Vec<u8> {
        let mut message = vec![0, 0, 0, 1];
        message.extend_from_slice(&[0, 0, 0, 0, 0, 1, 0, 1]);
        message.extend_from_slice(&0_i32.to_be_bytes());
        message.extend_from_slice(&pixel);
        message
    }

    #[test]
    fn merges_incremental_raw_rectangles_into_late_join_baseline() {
        let server_init = RfbServerInit {
            wire_bytes: Vec::new(),
            width: 2,
            height: 1,
            pixel_format: SHARED_PIXEL_FORMAT,
            bytes_per_pixel: 4,
        };
        let mut framebuffer = RfbFramebuffer::new(&server_init).unwrap();
        let mut full = vec![0, 0, 0, 1, 0, 0, 0, 0, 0, 2, 0, 1];
        full.extend_from_slice(&0_i32.to_be_bytes());
        full.extend_from_slice(&[1, 2, 3, 4, 5, 6, 7, 8]);
        framebuffer.apply(&full).unwrap();

        let mut incremental = vec![0, 0, 0, 1, 0, 1, 0, 0, 0, 1, 0, 1];
        incremental.extend_from_slice(&0_i32.to_be_bytes());
        incremental.extend_from_slice(&[9, 10, 11, 12]);
        framebuffer.apply(&incremental).unwrap();

        assert_eq!(
            &framebuffer.full_update()[16..],
            &[1, 2, 3, 4, 9, 10, 11, 12]
        );
    }

    #[test]
    fn bounded_fanout_reports_lag_without_blocking_other_subscribers() {
        let (frames, _) = broadcast::channel(2);
        let mut slow = frames.subscribe();
        let mut current = frames.subscribe();
        for value in 0..3_u8 {
            frames.send(Arc::new(vec![value])).unwrap();
            assert_eq!(&**current.try_recv().unwrap(), &[value]);
        }
        assert!(matches!(
            slow.try_recv(),
            Err(broadcast::error::TryRecvError::Lagged(1))
        ));
        assert_eq!(&**slow.try_recv().unwrap(), &[1]);
    }

    #[test]
    fn coalesces_viewer_refreshes_into_one_in_flight_upstream_request() {
        let (sender, mut receiver) = mpsc::channel(4);
        sender.try_send(()).unwrap();
        sender.try_send(()).unwrap();
        sender.try_send(()).unwrap();
        let mut in_flight = false;

        // The select branch consumed one signal; the helper drains all additional
        // viewer requests and authorizes only one upstream update request.
        receiver.try_recv().unwrap();
        assert!(take_coalesced_refresh(&mut receiver, &mut in_flight));
        assert!(receiver.try_recv().is_err());

        sender.try_send(()).unwrap();
        receiver.try_recv().unwrap();
        assert!(!take_coalesced_refresh(&mut receiver, &mut in_flight));

        in_flight = false;
        sender.try_send(()).unwrap();
        receiver.try_recv().unwrap();
        assert!(take_coalesced_refresh(&mut receiver, &mut in_flight));
    }

    async fn complete_test_rfb_client(
        websocket: &mut WebSocketStream<tokio_tungstenite::MaybeTlsStream<TcpStream>>,
    ) -> Vec<u8> {
        assert_eq!(next_binary(websocket).await, RFB_VERSION_3_8);
        websocket
            .send(Message::Binary(RFB_VERSION_3_8.to_vec()))
            .await
            .unwrap();
        assert_eq!(next_binary(websocket).await, [1, 1]);
        websocket.send(Message::Binary(vec![1])).await.unwrap();
        assert_eq!(next_binary(websocket).await, [0, 0, 0, 0]);
        websocket.send(Message::Binary(vec![1])).await.unwrap();
        next_binary(websocket).await
    }

    async fn next_binary(
        websocket: &mut WebSocketStream<tokio_tungstenite::MaybeTlsStream<TcpStream>>,
    ) -> Vec<u8> {
        loop {
            match websocket.next().await.unwrap().unwrap() {
                Message::Binary(payload) => return payload,
                Message::Ping(payload) => websocket.send(Message::Pong(payload)).await.unwrap(),
                other => panic!("unexpected WebSocket message: {other:?}"),
            }
        }
    }

    #[test]
    fn detects_real_human_rfb_input_but_not_observer_protocol_messages() {
        let pixel_format = SHARED_PIXEL_FORMAT;
        let mut parser = RfbClientMessageParser::new(Vec::new(), pixel_format);
        let messages = parser.ingest(&[3, 0, 0, 0, 0, 0, 0, 100, 0, 100]).unwrap();
        assert_eq!(messages.len(), 1);
        assert!(!messages[0].human_input);
        assert!(!messages[0].forward);
        assert!(messages[0].refresh);

        let messages = parser.ingest(&[4, 1, 0, 0, 0, 0, 0, 65]).unwrap();
        assert_eq!(messages.len(), 1);
        assert!(messages[0].human_input);
        assert!(messages[0].forward);
        assert!(!messages[0].refresh);

        assert!(parser.ingest(&[5, 1, 0]).unwrap().is_empty());
        let messages = parser.ingest(&[10, 0, 20]).unwrap();
        assert_eq!(messages.len(), 1);
        assert!(messages[0].human_input);
        assert!(messages[0].forward);
    }

    #[test]
    fn shared_fanout_rejects_incompatible_pixel_format_and_encoding() {
        let pixel_format = SHARED_PIXEL_FORMAT;
        let mut parser = RfbClientMessageParser::new(Vec::new(), pixel_format);
        let mut incompatible = vec![0, 0, 0, 0];
        incompatible.extend_from_slice(&[16, 16, 0, 1, 0, 31, 0, 63, 0, 31, 11, 5, 0, 0, 0, 0]);
        assert!(parser.ingest(&incompatible).is_err());

        let mut parser = RfbClientMessageParser::new(Vec::new(), pixel_format);
        assert!(parser.ingest(&[2, 0, 0, 1, 0, 0, 0, 6]).is_err());
        let mut parser = RfbClientMessageParser::new(Vec::new(), pixel_format);
        assert!(parser.ingest(&[2, 0, 0, 2, 0, 0, 0, 6, 0, 0, 0, 0]).is_ok());
    }

    #[test]
    fn legacy_ticket_without_access_mode_defaults_to_collaboration() {
        let claims = serde_json::json!({
            "tenantId": "tenant-test",
            "sessionId": "ses_legacy123456789",
            "actorId": "user-test",
            "coordinatorTerm": 3,
            "contextEpoch": 4,
            "operationEpoch": 4,
            "expiresAtEpochSeconds": unix_seconds() + 60,
            "nonce": "legacy-collaborative-ticket-123"
        });

        let parsed: RemoteDesktopTicketClaims = serde_json::from_value(claims).unwrap();

        assert_eq!(parsed.access_mode, "COLLABORATIVE");
        assert!(!parsed.view_only);
        assert_eq!(
            parsed.actor_bitrate_limit_kbps,
            DEFAULT_ACTOR_BITRATE_LIMIT_KBPS
        );
        assert_eq!(
            parsed.actor_frame_rate_limit_fps,
            DEFAULT_ACTOR_FRAME_RATE_LIMIT_FPS
        );
    }

    #[test]
    fn actor_forwarding_quota_is_shared_per_actor_but_isolated_between_actors() {
        let gateway = RemoteDesktopGateway::new(
            SECRET.as_bytes(),
            ["http://console.test".to_owned()],
            Arc::new(NoopDisconnectHandler),
        )
        .unwrap();
        gateway
            .register_session("ses_actorquota123456", "127.0.0.1:5901".parse().unwrap())
            .unwrap();
        let mut first = RemoteDesktopTicketClaims {
            tenant_id: "tenant-test".to_owned(),
            session_id: "ses_actorquota123456".to_owned(),
            actor_id: "actor-one".to_owned(),
            connection_id: "rdc_1234567890abcdefghij".to_owned(),
            coordinator_term: 1,
            context_epoch: 1,
            operation_epoch: 1,
            access_mode: "COLLABORATIVE".to_owned(),
            view_only: true,
            actor_bitrate_limit_kbps: 250,
            actor_frame_rate_limit_fps: 60,
            expires_at_epoch_seconds: unix_seconds() + 60,
            nonce: "actor-quota-first-ticket".to_owned(),
        };
        let first_delay = gateway.reserve_server_forwarding(&first, 1_000);
        let same_actor_delay = gateway.reserve_server_forwarding(&first, 1_000);
        first.actor_id = "actor-two".to_owned();
        first.connection_id = "rdc_abcdefghij1234567890".to_owned();
        let other_actor_delay = gateway.reserve_server_forwarding(&first, 1_000);

        assert!(first_delay >= Duration::from_millis(30));
        assert!(same_actor_delay >= Duration::from_millis(60));
        assert!(other_actor_delay < same_actor_delay);
    }

    #[test]
    fn usage_counters_are_monotonic_and_removed_only_after_final_reporting() {
        let gateway = RemoteDesktopGateway::new(
            SECRET.as_bytes(),
            ["http://console.test".to_owned()],
            Arc::new(NoopDisconnectHandler),
        )
        .unwrap();
        let connection_id = "rdc_1234567890abcdefghij";

        gateway.record_server_forwarded(connection_id, 1_024, Duration::from_millis(25));
        gateway.record_server_forwarded(connection_id, 2_048, Duration::ZERO);

        assert_eq!(
            gateway.connection_usage(connection_id),
            RemoteDesktopUsageCounters {
                forwarded_bytes: 3_072,
                quota_wait_millis: 25,
                throttled_batches: 1,
            }
        );
        assert_eq!(
            gateway.take_connection_usage(connection_id).forwarded_bytes,
            3_072
        );
        assert_eq!(
            gateway.connection_usage(connection_id),
            RemoteDesktopUsageCounters::default()
        );
    }

    #[tokio::test]
    async fn proxies_binary_vnc_stream_and_runs_disconnect_barrier() {
        let vnc_listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let vnc_endpoint = vnc_listener.local_addr().unwrap();
        let upstream_connections = Arc::new(AtomicUsize::new(0));
        tokio::spawn(accept_test_rfb_upstream(
            vnc_listener,
            upstream_connections.clone(),
            None,
            None,
        ));

        let disconnects = Arc::new(CountDisconnects(AtomicUsize::new(0)));
        let gateway = RemoteDesktopGateway::with_disconnect_grace(
            SECRET.as_bytes(),
            ["http://console.test".to_owned()],
            disconnects.clone(),
            Duration::from_millis(10),
        )
        .unwrap();
        gateway
            .register_session("ses_test1234567890", vnc_endpoint)
            .unwrap();
        assert_eq!(gateway.bitrate_limit_kbps("ses_test1234567890"), Some(0));
        assert_eq!(
            gateway
                .set_bitrate_limit("ses_test1234567890", 8_000)
                .unwrap(),
            0
        );
        assert_eq!(
            gateway.bitrate_limit_kbps("ses_test1234567890"),
            Some(8_000)
        );
        assert!(gateway
            .set_bitrate_limit("ses_test1234567890", 100)
            .is_err());
        let gateway_listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let gateway_endpoint = gateway_listener.local_addr().unwrap();
        tokio::spawn(gateway.clone().serve(gateway_listener));

        let nonce = uuid::Uuid::new_v4().simple().to_string();
        let mut request = format!(
            "ws://{gateway_endpoint}/desktop/v1/sessions/ses_test1234567890?ticket={}",
            ticket("ses_test1234567890", &nonce)
        )
        .into_client_request()
        .unwrap();
        request
            .headers_mut()
            .insert(ORIGIN, "http://console.test".parse().unwrap());
        request
            .headers_mut()
            .insert(SEC_WEBSOCKET_PROTOCOL, "binary".parse().unwrap());
        let (mut websocket, response) = connect_async(request).await.unwrap();
        assert_eq!(
            response.headers().get(SEC_WEBSOCKET_PROTOCOL).unwrap(),
            "binary"
        );
        let server_init = complete_test_rfb_client(&mut websocket).await;
        assert_eq!(&server_init[0..4], &[0, 1, 0, 1]);
        assert_eq!(
            tokio::time::timeout(Duration::from_secs(1), next_binary(&mut websocket))
                .await
                .unwrap(),
            test_raw_framebuffer_update([1, 2, 3, 4])
        );
        assert_eq!(upstream_connections.load(Ordering::SeqCst), 1);
        assert!(
            gateway
                .frame_age_ms("ses_test1234567890")
                .is_some_and(|age| age < 1_000),
            "active VNC traffic must expose a recent server-frame timestamp"
        );
        websocket.close(None).await.unwrap();

        tokio::time::timeout(std::time::Duration::from_secs(1), async {
            while disconnects.0.load(Ordering::SeqCst) != 1 {
                tokio::task::yield_now().await;
            }
        })
        .await
        .unwrap();
        assert_eq!(gateway.frame_age_ms("ses_test1234567890"), None);
        gateway.unregister_session("ses_test1234567890");
        assert_eq!(gateway.bitrate_limit_kbps("ses_test1234567890"), None);
    }

    #[tokio::test]
    async fn keeps_multiple_collaborators_connected_and_releases_after_the_last_disconnect() {
        let vnc_listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let vnc_endpoint = vnc_listener.local_addr().unwrap();
        let upstream_connections = Arc::new(AtomicUsize::new(0));
        let (input_sender, input_receiver) = oneshot::channel();
        tokio::spawn(accept_test_rfb_upstream(
            vnc_listener,
            upstream_connections.clone(),
            Some(input_sender),
            None,
        ));

        let disconnects = Arc::new(CountDisconnects(AtomicUsize::new(0)));
        let gateway = RemoteDesktopGateway::with_disconnect_grace(
            SECRET.as_bytes(),
            ["http://console.test".to_owned()],
            disconnects.clone(),
            Duration::from_millis(40),
        )
        .unwrap();
        gateway
            .register_session("ses_multiviewer12345", vnc_endpoint)
            .unwrap();
        let gateway_listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let gateway_endpoint = gateway_listener.local_addr().unwrap();
        tokio::spawn(gateway.clone().serve(gateway_listener));

        let make_request = || {
            let nonce = uuid::Uuid::new_v4().simple().to_string();
            let mut request = format!(
                "ws://{gateway_endpoint}/desktop/v1/sessions/ses_multiviewer12345?ticket={}",
                ticket("ses_multiviewer12345", &nonce)
            )
            .into_client_request()
            .unwrap();
            request
                .headers_mut()
                .insert(ORIGIN, "http://console.test".parse().unwrap());
            request
        };
        let (mut first, _) = connect_async(make_request()).await.unwrap();
        complete_test_rfb_client(&mut first).await;
        assert_eq!(
            next_binary(&mut first).await,
            test_raw_framebuffer_update([1, 2, 3, 4])
        );
        let (mut second, _) = connect_async(make_request()).await.unwrap();
        complete_test_rfb_client(&mut second).await;
        assert_eq!(
            next_binary(&mut second).await,
            test_raw_framebuffer_update([1, 2, 3, 4])
        );
        assert_eq!(gateway.active_connection_count("ses_multiviewer12345"), 2);
        assert_eq!(upstream_connections.load(Ordering::SeqCst), 1);

        first
            .send(Message::Binary(vec![4, 1, 0, 0, 0, 0, 0, 65]))
            .await
            .unwrap();
        assert_eq!(
            tokio::time::timeout(Duration::from_secs(1), input_receiver)
                .await
                .unwrap()
                .unwrap(),
            vec![4, 1, 0, 0, 0, 0, 0, 65]
        );

        first.close(None).await.unwrap();
        tokio::time::sleep(Duration::from_millis(80)).await;
        assert_eq!(gateway.active_connection_count("ses_multiviewer12345"), 1);
        assert_eq!(disconnects.0.load(Ordering::SeqCst), 0);
        assert!(gateway.frame_age_ms("ses_multiviewer12345").is_some());

        second.close(None).await.unwrap();
        tokio::time::timeout(Duration::from_secs(1), async {
            while disconnects.0.load(Ordering::SeqCst) != 1 {
                tokio::task::yield_now().await;
            }
        })
        .await
        .unwrap();
        assert_eq!(gateway.active_connection_count("ses_multiviewer12345"), 0);
        assert_eq!(gateway.frame_age_ms("ses_multiviewer12345"), None);
    }

    #[tokio::test]
    async fn rejects_human_input_on_a_server_enforced_view_only_connection() {
        let vnc_listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let vnc_endpoint = vnc_listener.local_addr().unwrap();
        let (input_sender, input_receiver) = oneshot::channel();
        tokio::spawn(accept_test_rfb_upstream(
            vnc_listener,
            Arc::new(AtomicUsize::new(0)),
            Some(input_sender),
            None,
        ));

        let gateway = RemoteDesktopGateway::new(
            SECRET.as_bytes(),
            ["http://console.test".to_owned()],
            Arc::new(NoopDisconnectHandler),
        )
        .unwrap();
        gateway
            .register_session("ses_viewonly1234567", vnc_endpoint)
            .unwrap();
        let gateway_listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let gateway_endpoint = gateway_listener.local_addr().unwrap();
        tokio::spawn(gateway.clone().serve(gateway_listener));

        let nonce = uuid::Uuid::new_v4().simple().to_string();
        let mut request = format!(
            "ws://{gateway_endpoint}/desktop/v1/sessions/ses_viewonly1234567?ticket={}",
            ticket_with_options("ses_viewonly1234567", &nonce, "COLLABORATIVE", true)
        )
        .into_client_request()
        .unwrap();
        request
            .headers_mut()
            .insert(ORIGIN, "http://console.test".parse().unwrap());
        let (mut websocket, _) = connect_async(request).await.unwrap();
        complete_test_rfb_client(&mut websocket).await;
        next_binary(&mut websocket).await;
        let handshake_and_key = vec![4, 1, 0, 0, 0, 0, 0, 65];
        websocket
            .send(Message::Binary(handshake_and_key))
            .await
            .unwrap();

        assert!(
            tokio::time::timeout(Duration::from_millis(100), input_receiver)
                .await
                .is_err(),
            "view-only Human Input must never reach the shared x11vnc upstream"
        );
        tokio::time::timeout(Duration::from_secs(1), async {
            while gateway.active_connection_count("ses_viewonly1234567") != 0 {
                tokio::task::yield_now().await;
            }
        })
        .await
        .unwrap();
    }

    #[tokio::test]
    async fn revokes_only_the_selected_collaborator() {
        let vnc_listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let vnc_endpoint = vnc_listener.local_addr().unwrap();
        let upstream_connections = Arc::new(AtomicUsize::new(0));
        let (input_sender, input_receiver) = oneshot::channel();
        tokio::spawn(accept_test_rfb_upstream(
            vnc_listener,
            upstream_connections.clone(),
            Some(input_sender),
            None,
        ));

        let gateway = RemoteDesktopGateway::new(
            SECRET.as_bytes(),
            ["http://console.test".to_owned()],
            Arc::new(NoopDisconnectHandler),
        )
        .unwrap();
        let session_id = "ses_exactrevoke12345";
        gateway.register_session(session_id, vnc_endpoint).unwrap();
        let gateway_listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let gateway_endpoint = gateway_listener.local_addr().unwrap();
        tokio::spawn(gateway.clone().serve(gateway_listener));

        let first_nonce = "first-participant-1234567890";
        let second_nonce = "second-participant-123456789";
        let connect = |nonce: &str| {
            let mut request = format!(
                "ws://{gateway_endpoint}/desktop/v1/sessions/{session_id}?ticket={}",
                ticket(session_id, nonce)
            )
            .into_client_request()
            .unwrap();
            request
                .headers_mut()
                .insert(ORIGIN, "http://console.test".parse().unwrap());
            request
        };
        let (mut first, _) = connect_async(connect(first_nonce)).await.unwrap();
        complete_test_rfb_client(&mut first).await;
        next_binary(&mut first).await;
        let (mut second, _) = connect_async(connect(second_nonce)).await.unwrap();
        complete_test_rfb_client(&mut second).await;
        next_binary(&mut second).await;
        assert_eq!(upstream_connections.load(Ordering::SeqCst), 1);
        let first_hash = format!("{:x}", Sha256::digest(first_nonce.as_bytes()));
        let first_connection_id = format!("rdc_{}", &first_hash[..20]);
        assert!(gateway
            .revoke_connection(session_id, &first_connection_id)
            .is_some());
        tokio::time::timeout(Duration::from_secs(1), async {
            while gateway.active_connection_count(session_id) != 1 {
                tokio::task::yield_now().await;
            }
        })
        .await
        .unwrap();

        second
            .send(Message::Binary(vec![4, 1, 0, 0, 0, 0, 0, 66]))
            .await
            .unwrap();
        assert_eq!(
            tokio::time::timeout(Duration::from_secs(1), input_receiver)
                .await
                .unwrap()
                .unwrap(),
            vec![4, 1, 0, 0, 0, 0, 0, 66]
        );
        assert!(gateway
            .revoke_connection(session_id, "rdc_missing0000000000000")
            .is_none());
        let _ = first.next().await;
        second.close(None).await.unwrap();
    }

    #[tokio::test]
    async fn unregister_revokes_all_collaborators_without_duplicate_disconnect_barriers() {
        let vnc_listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let vnc_endpoint = vnc_listener.local_addr().unwrap();
        tokio::spawn(async move {
            for _ in 0..2 {
                let (mut stream, _) = vnc_listener.accept().await.unwrap();
                tokio::spawn(async move {
                    let mut buffer = [0_u8; 64];
                    while stream.read(&mut buffer).await.unwrap_or_default() != 0 {}
                });
            }
        });

        let disconnects = Arc::new(CountDisconnects(AtomicUsize::new(0)));
        let gateway = RemoteDesktopGateway::with_disconnect_grace(
            SECRET.as_bytes(),
            ["http://console.test".to_owned()],
            disconnects.clone(),
            Duration::from_millis(20),
        )
        .unwrap();
        let session_id = "ses_unregister123456";
        gateway.register_session(session_id, vnc_endpoint).unwrap();
        let gateway_listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let gateway_endpoint = gateway_listener.local_addr().unwrap();
        tokio::spawn(gateway.clone().serve(gateway_listener));

        let make_request = || {
            let nonce = uuid::Uuid::new_v4().simple().to_string();
            let mut request = format!(
                "ws://{gateway_endpoint}/desktop/v1/sessions/{session_id}?ticket={}",
                ticket(session_id, &nonce)
            )
            .into_client_request()
            .unwrap();
            request
                .headers_mut()
                .insert(ORIGIN, "http://console.test".parse().unwrap());
            request
        };
        let (first, _) = connect_async(make_request()).await.unwrap();
        let (second, _) = connect_async(make_request()).await.unwrap();
        assert_eq!(gateway.active_connection_count(session_id), 2);

        gateway.unregister_session(session_id);
        tokio::time::sleep(Duration::from_millis(80)).await;
        assert_eq!(gateway.active_connection_count(session_id), 0);
        assert_eq!(disconnects.0.load(Ordering::SeqCst), 0);
        drop((first, second));
    }

    #[tokio::test]
    async fn enforces_configured_server_to_client_bitrate_delay() {
        let gateway = RemoteDesktopGateway::new(
            SECRET.as_bytes(),
            ["http://console.test".to_owned()],
            Arc::new(NoopDisconnectHandler),
        )
        .unwrap();
        gateway
            .register_session("ses_bitrate1234567890", "127.0.0.1:5901".parse().unwrap())
            .unwrap();
        gateway
            .set_bitrate_limit("ses_bitrate1234567890", 250)
            .unwrap();

        assert!(
            gateway.server_bitrate_delay("ses_bitrate1234567890", 1_000)
                >= Duration::from_millis(30),
            "1KB at 250Kbps must consume the configured transmission budget"
        );
    }

    #[tokio::test]
    async fn enforces_configured_observer_forwarding_rate_without_affecting_input_path() {
        let gateway = RemoteDesktopGateway::new(
            SECRET.as_bytes(),
            ["http://console.test".to_owned()],
            Arc::new(NoopDisconnectHandler),
        )
        .unwrap();
        gateway
            .register_session("ses_observer123456789", "127.0.0.1:5901".parse().unwrap())
            .unwrap();
        assert_eq!(
            gateway.observer_frame_rate_fps("ses_observer123456789"),
            Some(30)
        );
        assert!(gateway
            .set_observer_frame_rate("ses_observer123456789", 0)
            .is_err());
        assert_eq!(
            gateway
                .set_observer_frame_rate("ses_observer123456789", 5)
                .unwrap(),
            30
        );

        assert_eq!(
            gateway.observer_frame_rate_delay("ses_observer123456789", None),
            Duration::ZERO
        );
        assert!(
            gateway.observer_frame_rate_delay("ses_observer123456789", Some(Instant::now()),)
                >= Duration::from_millis(180),
            "5 FPS must keep consecutive Observer forwarding batches about 200ms apart"
        );

        gateway.unregister_session("ses_observer123456789");
        assert_eq!(
            gateway.observer_frame_rate_fps("ses_observer123456789"),
            None
        );
    }

    #[tokio::test]
    async fn observer_throttle_does_not_block_human_input_forwarding() {
        let vnc_listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let vnc_endpoint = vnc_listener.local_addr().unwrap();
        let (second_frame_sender, second_frame_receiver) = oneshot::channel();
        let (input_sender, input_receiver) = oneshot::channel();
        tokio::spawn(accept_test_rfb_upstream(
            vnc_listener,
            Arc::new(AtomicUsize::new(0)),
            Some(input_sender),
            Some(second_frame_receiver),
        ));

        let gateway = RemoteDesktopGateway::new(
            SECRET.as_bytes(),
            ["http://console.test".to_owned()],
            Arc::new(NoopDisconnectHandler),
        )
        .unwrap();
        gateway
            .register_session("ses_inputpriority1234", vnc_endpoint)
            .unwrap();
        gateway
            .set_observer_frame_rate("ses_inputpriority1234", 1)
            .unwrap();
        let gateway_listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let gateway_endpoint = gateway_listener.local_addr().unwrap();
        tokio::spawn(gateway.clone().serve(gateway_listener));

        let nonce = uuid::Uuid::new_v4().simple().to_string();
        let mut request = format!(
            "ws://{gateway_endpoint}/desktop/v1/sessions/ses_inputpriority1234?ticket={}",
            ticket("ses_inputpriority1234", &nonce)
        )
        .into_client_request()
        .unwrap();
        request
            .headers_mut()
            .insert(ORIGIN, "http://console.test".parse().unwrap());
        let (mut websocket, _) = connect_async(request).await.unwrap();
        complete_test_rfb_client(&mut websocket).await;
        assert_eq!(
            next_binary(&mut websocket).await,
            test_raw_framebuffer_update([1, 2, 3, 4])
        );

        second_frame_sender.send(()).unwrap();
        tokio::time::sleep(Duration::from_millis(50)).await;
        websocket
            .send(Message::Binary(vec![3, 1, 0, 0, 0, 0, 0, 1, 0, 1]))
            .await
            .unwrap();
        websocket
            .send(Message::Binary(vec![4, 1, 0, 0, 0, 0, 0, 65]))
            .await
            .unwrap();
        assert_eq!(
            tokio::time::timeout(Duration::from_millis(200), input_receiver)
                .await
                .expect("Human Input must not wait for the one-second Observer frame interval")
                .unwrap(),
            vec![4, 1, 0, 0, 0, 0, 0, 65]
        );
        websocket.close(None).await.unwrap();
    }

    #[tokio::test]
    async fn blackholed_client_runs_disconnect_barrier_after_liveness_timeout() {
        let vnc_listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let vnc_endpoint = vnc_listener.local_addr().unwrap();
        let (input_sender, input_receiver) = oneshot::channel();
        tokio::spawn(accept_test_rfb_upstream(
            vnc_listener,
            Arc::new(AtomicUsize::new(0)),
            Some(input_sender),
            None,
        ));

        let disconnects = Arc::new(CountDisconnects(AtomicUsize::new(0)));
        let gateway = RemoteDesktopGateway::new_with_timeouts(
            SECRET.as_bytes(),
            ["http://console.test".to_owned()],
            disconnects.clone(),
            Duration::from_millis(20),
            Duration::from_millis(20),
            Duration::from_millis(200),
        )
        .unwrap();
        gateway
            .register_session("ses_blackhole123456", vnc_endpoint)
            .unwrap();
        let gateway_listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let gateway_endpoint = gateway_listener.local_addr().unwrap();
        tokio::spawn(gateway.clone().serve(gateway_listener));

        let mut request = format!(
            "ws://{gateway_endpoint}/desktop/v1/sessions/ses_blackhole123456?ticket={}",
            ticket(
                "ses_blackhole123456",
                &uuid::Uuid::new_v4().simple().to_string()
            )
        )
        .into_client_request()
        .unwrap();
        request
            .headers_mut()
            .insert(ORIGIN, "http://console.test".parse().unwrap());
        let (mut blackholed_client, _) = connect_async(request).await.unwrap();
        complete_test_rfb_client(&mut blackholed_client).await;
        next_binary(&mut blackholed_client).await;
        blackholed_client
            .send(Message::Binary(vec![4, 1, 0, 0, 0, 0, 0, 65]))
            .await
            .unwrap();
        assert_eq!(
            tokio::time::timeout(Duration::from_secs(1), input_receiver)
                .await
                .unwrap()
                .unwrap(),
            vec![4, 1, 0, 0, 0, 0, 0, 65]
        );

        tokio::time::timeout(Duration::from_secs(1), async {
            while disconnects.0.load(Ordering::SeqCst) != 1 {
                tokio::task::yield_now().await;
            }
        })
        .await
        .unwrap();
        assert_eq!(gateway.active_connection_count("ses_blackhole123456"), 0);
        drop(blackholed_client);
    }

    #[tokio::test]
    async fn reconnect_within_grace_suppresses_stale_disconnect_barrier() {
        let vnc_listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let vnc_endpoint = vnc_listener.local_addr().unwrap();
        tokio::spawn(accept_test_rfb_upstream(
            vnc_listener,
            Arc::new(AtomicUsize::new(0)),
            None,
            None,
        ));

        let disconnects = Arc::new(CountDisconnects(AtomicUsize::new(0)));
        let gateway = RemoteDesktopGateway::new_with_timeouts(
            SECRET.as_bytes(),
            ["http://console.test".to_owned()],
            disconnects.clone(),
            Duration::from_millis(500),
            Duration::from_millis(20),
            Duration::from_millis(200),
        )
        .unwrap();
        gateway
            .register_session("ses_reconnect123456", vnc_endpoint)
            .unwrap();
        let gateway_listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let gateway_endpoint = gateway_listener.local_addr().unwrap();
        tokio::spawn(gateway.clone().serve(gateway_listener));

        let make_request = |nonce: String| {
            let mut request = format!(
                "ws://{gateway_endpoint}/desktop/v1/sessions/ses_reconnect123456?ticket={}",
                ticket("ses_reconnect123456", &nonce)
            )
            .into_client_request()
            .unwrap();
            request
                .headers_mut()
                .insert(ORIGIN, "http://console.test".parse().unwrap());
            request
        };
        let (mut first_client, _) =
            connect_async(make_request(uuid::Uuid::new_v4().simple().to_string()))
                .await
                .unwrap();
        complete_test_rfb_client(&mut first_client).await;
        next_binary(&mut first_client).await;
        first_client.close(None).await.unwrap();

        tokio::time::timeout(Duration::from_secs(1), async {
            while gateway.active_connection_count("ses_reconnect123456") > 0 {
                tokio::task::yield_now().await;
            }
        })
        .await
        .unwrap();
        let (mut replacement, _) =
            connect_async(make_request(uuid::Uuid::new_v4().simple().to_string()))
                .await
                .unwrap();
        complete_test_rfb_client(&mut replacement).await;
        next_binary(&mut replacement).await;
        let (stop_sender, mut stop_receiver) = oneshot::channel();
        let replacement_task = tokio::spawn(async move {
            loop {
                tokio::select! {
                    _ = &mut stop_receiver => {
                        replacement.close(None).await.unwrap();
                        break;
                    }
                    message = replacement.next() => {
                        match message {
                            Some(Ok(Message::Ping(payload))) => {
                                replacement.send(Message::Pong(payload)).await.unwrap();
                            }
                            Some(Ok(Message::Close(_))) | None => break,
                            Some(Err(error)) => panic!("replacement connection failed: {error}"),
                            _ => {}
                        }
                    }
                }
            }
        });

        tokio::time::sleep(Duration::from_millis(650)).await;
        assert_eq!(disconnects.0.load(Ordering::SeqCst), 0);

        stop_sender.send(()).unwrap();
        replacement_task.await.unwrap();
        tokio::time::timeout(Duration::from_secs(1), async {
            while disconnects.0.load(Ordering::SeqCst) != 1 {
                tokio::task::yield_now().await;
            }
        })
        .await
        .unwrap();
    }

    #[tokio::test]
    async fn rejects_ticket_replay() {
        let gateway = RemoteDesktopGateway::new(
            SECRET.as_bytes(),
            std::iter::empty(),
            Arc::new(NoopDisconnectHandler),
        )
        .unwrap();
        gateway
            .register_session("ses_test1234567890", "127.0.0.1:5900".parse().unwrap())
            .unwrap();
        let nonce = uuid::Uuid::new_v4().simple().to_string();
        let uri = format!(
            "/desktop/v1/sessions/ses_test1234567890?ticket={}",
            ticket("ses_test1234567890", &nonce)
        );
        let request = Request::builder().uri(&uri).body(()).unwrap();
        let first = authorize(&gateway.state, &request);
        assert!(first.is_ok());
        gateway.state.active_connections.lock().unwrap().clear();
        let second = authorize(&gateway.state, &request);
        assert_eq!(second.unwrap_err().status, StatusCode::CONFLICT);
    }

    #[tokio::test]
    async fn bounds_collaborators_and_normalizes_legacy_takeover_as_collaborative() {
        let gateway = RemoteDesktopGateway::new(
            SECRET.as_bytes(),
            std::iter::empty(),
            Arc::new(NoopDisconnectHandler),
        )
        .unwrap();
        let session_id = "ses_boundedviewers123";
        gateway
            .register_session(session_id, "127.0.0.1:5900".parse().unwrap())
            .unwrap();

        for index in 0..MAX_ACTIVE_CONNECTIONS_PER_SESSION {
            let nonce = format!("collaborator{index:020}");
            let request = Request::builder()
                .uri(format!(
                    "/desktop/v1/sessions/{session_id}?ticket={}",
                    ticket(session_id, &nonce)
                ))
                .body(())
                .unwrap();
            assert!(authorize(&gateway.state, &request).is_ok());
        }
        assert_eq!(
            gateway.active_connection_count(session_id),
            MAX_ACTIVE_CONNECTIONS_PER_SESSION
        );

        let overflow_nonce = "collaborator-overflow-123456789";
        let overflow = Request::builder()
            .uri(format!(
                "/desktop/v1/sessions/{session_id}?ticket={}",
                ticket(session_id, overflow_nonce)
            ))
            .body(())
            .unwrap();
        assert_eq!(
            authorize(&gateway.state, &overflow).unwrap_err().status,
            StatusCode::TOO_MANY_REQUESTS
        );

        gateway.state.active_connections.lock().unwrap().clear();
        let collaborative_nonce = "collaborator-before-takeover-123";
        let collaborative = Request::builder()
            .uri(format!(
                "/desktop/v1/sessions/{session_id}?ticket={}",
                ticket(session_id, collaborative_nonce)
            ))
            .body(())
            .unwrap();
        let collaborative = authorize(&gateway.state, &collaborative).unwrap();
        let takeover_nonce = "exclusive-takeover-1234567890";
        let takeover = Request::builder()
            .uri(format!(
                "/desktop/v1/sessions/{session_id}?ticket={}",
                ticket_with_mode(session_id, takeover_nonce, "EXCLUSIVE_TAKEOVER")
            ))
            .body(())
            .unwrap();
        let takeover = authorize(&gateway.state, &takeover).unwrap();
        assert_eq!(takeover.claims.access_mode, "COLLABORATIVE");
        assert!(!*collaborative.revocation.borrow());
        assert!(!*takeover.revocation.borrow());
        assert_eq!(gateway.active_connection_count(session_id), 2);

        let blocked_collaborator_nonce = "collaborator-during-takeover-123";
        let blocked_collaborator = Request::builder()
            .uri(format!(
                "/desktop/v1/sessions/{session_id}?ticket={}",
                ticket(session_id, blocked_collaborator_nonce)
            ))
            .body(())
            .unwrap();
        let collaborator = authorize(&gateway.state, &blocked_collaborator).unwrap();
        assert_eq!(collaborator.claims.access_mode, "COLLABORATIVE");
        assert_eq!(gateway.active_connection_count(session_id), 3);
    }
}
