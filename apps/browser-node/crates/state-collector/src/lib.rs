//! State Collector。
//!
//! 负责采集浏览器当前状态。

use async_trait::async_trait;
use futures_util::{SinkExt, StreamExt};
use serde::{Deserialize, Serialize};
use std::collections::{hash_map::DefaultHasher, HashMap};
use std::hash::{Hash, Hasher};
use std::sync::Arc;
use tokio::sync::{Mutex, RwLock};
use tokio::time::{timeout, Duration};
use tokio_tungstenite::tungstenite::Message;

/// 交互目标。
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct InteractiveTarget {
    /// 目标引用
    pub target_ref: String,
    /// 角色
    pub role: String,
    /// 名称
    pub name: Option<String>,
    /// 边界
    pub bounds: Option<Bounds>,
    /// 是否启用
    pub enabled: bool,
    /// 是否可见
    pub visible: bool,
}

/// 边界。
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Bounds {
    pub x: f64,
    pub y: f64,
    pub width: f64,
    pub height: f64,
}

/// 状态质量。
#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum StateQuality {
    Complete,
    DepthLimited,
    Resyncing,
    Degraded,
    Invalid,
}

/// 当前状态。
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CurrentState {
    /// Session ID
    pub session_id: String,
    /// 状态版本
    pub state_version: u64,
    /// 目标版本
    pub target_revision: u64,
    /// URL
    pub url: String,
    /// 标题
    pub title: String,
    /// 交互目标列表
    pub targets: Vec<InteractiveTarget>,
    /// 状态质量
    pub quality: StateQuality,
    /// 内容哈希
    pub content_hash: String,
}

/// 浏览器状态采集器 trait。
#[async_trait]
pub trait BrowserStateCollector: Send + Sync {
    /// 采集当前状态。
    async fn collect_current_state(&self, session_id: &str) -> anyhow::Result<CurrentState>;

    /// 重新同步区域。
    async fn resync_region(&self, session_id: &str, root_ref: &str)
        -> anyhow::Result<CurrentState>;
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct CdpTarget {
    #[serde(rename = "type")]
    target_type: String,
    web_socket_debugger_url: String,
}

#[derive(Debug, Deserialize)]
struct EvaluatedPageState {
    url: String,
    title: String,
    targets: Vec<EvaluatedTarget>,
}

#[derive(Debug, Serialize, Deserialize)]
struct EvaluatedTarget {
    role: String,
    name: Option<String>,
    bounds: Option<Bounds>,
    enabled: bool,
    visible: bool,
}

/// 基于真实 Chrome DevTools Protocol 的基础 State Collector。
///
/// 只连接 Browser Node 内部回环地址，不将 CDP 暴露给 Agent 或客户端。
#[derive(Clone, Default)]
pub struct CdpStateCollector {
    endpoints: Arc<RwLock<HashMap<String, String>>>,
    versions: Arc<Mutex<HashMap<String, u64>>>,
}

impl CdpStateCollector {
    pub fn new() -> Self {
        Self::default()
    }

    pub async fn register_runtime(
        &self,
        session_id: &str,
        cdp_endpoint: &str,
    ) -> anyhow::Result<()> {
        anyhow::ensure!(
            cdp_endpoint.starts_with("http://127.0.0.1:")
                || cdp_endpoint.starts_with("http://localhost:"),
            "CDP endpoint must use the Browser Node loopback interface"
        );
        self.endpoints.write().await.insert(
            session_id.to_owned(),
            cdp_endpoint.trim_end_matches('/').to_owned(),
        );
        Ok(())
    }

    pub async fn unregister_runtime(&self, session_id: &str) {
        self.endpoints.write().await.remove(session_id);
    }

    async fn target_websocket(&self, session_id: &str) -> anyhow::Result<String> {
        let endpoint = self
            .endpoints
            .read()
            .await
            .get(session_id)
            .cloned()
            .ok_or_else(|| anyhow::anyhow!("runtime CDP endpoint is not registered"))?;
        let response = reqwest::Client::builder()
            .connect_timeout(Duration::from_secs(1))
            .timeout(Duration::from_secs(2))
            .no_proxy()
            .build()?
            .get(format!("{endpoint}/json/list"))
            .send()
            .await?
            .error_for_status()?;
        let targets: Vec<CdpTarget> = response.json().await?;
        targets
            .into_iter()
            .find(|target| target.target_type == "page")
            .map(|target| target.web_socket_debugger_url)
            .ok_or_else(|| anyhow::anyhow!("CDP has no page target"))
    }

