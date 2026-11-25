//! Native Rust MCP Streamable HTTP (10 converged tools) + REST API router for L0 Lite.

use crate::backpressure::BackpressureState;
use crate::runtime::{
    ActBatchParams, ActionStepParams, CloseSessionParams, CreateCredentialParams,
    CreateProfileParams, CreateSessionParams, IntranetAuthMode, LiteRuntime, ObserveParams,
    OpenProfileParams, ProfileOpParams, ScreenshotParams, WaitParams,
};
use crate::security::{matches_allowlist, validate_identifier};
use crate::store::AgentTokenRecord;
use axum::body::Body;
use axum::extract::{Path, Query, Request, State};
use axum::http::{header, HeaderMap, HeaderValue, StatusCode};
use axum::middleware::{self, Next};
use axum::response::{IntoResponse, Response};
use axum::routing::{delete, get, patch, post};
use axum::{Json, Router};
use base64::Engine as _;
use futures_util::{SinkExt, StreamExt};
use hyper_util::rt::TokioIo;
use ring::digest;
use serde::Deserialize;
use serde_json::{json, Value};
use std::sync::atomic::Ordering;
use std::sync::Arc;
use tokio_tungstenite::tungstenite::protocol::Role;
use tokio_tungstenite::WebSocketStream;
use uuid::Uuid;

pub const MCP_PROTOCOL_VERSION: &str = "2025-03-26";

