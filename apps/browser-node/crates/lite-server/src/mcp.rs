//! Native Rust MCP Streamable HTTP (JSON-RPC 2.0) and converged 10-tool catalog for L0 Lite.

use crate::rest::authorize;
pub use crate::rest::build_router;
use crate::runtime::{
    ActBatchParams, ActionStepParams, CloseSessionParams, CreateSessionParams, IntranetAuthMode,
    LiteRuntime, ObserveParams, OpenProfileParams, ProfileOpParams, ScreenshotParams, WaitParams,
};
use crate::store::AgentTokenRecord;
use axum::extract::State;
use axum::http::{HeaderMap, StatusCode};
use axum::response::{IntoResponse, Response};
use axum::Json;
use serde::Deserialize;
use serde_json::{json, Value};
use std::sync::atomic::Ordering;
use std::sync::Arc;

pub const MCP_PROTOCOL_VERSION: &str = "2025-03-26";

// ---------- Native Rust MCP Streamable HTTP (JSON-RPC 2.0) ----------

#[derive(Deserialize)]
pub(crate) struct JsonRpcRequest {
    jsonrpc: String,
    id: Option<Value>,
    method: String,
    #[serde(default)]
    params: Value,
}

pub(crate) async fn handle_mcp(
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
                    "timeout_ms": { "type": "integer", "default": 300000, "minimum": 100, "maximum": 900000 }
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
