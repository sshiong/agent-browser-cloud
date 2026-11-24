//! Native Rust MCP Streamable HTTP (10 converged tools) + REST API router for L0 Lite.

use crate::runtime::{
    ActBatchParams, ActionStepParams, CloseSessionParams, CreateSessionParams, LiteRuntime,
    ObserveParams, ProfileOpParams, ScreenshotParams, WaitParams,
};
use crate::security::{matches_allowlist, validate_identifier};
use crate::store::AgentTokenRecord;
use axum::extract::{Path, Query, State};
use axum::http::{HeaderMap, StatusCode};
use axum::response::{IntoResponse, Response};
use axum::routing::{delete, get, post};
use axum::{Json, Router};
use serde::Deserialize;
use serde_json::{json, Value};
use std::sync::Arc;

pub const MCP_PROTOCOL_VERSION: &str = "2025-03-26";

pub fn build_router(runtime: Arc<LiteRuntime>) -> Router {
    Router::new()
        .route("/healthz", get(healthz))
        .route("/mcp", post(handle_mcp))
        .route(
            "/api/v1/lite/sessions",
            get(rest_list_sessions).post(rest_create_session),
        )
        .route(
            "/api/v1/lite/sessions/:session_id/navigate",
            post(rest_navigate),
        )
        .route(
            "/api/v1/lite/sessions/:session_id/observe",
            post(rest_observe),
        )
        .route("/api/v1/lite/sessions/:session_id/act", post(rest_act))
        .route(
            "/api/v1/lite/sessions/:session_id/act-batch",
            post(rest_act_batch),
        )
        .route("/api/v1/lite/sessions/:session_id/wait", post(rest_wait))
        .route(
            "/api/v1/lite/sessions/:session_id/screenshot",
            post(rest_screenshot),
        )
        .route(
            "/api/v1/lite/sessions/:session_id/profile",
            post(rest_profile),
        )
        .route("/api/v1/lite/sessions/:session_id/close", post(rest_close))
        .route(
            "/api/v1/lite/tokens",
            get(rest_list_tokens).post(rest_create_token),
        )
        .route(
            "/api/v1/lite/tokens/:token_id/rotate",
            post(rest_rotate_token),
        )
        .route("/api/v1/lite/tokens/:token_id", delete(rest_revoke_token))
        .with_state(runtime)
}

async fn healthz(State(rt): State<Arc<LiteRuntime>>) -> Json<Value> {
    let bp = rt.backpressure_snapshot().await;
    let running = rt.store.count_running_sessions_total().unwrap_or(0);
    Json(json!({
        "status": "UP",
        "tier": "L0_LITE",
        "runtime": "rust",
        "runningSessions": running,
        "maxSessions": rt.config.max_sessions,
        "backpressure": bp,
    }))
}

fn authorize(
    rt: &LiteRuntime,
    headers: &HeaderMap,
    required_scope: &str,
) -> Result<AgentTokenRecord, (StatusCode, Json<Value>)> {
    if let Some(host) = headers.get("host").and_then(|v| v.to_str().ok()) {
        if !matches_allowlist(host, &rt.config.allowed_hosts) {
            return Err((
                StatusCode::FORBIDDEN,
                Json(json!({ "error": "HOST_NOT_ALLOWED" })),
            ));
        }
    }
    if let Some(origin) = headers.get("origin").and_then(|v| v.to_str().ok()) {
        if !matches_allowlist(origin, &rt.config.allowed_origins) {
            return Err((
                StatusCode::FORBIDDEN,
                Json(json!({ "error": "ORIGIN_NOT_ALLOWED" })),
            ));
        }
    }
    let auth = headers
        .get("authorization")
        .and_then(|v| v.to_str().ok())
        .and_then(|v| v.strip_prefix("Bearer "))
        .ok_or_else(|| {
            (
                StatusCode::UNAUTHORIZED,
                Json(json!({ "error": "AUTHENTICATION_REQUIRED" })),
            )
        })?;
    let record = rt
        .store
        .authenticate(auth)
        .map_err(|_| {
            (
                StatusCode::INTERNAL_SERVER_ERROR,
                Json(json!({ "error": "AUTH_STORE_ERROR" })),
            )
        })?
        .ok_or_else(|| {
            (
                StatusCode::UNAUTHORIZED,
                Json(json!({ "error": "TOKEN_INVALID_OR_REVOKED" })),
            )
        })?;
    if !record.has_scope(required_scope) {
        return Err((
            StatusCode::FORBIDDEN,
            Json(json!({ "error": "SCOPE_FORBIDDEN" })),
        ));
    }
    Ok(record)
}