pub fn build_router(runtime: Arc<LiteRuntime>) -> Router {
    Router::new()
        .route("/healthz", get(healthz))
        .route("/metrics", get(metrics_prometheus))
        .route("/mcp", post(handle_mcp))
        .route("/api/v1/lite/events", get(rest_events_sse))
        .route(
            "/api/v1/lite/credentials",
            get(rest_list_credentials).post(rest_create_credential),
        )
        .route(
            "/api/v1/lite/credentials/:credential_id",
            delete(rest_revoke_credential),
        )
        .route(
            "/api/v1/lite/sessions",
            get(rest_list_sessions).post(rest_create_session),
        )
        .route(
            "/api/v1/lite/sessions/:session_id/cdp",
            get(rest_cdp_ws_proxy),
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
        .route(
            "/api/v1/lite/sessions/:session_id/network-policy",
            get(rest_get_session_network_policy).patch(rest_update_session_network_policy),
        )
        .route(
            "/api/v1/lite/sessions/:session_id/network-policy/authorize",
            post(rest_authorize_session_private_hosts),
        )
        .route(
            "/api/v1/lite/sessions/:session_id/network-policy/grants",
            delete(rest_revoke_session_private_hosts),
        )
        .route("/api/v1/lite/sessions/:session_id/close", post(rest_close))
        .route(
            "/api/v1/lite/profiles",
            get(rest_list_profiles).post(rest_create_profile),
        )
        .route(
            "/api/v1/lite/profiles/:profile_id/open",
            post(rest_open_profile),
        )
        .route(
            "/api/v1/lite/profiles/:profile_id/grants",
            post(rest_grant_profile),
        )
        .route(
            "/api/v1/lite/profiles/:profile_id/grants/:agent_id",
            delete(rest_revoke_profile_grant),
        )
        .route(
            "/api/v1/lite/profiles/:profile_id/checkpoints",
            get(rest_list_profile_checkpoints),
        )
        .route(
            "/api/v1/lite/profiles/:profile_id/restore",
            post(rest_restore_profile_by_id),
        )
        .route(
            "/api/v1/lite/profiles/:profile_id/network-policy",
            patch(rest_update_profile_network_policy),
        )
        .route(
            "/api/v1/lite/tokens",
            get(rest_list_tokens).post(rest_create_token),
        )
        .route(
            "/api/v1/lite/tokens/:token_id/rotate",
            post(rest_rotate_token),
        )
        .route(
            "/api/v1/lite/tokens/:token_id",
            patch(rest_update_token_quota).delete(rest_revoke_token),
        )
        .layer(middleware::from_fn_with_state(
            runtime.clone(),
            trace_and_metrics_middleware,
        ))
        .with_state(runtime)
}

async fn trace_and_metrics_middleware(
    State(rt): State<Arc<LiteRuntime>>,
    req: Request,
    next: Next,
) -> Response {
    rt.metrics.requests_total.fetch_add(1, Ordering::Relaxed);
    let trace_id = req
        .headers()
        .get("x-trace-id")
        .and_then(|v| v.to_str().ok())
        .map(str::trim)
        .filter(|s| !s.is_empty() && s.len() <= 128 && s.is_ascii())
        .map(ToOwned::to_owned)
        .unwrap_or_else(|| format!("trc_{}", &Uuid::new_v4().simple().to_string()[..16]));
    let mut resp = next.run(req).await;
    if let Ok(val) = HeaderValue::from_str(&trace_id) {
        resp.headers_mut().insert("x-trace-id", val);
    }
    resp
}

async fn metrics_prometheus(State(rt): State<Arc<LiteRuntime>>) -> Response {
    let bp = rt.backpressure_snapshot().await;
    let level = match bp.state {
        BackpressureState::Normal => 0,
        BackpressureState::Degraded => 1,
        BackpressureState::QueueOnly => 2,
        BackpressureState::NoNewSessions => 3,
        BackpressureState::Recovering => 4,
        BackpressureState::Closed => 5,
    };
    let running = rt.store.count_running_sessions_total().unwrap_or(0) as u64;
    let warm = rt.warm_pool_len().await as u64;
    let body = rt.metrics.render_prometheus(
        running,
        warm,
        rt.config.max_sessions as u64,
        level,
        rt.events.total_published(),
    );
    (
        StatusCode::OK,
        [(
            header::CONTENT_TYPE,
            "text/plain; version=0.0.4; charset=utf-8",
        )],
        body,
    )
        .into_response()
}

async fn healthz(State(rt): State<Arc<LiteRuntime>>) -> Json<Value> {
    let bp = rt.backpressure_snapshot().await;
    let running = rt.store.count_running_sessions_total().unwrap_or(0);
    Json(json!({
        "status": "UP",
        "tier": "L0_LITE",
        "runtime": "rust",
        "runningSessions": running,
        "warmPoolReady": rt.warm_pool_len().await,
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
    if let Some(host) = msg.strip_prefix("PRIVATE_NETWORK_HOST_NOT_AUTHORIZED:") {
        return (
            StatusCode::FORBIDDEN,
            Json(json!({
                "error": msg,
                "host": host,
                "sessionPreserved": true,
                "authorizationOptions": ["once", "permanent"],
            })),
        );
    }
    let status = if msg.contains("ACCESS_DENIED")
        || msg.contains("FORBIDDEN")
        || msg.contains("NOT_AUTHORIZED")
        || msg.contains("ADMIN_SCOPE_REQUIRED")
    {
        StatusCode::FORBIDDEN
    } else if msg.contains("ALREADY_IN_USE") {
        StatusCode::CONFLICT
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

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct NetworkPolicyBody {
    #[serde(default, alias = "allowed_private_hosts")]
    allowed_private_hosts: Vec<String>,
    #[serde(default)]
    mode: Option<IntranetAuthMode>,
    #[serde(default, alias = "ttl_seconds")]
    ttl_seconds: Option<u64>,
    #[serde(default)]
    replace: bool,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct AuthorizePrivateHostsBody {
    #[serde(default)]
    host: Option<String>,
    #[serde(default, alias = "allowed_private_hosts")]
    hosts: Vec<String>,
    #[serde(default = "default_intranet_mode")]
    mode: IntranetAuthMode,
    #[serde(default, alias = "ttl_seconds")]
    ttl_seconds: Option<u64>,
}

fn default_intranet_mode() -> IntranetAuthMode {
    IntranetAuthMode::Once
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct RevokePrivateHostsBody {
    #[serde(default)]
    host: Option<String>,
    #[serde(default)]
    hosts: Vec<String>,
    #[serde(default)]
    mode: Option<IntranetAuthMode>,
}

async fn rest_get_session_network_policy(
    State(rt): State<Arc<LiteRuntime>>,
    headers: HeaderMap,
    Path(session_id): Path<String>,
) -> Response {
    let caller = match authorize(&rt, &headers, "sessions") {
        Ok(c) => c,
        Err(resp) => return resp.into_response(),
    };
    match rt.get_session_network_policy(&caller, &session_id).await {
        Ok(val) => Json(val).into_response(),
        Err(err) => map_runtime_error(err).into_response(),
    }
}

async fn rest_update_session_network_policy(
    State(rt): State<Arc<LiteRuntime>>,
    headers: HeaderMap,
    Path(session_id): Path<String>,
    Json(body): Json<NetworkPolicyBody>,
) -> Response {
    let caller = match authorize(&rt, &headers, "sessions") {
        Ok(c) => c,
        Err(resp) => return resp.into_response(),
    };
    let result = match body.mode {
        Some(mode) if !body.replace => {
            rt.authorize_session_private_hosts(
                &caller,
                &session_id,
                &body.allowed_private_hosts,
                mode,
                body.ttl_seconds,
            )
            .await
        }
        _ => {
            rt.update_session_network_policy(&caller, &session_id, &body.allowed_private_hosts)
                .await
        }
    };
    match result {
        Ok(val) => Json(val).into_response(),
        Err(err) => map_runtime_error(err).into_response(),
    }
}

async fn rest_authorize_session_private_hosts(
    State(rt): State<Arc<LiteRuntime>>,
    headers: HeaderMap,
    Path(session_id): Path<String>,
    Json(body): Json<AuthorizePrivateHostsBody>,
) -> Response {
    let caller = match authorize(&rt, &headers, "sessions") {
        Ok(c) => c,
        Err(resp) => return resp.into_response(),
    };
    let mut hosts = body.hosts;
    if let Some(single) = body.host {
        hosts.push(single);
    }
    match rt
        .authorize_session_private_hosts(&caller, &session_id, &hosts, body.mode, body.ttl_seconds)
        .await
    {
        Ok(val) => Json(val).into_response(),
        Err(err) => map_runtime_error(err).into_response(),
    }
}

async fn rest_revoke_session_private_hosts(
    State(rt): State<Arc<LiteRuntime>>,
    headers: HeaderMap,
    Path(session_id): Path<String>,
    Json(body): Json<RevokePrivateHostsBody>,
) -> Response {
    let caller = match authorize(&rt, &headers, "sessions") {
        Ok(c) => c,
        Err(resp) => return resp.into_response(),
    };
    let mut hosts = body.hosts;
    if let Some(single) = body.host {
        hosts.push(single);
    }
    match rt
        .revoke_session_private_hosts(&caller, &session_id, &hosts, body.mode)
        .await
    {
        Ok(val) => Json(val).into_response(),
        Err(err) => map_runtime_error(err).into_response(),
    }
}

// ---------- Admin-Owned Profile Management, Authorization & Collaboration ----------

async fn rest_list_profiles(State(rt): State<Arc<LiteRuntime>>, headers: HeaderMap) -> Response {
    let caller = match authorize(&rt, &headers, "sessions") {
        Ok(c) => c,
        Err(resp) => return resp.into_response(),
    };
    match rt.list_profiles(&caller) {
        Ok(val) => Json(val).into_response(),
        Err(err) => map_runtime_error(err).into_response(),
    }
}

async fn rest_create_profile(
    State(rt): State<Arc<LiteRuntime>>,
    headers: HeaderMap,
    Json(params): Json<CreateProfileParams>,
) -> Response {
    let caller = match authorize(&rt, &headers, "admin") {
        Ok(c) => c,
        Err(resp) => return resp.into_response(),
    };
    match rt.create_profile(&caller, params) {
        Ok(val) => (StatusCode::CREATED, Json(val)).into_response(),
        Err(err) => map_runtime_error(err).into_response(),
    }
}

async fn rest_open_profile(
    State(rt): State<Arc<LiteRuntime>>,
    headers: HeaderMap,
    Path(profile_id): Path<String>,
    Json(params): Json<OpenProfileParams>,
) -> Response {
    let caller = match authorize(&rt, &headers, "sessions") {
        Ok(c) => c,
        Err(resp) => return resp.into_response(),
    };
    match rt.open_profile(&caller, &profile_id, params).await {
        Ok(val) => Json(val).into_response(),
        Err(err) => map_runtime_error(err).into_response(),
    }
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct GrantProfileBody {
    #[serde(alias = "agent_id")]
    agent_id: String,
}

async fn rest_grant_profile(
    State(rt): State<Arc<LiteRuntime>>,
    headers: HeaderMap,
    Path(profile_id): Path<String>,
    Json(body): Json<GrantProfileBody>,
) -> Response {
    let caller = match authorize(&rt, &headers, "admin") {
        Ok(c) => c,
        Err(resp) => return resp.into_response(),
    };
    match rt.grant_profile(&caller, &profile_id, &body.agent_id) {
        Ok(val) => Json(val).into_response(),
        Err(err) => map_runtime_error(err).into_response(),
    }
}

async fn rest_revoke_profile_grant(
    State(rt): State<Arc<LiteRuntime>>,
    headers: HeaderMap,
    Path((profile_id, agent_id)): Path<(String, String)>,
) -> Response {
    let caller = match authorize(&rt, &headers, "admin") {
        Ok(c) => c,
        Err(resp) => return resp.into_response(),
    };
    match rt.revoke_profile_grant(&caller, &profile_id, &agent_id) {
        Ok(val) => Json(val).into_response(),
        Err(err) => map_runtime_error(err).into_response(),
    }
}

async fn rest_list_profile_checkpoints(
    State(rt): State<Arc<LiteRuntime>>,
    headers: HeaderMap,
    Path(profile_id): Path<String>,
) -> Response {
    let caller = match authorize(&rt, &headers, "sessions") {
        Ok(c) => c,
        Err(resp) => return resp.into_response(),
    };
    match rt.list_profile_checkpoints(&caller, &profile_id) {
        Ok(val) => Json(val).into_response(),
        Err(err) => map_runtime_error(err).into_response(),
    }
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct RestoreProfileBody {
    #[serde(alias = "checkpoint_id")]
    checkpoint_id: String,
}

async fn rest_restore_profile_by_id(
    State(rt): State<Arc<LiteRuntime>>,
    headers: HeaderMap,
    Path(profile_id): Path<String>,
    Json(body): Json<RestoreProfileBody>,
) -> Response {
    let caller = match authorize(&rt, &headers, "sessions") {
        Ok(c) => c,
        Err(resp) => return resp.into_response(),
    };
    match rt
        .restore_profile_by_id(&caller, &profile_id, &body.checkpoint_id)
        .await
    {
        Ok(val) => Json(val).into_response(),
        Err(err) => map_runtime_error(err).into_response(),
    }
}

async fn rest_update_profile_network_policy(
    State(rt): State<Arc<LiteRuntime>>,
    headers: HeaderMap,
    Path(profile_id): Path<String>,
    Json(body): Json<NetworkPolicyBody>,
) -> Response {
    let caller = match authorize(&rt, &headers, "admin") {
        Ok(c) => c,
        Err(resp) => return resp.into_response(),
    };
    match rt
        .update_profile_network_policy(&caller, &profile_id, &body.allowed_private_hosts)
        .await
    {
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

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct UpdateTokenQuotaBody {
    #[serde(alias = "max_sessions")]
    max_sessions: u32,
}

async fn rest_update_token_quota(
    State(rt): State<Arc<LiteRuntime>>,
    headers: HeaderMap,
    Path(token_id): Path<String>,
    Json(body): Json<UpdateTokenQuotaBody>,
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
    match rt
        .store
        .update_token_quota(&token_id, body.max_sessions.clamp(1, 64))
    {
        Ok(Some(token)) => {
            rt.store.record_audit(
                &caller.agent_id,
                None,
                "TOKEN_QUOTA_UPDATE",
                "OK",
                Some(&format!("{token_id}:{}", token.max_sessions)),
            );
            Json(json!({ "token": token })).into_response()
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

// ---------- Credential Vault, SSE Events, and Authenticated CDP Proxy ----------

#[derive(Deserialize, Default)]
struct SseQuery {
    last_event_id: Option<u64>,
    #[serde(default)]
    once: bool,
}

async fn rest_events_sse(
    State(rt): State<Arc<LiteRuntime>>,
    headers: HeaderMap,
    Query(query): Query<SseQuery>,
) -> Response {
    let caller = match authorize(&rt, &headers, "sessions") {
        Ok(c) => c,
        Err(resp) => return resp.into_response(),
    };
    let last_id = headers
        .get("last-event-id")
        .and_then(|v| v.to_str().ok())
        .and_then(|s| s.trim().parse::<u64>().ok())
        .or(query.last_event_id)
        .unwrap_or(0);
    let is_admin = caller.has_scope("admin");
    let replayed = rt.events.replay_since(last_id, &caller.agent_id, is_admin);

    if query.once {
        let mut buf = String::new();
        for ev in replayed {
            let data = serde_json::to_string(&ev).unwrap_or_else(|_| "{}".to_owned());
            buf.push_str(&format!(
                "id: {}\nevent: {}\ndata: {}\n\n",
                ev.id, ev.event_type, data
            ));
        }
        return (
            StatusCode::OK,
            [
                (header::CONTENT_TYPE, "text/event-stream; charset=utf-8"),
                (header::CACHE_CONTROL, "no-cache"),
            ],
            buf,
        )
            .into_response();
    }

    let mut rx = rt.events.subscribe();
    let owner_id = caller.agent_id.clone();
    let (tx, mut out_rx) = tokio::sync::mpsc::channel::<Result<String, std::io::Error>>(64);
    tokio::spawn(async move {
        let mut high_water = last_id;
        for ev in replayed {
            high_water = high_water.max(ev.id);
            let data = serde_json::to_string(&ev).unwrap_or_else(|_| "{}".to_owned());
            let frame = format!(
                "id: {}\nevent: {}\ndata: {}\n\n",
                ev.id, ev.event_type, data
            );
            if tx.send(Ok(frame)).await.is_err() {
                return;
            }
        }
        loop {
            match rx.recv().await {
                Ok(ev) => {
                    if ev.id <= high_water {
                        continue;
                    }
                    if !is_admin && ev.owner_agent_id != owner_id {
                        continue;
                    }
                    high_water = ev.id;
                    let data = serde_json::to_string(&ev).unwrap_or_else(|_| "{}".to_owned());
                    let frame = format!(
                        "id: {}\nevent: {}\ndata: {}\n\n",
                        ev.id, ev.event_type, data
                    );
                    if tx.send(Ok(frame)).await.is_err() {
                        return;
                    }
                }
                Err(tokio::sync::broadcast::error::RecvError::Lagged(_)) => {
                    let _ = tx
                        .send(Ok(
                            "event: resync\ndata: {\"reason\":\"STREAM_LAGGED\"}\n\n".to_owned(),
                        ))
                        .await;
                }
                Err(tokio::sync::broadcast::error::RecvError::Closed) => return,
            }
        }
    });

    let stream = futures_util::stream::poll_fn(move |cx| out_rx.poll_recv(cx));
    Response::builder()
        .status(StatusCode::OK)
        .header(header::CONTENT_TYPE, "text/event-stream; charset=utf-8")
        .header(header::CACHE_CONTROL, "no-cache")
        .body(Body::from_stream(stream))
        .unwrap_or_else(|_| StatusCode::INTERNAL_SERVER_ERROR.into_response())
}

async fn rest_list_credentials(State(rt): State<Arc<LiteRuntime>>, headers: HeaderMap) -> Response {
    let caller = match authorize(&rt, &headers, "sessions") {
        Ok(c) => c,
        Err(resp) => return resp.into_response(),
    };
    match rt.list_credentials(&caller) {
        Ok(creds) => Json(json!({ "credentials": creds })).into_response(),
        Err(err) => map_runtime_error(err).into_response(),
    }
}

async fn rest_create_credential(
    State(rt): State<Arc<LiteRuntime>>,
    headers: HeaderMap,
    Json(params): Json<CreateCredentialParams>,
) -> Response {
    let caller = match authorize(&rt, &headers, "sessions") {
        Ok(c) => c,
        Err(resp) => return resp.into_response(),
    };
    match rt.create_credential(&caller, params) {
        Ok(meta) => (StatusCode::CREATED, Json(json!({ "credential": meta }))).into_response(),
        Err(err) => map_runtime_error(err).into_response(),
    }
}

async fn rest_revoke_credential(
    State(rt): State<Arc<LiteRuntime>>,
    headers: HeaderMap,
    Path(credential_id): Path<String>,
) -> Response {
    let caller = match authorize(&rt, &headers, "sessions") {
        Ok(c) => c,
        Err(resp) => return resp.into_response(),
    };
    match rt.revoke_credential(&caller, &credential_id) {
        Ok(true) => Json(json!({ "revoked": true, "credentialId": credential_id })).into_response(),
        Ok(false) => (
            StatusCode::NOT_FOUND,
            Json(json!({ "error": "CREDENTIAL_NOT_FOUND" })),
        )
            .into_response(),
        Err(err) => map_runtime_error(err).into_response(),
    }
}

/// Authenticated, ownership-checked RFC 6455 WebSocket proxy for Playwright/CDP clients.
/// Never exposes raw Chromium CDP ports externally.
async fn rest_cdp_ws_proxy(
    State(rt): State<Arc<LiteRuntime>>,
    Path(session_id): Path<String>,
    req: Request,
) -> Response {
    let caller = match authorize(&rt, req.headers(), "sessions") {
        Ok(c) => c,
        Err(resp) => return resp.into_response(),
    };
    let Some(ws_key) = req
        .headers()
        .get("sec-websocket-key")
        .and_then(|v| v.to_str().ok())
        .map(ToOwned::to_owned)
    else {
        return (
            StatusCode::UPGRADE_REQUIRED,
            Json(json!({ "error": "WEBSOCKET_UPGRADE_REQUIRED" })),
        )
            .into_response();
    };
    let upstream_ws_url = match rt.active_page_ws_for_agent(&caller, &session_id).await {
        Ok(url) => url,
        Err(err) => return map_runtime_error(err).into_response(),
    };
    let (upstream_ws, _) = match tokio_tungstenite::connect_async(&upstream_ws_url).await {
        Ok(pair) => pair,
        Err(_) => {
            return (
                StatusCode::BAD_GATEWAY,
                Json(json!({ "error": "CDP_UPSTREAM_CONNECT_FAILED" })),
            )
                .into_response();
        }
    };

    let mut accept_input = ws_key.into_bytes();
    accept_input.extend_from_slice(b"258EAFA5-E914-47DA-95CA-C5AB0DC85B11");
    let accept_hash = digest::digest(&digest::SHA1_FOR_LEGACY_USE_ONLY, &accept_input);
    let accept_val = base64::engine::general_purpose::STANDARD.encode(accept_hash.as_ref());

    let metrics = rt.metrics.clone();
    metrics.cdp_proxy_total.fetch_add(1, Ordering::Relaxed);
    metrics.cdp_proxy_active.fetch_add(1, Ordering::Relaxed);

    tokio::spawn(async move {
        if let Ok(upgraded) = hyper::upgrade::on(req).await {
            let client_ws =
                WebSocketStream::from_raw_socket(TokioIo::new(upgraded), Role::Server, None).await;
            let (mut c_sink, mut c_stream) = client_ws.split();
            let (mut u_sink, mut u_stream) = upstream_ws.split();
            let c2u = async {
                while let Some(Ok(msg)) = c_stream.next().await {
                    if u_sink.send(msg).await.is_err() {
                        break;
                    }
                }
            };
            let u2c = async {
                while let Some(Ok(msg)) = u_stream.next().await {
                    if c_sink.send(msg).await.is_err() {
                        break;
                    }
                }
            };
            tokio::select! {
                _ = c2u => {}
                _ = u2c => {}
            }
        }
        metrics.cdp_proxy_active.fetch_sub(1, Ordering::Relaxed);
    });

    Response::builder()
        .status(StatusCode::SWITCHING_PROTOCOLS)
        .header(header::UPGRADE, "websocket")
        .header(header::CONNECTION, "Upgrade")
        .header("sec-websocket-accept", accept_val)
        .body(Body::empty())
        .unwrap_or_else(|_| StatusCode::INTERNAL_SERVER_ERROR.into_response())
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
    rt.metrics.mcp_calls_total.fetch_add(1, Ordering::Relaxed);
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
            let action = args
                .get("action")
                .and_then(Value::as_str)
                .unwrap_or_default();
            match action {
                "list_profiles" => Ok(json_text(rt.list_profiles(caller)?)),
                "open" => {
                    let profile_id = args
                        .get("profile_id")
                        .and_then(Value::as_str)
                        .ok_or_else(|| anyhow::anyhow!("PROFILE_ID_REQUIRED"))?;
                    let params: OpenProfileParams =
                        serde_json::from_value(args.clone()).unwrap_or(OpenProfileParams {
                            name: None,
                            proxy_url: None,
                            start_url: None,
                            allowed_private_hosts: Vec::new(),
                            idle_timeout_seconds: None,
                            start: true,
                        });
                    Ok(json_text(
                        rt.open_profile(caller, profile_id, params).await?,
                    ))
                }
                "grant" => {
                    let profile_id = args
                        .get("profile_id")
                        .and_then(Value::as_str)
                        .ok_or_else(|| anyhow::anyhow!("PROFILE_ID_REQUIRED"))?;
                    let agent_id = args
                        .get("agent_id")
                        .and_then(Value::as_str)
                        .ok_or_else(|| anyhow::anyhow!("AGENT_ID_REQUIRED"))?;
                    Ok(json_text(rt.grant_profile(caller, profile_id, agent_id)?))
                }
                "revoke" => {
                    let profile_id = args
                        .get("profile_id")
                        .and_then(Value::as_str)
                        .ok_or_else(|| anyhow::anyhow!("PROFILE_ID_REQUIRED"))?;
                    let agent_id = args
                        .get("agent_id")
                        .and_then(Value::as_str)
                        .ok_or_else(|| anyhow::anyhow!("AGENT_ID_REQUIRED"))?;
                    Ok(json_text(
                        rt.revoke_profile_grant(caller, profile_id, agent_id)?,
                    ))
                }
                "authorize_private_host" => {
                    let session_id = require_session_id(&args)?;
                    let mut hosts: Vec<String> = args
                        .get("hosts")
                        .and_then(Value::as_array)
                        .map(|arr| {
                            arr.iter()
                                .filter_map(Value::as_str)
                                .map(ToOwned::to_owned)
                                .collect()
                        })
                        .unwrap_or_default();
                    if let Some(h) = args.get("host").and_then(Value::as_str) {
                        hosts.push(h.to_owned());
                    }
                    let mode = match args.get("mode").and_then(Value::as_str) {
                        Some("permanent") => IntranetAuthMode::Permanent,
                        Some("once") | None => IntranetAuthMode::Once,
                        Some(_) => anyhow::bail!("INTRANET_AUTH_MODE_INVALID"),
                    };
                    let ttl_seconds = args.get("ttl_seconds").and_then(Value::as_u64);
                    Ok(json_text(
                        rt.authorize_session_private_hosts(
                            caller,
                            &session_id,
                            &hosts,
                            mode,
                            ttl_seconds,
                        )
                        .await?,
                    ))
                }
                "revoke_private_host" => {
                    let session_id = require_session_id(&args)?;
                    let mut hosts: Vec<String> = args
                        .get("hosts")
                        .and_then(Value::as_array)
                        .map(|arr| {
                            arr.iter()
                                .filter_map(Value::as_str)
                                .map(ToOwned::to_owned)
                                .collect()
                        })
                        .unwrap_or_default();
                    if let Some(h) = args.get("host").and_then(Value::as_str) {
                        hosts.push(h.to_owned());
                    }
                    let mode = match args.get("mode").and_then(Value::as_str) {
                        Some("permanent") => Some(IntranetAuthMode::Permanent),
                        Some("once") => Some(IntranetAuthMode::Once),
                        None => None,
                        Some(_) => anyhow::bail!("INTRANET_AUTH_MODE_INVALID"),
                    };
                    Ok(json_text(
                        rt.revoke_session_private_hosts(caller, &session_id, &hosts, mode)
                            .await?,
                    ))
                }
                _ => {
                    let session_id = require_session_id(&args)?;
                    let params: ProfileOpParams = serde_json::from_value(args)?;
                    Ok(json_text(rt.profile_op(caller, &session_id, params).await?))
                }
            }
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
                    "isolation_mode": {
                        "type": "string",
                        "enum": ["process", "context"],
                        "default": "process",
                        "description": "'process' (default, strong per-session Chromium process isolation) or 'context' (lightweight opt-in same-agent isolation; does not provide process-level isolation)"
                    },
                    "allowed_private_hosts": {
                        "type": "array",
                        "items": { "type": "string" },
                        "description": "Optional per-environment intranet host/CIDR allowlist (e.g. ['127.0.0.1:3000', '192.168.1.0/24', 'admin.internal']). Public internet works by default; unauthorized private requests are rejected without stopping the browser."
                    },
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
                    "value_ref": { "type": "string", "description": "Credential Vault reference (cred_...) resolved strictly in memory on authorized domains" },
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
            "description": "Wait for regional/element readiness ('element_ready'), DOM quiet ('dom_quiet'), document stability ('state_stable'), visual frame stability ('visual_stable'), or app predicates ('app_condition').",
            "inputSchema": {
                "type": "object",
                "required": ["session_id"],
                "properties": {
                    "session_id": { "type": "string" },
                    "mode": { "type": "string", "enum": ["element_ready", "dom_quiet", "state_stable", "visual_stable", "app_condition"], "default": "element_ready" },
                    "target": { "type": "string" },
                    "text": { "type": "string" },
                    "url_contains": { "type": "string" },
                    "title_contains": { "type": "string" },
                    "stable_frames": { "type": "integer", "minimum": 2, "maximum": 5 },
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
            "description": "Manage admin-owned Profiles, checkpoints, and per-environment intranet authorization (once vs permanent): save/restore/list checkpoints, list_profiles, open (collaborate or start single-writer), grant/revoke Agent access (admin), or authorize_private_host/revoke_private_host.",
            "inputSchema": {
                "type": "object",
                "required": ["action"],
                "properties": {
                    "session_id": { "type": "string" },
                    "profile_id": { "type": "string" },
                    "agent_id": { "type": "string" },
                    "host": { "type": "string", "description": "Intranet host/port/CIDR for authorize_private_host or revoke_private_host" },
                    "hosts": { "type": "array", "items": { "type": "string" } },
                    "mode": { "type": "string", "enum": ["once", "permanent"], "description": "'once' (temporary for current session only) or 'permanent' (persisted on environment/profile so it never prompts again)" },
                    "ttl_seconds": { "type": "integer", "minimum": 10, "maximum": 86400 },
                    "action": { "type": "string", "enum": ["save", "restore", "list", "list_profiles", "open", "grant", "revoke", "authorize_private_host", "revoke_private_host"] },
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
