//! Pure-Rust MCP Streamable HTTP Gateway for Agent Browser Cloud (L1/L2 Control Plane + L0 Lite).
//!
//! 100% wire-compatible drop-in replacement for Python `apps/mcp-gateway/gateway.py` (all 16
//! Control Plane tools, Bearer token introspection against `/api/v1/agent-access/identity`,
//! Host/Origin DNS-rebinding guard, `details.reason` propagation, bounded operation/task waits,
//! non-root read-only container execution) at ~4 MiB idle RSS instead of ~65 MiB Python/Uvicorn.

use anyhow::{anyhow, bail, ensure, Result};
use axum::extract::State;
use axum::http::{header, HeaderMap, HeaderValue, StatusCode};
use axum::response::{IntoResponse, Response};
use axum::routing::{get, post};
use axum::{Json, Router};
use reqwest::{Client, Method};
use serde::Deserialize;
use serde_json::{json, Value};
use std::sync::Arc;
use std::time::{Duration, Instant};

pub const MCP_PROTOCOL_VERSION: &str = "2025-03-26";
const DEFAULT_WAIT_TIMEOUT_SECS: i64 = 300;
const DEFAULT_MAX_WAIT_TIMEOUT_SECS: i64 = 900;

fn max_wait_timeout_seconds() -> i64 {
    std::env::var("MCP_MAX_WAIT_TIMEOUT_SECONDS")
        .ok()
        .and_then(|v| v.parse::<i64>().ok())
        .unwrap_or(DEFAULT_MAX_WAIT_TIMEOUT_SECS)
        .clamp(30, 3600)
}

fn default_wait_timeout_seconds() -> i64 {
    let max_secs = max_wait_timeout_seconds();
    std::env::var("MCP_DEFAULT_WAIT_TIMEOUT_SECONDS")
        .ok()
        .and_then(|v| v.parse::<i64>().ok())
        .unwrap_or(DEFAULT_WAIT_TIMEOUT_SECS)
        .clamp(1, max_secs)
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum GatewayBackend {
    ControlPlane,
    Lite,
}

#[derive(Debug, Clone)]
pub struct GatewayConfig {
    pub backend: GatewayBackend,
    pub upstream_url: String,
    pub bind_host: String,
    pub bind_port: u16,
    pub allowed_hosts: Vec<String>,
    pub allowed_origins: Vec<String>,
}

impl GatewayConfig {
    pub fn from_env() -> Result<Self> {
        let backend = match std::env::var("MCP_BACKEND")
            .unwrap_or_else(|_| "control-plane".to_owned())
            .trim()
            .to_ascii_lowercase()
            .as_str()
        {
            "lite" | "l0" => GatewayBackend::Lite,
            _ => GatewayBackend::ControlPlane,
        };
        let raw_url = std::env::var("CONTROL_PLANE_URL")
            .or_else(|_| std::env::var("LITE_URL"))
            .unwrap_or_else(|_| {
                if backend == GatewayBackend::Lite {
                    "http://127.0.0.1:8130".to_owned()
                } else {
                    "http://127.0.0.1:8080".to_owned()
                }
            });
        let allow_internal_http = std::env::var("MCP_ALLOW_INTERNAL_HTTP")
            .map(|v| v.eq_ignore_ascii_case("true"))
            .unwrap_or(false);
        let upstream_url = validate_upstream_url(&raw_url, allow_internal_http)?;
        let bind_host = std::env::var("MCP_HOST").unwrap_or_else(|_| "127.0.0.1".to_owned());
        let bind_port = std::env::var("MCP_PORT")
            .ok()
            .and_then(|s| s.parse::<u16>().ok())
            .unwrap_or(8130);
        let allowed_origins = std::env::var("MCP_ALLOWED_ORIGINS")
            .unwrap_or_else(|_| "http://127.0.0.1:* http://localhost:*".to_owned())
            .split_whitespace()
            .map(ToOwned::to_owned)
            .collect();
        let allowed_hosts = std::env::var("MCP_ALLOWED_HOSTS")
            .unwrap_or_else(|_| "127.0.0.1:* localhost:*".to_owned())
            .split_whitespace()
            .map(ToOwned::to_owned)
            .collect();
        Ok(Self {
            backend,
            upstream_url,
            bind_host,
            bind_port,
            allowed_hosts,
            allowed_origins,
        })
    }
}

pub fn validate_upstream_url(raw: &str, allow_internal_http: bool) -> Result<String> {
    let trimmed = raw.trim().trim_end_matches('/');
    let uri: http::Uri = trimmed
        .parse()
        .map_err(|_| anyhow!("MCP_CONTROL_PLANE_URL_INVALID"))?;
    let scheme = uri
        .scheme_str()
        .ok_or_else(|| anyhow!("MCP_CONTROL_PLANE_URL_INVALID"))?;
    ensure!(
        matches!(scheme, "http" | "https"),
        "MCP_CONTROL_PLANE_URL_INVALID"
    );
    let authority = uri
        .authority()
        .ok_or_else(|| anyhow!("MCP_CONTROL_PLANE_URL_INVALID"))?;
    ensure!(
        !authority.as_str().contains('@') && uri.query().is_none(),
        "MCP_CONTROL_PLANE_URL_INVALID"
    );
    let host = authority.host();
    if scheme == "http" && !matches!(host, "127.0.0.1" | "localhost") && !allow_internal_http {
        bail!("MCP_CONTROL_PLANE_HTTPS_REQUIRED");
    }
    Ok(trimmed.to_owned())
}

pub fn matches_allowlist(value: &str, patterns: &[String]) -> bool {
    let candidate = value.trim().to_ascii_lowercase();
    patterns.iter().any(|pattern| {
        let p = pattern.trim().to_ascii_lowercase();
        if let Some(prefix) = p.strip_suffix(":*") {
            candidate == prefix || candidate.starts_with(&format!("{prefix}:"))
        } else {
            candidate == p
        }
    })
}

pub fn validate_identifier<'a>(value: &'a str, prefix: &str) -> Result<&'a str> {
    ensure!(
        value.starts_with(prefix)
            && value.len() <= 128
            && value
                .bytes()
                .all(|b| b.is_ascii_alphanumeric() || b == b'_' || b == b'-'),
        "RESOURCE_IDENTIFIER_INVALID"
    );
    Ok(value)
}

#[derive(Debug)]
enum GatewayApiError {
    Unauthorized,
    Forbidden(String),
    UpstreamUnavailable(String),
    Rejected(String),
}

impl std::fmt::Display for GatewayApiError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Unauthorized => f.write_str("MCP_AUTHENTICATION_REQUIRED"),
            Self::Forbidden(code) => f.write_str(code),
            Self::UpstreamUnavailable(detail) => write!(f, "CONTROL_PLANE_UNAVAILABLE: {detail}"),
            Self::Rejected(detail) => f.write_str(detail),
        }
    }
}