fn map_runtime_error(error: anyhow::Error) -> (StatusCode, Json<Value>) {
    let msg = error.to_string();
    let status = if msg.contains("ACCESS_DENIED") || msg.contains("FORBIDDEN") {
        StatusCode::FORBIDDEN
    } else if msg.contains("NOT_FOUND") {
        StatusCode::NOT_FOUND
    } else if msg.contains("QUOTA") || msg.contains("MAX_SESSIONS") || msg.contains("BACKPRESSURE")
    {
        StatusCode::TOO_MANY_REQUESTS
    } else if msg.contains("INVALID") || msg.contains("REQUIRED") || msg.contains("UNSUPPORTED") {
        StatusCode::BAD_REQUEST
    } else {
        StatusCode::UNPROCESSABLE_ENTITY
    };
    (status, Json(json!({ "error": msg })))
}

// ---------- REST API ----------

#[derive(Deserialize, Default)]
struct ListQuery {
    #[serde(default)]
    include_closed: bool,
}

async fn rest_list_sessions(
    State(rt): State<Arc<LiteRuntime>>,
    headers: HeaderMap,
    Query(query): Query<ListQuery>,
) -> Response {
    let caller = match authorize(&rt, &headers, "sessions") {
        Ok(c) => c,
        Err(resp) => return resp.into_response(),
    };
    match rt.list_sessions(&caller, query.include_closed).await {
        Ok(val) => Json(val).into_response(),
        Err(err) => map_runtime_error(err).into_response(),
    }
}

async fn rest_create_session(
    State(rt): State<Arc<LiteRuntime>>,
    headers: HeaderMap,
    Json(params): Json<CreateSessionParams>,
) -> Response {
    let caller = match authorize(&rt, &headers, "sessions") {
        Ok(c) => c,
        Err(resp) => return resp.into_response(),
    };
    match rt.create_session(&caller, params).await {
        Ok(val) => (StatusCode::CREATED, Json(val)).into_response(),
        Err(err) => map_runtime_error(err).into_response(),
    }
}

#[derive(Deserialize)]
struct NavigateBody {
    url: String,
}

async fn rest_navigate(
    State(rt): State<Arc<LiteRuntime>>,
    headers: HeaderMap,
    Path(session_id): Path<String>,
    Json(body): Json<NavigateBody>,
) -> Response {
    let caller = match authorize(&rt, &headers, "sessions") {
        Ok(c) => c,
        Err(resp) => return resp.into_response(),
    };
    match rt.navigate(&caller, &session_id, &body.url).await {
        Ok(val) => Json(val).into_response(),
        Err(err) => map_runtime_error(err).into_response(),
    }
}

async fn rest_observe(
    State(rt): State<Arc<LiteRuntime>>,
    headers: HeaderMap,
    Path(session_id): Path<String>,
    Json(params): Json<ObserveParams>,
) -> Response {
    let caller = match authorize(&rt, &headers, "sessions") {
        Ok(c) => c,
        Err(resp) => return resp.into_response(),
    };
    match rt.observe(&caller, &session_id, params).await {
        Ok(val) => Json(val).into_response(),
        Err(err) => map_runtime_error(err).into_response(),
    }
}

async fn rest_act(
    State(rt): State<Arc<LiteRuntime>>,
    headers: HeaderMap,
    Path(session_id): Path<String>,
    Json(params): Json<ActionStepParams>,
) -> Response {
    let caller = match authorize(&rt, &headers, "sessions") {
        Ok(c) => c,
        Err(resp) => return resp.into_response(),
    };
    match rt.act(&caller, &session_id, params).await {
        Ok(val) => Json(val).into_response(),
        Err(err) => map_runtime_error(err).into_response(),
    }
}

async fn rest_act_batch(
    State(rt): State<Arc<LiteRuntime>>,
    headers: HeaderMap,
    Path(session_id): Path<String>,
    Json(params): Json<ActBatchParams>,
) -> Response {
    let caller = match authorize(&rt, &headers, "sessions") {
        Ok(c) => c,
        Err(resp) => return resp.into_response(),
    };
    match rt.act_batch(&caller, &session_id, params).await {
        Ok(val) => Json(val).into_response(),
        Err(err) => map_runtime_error(err).into_response(),
    }
}

