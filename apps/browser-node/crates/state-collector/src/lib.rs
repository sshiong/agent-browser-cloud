//! State Collector。
//!
//! 负责采集浏览器当前状态。

use async_trait::async_trait;
use futures_util::{SinkExt, StreamExt};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::collections::{HashMap, HashSet};
use std::sync::Arc;
use tokio::sync::{Mutex, RwLock};
use tokio::time::{timeout, Duration};
use tokio_tungstenite::tungstenite::Message;

/// 交互目标。
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
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
    /// 是否为 Password/OTP 等敏感输入目标
    pub sensitive: bool,
}

/// 边界。
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct Bounds {
    pub x: f64,
    pub y: f64,
    pub width: f64,
    pub height: f64,
}

/// 状态质量。
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub enum StateQuality {
    Complete,
    DepthLimited,
    Resyncing,
    Degraded,
    Invalid,
}

/// 当前状态。
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
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

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct StateDiff {
    pub session_id: String,
    pub base_state_version: u64,
    pub state_version: u64,
    pub target_revision: u64,
    pub url: String,
    pub title: String,
    pub quality: StateQuality,
    pub content_hash: String,
    pub upserted_targets: Vec<InteractiveTarget>,
    pub removed_target_refs: Vec<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct DiffTruncated {
    pub session_id: String,
    pub reason: String,
    pub last_good_state_version: u64,
    pub current_state_version: u64,
    pub affected_root: String,
    pub estimated_targets: usize,
}

#[derive(Debug, Clone, PartialEq)]
pub enum DiffOutcome {
    Diff(StateDiff),
    Truncated(DiffTruncated),
}

pub fn diff_states(
    previous: &CurrentState,
    current: &CurrentState,
    max_bytes: usize,
    max_changes: usize,
) -> anyhow::Result<DiffOutcome> {
    anyhow::ensure!(
        previous.session_id == current.session_id,
        "cannot diff states from different sessions"
    );
    let previous_by_ref = previous
        .targets
        .iter()
        .map(|target| (target.target_ref.as_str(), target))
        .collect::<HashMap<_, _>>();
    let current_refs = current
        .targets
        .iter()
        .map(|target| target.target_ref.as_str())
        .collect::<HashSet<_>>();
    let upserted_targets = current
        .targets
        .iter()
        .filter(|target| previous_by_ref.get(target.target_ref.as_str()) != Some(target))
        .cloned()
        .collect::<Vec<_>>();
    let removed_target_refs = previous
        .targets
        .iter()
        .filter(|target| !current_refs.contains(target.target_ref.as_str()))
        .map(|target| target.target_ref.clone())
        .collect::<Vec<_>>();
    let diff = StateDiff {
        session_id: current.session_id.clone(),
        base_state_version: previous.state_version,
        state_version: current.state_version,
        target_revision: current.target_revision,
        url: current.url.clone(),
        title: current.title.clone(),
        quality: current.quality.clone(),
        content_hash: current.content_hash.clone(),
        upserted_targets,
        removed_target_refs,
    };
    let changed_targets = diff.upserted_targets.len() + diff.removed_target_refs.len();
    let encoded_bytes = serde_json::to_vec(&diff)?.len();
    if changed_targets > max_changes || encoded_bytes > max_bytes {
        return Ok(DiffOutcome::Truncated(DiffTruncated {
            session_id: current.session_id.clone(),
            reason: if changed_targets > max_changes {
                "TARGET_LIMIT".to_owned()
            } else {
                "BYTE_LIMIT".to_owned()
            },
            last_good_state_version: previous.state_version,
            current_state_version: current.state_version,
            affected_root: "document".to_owned(),
            estimated_targets: current.targets.len(),
        }));
    }
    Ok(DiffOutcome::Diff(diff))
}

/// 浏览器状态采集器 trait。
#[async_trait]
pub trait BrowserStateCollector: Send + Sync {
    /// 采集当前状态。
    async fn collect_current_state(&self, session_id: &str) -> anyhow::Result<CurrentState>;

    /// 强制重建全量交互状态并递增 Target Revision。
    async fn resync_full(&self, session_id: &str) -> anyhow::Result<CurrentState>;

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
    #[serde(default)]
    truncated: bool,
}

#[derive(Debug, Serialize, Deserialize)]
struct EvaluatedTarget {
    path: String,
    role: String,
    name: Option<String>,
    bounds: Option<Bounds>,
    enabled: bool,
    visible: bool,
    #[serde(default)]
    sensitive: bool,
}

/// 基于真实 Chrome DevTools Protocol 的基础 State Collector。
///
/// 只连接 Browser Node 内部回环地址，不将 CDP 暴露给 Agent 或客户端。
#[derive(Clone, Default)]
pub struct CdpStateCollector {
    endpoints: Arc<RwLock<HashMap<String, String>>>,
    cursors: Arc<Mutex<HashMap<String, CollectorCursor>>>,
    target_registries: Arc<Mutex<HashMap<String, TargetRegistry>>>,
}

#[derive(Debug, Default)]
struct CollectorCursor {
    state_version: u64,
    target_revision: u64,
    url: String,
    target_fingerprint: String,
}

#[derive(Debug, Default)]
struct TargetRegistry {
    target_revision: u64,
    targets: HashMap<String, ResolvedTarget>,
}

#[derive(Debug, Clone)]
pub struct ResolvedTarget {
    pub role: String,
    pub bounds: Bounds,
    pub enabled: bool,
    pub visible: bool,
    pub sensitive: bool,
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
        self.target_registries.lock().await.remove(session_id);
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
                if (explicit) return explicit.slice(0, 64);
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
              const sensitiveFor = (element) => {
                const type = (element.getAttribute('type') || '').toLowerCase();
                const autocomplete = (element.getAttribute('autocomplete') || '').toLowerCase();
                const name = (element.getAttribute('name') || '').toLowerCase();
                return type === 'password'
                  || autocomplete.includes('one-time-code')
                  || /(^|[_-])(password|passwd|pwd|otp|one.?time.?code)($|[_-])/.test(name);
              };
              const pathFor = (element) => {
                const parts = [];
                let current = element;
                while (current && current.nodeType === Node.ELEMENT_NODE && parts.length < 24) {
                  const tag = current.tagName.toLowerCase();
                  let index = 1;
                  let sibling = current.previousElementSibling;
                  while (sibling) {
                    if (sibling.tagName === current.tagName) index += 1;
                    sibling = sibling.previousElementSibling;
                  }
                  parts.unshift(`${tag}:nth-of-type(${index})`);
                  current = current.parentElement;
                }
                return parts.join('>');
              };
              const candidates = Array.from(document.querySelectorAll(selector));
              const targets = candidates
                .slice(0, 40)
                .map((element) => {
                  const rect = element.getBoundingClientRect();
                  const style = window.getComputedStyle(element);
                  const visible = rect.width > 0 && rect.height > 0
                    && style.visibility !== 'hidden' && style.display !== 'none';
                  return {
                    path: pathFor(element),
                    role: roleFor(element),
                    name: nameFor(element),
                    bounds: visible ? {
                      x: rect.x, y: rect.y, width: rect.width, height: rect.height
                    } : null,
                    enabled: !element.disabled && element.getAttribute('aria-disabled') !== 'true',
                    visible,
                    sensitive: sensitiveFor(element)
                  };
                });
              return {
                url: location.href,
                title: document.title.slice(0, 1024),
                targets,
                truncated: candidates.length > 40
              };
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

    pub async fn resolve_target(
        &self,
        session_id: &str,
        target_ref: &str,
        target_revision: u64,
    ) -> anyhow::Result<ResolvedTarget> {
        let registries = self.target_registries.lock().await;
        let registry = registries
            .get(session_id)
            .ok_or_else(|| anyhow::anyhow!("target registry is unavailable"))?;
        anyhow::ensure!(
            registry.target_revision == target_revision,
            "target revision is stale"
        );
        let target = registry
            .targets
            .get(target_ref)
            .cloned()
            .ok_or_else(|| anyhow::anyhow!("target reference is stale or unknown"))?;
        anyhow::ensure!(target.visible, "target is not visible");
        anyhow::ensure!(target.enabled, "target is not enabled");
        anyhow::ensure!(
            target.bounds.width > 0.0 && target.bounds.height > 0.0,
            "target bounds are not actionable"
        );
        Ok(target)
    }

    pub async fn scroll(&self, session_id: &str, delta_y: i32) -> anyhow::Result<()> {
        anyhow::ensure!(
            (100..=2000).contains(&delta_y.abs()),
            "scroll delta must be between 100 and 2000 pixels"
        );
        let websocket_url = self.target_websocket(session_id).await?;
        let (mut socket, _) = timeout(
            Duration::from_secs(3),
            tokio_tungstenite::connect_async(websocket_url),
        )
        .await
        .map_err(|_| anyhow::anyhow!("CDP scroll connection timed out"))??;
        let expression =
            format!("window.scrollBy({{top:{delta_y},left:0,behavior:'instant'}}); true");
        socket
            .send(Message::Text(
                serde_json::json!({
                    "id": 3,
                    "method": "Runtime.evaluate",
                    "params": {"expression": expression, "returnByValue": true}
                })
                .to_string(),
            ))
            .await?;
        while let Some(message) = timeout(Duration::from_secs(3), socket.next())
            .await
            .map_err(|_| anyhow::anyhow!("CDP scroll command timed out"))?
        {
            let Message::Text(text) = message? else {
                continue;
            };
            let response: serde_json::Value = serde_json::from_str(&text)?;
            if response.get("id").and_then(serde_json::Value::as_i64) != Some(3) {
                continue;
            }
            anyhow::ensure!(response.get("error").is_none(), "CDP scroll command failed");
            return Ok(());
        }
        anyhow::bail!("CDP websocket closed before scroll acknowledgement")
    }

    async fn collect(
        &self,
        session_id: &str,
        force_target_revision: bool,
    ) -> anyhow::Result<CurrentState> {
        let websocket_url = self.target_websocket(session_id).await?;
        let page = self.evaluate_page(&websocket_url).await?;
        let serialized_targets = serde_json::to_string(&page.targets)?;
        let target_fingerprint = hex_sha256(serialized_targets.as_bytes());
        let (state_version, target_revision) = {
            let mut cursors = self.cursors.lock().await;
            let cursor = cursors.entry(session_id.to_owned()).or_default();
            cursor.state_version += 1;
            if cursor.target_revision == 0
                || cursor.url != page.url
                || cursor.target_fingerprint != target_fingerprint
                || force_target_revision
            {
                cursor.target_revision += 1;
            }
            cursor.url = page.url.clone();
            cursor.target_fingerprint = target_fingerprint;
            (cursor.state_version, cursor.target_revision)
        };
        let content_hash = hex_sha256(
            format!(
                "{}\n{}\n{}\n{}",
                page.url, page.title, serialized_targets, page.truncated
            )
            .as_bytes(),
        );
        let mut registry_targets = HashMap::new();
        let targets = page
            .targets
            .into_iter()
            .map(|target| {
                let target_ref = format!(
                    "target:{target_revision}:{}",
                    &hex_sha256(target.path.as_bytes())[..16]
                );
                if let Some(bounds) = target.bounds.clone() {
                    registry_targets.insert(
                        target_ref.clone(),
                        ResolvedTarget {
                            role: target.role.clone(),
                            bounds,
                            enabled: target.enabled,
                            visible: target.visible,
                            sensitive: target.sensitive,
                        },
                    );
                }
                InteractiveTarget {
                    target_ref,
                    role: target.role,
                    name: (!target.sensitive).then_some(target.name).flatten(),
                    bounds: target.bounds,
                    enabled: target.enabled,
                    visible: target.visible,
                    sensitive: target.sensitive,
                }
            })
            .collect::<Vec<_>>();
        self.target_registries.lock().await.insert(
            session_id.to_owned(),
            TargetRegistry {
                target_revision,
                targets: registry_targets,
            },
        );

        Ok(CurrentState {
            session_id: session_id.to_owned(),
            state_version,
            target_revision,
            url: page.url,
            title: page.title,
            targets,
            quality: if page.truncated {
                StateQuality::DepthLimited
            } else {
                StateQuality::Complete
            },
            content_hash,
        })
    }
}

#[async_trait]
impl BrowserStateCollector for CdpStateCollector {
    async fn collect_current_state(&self, session_id: &str) -> anyhow::Result<CurrentState> {
        self.collect(session_id, false).await
    }

    async fn resync_full(&self, session_id: &str) -> anyhow::Result<CurrentState> {
        self.collect(session_id, true).await
    }

    async fn resync_region(
        &self,
        session_id: &str,
        _root_ref: &str,
    ) -> anyhow::Result<CurrentState> {
        // 首版以全量重建作为安全 fallback；命令与结果显式保留 REGION 请求语义。
        self.collect(session_id, true).await
    }
}

fn hex_sha256(value: &[u8]) -> String {
    Sha256::digest(value)
        .iter()
        .map(|byte| format!("{byte:02x}"))
        .collect()
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
                                    "path": "html:nth-of-type(1)>body:nth-of-type(1)>button:nth-of-type(1)",
                                    "role": "button",
                                    "name": "提交",
                                    "bounds": {"x": 12.0, "y": 24.0, "width": 96.0, "height": 32.0},
                                    "enabled": true,
                                    "visible": true
                                }, {
                                    "path": "html:nth-of-type(1)>body:nth-of-type(1)>input:nth-of-type(1)",
                                    "role": "textbox",
                                    "name": "Password",
                                    "bounds": {"x": 12.0, "y": 64.0, "width": 196.0, "height": 32.0},
                                    "enabled": true,
                                    "visible": true,
                                    "sensitive": true
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
        assert_eq!(state.targets.len(), 2);
        assert_eq!(state.targets[0].role, "button");
        assert_eq!(state.targets[0].name.as_deref(), Some("提交"));
        assert!(state.targets[1].sensitive);
        assert_eq!(state.targets[1].name, None);
        let resolved = collector
            .resolve_target(
                "ses_state",
                &state.targets[1].target_ref,
                state.target_revision,
            )
            .await
            .unwrap();
        assert!(resolved.sensitive);
        assert_eq!(resolved.role, "textbox");
        assert!(collector
            .resolve_target(
                "ses_state",
                &state.targets[1].target_ref,
                state.target_revision + 1,
            )
            .await
            .is_err());
        assert!(matches!(state.quality, StateQuality::Complete));
        let repeated = collector.collect_current_state("ses_state").await.unwrap();
        assert_eq!(repeated.state_version, 2);
        assert_eq!(repeated.targets[0].target_ref, state.targets[0].target_ref);
        assert_eq!(repeated.target_revision, state.target_revision);
        assert_eq!(repeated.content_hash, state.content_hash);

        websocket_task.await.unwrap();
        http_task.await.unwrap();
    }

    #[test]
    fn creates_bounded_diff_and_reports_truncation() {
        let target = |target_ref: &str, name: &str| InteractiveTarget {
            target_ref: target_ref.to_owned(),
            role: "button".to_owned(),
            name: Some(name.to_owned()),
            bounds: None,
            enabled: true,
            visible: true,
            sensitive: false,
        };
        let previous = CurrentState {
            session_id: "ses_state".to_owned(),
            state_version: 1,
            target_revision: 1,
            url: "https://example.test".to_owned(),
            title: "Example".to_owned(),
            targets: vec![target("target:1:a", "A"), target("target:1:b", "B")],
            quality: StateQuality::Complete,
            content_hash: "old".to_owned(),
        };
        let current = CurrentState {
            state_version: 2,
            targets: vec![target("target:1:a", "Changed"), target("target:1:c", "C")],
            content_hash: "new".to_owned(),
            ..previous.clone()
        };

        let DiffOutcome::Diff(diff) = diff_states(&previous, &current, 16_384, 10).unwrap() else {
            panic!("expected bounded diff")
        };
        assert_eq!(diff.base_state_version, 1);
        assert_eq!(diff.upserted_targets.len(), 2);
        assert_eq!(diff.removed_target_refs, vec!["target:1:b"]);

        let DiffOutcome::Truncated(truncated) =
            diff_states(&previous, &current, 16_384, 1).unwrap()
        else {
            panic!("expected truncation")
        };
        assert_eq!(truncated.reason, "TARGET_LIMIT");
        assert_eq!(truncated.last_good_state_version, 1);
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