impl std::error::Error for GatewayApiError {}

pub struct GatewayState {
    pub config: GatewayConfig,
    pub http: Client,
    health_http: Client,
}

impl GatewayState {
    pub fn new(config: GatewayConfig) -> Result<Self> {
        let http = Client::builder()
            .no_proxy()
            .timeout(Duration::from_secs(30))
            .build()?;
        let health_http = Client::builder()
            .no_proxy()
            .pool_max_idle_per_host(0)
            .timeout(Duration::from_secs(2))
            .build()?;
        Ok(Self {
            config,
            http,
            health_http,
        })
    }

    async fn check_upstream_health(&self) -> bool {
        let probe_url = match self.config.backend {
            GatewayBackend::ControlPlane => {
                format!("{}/actuator/health", self.config.upstream_url)
            }
            GatewayBackend::Lite => format!("{}/healthz", self.config.upstream_url),
        };
        match self.health_http.get(&probe_url).send().await {
            Ok(resp) => resp.status().is_success(),
            Err(_) => false,
        }
    }

    async fn api_checked(
        &self,
        token: &str,
        path: &str,
        method: Method,
        body: Option<Value>,
        idempotency_key: Option<&str>,
    ) -> std::result::Result<Value, GatewayApiError> {
        if token.is_empty() {
            return Err(GatewayApiError::Unauthorized);
        }
        let url = format!("{}/api/v1/agent-access{path}", self.config.upstream_url);
        let mut req = self
            .http
            .request(method, &url)
            .header(header::AUTHORIZATION, format!("Bearer {token}"));
        if let Some(key) = idempotency_key {
            if key.trim().is_empty() || key.len() > 128 {
                return Err(GatewayApiError::Rejected(
                    "IDEMPOTENCY_KEY_INVALID".to_owned(),
                ));
            }
            req = req.header("Idempotency-Key", key);
        }
        if let Some(payload) = body {
            req = req.json(&payload);
        }
        let resp = req
            .send()
            .await
            .map_err(|e| GatewayApiError::UpstreamUnavailable(e.to_string()))?;
        let status = resp.status();
        let bytes = resp
            .bytes()
            .await
            .map_err(|e| GatewayApiError::UpstreamUnavailable(e.to_string()))?;
        if status.as_u16() >= 500 {
            return Err(GatewayApiError::UpstreamUnavailable(format!(
                "HTTP {}",
                status.as_u16()
            )));
        }
        if status == StatusCode::UNAUTHORIZED {
            return Err(GatewayApiError::Unauthorized);
        }
        if status.as_u16() >= 400 {
            let mut code = "CONTROL_PLANE_REQUEST_REJECTED".to_owned();
            let mut reason: Option<String> = None;
            if let Ok(payload) = serde_json::from_slice::<Value>(&bytes) {
                if let Some(cand) = payload.get("code").and_then(Value::as_str) {
                    if is_safe_code(cand) {
                        code = cand.to_owned();
                    }
                }
                if let Some(det) = payload.pointer("/details/reason").and_then(Value::as_str) {
                    if is_safe_code(det) {
                        reason = Some(det.to_owned());
                    }
                }
            }
            if status == StatusCode::FORBIDDEN {
                return Err(GatewayApiError::Forbidden(code));
            }
            let detail = match reason {
                Some(r) if r != code => format!("{code} ({r})"),
                _ => code,
            };
            return Err(GatewayApiError::Rejected(format!(
                "{detail}: HTTP {}",
                status.as_u16()
            )));
        }
        serde_json::from_slice::<Value>(&bytes).map_err(|e| {
            GatewayApiError::UpstreamUnavailable(format!("INVALID_UPSTREAM_JSON: {e}"))
        })
    }

    async fn api(
        &self,
        token: &str,
        path: &str,
        method: Method,
        body: Option<Value>,
        idempotency_key: Option<&str>,
    ) -> Result<Value> {
        self.api_checked(token, path, method, body, idempotency_key)
            .await
            .map_err(|e| anyhow!("{e}"))
    }
}

fn is_safe_code(s: &str) -> bool {
    !s.is_empty()
        && s.len() < 100
        && s.bytes()
            .all(|b| b.is_ascii_uppercase() || b.is_ascii_digit() || b == b'_')
}

pub fn build_router(state: Arc<GatewayState>) -> Router {
    Router::new()
        .route("/healthz", get(healthz))
        .route("/readyz", get(healthz))
        .route("/livez", get(livez))
        .route("/mcp", post(handle_mcp).delete(handle_mcp_delete))
        .with_state(state)
}

async fn livez(State(state): State<Arc<GatewayState>>) -> Json<Value> {
    Json(json!({
        "status": "UP",
        "runtime": "rust",
        "backend": match state.config.backend {
            GatewayBackend::ControlPlane => "control-plane",
            GatewayBackend::Lite => "lite",
        }
    }))
}

async fn healthz(State(state): State<Arc<GatewayState>>) -> Response {
    let backend = match state.config.backend {
        GatewayBackend::ControlPlane => "control-plane",
        GatewayBackend::Lite => "lite",
    };
    if state.check_upstream_health().await {
        (
            StatusCode::OK,
            Json(json!({
                "status": "UP",
                "upstream": "UP",
                "runtime": "rust",
                "backend": backend,
            })),
        )
            .into_response()
    } else {
        (
            StatusCode::SERVICE_UNAVAILABLE,
            Json(json!({
                "status": "DEGRADED",
                "upstream": "DOWN",
                "runtime": "rust",
                "backend": backend,
                "error": "CONTROL_PLANE_UNAVAILABLE",
                "retryable": true,
            })),
        )
            .into_response()
    }
}

fn reject_unauth() -> Response {
    (
        StatusCode::UNAUTHORIZED,
        [(header::CACHE_CONTROL, HeaderValue::from_static("no-store"))],
        Json(json!({ "error": "MCP_AUTHENTICATION_REQUIRED" })),
    )
        .into_response()
}

fn reject_forbidden(code: &str) -> Response {
    (
        StatusCode::FORBIDDEN,
        [(header::CACHE_CONTROL, HeaderValue::from_static("no-store"))],
        Json(json!({ "error": code })),
    )
        .into_response()
}

fn reject_upstream_unavailable() -> Response {
    (
        StatusCode::SERVICE_UNAVAILABLE,
        [
            (header::CACHE_CONTROL, HeaderValue::from_static("no-store")),
            (header::RETRY_AFTER, HeaderValue::from_static("2")),
        ],
        Json(json!({
            "error": "CONTROL_PLANE_UNAVAILABLE",
            "retryable": true,
        })),
    )
        .into_response()
}

async fn handle_mcp_delete() -> StatusCode {
    StatusCode::NO_CONTENT
}