    async fn evaluate_page(&self, websocket_url: &str) -> anyhow::Result<EvaluatedPageState> {
        let (mut socket, _) = timeout(
            Duration::from_secs(3),
            tokio_tungstenite::connect_async(websocket_url),
        )
        .await
        .map_err(|_| anyhow::anyhow!("CDP websocket connection timed out"))??;
        let expression = r#"
            (() => {
              const selector = [
                'a[href]', 'button', 'input', 'select', 'textarea',
                '[role="button"]', '[role="link"]', '[role="checkbox"]',
                '[role="radio"]', '[role="textbox"]', '[tabindex]'
              ].join(',');
              const roleFor = (element) => {
                const explicit = element.getAttribute('role');
                if (explicit) return explicit;
                const tag = element.tagName.toLowerCase();
                if (tag === 'a') return 'link';
                if (tag === 'button') return 'button';
                if (tag === 'select') return 'combobox';
                if (tag === 'textarea') return 'textbox';
                if (tag === 'input') {
                  const type = (element.getAttribute('type') || 'text').toLowerCase();
                  if (type === 'checkbox') return 'checkbox';
                  if (type === 'radio') return 'radio';
                  if (type === 'submit' || type === 'button') return 'button';
                  return 'textbox';
                }
                return 'generic';
              };
              const nameFor = (element) => {
                const aria = element.getAttribute('aria-label');
                if (aria) return aria.slice(0, 256);
                const type = (element.getAttribute('type') || '').toLowerCase();
                if (type === 'password') return null;
                const text = element.innerText || element.getAttribute('placeholder') || '';
                return text.trim().slice(0, 256) || null;
              };
              const targets = Array.from(document.querySelectorAll(selector))
                .slice(0, 500)
                .map((element) => {
                  const rect = element.getBoundingClientRect();
                  const style = window.getComputedStyle(element);
                  const visible = rect.width > 0 && rect.height > 0
                    && style.visibility !== 'hidden' && style.display !== 'none';
                  return {
                    role: roleFor(element),
                    name: nameFor(element),
                    bounds: visible ? {
                      x: rect.x, y: rect.y, width: rect.width, height: rect.height
                    } : null,
                    enabled: !element.disabled && element.getAttribute('aria-disabled') !== 'true',
                    visible
                  };
                });
              return { url: location.href, title: document.title, targets };
            })()
        "#;
        let request = serde_json::json!({
            "id": 1,
            "method": "Runtime.evaluate",
            "params": {
                "expression": expression,
                "returnByValue": true,
                "awaitPromise": true
            }
        });
        socket.send(Message::Text(request.to_string())).await?;

        while let Some(message) = timeout(Duration::from_secs(3), socket.next())
            .await
            .map_err(|_| anyhow::anyhow!("CDP Runtime.evaluate timed out"))?
        {
            let message = message?;
            let Message::Text(text) = message else {
                continue;
            };
            let response: serde_json::Value = serde_json::from_str(&text)?;
            if response.get("id").and_then(serde_json::Value::as_i64) != Some(1) {
                continue;
            }
            if let Some(error) = response.get("error") {
                anyhow::bail!("CDP Runtime.evaluate failed: {error}");
            }
            let value = response
                .pointer("/result/result/value")
                .cloned()
                .ok_or_else(|| anyhow::anyhow!("CDP response has no by-value result"))?;
            return Ok(serde_json::from_value(value)?);
        }
        anyhow::bail!("CDP websocket closed before Runtime.evaluate completed")
    }

    pub async fn navigate(&self, session_id: &str, url: &str) -> anyhow::Result<()> {
        anyhow::ensure!(
            url.starts_with("http://") || url.starts_with("https://") || url == "about:blank",
            "navigation URL scheme is not allowed"
        );
        let websocket_url = self.target_websocket(session_id).await?;
        let (mut socket, _) = timeout(
            Duration::from_secs(3),
            tokio_tungstenite::connect_async(websocket_url),
        )
        .await
        .map_err(|_| anyhow::anyhow!("CDP websocket connection timed out"))??;
        socket
            .send(Message::Text(
                serde_json::json!({
                    "id": 2,
                    "method": "Page.navigate",
                    "params": {"url": url}
                })
                .to_string(),
            ))
            .await?;
        while let Some(message) = timeout(Duration::from_secs(5), socket.next())
            .await
            .map_err(|_| anyhow::anyhow!("CDP Page.navigate timed out"))?
        {
            let message = message?;
            let Message::Text(text) = message else {
                continue;
            };
            let response: serde_json::Value = serde_json::from_str(&text)?;
            if response.get("id").and_then(serde_json::Value::as_i64) != Some(2) {
                continue;
            }
            if let Some(error) = response.get("error") {
                anyhow::bail!("CDP Page.navigate failed: {error}");
            }
            if let Some(error_text) = response
                .pointer("/result/errorText")
                .and_then(serde_json::Value::as_str)
            {
                anyhow::bail!("CDP navigation failed: {error_text}");
            }
            return Ok(());
        }
        anyhow::bail!("CDP websocket closed before Page.navigate completed")
    }

