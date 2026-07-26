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
use std::time::{Duration, SystemTime, UNIX_EPOCH};
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
    used_nonces: Mutex<HashMap<String, u64>>,
    disconnect_grace: Duration,
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
        let ticket_secret = ticket_secret.into();
        anyhow::ensure!(
            ticket_secret.len() >= 32,
            "remote desktop ticket secret must contain at least 32 bytes"
        );
        Ok(Self {
            state: Arc::new(GatewayState {
                ticket_secret,
                allowed_origins: allowed_origins.into_iter().collect(),
                vnc_endpoints: RwLock::new(HashMap::new()),
                active_sessions: Mutex::new(HashSet::new()),
                connection_generations: Mutex::new(HashMap::new()),
                used_nonces: Mutex::new(HashMap::new()),
                disconnect_grace,
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
        Ok(())
    }

    pub fn unregister_session(&self, session_id: &str) {
        self.state
            .vnc_endpoints
            .write()
            .expect("VNC endpoint lock poisoned")
            .remove(session_id);
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
        let mut buffer = vec![0_u8; 64 * 1024];
        let mut heartbeat = tokio::time::interval_at(
            tokio::time::Instant::now() + HEARTBEAT_INTERVAL,
            HEARTBEAT_INTERVAL,
        );
        heartbeat.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
        let mut last_client_activity = tokio::time::Instant::now();
        loop {
            tokio::select! {
                _ = heartbeat.tick() => {
                    anyhow::ensure!(
                        last_client_activity.elapsed() < CLIENT_LIVENESS_TIMEOUT,
                        "remote desktop client heartbeat timed out"
                    );
                    websocket.send(Message::Ping(Vec::new())).await?;
                }
                read = vnc.read(&mut buffer) => {
                    let read = read?;
                    if read == 0 {
                        break;
                    }
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
        let gateway_listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let gateway_endpoint = gateway_listener.local_addr().unwrap();
        tokio::spawn(gateway.serve(gateway_listener));

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
        websocket.close(None).await.unwrap();

        tokio::time::timeout(std::time::Duration::from_secs(1), async {
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