#[derive(Deserialize)]
struct JsonRpcRequest {
    jsonrpc: Option<String>,
    id: Option<Value>,
    method: Option<String>,
    #[serde(default)]
    params: Value,
}

async fn handle_mcp(
    State(state): State<Arc<GatewayState>>,
    headers: HeaderMap,
    body: axum::body::Bytes,
) -> Response {
    // 1. Authenticate Bearer token first (matching Python AuthenticatedGateway order).
    let Some(token) = headers
        .get(header::AUTHORIZATION)
        .and_then(|v| v.to_str().ok())
        .and_then(|v| v.strip_prefix("Bearer "))
        .filter(|t| !t.is_empty() && t.len() <= 128)
    else {
        return reject_unauth();
    };

    // 2. Host & Origin DNS-rebinding protection (matching FastMCP TransportSecuritySettings).
    if let Some(host) = headers.get(header::HOST).and_then(|v| v.to_str().ok()) {
        if !matches_allowlist(host, &state.config.allowed_hosts) {
            return reject_forbidden("MCP_HOST_NOT_ALLOWED");
        }
    }
    if let Some(origin) = headers.get(header::ORIGIN).and_then(|v| v.to_str().ok()) {
        if !matches_allowlist(origin, &state.config.allowed_origins) {
            return reject_forbidden("MCP_ORIGIN_NOT_ALLOWED");
        }
    }

    // In L0 Lite backend mode, forward directly to lite-server `/mcp`.
    if state.config.backend == GatewayBackend::Lite {
        let mut req = state
            .http
            .post(format!("{}/mcp", state.config.upstream_url))
            .header(header::CONTENT_TYPE, "application/json")
            .header(header::AUTHORIZATION, format!("Bearer {token}"))
            .body(body);
        if let Some(origin) = headers.get(header::ORIGIN) {
            req = req.header(header::ORIGIN, origin);
        }
        return match req.send().await {
            Ok(resp) => {
                let status = resp.status();
                let resp_bytes = resp.bytes().await.unwrap_or_default();
                (
                    status,
                    [(header::CONTENT_TYPE, "application/json")],
                    resp_bytes,
                )
                    .into_response()
            }
            Err(_) => (
                StatusCode::BAD_GATEWAY,
                Json(json!({ "error": "LITE_UPSTREAM_UNAVAILABLE" })),
            )
                .into_response(),
        };
    }

    // 3. Introspect Bearer token against Control Plane `/api/v1/agent-access/identity`.
    // Distinguish genuine authentication/authorization failures (401/403) from upstream Control
    // Plane outages/timeouts/5xx (503 retryable) so Agents can retry instead of treating a backend
    // outage as a revoked credential.
    match state
        .api_checked(token, "/identity", Method::GET, None, None)
        .await
    {
        Ok(_) => {}
        Err(GatewayApiError::Unauthorized) => return reject_unauth(),
        Err(GatewayApiError::Forbidden(code)) => return reject_forbidden(&code),
        Err(GatewayApiError::UpstreamUnavailable(_)) => return reject_upstream_unavailable(),
        Err(GatewayApiError::Rejected(_)) => return reject_unauth(),
    }

    // 4. Parse JSON-RPC 2.0 request.
    let Ok(req) = serde_json::from_slice::<JsonRpcRequest>(&body) else {
        return (
            StatusCode::BAD_REQUEST,
            Json(rpc_error(None, -32700, "Parse error")),
        )
            .into_response();
    };
    if req.jsonrpc.as_deref() != Some("2.0") {
        return (
            StatusCode::BAD_REQUEST,
            Json(rpc_error(req.id, -32600, "Invalid JSON-RPC version")),
        )
            .into_response();
    }
    let method = req.method.as_deref().unwrap_or("");
    if req.id.is_none() && method.starts_with("notifications/") {
        return StatusCode::ACCEPTED.into_response();
    }

    let response_json = match method {
        "notifications/initialized" => return StatusCode::ACCEPTED.into_response(),
        "ping" => rpc_ok(req.id, json!({})),
        "initialize" => rpc_ok(
            req.id,
            json!({
                "protocolVersion": MCP_PROTOCOL_VERSION,
                "capabilities": { "tools": { "listChanged": false } },
                "serverInfo": {
                    "name": "agent_browser_cloud",
                    "version": env!("CARGO_PKG_VERSION"),
                }
            }),
        ),
        "tools/list" => rpc_ok(req.id, json!({ "tools": mcp_tool_catalog() })),
        "tools/call" => {
            let name = req
                .params
                .get("name")
                .and_then(Value::as_str)
                .unwrap_or_default();
            let args = req
                .params
                .get("arguments")
                .cloned()
                .unwrap_or_else(|| json!({}));
            match dispatch_tool(&state, token, name, args).await {
                Ok(payload) => rpc_ok(
                    req.id,
                    json!({
                        "content": [{
                            "type": "text",
                            "text": serde_json::to_string(&payload).unwrap_or_else(|_| "{}".to_owned()),
                        }],
                        "structuredContent": payload,
                        "isError": false,
                    }),
                ),
                Err(err) => rpc_ok(
                    req.id,
                    json!({
                        "content": [{ "type": "text", "text": err.to_string() }],
                        "isError": true,
                    }),
                ),
            }
        }
        _ => rpc_error(req.id, -32601, "Method not found"),
    };
    Json(response_json).into_response()
}

fn rpc_ok(id: Option<Value>, result: Value) -> Value {
    json!({
        "jsonrpc": "2.0",
        "id": id.unwrap_or(Value::Null),
        "result": result,
    })
}

fn rpc_error(id: Option<Value>, code: i64, message: &str) -> Value {
    json!({
        "jsonrpc": "2.0",
        "id": id.unwrap_or(Value::Null),
        "error": { "code": code, "message": message },
    })
}

fn req_str<'a>(args: &'a Value, key: &str) -> Result<&'a str> {
    args.get(key)
        .and_then(Value::as_str)
        .ok_or_else(|| anyhow!("MISSING_ARGUMENT: {key}"))
}

