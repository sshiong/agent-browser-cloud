//! Browser Node 的受控远程桌面数据面。
//!
//! 浏览器只连接此 WebSocket 网关，VNC TCP 端口始终限制在 Node 回环地址。
//! 网关校验 Control Plane 签发的短期 HMAC 票据，阻止票据重放，并为每个 Session
//! 提供有界的多协作者连接。显式 HumanTakeover 仍保持独占。
//! 普通远程桌面连接允许与 Agent 协作；只有真实 RFB 输入会触发短时真人优先窗口。

use anyhow::Context;
use async_trait::async_trait;
use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use base64::Engine;
use futures_util::{SinkExt, StreamExt};
use hmac::{Hmac, Mac};
use serde::{Deserialize, Serialize};
use sha2::Sha256;
use std::collections::{HashMap, HashSet};
use std::net::SocketAddr;
use std::sync::{Arc, Mutex, RwLock};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::watch;
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
const RFB_CLIENT_HANDSHAKE_BYTES: usize = 14;
const MAX_ACTIVE_CONNECTIONS_PER_SESSION: usize = 8;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct RemoteDesktopTicketClaims {
    pub tenant_id: String,
    pub session_id: String,
    pub actor_id: String,
    pub coordinator_term: i64,
    pub context_epoch: i64,
    pub operation_epoch: u64,
    #[serde(default = "default_access_mode")]
    pub access_mode: String,
    #[serde(default)]
    pub view_only: bool,
    pub expires_at_epoch_seconds: u64,
    pub nonce: String,
}

fn default_access_mode() -> String {
    // Missing accessMode can only come from an older Control Plane during a rolling upgrade.
    // Fail collaborative: opening VNC must never acquire exclusive control merely because an
    // additive claim is absent. Exclusive takeover always requires an explicitly signed value.
    "COLLABORATIVE".to_owned()
}

#[async_trait]
pub trait DisconnectHandler: Send + Sync {
    async fn disconnected(&self, claims: &RemoteDesktopTicketClaims);
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
    active_connections: Mutex<HashMap<String, SessionConnectionState>>,
    last_server_frame_at: Mutex<HashMap<String, Instant>>,
    last_human_input_at: Mutex<HashMap<String, Instant>>,
    bitrate_limits_kbps: Mutex<HashMap<String, u32>>,
    observer_frame_rates_fps: Mutex<HashMap<String, u32>>,
    used_nonces: Mutex<HashMap<String, u64>>,
    disconnect_grace: Duration,
    heartbeat_interval: Duration,
    client_liveness_timeout: Duration,
    disconnect_handler: Arc<dyn DisconnectHandler>,
}

#[derive(Debug, Default)]
struct SessionConnectionState {
    generation: u64,
    leases_by_nonce: HashMap<String, ConnectionLease>,
}