    async fn collect(&self, session_id: &str) -> anyhow::Result<CurrentState> {
        let websocket_url = self.target_websocket(session_id).await?;
        let page = self.evaluate_page(&websocket_url).await?;
        let state_version = {
            let mut versions = self.versions.lock().await;
            let version = versions.entry(session_id.to_owned()).or_default();
            *version += 1;
            *version
        };
        let mut hasher = DefaultHasher::new();
        page.url.hash(&mut hasher);
        page.title.hash(&mut hasher);
        serde_json::to_string(&page.targets)?.hash(&mut hasher);
        let content_hash = format!("{:016x}", hasher.finish());
        let targets = page
            .targets
            .into_iter()
            .enumerate()
            .map(|(index, target)| InteractiveTarget {
                target_ref: format!("target:{state_version}:{index}"),
                role: target.role,
                name: target.name,
                bounds: target.bounds,
                enabled: target.enabled,
                visible: target.visible,
            })
            .collect::<Vec<_>>();

        Ok(CurrentState {
            session_id: session_id.to_owned(),
            state_version,
            target_revision: state_version,
            url: page.url,
            title: page.title,
            targets,
            quality: StateQuality::Complete,
            content_hash,
        })
    }
}

#[async_trait]
impl BrowserStateCollector for CdpStateCollector {
    async fn collect_current_state(&self, session_id: &str) -> anyhow::Result<CurrentState> {
        self.collect(session_id).await
    }