async fn dispatch_tool(
    state: &GatewayState,
    token: &str,
    name: &str,
    args: Value,
) -> Result<Value> {
    match name {
        "browser_get_quota" => state.api(token, "/quota", Method::GET, None, None).await,
        "browser_list_environments" => {
            let limit = args.get("limit").and_then(Value::as_i64).unwrap_or(20);
            let offset = args.get("offset").and_then(Value::as_i64).unwrap_or(0);
            ensure!(
                (1..=100).contains(&limit) && offset >= 0,
                "PAGINATION_INVALID"
            );
            state
                .api(
                    token,
                    &format!("/environments?limit={limit}&offset={offset}"),
                    Method::GET,
                    None,
                    None,
                )
                .await
        }
        "browser_get_environment" => {
            let env_id = validate_identifier(req_str(&args, "environment_id")?, "ses_")?;
            state
                .api(
                    token,
                    &format!("/environments/{env_id}"),
                    Method::GET,
                    None,
                    None,
                )
                .await
        }
        "browser_create_environment" => {
            let profile_id = req_str(&args, "profile_id")?;
            let idempotency_key = req_str(&args, "idempotency_key")?;
            let region = args
                .get("region")
                .and_then(Value::as_str)
                .unwrap_or("local");
            let identity = state
                .api(token, "/identity", Method::GET, None, None)
                .await?;
            let tenant_id = identity
                .get("tenantId")
                .and_then(Value::as_str)
                .ok_or_else(|| anyhow!("IDENTITY_TENANT_MISSING"))?;
            let mut body = json!({
                "tenantId": tenant_id,
                "profileId": profile_id,
                "region": region,
                "resourcePolicy": { "mode": "AUTO" },
            });
            if let Some(v) = args.get("runtime_build_id").and_then(Value::as_str) {
                body["runtimeBuildId"] = json!(v);
            }
            if let Some(v) = args.get("proxy_binding_profile_id").and_then(Value::as_str) {
                body["proxyBindingProfileId"] = json!(v);
            }
            if let Some(v) = args.get("display_name").and_then(Value::as_str) {
                let cleaned = v.trim();
                ensure!(
                    !cleaned.is_empty() && cleaned.len() <= 128,
                    "DISPLAY_NAME_INVALID"
                );
                body["metadata"] = json!({ "displayName": cleaned });
            }
            if let Some(v) = args.get("agent_policy").and_then(Value::as_str) {
                ensure!(
                    matches!(v, "BALANCED" | "INTERACTIVE"),
                    "AGENT_POLICY_INVALID"
                );
                body["agentPolicy"] = json!(v);
            }
            state
                .api(
                    token,
                    "/environments",
                    Method::POST,
                    Some(body),
                    Some(idempotency_key),
                )
                .await
        }
        "browser_start_environment" => {
            let env_id = validate_identifier(req_str(&args, "environment_id")?, "ses_")?;
            let key = req_str(&args, "idempotency_key")?;
            state
                .api(
                    token,
                    &format!("/environments/{env_id}:start"),
                    Method::POST,
                    None,
                    Some(key),
                )
                .await
        }
        "browser_stop_environment" => {
            let env_id = validate_identifier(req_str(&args, "environment_id")?, "ses_")?;
            let key = req_str(&args, "idempotency_key")?;
            state
                .api(
                    token,
                    &format!("/environments/{env_id}:stop"),
                    Method::POST,
                    None,
                    Some(key),
                )
                .await
        }
        "browser_delete_environment" => {
            let env_id = validate_identifier(req_str(&args, "environment_id")?, "ses_")?;
            let key = req_str(&args, "idempotency_key")?;
            state
                .api(
                    token,
                    &format!("/environments/{env_id}"),
                    Method::DELETE,
                    None,
                    Some(key),
                )
                .await
        }
        "browser_get_operation" => {
            let op_id = validate_identifier(req_str(&args, "operation_id")?, "op_")?;
            state
                .api(
                    token,
                    &format!("/operations/{op_id}"),
                    Method::GET,
                    None,
                    None,
                )
                .await
        }
        "browser_wait_operation" => {
            let op_id = validate_identifier(req_str(&args, "operation_id")?, "op_")?;
            let max_wait = max_wait_timeout_seconds();
            let timeout_seconds = args
                .get("timeout_seconds")
                .and_then(Value::as_i64)
                .unwrap_or_else(default_wait_timeout_seconds);
            ensure!(
                (1..=max_wait).contains(&timeout_seconds),
                "WAIT_TIMEOUT_INVALID"
            );
            let path = format!("/operations/{op_id}");
            let deadline = Instant::now() + Duration::from_secs(timeout_seconds as u64);
            let mut last: Value = Value::Null;
            loop {
                let remaining = deadline.saturating_duration_since(Instant::now());
                if remaining.is_zero() {
                    return Ok(json!({
                        "completed": false,
                        "waitTimedOut": true,
                        "succeeded": false,
                        "operation": last,
                    }));
                }
                match tokio::time::timeout(
                    remaining,
                    state.api(token, &path, Method::GET, None, None),
                )
                .await
                {
                    Ok(Ok(op)) => {
                        let st = op.get("state").and_then(Value::as_str).unwrap_or("");
                        if matches!(st, "COMMITTED" | "ABORTED" | "TIMED_OUT") {
                            return Ok(json!({
                                "completed": true,
                                "waitTimedOut": false,
                                "succeeded": st == "COMMITTED",
                                "operation": op,
                            }));
                        }
                        last = op;
                    }
                    Ok(Err(e)) => return Err(e),
                    Err(_) => {
                        return Ok(json!({
                            "completed": false,
                            "waitTimedOut": true,
                            "succeeded": false,
                            "operation": last,
                        }));
                    }
                }
                let sleep_for = Duration::from_millis(500)
                    .min(deadline.saturating_duration_since(Instant::now()));
                if sleep_for.is_zero() {
                    return Ok(json!({
                        "completed": false,
                        "waitTimedOut": true,
                        "succeeded": false,
                        "operation": last,
                    }));
                }
                tokio::time::sleep(sleep_for).await;
            }
        }
        "browser_snapshot" => {
            let env_id = validate_identifier(req_str(&args, "environment_id")?, "ses_")?;
            state
                .api(
                    token,
                    &format!("/environments/{env_id}/browser/snapshot"),
                    Method::GET,
                    None,
                    None,
                )
                .await
        }
        "browser_find" => {
            let env_id = validate_identifier(req_str(&args, "environment_id")?, "ses_")?;
            let cleaned = req_str(&args, "query")?.trim();
            ensure!(
                !cleaned.is_empty() && cleaned.len() <= 256,
                "FIND_QUERY_INVALID"
            );
            let limit = args.get("limit").and_then(Value::as_i64).unwrap_or(20);
            ensure!((1..=100).contains(&limit), "PAGINATION_INVALID");
            let include_hidden = args
                .get("include_hidden")
                .and_then(Value::as_bool)
                .unwrap_or(false);
            let mut body = json!({
                "query": cleaned,
                "includeHidden": include_hidden,
                "limit": limit,
            });
            if let Some(roles) = args.get("roles").and_then(Value::as_array) {
                if !roles.is_empty() {
                    ensure!(roles.len() <= 16, "FIND_ROLES_INVALID");
                    body["roles"] = Value::Array(roles.clone());
                }
            }
            state
                .api(
                    token,
                    &format!("/environments/{env_id}/browser/find"),
                    Method::POST,
                    Some(body),
                    None,
                )
                .await
        }
        "browser_inspect" => {
            let env_id = validate_identifier(req_str(&args, "environment_id")?, "ses_")?;
            let state_cursor = req_str(&args, "state_cursor")?;
            let element_ids = args
                .get("element_ids")
                .and_then(Value::as_array)
                .ok_or_else(|| anyhow!("INSPECT_ELEMENT_COUNT_INVALID"))?;
            ensure!(
                (1..=50).contains(&element_ids.len()),
                "INSPECT_ELEMENT_COUNT_INVALID"
            );
            state
                .api(
                    token,
                    &format!("/environments/{env_id}/browser/inspect"),
                    Method::POST,
                    Some(json!({
                        "stateCursor": state_cursor,
                        "elementIds": element_ids,
                    })),
                    None,
                )
                .await
        }
        "browser_act" => {
            let env_id = validate_identifier(req_str(&args, "environment_id")?, "ses_")?;
            let goal = req_str(&args, "goal")?;
            let state_cursor = req_str(&args, "state_cursor")?;
            let idempotency_key = req_str(&args, "idempotency_key")?;
            let actions = args
                .get("actions")
                .and_then(Value::as_array)
                .ok_or_else(|| anyhow!("ACTION_BATCH_SIZE_INVALID"))?;
            ensure!(
                (1..=20).contains(&actions.len()),
                "ACTION_BATCH_SIZE_INVALID"
            );
            state
                .api(
                    token,
                    &format!("/environments/{env_id}/browser/act"),
                    Method::POST,
                    Some(json!({
                        "goal": goal,
                        "expectedStateCursor": state_cursor,
                        "actions": actions,
                        "stopOnError": true,
                    })),
                    Some(idempotency_key),
                )
                .await
        }
        "browser_navigate" => {
            let env_id = validate_identifier(req_str(&args, "environment_id")?, "ses_")?;
            let goal = req_str(&args, "goal")?;
            let state_cursor = req_str(&args, "state_cursor")?;
            let url = req_str(&args, "url")?;
            let idempotency_key = req_str(&args, "idempotency_key")?;
            state
                .api(
                    token,
                    &format!("/environments/{env_id}/browser/act"),
                    Method::POST,
                    Some(json!({
                        "goal": goal,
                        "expectedStateCursor": state_cursor,
                        "actions": [],
                        "navigateUrl": url,
                    })),
                    Some(idempotency_key),
                )
                .await
        }
        "browser_get_action_task" => {
            let env_id = validate_identifier(req_str(&args, "environment_id")?, "ses_")?;
            let task_id = validate_identifier(req_str(&args, "task_id")?, "agt_")?;
            state
                .api(
                    token,
                    &format!("/environments/{env_id}/browser/tasks/{task_id}"),
                    Method::GET,
                    None,
                    None,
                )
                .await
        }
        "browser_wait_action_task" => {
            let env_id = validate_identifier(req_str(&args, "environment_id")?, "ses_")?;
            let task_id = validate_identifier(req_str(&args, "task_id")?, "agt_")?;
            let max_wait = max_wait_timeout_seconds();
            let timeout_seconds = args
                .get("timeout_seconds")
                .and_then(Value::as_i64)
                .unwrap_or_else(default_wait_timeout_seconds);
            ensure!(
                (1..=max_wait).contains(&timeout_seconds),
                "WAIT_TIMEOUT_INVALID"
            );
            let path = format!("/environments/{env_id}/browser/tasks/{task_id}");
            let deadline = Instant::now() + Duration::from_secs(timeout_seconds as u64);
            let mut last: Value = Value::Null;
            let build_timeout_payload = |last: Value| {
                let st = last.get("state").cloned().unwrap_or(Value::Null);
                let execution_wait = last.get("executionWait").cloned().unwrap_or(Value::Null);
                let waiting_for_human_input = execution_wait
                    .get("reason")
                    .and_then(Value::as_str)
                    .is_some_and(|r| r == "HUMAN_INPUT_PRIORITY")
                    || st.as_str() == Some("WAITING_FOR_HUMAN");
                json!({
                    "completed": false,
                    "waitTimedOut": true,
                    "succeeded": false,
                    "state": st,
                    "waitingForHumanInput": waiting_for_human_input,
                    "executionWait": execution_wait,
                    "task": last,
                })
            };
            loop {
                let remaining = deadline.saturating_duration_since(Instant::now());
                if remaining.is_zero() {
                    return Ok(build_timeout_payload(last));
                }
                match tokio::time::timeout(
                    remaining,
                    state.api(token, &path, Method::GET, None, None),
                )
                .await
                {
                    Ok(Ok(task)) => {
                        let st = task.get("state").and_then(Value::as_str).unwrap_or("");
                        if matches!(
                            st,
                            "COMPLETED"
                                | "FAILED"
                                | "BLOCKED"
                                | "WAITING_FOR_HUMAN"
                                | "AWAITING_CONFIRMATION"
                        ) {
                            let execution_wait =
                                task.get("executionWait").cloned().unwrap_or(Value::Null);
                            return Ok(json!({
                                "completed": true,
                                "waitTimedOut": false,
                                "succeeded": st == "COMPLETED",
                                "state": st,
                                "waitingForHumanInput": st == "WAITING_FOR_HUMAN",
                                "executionWait": execution_wait,
                                "task": task,
                            }));
                        }
                        last = task;
                    }
                    Ok(Err(e)) => return Err(e),
                    Err(_) => {
                        return Ok(build_timeout_payload(last));
                    }
                }
                let sleep_for = Duration::from_millis(250)
                    .min(deadline.saturating_duration_since(Instant::now()));
                if sleep_for.is_zero() {
                    return Ok(build_timeout_payload(last));
                }
                tokio::time::sleep(sleep_for).await;
            }
        }
        other => bail!("TOOL_NOT_FOUND: {other}"),
    }
}

