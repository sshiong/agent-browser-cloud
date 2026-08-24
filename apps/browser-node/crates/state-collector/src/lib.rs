//! State Collector。
//!
//! 负责采集浏览器当前状态。

mod dialog_monitor;
mod safety_monitor;

use async_trait::async_trait;
pub use dialog_monitor::NativeDialog;
use futures_util::{SinkExt, StreamExt};
pub use safety_monitor::{BrowserDownload, BrowserSafetyObservation, BrowserTransactionPolicy};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::collections::{HashMap, HashSet};
use std::path::Path;
use std::sync::Arc;
use tokio::sync::{Mutex, RwLock};
use tokio::task::JoinHandle;
use tokio::time::{timeout, Duration};
use tokio_tungstenite::tungstenite::Message;

const NETWORK_READINESS_HASH_BUCKET_MILLIS: u64 = 1_000;
const MAX_NETWORK_QUIET_POLICY_MILLIS: u64 = 30_000;

/// 交互目标。
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct InteractiveTarget {
    /// 目标引用
    pub target_ref: String,
    /// 跨 State/Target Revision 稳定的结构化元素 ID；target_ref 仍保留版本 fencing。
    pub element_id: String,
    /// 角色
    pub role: String,
    /// 名称
    pub name: Option<String>,
    /// 非敏感控件的当前值；敏感控件始终为空。
    pub value: Option<String>,
    /// DOM 控件类型（例如 text、submit、select-one）。
    pub control_type: Option<String>,
    /// 边界
    pub bounds: Option<Bounds>,
    /// 是否启用
    pub enabled: bool,
    /// 是否可见
    pub visible: bool,
    /// 是否为 Password/OTP 等敏感输入目标
    pub sensitive: bool,
    /// 当前焦点、选择和值状态。
    pub focused: bool,
    pub checked: Option<bool>,
    pub selected: Option<bool>,
    /// 是否属于结构化可交互集合。
    pub interactive: bool,
    /// 主文档为 main；同源 iframe 使用稳定 frame path。
    pub frame_id: String,
    /// 可见性细分，供 Agent 避免点击离屏或被遮挡目标。
    pub in_viewport: bool,
    pub occluded: bool,
    pub visibility_reason: Option<String>,
}

/// 边界。
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct Bounds {
    pub x: f64,
    pub y: f64,
    pub width: f64,
    pub height: f64,
}

/// Browser-level Page Target exposed to the Agent as one stable tab.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct BrowserTab {
    pub tab_id: String,
    pub url: String,
    pub title: String,
    pub active: bool,
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
    /// Browser-level Page Targets. Exactly one entry is active.
    #[serde(default)]
    pub tabs: Vec<BrowserTab>,
    /// Active Page Target ID. Empty only for an older serialized state.
    #[serde(default)]
    pub active_tab_id: String,
    /// Browser-native JavaScript dialogs. DOM role=dialog remains in targets and is separate.
    #[serde(default)]
    pub native_dialogs: Vec<NativeDialog>,
    /// True only when every current Page Target is continuously observed or safely probed.
    #[serde(default)]
    pub native_dialog_evidence_fresh: bool,
    /// URL-free bounded download lifecycle from Chromium Browser events.
    #[serde(default)]
    pub downloads: Vec<BrowserDownload>,
    /// False after any Browser event observer gap; callers must not infer terminal state.
    #[serde(default)]
    pub download_evidence_fresh: bool,
    /// 交互目标列表
    pub targets: Vec<InteractiveTarget>,
    /// 状态质量
    pub quality: StateQuality,
    /// 内容哈希
    pub content_hash: String,
    /// 页面文档生命周期状态（loading / interactive / complete）。
    #[serde(default)]
    pub document_ready_state: String,
    /// 自最近 CDP Network 活动以来的安静时长；有在途请求或证据不新鲜时为 0。
    #[serde(default)]
    pub network_quiet_millis: u64,
    /// Network 观察在当前 Runtime 代内是否连续、可用于 Ready Gate。
    #[serde(default)]
    pub network_evidence_fresh: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct StateDiff {
    pub session_id: String,
    pub base_state_version: u64,
    pub state_version: u64,
    pub target_revision: u64,
    pub url: String,
    pub title: String,
    #[serde(default)]
    pub tabs: Vec<BrowserTab>,
    #[serde(default)]
    pub active_tab_id: String,
    #[serde(default)]
    pub native_dialogs: Vec<NativeDialog>,
    #[serde(default)]
    pub native_dialog_evidence_fresh: bool,
    #[serde(default)]
    pub downloads: Vec<BrowserDownload>,
    #[serde(default)]
    pub download_evidence_fresh: bool,
    pub quality: StateQuality,
    pub content_hash: String,
    #[serde(default)]
    pub document_ready_state: String,
    #[serde(default)]
    pub network_quiet_millis: u64,
    #[serde(default)]
    pub network_evidence_fresh: bool,
    pub upserted_targets: Vec<InteractiveTarget>,
    pub removed_target_refs: Vec<String>,
    #[serde(default)]
    pub snapshot_kind: String,
    #[serde(default)]
    pub requested_root_ref: String,
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
    Diff(Box<StateDiff>),
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
        tabs: current.tabs.clone(),
        active_tab_id: current.active_tab_id.clone(),
        native_dialogs: current.native_dialogs.clone(),
        native_dialog_evidence_fresh: current.native_dialog_evidence_fresh,
        downloads: current.downloads.clone(),
        download_evidence_fresh: current.download_evidence_fresh,
        quality: current.quality.clone(),
        content_hash: current.content_hash.clone(),
        document_ready_state: current.document_ready_state.clone(),
        network_quiet_millis: current.network_quiet_millis,
        network_evidence_fresh: current.network_evidence_fresh,
        upserted_targets,
        removed_target_refs,
        snapshot_kind: String::new(),
        requested_root_ref: String::new(),
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
    Ok(DiffOutcome::Diff(Box::new(diff)))
}

/// 浏览器状态采集器 trait。
#[async_trait]
pub trait BrowserStateCollector: Send + Sync {
    /// 采集当前状态。
    async fn collect_current_state(&self, session_id: &str) -> anyhow::Result<CurrentState>;

    /// 在已确认的 Agent 动作后采集一个提交屏障。
    ///
    /// 即使公开 State 内容没有变化，也必须推进 State Version，以证明动作后的采集
    /// 确实发生；Target Revision 仍只在页面身份或目标集合变化时推进。周期探测不得
    /// 使用该入口，避免无变化轮询制造幽灵版本。
    async fn collect_action_confirmation(&self, session_id: &str) -> anyhow::Result<CurrentState>;

    /// 强制重建全量交互状态并递增 Target Revision。
    async fn resync_full(&self, session_id: &str) -> anyhow::Result<CurrentState>;

    /// 重新同步区域。
    async fn resync_region(
        &self,
        session_id: &str,
        root_ref: &str,
        baseline: &CurrentState,
    ) -> anyhow::Result<CurrentState>;
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
struct CdpTarget {
    #[serde(default)]
    id: String,
    #[serde(rename = "type")]
    target_type: String,
    #[serde(default)]
    url: String,
    #[serde(default)]
    title: String,
    #[serde(default)]
    web_socket_debugger_url: Option<String>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct CdpVersion {
    web_socket_debugger_url: String,
}

#[derive(Debug, Clone)]
struct TabSnapshot {
    tabs: Vec<BrowserTab>,
    active_tab_id: String,
    active_websocket_url: String,
}

/// 从 Chromium CDP 权威接口读取的轻量 Session 资源指标。
///
/// `main_thread_task_duration_ms` 是当前所有 Page Target 的累计 TaskDuration。
/// 调用方必须对连续样本做差值，不能把累计值直接解释成单个窗口的阻塞时长。
#[derive(Debug, Clone, PartialEq)]
pub struct BrowserResourceMetrics {
    pub renderer_count: Option<u32>,
    pub tab_count: u32,
    pub main_thread_task_duration_ms: Option<f64>,
}

/// Browser Node 实际执行的标签页资源保护策略。
///
/// `block_new_tabs` 启用时以策略提交瞬间仍存在的 Page Target 为允许集合；之后出现的
/// Page Target 会由 Node 内部 CDP 监视器关闭。已有标签页不会因为策略切换被关闭。
/// 后台冻结使用五秒短 Lease 周期性解冻，避免用户切回标签页后被永久冻结。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TabResourcePolicy {
    pub tab_budget: u32,
    pub freeze_background_tabs: bool,
    pub block_new_tabs: bool,
    pub paused_extension_ids: Vec<String>,
}

#[derive(Debug, Clone)]
struct TabResourcePolicyState {
    policy: TabResourcePolicy,
    allowed_target_ids: HashSet<String>,
    frozen_targets: HashMap<String, std::time::Instant>,
    paused_extension_targets: HashMap<String, String>,
}

#[derive(Debug, Deserialize)]
struct EvaluatedPageState {
    url: String,
    title: String,
    #[serde(default, rename = "documentReadyState")]
    document_ready_state: String,
    #[serde(default)]
    targets: Vec<EvaluatedTarget>,
    #[serde(default)]
    truncated: bool,
    #[serde(default, rename = "rootPath")]
    root_path: Option<String>,
    #[serde(default)]
    error: Option<String>,
}

fn default_true() -> bool {
    true
}

#[derive(Debug, Clone, Serialize, Deserialize)]
struct EvaluatedTarget {
    path: String,
    role: String,
    name: Option<String>,
    #[serde(default)]
    value: Option<String>,
    #[serde(default, rename = "controlType")]
    control_type: Option<String>,
    bounds: Option<Bounds>,
    enabled: bool,
    visible: bool,
    #[serde(default)]
    sensitive: bool,
    #[serde(default)]
    focused: bool,
    #[serde(default)]
    checked: Option<bool>,
    #[serde(default)]
    selected: Option<bool>,
    #[serde(default)]
    interactive: bool,
    #[serde(default, rename = "frameId")]
    frame_id: String,
    #[serde(default = "default_true", rename = "inViewport")]
    in_viewport: bool,
    #[serde(default)]
    occluded: bool,
    #[serde(default, rename = "visibilityReason")]
    visibility_reason: Option<String>,
}

/// 基于真实 Chrome DevTools Protocol 的基础 State Collector。
///
/// 只连接 Browser Node 内部回环地址，不将 CDP 暴露给 Agent 或客户端。
#[derive(Clone, Default)]
pub struct CdpStateCollector {
    endpoints: Arc<RwLock<HashMap<String, String>>>,
    cursors: Arc<Mutex<HashMap<String, CollectorCursor>>>,
    target_registries: Arc<Mutex<HashMap<String, TargetRegistry>>>,
    collection_locks: Arc<Mutex<HashMap<String, Arc<Mutex<()>>>>>,
    resource_budget_percentages: Arc<RwLock<HashMap<String, u32>>>,
    tab_resource_policies: Arc<RwLock<HashMap<String, TabResourcePolicyState>>>,
    tab_policy_monitors: Arc<Mutex<HashMap<String, JoinHandle<()>>>>,
    safety_observations: Arc<RwLock<HashMap<String, BrowserSafetyObservation>>>,
    safety_monitors: Arc<Mutex<HashMap<String, JoinHandle<()>>>>,
    native_dialog_observations:
        Arc<RwLock<HashMap<String, dialog_monitor::NativeDialogObservation>>>,
    native_dialog_monitors: Arc<Mutex<HashMap<String, JoinHandle<()>>>>,
    last_states: Arc<RwLock<HashMap<String, CurrentState>>>,
}

#[derive(Debug, Default, Clone)]
struct CollectorCursor {
    state_version: u64,
    target_revision: u64,
    url: String,
    target_fingerprint: String,
    content_hash: String,
    active_tab_id: String,
}

#[derive(Debug, Default, Clone)]
struct TargetRegistry {
    target_revision: u64,
    targets: HashMap<String, RegisteredTarget>,
}

#[derive(Debug, Clone)]
struct RegisteredTarget {
    evaluated: EvaluatedTarget,
    interactive: InteractiveTarget,
    resolved: Option<ResolvedTarget>,
}

struct DownloadHashEvidence<'a> {
    downloads: &'a [BrowserDownload],
    fresh: bool,
}

