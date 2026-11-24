//! `browsercloud-mcp-bridge`: Pure-Rust local STDIO ↔ Streamable HTTP MCP bridge (Section 6.2).
//!
//! Allows STDIO-only MCP clients to connect cleanly to a local or remote L0 Lite / L1 / L2
//! Streamable HTTP MCP endpoint (`POST /mcp`) using a Bearer token from env or a `0600` file.

use anyhow::{Context, Result};
use reqwest::Client;
use serde_json::{json, Value};
use std::env;
use std::time::Duration;
use tokio::io::{self, AsyncBufReadExt, AsyncWriteExt, BufReader};

async fn forward_line(
    client: &Client,
    endpoint: &str,
    bearer: &str,
    line: &str,
) -> Result<Option<String>> {
    let parsed: Value = serde_json::from_str(line).context("invalid JSON-RPC line on stdin")?;
    let id = parsed.get("id").cloned();
    let resp = client
        .post(endpoint)
        .header("content-type", "application/json")
        .header("accept", "application/json")
        .header("authorization", format!("Bearer {bearer}"))
        .body(line.to_owned())
        .send()
        .await;

    match resp {
        Ok(response) => {
            let status = response.status();
            if status.as_u16() == 202 || status.as_u16() == 204 {
                return Ok(None);
            }
            let body = response.text().await.unwrap_or_default();
            if status.is_success() {
                let trimmed = body.trim();
                if trimmed.is_empty() {
                    Ok(None)
                } else {
                    Ok(Some(trimmed.to_owned()))
                }
            } else {
                Ok(id.map(|req_id| {
                    json!({
                        "jsonrpc": "2.0",
                        "id": req_id,
                        "error": {
                            "code": -32000,
                            "message": format!("HTTP {}: {}", status.as_u16(), body.trim())
                        }
                    })
                    .to_string()
                }))
            }
        }
        Err(error) => Ok(id.map(|req_id| {
            json!({
                "jsonrpc": "2.0",
                "id": req_id,
                "error": {
                    "code": -32001,
                    "message": format!("MCP upstream unreachable: {error}")
                }
            })
            .to_string()
        })),
    }
}

fn load_bearer_token() -> Result<String> {
    if let Ok(token) = env::var("MCP_BEARER_TOKEN") {
        let trimmed = token.trim();
        if !trimmed.is_empty() {
            return Ok(trimmed.to_owned());
        }
    }
    if let Ok(file) = env::var("MCP_TOKEN_FILE") {
        let raw = std::fs::read_to_string(&file)
            .with_context(|| format!("failed to read MCP_TOKEN_FILE '{file}'"))?;
        let trimmed = raw.trim();
        anyhow::ensure!(!trimmed.is_empty(), "MCP_TOKEN_FILE is empty");
        return Ok(trimmed.to_owned());
    }
    anyhow::bail!("set MCP_BEARER_TOKEN or MCP_TOKEN_FILE before starting browsercloud-mcp-bridge")
}

#[tokio::main]
async fn main() -> Result<()> {
    let endpoint =
        env::var("MCP_HTTP_URL").unwrap_or_else(|_| "http://127.0.0.1:8130/mcp".to_owned());
    let bearer = load_bearer_token()?;
    let timeout_secs: u64 = env::var("MCP_BRIDGE_TIMEOUT_SECONDS")
        .ok()
        .and_then(|v| v.parse().ok())
        .unwrap_or(60)
        .clamp(5, 300);

    let client = Client::builder()
        .timeout(Duration::from_secs(timeout_secs))
        .build()?;

    let stdin = BufReader::new(io::stdin());
    let mut stdout = io::stdout();
    let mut lines = stdin.lines();
    while let Some(line) = lines.next_line().await? {
        let trimmed = line.trim();
        if trimmed.is_empty() {
            continue;
        }
        if let Some(reply) = forward_line(&client, &endpoint, &bearer, trimmed).await? {
            stdout.write_all(reply.as_bytes()).await?;
            stdout.write_all(b"\n").await?;
            stdout.flush().await?;
        }
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use lite_server::{build_router, LiteConfig, LiteRuntime, LiteStore};
    use std::sync::Arc;
    use tokio::net::TcpListener;
    use uuid::Uuid;

    #[tokio::test]
    async fn forwards_stdio_jsonrpc_to_lite_http_mcp_and_handles_notifications() {
        let dir = std::env::temp_dir().join(format!("lite-bridge-{}", Uuid::new_v4().simple()));
        let store = Arc::new(LiteStore::open(&dir.join("lite.sqlite3")).unwrap());
        let token = store.ensure_bootstrap_token(None, 2).unwrap().unwrap();
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
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap();
        let server = tokio::spawn(async move {
            let _ = axum::serve(listener, build_router(rt)).await;
        });

        let client = Client::builder().no_proxy().build().unwrap();
        let url = format!("http://{addr}/mcp");

        // Notification returns None (202 Accepted).
        let notif = forward_line(
            &client,
            &url,
            &token.bearer_token,
            r#"{"jsonrpc":"2.0","method":"notifications/initialized"}"#,
        )
        .await
        .unwrap();
        assert!(notif.is_none());

        // tools/list returns the 10 converged tools over HTTP.
        let reply = forward_line(
            &client,
            &url,
            &token.bearer_token,
            r#"{"jsonrpc":"2.0","id":7,"method":"tools/list"}"#,
        )
        .await
        .unwrap()
        .unwrap();
        let parsed: Value = serde_json::from_str(&reply).unwrap();
        assert_eq!(parsed["id"], 7);
        assert_eq!(parsed["result"]["tools"].as_array().unwrap().len(), 10);

        server.abort();
        let _ = std::fs::remove_dir_all(&dir);
    }
}
