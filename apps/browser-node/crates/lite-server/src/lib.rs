//! Pure-Rust L0 Lite standalone server for Agent Browser Cloud.
//!
//! Runs multi-browser Chromium sessions with SQLite metadata, per-Agent token isolation, local
//! Profile checkpointing, graded backpressure, REST API, and native MCP Streamable HTTP — with
//! zero Java, PostgreSQL, Redis, Python, or external model API key dependencies.

pub mod backpressure;
pub mod egress;
pub mod events;
pub mod mcp;
pub mod profile;
pub mod rest;
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

    #[tokio::test]
    async fn admin_owned_profiles_regrant_single_writer_per_token_quota_and_intranet_policy() {
        let dir = std::env::temp_dir().join(format!("lite-policy-{}", Uuid::new_v4().simple()));
        let store = Arc::new(LiteStore::open(&dir.join("lite.sqlite3")).unwrap());
        let admin = store.ensure_bootstrap_token(None, 4).unwrap().unwrap();
        let agent_a = store
            .create_token(
                "agent_a",
                "Agent A",
                vec!["mcp".to_owned(), "sessions".to_owned()],
                1, // Strict per-token quota = 1
            )
            .unwrap();
        let agent_b = store
            .create_token(
                "agent_b",
                "Agent B",
                vec!["mcp".to_owned(), "sessions".to_owned()],
                1,
            )
            .unwrap();

        let cfg = LiteConfig {
            data_dir: dir.clone(),
            chromium_path: std::path::PathBuf::from("/nonexistent/chromium-test"),
            max_sessions: 4,
            allow_private_network: false,
            ..LiteConfig::default()
        };
        let rt = Arc::new(LiteRuntime::new(cfg, store.clone()).unwrap());
        let app = build_router(rt);

        // 1. Admin creates an admin-owned Profile; non-admin agents CANNOT see or open it yet.
        let create_prof = app
            .clone()
            .oneshot(
                Request::builder()
                    .method("POST")
                    .uri("/api/v1/lite/profiles")
                    .header("content-type", "application/json")
                    .header("authorization", format!("Bearer {}", admin.bearer_token))
                    .body(Body::from(
                        json!({ "name": "Corp Admin Account" }).to_string(),
                    ))
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(create_prof.status(), StatusCode::CREATED);
        let prof_json = json_body(create_prof).await;
        let profile_id = prof_json["profile"]["profileId"]
            .as_str()
            .unwrap()
            .to_owned();
        assert_eq!(prof_json["profile"]["ownerAccountId"], "agent_admin");

        // Seed login cookie data in the profile directory.
        std::fs::create_dir_all(dir.join("profiles").join(&profile_id)).unwrap();
        std::fs::write(
            dir.join("profiles").join(&profile_id).join("Cookies"),
            b"session=admin_logged_in",
        )
        .unwrap();

        // Agent A sees 0 profiles and gets 403 trying to open the ungranted profile.
        let list_a_0 = app
            .clone()
            .oneshot(
                Request::builder()
                    .uri("/api/v1/lite/profiles")
                    .header("authorization", format!("Bearer {}", agent_a.bearer_token))
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(json_body(list_a_0).await["count"], 0);

        let denied_open = app
            .clone()
            .oneshot(
                Request::builder()
                    .method("POST")
                    .uri(format!("/api/v1/lite/profiles/{profile_id}/open"))
                    .header("content-type", "application/json")
                    .header("authorization", format!("Bearer {}", agent_a.bearer_token))
                    .body(Body::from(json!({ "start": false }).to_string()))
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(denied_open.status(), StatusCode::FORBIDDEN);

        // 2. Admin grants Profile to Agent A -> Agent A sees it, opens it, saves a checkpoint.
        let grant_a = app
            .clone()
            .oneshot(
                Request::builder()
                    .method("POST")
                    .uri(format!("/api/v1/lite/profiles/{profile_id}/grants"))
                    .header("content-type", "application/json")
                    .header("authorization", format!("Bearer {}", admin.bearer_token))
                    .body(Body::from(json!({ "agentId": "agent_a" }).to_string()))
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(grant_a.status(), StatusCode::OK);

        let open_a = app
            .clone()
            .oneshot(
                Request::builder()
                    .method("POST")
                    .uri(format!("/api/v1/lite/profiles/{profile_id}/open"))
                    .header("content-type", "application/json")
                    .header("authorization", format!("Bearer {}", agent_a.bearer_token))
                    .body(Body::from(json!({ "start": false }).to_string()))
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(open_a.status(), StatusCode::OK);
        let ses_a = json_body(open_a).await["session"]["sessionId"]
            .as_str()
            .unwrap()
            .to_owned();

        let save_a = app
            .clone()
            .oneshot(
                Request::builder()
                    .method("POST")
                    .uri(format!("/api/v1/lite/sessions/{ses_a}/profile"))
                    .header("content-type", "application/json")
                    .header("authorization", format!("Bearer {}", agent_a.bearer_token))
                    .body(Body::from(json!({ "action": "save" }).to_string()))
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(save_a.status(), StatusCode::OK);
        let chk_id = json_body(save_a).await["checkpoint"]["checkpointId"]
            .as_str()
            .unwrap()
            .to_owned();

        // 3. Single-writer & same-browser collaboration: simulate ses_a RUNNING on profile_id.
        store.update_session_state(&ses_a, "RUNNING", None).unwrap();

        // Attempting to spawn a SECOND concurrent writer session on the same Profile -> 409 CONFLICT!
        let second_writer = app
            .clone()
            .oneshot(
                Request::builder()
                    .method("POST")
                    .uri("/api/v1/lite/sessions")
                    .header("content-type", "application/json")
                    .header("authorization", format!("Bearer {}", admin.bearer_token))
                    .body(Body::from(
                        json!({ "profile_id": profile_id, "start": true }).to_string(),
                    ))
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(second_writer.status(), StatusCode::CONFLICT);
        assert!(json_body(second_writer).await["error"]
            .as_str()
            .unwrap()
            .contains("PROFILE_ALREADY_IN_USE"));

        // Admin calling /profiles/:id/open joins ses_a directly for same-browser collaboration!
        let admin_collab = app
            .clone()
            .oneshot(
                Request::builder()
                    .method("POST")
                    .uri(format!("/api/v1/lite/profiles/{profile_id}/open"))
                    .header("content-type", "application/json")
                    .header("authorization", format!("Bearer {}", admin.bearer_token))
                    .body(Body::from(json!({ "start": true }).to_string()))
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(admin_collab.status(), StatusCode::OK);
        let collab_json = json_body(admin_collab).await;
        assert_eq!(collab_json["collaborating"], true);
        assert_eq!(collab_json["singleWriterSessionId"], ses_a);

        // 4. Per-Agent-Token running quota isolation:
        // Agent A has max_sessions = 1 and ses_a is RUNNING -> starting a 2nd session returns 429!
        let quota_a_2 = app
            .clone()
            .oneshot(
                Request::builder()
                    .method("POST")
                    .uri("/api/v1/lite/sessions")
                    .header("content-type", "application/json")
                    .header("authorization", format!("Bearer {}", agent_a.bearer_token))
                    .body(Body::from(
                        json!({ "name": "A2", "start": true }).to_string(),
                    ))
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(quota_a_2.status(), StatusCode::TOO_MANY_REQUESTS);
        assert_eq!(
            json_body(quota_a_2).await["error"],
            "AGENT_SESSION_QUOTA_EXCEEDED"
        );
        // Agent A's already-running session ses_a stays RUNNING!
        assert_eq!(store.get_session(&ses_a).unwrap().unwrap().state, "RUNNING");

        // Agent B is NOT blocked by Agent A's quota (machine still has capacity 1/4)!
        let start_b = app
            .clone()
            .oneshot(
                Request::builder()
                    .method("POST")
                    .uri("/api/v1/lite/sessions")
                    .header("content-type", "application/json")
                    .header("authorization", format!("Bearer {}", agent_b.bearer_token))
                    .body(Body::from(
                        json!({ "name": "B1", "start": false }).to_string(),
                    ))
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(start_b.status(), StatusCode::CREATED);

        // Admin bumps Agent A's token quota from 1 -> 2.
        let bump = app
            .clone()
            .oneshot(
                Request::builder()
                    .method("PATCH")
                    .uri(format!("/api/v1/lite/tokens/{}", agent_a.token.token_id))
                    .header("content-type", "application/json")
                    .header("authorization", format!("Bearer {}", admin.bearer_token))
                    .body(Body::from(json!({ "maxSessions": 2 }).to_string()))
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(bump.status(), StatusCode::OK);
        assert_eq!(json_body(bump).await["token"]["maxSessions"], 2);

        // 5. Public internet works normally; unauthorized intranet request is rejected (403)
        //    WITHOUT stopping or failing the running browser session; authorizing once unlocks it!
        let nav_private_denied = app
            .clone()
            .oneshot(
                Request::builder()
                    .method("POST")
                    .uri(format!("/api/v1/lite/sessions/{ses_a}/navigate"))
                    .header("content-type", "application/json")
                    .header("authorization", format!("Bearer {}", agent_a.bearer_token))
                    .body(Body::from(
                        json!({ "url": "http://192.168.1.50:8080/admin" }).to_string(),
                    ))
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(nav_private_denied.status(), StatusCode::FORBIDDEN);
        assert!(json_body(nav_private_denied).await["error"]
            .as_str()
            .unwrap()
            .contains("PRIVATE_NETWORK_HOST_NOT_AUTHORIZED"));
        // Session MUST still be RUNNING!
        assert_eq!(store.get_session(&ses_a).unwrap().unwrap().state, "RUNNING");

        // Authorize internal admin panel once on this environment.
        let auth_net = app
            .clone()
            .oneshot(
                Request::builder()
                    .method("PATCH")
                    .uri(format!("/api/v1/lite/sessions/{ses_a}/network-policy"))
                    .header("content-type", "application/json")
                    .header("authorization", format!("Bearer {}", agent_a.bearer_token))
                    .body(Body::from(
                        json!({ "allowedPrivateHosts": ["192.168.1.50:8080", "10.0.0.0/8"] })
                            .to_string(),
                    ))
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(auth_net.status(), StatusCode::OK);
        assert!(security::validate_navigation_url_with_policy(
            "http://192.168.1.50:8080/admin",
            false,
            &store
                .get_session(&ses_a)
                .unwrap()
                .unwrap()
                .allowed_private_hosts,
        )
        .is_ok());
        assert!(security::validate_navigation_url_with_policy(
            "https://example.com/public",
            false,
            &[],
        )
        .is_ok());

        // 6. Revoke Agent A and re-grant Profile to Agent B (replacing Agent/Token while keeping login state).
        store
            .update_session_state(&ses_a, "HIBERNATED", None)
            .unwrap();
        let revoke_a = app
            .clone()
            .oneshot(
                Request::builder()
                    .method("DELETE")
                    .uri(format!("/api/v1/lite/profiles/{profile_id}/grants/agent_a"))
                    .header("authorization", format!("Bearer {}", admin.bearer_token))
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(revoke_a.status(), StatusCode::OK);

        // Agent A immediately loses access to the profile and its session!
        let denied_a_after_revoke = app
            .clone()
            .oneshot(
                Request::builder()
                    .method("POST")
                    .uri(format!("/api/v1/lite/sessions/{ses_a}/profile"))
                    .header("content-type", "application/json")
                    .header("authorization", format!("Bearer {}", agent_a.bearer_token))
                    .body(Body::from(json!({ "action": "list" }).to_string()))
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(denied_a_after_revoke.status(), StatusCode::FORBIDDEN);

        // Re-grant to Agent B -> Agent B sees Profile & Agent A's saved checkpoint and restores login state!
        let grant_b = app
            .clone()
            .oneshot(
                Request::builder()
                    .method("POST")
                    .uri(format!("/api/v1/lite/profiles/{profile_id}/grants"))
                    .header("content-type", "application/json")
                    .header("authorization", format!("Bearer {}", admin.bearer_token))
                    .body(Body::from(json!({ "agentId": "agent_b" }).to_string()))
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(grant_b.status(), StatusCode::OK);

        let chk_b = app
            .clone()
            .oneshot(
                Request::builder()
                    .uri(format!("/api/v1/lite/profiles/{profile_id}/checkpoints"))
                    .header("authorization", format!("Bearer {}", agent_b.bearer_token))
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(chk_b.status(), StatusCode::OK);
        assert_eq!(
            json_body(chk_b).await["checkpoints"][0]["checkpointId"],
            chk_id
        );

        // Overwrite cookie file, then let Agent B restore checkpoint -> login cookie preserved!
        std::fs::write(
            dir.join("profiles").join(&profile_id).join("Cookies"),
            b"corrupted",
        )
        .unwrap();
        let restore_b = app
            .clone()
            .oneshot(
                Request::builder()
                    .method("POST")
                    .uri(format!("/api/v1/lite/profiles/{profile_id}/restore"))
                    .header("content-type", "application/json")
                    .header("authorization", format!("Bearer {}", agent_b.bearer_token))
                    .body(Body::from(json!({ "checkpointId": chk_id }).to_string()))
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(restore_b.status(), StatusCode::OK);
        let restored_cookie =
            std::fs::read(dir.join("profiles").join(&profile_id).join("Cookies")).unwrap();
        assert_eq!(restored_cookie, b"session=admin_logged_in");

        let _ = std::fs::remove_dir_all(&dir);
    }

    #[tokio::test]
    async fn intranet_authorization_distinguishes_once_temporary_and_permanent_environment_grants()
    {
        let dir = std::env::temp_dir().join(format!("lite-intranet-{}", Uuid::new_v4().simple()));
        let store = Arc::new(LiteStore::open(&dir.join("lite.sqlite3")).unwrap());
        let admin = store.ensure_bootstrap_token(None, 4).unwrap().unwrap();
        let agent_a = store
            .create_token(
                "agent_a",
                "Agent A",
                vec!["mcp".to_owned(), "sessions".to_owned()],
                2,
            )
            .unwrap();

        let cfg = LiteConfig {
            data_dir: dir.clone(),
            chromium_path: std::path::PathBuf::from("/nonexistent/chromium-test"),
            max_sessions: 4,
            allow_private_network: false,
            ..LiteConfig::default()
        };
        let rt = Arc::new(LiteRuntime::new(cfg, store.clone()).unwrap());
        let app = build_router(rt);

        // Create session for Agent A and mark RUNNING.
        let created = app
            .clone()
            .oneshot(
                Request::builder()
                    .method("POST")
                    .uri("/api/v1/lite/sessions")
                    .header("content-type", "application/json")
                    .header("authorization", format!("Bearer {}", agent_a.bearer_token))
                    .body(Body::from(
                        json!({ "name": "NetTest", "start": false }).to_string(),
                    ))
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(created.status(), StatusCode::CREATED);
        let cjson = json_body(created).await;
        let sid = cjson["session"]["sessionId"].as_str().unwrap().to_owned();
        let pid = cjson["session"]["profileId"].as_str().unwrap().to_owned();
        store.update_session_state(&sid, "RUNNING", None).unwrap();

        // 1. Unauthorized private request returns 403 with once/permanent options and keeps session RUNNING.
        let denied = app
            .clone()
            .oneshot(
                Request::builder()
                    .method("POST")
                    .uri(format!("/api/v1/lite/sessions/{sid}/navigate"))
                    .header("content-type", "application/json")
                    .header("authorization", format!("Bearer {}", agent_a.bearer_token))
                    .body(Body::from(
                        json!({ "url": "http://192.168.1.50:8080/admin" }).to_string(),
                    ))
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(denied.status(), StatusCode::FORBIDDEN);
        let djson = json_body(denied).await;
        assert_eq!(djson["host"], "192.168.1.50:8080");
        assert_eq!(djson["sessionPreserved"], true);
        assert_eq!(djson["authorizationOptions"], json!(["once", "permanent"]));
        assert_eq!(store.get_session(&sid).unwrap().unwrap().state, "RUNNING");

        // 2. Grant 192.168.1.50:8080 as ONCE (ephemeral, NOT written to Profile DB).
        let once_resp = app
            .clone()
            .oneshot(
                Request::builder()
                    .method("POST")
                    .uri(format!(
                        "/api/v1/lite/sessions/{sid}/network-policy/authorize"
                    ))
                    .header("content-type", "application/json")
                    .header("authorization", format!("Bearer {}", agent_a.bearer_token))
                    .body(Body::from(
                        json!({ "host": "192.168.1.50:8080", "mode": "once", "ttlSeconds": 300 })
                            .to_string(),
                    ))
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(once_resp.status(), StatusCode::OK);
        let ojson = json_body(once_resp).await;
        assert_eq!(ojson["mode"], "once");
        assert_eq!(ojson["permanentAllowedPrivateHosts"], json!([]));
        assert_eq!(
            ojson["onceAllowedPrivateHosts"].as_array().unwrap().len(),
            1
        );
        assert_eq!(
            ojson["effectiveAllowedPrivateHosts"],
            json!(["192.168.1.50:8080"])
        );
        // SQLite profile MUST still have empty permanent allowlist!
        assert!(store
            .get_profile(&pid)
            .unwrap()
            .unwrap()
            .allowed_private_hosts
            .is_empty());

        // 3. Grant 10.0.0.5:3000 as PERMANENT (additive! preserves once grant AND persists to Profile DB).
        let perm_resp = app
            .clone()
            .oneshot(
                Request::builder()
                    .method("POST")
                    .uri(format!(
                        "/api/v1/lite/sessions/{sid}/network-policy/authorize"
                    ))
                    .header("content-type", "application/json")
                    .header("authorization", format!("Bearer {}", agent_a.bearer_token))
                    .body(Body::from(
                        json!({ "host": "10.0.0.5:3000", "mode": "permanent" }).to_string(),
                    ))
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(perm_resp.status(), StatusCode::OK);
        let pjson = json_body(perm_resp).await;
        assert_eq!(pjson["mode"], "permanent");
        assert_eq!(
            pjson["permanentAllowedPrivateHosts"],
            json!(["10.0.0.5:3000"])
        );
        assert_eq!(
            pjson["onceAllowedPrivateHosts"].as_array().unwrap().len(),
            1
        );
        assert_eq!(
            store
                .get_profile(&pid)
                .unwrap()
                .unwrap()
                .allowed_private_hosts,
            vec!["10.0.0.5:3000".to_owned()]
        );

        // 4. Hibernate session -> ONCE grant is automatically cleared; PERMANENT grant persists!
        let hib = app
            .clone()
            .oneshot(
                Request::builder()
                    .method("POST")
                    .uri(format!("/api/v1/lite/sessions/{sid}/close"))
                    .header("content-type", "application/json")
                    .header("authorization", format!("Bearer {}", agent_a.bearer_token))
                    .body(Body::from(json!({ "hibernate": true }).to_string()))
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(hib.status(), StatusCode::OK);

        let policy_after = app
            .clone()
            .oneshot(
                Request::builder()
                    .uri(format!("/api/v1/lite/sessions/{sid}/network-policy"))
                    .header("authorization", format!("Bearer {}", agent_a.bearer_token))
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        let after_json = json_body(policy_after).await;
        assert_eq!(after_json["onceAllowedPrivateHosts"], json!([]));
        assert_eq!(
            after_json["permanentAllowedPrivateHosts"],
            json!(["10.0.0.5:3000"])
        );

        // New session opened by Admin on the same Profile inherits PERMANENT grant automatically (never asks again)!
        let reopen = app
            .clone()
            .oneshot(
                Request::builder()
                    .method("POST")
                    .uri(format!("/api/v1/lite/profiles/{pid}/open"))
                    .header("content-type", "application/json")
                    .header("authorization", format!("Bearer {}", admin.bearer_token))
                    .body(Body::from(json!({ "start": false }).to_string()))
                    .unwrap(),
            )
            .await
            .unwrap();
        let rjson = json_body(reopen).await;
        assert_eq!(
            rjson["session"]["allowedPrivateHosts"],
            json!(["10.0.0.5:3000"])
        );

        // 5. Revoke permanent grant cleanly.
        let revoke = app
            .clone()
            .oneshot(
                Request::builder()
                    .method("DELETE")
                    .uri(format!("/api/v1/lite/sessions/{sid}/network-policy/grants"))
                    .header("content-type", "application/json")
                    .header("authorization", format!("Bearer {}", admin.bearer_token))
                    .body(Body::from(
                        json!({ "host": "10.0.0.5:3000", "mode": "permanent" }).to_string(),
                    ))
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(revoke.status(), StatusCode::OK);
        assert_eq!(
            json_body(revoke).await["permanentAllowedPrivateHosts"],
            json!([])
        );

        let _ = std::fs::remove_dir_all(&dir);
    }
}