#[derive(Debug, Clone)]
pub struct ResolvedTarget {
    pub element_id: String,
    pub role: String,
    pub bounds: Bounds,
    pub enabled: bool,
    pub visible: bool,
    pub sensitive: bool,
    pub in_viewport: bool,
    pub occluded: bool,
    pub checked: Option<bool>,
    pub control_type: Option<String>,
    pub path: String,
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
        self.resource_budget_percentages
            .write()
            .await
            .entry(session_id.to_owned())
            .or_insert(100);
        self.collection_locks
            .lock()
            .await
            .entry(session_id.to_owned())
            .or_insert_with(|| Arc::new(Mutex::new(())));
        self.native_dialog_observations
            .write()
            .await
            .insert(session_id.to_owned(), Default::default());
        Ok(())
    }

    /// Starts the continuous Page.javascriptDialogOpening/Closed observer after the Runtime
    /// endpoint has passed registration. Kept separate from register_runtime so registration
    /// remains a side-effect-free boundary for isolated collector tests and staged startup.
    pub async fn start_native_dialog_monitor(&self, session_id: &str) -> anyhow::Result<()> {
        let endpoint = self.endpoint(session_id).await?;
        if let Some(previous) = self.native_dialog_monitors.lock().await.remove(session_id) {
            previous.abort();
        }
        self.native_dialog_observations
            .write()
            .await
            .insert(session_id.to_owned(), Default::default());
        self.native_dialog_monitors.lock().await.insert(
            session_id.to_owned(),
            dialog_monitor::spawn(
                session_id.to_owned(),
                endpoint,
                Arc::clone(&self.native_dialog_observations),
            ),
        );
        Ok(())
    }

    pub async fn unregister_runtime(&self, session_id: &str) {
        self.endpoints.write().await.remove(session_id);
        if let Some(cursor) = self.cursors.lock().await.get_mut(session_id) {
            // State Version must remain monotonic across Browser generations. Clearing the page
            // identity forces the first snapshot from the next generation to rotate Target
            // Revision so no pre-crash target reference can become actionable again.
            cursor.url.clear();
            cursor.target_fingerprint.clear();
            cursor.active_tab_id.clear();
        }
        self.target_registries.lock().await.remove(session_id);
        self.collection_locks.lock().await.remove(session_id);
        self.resource_budget_percentages
            .write()
            .await
            .remove(session_id);
        self.tab_resource_policies.write().await.remove(session_id);
        if let Some(monitor) = self.tab_policy_monitors.lock().await.remove(session_id) {
            monitor.abort();
        }
        self.safety_observations.write().await.remove(session_id);
        if let Some(monitor) = self.safety_monitors.lock().await.remove(session_id) {
            monitor.abort();
        }
        self.native_dialog_observations
            .write()
            .await
            .remove(session_id);
        if let Some(monitor) = self.native_dialog_monitors.lock().await.remove(session_id) {
            monitor.abort();
        }
        self.last_states.write().await.remove(session_id);
    }

    /// 启动持续、只读的 CDP Browser/Network 活动观察器。
    ///
    /// 观察器在独立 Browser WebSocket 上运行，不阻塞 State/Resource 的周期采集。
    /// 一旦已经建立过的观察连接丢失，本代 Runtime 会保持 fail-closed，不会把重连后的
    /// 不完整请求集合误报为“无上传/下载”。
    pub async fn start_safety_monitor(
        &self,
        session_id: &str,
        transaction_policy: BrowserTransactionPolicy,
    ) -> anyhow::Result<()> {
        transaction_policy.validate()?;
        let endpoint = self.endpoint(session_id).await?;
        if let Some(previous) = self.safety_monitors.lock().await.remove(session_id) {
            previous.abort();
        }
        self.safety_observations
            .write()
            .await
            .insert(session_id.to_owned(), BrowserSafetyObservation::default());
        let monitor = safety_monitor::spawn(
            session_id.to_owned(),
            endpoint,
            transaction_policy,
            Arc::clone(&self.safety_observations),
        );
        self.safety_monitors
            .lock()
            .await
            .insert(session_id.to_owned(), monitor);
        Ok(())
    }

    /// 返回当前 Browser 活动观察。`fresh=false` 时调用方必须省略安全字段，使
    /// Control Plane 依靠 TTL 和缺失信号 fail-closed。
    pub async fn browser_safety_observation(&self, session_id: &str) -> BrowserSafetyObservation {
        self.safety_observations
            .read()
            .await
            .get(session_id)
            .cloned()
            .unwrap_or_default()
    }

    /// 在线调整单 Session 的 State Collector 工作预算，返回旧值。
    ///
    /// 预算会同时影响周期采集间隔、Diff 上限和轻量 CDP Page 指标目标数。
    pub async fn set_resource_budget(
        &self,
        session_id: &str,
        budget_percent: u32,
    ) -> anyhow::Result<u32> {
        anyhow::ensure!(
            (10..=100).contains(&budget_percent),
            "State Collector budget must be between 10 and 100 percent"
        );
        anyhow::ensure!(
            self.endpoints.read().await.contains_key(session_id),
            "runtime CDP endpoint is not registered"
        );
        Ok(self
            .resource_budget_percentages
            .write()
            .await
            .insert(session_id.to_owned(), budget_percent)
            .unwrap_or(100))
    }

    pub async fn resource_budget_percent(&self, session_id: &str) -> u32 {
        self.resource_budget_percentages
            .read()
            .await
            .get(session_id)
            .copied()
            .unwrap_or(100)
    }

    pub async fn collection_interval_probes(&self, session_id: &str) -> u64 {
        let budget = self.resource_budget_percent(session_id).await;
        u64::from(200_u32.div_ceil(budget)).max(2)
    }

    /// 在线执行后台标签冻结与新增标签阻断。
    ///
    /// 该方法只有在首次 CDP 执行成功后才返回；调用方可据此发送 Node ACK。持续监视器
    /// 只访问 Browser Node 回环 CDP，并在后续新 Page Target 出现时立即执行相同策略。
    pub async fn set_tab_resource_policy(
        &self,
        session_id: &str,
        policy: TabResourcePolicy,
    ) -> anyhow::Result<TabResourcePolicy> {
        anyhow::ensure!(policy.tab_budget > 0, "tab budget must be positive");
        let endpoint = self.endpoint(session_id).await?;
        let previous_state = self
            .tab_resource_policies
            .read()
            .await
            .get(session_id)
            .cloned();
        let previous = previous_state
            .as_ref()
            .map(|state| state.policy.clone())
            .unwrap_or(TabResourcePolicy {
                tab_budget: policy.tab_budget,
                freeze_background_tabs: false,
                block_new_tabs: false,
                paused_extension_ids: Vec::new(),
            });

        if let Some(monitor) = self.tab_policy_monitors.lock().await.remove(session_id) {
            monitor.abort();
        }
        if let Some(state) = previous_state.as_ref() {
            self.restore_frozen_tabs(&endpoint, state).await?;
            self.resume_paused_extensions(&endpoint, state).await?;
        }

        let targets = Self::list_targets(&endpoint).await?;
        let page_targets = targets
            .iter()
            .filter(|target| target.target_type == "page")
            .cloned()
            .collect::<Vec<_>>();
        anyhow::ensure!(
            !policy.block_new_tabs || !page_targets.is_empty(),
            "cannot block new tabs before the initial Page Target is available"
        );
        anyhow::ensure!(
            page_targets.iter().all(|target| !target.id.is_empty()),
            "CDP Page Target ID is unavailable"
        );
        let allowed_target_ids = page_targets
            .iter()
            .map(|target| target.id.clone())
            .collect::<HashSet<_>>();
        let mut frozen_targets = HashMap::new();
        if policy.freeze_background_tabs {
            for target in &page_targets {
                if self.freeze_target_if_background(target).await? {
                    frozen_targets.insert(target.id.clone(), std::time::Instant::now());
                }
            }
        }
        let mut paused_extension_targets = HashMap::new();
        for target in targets.iter().filter(|target| {
            Self::extension_id(target).is_some_and(|extension_id| {
                policy
                    .paused_extension_ids
                    .iter()
                    .any(|paused| paused == extension_id)
            })
        }) {
            self.pause_extension_target(target).await?;
            paused_extension_targets.insert(
                target.id.clone(),
                target
                    .web_socket_debugger_url
                    .clone()
                    .expect("pause_extension_target requires a websocket"),
            );
        }

        self.tab_resource_policies.write().await.insert(
            session_id.to_owned(),
            TabResourcePolicyState {
                policy: policy.clone(),
                allowed_target_ids,
                frozen_targets,
                paused_extension_targets,
            },
        );
        if policy.freeze_background_tabs
            || policy.block_new_tabs
            || !policy.paused_extension_ids.is_empty()
        {
            let collector = self.clone();
            let monitored_session_id = session_id.to_owned();
            let handle = tokio::spawn(async move {
                loop {
                    tokio::time::sleep(Duration::from_secs(1)).await;
                    if let Err(error) = collector
                        .enforce_tab_resource_policy_once(&monitored_session_id)
                        .await
                    {
                        tracing::warn!(
                            session_id = monitored_session_id,
                            error = %error,
                            "Tab resource policy enforcement failed"
                        );
                    }
                }
            });
            self.tab_policy_monitors
                .lock()
                .await
                .insert(session_id.to_owned(), handle);
        }
        Ok(previous)
    }

    pub async fn tab_resource_policy(&self, session_id: &str) -> Option<TabResourcePolicy> {
        self.tab_resource_policies
            .read()
            .await
            .get(session_id)
            .map(|state| state.policy.clone())
    }

    pub async fn bounded_diff_limits(
        &self,
        session_id: &str,
        configured_max_bytes: usize,
        configured_max_changes: usize,
    ) -> (usize, usize) {
        let budget = self.resource_budget_percent(session_id).await as usize;
        (
            (configured_max_bytes.saturating_mul(budget) / 100).max(1_024),
            (configured_max_changes.saturating_mul(budget) / 100).max(1),
        )
    }

    async fn tab_snapshot(&self, session_id: &str) -> anyhow::Result<TabSnapshot> {
        let endpoint = self.endpoint(session_id).await?;
        let page_targets = Self::list_targets(&endpoint)
            .await?
            .into_iter()
            .filter(|target| target.target_type == "page")
            .collect::<Vec<_>>();
        anyhow::ensure!(!page_targets.is_empty(), "CDP has no page target");
        anyhow::ensure!(
            page_targets.len() <= 100,
            "CDP page target count exceeds 100"
        );
        anyhow::ensure!(
            page_targets.iter().all(|target| {
                !target.id.is_empty() && target.web_socket_debugger_url.is_some()
            }),
            "CDP Page Target identity is incomplete"
        );

        let previous_active = self
            .cursors
            .lock()
            .await
            .get(session_id)
            .map(|cursor| cursor.active_tab_id.clone())
            .unwrap_or_default();
        let active_tab_id = if page_targets.len() == 1 {
            page_targets[0].id.clone()
        } else {
            let observations = futures_util::future::join_all(page_targets.iter().map(|target| {
                let target_id = target.id.clone();
                let websocket = target
                    .web_socket_debugger_url
                    .clone()
                    .expect("Page Target websocket was validated");
                async move {
                    let visibility = Self::page_visibility_state(&websocket).await;
                    (target_id, visibility)
                }
            }))
            .await;
            let visible = observations
                .iter()
                .filter(|(_, visibility)| visibility.as_deref() == Some("visible"))
                .map(|(target_id, _)| target_id.clone())
                .collect::<Vec<_>>();
            if visible.len() == 1 {
                visible[0].clone()
            } else if !previous_active.is_empty()
                && page_targets
                    .iter()
                    .any(|target| target.id == previous_active)
                && (visible.is_empty() || visible.iter().any(|id| id == &previous_active))
            {
                previous_active
            } else {
                anyhow::bail!("CDP active Page Target is ambiguous")
            }
        };
        let active_websocket_url = page_targets
            .iter()
            .find(|target| target.id == active_tab_id)
            .and_then(|target| target.web_socket_debugger_url.clone())
            .ok_or_else(|| anyhow::anyhow!("CDP active Page Target disappeared"))?;
        let tabs = page_targets
            .into_iter()
            .map(|target| BrowserTab {
                active: target.id == active_tab_id,
                tab_id: target.id,
                url: target.url,
                title: target.title,
            })
            .collect();
        Ok(TabSnapshot {
            tabs,
            active_tab_id,
            active_websocket_url,
        })
    }

    async fn page_visibility_state(websocket_url: &str) -> Option<String> {
        Self::cdp_command_with_params(
            websocket_url,
            "Runtime.evaluate",
            401,
            serde_json::json!({
                "expression": "document.visibilityState",
                "returnByValue": true,
                "awaitPromise": false
            }),
        )
        .await
        .ok()
        .and_then(|result| {
            result
                .pointer("/result/value")
                .and_then(serde_json::Value::as_str)
                .map(str::to_owned)
        })
    }

    pub async fn active_page_websocket(&self, session_id: &str) -> anyhow::Result<String> {
        Ok(self.tab_snapshot(session_id).await?.active_websocket_url)
    }

    async fn native_dialog_snapshot(
        &self,
        session_id: &str,
        tabs: &[BrowserTab],
    ) -> (Vec<NativeDialog>, bool) {
        let tab_ids = tabs
            .iter()
            .map(|tab| tab.tab_id.as_str())
            .collect::<HashSet<_>>();
        let guard = self.native_dialog_observations.read().await;
        let Some(observation) = guard.get(session_id) else {
            return (Vec::new(), false);
        };
        let mut dialogs = observation
            .dialogs
            .values()
            .filter(|dialog| tab_ids.contains(dialog.tab_id.as_str()))
            .cloned()
            .collect::<Vec<_>>();
        dialogs.sort_by(|left, right| left.tab_id.cmp(&right.tab_id));
        let fresh = !tab_ids.is_empty()
            && tab_ids
                .iter()
                .all(|tab_id| observation.fresh_tab_ids.contains(*tab_id));
        (dialogs, fresh)
    }

    pub async fn handle_native_dialog(
        &self,
        session_id: &str,
        dialog_id: &str,
        accept: bool,
        prompt_text: Option<&str>,
    ) -> anyhow::Result<()> {
        anyhow::ensure!(
            dialog_id.starts_with("dlg_")
                && dialog_id.len() == 24
                && dialog_id[4..]
                    .bytes()
                    .all(|value| value.is_ascii_hexdigit()),
            "Native Dialog ID is invalid"
        );
        let dialog = {
            let guard = self.native_dialog_observations.read().await;
            let observation = guard
                .get(session_id)
                .ok_or_else(|| anyhow::anyhow!("Native Dialog observation is unavailable"))?;
            let dialog = observation
                .dialogs
                .values()
                .find(|dialog| dialog.dialog_id == dialog_id)
                .cloned()
                .ok_or_else(|| anyhow::anyhow!("Native Dialog is stale or unknown"))?;
            anyhow::ensure!(
                observation.fresh_tab_ids.contains(&dialog.tab_id),
                "Native Dialog evidence is stale"
            );
            dialog
        };
        if let Some(value) = prompt_text {
            anyhow::ensure!(
                accept && dialog.dialog_type == "PROMPT" && !value.is_empty(),
                "Native Dialog prompt text is forbidden"
            );
            anyhow::ensure!(
                value.len() <= 2_000
                    && !value.chars().any(|character| {
                        character.is_control() && !matches!(character, '\n' | '\r' | '\t')
                    }),
                "Native Dialog prompt text is invalid"
            );
        }
        let endpoint = self.endpoint(session_id).await?;
        let websocket = Self::list_targets(&endpoint)
            .await?
            .into_iter()
            .find(|target| target.target_type == "page" && target.id == dialog.tab_id)
            .and_then(|target| target.web_socket_debugger_url)
            .ok_or_else(|| anyhow::anyhow!("Native Dialog Page Target disappeared"))?;
        let mut params = serde_json::json!({"accept": accept});
        if let Some(value) = prompt_text {
            params["promptText"] = serde_json::Value::String(value.to_owned());
        }
        Self::cdp_command_with_params(&websocket, "Page.handleJavaScriptDialog", 405, params)
            .await?;
        // The CDP acknowledgement is the write barrier. Update the shared observer immediately;
        // the independent Closed event/probe will converge to the same state.
        let mut guard = self.native_dialog_observations.write().await;
        let observation = guard.entry(session_id.to_owned()).or_default();
        observation.dialogs.remove(&dialog.tab_id);
        observation.fresh_tab_ids.insert(dialog.tab_id);
        Ok(())
    }

    pub async fn open_tab(&self, session_id: &str, url: &str) -> anyhow::Result<String> {
        let endpoint = self.endpoint(session_id).await?;
        let resource_policy = self
            .tab_resource_policies
            .read()
            .await
            .get(session_id)
            .map(|state| state.policy.clone());
        if let Some(policy) = resource_policy {
            anyhow::ensure!(
                !policy.block_new_tabs,
                "new Page Targets are blocked by the active resource policy"
            );
            let page_count = Self::list_targets(&endpoint)
                .await?
                .into_iter()
                .filter(|target| target.target_type == "page")
                .count();
            anyhow::ensure!(
                page_count < policy.tab_budget as usize,
                "Page Target budget is exhausted"
            );
        }
        let browser = Self::browser_websocket(&endpoint).await?;
        let result = Self::cdp_command_with_params(
            &browser,
            "Target.createTarget",
            402,
            serde_json::json!({"url": url, "newWindow": false, "background": false}),
        )
        .await?;
        let tab_id = result
            .get("targetId")
            .and_then(serde_json::Value::as_str)
            .filter(|value| !value.is_empty() && value.chars().count() <= 128)
            .ok_or_else(|| anyhow::anyhow!("CDP Target.createTarget returned no targetId"))?
            .to_owned();
        self.activate_tab(session_id, &tab_id).await?;
        Ok(tab_id)
    }

    pub async fn activate_tab(&self, session_id: &str, tab_id: &str) -> anyhow::Result<()> {
        Self::validate_tab_id(tab_id)?;
        let endpoint = self.endpoint(session_id).await?;
        let targets = Self::list_targets(&endpoint).await?;
        anyhow::ensure!(
            targets
                .iter()
                .any(|target| target.target_type == "page" && target.id == tab_id),
            "Page Target is stale or unknown"
        );
        let browser = Self::browser_websocket(&endpoint).await?;
        Self::cdp_command_with_params(
            &browser,
            "Target.activateTarget",
            403,
            serde_json::json!({"targetId": tab_id}),
        )
        .await?;
        let deadline = tokio::time::Instant::now() + Duration::from_secs(2);
        loop {
            let targets = Self::list_targets(&endpoint).await?;
            let active = targets
                .iter()
                .find(|target| target.target_type == "page" && target.id == tab_id)
                .and_then(|target| target.web_socket_debugger_url.as_deref());
            if let Some(websocket) = active {
                if Self::page_visibility_state(websocket).await.as_deref() == Some("visible") {
                    break;
                }
            } else {
                anyhow::bail!("activated Page Target disappeared");
            }
            anyhow::ensure!(
                tokio::time::Instant::now() < deadline,
                "Page Target activation did not become visible"
            );
            tokio::time::sleep(Duration::from_millis(25)).await;
        }
        if let Some(cursor) = self.cursors.lock().await.get_mut(session_id) {
            cursor.active_tab_id = tab_id.to_owned();
        }
        Ok(())
    }

    pub async fn close_tab(&self, session_id: &str, tab_id: &str) -> anyhow::Result<()> {
        Self::validate_tab_id(tab_id)?;
        let endpoint = self.endpoint(session_id).await?;
        let page_targets = Self::list_targets(&endpoint)
            .await?
            .into_iter()
            .filter(|target| target.target_type == "page")
            .collect::<Vec<_>>();
        anyhow::ensure!(page_targets.len() > 1, "cannot close the last Page Target");
        anyhow::ensure!(
            page_targets.iter().any(|target| target.id == tab_id),
            "Page Target is stale or unknown"
        );
        let fallback = page_targets
            .iter()
            .find(|target| target.id != tab_id)
            .map(|target| target.id.clone())
            .expect("more than one Page Target was validated");
        let browser = Self::browser_websocket(&endpoint).await?;
        let result = Self::cdp_command_with_params(
            &browser,
            "Target.closeTarget",
            404,
            serde_json::json!({"targetId": tab_id}),
        )
        .await?;
        anyhow::ensure!(
            result.get("success").and_then(serde_json::Value::as_bool) == Some(true),
            "CDP Target.closeTarget did not close the Page Target"
        );
        let deadline = tokio::time::Instant::now() + Duration::from_secs(2);
        loop {
            let still_present = Self::list_targets(&endpoint)
                .await?
                .iter()
                .any(|target| target.target_type == "page" && target.id == tab_id);
            if !still_present {
                break;
            }
            anyhow::ensure!(
                tokio::time::Instant::now() < deadline,
                "closed Page Target is still present"
            );
            tokio::time::sleep(Duration::from_millis(25)).await;
        }
        let was_active = self
            .cursors
            .lock()
            .await
            .get(session_id)
            .is_some_and(|cursor| cursor.active_tab_id == tab_id);
        if was_active {
            self.activate_tab(session_id, &fallback).await?;
        }
        Ok(())
    }

    fn validate_tab_id(tab_id: &str) -> anyhow::Result<()> {
        anyhow::ensure!(
            !tab_id.is_empty()
                && tab_id.chars().count() <= 128
                && !tab_id.chars().any(char::is_control),
            "Page Target ID is invalid"
        );
        Ok(())
    }

    async fn endpoint(&self, session_id: &str) -> anyhow::Result<String> {
        self.endpoints
            .read()
            .await
            .get(session_id)
            .cloned()
            .ok_or_else(|| anyhow::anyhow!("runtime CDP endpoint is not registered"))
    }

    fn http_client() -> anyhow::Result<reqwest::Client> {
        Ok(reqwest::Client::builder()
            .connect_timeout(Duration::from_secs(1))
            .timeout(Duration::from_secs(2))
            .no_proxy()
            .build()?)
    }

    async fn list_targets(endpoint: &str) -> anyhow::Result<Vec<CdpTarget>> {
        Ok(Self::http_client()?
            .get(format!("{endpoint}/json/list"))
            .send()
            .await?
            .error_for_status()?
            .json()
            .await?)
    }

    async fn browser_websocket(endpoint: &str) -> anyhow::Result<String> {
        let version: CdpVersion = Self::http_client()?
            .get(format!("{endpoint}/json/version"))
            .send()
            .await?
            .error_for_status()?
            .json()
            .await?;
        Ok(version.web_socket_debugger_url)
    }

    async fn cdp_command_with_params(
        websocket_url: &str,
        method: &str,
        id: i64,
        params: serde_json::Value,
    ) -> anyhow::Result<serde_json::Value> {
        Self::require_loopback_websocket(websocket_url)?;
        let (mut socket, _) = timeout(
            Duration::from_secs(2),
            tokio_tungstenite::connect_async(websocket_url),
        )
        .await
        .map_err(|_| anyhow::anyhow!("CDP resource websocket connection timed out"))??;
        socket
            .send(Message::Text(
                serde_json::json!({"id": id, "method": method, "params": params}).to_string(),
            ))
            .await?;
        while let Some(message) = timeout(Duration::from_secs(2), socket.next())
            .await
            .map_err(|_| anyhow::anyhow!("CDP {method} timed out"))?
        {
            let Message::Text(text) = message? else {
                continue;
            };
            let response: serde_json::Value = serde_json::from_str(&text)?;
            if response.get("id").and_then(serde_json::Value::as_i64) != Some(id) {
                continue;
            }
            if let Some(error) = response.get("error") {
                anyhow::bail!("CDP {method} failed: {error}");
            }
            return response
                .get("result")
                .cloned()
                .ok_or_else(|| anyhow::anyhow!("CDP {method} response has no result"));
        }
        anyhow::bail!("CDP websocket closed before {method} completed")
    }

    async fn cdp_command(
        websocket_url: &str,
        method: &str,
        id: i64,
    ) -> anyhow::Result<serde_json::Value> {
        Self::require_loopback_websocket(websocket_url)?;
        let (mut socket, _) = timeout(
            Duration::from_secs(2),
            tokio_tungstenite::connect_async(websocket_url),
        )
        .await
        .map_err(|_| anyhow::anyhow!("CDP resource websocket connection timed out"))??;
        socket
            .send(Message::Text(
                serde_json::json!({"id": id, "method": method}).to_string(),
            ))
            .await?;
        while let Some(message) = timeout(Duration::from_secs(2), socket.next())
            .await
            .map_err(|_| anyhow::anyhow!("CDP {method} timed out"))?
        {
            let Message::Text(text) = message? else {
                continue;
            };
            let response: serde_json::Value = serde_json::from_str(&text)?;
            if response.get("id").and_then(serde_json::Value::as_i64) != Some(id) {
                continue;
            }
            if let Some(error) = response.get("error") {
                anyhow::bail!("CDP {method} failed: {error}");
            }
            return response
                .get("result")
                .cloned()
                .ok_or_else(|| anyhow::anyhow!("CDP {method} response has no result"));
        }
        anyhow::bail!("CDP websocket closed before {method} completed")
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
        anyhow::ensure!(loopback, "CDP websocket must use the Browser Node loopback");
        Ok(())
    }

    async fn freeze_target_if_background(&self, target: &CdpTarget) -> anyhow::Result<bool> {
        let websocket = target
            .web_socket_debugger_url
            .as_deref()
            .ok_or_else(|| anyhow::anyhow!("CDP Page websocket is unavailable"))?;
        let visibility = Self::cdp_command_with_params(
            websocket,
            "Runtime.evaluate",
            501,
            serde_json::json!({
                "expression": "document.visibilityState",
                "returnByValue": true,
                "awaitPromise": false
            }),
        )
        .await?;
        if visibility
            .pointer("/result/value")
            .and_then(serde_json::Value::as_str)
            != Some("hidden")
        {
            return Ok(false);
        }
        Self::cdp_command_with_params(
            websocket,
            "Page.setWebLifecycleState",
            502,
            serde_json::json!({"state": "frozen"}),
        )
        .await?;
        Ok(true)
    }

    async fn restore_frozen_tabs(
        &self,
        endpoint: &str,
        state: &TabResourcePolicyState,
    ) -> anyhow::Result<()> {
        if state.frozen_targets.is_empty() {
            return Ok(());
        }
        let targets = Self::list_targets(endpoint).await?;
        for target in targets.into_iter().filter(|target| {
            target.target_type == "page" && state.frozen_targets.contains_key(&target.id)
        }) {
            let websocket = target
                .web_socket_debugger_url
                .as_deref()
                .ok_or_else(|| anyhow::anyhow!("CDP Page websocket is unavailable"))?;
            Self::cdp_command_with_params(
                websocket,
                "Page.setWebLifecycleState",
                503,
                serde_json::json!({"state": "active"}),
            )
            .await?;
        }
        Ok(())
    }

    fn extension_id(target: &CdpTarget) -> Option<&str> {
        if !matches!(
            target.target_type.as_str(),
            "background_page" | "service_worker" | "worker" | "shared_worker"
        ) {
            return None;
        }
        target
            .url
            .strip_prefix("chrome-extension://")
            .and_then(|value| value.split('/').next())
            .filter(|value| !value.is_empty())
    }

    async fn pause_extension_target(&self, target: &CdpTarget) -> anyhow::Result<()> {
        anyhow::ensure!(
            !target.id.is_empty(),
            "CDP Extension Target ID is unavailable"
        );
        let websocket = target
            .web_socket_debugger_url
            .as_deref()
            .ok_or_else(|| anyhow::anyhow!("CDP Extension websocket is unavailable"))?;
        Self::cdp_command(websocket, "Debugger.enable", 601).await?;
        Self::cdp_command(websocket, "Debugger.pause", 602).await?;
        Ok(())
    }

    async fn resume_paused_extensions(
        &self,
        endpoint: &str,
        state: &TabResourcePolicyState,
    ) -> anyhow::Result<()> {
        if state.paused_extension_targets.is_empty() {
            return Ok(());
        }
        let targets = Self::list_targets(endpoint).await?;
        for target in targets.into_iter().filter(|target| {
            state
                .paused_extension_targets
                .contains_key(target.id.as_str())
        }) {
            let websocket = target
                .web_socket_debugger_url
                .as_deref()
                .ok_or_else(|| anyhow::anyhow!("CDP Extension websocket is unavailable"))?;
            Self::cdp_command(websocket, "Debugger.resume", 603).await?;
            Self::cdp_command(websocket, "Debugger.disable", 604).await?;
        }
        Ok(())
    }

    async fn enforce_tab_resource_policy_once(&self, session_id: &str) -> anyhow::Result<()> {
        let Some(state) = self
            .tab_resource_policies
            .read()
            .await
            .get(session_id)
            .cloned()
        else {
            return Ok(());
        };
        let endpoint = self.endpoint(session_id).await?;
        let targets = Self::list_targets(&endpoint).await?;
        let mut paused_extension_targets = state.paused_extension_targets.clone();
        for target in targets.iter().filter(|target| {
            !state.paused_extension_targets.contains_key(&target.id)
                && Self::extension_id(target).is_some_and(|extension_id| {
                    state
                        .policy
                        .paused_extension_ids
                        .iter()
                        .any(|paused| paused == extension_id)
                })
        }) {
            self.pause_extension_target(target).await?;
            paused_extension_targets.insert(
                target.id.clone(),
                target
                    .web_socket_debugger_url
                    .clone()
                    .expect("pause_extension_target requires a websocket"),
            );
        }
        let page_targets = targets
            .into_iter()
            .filter(|target| target.target_type == "page")
            .collect::<Vec<_>>();
        let mut frozen_targets = state.frozen_targets.clone();
        let thaw_due = frozen_targets
            .iter()
            .filter_map(|(target_id, frozen_at)| {
                (frozen_at.elapsed() >= Duration::from_secs(5)).then_some(target_id.clone())
            })
            .collect::<HashSet<_>>();
        for target in page_targets
            .iter()
            .filter(|target| thaw_due.contains(&target.id))
        {
            let websocket = target
                .web_socket_debugger_url
                .as_deref()
                .ok_or_else(|| anyhow::anyhow!("CDP Page websocket is unavailable"))?;
            Self::cdp_command_with_params(
                websocket,
                "Page.setWebLifecycleState",
                505,
                serde_json::json!({"state": "active"}),
            )
            .await?;
            frozen_targets.remove(&target.id);
        }
        for target in page_targets {
            anyhow::ensure!(!target.id.is_empty(), "CDP Page Target ID is unavailable");
            if state.policy.block_new_tabs && !state.allowed_target_ids.contains(&target.id) {
                let browser_websocket = Self::browser_websocket(&endpoint).await?;
                let result = Self::cdp_command_with_params(
                    &browser_websocket,
                    "Target.closeTarget",
                    504,
                    serde_json::json!({"targetId": target.id}),
                )
                .await?;
                anyhow::ensure!(
                    result
                        .get("success")
                        .and_then(serde_json::Value::as_bool)
                        .unwrap_or(false),
                    "CDP refused to close a policy-blocked Page Target"
                );
                continue;
            }
            if state.policy.freeze_background_tabs
                && !thaw_due.contains(&target.id)
                && !frozen_targets.contains_key(&target.id)
                && self.freeze_target_if_background(&target).await?
            {
                frozen_targets.insert(target.id, std::time::Instant::now());
            }
        }
        if let Some(current) = self.tab_resource_policies.write().await.get_mut(session_id) {
            current.frozen_targets = frozen_targets;
            current.paused_extension_targets = paused_extension_targets;
        }
        Ok(())
    }

    /// 读取轻量 Browser/Page 指标。单项 CDP 能力不可用时只令该项为空；
    /// `/json/list` 不可用则整体失败，调用方仍应继续上报进程/Cgroup 指标。
    pub async fn collect_resource_metrics(
        &self,
        session_id: &str,
    ) -> anyhow::Result<BrowserResourceMetrics> {
        let endpoint = self.endpoint(session_id).await?;
        let targets = Self::list_targets(&endpoint).await?;
        let page_metric_target_budget =
            (32 * self.resource_budget_percent(session_id).await as usize / 100).max(1);
        let page_websockets = targets
            .iter()
            .filter(|target| target.target_type == "page")
            .filter_map(|target| target.web_socket_debugger_url.as_deref())
            .take(page_metric_target_budget)
            .collect::<Vec<_>>();
        let tab_count = targets
            .iter()
            .filter(|target| target.target_type == "page")
            .count()
            .try_into()
            .unwrap_or(u32::MAX);

        let renderer_count = match Self::browser_websocket(&endpoint).await {
            Ok(websocket) => Self::cdp_command(&websocket, "SystemInfo.getProcessInfo", 41)
                .await
                .ok()
                .and_then(|result| {
                    result
                        .get("processInfo")
                        .and_then(serde_json::Value::as_array)
                        .map(|processes| {
                            processes
                                .iter()
                                .filter(|process| {
                                    process.get("type").and_then(serde_json::Value::as_str)
                                        == Some("renderer")
                                })
                                .count()
                                .try_into()
                                .unwrap_or(u32::MAX)
                        })
                }),
            Err(_) => None,
        };

        let mut task_duration_ms = 0.0_f64;
        let mut measured_pages = 0_u32;
        for (index, websocket) in page_websockets.into_iter().enumerate() {
            let Ok(result) =
                Self::cdp_command(websocket, "Performance.getMetrics", 100 + index as i64).await
            else {
                continue;
            };
            let Some(metrics) = result.get("metrics").and_then(serde_json::Value::as_array) else {
                continue;
            };
            let Some(task_duration_seconds) = metrics.iter().find_map(|metric| {
                (metric.get("name").and_then(serde_json::Value::as_str) == Some("TaskDuration"))
                    .then(|| metric.get("value").and_then(serde_json::Value::as_f64))
                    .flatten()
            }) else {
                continue;
            };
            if task_duration_seconds.is_finite() && task_duration_seconds >= 0.0 {
                task_duration_ms += task_duration_seconds * 1000.0;
                measured_pages = measured_pages.saturating_add(1);
            }
        }

        Ok(BrowserResourceMetrics {
            renderer_count,
            tab_count,
            main_thread_task_duration_ms: (measured_pages > 0).then_some(task_duration_ms),
        })
    }

    async fn evaluate_page(&self, websocket_url: &str) -> anyhow::Result<EvaluatedPageState> {
        self.evaluate_state(websocket_url, None).await
    }

    async fn evaluate_region(
        &self,
        websocket_url: &str,
        root_selector: &str,
    ) -> anyhow::Result<EvaluatedPageState> {
        self.evaluate_state(websocket_url, Some(root_selector))
            .await
    }

    async fn evaluate_state(
        &self,
        websocket_url: &str,
        root_selector: Option<&str>,
    ) -> anyhow::Result<EvaluatedPageState> {
        let (mut socket, _) = timeout(
            Duration::from_secs(3),
            tokio_tungstenite::connect_async(websocket_url),
        )
        .await
        .map_err(|_| anyhow::anyhow!("CDP websocket connection timed out"))??;
        let requested_root = serde_json::to_string(&root_selector)?;
        let expression = r#"
            (() => {
              const requestedRoot = __REQUESTED_ROOT__;
              const selector = [
                'a[href]', 'button', 'input', 'select', 'textarea', 'summary',
                '[contenteditable]:not([contenteditable="false"])',
                '[role="button"]', '[role="link"]', '[role="checkbox"]',
                '[role="radio"]', '[role="textbox"]', '[role="combobox"]',
                '[role="switch"]', '[role="slider"]', '[role="option"]',
                '[role="menuitem"]', '[role="tab"]', '[role="alert"]',
                '[role="status"]', '[role="dialog"]', '[role="alertdialog"]',
                '[tabindex]'
              ].join(',');
              const accessibilityFor = (element) => {
                try {
                  const node = element.ownerDocument.defaultView
                    .getComputedAccessibleNode?.(element);
                  return node ? {role: node.role || null, name: node.name || null} : null;
                } catch (_) {
                  return null;
                }
              };
              const roleFor = (element) => {
                const accessibility = accessibilityFor(element);
                if (accessibility?.role) return String(accessibility.role).slice(0, 64);
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
                const accessibility = accessibilityFor(element);
                if (accessibility?.name) return String(accessibility.name).slice(0, 256);
                const aria = element.getAttribute('aria-label');
                if (aria) return aria.slice(0, 256);
                const labelledBy = element.getAttribute('aria-labelledby');
                if (labelledBy) {
                  const labelled = labelledBy.split(/\s+/)
                    .map((id) => element.ownerDocument.getElementById(id)?.textContent || '')
                    .join(' ').trim();
                  if (labelled) return labelled.slice(0, 256);
                }
                if (element.labels?.length) {
                  const labelled = Array.from(element.labels)
                    .map((label) => label.textContent || '').join(' ').trim();
                  if (labelled) return labelled.slice(0, 256);
                }
                const type = (element.getAttribute('type') || '').toLowerCase();
                if (type === 'password') return null;
                const text = element.innerText || element.getAttribute('alt')
                  || element.getAttribute('title') || element.getAttribute('placeholder') || '';
                return text.trim().slice(0, 256) || null;
              };
              const sensitiveFor = (element) => {
                const type = (element.getAttribute('type') || '').toLowerCase();
                const autocomplete = (element.getAttribute('autocomplete') || '').toLowerCase();
                const classification =
                  (element.getAttribute('data-classification') || '').toUpperCase();
                const identity = [
                  element.getAttribute('name'),
                  element.getAttribute('id'),
                  element.getAttribute('aria-label'),
                  element.getAttribute('placeholder')
                ].filter(Boolean).join(' ');
                const sensitiveIdentity =
                  /(^|[^a-z])(password|passwd|pwd|passcode|otp|one.?time.?code|pin|cvv|cvc|card.?number|account.?number|routing.?number|secret|token|api.?key|private.?key|ssn|social.?security)([^a-z]|$)/i;
                const sensitiveAutocomplete = new Set([
                  'current-password', 'new-password', 'one-time-code', 'cc-number', 'cc-csc',
                  'cc-exp', 'cc-exp-month', 'cc-exp-year', 'transaction-amount',
                  'transaction-currency'
                ]);
                return type === 'password'
                  || element.hasAttribute('data-sensitive')
                  || element.hasAttribute('data-private')
                  || element.hasAttribute('data-redact')
                  || classification === 'SENSITIVE'
                  || classification === 'HIGHLY_SENSITIVE'
                  || autocomplete.split(/\s+/).some((token) => sensitiveAutocomplete.has(token))
                  || sensitiveIdentity.test(identity);
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
                  const parent = current.parentElement;
                  if (parent) {
                    current = parent;
                  } else {
                    const root = current.getRootNode?.();
                    if (root instanceof ShadowRoot) {
                      parts.unshift('>>shadow>>');
                      current = root.host;
                    } else {
                      current = null;
                    }
                  }
                }
                return parts.join('>');
              };
              const visibilityFor = (element, rect, frameOffsetX, frameOffsetY) => {
                let current = element;
                let reason = null;
                while (current && current.nodeType === Node.ELEMENT_NODE) {
                  const style = current.ownerDocument.defaultView.getComputedStyle(current);
                  if (current.hidden) { reason = 'HIDDEN_ATTRIBUTE'; break; }
                  if (current.getAttribute('aria-hidden') === 'true') { reason = 'ARIA_HIDDEN'; break; }
                  if (style.display === 'none') { reason = 'DISPLAY_NONE'; break; }
                  if (style.visibility === 'hidden' || style.visibility === 'collapse') {
                    reason = 'VISIBILITY_HIDDEN'; break;
                  }
                  if (Number.parseFloat(style.opacity || '1') <= 0.01) {
                    reason = 'OPACITY_ZERO'; break;
                  }
                  if (style.pointerEvents === 'none') { reason = 'POINTER_EVENTS_NONE'; break; }
                  if (current.tagName === 'DETAILS' && !current.open
                      && element !== current.querySelector(':scope > summary')) {
                    reason = 'COLLAPSED'; break;
                  }
                  current = current.parentElement || current.getRootNode?.()?.host || null;
                }
                if (!reason && (rect.width <= 0 || rect.height <= 0)) reason = 'ZERO_SIZE';
                const global = {
                  x: rect.x + frameOffsetX, y: rect.y + frameOffsetY,
                  width: rect.width, height: rect.height
                };
                const inViewport = global.x + global.width > 0 && global.y + global.height > 0
                  && global.x < window.innerWidth && global.y < window.innerHeight;
                if (!reason && !inViewport) reason = 'OUTSIDE_VIEWPORT';
                let occluded = false;
                if (!reason) {
                  const x = Math.max(0, Math.min(rect.x + rect.width / 2,
                    element.ownerDocument.defaultView.innerWidth - 1));
                  const y = Math.max(0, Math.min(rect.y + rect.height / 2,
                    element.ownerDocument.defaultView.innerHeight - 1));
                  const hit = element.ownerDocument.elementFromPoint(x, y);
                  occluded = !!hit && hit !== element && !element.contains(hit) && !hit.contains(element);
                  if (occluded) reason = 'OCCLUDED';
                }
                return { visible: reason === null, reason, inViewport, occluded, global };
              };
              let root = document;
              if (requestedRoot !== null) {
                if (requestedRoot === 'document') {
                  root = document.documentElement;
                } else {
                  try {
                    root = document.querySelector(requestedRoot);
                  } catch (_) {
                    return {
                      url: location.href,
                      title: document.title.slice(0, 1024),
                      documentReadyState: document.readyState,
                      error: 'REGION_SELECTOR_INVALID'
                    };
                  }
                }
                if (!root) {
                  return {
                    url: location.href,
                    title: document.title.slice(0, 1024),
                    documentReadyState: document.readyState,
                    error: 'REGION_ROOT_NOT_FOUND'
                  };
                }
              }
              const candidates = [];
              const visited = new Set();
              const walk = (walkRoot, frameId = 'main', offsetX = 0, offsetY = 0, prefix = '') => {
                const elements = walkRoot === document
                  ? Array.from(document.querySelectorAll('*'))
                  : [
                      ...(walkRoot.matches?.('*') ? [walkRoot] : []),
                      ...Array.from(walkRoot.querySelectorAll?.('*') || [])
                    ];
                for (const element of elements) {
                  const path = `${prefix}${pathFor(element)}`;
                  if (element.matches?.(selector) && !visited.has(`${frameId}:${path}`)) {
                    visited.add(`${frameId}:${path}`);
                    candidates.push({ element, path, frameId, offsetX, offsetY });
                  }
                  if (element.shadowRoot) {
                    walk(element.shadowRoot, frameId, offsetX, offsetY, `${path}>>>`);
                  }
                  if (element.tagName === 'IFRAME') {
                    try {
                      const frameDocument = element.contentDocument;
                      if (frameDocument?.documentElement) {
                        const frameRect = element.getBoundingClientRect();
                        walk(frameDocument, `frame:${path}`, offsetX + frameRect.x,
                          offsetY + frameRect.y, `${path}::frame::`);
                      }
                    } catch (_) { /* cross-origin frames remain explicit Browser State boundaries */ }
                  }
                }
              };
              walk(root);
              const targets = candidates
                .slice(0, 200)
                .map(({ element, path, frameId, offsetX, offsetY }) => {
                  const rect = element.getBoundingClientRect();
                  const visibility = visibilityFor(element, rect, offsetX, offsetY);
                  const sensitive = sensitiveFor(element);
                  const rawValue = !sensitive && 'value' in element
                    ? String(element.value ?? '').slice(0, 512) : null;
                  return {
                    path,
                    role: roleFor(element),
                    name: sensitive ? null : nameFor(element),
                    value: rawValue || null,
                    controlType: (element.getAttribute('type') || element.type || '').slice(0, 64) || null,
                    bounds: rect.width > 0 && rect.height > 0 ? visibility.global : null,
                    enabled: !element.disabled && element.getAttribute('aria-disabled') !== 'true',
                    visible: visibility.visible,
                    sensitive,
                    focused: element.ownerDocument.activeElement === element,
                    checked: 'checked' in element ? !!element.checked : null,
                    selected: 'selected' in element ? !!element.selected : null,
                    interactive: element.matches(selector),
                    frameId,
                    inViewport: visibility.inViewport,
                    occluded: visibility.occluded,
                    visibilityReason: visibility.reason
                  };
                });
              return {
                url: location.href,
                title: document.title.slice(0, 1024),
                documentReadyState: document.readyState,
                targets,
                truncated: candidates.length > 200,
                rootPath: requestedRoot === null ? null : pathFor(root)
              };
            })()
        "#
        .replace("__REQUESTED_ROOT__", &requested_root);
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
            let evaluated: EvaluatedPageState = serde_json::from_value(value)?;
            if let Some(error) = evaluated.error.as_deref() {
                anyhow::bail!(error.to_owned());
            }
            return Ok(evaluated);
        }
        anyhow::bail!("CDP websocket closed before Runtime.evaluate completed")
    }

    pub async fn navigate(&self, session_id: &str, url: &str) -> anyhow::Result<()> {
        anyhow::ensure!(
            url.starts_with("http://") || url.starts_with("https://") || url == "about:blank",
            "navigation URL scheme is not allowed"
        );
        let websocket_url = self.active_page_websocket(session_id).await?;
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

    pub async fn reload(&self, session_id: &str, ignore_cache: bool) -> anyhow::Result<()> {
        let websocket_url = self.active_page_websocket(session_id).await?;
        let (mut socket, _) = timeout(
            Duration::from_secs(3),
            tokio_tungstenite::connect_async(websocket_url),
        )
        .await
        .map_err(|_| anyhow::anyhow!("CDP websocket connection timed out"))??;
        socket
            .send(Message::Text(
                serde_json::json!({
                    "id": 3,
                    "method": "Page.reload",
                    "params": {"ignoreCache": ignore_cache}
                })
                .to_string(),
            ))
            .await?;
        while let Some(message) = timeout(Duration::from_secs(5), socket.next())
            .await
            .map_err(|_| anyhow::anyhow!("CDP Page.reload timed out"))?
        {
            let message = message?;
            let Message::Text(text) = message else {
                continue;
            };
            let response: serde_json::Value = serde_json::from_str(&text)?;
            if response.get("id").and_then(serde_json::Value::as_i64) != Some(3) {
                continue;
            }
            if let Some(error) = response.get("error") {
                anyhow::bail!("CDP Page.reload failed: {error}");
            }
            return Ok(());
        }
        anyhow::bail!("CDP websocket closed before Page.reload completed")
    }

    /// Restarts exactly one contract-bound Chromium extension from its own trusted CDP context.
    ///
    /// The expression is constant and the Extension ID is used only to select a
    /// `chrome-extension://<id>/` target. Arbitrary JavaScript, arbitrary target URLs and
    /// cross-extension execution are intentionally unsupported.
    pub async fn restart_extension(
        &self,
        session_id: &str,
        extension_id: &str,
    ) -> anyhow::Result<()> {
        anyhow::ensure!(
            extension_id.len() == 32
                && extension_id
                    .bytes()
                    .all(|character| (b'a'..=b'p').contains(&character)),
            "Chromium Extension ID is invalid"
        );
        let endpoint = self.endpoint(session_id).await?;
        let target_prefix = format!("chrome-extension://{extension_id}/");
        let websocket_url = Self::list_targets(&endpoint)
            .await?
            .into_iter()
            .filter(|target| target.url.starts_with(&target_prefix))
            .filter(|target| {
                matches!(
                    target.target_type.as_str(),
                    "service_worker" | "background_page" | "page"
                )
            })
            .filter_map(|target| {
                let priority = match target.target_type.as_str() {
                    "service_worker" => 0_u8,
                    "background_page" => 1,
                    _ => 2,
                };
                target
                    .web_socket_debugger_url
                    .map(|websocket| (priority, websocket))
            })
            .min_by_key(|(priority, _)| *priority)
            .map(|(_, websocket)| websocket)
            .ok_or_else(|| anyhow::anyhow!("trusted Extension CDP target is unavailable"))?;
        let (mut socket, _) = timeout(
            Duration::from_secs(3),
            tokio_tungstenite::connect_async(websocket_url),
        )
        .await
        .map_err(|_| anyhow::anyhow!("Extension CDP websocket connection timed out"))??;
        socket
            .send(Message::Text(
                serde_json::json!({
                    "id": 4,
                    "method": "Runtime.evaluate",
                    "params": {
                        "expression": "setTimeout(() => chrome.runtime.reload(), 0); true",
                        "returnByValue": true,
                        "awaitPromise": false
                    }
                })
                .to_string(),
            ))
            .await?;
        while let Some(message) = timeout(Duration::from_secs(5), socket.next())
            .await
            .map_err(|_| anyhow::anyhow!("Extension Runtime.evaluate timed out"))?
        {
            let message = message?;
            let Message::Text(text) = message else {
                continue;
            };
            let response: serde_json::Value = serde_json::from_str(&text)?;
            if response.get("id").and_then(serde_json::Value::as_i64) != Some(4) {
                continue;
            }
            if let Some(error) = response.get("error") {
                anyhow::bail!("Extension Runtime.evaluate failed: {error}");
            }
            if let Some(exception) = response.pointer("/result/exceptionDetails") {
                anyhow::bail!("Extension reload was rejected: {exception}");
            }
            let accepted = response
                .pointer("/result/result/value")
                .and_then(serde_json::Value::as_bool)
                .unwrap_or(false);
            anyhow::ensure!(accepted, "Extension reload was not accepted");
            return Ok(());
        }
        anyhow::bail!("Extension CDP websocket closed before reload was accepted")
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
            .or_else(|| {
                registry
                    .targets
                    .values()
                    .find(|target| target.interactive.element_id == target_ref)
            })
            .and_then(|target| target.resolved.clone())
            .ok_or_else(|| {
                anyhow::anyhow!("target reference is stale, unknown, or not actionable")
            })?;
        anyhow::ensure!(target.visible, "target is not visible");
        anyhow::ensure!(target.enabled, "target is not enabled");
        anyhow::ensure!(target.in_viewport, "target is outside viewport");
        anyhow::ensure!(!target.occluded, "target is occluded");
        anyhow::ensure!(
            target.bounds.width > 0.0 && target.bounds.height > 0.0,
            "target bounds are not actionable"
        );
        Ok(target)
    }

    /// Sets a Session-staged file on the exact structured `<input type=file>` target through CDP.
    /// This never opens or interacts with an OS file chooser.
    pub async fn set_file_input_files(
        &self,
        session_id: &str,
        target_ref: &str,
        target_revision: u64,
        staged_path: &Path,
    ) -> anyhow::Result<()> {
        anyhow::ensure!(
            staged_path.is_absolute(),
            "staged upload path must be absolute"
        );
        let metadata = tokio::fs::metadata(staged_path).await?;
        anyhow::ensure!(metadata.is_file(), "staged upload is not a regular file");
        let path = {
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
                .or_else(|| {
                    registry
                        .targets
                        .values()
                        .find(|target| target.interactive.element_id == target_ref)
                })
                .ok_or_else(|| anyhow::anyhow!("file input target is stale or unknown"))?;
            anyhow::ensure!(target.evaluated.enabled, "file input target is not enabled");
            anyhow::ensure!(
                target.evaluated.control_type.as_deref() == Some("file"),
                "target is not a file input"
            );
            target.evaluated.path.clone()
        };
        let websocket_url = self.active_page_websocket(session_id).await?;
        let (mut socket, _) = timeout(
            Duration::from_secs(3),
            tokio_tungstenite::connect_async(websocket_url),
        )
        .await
        .map_err(|_| anyhow::anyhow!("CDP file upload connection timed out"))??;
        let requested_path = serde_json::to_string(&path)?;
        let expression = r#"
          (() => {
            const expected = __EXPECTED_PATH__;
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
                const parent = current.parentElement;
                if (parent) current = parent;
                else {
                  const root = current.getRootNode?.();
                  if (root instanceof ShadowRoot) {
                    parts.unshift('>>shadow>>');
                    current = root.host;
                  } else current = null;
                }
              }
              return parts.join('>');
            };
            let found = null;
            const walk = (walkRoot, prefix = '') => {
              const elements = walkRoot === document
                ? Array.from(document.querySelectorAll('*'))
                : Array.from(walkRoot.querySelectorAll?.('*') || []);
              for (const element of elements) {
                const candidate = `${prefix}${pathFor(element)}`;
                if (candidate === expected) { found = element; return true; }
                if (element.shadowRoot && walk(element.shadowRoot, `${candidate}>>>`)) return true;
                if (element.tagName === 'IFRAME') {
                  try {
                    if (element.contentDocument?.documentElement
                        && walk(element.contentDocument, `${candidate}::frame::`)) return true;
                  } catch (_) { /* cross-origin frames are not structured targets */ }
                }
              }
              return false;
            };
            walk(document);
            return found;
          })()
        "#
        .replace("__EXPECTED_PATH__", &requested_path);
        socket
            .send(Message::Text(
                serde_json::json!({
                    "id": 501,
                    "method": "Runtime.evaluate",
                    "params": {
                        "expression": expression,
                        "returnByValue": false,
                        "awaitPromise": false,
                        "userGesture": true
                    }
                })
                .to_string(),
            ))
            .await?;
        let evaluated = next_cdp_response(&mut socket, 501, "file input resolve").await?;
        let object_id = evaluated
            .pointer("/result/result/objectId")
            .and_then(serde_json::Value::as_str)
            .filter(|value| !value.is_empty())
            .ok_or_else(|| anyhow::anyhow!("file input target disappeared"))?;
        socket
            .send(Message::Text(
                serde_json::json!({
                    "id": 502,
                    "method": "DOM.describeNode",
                    "params": {"objectId": object_id}
                })
                .to_string(),
            ))
            .await?;
        let described = next_cdp_response(&mut socket, 502, "file input describe").await?;
        let backend_node_id = described
            .pointer("/result/node/backendNodeId")
            .and_then(serde_json::Value::as_u64)
            .filter(|value| *value > 0)
            .ok_or_else(|| anyhow::anyhow!("file input backend node is unavailable"))?;
        socket
            .send(Message::Text(
                serde_json::json!({
                    "id": 503,
                    "method": "DOM.setFileInputFiles",
                    "params": {
                        "files": [staged_path.to_string_lossy()],
                        "backendNodeId": backend_node_id
                    }
                })
                .to_string(),
            ))
            .await?;
        next_cdp_response(&mut socket, 503, "set file input files").await?;
        Ok(())
    }

    /// Returns the current CSS viewport used by CDP input coordinates.
    pub async fn viewport_size(&self, session_id: &str) -> anyhow::Result<(f64, f64)> {
        let websocket_url = self.active_page_websocket(session_id).await?;
        let (mut socket, _) = timeout(
            Duration::from_secs(3),
            tokio_tungstenite::connect_async(websocket_url),
        )
        .await
        .map_err(|_| anyhow::anyhow!("CDP viewport connection timed out"))??;
        socket
            .send(Message::Text(
                serde_json::json!({
                    "id": 8,
                    "method": "Runtime.evaluate",
                    "params": {
                        "expression": "({width:window.innerWidth,height:window.innerHeight})",
                        "returnByValue": true,
                        "awaitPromise": false,
                        "userGesture": false
                    }
                })
                .to_string(),
            ))
            .await?;
        while let Some(message) = timeout(Duration::from_secs(3), socket.next())
            .await
            .map_err(|_| anyhow::anyhow!("CDP viewport command timed out"))?
        {
            let Message::Text(text) = message? else {
                continue;
            };
            let response: serde_json::Value = serde_json::from_str(&text)?;
            if response.get("id").and_then(serde_json::Value::as_i64) != Some(8) {
                continue;
            }
            if let Some(error) = response.get("error") {
                anyhow::bail!("CDP viewport command failed: {error}");
            }
            let width = response
                .pointer("/result/result/value/width")
                .and_then(serde_json::Value::as_f64)
                .ok_or_else(|| anyhow::anyhow!("CDP viewport width is unavailable"))?;
            let height = response
                .pointer("/result/result/value/height")
                .and_then(serde_json::Value::as_f64)
                .ok_or_else(|| anyhow::anyhow!("CDP viewport height is unavailable"))?;
            anyhow::ensure!(
                width.is_finite() && height.is_finite() && width > 0.0 && height > 0.0,
                "CDP viewport dimensions are invalid"
            );
            return Ok((width, height));
        }
        anyhow::bail!("CDP websocket closed before viewport response")
    }

    pub async fn scroll(&self, session_id: &str, delta_y: i32) -> anyhow::Result<()> {
        anyhow::ensure!(
            (100..=2000).contains(&delta_y.abs()),
            "scroll delta must be between 100 and 2000 pixels"
        );
        let websocket_url = self.active_page_websocket(session_id).await?;
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

    async fn collection_lock(&self, session_id: &str) -> anyhow::Result<Arc<Mutex<()>>> {
        self.collection_locks
            .lock()
            .await
            .get(session_id)
            .cloned()
            .ok_or_else(|| anyhow::anyhow!("state collection lock is unavailable"))
    }

    fn target_ref(target_revision: u64, path: &str) -> String {
        format!(
            "target:{target_revision}:{}",
            &hex_sha256(path.as_bytes())[..16]
        )
    }

    fn element_id(path: &str) -> String {
        format!("e{}", &hex_sha256(path.as_bytes())[..12])
    }

    fn registered_target(target_revision: u64, evaluated: EvaluatedTarget) -> RegisteredTarget {
        let target_ref = Self::target_ref(target_revision, &evaluated.path);
        let resolved = evaluated.bounds.clone().map(|bounds| ResolvedTarget {
            element_id: Self::element_id(&evaluated.path),
            role: evaluated.role.clone(),
            bounds,
            enabled: evaluated.enabled,
            visible: evaluated.visible,
            sensitive: evaluated.sensitive,
            in_viewport: evaluated.in_viewport,
            occluded: evaluated.occluded,
            checked: evaluated.checked,
            control_type: evaluated.control_type.clone(),
            path: evaluated.path.clone(),
        });
        let interactive = InteractiveTarget {
            target_ref,
            element_id: Self::element_id(&evaluated.path),
            role: evaluated.role.clone(),
            name: (!evaluated.sensitive)
                .then_some(evaluated.name.clone())
                .flatten(),
            value: (!evaluated.sensitive)
                .then_some(evaluated.value.clone())
                .flatten(),
            control_type: evaluated.control_type.clone(),
            bounds: evaluated.bounds.clone(),
            enabled: evaluated.enabled,
            visible: evaluated.visible,
            sensitive: evaluated.sensitive,
            focused: evaluated.focused,
            checked: evaluated.checked,
            selected: evaluated.selected,
            interactive: evaluated.interactive,
            frame_id: if evaluated.frame_id.is_empty() {
                "main".to_owned()
            } else {
                evaluated.frame_id.clone()
            },
            in_viewport: evaluated.in_viewport,
            occluded: evaluated.occluded,
            visibility_reason: evaluated.visibility_reason.clone(),
        };
        RegisteredTarget {
            evaluated,
            interactive,
            resolved,
        }
    }

    fn canonical_targets(
        registry: &TargetRegistry,
    ) -> Vec<(String, EvaluatedTarget, InteractiveTarget)> {
        let mut targets = registry
            .targets
            .iter()
            .map(|(target_ref, registered)| {
                (
                    target_ref.clone(),
                    registered.evaluated.clone(),
                    registered.interactive.clone(),
                )
            })
            .collect::<Vec<_>>();
        targets.sort_by(|left, right| left.1.path.cmp(&right.1.path));
        targets
    }

    fn state_hash(
        page: &EvaluatedPageState,
        tab_snapshot: &TabSnapshot,
        native_dialogs: &[NativeDialog],
        native_dialog_evidence_fresh: bool,
        download_evidence: DownloadHashEvidence<'_>,
        truncated: bool,
        network_readiness_hash_bucket: u64,
    ) -> anyhow::Result<(String, String)> {
        let serialized_targets = serde_json::to_string(&page.targets)?;
        let content_hash = hex_sha256(
            format!(
                "{}\n{}\n{}\n{}\n{}\n{}\n{}\n{}\n{}\n{}\n{}\n{}",
                page.url,
                page.title,
                serde_json::to_string(&tab_snapshot.tabs)?,
                tab_snapshot.active_tab_id,
                serde_json::to_string(native_dialogs)?,
                native_dialog_evidence_fresh,
                serde_json::to_string(download_evidence.downloads)?,
                download_evidence.fresh,
                serialized_targets,
                truncated,
                page.document_ready_state,
                network_readiness_hash_bucket
            )
            .as_bytes(),
        );
        Ok((serialized_targets, content_hash))
    }

    async fn collect_dialog_blocked_state(
        &self,
        session_id: &str,
        tab_snapshot: TabSnapshot,
        native_dialogs: Vec<NativeDialog>,
        native_dialog_evidence_fresh: bool,
        force_state_version: bool,
        force_target_revision: bool,
    ) -> anyhow::Result<CurrentState> {
        let previous = self.last_states.read().await.get(session_id).cloned();
        let network_observation = self.browser_safety_observation(session_id).await;
        let network_quiet_millis = network_observation.network_quiet_millis();
        let active_tab = tab_snapshot
            .tabs
            .iter()
            .find(|tab| tab.tab_id == tab_snapshot.active_tab_id)
            .ok_or_else(|| anyhow::anyhow!("active Page Target disappeared"))?;
        let url = previous
            .as_ref()
            .filter(|state| state.active_tab_id == tab_snapshot.active_tab_id)
            .map(|state| state.url.clone())
            .unwrap_or_else(|| active_tab.url.clone());
        let title = previous
            .as_ref()
            .filter(|state| state.active_tab_id == tab_snapshot.active_tab_id)
            .map(|state| state.title.clone())
            .unwrap_or_else(|| active_tab.title.clone());
        let targets = previous
            .as_ref()
            .filter(|state| state.active_tab_id == tab_snapshot.active_tab_id)
            .map(|state| state.targets.clone())
            .unwrap_or_default();
        let quality = previous
            .as_ref()
            .filter(|state| state.active_tab_id == tab_snapshot.active_tab_id)
            .map(|state| state.quality.clone())
            .unwrap_or(StateQuality::Degraded);
        let document_ready_state = previous
            .as_ref()
            .filter(|state| state.active_tab_id == tab_snapshot.active_tab_id)
            .map(|state| state.document_ready_state.clone())
            .unwrap_or_default();
        let fallback_target_fingerprint = hex_sha256(serde_json::to_string(&targets)?.as_bytes());
        let content_hash = hex_sha256(
            format!(
                "native-dialog-blocked-v2\n{url}\n{title}\n{}\n{}\n{}\n{}\n{}\n{}\n{fallback_target_fingerprint}",
                serde_json::to_string(&tab_snapshot.tabs)?,
                tab_snapshot.active_tab_id,
                serde_json::to_string(&native_dialogs)?, native_dialog_evidence_fresh,
                serde_json::to_string(&network_observation.downloads)?,
                network_observation.fresh,
            )
            .as_bytes(),
        );
        let (state_version, target_revision) = {
            let mut cursors = self.cursors.lock().await;
            let cursor = cursors.entry(session_id.to_owned()).or_default();
            if cursor.state_version == 0
                || cursor.content_hash != content_hash
                || force_state_version
                || force_target_revision
            {
                cursor.state_version = cursor
                    .state_version
                    .checked_add(1)
                    .ok_or_else(|| anyhow::anyhow!("State version overflow"))?;
            }
            if cursor.target_revision == 0
                || cursor.active_tab_id != tab_snapshot.active_tab_id
                || force_target_revision
            {
                cursor.target_revision = cursor
                    .target_revision
                    .checked_add(1)
                    .ok_or_else(|| anyhow::anyhow!("Target revision overflow"))?;
            }
            cursor.url = url.clone();
            cursor.active_tab_id = tab_snapshot.active_tab_id.clone();
            if cursor.target_fingerprint.is_empty() {
                cursor.target_fingerprint = fallback_target_fingerprint;
            }
            cursor.content_hash = content_hash.clone();
            (cursor.state_version, cursor.target_revision)
        };
        let state = CurrentState {
            session_id: session_id.to_owned(),
            state_version,
            target_revision,
            url,
            title,
            tabs: tab_snapshot.tabs,
            active_tab_id: tab_snapshot.active_tab_id,
            native_dialogs,
            native_dialog_evidence_fresh,
            downloads: network_observation.downloads,
            download_evidence_fresh: network_observation.fresh,
            targets,
            quality,
            content_hash,
            document_ready_state,
            network_quiet_millis,
            network_evidence_fresh: network_observation.fresh,
        };
        self.last_states
            .write()
            .await
            .insert(session_id.to_owned(), state.clone());
        Ok(state)
    }

    async fn collect(
        &self,
        session_id: &str,
        force_state_version: bool,
        force_target_revision: bool,
    ) -> anyhow::Result<CurrentState> {
        let collection_lock = self.collection_lock(session_id).await?;
        let _collection_guard = collection_lock.lock().await;
        let mut tab_snapshot = self.tab_snapshot(session_id).await?;
        let (native_dialogs, native_dialog_evidence_fresh) = self
            .native_dialog_snapshot(session_id, &tab_snapshot.tabs)
            .await;
        if native_dialog_evidence_fresh
            && native_dialogs
                .iter()
                .any(|dialog| dialog.tab_id == tab_snapshot.active_tab_id)
        {
            return self
                .collect_dialog_blocked_state(
                    session_id,
                    tab_snapshot,
                    native_dialogs,
                    native_dialog_evidence_fresh,
                    force_state_version,
                    force_target_revision,
                )
                .await;
        }
        let mut page = match self.evaluate_page(&tab_snapshot.active_websocket_url).await {
            Ok(page) => page,
            Err(error) => {
                let deadline = tokio::time::Instant::now() + Duration::from_secs(2);
                loop {
                    let (observed_dialogs, evidence_fresh) = self
                        .native_dialog_snapshot(session_id, &tab_snapshot.tabs)
                        .await;
                    if evidence_fresh
                        && observed_dialogs
                            .iter()
                            .any(|dialog| dialog.tab_id == tab_snapshot.active_tab_id)
                    {
                        return self
                            .collect_dialog_blocked_state(
                                session_id,
                                tab_snapshot,
                                observed_dialogs,
                                true,
                                force_state_version,
                                force_target_revision,
                            )
                            .await;
                    }
                    if tokio::time::Instant::now() >= deadline {
                        return Err(error);
                    }
                    tokio::time::sleep(Duration::from_millis(25)).await;
                }
            }
        };
        if let Some(active) = tab_snapshot
            .tabs
            .iter_mut()
            .find(|tab| tab.tab_id == tab_snapshot.active_tab_id)
        {
            active.url = page.url.clone();
            active.title = page.title.clone();
        }
        page.targets
            .sort_by(|left, right| left.path.cmp(&right.path));
        let network_observation = self.browser_safety_observation(session_id).await;
        let network_quiet_millis = network_observation.network_quiet_millis();
        let network_readiness_hash_bucket =
            network_readiness_hash_bucket(network_quiet_millis, network_observation.fresh);
        let (serialized_targets, content_hash) = Self::state_hash(
            &page,
            &tab_snapshot,
            &native_dialogs,
            native_dialog_evidence_fresh,
            DownloadHashEvidence {
                downloads: &network_observation.downloads,
                fresh: network_observation.fresh,
            },
            page.truncated,
            network_readiness_hash_bucket,
        )?;
        let target_fingerprint = hex_sha256(serialized_targets.as_bytes());
        let (state_version, target_revision) = {
            let mut cursors = self.cursors.lock().await;
            let cursor = cursors.entry(session_id.to_owned()).or_default();
            if cursor.state_version == 0
                || cursor.content_hash != content_hash
                || force_state_version
                || force_target_revision
            {
                cursor.state_version = cursor
                    .state_version
                    .checked_add(1)
                    .ok_or_else(|| anyhow::anyhow!("State version overflow"))?;
            }
            if cursor.target_revision == 0
                || cursor.url != page.url
                || cursor.active_tab_id != tab_snapshot.active_tab_id
                || cursor.target_fingerprint != target_fingerprint
                || force_target_revision
            {
                cursor.target_revision += 1;
            }
            cursor.url = page.url.clone();
            cursor.active_tab_id = tab_snapshot.active_tab_id.clone();
            cursor.target_fingerprint = target_fingerprint;
            cursor.content_hash = content_hash.clone();
            (cursor.state_version, cursor.target_revision)
        };
        let registry_targets = page
            .targets
            .into_iter()
            .map(|target| {
                let registered = Self::registered_target(target_revision, target);
                (registered.interactive.target_ref.clone(), registered)
            })
            .collect::<HashMap<_, _>>();
        let registry = TargetRegistry {
            target_revision,
            targets: registry_targets,
        };
        let targets = Self::canonical_targets(&registry)
            .into_iter()
            .map(|(_, _, interactive)| interactive)
            .collect();
        self.target_registries
            .lock()
            .await
            .insert(session_id.to_owned(), registry);

        let state = CurrentState {
            session_id: session_id.to_owned(),
            state_version,
            target_revision,
            url: page.url,
            title: page.title,
            tabs: tab_snapshot.tabs,
            active_tab_id: tab_snapshot.active_tab_id,
            native_dialogs,
            native_dialog_evidence_fresh,
            downloads: network_observation.downloads,
            download_evidence_fresh: network_observation.fresh,
            targets,
            quality: if page.truncated {
                StateQuality::DepthLimited
            } else {
                StateQuality::Complete
            },
            content_hash,
            document_ready_state: page.document_ready_state,
            network_quiet_millis,
            network_evidence_fresh: network_observation.fresh,
        };
        self.last_states
            .write()
            .await
            .insert(session_id.to_owned(), state.clone());
        Ok(state)
    }

    async fn collect_region(
        &self,
        session_id: &str,
        root_ref: &str,
        baseline: &CurrentState,
    ) -> anyhow::Result<CurrentState> {
        anyhow::ensure!(
            baseline.session_id == session_id,
            "Region baseline session mismatch"
        );
        anyhow::ensure!(
            !root_ref.is_empty()
                && root_ref.chars().count() <= 512
                && !root_ref.chars().any(char::is_control),
            "Region root_ref is invalid"
        );
        anyhow::ensure!(
            matches!(
                baseline.quality,
                StateQuality::Complete | StateQuality::DepthLimited
            ),
            "Region baseline is not mergeable"
        );

        let collection_lock = self.collection_lock(session_id).await?;
        let _collection_guard = collection_lock.lock().await;
        let cursor = self
            .cursors
            .lock()
            .await
            .get(session_id)
            .cloned()
            .ok_or_else(|| anyhow::anyhow!("Region baseline cursor is unavailable"))?;
        let mut registry = self
            .target_registries
            .lock()
            .await
            .get(session_id)
            .cloned()
            .ok_or_else(|| anyhow::anyhow!("Region target registry is unavailable"))?;
        anyhow::ensure!(
            cursor.state_version == baseline.state_version
                && cursor.target_revision == baseline.target_revision
                && cursor.url == baseline.url
                && cursor.content_hash == baseline.content_hash
                && registry.target_revision == baseline.target_revision,
            "Region baseline is stale"
        );
        anyhow::ensure!(
            baseline.targets.len() == registry.targets.len()
                && baseline
                    .targets
                    .iter()
                    .all(|target| registry.targets.contains_key(&target.target_ref)),
            "Region target registry does not match baseline"
        );

        let root_selector = if root_ref.starts_with("target:") {
            registry
                .targets
                .get(root_ref)
                .map(|target| target.evaluated.path.clone())
                .ok_or_else(|| anyhow::anyhow!("Region target root is stale or unknown"))?
        } else {
            root_ref.to_owned()
        };
        let mut tab_snapshot = self.tab_snapshot(session_id).await?;
        let (native_dialogs, native_dialog_evidence_fresh) = self
            .native_dialog_snapshot(session_id, &tab_snapshot.tabs)
            .await;
        anyhow::ensure!(
            tab_snapshot.active_tab_id == baseline.active_tab_id,
            "Region active Page Target changed during collection"
        );
        let mut page = self
            .evaluate_region(&tab_snapshot.active_websocket_url, &root_selector)
            .await?;
        if let Some(active) = tab_snapshot
            .tabs
            .iter_mut()
            .find(|tab| tab.tab_id == tab_snapshot.active_tab_id)
        {
            active.url = page.url.clone();
            active.title = page.title.clone();
        }
        anyhow::ensure!(
            page.url == baseline.url,
            "Region page URL changed during collection"
        );
        let root_path = page
            .root_path
            .take()
            .filter(|path| !path.is_empty())
            .ok_or_else(|| anyhow::anyhow!("Region collector did not resolve a root path"))?;
        let descendant_prefix = format!("{root_path}>");
        anyhow::ensure!(
            page.targets.iter().all(|target| {
                target.path == root_path || target.path.starts_with(&descendant_prefix)
            }),
            "Region collector returned a target outside the requested root"
        );
        page.targets
            .sort_by(|left, right| left.path.cmp(&right.path));

        registry.targets.retain(|_, target| {
            target.evaluated.path != root_path
                && !target.evaluated.path.starts_with(&descendant_prefix)
        });
        for target in page.targets {
            let registered = Self::registered_target(baseline.target_revision, target);
            registry
                .targets
                .insert(registered.interactive.target_ref.clone(), registered);
        }
        anyhow::ensure!(
            registry.targets.len() <= 40,
            "Region result exceeds the bounded state target limit; request FULL resync"
        );
        let canonical = Self::canonical_targets(&registry);
        page.targets = canonical
            .iter()
            .map(|(_, evaluated, _)| evaluated.clone())
            .collect::<Vec<_>>();
        let targets = canonical
            .into_iter()
            .map(|(_, _, interactive)| interactive)
            .collect::<Vec<_>>();
        let network_observation = self.browser_safety_observation(session_id).await;
        let network_quiet_millis = network_observation.network_quiet_millis();
        let readiness_bucket =
            network_readiness_hash_bucket(network_quiet_millis, network_observation.fresh);
        let truncated = page.truncated || matches!(baseline.quality, StateQuality::DepthLimited);
        let (serialized_targets, content_hash) = Self::state_hash(
            &page,
            &tab_snapshot,
            &native_dialogs,
            native_dialog_evidence_fresh,
            DownloadHashEvidence {
                downloads: &network_observation.downloads,
                fresh: network_observation.fresh,
            },
            truncated,
            readiness_bucket,
        )?;
        let state_version = baseline.state_version.saturating_add(1);
        anyhow::ensure!(
            state_version > baseline.state_version,
            "State version overflow"
        );
        {
            let mut cursors = self.cursors.lock().await;
            let current = cursors
                .get_mut(session_id)
                .ok_or_else(|| anyhow::anyhow!("Region baseline cursor disappeared"))?;
            anyhow::ensure!(
                current.state_version == baseline.state_version
                    && current.target_revision == baseline.target_revision,
                "Region baseline changed during collection"
            );
            current.state_version = state_version;
            current.url = page.url.clone();
            current.active_tab_id = tab_snapshot.active_tab_id.clone();
            current.target_fingerprint = hex_sha256(serialized_targets.as_bytes());
            current.content_hash = content_hash.clone();
        }
        self.target_registries
            .lock()
            .await
            .insert(session_id.to_owned(), registry);

        let state = CurrentState {
            session_id: session_id.to_owned(),
            state_version,
            target_revision: baseline.target_revision,
            url: page.url,
            title: page.title,
            tabs: tab_snapshot.tabs,
            active_tab_id: tab_snapshot.active_tab_id,
            native_dialogs,
            native_dialog_evidence_fresh,
            downloads: network_observation.downloads,
            download_evidence_fresh: network_observation.fresh,
            targets,
            quality: if truncated {
                StateQuality::DepthLimited
            } else {
                StateQuality::Complete
            },
            content_hash,
            document_ready_state: page.document_ready_state,
            network_quiet_millis,
            network_evidence_fresh: network_observation.fresh,
        };
        self.last_states
            .write()
            .await
            .insert(session_id.to_owned(), state.clone());
        Ok(state)
    }
}

