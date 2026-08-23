use futures_util::{SinkExt, StreamExt};
use serde::Deserialize;
use sha2::{Digest, Sha256};
use std::collections::{HashMap, HashSet};
use std::sync::Arc;
use tokio::sync::RwLock;
use tokio::task::JoinHandle;
use tokio::time::{timeout, Duration};
use tokio_tungstenite::tungstenite::Message;

const MAX_DIALOG_TEXT_BYTES: usize = 4_096;

#[derive(Debug, Clone, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct NativeDialog {
    pub dialog_id: String,
    pub tab_id: String,
    pub dialog_type: String,
    pub message: String,
    pub default_prompt: String,
    pub has_browser_handler: bool,
}

#[derive(Debug, Clone, Default)]
pub(crate) struct NativeDialogObservation {
    pub dialogs: HashMap<String, NativeDialog>,
    pub fresh_tab_ids: HashSet<String>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct CdpVersion {
    web_socket_debugger_url: String,
}

pub(crate) fn spawn(
    session_id: String,
    endpoint: String,
    observations: Arc<RwLock<HashMap<String, NativeDialogObservation>>>,
) -> JoinHandle<()> {
    tokio::spawn(async move {
        loop {
            let result = observe(&endpoint, &session_id, &observations).await;
            mark_all_stale(&observations, &session_id).await;
            match result {
                Ok(()) => tracing::warn!(session_id, "Native Dialog CDP observer closed"),
                Err(error) => tracing::warn!(
                    session_id,
                    error = %error,
                    "Native Dialog CDP observer failed"
                ),
            }
            tokio::time::sleep(Duration::from_secs(1)).await;
        }
    })
}

async fn observe(
    endpoint: &str,
    session_id: &str,
    observations: &Arc<RwLock<HashMap<String, NativeDialogObservation>>>,
) -> anyhow::Result<()> {
    let websocket_url = browser_websocket(endpoint).await?;
    require_loopback_websocket(&websocket_url)?;
    let (mut socket, _) = timeout(
        Duration::from_secs(3),
        tokio_tungstenite::connect_async(&websocket_url),
    )
    .await
    .map_err(|_| anyhow::anyhow!("CDP Native Dialog connection timed out"))??;

    send_command(
        &mut socket,
        9_001,
        "Target.setDiscoverTargets",
        serde_json::json!({"discover": true}),
        None,
    )
    .await?;
    send_command(
        &mut socket,
        9_002,
        "Target.setAutoAttach",
        serde_json::json!({
            "autoAttach": true,
            "waitForDebuggerOnStart": false,
            "flatten": true,
            "filter": [{"type": "page", "exclude": false}]
        }),
        None,
    )
    .await?;

    let mut next_command_id = 9_100_i64;
    let mut page_enable_commands = HashMap::<i64, String>::new();
    let mut probe_commands = HashMap::<i64, String>::new();
    let mut target_by_session = HashMap::<String, String>::new();
    loop {
        let message = match timeout(Duration::from_secs(1), socket.next()).await {
            Ok(Some(message)) => message,
            Ok(None) => break,
            Err(_) => {
                for cdp_session in target_by_session.keys() {
                    let command_id = next_command_id;
                    next_command_id = next_command_id.saturating_add(1);
                    send_probe(&mut socket, command_id, cdp_session).await?;
                    probe_commands.insert(command_id, cdp_session.clone());
                }
                continue;
            }
        };
        let Message::Text(text) = message? else {
            continue;
        };
        let event: serde_json::Value = serde_json::from_str(&text)?;
        if let Some(id) = event.get("id").and_then(serde_json::Value::as_i64) {
            if let Some(cdp_session) = page_enable_commands.remove(&id) {
                if event.get("error").is_some() {
                    anyhow::bail!("required Page.enable command failed");
                }
                let command_id = next_command_id;
                next_command_id = next_command_id.saturating_add(1);
                send_probe(&mut socket, command_id, &cdp_session).await?;
                probe_commands.insert(command_id, cdp_session);
            } else if let Some(cdp_session) = probe_commands.remove(&id) {
                if event.get("error").is_none() {
                    if let Some(tab_id) = target_by_session.get(&cdp_session) {
                        let mut guard = observations.write().await;
                        let observation = guard.entry(session_id.to_owned()).or_default();
                        // A successful Runtime probe is impossible while a native JavaScript
                        // dialog blocks this Page. It therefore closes any lifecycle gap after a
                        // Node observer reconnect without guessing or dismissing the dialog.
                        observation.dialogs.remove(tab_id);
                        observation.fresh_tab_ids.insert(tab_id.clone());
                    }
                }
            } else if event.get("error").is_some() && matches!(id, 9_001 | 9_002) {
                anyhow::bail!("required Native Dialog observer command failed");
            }
            continue;
        }

        let method = event
            .get("method")
            .and_then(serde_json::Value::as_str)
            .unwrap_or_default();
        let cdp_session = event
            .get("sessionId")
            .and_then(serde_json::Value::as_str)
            .unwrap_or_default();
        match method {
            "Target.attachedToTarget"
                if event
                    .pointer("/params/targetInfo/type")
                    .and_then(serde_json::Value::as_str)
                    == Some("page") =>
            {
                let attached_session = bounded_text(
                    event
                        .pointer("/params/sessionId")
                        .and_then(serde_json::Value::as_str)
                        .unwrap_or_default(),
                    128,
                )?;
                let tab_id = bounded_text(
                    event
                        .pointer("/params/targetInfo/targetId")
                        .and_then(serde_json::Value::as_str)
                        .unwrap_or_default(),
                    128,
                )?;
                target_by_session.insert(attached_session.clone(), tab_id.clone());
                {
                    let mut guard = observations.write().await;
                    let observation = guard.entry(session_id.to_owned()).or_default();
                    observation.fresh_tab_ids.remove(&tab_id);
                }
                let command_id = next_command_id;
                next_command_id = next_command_id.saturating_add(1);
                send_command(
                    &mut socket,
                    command_id,
                    "Page.enable",
                    serde_json::json!({"enableFileChooserOpenedEvent": false}),
                    Some(&attached_session),
                )
                .await?;
                page_enable_commands.insert(command_id, attached_session);
            }
            "Target.detachedFromTarget" => {
                if let Some(detached_session) = event
                    .pointer("/params/sessionId")
                    .and_then(serde_json::Value::as_str)
                {
                    if let Some(tab_id) = target_by_session.remove(detached_session) {
                        let mut guard = observations.write().await;
                        let observation = guard.entry(session_id.to_owned()).or_default();
                        observation.dialogs.remove(&tab_id);
                        observation.fresh_tab_ids.remove(&tab_id);
                    }
                }
            }
            "Page.javascriptDialogOpening" => {
                let tab_id = target_by_session
                    .get(cdp_session)
                    .cloned()
                    .ok_or_else(|| anyhow::anyhow!("Dialog event has no attached Page Target"))?;
                let dialog_type = event
                    .pointer("/params/type")
                    .and_then(serde_json::Value::as_str)
                    .unwrap_or_default()
                    .to_ascii_uppercase();
                anyhow::ensure!(
                    matches!(
                        dialog_type.as_str(),
                        "ALERT" | "CONFIRM" | "PROMPT" | "BEFOREUNLOAD"
                    ),
                    "unsupported native JavaScript Dialog type"
                );
                let message = bounded_optional_text(
                    event
                        .pointer("/params/message")
                        .and_then(serde_json::Value::as_str)
                        .unwrap_or_default(),
                )?;
                let default_prompt = bounded_optional_text(
                    event
                        .pointer("/params/defaultPrompt")
                        .and_then(serde_json::Value::as_str)
                        .unwrap_or_default(),
                )?;
                let has_browser_handler = event
                    .pointer("/params/hasBrowserHandler")
                    .and_then(serde_json::Value::as_bool)
                    .unwrap_or(false);
                let nonce = event
                    .pointer("/params/url")
                    .and_then(serde_json::Value::as_str)
                    .unwrap_or_default();
                let digest = Sha256::digest(
                    format!(
                        "native-dialog-v1\n{tab_id}\n{dialog_type}\n{message}\n{default_prompt}\n{nonce}"
                    )
                    .as_bytes(),
                );
                let dialog = NativeDialog {
                    dialog_id: format!("dlg_{}", hex::encode(&digest[..10])),
                    tab_id: tab_id.clone(),
                    dialog_type,
                    message,
                    default_prompt,
                    has_browser_handler,
                };
                let mut guard = observations.write().await;
                let observation = guard.entry(session_id.to_owned()).or_default();
                observation.dialogs.insert(tab_id.clone(), dialog);
                observation.fresh_tab_ids.insert(tab_id);
            }
            "Page.javascriptDialogClosed" => {
                if let Some(tab_id) = target_by_session.get(cdp_session) {
                    let mut guard = observations.write().await;
                    let observation = guard.entry(session_id.to_owned()).or_default();
                    observation.dialogs.remove(tab_id);
                    observation.fresh_tab_ids.insert(tab_id.clone());
                }
            }
            _ => {}
        }
    }
    Ok(())
}

async fn send_probe<S>(
    socket: &mut tokio_tungstenite::WebSocketStream<S>,
    id: i64,
    session_id: &str,
) -> anyhow::Result<()>
where
    S: tokio::io::AsyncRead + tokio::io::AsyncWrite + Unpin,
{
    send_command(
        socket,
        id,
        "Runtime.evaluate",
        serde_json::json!({
            "expression": "void 0",
            "returnByValue": true,
            "awaitPromise": false
        }),
        Some(session_id),
    )
    .await
}

async fn browser_websocket(endpoint: &str) -> anyhow::Result<String> {
    let client = reqwest::Client::builder()
        .connect_timeout(Duration::from_secs(1))
        .timeout(Duration::from_secs(2))
        .no_proxy()
        .build()?;
    let version: CdpVersion = client
        .get(format!("{endpoint}/json/version"))
        .send()
        .await?
        .error_for_status()?
        .json()
        .await?;
    Ok(version.web_socket_debugger_url)
}

fn require_loopback_websocket(websocket_url: &str) -> anyhow::Result<()> {
    let url = reqwest::Url::parse(websocket_url)?;
    anyhow::ensure!(url.scheme() == "ws", "CDP websocket must use ws");
    let host = url
        .host_str()
        .ok_or_else(|| anyhow::anyhow!("CDP websocket host is unavailable"))?;
    let loopback = host.eq_ignore_ascii_case("localhost")
        || host
            .parse::<std::net::IpAddr>()
            .map(|address| address.is_loopback())
            .unwrap_or(false);
    anyhow::ensure!(loopback, "CDP websocket must use Browser Node loopback");
    Ok(())
}

async fn send_command<S>(
    socket: &mut tokio_tungstenite::WebSocketStream<S>,
    id: i64,
    method: &str,
    params: serde_json::Value,
    session_id: Option<&str>,
) -> anyhow::Result<()>
where
    S: tokio::io::AsyncRead + tokio::io::AsyncWrite + Unpin,
{
    let mut command = serde_json::json!({"id": id, "method": method, "params": params});
    if let Some(session_id) = session_id {
        command["sessionId"] = serde_json::Value::String(session_id.to_owned());
    }
    socket.send(Message::Text(command.to_string())).await?;
    Ok(())
}

async fn mark_all_stale(
    observations: &Arc<RwLock<HashMap<String, NativeDialogObservation>>>,
    session_id: &str,
) {
    observations
        .write()
        .await
        .entry(session_id.to_owned())
        .or_default()
        .fresh_tab_ids
        .clear();
}

fn bounded_text(value: &str, max_bytes: usize) -> anyhow::Result<String> {
    anyhow::ensure!(
        !value.is_empty() && value.len() <= max_bytes && !value.chars().any(char::is_control),
        "CDP Native Dialog identifier is invalid"
    );
    Ok(value.to_owned())
}

fn bounded_optional_text(value: &str) -> anyhow::Result<String> {
    anyhow::ensure!(
        value.len() <= MAX_DIALOG_TEXT_BYTES
            && !value.chars().any(|character| {
                character.is_control() && !matches!(character, '\n' | '\r' | '\t')
            }),
        "CDP Native Dialog text is invalid"
    );
    Ok(value.to_owned())
}

mod hex {
    pub fn encode(bytes: &[u8]) -> String {
        bytes.iter().map(|byte| format!("{byte:02x}")).collect()
    }
}
