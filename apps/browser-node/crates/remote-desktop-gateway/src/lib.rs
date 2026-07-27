//! Browser Node 的受控远程桌面数据面。
//!
//! 浏览器只连接此 WebSocket 网关，VNC TCP 端口始终限制在 Node 回环地址。
//! 网关校验 Control Plane 签发的短期 HMAC 票据，并阻止并发接管与票据重放。

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

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct RemoteDesktopTicketClaims {
    pub tenant_id: String,
    pub session_id: String,
    pub actor_id: String,
    pub coordinator_term: i64,
    pub context_epoch: i64,
    pub operation_epoch: u64,
    pub expires_at_epoch_seconds: u64,
    pub nonce: String,
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
    active_sessions: Mutex<HashSet<String>>,
    connection_generations: Mutex<HashMap<String, u64>>,
    last_server_frame_at: Mutex<HashMap<String, Instant>>,
    bitrate_limits_kbps: Mutex<HashMap<String, u32>>,
    used_nonces: Mutex<HashMap<String, u64>>,
    disconnect_grace: Duration,
    heartbeat_interval: Duration,
    client_liveness_timeout: Duration,
    disconnect_handler: Arc<dyn DisconnectHandler>,
}

#[derive(Clone)]
pub struct RemoteDesktopGateway {
    state: Arc<GatewayState>,
}

#[derive(Debug)]
struct AuthorizedConnection {
    claims: RemoteDesktopTicketClaims,
    vnc_endpoint: SocketAddr,
    connection_generation: u64,
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
                active_sessions: Mutex::new(HashSet::new()),
                connection_generations: Mutex::new(HashMap::new()),
                last_server_frame_at: Mutex::new(HashMap::new()),
                bitrate_limits_kbps: Mutex::new(HashMap::new()),
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
            .bitrate_limits_kbps
            .lock()
            .expect("bitrate limit lock poisoned")
            .remove(session_id);
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

    async fn apply_server_bitrate_limit(&self, session_id: &str, bytes: usize) {
        let bitrate_kbps = self.bitrate_limit_kbps(session_id).unwrap_or_default();
        if bitrate_kbps == 0 || bytes == 0 {
            return;
        }
        let seconds = bytes as f64 * 8.0 / (f64::from(bitrate_kbps) * 1_000.0);
        tokio::time::sleep(Duration::from_secs_f64(seconds)).await;
    }

    /// 返回当前活跃远程桌面连接距离最近一批 VNC Server 数据的年龄。
    ///
    /// 没有活跃客户端时返回 `None`，避免把未使用远程桌面的 Session 误判为帧阻塞。
    pub fn frame_age_ms(&self, session_id: &str) -> Option<u32> {
        if !self
            .state
            .active_sessions
            .lock()
            .expect("active session lock poisoned")
            .contains(session_id)
        {
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
        self.state
            .active_sessions
            .lock()
            .expect("active session lock poisoned")
            .remove(&authorized.claims.session_id);
        let state = self.state.clone();
        let claims = authorized.claims.clone();
        let connection_generation = authorized.connection_generation;
        tokio::spawn(async move {
            tokio::time::sleep(state.disconnect_grace).await;
            let still_disconnected = !state
                .active_sessions
                .lock()
                .expect("active session lock poisoned")
                .contains(&claims.session_id);
            let still_latest = state
                .connection_generations
                .lock()
                .expect("connection generation lock poisoned")
                .get(&claims.session_id)
                .is_some_and(|generation| *generation == connection_generation);
            if still_disconnected && still_latest {
                state.disconnect_handler.disconnected(&claims).await;
            }
        });
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
        loop {
            tokio::select! {
                _ = heartbeat.tick() => {
                    anyhow::ensure!(
                        last_client_activity.elapsed() < self.state.client_liveness_timeout,
                        "remote desktop client heartbeat timed out"
                    );
                    websocket.send(Message::Ping(Vec::new())).await?;
                }
                read = vnc.read(&mut buffer) => {
                    let read = read?;
                    if read == 0 {
                        break;
                    }
                    self.state
                        .last_server_frame_at
                        .lock()
                        .expect("frame timestamp lock poisoned")
                        .insert(authorized.claims.session_id.clone(), Instant::now());
                    self.apply_server_bitrate_limit(&authorized.claims.session_id, read)
                        .await;
                    websocket
                        .send(Message::Binary(buffer[..read].to_vec()))
                        .await?;
                }
                message = websocket.next() => {
                    match message {
                        Some(Ok(Message::Binary(payload))) => {
                            last_client_activity = tokio::time::Instant::now();
                            anyhow::ensure!(
                                payload.len() <= MAX_VNC_FRAME_BYTES,
                                "VNC client frame exceeds 1 MiB"
                            );
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
    let connection_generation = {
        let mut active = state
            .active_sessions
            .lock()
            .expect("active session lock poisoned");
        if !active.insert(session_id.to_owned()) {
            return Err(rejection(
                StatusCode::CONFLICT,
                "remote desktop already has an active client",
            ));
        }
        let mut generations = state
            .connection_generations
            .lock()
            .expect("connection generation lock poisoned");
        let generation = generations.entry(session_id.to_owned()).or_default();
        *generation = generation.saturating_add(1);
        *generation
    };
    Ok(AuthorizedConnection {
        claims,
        vnc_endpoint,
        connection_generation,
    })
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
        let claims = RemoteDesktopTicketClaims {
            tenant_id: "tenant-test".to_owned(),
            session_id: session_id.to_owned(),
            actor_id: "user-test".to_owned(),
            coordinator_term: 3,
            context_epoch: 4,
            operation_epoch: 7,
            expires_at_epoch_seconds: unix_seconds() + 60,
            nonce: nonce.to_owned(),
        };
        let payload = URL_SAFE_NO_PAD.encode(serde_json::to_vec(&claims).unwrap());
        let mut mac = HmacSha256::new_from_slice(SECRET.as_bytes()).unwrap();
        mac.update(payload.as_bytes());
        let signature = URL_SAFE_NO_PAD.encode(mac.finalize().into_bytes());
        format!("{payload}.{signature}")
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

        let started = Instant::now();
        gateway
            .apply_server_bitrate_limit("ses_bitrate1234567890", 1_000)
            .await;
        assert!(
            started.elapsed() >= Duration::from_millis(30),
            "1KB at 250Kbps must consume the configured transmission budget"
        );
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
        assert!(!gateway
            .state
            .active_sessions
            .lock()
            .unwrap()
            .contains("ses_blackhole123456"));
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
            while gateway
                .state
                .active_sessions
                .lock()
                .unwrap()
                .contains("ses_reconnect123456")
            {
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
        gateway.state.active_sessions.lock().unwrap().clear();
        let second = authorize(&gateway.state, &request);
        assert_eq!(second.unwrap_err().status, StatusCode::CONFLICT);
    }
}