#[async_trait]
impl BrowserStateCollector for CdpStateCollector {
    async fn collect_current_state(&self, session_id: &str) -> anyhow::Result<CurrentState> {
        self.collect(session_id, false, false).await
    }

    async fn collect_action_confirmation(&self, session_id: &str) -> anyhow::Result<CurrentState> {
        self.collect(session_id, true, false).await
    }

    async fn resync_full(&self, session_id: &str) -> anyhow::Result<CurrentState> {
        self.collect(session_id, true, true).await
    }

    async fn resync_region(
        &self,
        session_id: &str,
        root_ref: &str,
        baseline: &CurrentState,
    ) -> anyhow::Result<CurrentState> {
        self.collect_region(session_id, root_ref, baseline).await
    }
}

fn hex_sha256(value: &[u8]) -> String {
    Sha256::digest(value)
        .iter()
        .map(|byte| format!("{byte:02x}"))
        .collect()
}

async fn next_cdp_response<S>(
    socket: &mut tokio_tungstenite::WebSocketStream<S>,
    expected_id: i64,
    operation: &str,
) -> anyhow::Result<serde_json::Value>
where
    S: tokio::io::AsyncRead + tokio::io::AsyncWrite + Unpin,
{
    while let Some(message) = timeout(Duration::from_secs(3), socket.next())
        .await
        .map_err(|_| anyhow::anyhow!("CDP {operation} timed out"))?
    {
        let Message::Text(text) = message? else {
            continue;
        };
        let response: serde_json::Value = serde_json::from_str(&text)?;
        if response.get("id").and_then(serde_json::Value::as_i64) != Some(expected_id) {
            continue;
        }
        if let Some(error) = response.get("error") {
            anyhow::bail!("CDP {operation} failed: {error}");
        }
        if let Some(exception) = response.pointer("/result/exceptionDetails") {
            anyhow::bail!("CDP {operation} was rejected: {exception}");
        }
        return Ok(response);
    }
    anyhow::bail!("CDP websocket closed before {operation} acknowledgement")
}

