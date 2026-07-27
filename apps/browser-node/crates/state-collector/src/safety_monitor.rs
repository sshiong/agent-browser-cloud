use futures_util::{SinkExt, StreamExt};
use serde::Deserialize;
use std::collections::{HashMap, HashSet};
use std::sync::Arc;
use tokio::sync::RwLock;
use tokio::task::JoinHandle;
use tokio::time::{timeout, Duration};
use tokio_tungstenite::tungstenite::Message;

/// Browser Node 从持续 CDP 事件流读取的安全点活动快照。
///
/// `fresh` 只在 Browser 下载事件和至少一个 Page Network Domain 都成功启用后为 true。
/// 连接丢失后不会在同一 Runtime 代内重新变为 true，因为重连无法重建已经在途的请求。
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct BrowserSafetyObservation {
    pub fresh: bool,
    pub active_upload_count: u32,
    pub active_download_count: u32,
    pub active_form_submission_count: u32,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct CdpVersion {
    web_socket_debugger_url: String,
}

#[derive(Debug, Default, Clone, Copy)]
struct RequestActivity {
    upload: bool,
    download: bool,
    form_submission: bool,
}

#[derive(Debug, Default)]
struct ActivityTracker {
    requests: HashMap<(String, String), RequestActivity>,
    browser_downloads: HashSet<String>,
    download_events_enabled: bool,
    network_sessions: HashSet<String>,
    fresh_allowed: bool,
    was_fresh: bool,
}

impl ActivityTracker {
    fn observation(&self) -> BrowserSafetyObservation {
        // The same browser download is normally visible both as a Network response and as a
        // Browser download GUID. Use the larger live set as a conservative de-duplicated count
        // instead of summing both protocol surfaces.
        let network_download_count = self
            .requests
            .values()
            .filter(|activity| activity.download)
            .count();
        BrowserSafetyObservation {
            fresh: self.fresh_allowed
                && self.download_events_enabled
                && !self.network_sessions.is_empty(),
            active_upload_count: self
                .requests
                .values()
                .filter(|activity| activity.upload)
                .count()
                .try_into()
                .unwrap_or(u32::MAX),
            active_download_count: network_download_count
                .max(self.browser_downloads.len())
                .try_into()
                .unwrap_or(u32::MAX),
            active_form_submission_count: self
                .requests
                .values()
                .filter(|activity| activity.form_submission)
                .count()
                .try_into()
                .unwrap_or(u32::MAX),
        }
    }

    fn remove_session(&mut self, session_id: &str) {
        self.network_sessions.remove(session_id);
        self.requests
            .retain(|(request_session, _), _| request_session != session_id);
    }

    fn next_observation(&mut self) -> BrowserSafetyObservation {
        let observation = self.observation();
        self.was_fresh |= observation.fresh;
        observation
    }
}

pub(crate) fn spawn(
    session_id: String,
    endpoint: String,
    observations: Arc<RwLock<HashMap<String, BrowserSafetyObservation>>>,
) -> JoinHandle<()> {
    tokio::spawn(async move {
        let mut established_once = false;
        loop {
            let websocket_url = match browser_websocket(&endpoint).await {
                Ok(websocket_url) => websocket_url,
                Err(error) => {
                    tracing::warn!(
                        session_id,
                        error = %error,
                        "Browser safety observer could not resolve CDP Browser endpoint"
                    );
                    tokio::time::sleep(Duration::from_secs(1)).await;
                    continue;
                }
            };
            let mut tracker = ActivityTracker {
                fresh_allowed: !established_once,
                ..ActivityTracker::default()
            };
            let result =
                observe_browser(&websocket_url, &session_id, &observations, &mut tracker).await;
            established_once |= tracker.was_fresh;
            publish(
                &observations,
                &session_id,
                BrowserSafetyObservation::default(),
            )
            .await;
            match result {
                Ok(()) => {
                    tracing::warn!(session_id, "Browser safety observer CDP connection closed");
                }
                Err(error) => {
                    tracing::warn!(
                        session_id,
                        error = %error,
                        "Browser safety observer CDP connection failed"
                    );
                }
            }
            tokio::time::sleep(Duration::from_secs(1)).await;
        }
    })
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

async fn observe_browser(
    websocket_url: &str,
    session_id: &str,
    observations: &Arc<RwLock<HashMap<String, BrowserSafetyObservation>>>,
    tracker: &mut ActivityTracker,
) -> anyhow::Result<()> {
    let (mut socket, _) = timeout(
        Duration::from_secs(3),
        tokio_tungstenite::connect_async(websocket_url),
    )
    .await
    .map_err(|_| anyhow::anyhow!("CDP browser safety connection timed out"))??;

    send_command(
        &mut socket,
        8_001,
        "Target.setDiscoverTargets",
        serde_json::json!({"discover": true}),
        None,
    )
    .await?;
    send_command(
        &mut socket,
        8_002,
        "Target.setAutoAttach",
        serde_json::json!({
            "autoAttach": true,
            "waitForDebuggerOnStart": false,
            "flatten": true
        }),
        None,
    )
    .await?;
    send_command(
        &mut socket,
        8_003,
        "Browser.setDownloadBehavior",
        serde_json::json!({"behavior": "default", "eventsEnabled": true}),
        None,
    )
    .await?;

    let mut next_command_id = 8_100_i64;
    let mut network_enable_commands = HashMap::<i64, String>::new();
    while let Some(message) = socket.next().await {
        let Message::Text(text) = message? else {
            continue;
        };
        let event: serde_json::Value = serde_json::from_str(&text)?;
        if let Some(id) = event.get("id").and_then(serde_json::Value::as_i64) {
            if let Some(network_session) = network_enable_commands.remove(&id) {
                if event.get("error").is_none() {
                    tracker.network_sessions.insert(network_session);
                }
            } else if id == 8_003 && event.get("error").is_none() {
                tracker.download_events_enabled = true;
            } else if event.get("error").is_some() && matches!(id, 8_001..=8_003) {
                anyhow::bail!("required CDP safety domain command {id} failed");
            }
            let observation = tracker.next_observation();
            publish(observations, session_id, observation).await;
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
                let attached_session = event
                    .pointer("/params/sessionId")
                    .and_then(serde_json::Value::as_str)
                    .ok_or_else(|| anyhow::anyhow!("attached Page Target has no sessionId"))?
                    .to_owned();
                let command_id = next_command_id;
                next_command_id = next_command_id.saturating_add(1);
                send_command(
                    &mut socket,
                    command_id,
                    "Network.enable",
                    serde_json::json!({
                        "maxTotalBufferSize": 65_536,
                        "maxResourceBufferSize": 16_384,
                        "maxPostDataSize": 0
                    }),
                    Some(&attached_session),
                )
                .await?;
                network_enable_commands.insert(command_id, attached_session);
            }
            "Target.detachedFromTarget" => {
                if let Some(detached_session) = event
                    .pointer("/params/sessionId")
                    .and_then(serde_json::Value::as_str)
                {
                    tracker.remove_session(detached_session);
                }
            }
            "Network.requestWillBeSent" => {
                let request_id = event
                    .pointer("/params/requestId")
                    .and_then(serde_json::Value::as_str)
                    .unwrap_or_default();
                if !request_id.is_empty() && !cdp_session.is_empty() {
                    let request = event
                        .pointer("/params/request")
                        .cloned()
                        .unwrap_or_default();
                    let method = request
                        .get("method")
                        .and_then(serde_json::Value::as_str)
                        .unwrap_or_default()
                        .to_ascii_uppercase();
                    let resource_type = event
                        .pointer("/params/type")
                        .and_then(serde_json::Value::as_str)
                        .unwrap_or_default();
                    let content_type = header(&request["headers"], "content-type");
                    let content_disposition = header(&request["headers"], "content-disposition");
                    let upload = content_type.contains("multipart/form-data")
                        || content_type.contains("application/octet-stream")
                        || content_disposition.contains("form-data")
                        || content_disposition.contains("attachment");
                    let form_submission = resource_type == "Document"
                        && !matches!(method.as_str(), "GET" | "HEAD" | "OPTIONS");
                    tracker.requests.insert(
                        (cdp_session.to_owned(), request_id.to_owned()),
                        RequestActivity {
                            upload,
                            form_submission,
                            ..RequestActivity::default()
                        },
                    );
                }
            }
            "Network.responseReceived" => {
                let request_id = event
                    .pointer("/params/requestId")
                    .and_then(serde_json::Value::as_str)
                    .unwrap_or_default();
                let disposition = header(
                    event
                        .pointer("/params/response/headers")
                        .unwrap_or(&serde_json::Value::Null),
                    "content-disposition",
                );
                if !request_id.is_empty()
                    && !cdp_session.is_empty()
                    && disposition.contains("attachment")
                {
                    tracker
                        .requests
                        .entry((cdp_session.to_owned(), request_id.to_owned()))
                        .or_default()
                        .download = true;
                }
            }
            "Network.loadingFinished" | "Network.loadingFailed" => {
                if let Some(request_id) = event
                    .pointer("/params/requestId")
                    .and_then(serde_json::Value::as_str)
                {
                    tracker
                        .requests
                        .remove(&(cdp_session.to_owned(), request_id.to_owned()));
                }
            }
            "Browser.downloadWillBegin" => {
                if let Some(guid) = event
                    .pointer("/params/guid")
                    .and_then(serde_json::Value::as_str)
                {
                    tracker.browser_downloads.insert(guid.to_owned());
                }
            }
            "Browser.downloadProgress" => {
                if matches!(
                    event
                        .pointer("/params/state")
                        .and_then(serde_json::Value::as_str),
                    Some("completed" | "canceled")
                ) {
                    if let Some(guid) = event
                        .pointer("/params/guid")
                        .and_then(serde_json::Value::as_str)
                    {
                        tracker.browser_downloads.remove(guid);
                    }
                }
            }
            _ => {}
        }
        let observation = tracker.next_observation();
        publish(observations, session_id, observation).await;
    }
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

fn header(headers: &serde_json::Value, expected: &str) -> String {
    headers
        .as_object()
        .and_then(|headers| {
            headers.iter().find_map(|(name, value)| {
                name.eq_ignore_ascii_case(expected)
                    .then(|| value.as_str())
                    .flatten()
            })
        })
        .unwrap_or_default()
        .to_ascii_lowercase()
}

async fn publish(
    observations: &Arc<RwLock<HashMap<String, BrowserSafetyObservation>>>,
    session_id: &str,
    observation: BrowserSafetyObservation,
) {
    observations
        .write()
        .await
        .insert(session_id.to_owned(), observation);
}

#[cfg(test)]
mod tests {
    use super::*;
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    use tokio::net::TcpListener;

    #[test]
    fn remembers_freshness_after_the_last_page_detaches() {
        let mut tracker = ActivityTracker {
            download_events_enabled: true,
            fresh_allowed: true,
            ..ActivityTracker::default()
        };
        tracker.network_sessions.insert("page-1".to_owned());
        assert!(tracker.next_observation().fresh);

        tracker.remove_session("page-1");
        assert!(!tracker.next_observation().fresh);
        assert!(tracker.was_fresh);
    }

    #[tokio::test]
    async fn tracks_upload_submission_and_download_lifecycle() {
        let websocket_listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let websocket_address = websocket_listener.local_addr().unwrap();
        let websocket_task = tokio::spawn(async move {
            let (stream, _) = websocket_listener.accept().await.unwrap();
            let mut socket = tokio_tungstenite::accept_async(stream).await.unwrap();
            for expected in [
                "Target.setDiscoverTargets",
                "Target.setAutoAttach",
                "Browser.setDownloadBehavior",
            ] {
                let Message::Text(command) = socket.next().await.unwrap().unwrap() else {
                    panic!("expected CDP command")
                };
                let command: serde_json::Value = serde_json::from_str(&command).unwrap();
                assert_eq!(command["method"], expected);
                socket
                    .send(Message::Text(
                        serde_json::json!({"id": command["id"], "result": {}}).to_string(),
                    ))
                    .await
                    .unwrap();
            }
            socket
                .send(Message::Text(
                    serde_json::json!({
                        "method": "Target.attachedToTarget",
                        "params": {
                            "sessionId": "page-session-1",
                            "targetInfo": {"type": "page"}
                        }
                    })
                    .to_string(),
                ))
                .await
                .unwrap();
            let Message::Text(network_enable) = socket.next().await.unwrap().unwrap() else {
                panic!("expected Network.enable")
            };
            let network_enable: serde_json::Value = serde_json::from_str(&network_enable).unwrap();
            assert_eq!(network_enable["method"], "Network.enable");
            assert_eq!(network_enable["sessionId"], "page-session-1");
            socket
                .send(Message::Text(
                    serde_json::json!({"id": network_enable["id"], "result": {}}).to_string(),
                ))
                .await
                .unwrap();
            socket
                .send(Message::Text(
                    serde_json::json!({
                        "sessionId": "page-session-1",
                        "method": "Network.requestWillBeSent",
                        "params": {
                            "requestId": "upload-1",
                            "type": "Document",
                            "request": {
                                "method": "POST",
                                "headers": {"Content-Type": "multipart/form-data; boundary=x"}
                            }
                        }
                    })
                    .to_string(),
                ))
                .await
                .unwrap();
            socket
                .send(Message::Text(
                    serde_json::json!({
                        "method": "Browser.downloadWillBegin",
                        "params": {"guid": "download-1"}
                    })
                    .to_string(),
                ))
                .await
                .unwrap();
            tokio::time::sleep(Duration::from_millis(100)).await;
            socket
                .send(Message::Text(
                    serde_json::json!({
                        "sessionId": "page-session-1",
                        "method": "Network.loadingFinished",
                        "params": {"requestId": "upload-1"}
                    })
                    .to_string(),
                ))
                .await
                .unwrap();
            socket
                .send(Message::Text(
                    serde_json::json!({
                        "method": "Browser.downloadProgress",
                        "params": {"guid": "download-1", "state": "completed"}
                    })
                    .to_string(),
                ))
                .await
                .unwrap();
            tokio::time::sleep(Duration::from_millis(100)).await;
        });

        let http_listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let http_address = http_listener.local_addr().unwrap();
        let http_task = tokio::spawn(async move {
            let (mut stream, _) = http_listener.accept().await.unwrap();
            let mut request = vec![0_u8; 4096];
            let count = stream.read(&mut request).await.unwrap();
            assert!(String::from_utf8_lossy(&request[..count]).starts_with("GET /json/version "));
            let body = serde_json::json!({
                "webSocketDebuggerUrl": format!("ws://{websocket_address}/devtools/browser/1")
            })
            .to_string();
            let response = format!(
                "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}",
                body.len(),
                body
            );
            stream.write_all(response.as_bytes()).await.unwrap();
        });

        let observations = Arc::new(RwLock::new(HashMap::new()));
        let monitor = spawn(
            "ses_safety".to_owned(),
            format!("http://{http_address}"),
            Arc::clone(&observations),
        );
        let mut active = None;
        for _ in 0..100 {
            let observation = observations
                .read()
                .await
                .get("ses_safety")
                .cloned()
                .unwrap_or_default();
            if observation.fresh
                && observation.active_upload_count == 1
                && observation.active_download_count == 1
                && observation.active_form_submission_count == 1
            {
                active = Some(observation);
                break;
            }
            tokio::time::sleep(Duration::from_millis(10)).await;
        }
        assert!(
            active.is_some(),
            "active browser operations were not observed"
        );
        let mut completed = None;
        for _ in 0..100 {
            let observation = observations
                .read()
                .await
                .get("ses_safety")
                .cloned()
                .unwrap_or_default();
            if observation.fresh
                && observation.active_upload_count == 0
                && observation.active_download_count == 0
                && observation.active_form_submission_count == 0
            {
                completed = Some(observation);
                break;
            }
            tokio::time::sleep(Duration::from_millis(10)).await;
        }
        assert!(
            completed.is_some(),
            "completed browser operations remained active"
        );

        monitor.abort();
        websocket_task.await.unwrap();
        http_task.await.unwrap();
    }
}
