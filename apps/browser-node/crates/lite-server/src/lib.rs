//! Pure-Rust L0 Lite standalone server for Agent Browser Cloud.
//!
//! Runs multi-browser Chromium sessions with SQLite metadata, per-Agent token isolation, local
//! Profile checkpointing, graded backpressure, REST API, and native MCP Streamable HTTP — with
//! zero Java, PostgreSQL, Redis, Python, or external model API key dependencies.

pub mod backpressure;
pub mod events;
pub mod mcp;
pub mod profile;
pub mod runtime;
pub mod security;
pub mod store;
pub mod vault;

pub use mcp::build_router;
pub use runtime::{LiteConfig, LiteRuntime};
pub use store::LiteStore;

#[cfg(test)]
mod integration_tests {
    use super::*;
    use axum::body::Body;
    use axum::http::{Request, StatusCode};
    use http_body_util::BodyExt;
    use serde_json::{json, Value};
    use std::sync::Arc;
    use tower::ServiceExt;
    use uuid::Uuid;

    async fn json_body(response: axum::response::Response) -> Value {
        let bytes = response.into_body().collect().await.unwrap().to_bytes();
        serde_json::from_slice(&bytes).unwrap()
    }

    #[tokio::test]
    async fn mcp_protocol_converged_10_tools_multi_agent_isolation_and_origin_guard() {
        let dir = std::env::temp_dir().join(format!("lite-int-{}", Uuid::new_v4().simple()));
        let store = Arc::new(LiteStore::open(&dir.join("lite.sqlite3")).unwrap());
        let admin = store.ensure_bootstrap_token(None, 5).unwrap().unwrap();
        let agent_a = store
            .create_token(
                "agent_a",
                "Codex A",
                vec!["mcp".to_owned(), "sessions".to_owned()],
                3,
            )
            .unwrap();
        let agent_b = store
            .create_token(
                "agent_b",
                "Claude B",
                vec!["mcp".to_owned(), "sessions".to_owned()],
                2,
            )
            .unwrap();

        let cfg = LiteConfig {
            data_dir: dir.clone(),
            ..LiteConfig::default()
        };
        let rt = Arc::new(LiteRuntime::new(cfg, store).unwrap());
        let app = build_router(rt);

        // 1. Health endpoint works without auth.
        let health = app
            .clone()
            .oneshot(
                Request::builder()
                    .uri("/healthz")
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(health.status(), StatusCode::OK);
        let hjson = json_body(health).await;
        assert_eq!(hjson["tier"], "L0_LITE");
        assert_eq!(hjson["runtime"], "rust");
        assert_eq!(hjson["warmPoolReady"], 0);

        // 2. Unauthenticated MCP request rejected with 401.
        let unauth = app
            .clone()
            .oneshot(
                Request::builder()
                    .method("POST")
                    .uri("/mcp")
                    .header("content-type", "application/json")
                    .body(Body::from(
                        json!({"jsonrpc": "2.0", "id": 1, "method": "tools/list"}).to_string(),
                    ))
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(unauth.status(), StatusCode::UNAUTHORIZED);

        // 3. Untrusted Origin rejected with 403.
        let bad_origin = app
            .clone()
            .oneshot(
                Request::builder()
                    .method("POST")
                    .uri("/mcp")
                    .header("content-type", "application/json")
                    .header("origin", "https://untrusted.invalid")
                    .header("authorization", format!("Bearer {}", agent_a.bearer_token))
                    .body(Body::from(
                        json!({"jsonrpc": "2.0", "id": 1, "method": "tools/list"}).to_string(),
                    ))
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(bad_origin.status(), StatusCode::FORBIDDEN);

        // 4. MCP initialize + tools/list exposes exactly the 10 converged tools.
        let list_resp = app
            .clone()
            .oneshot(
                Request::builder()
                    .method("POST")
                    .uri("/mcp")
                    .header("content-type", "application/json")
                    .header("host", "127.0.0.1:8130")
                    .header("authorization", format!("Bearer {}", agent_a.bearer_token))
                    .body(Body::from(
                        json!({"jsonrpc": "2.0", "id": 2, "method": "tools/list"}).to_string(),
                    ))
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(list_resp.status(), StatusCode::OK);
        let tools_json = json_body(list_resp).await;
        let tool_names: Vec<&str> = tools_json["result"]["tools"]
            .as_array()
            .unwrap()
            .iter()
            .map(|t| t["name"].as_str().unwrap())
            .collect();
        assert_eq!(
            tool_names,
            vec![
                "browser_create",
                "browser_list",
                "browser_navigate",
                "browser_observe",
                "browser_act",
                "browser_act_batch",
                "browser_wait",
                "browser_screenshot",
                "browser_profile",
                "browser_close",
            ]
        );

        // 5. Agent A creates a hibernated session (start: false — no Chromium binary required in unit test).
        let create_resp = app
            .clone()
            .oneshot(
                Request::builder()
                    .method("POST")
                    .uri("/mcp")
                    .header("content-type", "application/json")
                    .header("authorization", format!("Bearer {}", agent_a.bearer_token))
                    .body(Body::from(
                        json!({
                            "jsonrpc": "2.0",
                            "id": 3,
                            "method": "tools/call",
                            "params": {
                                "name": "browser_create",
                                "arguments": { "name": "A-Session", "start": false }
                            }
                        })
                        .to_string(),
                    ))
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(create_resp.status(), StatusCode::OK);
        let created = json_body(create_resp).await;
        let session_id = created["result"]["structuredContent"]["session"]["sessionId"]
            .as_str()
            .unwrap()
            .to_owned();
        assert!(session_id.starts_with("ses_"));
        assert_eq!(
            created["result"]["structuredContent"]["session"]["isolationMode"],
            "process"
        );

        // 5b. Opt-in context isolation mode works, invalid mode is rejected fail-closed.
        let ctx_resp = app
            .clone()
            .oneshot(
                Request::builder()
                    .method("POST")
                    .uri("/mcp")
                    .header("content-type", "application/json")
                    .header("authorization", format!("Bearer {}", agent_a.bearer_token))
                    .body(Body::from(
                        json!({
                            "jsonrpc": "2.0",
                            "id": 301,
                            "method": "tools/call",
                            "params": {
                                "name": "browser_create",
                                "arguments": { "name": "A-Ctx", "start": false, "isolation_mode": "context" }
                            }
                        })
                        .to_string(),
                    ))
                    .unwrap(),
            )
            .await
            .unwrap();
        let ctx_json = json_body(ctx_resp).await;
        assert_eq!(
            ctx_json["result"]["structuredContent"]["session"]["isolationMode"],
            "context"
        );

        let bad_iso = app
            .clone()
            .oneshot(
                Request::builder()
                    .method("POST")
                    .uri("/mcp")
                    .header("content-type", "application/json")
                    .header("authorization", format!("Bearer {}", agent_a.bearer_token))
                    .body(Body::from(
                        json!({
                            "jsonrpc": "2.0",
                            "id": 302,
                            "method": "tools/call",
                            "params": {
                                "name": "browser_create",
                                "arguments": { "name": "Bad", "start": false, "isolation_mode": "shared_unsafe" }
                            }
                        })
                        .to_string(),
                    ))
                    .unwrap(),
            )
            .await
            .unwrap();
        let bad_iso_json = json_body(bad_iso).await;
        assert_eq!(bad_iso_json["result"]["isError"], true);
        assert!(bad_iso_json["result"]["content"][0]["text"]
            .as_str()
            .unwrap()
            .contains("ISOLATION_MODE_INVALID"));

        // 6. Agent B CANNOT see or close Agent A's session!
        let list_b = app
            .clone()
            .oneshot(
                Request::builder()
                    .method("POST")
                    .uri("/mcp")
                    .header("content-type", "application/json")
                    .header("authorization", format!("Bearer {}", agent_b.bearer_token))
                    .body(Body::from(
                        json!({
                            "jsonrpc": "2.0",
                            "id": 4,
                            "method": "tools/call",
                            "params": { "name": "browser_list", "arguments": {} }
                        })
                        .to_string(),
                    ))
                    .unwrap(),
            )
            .await
            .unwrap();
        let list_b_json = json_body(list_b).await;
        assert_eq!(list_b_json["result"]["structuredContent"]["count"], 0);

        let close_b = app
            .clone()
            .oneshot(
                Request::builder()
                    .method("POST")
                    .uri("/mcp")
                    .header("content-type", "application/json")
                    .header("authorization", format!("Bearer {}", agent_b.bearer_token))
                    .body(Body::from(
                        json!({
                            "jsonrpc": "2.0",
                            "id": 5,
                            "method": "tools/call",
                            "params": {
                                "name": "browser_close",
                                "arguments": { "session_id": session_id, "hibernate": false }
                            }
                        })
                        .to_string(),
                    ))
                    .unwrap(),
            )
            .await
            .unwrap();
        let close_b_json = json_body(close_b).await;
        assert_eq!(close_b_json["result"]["isError"], true);
        assert!(close_b_json["result"]["content"][0]["text"]
            .as_str()
            .unwrap()
            .contains("SESSION_ACCESS_DENIED"));

        // 7. Agent A saves a local profile checkpoint via MCP and closes its session.
        let save_a = app
            .clone()
            .oneshot(
                Request::builder()
                    .method("POST")
                    .uri("/mcp")
                    .header("content-type", "application/json")
                    .header("authorization", format!("Bearer {}", agent_a.bearer_token))
                    .body(Body::from(
                        json!({
                            "jsonrpc": "2.0",
                            "id": 6,
                            "method": "tools/call",
                            "params": {
                                "name": "browser_profile",
                                "arguments": { "session_id": session_id, "action": "save" }
                            }
                        })
                        .to_string(),
                    ))
                    .unwrap(),
            )
            .await
            .unwrap();
        let save_a_json = json_body(save_a).await;
        assert_eq!(save_a_json["result"]["structuredContent"]["saved"], true);

        // 8. Admin revokes Agent A's token via REST; subsequent calls return 401.
        let revoke = app
            .clone()
            .oneshot(
                Request::builder()
                    .method("DELETE")
                    .uri(format!("/api/v1/lite/tokens/{}", agent_a.token.token_id))
                    .header("authorization", format!("Bearer {}", admin.bearer_token))
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(revoke.status(), StatusCode::OK);

        let after_revoke = app
            .oneshot(
                Request::builder()
                    .method("GET")
                    .uri("/api/v1/lite/sessions")
                    .header("authorization", format!("Bearer {}", agent_a.bearer_token))
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(after_revoke.status(), StatusCode::UNAUTHORIZED);

        let _ = std::fs::remove_dir_all(&dir);
    }

    #[tokio::test]
    async fn phase3_credential_vault_sse_replay_trace_metrics_and_cdp_proxy_guards() {
        let dir = std::env::temp_dir().join(format!("lite-p3-{}", Uuid::new_v4().simple()));
        let store = Arc::new(LiteStore::open(&dir.join("lite.sqlite3")).unwrap());
        let agent_a = store
            .create_token(
                "agent_a",
                "Agent A",
                vec!["mcp".into(), "sessions".into()],
                3,
            )
            .unwrap();
        let agent_b = store
            .create_token(
                "agent_b",
                "Agent B",
                vec!["mcp".into(), "sessions".into()],
                3,
            )
            .unwrap();
        let rt = Arc::new(
            LiteRuntime::new(
                LiteConfig {
                    data_dir: dir.clone(),
                    ..LiteConfig::default()
                },
                store,
            )
            .unwrap(),
        );
        let app = build_router(rt.clone());

        // 1. Create encrypted credential via REST; plaintext must NOT appear in response.
        let cred_resp = app
            .clone()
            .oneshot(
                Request::builder()
                    .method("POST")
                    .uri("/api/v1/lite/credentials")
                    .header("content-type", "application/json")
                    .header("x-trace-id", "trc_custom_001")
                    .header("authorization", format!("Bearer {}", agent_a.bearer_token))
                    .body(Body::from(
                        json!({
                            "label": "App Password",
                            "allowed_domain": "login.example.com",
                            "secret_value": "TopSecret#2026"
                        })
                        .to_string(),
                    ))
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(cred_resp.status(), StatusCode::CREATED);
        assert_eq!(
            cred_resp.headers().get("x-trace-id").unwrap(),
            "trc_custom_001"
        );
        let cred_bytes = cred_resp.into_body().collect().await.unwrap().to_bytes();
        let cred_str = String::from_utf8_lossy(&cred_bytes);
        assert!(!cred_str.contains("TopSecret#2026"));
        let cred_json: Value = serde_json::from_slice(&cred_bytes).unwrap();
        let cred_id = cred_json["credential"]["credentialId"]
            .as_str()
            .unwrap()
            .to_owned();

        // In-memory resolution works ONLY for owner + authorized domain.
        assert_eq!(
            rt.resolve_credential_for_url(&agent_a.token, &cred_id, "https://login.example.com/in")
                .unwrap(),
            "TopSecret#2026"
        );
        assert!(rt
            .resolve_credential_for_url(&agent_a.token, &cred_id, "https://phishing.invalid/in")
            .unwrap_err()
            .to_string()
            .contains("CREDENTIAL_DOMAIN_MISMATCH"));
        assert!(rt
            .resolve_credential_for_url(&agent_b.token, &cred_id, "https://login.example.com/in")
            .unwrap_err()
            .to_string()
            .contains("CREDENTIAL_ACCESS_DENIED"));

        // 2. Create a session for Agent A and check SSE replay isolation (`Last-Event-ID`).
        let s_resp = app
            .clone()
            .oneshot(
                Request::builder()
                    .method("POST")
                    .uri("/api/v1/lite/sessions")
                    .header("content-type", "application/json")
                    .header("authorization", format!("Bearer {}", agent_a.bearer_token))
                    .body(Body::from(
                        json!({ "name": "S1", "start": false }).to_string(),
                    ))
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(s_resp.status(), StatusCode::CREATED);
        let s_json = json_body(s_resp).await;
        let ses_id = s_json["session"]["sessionId"].as_str().unwrap().to_owned();

        let sse_a = app
            .clone()
            .oneshot(
                Request::builder()
                    .uri("/api/v1/lite/events?once=true")
                    .header("last-event-id", "1")
                    .header("authorization", format!("Bearer {}", agent_a.bearer_token))
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(sse_a.status(), StatusCode::OK);
        let sse_a_body = String::from_utf8(
            sse_a
                .into_body()
                .collect()
                .await
                .unwrap()
                .to_bytes()
                .to_vec(),
        )
        .unwrap();
        assert!(sse_a_body.contains("event: session.created"));
        assert!(!sse_a_body.contains("event: credential.created")); // id=1 skipped via Last-Event-ID

        let sse_b = app
            .clone()
            .oneshot(
                Request::builder()
                    .uri("/api/v1/lite/events?once=true")
                    .header("authorization", format!("Bearer {}", agent_b.bearer_token))
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        let sse_b_body = String::from_utf8(
            sse_b
                .into_body()
                .collect()
                .await
                .unwrap()
                .to_bytes()
                .to_vec(),
        )
        .unwrap();
        assert!(sse_b_body.is_empty(), "Agent B must not see Agent A events");

        // 3. CDP proxy rejects unauthenticated (401), non-upgrade (426), and cross-agent (403).
        let cdp_unauth = app
            .clone()
            .oneshot(
                Request::builder()
                    .uri(format!("/api/v1/lite/sessions/{ses_id}/cdp"))
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(cdp_unauth.status(), StatusCode::UNAUTHORIZED);

        let cdp_no_ws = app
            .clone()
            .oneshot(
                Request::builder()
                    .uri(format!("/api/v1/lite/sessions/{ses_id}/cdp"))
                    .header("authorization", format!("Bearer {}", agent_a.bearer_token))
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(cdp_no_ws.status(), StatusCode::UPGRADE_REQUIRED);

        let cdp_cross_agent = app
            .clone()
            .oneshot(
                Request::builder()
                    .uri(format!("/api/v1/lite/sessions/{ses_id}/cdp"))
                    .header("authorization", format!("Bearer {}", agent_b.bearer_token))
                    .header("sec-websocket-key", "dGhlIHNhbXBsZSBub25jZQ==")
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(cdp_cross_agent.status(), StatusCode::FORBIDDEN);

        // 4. Prometheus `/metrics` exposes lite counters and gauges.
        let metrics_resp = app
            .oneshot(
                Request::builder()
                    .uri("/metrics")
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(metrics_resp.status(), StatusCode::OK);
        let metrics_text = String::from_utf8(
            metrics_resp
                .into_body()
                .collect()
                .await
                .unwrap()
                .to_bytes()
                .to_vec(),
        )
        .unwrap();
        assert!(metrics_text.contains("browsercloud_lite_requests_total"));
        assert!(metrics_text.contains("browsercloud_lite_events_published_total"));

        let _ = std::fs::remove_dir_all(&dir);
    }
}