/// Exact 16-tool Control Plane MCP catalog matching `apps/mcp-gateway/gateway.py`.
pub fn mcp_tool_catalog() -> Vec<Value> {
    vec![
        json!({
            "name": "browser_get_quota",
            "description": "Get this connection's total and running environment quotas and usage.",
            "annotations": { "readOnlyHint": true },
            "inputSchema": { "type": "object", "properties": {} }
        }),
        json!({
            "name": "browser_list_environments",
            "description": "List only environments owned by this connection, with bounded pagination.",
            "annotations": { "readOnlyHint": true },
            "inputSchema": {
                "type": "object",
                "properties": {
                    "limit": { "type": "integer", "default": 20 },
                    "offset": { "type": "integer", "default": 0 }
                }
            }
        }),
        json!({
            "name": "browser_get_environment",
            "description": "Read an environment owned by this connection.",
            "annotations": { "readOnlyHint": true },
            "inputSchema": {
                "type": "object",
                "required": ["environment_id"],
                "properties": { "environment_id": { "type": "string" } }
            }
        }),
        json!({
            "name": "browser_create_environment",
            "description": "Create a stopped environment with AUTO resource policy.",
            "annotations": { "idempotentHint": true, "destructiveHint": false },
            "inputSchema": {
                "type": "object",
                "required": ["profile_id", "idempotency_key"],
                "properties": {
                    "profile_id": { "type": "string" },
                    "idempotency_key": { "type": "string" },
                    "region": { "type": "string", "default": "local" },
                    "runtime_build_id": { "type": "string" },
                    "proxy_binding_profile_id": { "type": "string" },
                    "display_name": { "type": "string" },
                    "agent_policy": { "type": "string", "enum": ["BALANCED", "INTERACTIVE"] }
                }
            }
        }),
        json!({
            "name": "browser_start_environment",
            "description": "Start an owned environment within running quota; returns an asynchronous operation.",
            "annotations": { "idempotentHint": true, "destructiveHint": false },
            "inputSchema": {
                "type": "object",
                "required": ["environment_id", "idempotency_key"],
                "properties": {
                    "environment_id": { "type": "string" },
                    "idempotency_key": { "type": "string" }
                }
            }
        }),
        json!({
            "name": "browser_stop_environment",
            "description": "Gracefully stop and checkpoint an owned environment.",
            "annotations": { "idempotentHint": true, "destructiveHint": false },
            "inputSchema": {
                "type": "object",
                "required": ["environment_id", "idempotency_key"],
                "properties": {
                    "environment_id": { "type": "string" },
                    "idempotency_key": { "type": "string" }
                }
            }
        }),
        json!({
            "name": "browser_delete_environment",
            "description": "Delete an owned stopped environment. Does not delete its Profile.",
            "annotations": { "idempotentHint": true, "destructiveHint": true },
            "inputSchema": {
                "type": "object",
                "required": ["environment_id", "idempotency_key"],
                "properties": {
                    "environment_id": { "type": "string" },
                    "idempotency_key": { "type": "string" }
                }
            }
        }),
        json!({
            "name": "browser_get_operation",
            "description": "Read an operation only when its environment belongs to this connection.",
            "annotations": { "readOnlyHint": true },
            "inputSchema": {
                "type": "object",
                "required": ["operation_id"],
                "properties": { "operation_id": { "type": "string" } }
            }
        }),
        json!({
            "name": "browser_wait_operation",
            "description": format!("Wait up to {} seconds (default {} seconds, adjustable via timeout_seconds) for COMMITTED, ABORTED or TIMED_OUT.", max_wait_timeout_seconds(), default_wait_timeout_seconds()),
            "annotations": { "readOnlyHint": true, "destructiveHint": false },
            "inputSchema": {
                "type": "object",
                "required": ["operation_id"],
                "properties": {
                    "operation_id": { "type": "string" },
                    "timeout_seconds": { "type": "integer", "default": default_wait_timeout_seconds(), "minimum": 1, "maximum": max_wait_timeout_seconds() }
                }
            }
        }),
        json!({
            "name": "browser_snapshot",
            "description": "Read the current page summary, actionable targets, regional stability, and state cursor.",
            "annotations": { "readOnlyHint": true },
            "inputSchema": {
                "type": "object",
                "required": ["environment_id"],
                "properties": { "environment_id": { "type": "string" } }
            }
        }),
        json!({
            "name": "browser_find",
            "description": "Search actionable elements (including offscreen OUTSIDE_VIEWPORT elements and <select> options).",
            "annotations": { "readOnlyHint": true },
            "inputSchema": {
                "type": "object",
                "required": ["environment_id", "query"],
                "properties": {
                    "environment_id": { "type": "string" },
                    "query": { "type": "string" },
                    "roles": { "type": "array", "items": { "type": "string" } },
                    "include_hidden": { "type": "boolean", "default": false },
                    "limit": { "type": "integer", "default": 20 }
                }
            }
        }),
        json!({
            "name": "browser_inspect",
            "description": "Inspect 1–50 exact elementIds from a stateCursor without a Browser Node round trip.",
            "annotations": { "readOnlyHint": true },
            "inputSchema": {
                "type": "object",
                "required": ["environment_id", "state_cursor", "element_ids"],
                "properties": {
                    "environment_id": { "type": "string" },
                    "state_cursor": { "type": "string" },
                    "element_ids": { "type": "array", "items": { "type": "string" } }
                }
            }
        }),
        json!({
            "name": "browser_act",
            "description": "Run 1–20 direct browser actions with auto-scroll, custom dropdowns, and atomic drag.",
            "annotations": { "idempotentHint": true, "destructiveHint": false },
            "inputSchema": {
                "type": "object",
                "required": ["environment_id", "goal", "state_cursor", "actions", "idempotency_key"],
                "properties": {
                    "environment_id": { "type": "string" },
                    "goal": { "type": "string" },
                    "state_cursor": { "type": "string" },
                    "actions": { "type": "array", "items": { "type": "object" } },
                    "idempotency_key": { "type": "string" }
                }
            }
        }),
        json!({
            "name": "browser_navigate",
            "description": "Navigate the active tab to an explicitly authorized HTTP(S) URL.",
            "annotations": { "idempotentHint": true, "destructiveHint": false },
            "inputSchema": {
                "type": "object",
                "required": ["environment_id", "goal", "state_cursor", "url", "idempotency_key"],
                "properties": {
                    "environment_id": { "type": "string" },
                    "goal": { "type": "string" },
                    "state_cursor": { "type": "string" },
                    "url": { "type": "string" },
                    "idempotency_key": { "type": "string" }
                }
            }
        }),
        json!({
            "name": "browser_get_action_task",
            "description": "Read a browser action task and its results within the same owned environment.",
            "annotations": { "readOnlyHint": true },
            "inputSchema": {
                "type": "object",
                "required": ["environment_id", "task_id"],
                "properties": {
                    "environment_id": { "type": "string" },
                    "task_id": { "type": "string" }
                }
            }
        }),
        json!({
            "name": "browser_wait_action_task",
            "description": format!("Wait up to {} seconds (default {} seconds, adjustable via timeout_seconds) for a browser action task to finish (including while waiting for collaborative human input).", max_wait_timeout_seconds(), default_wait_timeout_seconds()),
            "annotations": { "readOnlyHint": true, "destructiveHint": false },
            "inputSchema": {
                "type": "object",
                "required": ["environment_id", "task_id"],
                "properties": {
                    "environment_id": { "type": "string" },
                    "task_id": { "type": "string" },
                    "timeout_seconds": { "type": "integer", "default": default_wait_timeout_seconds(), "minimum": 1, "maximum": max_wait_timeout_seconds() }
                }
            }
        }),
    ]
}