async fn rest_wait(
    State(rt): State<Arc<LiteRuntime>>,
    headers: HeaderMap,
    Path(session_id): Path<String>,
    Json(params): Json<WaitParams>,
) -> Response {
    let caller = match authorize(&rt, &headers, "sessions") {
        Ok(c) => c,
        Err(resp) => return resp.into_response(),
    };
    match rt.wait(&caller, &session_id, params).await {
        Ok(val) => Json(val).into_response(),
        Err(err) => map_runtime_error(err).into_response(),
    }
}

async fn rest_screenshot(
    State(rt): State<Arc<LiteRuntime>>,
    headers: HeaderMap,
    Path(session_id): Path<String>,
    Json(params): Json<ScreenshotParams>,
) -> Response {
    let caller = match authorize(&rt, &headers, "sessions") {
        Ok(c) => c,
        Err(resp) => return resp.into_response(),
    };
    match rt.screenshot(&caller, &session_id, params).await {
        Ok((data, meta)) => Json(json!({ "meta": meta, "pngBase64": data })).into_response(),
        Err(err) => map_runtime_error(err).into_response(),
    }
}

async fn rest_profile(
    State(rt): State<Arc<LiteRuntime>>,
    headers: HeaderMap,
    Path(session_id): Path<String>,
    Json(params): Json<ProfileOpParams>,
) -> Response {
    let caller = match authorize(&rt, &headers, "sessions") {
        Ok(c) => c,
        Err(resp) => return resp.into_response(),
    };
    match rt.profile_op(&caller, &session_id, params).await {
        Ok(val) => Json(val).into_response(),
        Err(err) => map_runtime_error(err).into_response(),
    }
}

async fn rest_close(
    State(rt): State<Arc<LiteRuntime>>,
    headers: HeaderMap,
    Path(session_id): Path<String>,
    Json(params): Json<CloseSessionParams>,
) -> Response {
    let caller = match authorize(&rt, &headers, "sessions") {
        Ok(c) => c,
        Err(resp) => return resp.into_response(),
    };
    match rt.close_session(&caller, &session_id, params).await {
        Ok(val) => Json(val).into_response(),
        Err(err) => map_runtime_error(err).into_response(),
    }
}

// ---------- Multi-Agent Token Administration ----------

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct CreateTokenBody {
    agent_id: String,
    label: String,
    #[serde(default = "default_agent_scopes")]
    scopes: Vec<String>,
    #[serde(default = "default_agent_max_sessions")]
    max_sessions: u32,
}

fn default_agent_scopes() -> Vec<String> {
    vec!["mcp".to_owned(), "sessions".to_owned()]
}

fn default_agent_max_sessions() -> u32 {
    2
}

async fn rest_list_tokens(State(rt): State<Arc<LiteRuntime>>, headers: HeaderMap) -> Response {
    if let Err(resp) = authorize(&rt, &headers, "admin") {
        return resp.into_response();
    }
    match rt.store.list_tokens() {
        Ok(tokens) => Json(json!({ "tokens": tokens })).into_response(),
        Err(err) => map_runtime_error(err).into_response(),
    }
}

async fn rest_create_token(
    State(rt): State<Arc<LiteRuntime>>,
    headers: HeaderMap,
    Json(body): Json<CreateTokenBody>,
) -> Response {
    let caller = match authorize(&rt, &headers, "admin") {
        Ok(c) => c,
        Err(resp) => return resp.into_response(),
    };
    let agent_id = body.agent_id.trim();
    if agent_id.is_empty() || agent_id.len() > 64 {
        return (
            StatusCode::BAD_REQUEST,
            Json(json!({ "error": "AGENT_ID_INVALID" })),
        )
            .into_response();
    }
    match rt.store.create_token(
        agent_id,
        body.label.trim(),
        body.scopes,
        body.max_sessions.clamp(1, 64),
    ) {
        Ok(issued) => {
            rt.store.record_audit(
                &caller.agent_id,
                None,
                "TOKEN_CREATE",
                "OK",
                Some(&issued.token.token_id),
            );
            (StatusCode::CREATED, Json(issued)).into_response()
        }
        Err(err) => map_runtime_error(err).into_response(),
    }
}