#[derive(Debug)]
struct ConnectionLease {
    access_mode: String,
    revoke: watch::Sender<bool>,
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
                active_connections: Mutex::new(HashMap::new()),
                last_server_frame_at: Mutex::new(HashMap::new()),
                last_human_input_at: Mutex::new(HashMap::new()),
                bitrate_limits_kbps: Mutex::new(HashMap::new()),
                observer_frame_rates_fps: Mutex::new(HashMap::new()),
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
        self.state
            .vnc_endpoints
            .write()
            .expect("VNC endpoint lock poisoned")
            .insert(session_id.to_owned(), vnc_endpoint);
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
            for lease in connections.leases_by_nonce.into_values() {
                let _ = lease.revoke.send(true);
            }
        }
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

    fn server_bitrate_delay(&self, session_id: &str, bytes: usize) -> Duration {
        let bitrate_kbps = self.bitrate_limit_kbps(session_id).unwrap_or_default();
        if bitrate_kbps == 0 || bytes == 0 {
            return Duration::ZERO;
        }
        let seconds = bytes as f64 * 8.0 / (f64::from(bitrate_kbps) * 1_000.0);
        Duration::from_secs_f64(seconds)
    }

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

    fn server_forwarding_delay(
        &self,
        session_id: &str,
        bytes: usize,
        last_forwarded_at: Option<Instant>,
    ) -> Duration {
        self.server_bitrate_delay(session_id, bytes)
            .max(self.observer_frame_rate_delay(session_id, last_forwarded_at))
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
            .map(|connections| connections.leases_by_nonce.len())
            .unwrap_or_default()
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
        let disconnected_generation = {
            let mut sessions = self
                .state
                .active_connections
                .lock()
                .expect("active connection lock poisoned");
            sessions
                .get_mut(&authorized.claims.session_id)
                .and_then(|connections| {
                    connections
                        .leases_by_nonce
                        .remove(&authorized.claims.nonce)?;
                    connections.generation = connections.generation.saturating_add(1);
                    Some(connections.generation)
                })
        };
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
                        connections.leases_by_nonce.is_empty()
                            && connections.generation == disconnected_generation
                    });
                if last_connection_still_gone {
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
        let mut vnc = TcpStream::connect(authorized.vnc_endpoint)
            .await
            .context("registered VNC endpoint is unavailable")?;
        self.state
            .last_server_frame_at
            .lock()
            .expect("frame timestamp lock poisoned")
            .insert(authorized.claims.session_id.clone(), Instant::now());
        // 有界分片避免最低码率下单次等待过长而妨碍心跳和客户端存活检查。
        let mut buffer = vec![0_u8; 16 * 1024];
        let mut heartbeat = tokio::time::interval_at(
            tokio::time::Instant::now() + self.state.heartbeat_interval,
            self.state.heartbeat_interval,
        );
        heartbeat.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
        let mut last_client_activity = tokio::time::Instant::now();
        let mut last_server_forwarded_at = None;
        let mut pending_server_payload = None;
        let mut pending_server_ready_at = tokio::time::Instant::now();
        let mut input_detector = RfbClientInputDetector::default();
        let mut revocation = authorized.revocation.clone();
        loop {
            tokio::select! {
                changed = revocation.changed() => {
                    if changed.is_err() || *revocation.borrow() {
                        break;
                    }
                }
                _ = heartbeat.tick() => {
                    anyhow::ensure!(
                        last_client_activity.elapsed() < self.state.client_liveness_timeout,
                        "remote desktop client heartbeat timed out"
                    );
                    websocket.send(Message::Ping(Vec::new())).await?;
                }
                read = vnc.read(&mut buffer), if pending_server_payload.is_none() => {
                    let read = read?;
                    if read == 0 {
                        break;
                    }
                    self.state
                        .last_server_frame_at
                        .lock()
                        .expect("frame timestamp lock poisoned")
                        .insert(authorized.claims.session_id.clone(), Instant::now());
                    pending_server_ready_at = tokio::time::Instant::now() + self.server_forwarding_delay(
                        &authorized.claims.session_id,
                        read,
                        last_server_forwarded_at,
                    );
                    pending_server_payload = Some(buffer[..read].to_vec());
                }
                _ = tokio::time::sleep_until(pending_server_ready_at), if pending_server_payload.is_some() => {
                    websocket
                        .send(Message::Binary(
                            pending_server_payload
                                .take()
                                .expect("guarded pending Observer payload"),
                        ))
                        .await?;
                    last_server_forwarded_at = Some(Instant::now());
                }
                message = websocket.next() => {
                    match message {
                        Some(Ok(Message::Binary(payload))) => {
                            last_client_activity = tokio::time::Instant::now();
                            anyhow::ensure!(
                                payload.len() <= MAX_VNC_FRAME_BYTES,
                                "VNC client frame exceeds 1 MiB"
                            );
                            let human_input = input_detector.observe(&payload);
                            anyhow::ensure!(
                                !authorized.claims.view_only || !human_input,
                                "view-only remote desktop attempted human input"
                            );
                            if human_input {
                                self.mark_human_input(&authorized.claims.session_id);
                            }
                            vnc.write_all(&payload).await?;
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
struct RfbClientInputDetector {
    handshake_remaining: usize,
    buffered: Vec<u8>,
}

impl Default for RfbClientInputDetector {
    fn default() -> Self {
        Self {
            handshake_remaining: RFB_CLIENT_HANDSHAKE_BYTES,
            buffered: Vec::new(),
        }
    }
}

impl RfbClientInputDetector {
    /// 识别 RFB Client → Server 的 KeyEvent、PointerEvent 与 ClientCutText。
    /// 协议未知消息保守地按真人输入处理，确保真人优先而不丢失输入。
    fn observe(&mut self, payload: &[u8]) -> bool {
        let mut offset = 0;
        if self.handshake_remaining > 0 {
            let consumed = self.handshake_remaining.min(payload.len());
            self.handshake_remaining -= consumed;
            offset += consumed;
            if offset == payload.len() {
                return false;
            }
        }

        self.buffered.extend_from_slice(&payload[offset..]);
        if self.buffered.len() > MAX_VNC_FRAME_BYTES {
            self.buffered.clear();
            return true;
        }

        let mut human_input = false;
        while let Some(message_type) = self.buffered.first().copied() {
            let (message_length, is_human) = match message_type {
                0 => (20, false), // SetPixelFormat
                2 => {
                    if self.buffered.len() < 4 {
                        break;
                    }
                    let encoding_count = u16::from_be_bytes([self.buffered[2], self.buffered[3]]);
                    (
                        4usize.saturating_add(usize::from(encoding_count).saturating_mul(4)),
                        false,
                    )
                }
                3 => (10, false), // FramebufferUpdateRequest
                4 => (8, true),   // KeyEvent
                5 => (6, true),   // PointerEvent
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
                    (8usize.saturating_add(text_length), true)
                }
                150 => (10, false), // EnableContinuousUpdates
                _ => {
                    self.buffered.clear();
                    return true;
                }
            };
            if message_length > MAX_VNC_FRAME_BYTES {
                self.buffered.clear();
                return true;
            }
            if self.buffered.len() < message_length {
                break;
            }
            human_input |= is_human;
            self.buffered.drain(..message_length);
        }
        human_input
    }
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
    let claims = verify_ticket(&state.ticket_secret, ticket)
        .map_err(|_| rejection(StatusCode::UNAUTHORIZED, "connection ticket is invalid"))?;
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
        || claims.coordinator_term < 0
        || claims.context_epoch <= 0
        || claims.operation_epoch == 0
        || claims.operation_epoch > i64::MAX as u64
        || !matches!(
            claims.access_mode.as_str(),
            "COLLABORATIVE" | "EXCLUSIVE_TAKEOVER"
        )
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
        let has_exclusive = connections
            .leases_by_nonce
            .values()
            .any(|lease| lease.access_mode == "EXCLUSIVE_TAKEOVER");
        let requests_exclusive = claims.access_mode == "EXCLUSIVE_TAKEOVER";
        if !requests_exclusive && has_exclusive {
            return Err(rejection(
                StatusCode::CONFLICT,
                "exclusive HumanTakeover cannot share a remote desktop connection",
            ));
        }
        if requests_exclusive {
            for lease in connections.leases_by_nonce.values() {
                let _ = lease.revoke.send(true);
            }
            connections.leases_by_nonce.clear();
        }
        if connections.leases_by_nonce.len() >= MAX_ACTIVE_CONNECTIONS_PER_SESSION {
            return Err(rejection(
                StatusCode::TOO_MANY_REQUESTS,
                "remote desktop collaborator limit reached",
            ));
        }
        let (revoke, revocation) = watch::channel(false);
        connections.leases_by_nonce.insert(
            claims.nonce.clone(),
            ConnectionLease {
                access_mode: claims.access_mode.clone(),
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
        let claims = RemoteDesktopTicketClaims {
            tenant_id: "tenant-test".to_owned(),
            session_id: session_id.to_owned(),
            actor_id: "user-test".to_owned(),
            coordinator_term: 3,
            context_epoch: 4,
            operation_epoch: 7,
            access_mode: access_mode.to_owned(),
            view_only,
            expires_at_epoch_seconds: unix_seconds() + 60,
            nonce: nonce.to_owned(),
        };
        let payload = URL_SAFE_NO_PAD.encode(serde_json::to_vec(&claims).unwrap());
        let mut mac = HmacSha256::new_from_slice(SECRET.as_bytes()).unwrap();
        mac.update(payload.as_bytes());
        let signature = URL_SAFE_NO_PAD.encode(mac.finalize().into_bytes());
        format!("{payload}.{signature}")
    }

    #[test]
    fn detects_real_human_rfb_input_but_not_observer_protocol_messages() {
        let mut detector = RfbClientInputDetector::default();
        assert!(!detector.observe(b"RFB 003.008\n"));
        assert!(!detector.observe(&[1, 1]));
        assert!(!detector.observe(&[3, 0, 0, 0, 0, 0, 0, 100, 0, 100]));
        assert!(detector.observe(&[4, 1, 0, 0, 0, 0, 0, 65]));
        assert!(!detector.observe(&[5, 1, 0]));
        assert!(detector.observe(&[10, 0, 20]));
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
    }

    #[tokio::test]
    async fn proxies_binary_vnc_stream_and_runs_disconnect_barrier() {
        let vnc_listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let vnc_endpoint = vnc_listener.local_addr().unwrap();
        tokio::spawn(async move {
            let (mut stream, _) = vnc_listener.accept().await.unwrap();
            let mut buffer = [0_u8; 64];
            let read = stream.read(&mut buffer).await.unwrap();
            stream.write_all(&buffer[..read]).await.unwrap();
            while stream.read(&mut buffer).await.unwrap_or_default() != 0 {}
        });

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
        websocket
            .send(Message::Binary(b"rfb".to_vec()))
            .await
            .unwrap();
        let echo = tokio::time::timeout(Duration::from_secs(1), async {
            loop {
                match websocket.next().await.unwrap().unwrap() {
                    Message::Binary(payload) => break payload,
                    Message::Ping(payload) => {
                        websocket.send(Message::Pong(payload)).await.unwrap();
                    }
                    other => panic!("unexpected WebSocket message before RFB echo: {other:?}"),
                }
            }
        })
        .await
        .unwrap();
        assert_eq!(echo, b"rfb");
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
        tokio::spawn(async move {
            for _ in 0..2 {
                let (mut stream, _) = vnc_listener.accept().await.unwrap();
                tokio::spawn(async move {
                    let mut buffer = [0_u8; 64];
                    while let Ok(read) = stream.read(&mut buffer).await {
                        if read == 0 {
                            break;
                        }
                        stream.write_all(&buffer[..read]).await.unwrap();
                    }
                });
            }
        });

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
        let (mut second, _) = connect_async(make_request()).await.unwrap();
        assert_eq!(gateway.active_connection_count("ses_multiviewer12345"), 2);

        first
            .send(Message::Binary(b"viewer-one".to_vec()))
            .await
            .unwrap();
        second
            .send(Message::Binary(b"viewer-two".to_vec()))
            .await
            .unwrap();
        assert_eq!(
            first.next().await.unwrap().unwrap(),
            Message::Binary(b"viewer-one".to_vec())
        );
        assert_eq!(
            second.next().await.unwrap().unwrap(),
            Message::Binary(b"viewer-two".to_vec())
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
        let (read_sender, read_receiver) = oneshot::channel();
        tokio::spawn(async move {
            let (mut stream, _) = vnc_listener.accept().await.unwrap();
            let mut buffer = [0_u8; 64];
            read_sender
                .send(stream.read(&mut buffer).await.unwrap())
                .unwrap();
        });

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
        let mut handshake_and_key = vec![0_u8; RFB_CLIENT_HANDSHAKE_BYTES];
        handshake_and_key.extend_from_slice(&[4, 1, 0, 0, 0, 0, 0, 65]);
        websocket
            .send(Message::Binary(handshake_and_key))
            .await
            .unwrap();

        assert_eq!(
            tokio::time::timeout(Duration::from_secs(1), read_receiver)
                .await
                .unwrap()
                .unwrap(),
            0,
            "view-only Human Input must never reach x11vnc"
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
        tokio::spawn(async move {
            let (mut stream, _) = vnc_listener.accept().await.unwrap();
            stream.write_all(b"first-frame").await.unwrap();
            second_frame_receiver.await.unwrap();
            stream.write_all(b"second-frame").await.unwrap();
            let mut input = [0_u8; 64];
            let read = stream.read(&mut input).await.unwrap();
            input_sender.send(input[..read].to_vec()).unwrap();
        });

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
        loop {
            match websocket.next().await.unwrap().unwrap() {
                Message::Binary(payload) => {
                    assert_eq!(payload, b"first-frame");
                    break;
                }
                Message::Ping(payload) => websocket.send(Message::Pong(payload)).await.unwrap(),
                other => panic!("unexpected message before first Observer frame: {other:?}"),
            }
        }

        second_frame_sender.send(()).unwrap();
        tokio::time::sleep(Duration::from_millis(50)).await;
        websocket
            .send(Message::Binary(b"human-input".to_vec()))
            .await
            .unwrap();
        assert_eq!(
            tokio::time::timeout(Duration::from_millis(200), input_receiver)
                .await
                .expect("Human Input must not wait for the one-second Observer frame interval")
                .unwrap(),
            b"human-input"
        );
        websocket.close(None).await.unwrap();
    }

    #[tokio::test]
    async fn blackholed_client_runs_disconnect_barrier_after_liveness_timeout() {
        let vnc_listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let vnc_endpoint = vnc_listener.local_addr().unwrap();
        let (frame_sender, frame_receiver) = oneshot::channel();
        tokio::spawn(async move {
            let (mut stream, _) = vnc_listener.accept().await.unwrap();
            let mut buffer = [0_u8; 64];
            let read = stream.read(&mut buffer).await.unwrap();
            frame_sender.send(buffer[..read].to_vec()).unwrap();
            tokio::time::sleep(Duration::from_secs(1)).await;
        });

        let disconnects = Arc::new(CountDisconnects(AtomicUsize::new(0)));
        let gateway = RemoteDesktopGateway::new_with_timeouts(
            SECRET.as_bytes(),
            ["http://console.test".to_owned()],
            disconnects.clone(),
            Duration::from_millis(10),
            Duration::from_millis(10),
            Duration::from_millis(30),
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
        blackholed_client
            .send(Message::Binary(b"unpaired-input".to_vec()))
            .await
            .unwrap();
        assert_eq!(
            tokio::time::timeout(Duration::from_secs(1), frame_receiver)
                .await
                .unwrap()
                .unwrap(),
            b"unpaired-input"
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
        let gateway = RemoteDesktopGateway::new_with_timeouts(
            SECRET.as_bytes(),
            ["http://console.test".to_owned()],
            disconnects.clone(),
            Duration::from_millis(150),
            Duration::from_millis(10),
            Duration::from_millis(30),
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
        let (first_client, _) =
            connect_async(make_request(uuid::Uuid::new_v4().simple().to_string()))
                .await
                .unwrap();

        tokio::time::timeout(Duration::from_secs(1), async {
            while gateway.active_connection_count("ses_reconnect123456") > 0 {
                tokio::task::yield_now().await;
            }
        })
        .await
        .unwrap();
        drop(first_client);

        let (mut replacement, _) =
            connect_async(make_request(uuid::Uuid::new_v4().simple().to_string()))
                .await
                .unwrap();
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

        tokio::time::sleep(Duration::from_millis(220)).await;
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

    #[test]
    fn bounds_collaborators_and_explicit_takeover_revokes_them_exclusively() {
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
        assert!(*collaborative.revocation.borrow());
        assert!(!*takeover.revocation.borrow());
        assert_eq!(gateway.active_connection_count(session_id), 1);

        let blocked_collaborator_nonce = "collaborator-during-takeover-123";
        let blocked_collaborator = Request::builder()
            .uri(format!(
                "/desktop/v1/sessions/{session_id}?ticket={}",
                ticket(session_id, blocked_collaborator_nonce)
            ))
            .body(())
            .unwrap();
        assert_eq!(
            authorize(&gateway.state, &blocked_collaborator)
                .unwrap_err()
                .status,
            StatusCode::CONFLICT
        );
    }
}