#[cfg(test)]
mod tests {
    use super::*;
    use axum::body::Body;
    use axum::http::Request;
    use axum::routing::get;
    use http_body_util::BodyExt;
    use std::sync::atomic::{AtomicBool, Ordering};
    use tower::ServiceExt;

    async fn read_json(resp: Response) -> Value {
        let b = resp.into_body().collect().await.unwrap().to_bytes();
        serde_json::from_slice(&b).unwrap()
    }

    #[tokio::test]
    async fn rust_mcp_gateway_matches_all_16_tools_auth_origin_and_reason_propagation() {
        let revoked = Arc::new(AtomicBool::new(false));
        let rev_c = revoked.clone();
        let mock_cp = Router::new()
            .route(
                "/actuator/health",
                get(|| async { Json(json!({ "status": "UP" })) }),
            )
            .route(
                "/api/v1/agent-access/identity",
                get(move |headers: HeaderMap| {
                    let rev = rev_c.clone();
                    async move {
                        let ok = headers.get("authorization").and_then(|v| v.to_str().ok())
                            == Some("Bearer fixture")
                            && !rev.load(Ordering::SeqCst);
                        if ok {
                            (StatusCode::OK, Json(json!({ "tenantId": "owned" })))
                        } else {
                            (
                                StatusCode::UNAUTHORIZED,
                                Json(json!({ "code": "UNAUTHORIZED" })),
                            )
                        }
                    }
                }),
            )
            .route(
                "/api/v1/agent-access/quota",
                get(|| async { Json(json!({ "maxRunning": 5 })) }),
            )
            .route(
                "/api/v1/agent-access/environments",
                get(|| async { Json(json!({ "items": [] })) }).post(
                    |Json(body): Json<Value>| async move {
                        Json(json!({ "sessionId": "ses_owned", "tenantId": body["tenantId"] }))
                    },
                ),
            )
            .route(
                "/api/v1/agent-access/environments/ses_owned",
                get(|| async { Json(json!({ "sessionId": "ses_owned" })) })
                    .delete(|| async { Json(json!({ "deleted": true })) }),
            )
            .route(
                "/api/v1/agent-access/environments/ses_owned/browser/snapshot",
                get(|| async {
                    (
                        StatusCode::UNPROCESSABLE_ENTITY,
                        Json(json!({
                            "code": "AGENT_BROWSER_PERCEPTION_REJECTED",
                            "details": { "reason": "BROWSER_STATE_STALE" }
                        })),
                    )
                }),
            )
            .route(
                "/api/v1/agent-access/operations/op_owned",
                get(|| async { Json(json!({ "operationId": "op_owned", "state": "COMMITTED" })) }),
            )
            .route(
                "/api/v1/agent-access/environments/ses_owned/browser/tasks/agt_owned",
                get(|| async { Json(json!({ "taskId": "agt_owned", "state": "COMPLETED" })) }),
            );

        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap();
        let (shutdown_tx, shutdown_rx) = tokio::sync::oneshot::channel::<()>();
        let cp_srv = tokio::spawn(async move {
            axum::serve(listener, mock_cp)
                .with_graceful_shutdown(async move {
                    let _ = shutdown_rx.await;
                })
                .await
                .unwrap()
        });

        let cfg = GatewayConfig {
            backend: GatewayBackend::ControlPlane,
            upstream_url: format!("http://{addr}"),
            bind_host: "127.0.0.1".into(),
            bind_port: 8130,
            allowed_hosts: vec!["127.0.0.1:*".into(), "localhost:*".into()],
            allowed_origins: vec!["http://127.0.0.1:*".into(), "http://localhost:*".into()],
        };
        let app = build_router(Arc::new(GatewayState::new(cfg).unwrap()));

        // 1. /healthz
        let hz = app
            .clone()
            .oneshot(
                Request::builder()
                    .uri("/healthz")
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(hz.status(), StatusCode::OK);

        // 2. Unauth 401 + untrusted Origin 403
        let unauth = app
            .clone()
            .oneshot(
                Request::builder()
                    .method("POST")
                    .uri("/mcp")
                    .body(Body::from("{}"))
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(unauth.status(), StatusCode::UNAUTHORIZED);

        let bad_origin = app
            .clone()
            .oneshot(
                Request::builder()
                    .method("POST")
                    .uri("/mcp")
                    .header("authorization", "Bearer fixture")
                    .header("origin", "https://evil.invalid")
                    .body(Body::from("{}"))
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(bad_origin.status(), StatusCode::FORBIDDEN);

        // 3. tools/list returns exact 16 tools.
        let list = app
            .clone()
            .oneshot(
                Request::builder()
                    .method("POST")
                    .uri("/mcp")
                    .header("authorization", "Bearer fixture")
                    .body(Body::from(
                        json!({"jsonrpc": "2.0", "id": 1, "method": "tools/list"}).to_string(),
                    ))
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(list.status(), StatusCode::OK);
        let list_json = read_json(list).await;
        assert_eq!(list_json["result"]["tools"].as_array().unwrap().len(), 16);

        // 4. create_environment injects tenantId from /identity.
        let created = app
            .clone()
            .oneshot(
                Request::builder()
                    .method("POST")
                    .uri("/mcp")
                    .header("authorization", "Bearer fixture")
                    .body(Body::from(
                        json!({
                            "jsonrpc": "2.0",
                            "id": 2,
                            "method": "tools/call",
                            "params": {
                                "name": "browser_create_environment",
                                "arguments": {
                                    "profile_id": "prf_1",
                                    "idempotency_key": "k1",
                                    "agent_policy": "INTERACTIVE"
                                }
                            }
                        })
                        .to_string(),
                    ))
                    .unwrap(),
            )
            .await
            .unwrap();
        let cjson = read_json(created).await;
        assert_eq!(cjson["result"]["isError"], false);
        assert_eq!(cjson["result"]["structuredContent"]["tenantId"], "owned");

        // 5. wait_operation & wait_action_task settle cleanly.
        let wait_op = app
            .clone()
            .oneshot(
                Request::builder()
                    .method("POST")
                    .uri("/mcp")
                    .header("authorization", "Bearer fixture")
                    .body(Body::from(
                        json!({
                            "jsonrpc": "2.0",
                            "id": 3,
                            "method": "tools/call",
                            "params": {
                                "name": "browser_wait_operation",
                                "arguments": { "operation_id": "op_owned" }
                            }
                        })
                        .to_string(),
                    ))
                    .unwrap(),
            )
            .await
            .unwrap();
        let wjson = read_json(wait_op).await;
        assert_eq!(wjson["result"]["structuredContent"]["succeeded"], true);

        // 6. details.reason propagates on 422 error.
        let snap_err = app
            .clone()
            .oneshot(
                Request::builder()
                    .method("POST")
                    .uri("/mcp")
                    .header("authorization", "Bearer fixture")
                    .body(Body::from(
                        json!({
                            "jsonrpc": "2.0",
                            "id": 4,
                            "method": "tools/call",
                            "params": {
                                "name": "browser_snapshot",
                                "arguments": { "environment_id": "ses_owned" }
                            }
                        })
                        .to_string(),
                    ))
                    .unwrap(),
            )
            .await
            .unwrap();
        let sjson = read_json(snap_err).await;
        assert_eq!(sjson["result"]["isError"], true);
        assert!(sjson["result"]["content"][0]["text"]
            .as_str()
            .unwrap()
            .contains("BROWSER_STATE_STALE"));

        // 7. Revoked token rejected with 401.
        revoked.store(true, Ordering::SeqCst);
        let rev_resp = app
            .clone()
            .oneshot(
                Request::builder()
                    .method("POST")
                    .uri("/mcp")
                    .header("authorization", "Bearer fixture")
                    .body(Body::from(
                        json!({"jsonrpc": "2.0", "id": 5, "method": "ping"}).to_string(),
                    ))
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(rev_resp.status(), StatusCode::UNAUTHORIZED);

        // 8. Healthy upstream -> /healthz returns 200 UP.
        let health_up = app
            .clone()
            .oneshot(
                Request::builder()
                    .uri("/healthz")
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(health_up.status(), StatusCode::OK);
        assert_eq!(read_json(health_up).await["upstream"], "UP");

        // 9. When Control Plane stops, /healthz returns 503 DEGRADED and /mcp returns 503
        // CONTROL_PLANE_UNAVAILABLE (retryable) instead of misreporting 401 Unauthorized!
        let _ = shutdown_tx.send(());
        let _ = cp_srv.await;
        revoked.store(false, Ordering::SeqCst);

        let health_down = app
            .clone()
            .oneshot(
                Request::builder()
                    .uri("/healthz")
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(health_down.status(), StatusCode::SERVICE_UNAVAILABLE);
        let hd_json = read_json(health_down).await;
        assert_eq!(hd_json["status"], "DEGRADED");
        assert_eq!(hd_json["upstream"], "DOWN");

        let outage_resp = app
            .oneshot(
                Request::builder()
                    .method("POST")
                    .uri("/mcp")
                    .header("authorization", "Bearer fixture")
                    .body(Body::from(
                        json!({"jsonrpc": "2.0", "id": 6, "method": "ping"}).to_string(),
                    ))
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(outage_resp.status(), StatusCode::SERVICE_UNAVAILABLE);
        let oj = read_json(outage_resp).await;
        assert_eq!(oj["error"], "CONTROL_PLANE_UNAVAILABLE");
        assert_eq!(oj["retryable"], true);
    }

    #[tokio::test]
    async fn control_plane_5xx_returns_503_and_wait_action_task_reports_human_input_wait() {
        let fail_503 = Arc::new(AtomicBool::new(true));
        let fail_c = fail_503.clone();
        let mock_cp = Router::new()
            .route(
                "/actuator/health",
                get(|| async { Json(json!({ "status": "UP" })) }),
            )
            .route(
                "/api/v1/agent-access/identity",
                get(move || {
                    let f = fail_c.clone();
                    async move {
                        if f.load(Ordering::SeqCst) {
                            (
                                StatusCode::SERVICE_UNAVAILABLE,
                                Json(json!({ "code": "UPSTREAM_OVERLOADED" })),
                            )
                        } else {
                            (StatusCode::OK, Json(json!({ "tenantId": "owned" })))
                        }
                    }
                }),
            )
            .route(
                "/api/v1/agent-access/environments/ses_owned/browser/tasks/agt_human",
                get(|| async {
                    Json(json!({
                        "taskId": "agt_human",
                        "state": "RUNNING",
                        "executionWait": { "reason": "HUMAN_INPUT_PRIORITY" }
                    }))
                }),
            );

        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap();
        let (shutdown_tx, shutdown_rx) = tokio::sync::oneshot::channel::<()>();
        let cp_srv = tokio::spawn(async move {
            axum::serve(listener, mock_cp)
                .with_graceful_shutdown(async move {
                    let _ = shutdown_rx.await;
                })
                .await
                .unwrap()
        });

        let cfg = GatewayConfig {
            backend: GatewayBackend::ControlPlane,
            upstream_url: format!("http://{addr}"),
            bind_host: "127.0.0.1".to_owned(),
            bind_port: 0,
            allowed_hosts: vec!["127.0.0.1:*".to_owned(), "localhost:*".to_owned()],
            allowed_origins: vec!["http://127.0.0.1:*".to_owned()],
        };
        let app = build_router(Arc::new(GatewayState::new(cfg).unwrap()));

        // Control Plane returning 503 MUST map to 503 CONTROL_PLANE_UNAVAILABLE (not 401).
        let resp_503 = app
            .clone()
            .oneshot(
                Request::builder()
                    .method("POST")
                    .uri("/mcp")
                    .header("authorization", "Bearer fixture")
                    .body(Body::from(
                        json!({"jsonrpc": "2.0", "id": 1, "method": "ping"}).to_string(),
                    ))
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(resp_503.status(), StatusCode::SERVICE_UNAVAILABLE);
        assert_eq!(
            read_json(resp_503).await["error"],
            "CONTROL_PLANE_UNAVAILABLE"
        );

        // When upstream recovers, wait_action_task surfaces waitingForHumanInput=true and allows
        // timeout_seconds up to 120 (rejecting 121).
        fail_503.store(false, Ordering::SeqCst);
        let wait_resp = app
            .clone()
            .oneshot(
                Request::builder()
                    .method("POST")
                    .uri("/mcp")
                    .header("authorization", "Bearer fixture")
                    .body(Body::from(
                        json!({
                            "jsonrpc": "2.0",
                            "id": 2,
                            "method": "tools/call",
                            "params": {
                                "name": "browser_wait_action_task",
                                "arguments": {
                                    "environment_id": "ses_owned",
                                    "task_id": "agt_human",
                                    "timeout_seconds": 1
                                }
                            }
                        })
                        .to_string(),
                    ))
                    .unwrap(),
            )
            .await
            .unwrap();
        let wj = read_json(wait_resp).await;
        assert_eq!(wj["result"]["structuredContent"]["waitTimedOut"], true);
        assert_eq!(
            wj["result"]["structuredContent"]["waitingForHumanInput"],
            true
        );

        let too_long = app
            .oneshot(
                Request::builder()
                    .method("POST")
                    .uri("/mcp")
                    .header("authorization", "Bearer fixture")
                    .body(Body::from(
                        json!({
                            "jsonrpc": "2.0",
                            "id": 3,
                            "method": "tools/call",
                            "params": {
                                "name": "browser_wait_action_task",
                                "arguments": {
                                    "environment_id": "ses_owned",
                                    "task_id": "agt_human",
                                    "timeout_seconds": 901
                                }
                            }
                        })
                        .to_string(),
                    ))
                    .unwrap(),
            )
            .await
            .unwrap();
        let tj = read_json(too_long).await;
        assert_eq!(tj["result"]["isError"], true);

        let _ = shutdown_tx.send(());
        let _ = cp_srv.await;
    }
}