    async fn resync_region(
        &self,
        session_id: &str,
        _root_ref: &str,
    ) -> anyhow::Result<CurrentState> {
        // 首版安全地执行全量采集；后续可在 DOMSnapshot 上增加区域裁剪。
        self.collect(session_id).await
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::path::PathBuf;
    use std::time::{SystemTime, UNIX_EPOCH};
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    use tokio::net::TcpListener;

    #[tokio::test]
    async fn collects_page_and_interactive_targets_over_cdp() {
        let websocket_listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let websocket_address = websocket_listener.local_addr().unwrap();
        let websocket_task = tokio::spawn(async move {
            for _ in 0..2 {
                let (stream, _) = websocket_listener.accept().await.unwrap();
                let mut socket = tokio_tungstenite::accept_async(stream).await.unwrap();
                let request = socket.next().await.unwrap().unwrap();
                let Message::Text(request) = request else {
                    panic!("expected CDP text request");
                };
                let request: serde_json::Value = serde_json::from_str(&request).unwrap();
                assert_eq!(request["method"], "Runtime.evaluate");
                let response = serde_json::json!({
                    "id": 1,
                    "result": {
                        "result": {
                            "type": "object",
                            "value": {
                                "url": "https://example.test/form",
                                "title": "Example form",
                                "targets": [{
                                    "role": "button",
                                    "name": "提交",
                                    "bounds": {"x": 12.0, "y": 24.0, "width": 96.0, "height": 32.0},
                                    "enabled": true,
                                    "visible": true
                                }]
                            }
                        }
                    }
                });
                socket
                    .send(Message::Text(response.to_string()))
                    .await
                    .unwrap();
            }
        });

        let http_listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let http_address = http_listener.local_addr().unwrap();
        let http_task = tokio::spawn(async move {
            let body = serde_json::json!([{
                "id": "page-1",
                "type": "page",
                "webSocketDebuggerUrl": format!("ws://{websocket_address}/devtools/page/1")
            }])
            .to_string();
            for _ in 0..2 {
                let (mut stream, _) = http_listener.accept().await.unwrap();
                let mut request = vec![0_u8; 4096];
                let count = stream.read(&mut request).await.unwrap();
                let request = String::from_utf8_lossy(&request[..count]);
                assert!(request.starts_with("GET /json/list "));
                let response = format!(
                    "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}",
                    body.len(),
                    body
                );
                stream.write_all(response.as_bytes()).await.unwrap();
            }
        });

        let collector = CdpStateCollector::new();
        collector
            .register_runtime("ses_state", &format!("http://{http_address}"))
            .await
            .unwrap();
        let state = collector.collect_current_state("ses_state").await.unwrap();
        assert_eq!(state.url, "https://example.test/form");
        assert_eq!(state.title, "Example form");
        assert_eq!(state.state_version, 1);
        assert_eq!(state.targets.len(), 1);
        assert_eq!(state.targets[0].role, "button");
        assert_eq!(state.targets[0].name.as_deref(), Some("提交"));
        assert!(matches!(state.quality, StateQuality::Complete));
        let repeated = collector.collect_current_state("ses_state").await.unwrap();
        assert_eq!(repeated.state_version, 2);
        assert_ne!(repeated.targets[0].target_ref, state.targets[0].target_ref);
        assert_eq!(repeated.content_hash, state.content_hash);

        websocket_task.await.unwrap();
        http_task.await.unwrap();
    }

    #[tokio::test]
    async fn rejects_non_loopback_cdp_endpoint() {
        let collector = CdpStateCollector::new();
        let result = collector
            .register_runtime("ses_state", "http://192.0.2.10:9222")
            .await;
        assert!(result.is_err());
    }

    #[tokio::test]
    #[ignore = "requires REAL_CHROMIUM_PATH and launches a local browser"]
    async fn collects_state_from_real_chromium() {
        let chromium = std::env::var("REAL_CHROMIUM_PATH")
            .expect("REAL_CHROMIUM_PATH must point to Chromium or Google Chrome");
        let port_reservation = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let cdp_port = port_reservation.local_addr().unwrap().port();
        drop(port_reservation);

        let page_listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let page_address = page_listener.local_addr().unwrap();
        let page_task = tokio::spawn(async move {
            loop {
                let Ok((mut stream, _)) = page_listener.accept().await else {
                    return;
                };
                tokio::spawn(async move {
                    let mut request = vec![0_u8; 4096];
                    let _ = stream.read(&mut request).await;
                    let body = "<!doctype html><html><head><title>Runtime Gate</title></head><body><button aria-label=\"执行验收\">Run</button><input placeholder=\"Name\"></body></html>";
                    let response = format!(
                        "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}",
                        body.len(),
                        body
                    );
                    let _ = stream.write_all(response.as_bytes()).await;
                });
            }
        });

        let nonce = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        let profile = std::env::temp_dir().join(format!("browsercloud-real-cdp-{nonce}"));
        tokio::fs::create_dir_all(&profile).await.unwrap();
        let mut child = tokio::process::Command::new(PathBuf::from(chromium))
            .arg("--headless=new")
            .arg("--no-first-run")
            .arg("--no-default-browser-check")
            .arg("--disable-background-networking")
            .arg(format!("--user-data-dir={}", profile.display()))
            .arg(format!("--remote-debugging-port={cdp_port}"))
            .arg("--remote-debugging-address=127.0.0.1")
            .arg("about:blank")
            .kill_on_drop(true)
            .spawn()
            .unwrap();

        let collector = CdpStateCollector::new();
        let endpoint = format!("http://127.0.0.1:{cdp_port}");
        collector
            .register_runtime("ses_real_chromium", &endpoint)
            .await
            .unwrap();
        let mut ready = false;
        for _ in 0..100 {
            if collector
                .target_websocket("ses_real_chromium")
                .await
                .is_ok()
            {
                ready = true;
                break;
            }
            tokio::time::sleep(Duration::from_millis(100)).await;
        }
        assert!(ready, "real Chromium CDP did not become ready");

        collector
            .navigate(
                "ses_real_chromium",
                &format!("http://{page_address}/runtime-gate"),
            )
            .await
            .unwrap();
        let mut collected = None;
        for _ in 0..50 {
            if let Ok(state) = collector.collect_current_state("ses_real_chromium").await {
                if state.title == "Runtime Gate" {
                    collected = Some(state);
                    break;
                }
            }
            tokio::time::sleep(Duration::from_millis(100)).await;
        }
        let state = collected.expect("real Chromium page state was not collected");
        assert_eq!(state.url, format!("http://{page_address}/runtime-gate"));
        assert!(state
            .targets
            .iter()
            .any(|target| target.role == "button" && target.name.as_deref() == Some("执行验收")));
        assert!(state
            .targets
            .iter()
            .any(|target| target.role == "textbox" && target.name.as_deref() == Some("Name")));

        let _ = child.start_kill();
        let _ = child.wait().await;
        page_task.abort();
        let _ = tokio::fs::remove_dir_all(profile).await;
    }
}