/// 将 Network Quiet 证据压缩为控制面恢复策略真正关心的有界语义。
///
/// Recovery Contract 当前允许的最大静默窗口为 30 秒，因此超过该阈值后继续增长的
/// 原始毫秒值不应制造新的 Browser State 版本。Freshness 使用独立的 0 桶，确保
/// Runtime 重建或 CDP 观察中断时能够立即撤销旧的就绪证据。
fn network_readiness_hash_bucket(network_quiet_millis: u64, evidence_fresh: bool) -> u64 {
    if !evidence_fresh {
        return 0;
    }
    1 + network_quiet_millis.min(MAX_NETWORK_QUIET_POLICY_MILLIS)
        / NETWORK_READINESS_HASH_BUCKET_MILLIS
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::path::PathBuf;
    use std::time::{SystemTime, UNIX_EPOCH};
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    use tokio::net::TcpListener;

    #[test]
    fn network_readiness_hash_bucket_tracks_policy_thresholds_without_unbounded_churn() {
        assert_eq!(network_readiness_hash_bucket(300_000, false), 0);
        assert_eq!(network_readiness_hash_bucket(0, true), 1);
        assert_eq!(network_readiness_hash_bucket(999, true), 1);
        assert_eq!(network_readiness_hash_bucket(1_000, true), 2);
        assert_eq!(network_readiness_hash_bucket(29_999, true), 30);
        assert_eq!(network_readiness_hash_bucket(30_000, true), 31);
        assert_eq!(network_readiness_hash_bucket(300_000, true), 31);
    }

    #[tokio::test]
    async fn collects_page_and_interactive_targets_over_cdp() {
        let websocket_listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let websocket_address = websocket_listener.local_addr().unwrap();
        let websocket_task = tokio::spawn(async move {
            for _ in 0..3 {
                let (stream, _) = websocket_listener.accept().await.unwrap();
                let mut socket = tokio_tungstenite::accept_async(stream).await.unwrap();
                let request = socket.next().await.unwrap().unwrap();
                let Message::Text(request) = request else {
                    panic!("expected CDP text request");
                };
                let request: serde_json::Value = serde_json::from_str(&request).unwrap();
                assert_eq!(request["method"], "Runtime.evaluate");
                let expression = request["params"]["expression"].as_str().unwrap();
                assert!(expression.contains("'cc-number'"));
                assert!(expression.contains("HIGHLY_SENSITIVE"));
                assert!(expression.contains("private.?key"));
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
            for _ in 0..3 {
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
        assert_eq!(repeated.state_version, 1);
        assert_eq!(repeated.targets[0].target_ref, state.targets[0].target_ref);
        assert_eq!(repeated.target_revision, state.target_revision);
        assert_eq!(repeated.content_hash, state.content_hash);
        let action_confirmation = collector
            .collect_action_confirmation("ses_state")
            .await
            .unwrap();
        assert_eq!(action_confirmation.state_version, 2);
        assert_eq!(action_confirmation.target_revision, state.target_revision);
        assert_eq!(action_confirmation.content_hash, state.content_hash);
        assert_eq!(action_confirmation.targets, state.targets);

        websocket_task.await.unwrap();
        http_task.await.unwrap();
    }

    #[tokio::test]
    async fn resyncs_only_the_requested_region_and_preserves_outside_target_refs() {
        let websocket_listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let websocket_address = websocket_listener.local_addr().unwrap();
        let websocket_task = tokio::spawn(async move {
            for index in 0..3 {
                let (stream, _) = websocket_listener.accept().await.unwrap();
                let mut socket = tokio_tungstenite::accept_async(stream).await.unwrap();
                let Message::Text(request) = socket.next().await.unwrap().unwrap() else {
                    panic!("expected CDP text request");
                };
                let request: serde_json::Value = serde_json::from_str(&request).unwrap();
                let expression = request["params"]["expression"].as_str().unwrap();
                if index == 1 {
                    assert!(expression.contains("const requestedRoot = \"#app\";"));
                    assert!(expression.contains("walk(root)"));
                    assert!(expression.contains("element.shadowRoot"));
                    assert!(expression.contains("element.contentDocument"));
                } else {
                    assert!(expression.contains("const requestedRoot = null;"));
                }
                let outside = serde_json::json!({
                    "path": "html:nth-of-type(1)>body:nth-of-type(1)>button:nth-of-type(1)",
                    "role": "button",
                    "name": "Outside",
                    "bounds": {"x": 10.0, "y": 10.0, "width": 80.0, "height": 24.0},
                    "enabled": true,
                    "visible": true
                });
                let old_inside = serde_json::json!({
                    "path": "html:nth-of-type(1)>body:nth-of-type(1)>div:nth-of-type(1)>button:nth-of-type(1)",
                    "role": "button",
                    "name": "Old inside",
                    "bounds": {"x": 10.0, "y": 50.0, "width": 80.0, "height": 24.0},
                    "enabled": true,
                    "visible": true
                });
                let new_inside = serde_json::json!({
                    "path": "html:nth-of-type(1)>body:nth-of-type(1)>div:nth-of-type(1)>input:nth-of-type(1)",
                    "role": "textbox",
                    "name": "New inside",
                    "bounds": {"x": 10.0, "y": 50.0, "width": 120.0, "height": 24.0},
                    "enabled": true,
                    "visible": true
                });
                let (targets, root_path) = match index {
                    0 => (vec![outside, old_inside], serde_json::Value::Null),
                    1 => (
                        vec![new_inside],
                        serde_json::Value::String(
                            "html:nth-of-type(1)>body:nth-of-type(1)>div:nth-of-type(1)".to_owned(),
                        ),
                    ),
                    _ => (vec![outside, new_inside], serde_json::Value::Null),
                };
                let response = serde_json::json!({
                    "id": 1,
                    "result": {"result": {"type": "object", "value": {
                        "url": "https://example.test/app",
                        "title": "Region test",
                        "documentReadyState": "complete",
                        "targets": targets,
                        "truncated": false,
                        "rootPath": root_path
                    }}}
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
            for _ in 0..3 {
                let (mut stream, _) = http_listener.accept().await.unwrap();
                let mut request = vec![0_u8; 4096];
                let count = stream.read(&mut request).await.unwrap();
                assert!(String::from_utf8_lossy(&request[..count]).starts_with("GET /json/list "));
                let response = format!(
                    "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}",
                    body.len(), body
                );
                stream.write_all(response.as_bytes()).await.unwrap();
            }
        });

        let collector = CdpStateCollector::new();
        collector
            .register_runtime("ses_region", &format!("http://{http_address}"))
            .await
            .unwrap();
        let baseline = collector.collect_current_state("ses_region").await.unwrap();
        let outside_ref = baseline
            .targets
            .iter()
            .find(|target| target.name.as_deref() == Some("Outside"))
            .unwrap()
            .target_ref
            .clone();
        let old_inside_ref = baseline
            .targets
            .iter()
            .find(|target| target.name.as_deref() == Some("Old inside"))
            .unwrap()
            .target_ref
            .clone();

        let region = collector
            .resync_region("ses_region", "#app", &baseline)
            .await
            .unwrap();
        assert_eq!(region.state_version, baseline.state_version + 1);
        assert_eq!(region.target_revision, baseline.target_revision);
        assert!(region
            .targets
            .iter()
            .any(|target| target.target_ref == outside_ref));
        assert!(!region
            .targets
            .iter()
            .any(|target| target.target_ref == old_inside_ref));
        assert!(region
            .targets
            .iter()
            .any(|target| target.name.as_deref() == Some("New inside")));

        let periodic = collector.collect_current_state("ses_region").await.unwrap();
        assert_eq!(periodic.target_revision, region.target_revision);
        assert_eq!(periodic.content_hash, region.content_hash);
        assert_eq!(periodic.targets, region.targets);

        websocket_task.await.unwrap();
        http_task.await.unwrap();
    }

    #[tokio::test]
    async fn collects_lightweight_browser_resource_metrics_over_cdp() {
        let browser_listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let browser_address = browser_listener.local_addr().unwrap();
        let browser_task = tokio::spawn(async move {
            let (stream, _) = browser_listener.accept().await.unwrap();
            let mut socket = tokio_tungstenite::accept_async(stream).await.unwrap();
            let Message::Text(request) = socket.next().await.unwrap().unwrap() else {
                panic!("expected browser CDP request");
            };
            let request: serde_json::Value = serde_json::from_str(&request).unwrap();
            assert_eq!(request["method"], "SystemInfo.getProcessInfo");
            socket
                .send(Message::Text(
                    serde_json::json!({
                        "id": request["id"],
                        "result": {
                            "processInfo": [
                                {"type": "browser", "id": 1},
                                {"type": "renderer", "id": 2},
                                {"type": "renderer", "id": 3},
                                {"type": "gpu-process", "id": 4}
                            ]
                        }
                    })
                    .to_string(),
                ))
                .await
                .unwrap();
        });

        let page_listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let page_address = page_listener.local_addr().unwrap();
        let page_task = tokio::spawn(async move {
            let (stream, _) = page_listener.accept().await.unwrap();
            let mut socket = tokio_tungstenite::accept_async(stream).await.unwrap();
            let Message::Text(request) = socket.next().await.unwrap().unwrap() else {
                panic!("expected page CDP request");
            };
            let request: serde_json::Value = serde_json::from_str(&request).unwrap();
            assert_eq!(request["method"], "Performance.getMetrics");
            socket
                .send(Message::Text(
                    serde_json::json!({
                        "id": request["id"],
                        "result": {
                            "metrics": [
                                {"name": "Timestamp", "value": 10.0},
                                {"name": "TaskDuration", "value": 0.125}
                            ]
                        }
                    })
                    .to_string(),
                ))
                .await
                .unwrap();
        });

        let http_listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let http_address = http_listener.local_addr().unwrap();
        let http_task = tokio::spawn(async move {
            for _ in 0..2 {
                let (mut stream, _) = http_listener.accept().await.unwrap();
                let mut request = vec![0_u8; 4096];
                let count = stream.read(&mut request).await.unwrap();
                let request = String::from_utf8_lossy(&request[..count]);
                let (path, body) = if request.starts_with("GET /json/list ") {
                    (
                        "/json/list",
                        serde_json::json!([
                            {
                                "id": "page-1",
                                "type": "page",
                                "webSocketDebuggerUrl": format!(
                                    "ws://{page_address}/devtools/page/1"
                                )
                            },
                            {"id": "worker-1", "type": "service_worker"}
                        ])
                        .to_string(),
                    )
                } else {
                    assert!(request.starts_with("GET /json/version "));
                    (
                        "/json/version",
                        serde_json::json!({
                            "webSocketDebuggerUrl": format!(
                                "ws://{browser_address}/devtools/browser/1"
                            )
                        })
                        .to_string(),
                    )
                };
                let response = format!(
                    "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nX-Test-Path: {path}\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}",
                    body.len(),
                    body
                );
                stream.write_all(response.as_bytes()).await.unwrap();
            }
        });

        let collector = CdpStateCollector::new();
        collector
            .register_runtime("ses_metrics", &format!("http://{http_address}"))
            .await
            .unwrap();
        let metrics = collector
            .collect_resource_metrics("ses_metrics")
            .await
            .unwrap();
        assert_eq!(metrics.renderer_count, Some(2));
        assert_eq!(metrics.tab_count, 1);
        assert_eq!(metrics.main_thread_task_duration_ms, Some(125.0));

        browser_task.await.unwrap();
        page_task.await.unwrap();
        http_task.await.unwrap();
    }

    #[tokio::test]
    async fn applies_per_session_collector_budget_to_cadence_and_diff_limits() {
        let collector = CdpStateCollector::new();
        collector
            .register_runtime("ses_budget", "http://127.0.0.1:9222")
            .await
            .unwrap();

        assert_eq!(collector.resource_budget_percent("ses_budget").await, 100);
        assert_eq!(collector.collection_interval_probes("ses_budget").await, 2);
        assert_eq!(
            collector
                .bounded_diff_limits("ses_budget", 60_000, 200)
                .await,
            (60_000, 200)
        );

        assert_eq!(
            collector
                .set_resource_budget("ses_budget", 25)
                .await
                .unwrap(),
            100
        );
        assert_eq!(collector.collection_interval_probes("ses_budget").await, 8);
        assert_eq!(
            collector
                .bounded_diff_limits("ses_budget", 60_000, 200)
                .await,
            (15_000, 50)
        );
        assert!(collector
            .set_resource_budget("ses_budget", 9)
            .await
            .is_err());

        collector.unregister_runtime("ses_budget").await;
        assert_eq!(collector.resource_budget_percent("ses_budget").await, 100);
    }

    #[tokio::test]
    async fn resolves_the_single_visible_page_as_the_authoritative_active_tab() {
        async fn visibility_server(value: &'static str) -> (std::net::SocketAddr, JoinHandle<()>) {
            let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
            let address = listener.local_addr().unwrap();
            let task = tokio::spawn(async move {
                let (stream, _) = listener.accept().await.unwrap();
                let mut socket = tokio_tungstenite::accept_async(stream).await.unwrap();
                let Message::Text(request) = socket.next().await.unwrap().unwrap() else {
                    panic!("expected visibility command");
                };
                let request: serde_json::Value = serde_json::from_str(&request).unwrap();
                assert_eq!(request["method"], "Runtime.evaluate");
                socket
                    .send(Message::Text(
                        serde_json::json!({
                            "id": request["id"],
                            "result": {"result": {"value": value}}
                        })
                        .to_string(),
                    ))
                    .await
                    .unwrap();
            });
            (address, task)
        }

        let (hidden_address, hidden_task) = visibility_server("hidden").await;
        let (visible_address, visible_task) = visibility_server("visible").await;
        let http_listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let http_address = http_listener.local_addr().unwrap();
        let http_task = tokio::spawn(async move {
            let (mut stream, _) = http_listener.accept().await.unwrap();
            let mut request = vec![0_u8; 4096];
            let count = stream.read(&mut request).await.unwrap();
            assert!(String::from_utf8_lossy(&request[..count]).starts_with("GET /json/list "));
            let body = serde_json::json!([
                {
                    "id": "tab-hidden",
                    "type": "page",
                    "url": "https://hidden.example.test",
                    "title": "Hidden",
                    "webSocketDebuggerUrl": format!("ws://{hidden_address}/devtools/page/hidden")
                },
                {
                    "id": "tab-visible",
                    "type": "page",
                    "url": "https://visible.example.test",
                    "title": "Visible",
                    "webSocketDebuggerUrl": format!("ws://{visible_address}/devtools/page/visible")
                }
            ])
            .to_string();
            stream
                .write_all(
                    format!(
                        "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}",
                        body.len(), body
                    )
                    .as_bytes(),
                )
                .await
                .unwrap();
        });
        let collector = CdpStateCollector::new();
        collector
            .register_runtime("ses_tabs", &format!("http://{http_address}"))
            .await
            .unwrap();

        let snapshot = collector.tab_snapshot("ses_tabs").await.unwrap();
        assert_eq!(snapshot.active_tab_id, "tab-visible");
        assert_eq!(snapshot.tabs.len(), 2);
        assert!(snapshot.tabs[1].active);
        assert_eq!(
            snapshot.active_websocket_url,
            format!("ws://{visible_address}/devtools/page/visible")
        );

        hidden_task.await.unwrap();
        visible_task.await.unwrap();
        http_task.await.unwrap();
    }

    #[tokio::test]
    async fn preserves_monotonic_state_version_but_rotates_page_identity_after_runtime_unregister()
    {
        let collector = CdpStateCollector::new();
        collector
            .register_runtime("ses_generation", "http://127.0.0.1:9222")
            .await
            .unwrap();
        collector.cursors.lock().await.insert(
            "ses_generation".to_owned(),
            CollectorCursor {
                state_version: 7,
                target_revision: 3,
                url: "https://example.test/app".to_owned(),
                target_fingerprint: "fingerprint".to_owned(),
                content_hash: "content-hash".to_owned(),
                active_tab_id: "tab-1".to_owned(),
            },
        );

        collector.unregister_runtime("ses_generation").await;

        let cursors = collector.cursors.lock().await;
        let cursor = cursors.get("ses_generation").unwrap();
        assert_eq!(cursor.state_version, 7);
        assert_eq!(cursor.target_revision, 3);
        assert!(cursor.url.is_empty());
        assert!(cursor.target_fingerprint.is_empty());
        assert!(cursor.active_tab_id.is_empty());
        drop(cursors);
        assert!(!collector
            .collection_locks
            .lock()
            .await
            .contains_key("ses_generation"));
    }

    #[test]
    fn rejects_non_loopback_cdp_websocket_targets() {
        assert!(CdpStateCollector::require_loopback_websocket(
            "ws://127.0.0.1:9222/devtools/page/1"
        )
        .is_ok());
        assert!(
            CdpStateCollector::require_loopback_websocket("ws://example.test/devtools/page/1")
                .is_err()
        );
    }

    #[tokio::test]
    async fn freezes_hidden_page_targets_before_acknowledging_tab_policy() {
        let page_listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let page_address = page_listener.local_addr().unwrap();
        let page_task = tokio::spawn(async move {
            for expected_method in ["Runtime.evaluate", "Page.setWebLifecycleState"] {
                let (stream, _) = page_listener.accept().await.unwrap();
                let mut socket = tokio_tungstenite::accept_async(stream).await.unwrap();
                let Message::Text(request) = socket.next().await.unwrap().unwrap() else {
                    panic!("expected page CDP request");
                };
                let request: serde_json::Value = serde_json::from_str(&request).unwrap();
                assert_eq!(request["method"], expected_method);
                if expected_method == "Page.setWebLifecycleState" {
                    assert_eq!(request["params"]["state"], "frozen");
                }
                let result = if expected_method == "Runtime.evaluate" {
                    serde_json::json!({"result": {"type": "string", "value": "hidden"}})
                } else {
                    serde_json::json!({})
                };
                socket
                    .send(Message::Text(
                        serde_json::json!({"id": request["id"], "result": result}).to_string(),
                    ))
                    .await
                    .unwrap();
            }
        });

        let http_listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let http_address = http_listener.local_addr().unwrap();
        let http_task = tokio::spawn(async move {
            let (mut stream, _) = http_listener.accept().await.unwrap();
            let mut request = vec![0_u8; 4096];
            let count = stream.read(&mut request).await.unwrap();
            let request = String::from_utf8_lossy(&request[..count]);
            assert!(request.starts_with("GET /json/list "));
            let body = serde_json::json!([{
                "id": "page-hidden",
                "type": "page",
                "url": "https://example.test/background",
                "webSocketDebuggerUrl": format!("ws://{page_address}/devtools/page/hidden")
            }])
            .to_string();
            let response = format!(
                "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}",
                body.len(),
                body
            );
            stream.write_all(response.as_bytes()).await.unwrap();
        });

        let collector = CdpStateCollector::new();
        collector
            .register_runtime("ses_tabs", &format!("http://{http_address}"))
            .await
            .unwrap();
        let previous = collector
            .set_tab_resource_policy(
                "ses_tabs",
                TabResourcePolicy {
                    tab_budget: 8,
                    freeze_background_tabs: true,
                    block_new_tabs: true,
                    paused_extension_ids: Vec::new(),
                },
            )
            .await
            .unwrap();
        assert!(!previous.freeze_background_tabs);
        assert!(!previous.block_new_tabs);
        assert_eq!(
            collector.tab_resource_policy("ses_tabs").await,
            Some(TabResourcePolicy {
                tab_budget: 8,
                freeze_background_tabs: true,
                block_new_tabs: true,
                paused_extension_ids: Vec::new(),
            })
        );
        collector.unregister_runtime("ses_tabs").await;
        page_task.await.unwrap();
        http_task.await.unwrap();
    }

    #[tokio::test]
    async fn closes_page_targets_created_after_new_tab_block_is_committed() {
        let browser_listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let browser_address = browser_listener.local_addr().unwrap();
        let browser_task = tokio::spawn(async move {
            let (stream, _) = browser_listener.accept().await.unwrap();
            let mut socket = tokio_tungstenite::accept_async(stream).await.unwrap();
            let Message::Text(request) = socket.next().await.unwrap().unwrap() else {
                panic!("expected browser CDP request");
            };
            let request: serde_json::Value = serde_json::from_str(&request).unwrap();
            assert_eq!(request["method"], "Target.closeTarget");
            assert_eq!(request["params"]["targetId"], "page-new");
            socket
                .send(Message::Text(
                    serde_json::json!({
                        "id": request["id"],
                        "result": {"success": true}
                    })
                    .to_string(),
                ))
                .await
                .unwrap();
        });

        let http_listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let http_address = http_listener.local_addr().unwrap();
        let http_task = tokio::spawn(async move {
            for request_number in 0..3 {
                let (mut stream, _) = http_listener.accept().await.unwrap();
                let mut request = vec![0_u8; 4096];
                let count = stream.read(&mut request).await.unwrap();
                let request = String::from_utf8_lossy(&request[..count]);
                let body = if request.starts_with("GET /json/version ") {
                    serde_json::json!({
                        "webSocketDebuggerUrl": format!(
                            "ws://{browser_address}/devtools/browser/test"
                        )
                    })
                    .to_string()
                } else {
                    assert!(request.starts_with("GET /json/list "));
                    if request_number == 0 {
                        serde_json::json!([{
                            "id": "page-existing",
                            "type": "page",
                            "url": "https://example.test/current"
                        }])
                        .to_string()
                    } else {
                        serde_json::json!([{
                            "id": "page-existing",
                            "type": "page",
                            "url": "https://example.test/current"
                        }, {
                            "id": "page-new",
                            "type": "page",
                            "url": "https://example.test/new"
                        }])
                        .to_string()
                    }
                };
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
            .register_runtime("ses_block_tabs", &format!("http://{http_address}"))
            .await
            .unwrap();
        collector
            .set_tab_resource_policy(
                "ses_block_tabs",
                TabResourcePolicy {
                    tab_budget: 8,
                    freeze_background_tabs: false,
                    block_new_tabs: true,
                    paused_extension_ids: Vec::new(),
                },
            )
            .await
            .unwrap();
        collector
            .enforce_tab_resource_policy_once("ses_block_tabs")
            .await
            .unwrap();
        collector.unregister_runtime("ses_block_tabs").await;
        browser_task.await.unwrap();
        http_task.await.unwrap();
    }

    #[tokio::test]
    async fn pauses_and_resumes_only_configured_extension_background_targets() {
        let paused_extension_id = "abcdefghijklmnopabcdefghijklmnop";
        let privileged_extension_id = "ponmlkjihgfedcbaponmlkjihgfedcba";
        let extension_listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let extension_address = extension_listener.local_addr().unwrap();
        let extension_task = tokio::spawn(async move {
            for expected_method in [
                "Debugger.enable",
                "Debugger.pause",
                "Debugger.resume",
                "Debugger.disable",
            ] {
                let (stream, _) = extension_listener.accept().await.unwrap();
                let mut socket = tokio_tungstenite::accept_async(stream).await.unwrap();
                let Message::Text(request) = socket.next().await.unwrap().unwrap() else {
                    panic!("expected extension CDP request");
                };
                let request: serde_json::Value = serde_json::from_str(&request).unwrap();
                assert_eq!(request["method"], expected_method);
                socket
                    .send(Message::Text(
                        serde_json::json!({"id": request["id"], "result": {}}).to_string(),
                    ))
                    .await
                    .unwrap();
            }
        });

        let http_listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let http_address = http_listener.local_addr().unwrap();
        let http_task = tokio::spawn(async move {
            for _ in 0..3 {
                let (mut stream, _) = http_listener.accept().await.unwrap();
                let mut request = vec![0_u8; 4096];
                let count = stream.read(&mut request).await.unwrap();
                assert!(String::from_utf8_lossy(&request[..count]).starts_with("GET /json/list "));
                let body = serde_json::json!([
                    {
                        "id": "extension-paused",
                        "type": "service_worker",
                        "url": format!("chrome-extension://{paused_extension_id}/background.js"),
                        "webSocketDebuggerUrl": format!(
                            "ws://{extension_address}/devtools/page/extension-paused"
                        )
                    },
                    {
                        "id": "extension-privileged",
                        "type": "background_page",
                        "url": format!("chrome-extension://{privileged_extension_id}/background.html"),
                        "webSocketDebuggerUrl": format!(
                            "ws://{extension_address}/devtools/page/extension-privileged"
                        )
                    },
                    {
                        "id": "page-current",
                        "type": "page",
                        "url": "https://example.test/current"
                    }
                ])
                .to_string();
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
            .register_runtime("ses_extension_policy", &format!("http://{http_address}"))
            .await
            .unwrap();
        collector
            .set_tab_resource_policy(
                "ses_extension_policy",
                TabResourcePolicy {
                    tab_budget: 8,
                    freeze_background_tabs: false,
                    block_new_tabs: false,
                    paused_extension_ids: vec![paused_extension_id.to_owned()],
                },
            )
            .await
            .unwrap();
        assert_eq!(
            collector
                .tab_resource_policy("ses_extension_policy")
                .await
                .unwrap()
                .paused_extension_ids,
            vec![paused_extension_id]
        );

        collector
            .set_tab_resource_policy(
                "ses_extension_policy",
                TabResourcePolicy {
                    tab_budget: 8,
                    freeze_background_tabs: false,
                    block_new_tabs: false,
                    paused_extension_ids: Vec::new(),
                },
            )
            .await
            .unwrap();
        collector.unregister_runtime("ses_extension_policy").await;
        extension_task.await.unwrap();
        http_task.await.unwrap();
    }

    #[tokio::test]
    async fn pauses_matching_extension_target_created_after_policy_commit() {
        let extension_id = "abcdefghijklmnopabcdefghijklmnop";
        let extension_listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let extension_address = extension_listener.local_addr().unwrap();
        let extension_task = tokio::spawn(async move {
            for expected_method in ["Debugger.enable", "Debugger.pause"] {
                let (stream, _) = extension_listener.accept().await.unwrap();
                let mut socket = tokio_tungstenite::accept_async(stream).await.unwrap();
                let Message::Text(request) = socket.next().await.unwrap().unwrap() else {
                    panic!("expected extension CDP request");
                };
                let request: serde_json::Value = serde_json::from_str(&request).unwrap();
                assert_eq!(request["method"], expected_method);
                socket
                    .send(Message::Text(
                        serde_json::json!({"id": request["id"], "result": {}}).to_string(),
                    ))
                    .await
                    .unwrap();
            }
        });

        let http_listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let http_address = http_listener.local_addr().unwrap();
        let http_task = tokio::spawn(async move {
            for request_number in 0..2 {
                let (mut stream, _) = http_listener.accept().await.unwrap();
                let mut request = vec![0_u8; 4096];
                let count = stream.read(&mut request).await.unwrap();
                assert!(String::from_utf8_lossy(&request[..count]).starts_with("GET /json/list "));
                let mut targets = vec![serde_json::json!({
                    "id": "page-current",
                    "type": "page",
                    "url": "https://example.test/current"
                })];
                if request_number == 1 {
                    targets.push(serde_json::json!({
                        "id": "extension-restarted",
                        "type": "service_worker",
                        "url": format!("chrome-extension://{extension_id}/background.js"),
                        "webSocketDebuggerUrl": format!(
                            "ws://{extension_address}/devtools/page/extension-restarted"
                        )
                    }));
                }
                let body = serde_json::Value::Array(targets).to_string();
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
            .register_runtime("ses_extension_monitor", &format!("http://{http_address}"))
            .await
            .unwrap();
        collector
            .set_tab_resource_policy(
                "ses_extension_monitor",
                TabResourcePolicy {
                    tab_budget: 8,
                    freeze_background_tabs: false,
                    block_new_tabs: false,
                    paused_extension_ids: vec![extension_id.to_owned()],
                },
            )
            .await
            .unwrap();
        collector
            .enforce_tab_resource_policy_once("ses_extension_monitor")
            .await
            .unwrap();
        collector.unregister_runtime("ses_extension_monitor").await;
        extension_task.await.unwrap();
        http_task.await.unwrap();
    }

    #[tokio::test]
    async fn restarts_only_the_matching_chromium_extension_context() {
        let extension_id = "jdgnleokimdbblcflcfcohbinohmmmlb";
        let websocket_listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let websocket_address = websocket_listener.local_addr().unwrap();
        let websocket_task = tokio::spawn(async move {
            let (stream, _) = websocket_listener.accept().await.unwrap();
            let mut socket = tokio_tungstenite::accept_async(stream).await.unwrap();
            let Message::Text(request) = socket.next().await.unwrap().unwrap() else {
                panic!("expected CDP text request");
            };
            let request: serde_json::Value = serde_json::from_str(&request).unwrap();
            assert_eq!(request["method"], "Runtime.evaluate");
            assert_eq!(
                request["params"]["expression"],
                "setTimeout(() => chrome.runtime.reload(), 0); true"
            );
            socket
                .send(Message::Text(
                    serde_json::json!({
                        "id": 4,
                        "result": {"result": {"type": "boolean", "value": true}}
                    })
                    .to_string(),
                ))
                .await
                .unwrap();
        });

        let http_listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let http_address = http_listener.local_addr().unwrap();
        let http_task = tokio::spawn(async move {
            let (mut stream, _) = http_listener.accept().await.unwrap();
            let mut request = vec![0_u8; 4096];
            let count = stream.read(&mut request).await.unwrap();
            assert!(String::from_utf8_lossy(&request[..count]).starts_with("GET /json/list "));
            let body = serde_json::json!([{
                "id": "extension-worker",
                "type": "service_worker",
                "url": format!("chrome-extension://{extension_id}/background.js"),
                "webSocketDebuggerUrl": format!(
                    "ws://{websocket_address}/devtools/page/extension-worker"
                )
            }])
            .to_string();
            let response = format!(
                "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}",
                body.len(),
                body
            );
            stream.write_all(response.as_bytes()).await.unwrap();
        });

        let collector = CdpStateCollector::new();
        collector
            .register_runtime("ses_extension", &format!("http://{http_address}"))
            .await
            .unwrap();
        collector
            .restart_extension("ses_extension", extension_id)
            .await
            .unwrap();

        websocket_task.await.unwrap();
        http_task.await.unwrap();
    }

    #[tokio::test]
    async fn sets_hidden_file_input_through_cdp_without_opening_a_file_chooser() {
        let staged = std::env::temp_dir().join(format!(
            "browsercloud-agent-file-{}.txt",
            SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        ));
        tokio::fs::write(&staged, b"bounded file").await.unwrap();
        let expected_path = staged.to_string_lossy().to_string();
        let websocket_listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let websocket_address = websocket_listener.local_addr().unwrap();
        let websocket_task = tokio::spawn(async move {
            let (stream, _) = websocket_listener.accept().await.unwrap();
            let mut socket = tokio_tungstenite::accept_async(stream).await.unwrap();
            let Message::Text(resolve) = socket.next().await.unwrap().unwrap() else {
                panic!("expected file-input resolve")
            };
            let resolve: serde_json::Value = serde_json::from_str(&resolve).unwrap();
            assert_eq!(resolve["method"], "Runtime.evaluate");
            assert!(resolve["params"]["expression"]
                .as_str()
                .unwrap()
                .contains("input:nth-of-type(1)"));
            socket
                .send(Message::Text(
                    serde_json::json!({
                        "id": 501,
                        "result": {"result": {"type": "object", "objectId": "file-object-1"}}
                    })
                    .to_string(),
                ))
                .await
                .unwrap();
            let Message::Text(describe) = socket.next().await.unwrap().unwrap() else {
                panic!("expected DOM.describeNode")
            };
            let describe: serde_json::Value = serde_json::from_str(&describe).unwrap();
            assert_eq!(describe["method"], "DOM.describeNode");
            socket
                .send(Message::Text(
                    serde_json::json!({"id": 502, "result": {"node": {"backendNodeId": 77}}})
                        .to_string(),
                ))
                .await
                .unwrap();
            let Message::Text(set_files) = socket.next().await.unwrap().unwrap() else {
                panic!("expected DOM.setFileInputFiles")
            };
            let set_files: serde_json::Value = serde_json::from_str(&set_files).unwrap();
            assert_eq!(set_files["method"], "DOM.setFileInputFiles");
            assert_eq!(set_files["params"]["backendNodeId"], 77);
            assert_eq!(set_files["params"]["files"][0], expected_path);
            assert_ne!(set_files["method"], "Page.setInterceptFileChooserDialog");
            socket
                .send(Message::Text(
                    serde_json::json!({"id": 503, "result": {}}).to_string(),
                ))
                .await
                .unwrap();
        });

        let http_listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let http_address = http_listener.local_addr().unwrap();
        let http_task = tokio::spawn(async move {
            let (mut stream, _) = http_listener.accept().await.unwrap();
            let mut request = vec![0_u8; 4096];
            let count = stream.read(&mut request).await.unwrap();
            assert!(String::from_utf8_lossy(&request[..count]).starts_with("GET /json/list "));
            let body = serde_json::json!([{
                "id": "page-file",
                "type": "page",
                "url": "https://example.test/upload",
                "webSocketDebuggerUrl": format!("ws://{websocket_address}/devtools/page/file")
            }])
            .to_string();
            let response = format!(
                "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}",
                body.len(), body
            );
            stream.write_all(response.as_bytes()).await.unwrap();
        });

        let collector = CdpStateCollector::new();
        collector
            .endpoints
            .write()
            .await
            .insert("ses_file".to_owned(), format!("http://{http_address}"));
        let evaluated = EvaluatedTarget {
            path: "html:nth-of-type(1)>body:nth-of-type(1)>input:nth-of-type(1)".to_owned(),
            role: "button".to_owned(),
            name: Some("Upload".to_owned()),
            value: None,
            control_type: Some("file".to_owned()),
            bounds: None,
            enabled: true,
            visible: false,
            sensitive: false,
            focused: false,
            checked: None,
            selected: None,
            interactive: true,
            frame_id: "main".to_owned(),
            in_viewport: false,
            occluded: false,
            visibility_reason: Some("DISPLAY_NONE".to_owned()),
        };
        let interactive = InteractiveTarget {
            target_ref: "target:file".to_owned(),
            element_id: "element-file".to_owned(),
            role: "button".to_owned(),
            name: Some("Upload".to_owned()),
            value: None,
            control_type: Some("file".to_owned()),
            bounds: None,
            enabled: true,
            visible: false,
            sensitive: false,
            focused: false,
            checked: None,
            selected: None,
            interactive: true,
            frame_id: "main".to_owned(),
            in_viewport: false,
            occluded: false,
            visibility_reason: Some("DISPLAY_NONE".to_owned()),
        };
        collector.target_registries.lock().await.insert(
            "ses_file".to_owned(),
            TargetRegistry {
                target_revision: 7,
                targets: HashMap::from([(
                    "target:file".to_owned(),
                    RegisteredTarget {
                        evaluated,
                        interactive,
                        resolved: None,
                    },
                )]),
            },
        );
        collector
            .set_file_input_files("ses_file", "target:file", 7, &staged)
            .await
            .unwrap();

        websocket_task.await.unwrap();
        http_task.await.unwrap();
        tokio::fs::remove_file(staged).await.unwrap();
    }

    #[test]
    fn creates_bounded_diff_and_reports_truncation() {
        let target = |target_ref: &str, name: &str| InteractiveTarget {
            target_ref: target_ref.to_owned(),
            element_id: format!("e{name}"),
            role: "button".to_owned(),
            name: Some(name.to_owned()),
            value: None,
            control_type: Some("button".to_owned()),
            bounds: None,
            enabled: true,
            visible: true,
            sensitive: false,
            focused: false,
            checked: None,
            selected: None,
            interactive: true,
            frame_id: "main".to_owned(),
            in_viewport: true,
            occluded: false,
            visibility_reason: None,
        };
        let previous = CurrentState {
            session_id: "ses_state".to_owned(),
            state_version: 1,
            target_revision: 1,
            url: "https://example.test".to_owned(),
            title: "Example".to_owned(),
            tabs: vec![BrowserTab {
                tab_id: "tab-1".to_owned(),
                url: "https://example.test".to_owned(),
                title: "Example".to_owned(),
                active: true,
            }],
            active_tab_id: "tab-1".to_owned(),
            native_dialogs: Vec::new(),
            native_dialog_evidence_fresh: true,
            downloads: Vec::new(),
            download_evidence_fresh: true,
            targets: vec![target("target:1:a", "A"), target("target:1:b", "B")],
            quality: StateQuality::Complete,
            content_hash: "old".to_owned(),
            document_ready_state: "interactive".to_owned(),
            network_quiet_millis: 250,
            network_evidence_fresh: true,
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
        assert_eq!(diff.document_ready_state, "interactive");
        assert_eq!(diff.network_quiet_millis, 250);
        assert!(diff.network_evidence_fresh);

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
                .active_page_websocket("ses_real_chromium")
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
            .start_safety_monitor("ses_real_chromium", BrowserTransactionPolicy::default())
            .await
            .unwrap();
        let mut safety_observer_ready = false;
        for _ in 0..50 {
            if collector
                .browser_safety_observation("ses_real_chromium")
                .await
                .fresh
            {
                safety_observer_ready = true;
                break;
            }
            tokio::time::sleep(Duration::from_millis(100)).await;
        }
        assert!(
            safety_observer_ready,
            "real Chromium safety observer did not enable Browser/Network domains"
        );

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

        collector.unregister_runtime("ses_real_chromium").await;
        let _ = child.start_kill();
        let _ = child.wait().await;
        page_task.abort();
        let _ = tokio::fs::remove_dir_all(profile).await;
    }

    #[tokio::test]
    #[ignore = "requires REAL_CHROMIUM_PATH and launches a local browser"]
    async fn manages_tabs_against_a_real_browser_runtime() {
        let chromium = std::env::var("REAL_CHROMIUM_PATH")
            .expect("REAL_CHROMIUM_PATH must point to Chromium or the integration fixture");
        let port_reservation = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let cdp_port = port_reservation.local_addr().unwrap().port();
        drop(port_reservation);
        let nonce = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        let profile = std::env::temp_dir().join(format!("browsercloud-tab-cdp-{nonce}"));
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
        collector
            .register_runtime("ses_real_tabs", &format!("http://127.0.0.1:{cdp_port}"))
            .await
            .unwrap();
        let mut initial = None;
        for _ in 0..100 {
            if let Ok(snapshot) = collector.tab_snapshot("ses_real_tabs").await {
                initial = Some(snapshot);
                break;
            }
            tokio::time::sleep(Duration::from_millis(100)).await;
        }
        let initial = initial.expect("Browser tab authority did not become ready");
        let original_tab = initial.active_tab_id;
        collector
            .start_safety_monitor("ses_real_tabs", BrowserTransactionPolicy::default())
            .await
            .unwrap();
        collector
            .set_tab_resource_policy(
                "ses_real_tabs",
                TabResourcePolicy {
                    tab_budget: 8,
                    freeze_background_tabs: false,
                    block_new_tabs: false,
                    paused_extension_ids: Vec::new(),
                },
            )
            .await
            .unwrap();
        collector
            .collect_current_state("ses_real_tabs")
            .await
            .unwrap();
        let opened_tab = collector
            .open_tab("ses_real_tabs", "https://support.example.test/ticket")
            .await
            .unwrap();
        let opened = collector.tab_snapshot("ses_real_tabs").await.unwrap();
        assert_eq!(opened.active_tab_id, opened_tab);
        assert_eq!(opened.tabs.len(), 2);
        let confirmed = collector
            .collect_action_confirmation("ses_real_tabs")
            .await
            .unwrap();
        assert_eq!(confirmed.active_tab_id, opened_tab);
        collector
            .activate_tab("ses_real_tabs", &original_tab)
            .await
            .unwrap();
        collector
            .close_tab("ses_real_tabs", &opened_tab)
            .await
            .unwrap();
        let closed = collector.tab_snapshot("ses_real_tabs").await.unwrap();
        assert_eq!(closed.active_tab_id, original_tab);
        assert_eq!(closed.tabs.len(), 1);

        collector.unregister_runtime("ses_real_tabs").await;
        let _ = child.start_kill();
        let _ = child.wait().await;
        let _ = tokio::fs::remove_dir_all(profile).await;
    }
}