async fn rest_rotate_token(
    State(rt): State<Arc<LiteRuntime>>,
    headers: HeaderMap,
    Path(token_id): Path<String>,
) -> Response {
    let caller = match authorize(&rt, &headers, "admin") {
        Ok(c) => c,
        Err(resp) => return resp.into_response(),
    };
    if validate_identifier(&token_id, "tok_").is_err() {
        return (
            StatusCode::BAD_REQUEST,
            Json(json!({ "error": "TOKEN_ID_INVALID" })),
        )
            .into_response();
    }
    match rt.store.rotate_token(&token_id) {
        Ok(Some(issued)) => {
            rt.store.record_audit(
                &caller.agent_id,
                None,
                "TOKEN_ROTATE",
                "OK",
                Some(&token_id),
            );
            Json(issued).into_response()
        }
        Ok(None) => (
            StatusCode::NOT_FOUND,
            Json(json!({ "error": "TOKEN_NOT_FOUND" })),
        )
            .into_response(),
        Err(err) => map_runtime_error(err).into_response(),
    }
}

async fn rest_revoke_token(
    State(rt): State<Arc<LiteRuntime>>,
    headers: HeaderMap,
    Path(token_id): Path<String>,
) -> Response {
    let caller = match authorize(&rt, &headers, "admin") {
        Ok(c) => c,
        Err(resp) => return resp.into_response(),
    };
    if validate_identifier(&token_id, "tok_").is_err() {
        return (
            StatusCode::BAD_REQUEST,
            Json(json!({ "error": "TOKEN_ID_INVALID" })),
        )
            .into_response();
    }
    match rt.store.revoke_token(&token_id) {
        Ok(true) => {
            rt.store.record_audit(
                &caller.agent_id,
                None,
                "TOKEN_REVOKE",
                "OK",
                Some(&token_id),
            );
            Json(json!({ "revoked": true, "tokenId": token_id })).into_response()
        }
        Ok(false) => (
            StatusCode::NOT_FOUND,
            Json(json!({ "error": "TOKEN_NOT_FOUND" })),
        )
            .into_response(),
        Err(err) => map_runtime_error(err).into_response(),
    }
}

// ---------- Native Rust MCP Streamable HTTP (JSON-RPC 2.0) ----------

#[derive(Deserialize)]
struct JsonRpcRequest {
    jsonrpc: String,
    id: Option<Value>,
    method: String,
    #[serde(default)]
    params: Value,
}

async fn handle_mcp(
    State(rt): State<Arc<LiteRuntime>>,
    headers: HeaderMap,
    Json(req): Json<JsonRpcRequest>,
) -> Response {
    let caller = match authorize(&rt, &headers, "mcp") {
        Ok(c) => c,
        Err(resp) => return resp.into_response(),
    };
    if req.jsonrpc != "2.0" {
        return Json(rpc_error(req.id, -32600, "Invalid JSON-RPC version")).into_response();
    }
    match req.method.as_str() {
        "notifications/initialized" => StatusCode::ACCEPTED.into_response(),
        "ping" => Json(rpc_ok(req.id, json!({}))).into_response(),
        "initialize" => Json(rpc_ok(
            req.id,
            json!({
                "protocolVersion": MCP_PROTOCOL_VERSION,
                "capabilities": { "tools": { "listChanged": false } },
                "serverInfo": {
                    "name": "agent-browser-cloud-lite",
                    "version": env!("CARGO_PKG_VERSION"),
                }
            }),
        ))
        .into_response(),
        "tools/list" => {
            Json(rpc_ok(req.id, json!({ "tools": mcp_tool_catalog() }))).into_response()
        }
        "tools/call" => {
            let name = req
                .params
                .get("name")
                .and_then(Value::as_str)
                .unwrap_or_default();
            let arguments = req
                .params
                .get("arguments")
                .cloned()
                .unwrap_or_else(|| json!({}));
            match dispatch_mcp_tool(&rt, &caller, name, arguments).await {
                Ok(content) => Json(rpc_ok(req.id, content)).into_response(),
                Err(error) => Json(rpc_ok(
                    req.id,
                    json!({
                        "isError": true,
                        "content": [{ "type": "text", "text": error.to_string() }]
                    }),
                ))
                .into_response(),
            }
        }
        _ => Json(rpc_error(req.id, -32601, "Method not found")).into_response(),
    }
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

async fn dispatch_mcp_tool(
    rt: &LiteRuntime,
    caller: &AgentTokenRecord,
    name: &str,
    args: Value,
) -> anyhow::Result<Value> {
    let json_text = |payload: Value| -> Value {
        json!({
            "content": [{
                "type": "text",
                "text": serde_json::to_string(&payload).unwrap_or_else(|_| "{}".to_owned()),
            }],
            "structuredContent": payload,
        })
    };
    let require_session_id = |v: &Value| -> anyhow::Result<String> {
        v.get("session_id")
            .and_then(Value::as_str)
            .map(ToOwned::to_owned)
            .ok_or_else(|| anyhow::anyhow!("SESSION_ID_REQUIRED"))
    };

    match name {
        "browser_create" => {
            let params: CreateSessionParams = serde_json::from_value(args)?;
            Ok(json_text(rt.create_session(caller, params).await?))
        }
        "browser_list" => {
            let include_closed = args
                .get("include_closed")
                .and_then(Value::as_bool)
                .unwrap_or(false);
            Ok(json_text(rt.list_sessions(caller, include_closed).await?))
        }
        "browser_navigate" => {
            let session_id = require_session_id(&args)?;
            let url = args
                .get("url")
                .and_then(Value::as_str)
                .ok_or_else(|| anyhow::anyhow!("URL_REQUIRED"))?;
            Ok(json_text(rt.navigate(caller, &session_id, url).await?))
        }
        "browser_observe" => {
            let session_id = require_session_id(&args)?;
            let params: ObserveParams = serde_json::from_value(args)?;
            Ok(json_text(rt.observe(caller, &session_id, params).await?))
        }
        "browser_act" => {
            let session_id = require_session_id(&args)?;
            let params: ActionStepParams = serde_json::from_value(args)?;
            Ok(json_text(rt.act(caller, &session_id, params).await?))
        }
        "browser_act_batch" => {
            let session_id = require_session_id(&args)?;
            let params: ActBatchParams = serde_json::from_value(args)?;
            Ok(json_text(rt.act_batch(caller, &session_id, params).await?))
        }
        "browser_wait" => {
            let session_id = require_session_id(&args)?;
            let params: WaitParams = serde_json::from_value(args)?;
            Ok(json_text(rt.wait(caller, &session_id, params).await?))
        }
        "browser_screenshot" => {
            let session_id = require_session_id(&args)?;
            let params: ScreenshotParams = serde_json::from_value(args)?;
            let (b64_png, meta) = rt.screenshot(caller, &session_id, params).await?;
            Ok(json!({
                "content": [
                    { "type": "image", "data": b64_png, "mimeType": "image/png" },
                    { "type": "text", "text": serde_json::to_string(&meta).unwrap_or_default() }
                ],
                "structuredContent": meta,
            }))
        }
        "browser_profile" => {
            let session_id = require_session_id(&args)?;
            let params: ProfileOpParams = serde_json::from_value(args)?;
            Ok(json_text(rt.profile_op(caller, &session_id, params).await?))
        }
        "browser_close" => {
            let session_id = require_session_id(&args)?;
            let params: CloseSessionParams = serde_json::from_value(args)?;
            Ok(json_text(
                rt.close_session(caller, &session_id, params).await?,
            ))
        }
        _ => anyhow::bail!("TOOL_NOT_FOUND"),
    }
}

/// Converged 10-tool MCP catalog (Section 6.3).
pub fn mcp_tool_catalog() -> Vec<Value> {
    vec![
        json!({
            "name": "browser_create",
            "description": "Create and optionally start an isolated headless Chromium browser session with its own Profile and optional proxy.",
            "inputSchema": {
                "type": "object",
                "properties": {
                    "name": { "type": "string" },
                    "profile_id": { "type": "string" },
                    "proxy_url": { "type": "string" },
                    "start_url": { "type": "string" },
                    "idle_timeout_seconds": { "type": "integer", "minimum": 30, "maximum": 86400 },
                    "start": { "type": "boolean", "default": true }
                }
            }
        }),
        json!({
            "name": "browser_list",
            "description": "List browser sessions owned by the current Agent token and report node backpressure status.",
            "inputSchema": {
                "type": "object",
                "properties": {
                    "include_closed": { "type": "boolean", "default": false }
                }
            }
        }),
        json!({
            "name": "browser_navigate",
            "description": "Navigate a browser session's active tab to an SSRF-validated HTTP(S) URL. Auto-wakes hibernated sessions.",
            "inputSchema": {
                "type": "object",
                "required": ["session_id", "url"],
                "properties": {
                    "session_id": { "type": "string" },
                    "url": { "type": "string" }
                }
            }
        }),
        json!({
            "name": "browser_observe",
            "description": "Observe page state using mode: 'tree' (full structured summary), 'find' (semantic search including offscreen elements and <select> options), 'inspect' (exact element IDs), or 'diff' (incremental DOM delta since since_state_version).",
            "inputSchema": {
                "type": "object",
                "required": ["session_id"],
                "properties": {
                    "session_id": { "type": "string" },
                    "mode": { "type": "string", "enum": ["tree", "find", "inspect", "diff"], "default": "tree" },
                    "query": { "type": "string" },
                    "roles": { "type": "array", "items": { "type": "string" } },
                    "include_hidden": { "type": "boolean", "default": false },
                    "limit": { "type": "integer", "minimum": 1, "maximum": 100 },
                    "element_ids": { "type": "array", "items": { "type": "string" } },
                    "since_state_version": { "type": "integer" }
                }
            }
        }),
        json!({
            "name": "browser_act",
            "description": "Execute one smooth browser action (click, double_click, right_click, hover, fill, type, clear, select, check, uncheck, press_key, drag, swipe, scroll, mouse_down, mouse_up, drop, open_tab, switch_tab, close_tab, accept_dialog, dismiss_dialog). Auto-scrolls offscreen targets and clicks open custom dropdowns.",
            "inputSchema": {
                "type": "object",
                "required": ["session_id", "action"],
                "properties": {
                    "session_id": { "type": "string" },
                    "action": { "type": "string" },
                    "target": { "type": "string" },
                    "target_revision": { "type": "integer" },
                    "end_target": { "type": "string" },
                    "value": { "type": "string" },
                    "key": { "type": "string" },
                    "button": { "type": "integer" },
                    "delta_x": { "type": "integer" },
                    "delta_y": { "type": "integer" },
                    "duration_ms": { "type": "integer" },
                    "tab_id": { "type": "string" },
                    "tab_url": { "type": "string" },
                    "dialog_id": { "type": "string" }
                }
            }
        }),
        json!({
            "name": "browser_act_batch",
            "description": "Execute 1–20 deterministic browser steps sequentially with per-step timing, stop_on_error, and stable element rebinding.",
            "inputSchema": {
                "type": "object",
                "required": ["session_id", "steps"],
                "properties": {
                    "session_id": { "type": "string" },
                    "steps": { "type": "array", "minItems": 1, "maxItems": 20, "items": { "type": "object" } },
                    "stop_on_error": { "type": "boolean", "default": true },
                    "max_duration_ms": { "type": "integer", "minimum": 1000, "maximum": 180000 }
                }
            }
        }),
        json!({
            "name": "browser_wait",
            "description": "Wait for regional/element readiness ('element_ready'), DOM quiet ('dom_quiet'), or document stability ('state_stable') without relying solely on whole-page networkidle.",
            "inputSchema": {
                "type": "object",
                "required": ["session_id"],
                "properties": {
                    "session_id": { "type": "string" },
                    "mode": { "type": "string", "enum": ["element_ready", "dom_quiet", "state_stable"], "default": "element_ready" },
                    "target": { "type": "string" },
                    "timeout_ms": { "type": "integer", "minimum": 100, "maximum": 30000 }
                }
            }
        }),
        json!({
            "name": "browser_screenshot",
            "description": "Capture a PNG screenshot of the active viewport, full page, or a specific element without exposing raw CDP.",
            "inputSchema": {
                "type": "object",
                "required": ["session_id"],
                "properties": {
                    "session_id": { "type": "string" },
                    "target": { "type": "string" },
                    "full_page": { "type": "boolean", "default": false }
                }
            }
        }),
        json!({
            "name": "browser_profile",
            "description": "Save, restore, or list local Profile checkpoints (preserves Cookies/LocalStorage/IndexedDB while excluding disposable caches, verified by SHA-256 manifest).",
            "inputSchema": {
                "type": "object",
                "required": ["session_id", "action"],
                "properties": {
                    "session_id": { "type": "string" },
                    "action": { "type": "string", "enum": ["save", "restore", "list"] },
                    "checkpoint_id": { "type": "string" }
                }
            }
        }),
        json!({
            "name": "browser_close",
            "description": "Hibernate (stop Chromium, keep Profile for auto-wake) or permanently close a browser session.",
            "inputSchema": {
                "type": "object",
                "required": ["session_id"],
                "properties": {
                    "session_id": { "type": "string" },
                    "hibernate": { "type": "boolean", "default": true }
                }
            }
        }),
    ]
}
