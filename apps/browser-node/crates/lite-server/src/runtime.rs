//! Multi-browser L0 Lite Session Manager built directly on Rust Browser Runtime crates.

use crate::backpressure::{
    read_host_io_psi_some10, BackpressureSnapshot, GradedBackpressure, PressureSample,
};
use crate::events::{EventBus, LiteMetrics};
use crate::profile::{restore_profile_checkpoint, save_profile_checkpoint};
use crate::security::{
    hex_encode, validate_identifier, validate_navigation_url, validate_proxy_url,
};
use crate::store::{
    AgentTokenRecord, CredentialMetadata, LiteStore, ProfileCheckpointRecord, SessionRecord,
    StoredCredential,
};
use crate::vault::{normalize_allowed_domain, url_matches_allowed_domain, CredentialVault};
use anyhow::{bail, ensure, Context, Result};
use chrono::{DateTime, Utc};
use futures_util::{SinkExt, StreamExt};
use input_sandbox::{CdpDesktopInput, DesktopInput, InputKey};
use runtime_supervisor::{
    BrowserIdentitySpec, ChromiumRuntimeSupervisor, RuntimeHealth, RuntimeResourceLimits,
    RuntimeSpec, RuntimeSupervisor,
};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use state_collector::{
    diff_states, BrowserStateCollector, CdpStateCollector, CurrentState, DiffOutcome,
    DropdownSelectionProbe,
};
use std::collections::HashMap;
use std::net::TcpListener;
use std::path::PathBuf;
use std::sync::Arc;
use std::time::{Duration, Instant};
use tokio::sync::Mutex;
use tokio::time::timeout;
use tokio_tungstenite::tungstenite::Message;
use uuid::Uuid;

const INPUT_WATCHDOG_IDLE_TIMEOUT: Duration = Duration::from_secs(30);
const DEFAULT_IDLE_TIMEOUT_SECS: u64 = 900;

#[derive(Debug, Clone)]
pub struct LiteConfig {
    pub data_dir: PathBuf,
    pub chromium_path: PathBuf,
    pub max_sessions: u32,
    pub warm_pool_size: usize,
    pub default_idle_timeout_seconds: u64,
    pub allow_private_network: bool,
    pub allowed_hosts: Vec<String>,
    pub allowed_origins: Vec<String>,
}

impl Default for LiteConfig {
    fn default() -> Self {
        Self {
            data_dir: PathBuf::from("./.local/lite-data"),
            chromium_path: PathBuf::from("/usr/bin/chromium"),
            max_sessions: 4,
            warm_pool_size: 0,
            default_idle_timeout_seconds: DEFAULT_IDLE_TIMEOUT_SECS,
            allow_private_network: false,
            allowed_hosts: vec!["127.0.0.1:*".to_owned(), "localhost:*".to_owned()],
            allowed_origins: vec![
                "http://127.0.0.1:*".to_owned(),
                "http://localhost:*".to_owned(),
            ],
        }
    }
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "snake_case")]
pub struct CreateSessionParams {
    pub name: Option<String>,
    pub profile_id: Option<String>,
    pub proxy_url: Option<String>,
    pub start_url: Option<String>,
    pub isolation_mode: Option<String>,
    pub idle_timeout_seconds: Option<u64>,
    #[serde(default = "default_true")]
    pub start: bool,
}

fn default_true() -> bool {
    true
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "snake_case")]
pub struct ObserveParams {
    #[serde(default = "default_observe_mode")]
    pub mode: String,
    pub query: Option<String>,
    #[serde(default)]
    pub roles: Vec<String>,
    #[serde(default)]
    pub include_hidden: bool,
    pub limit: Option<usize>,
    #[serde(default)]
    pub element_ids: Vec<String>,
    pub since_state_version: Option<u64>,
}

fn default_observe_mode() -> String {
    "tree".to_owned()
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "snake_case")]
pub struct ActionStepParams {
    pub action: String,
    pub target: Option<String>,
    pub target_revision: Option<u64>,
    pub end_target: Option<String>,
    pub value: Option<String>,
    pub value_ref: Option<String>,
    pub key: Option<String>,
    pub button: Option<u8>,
    pub delta_x: Option<i32>,
    pub delta_y: Option<i32>,
    pub duration_ms: Option<u32>,
    pub tab_id: Option<String>,
    pub tab_url: Option<String>,
    pub dialog_id: Option<String>,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "snake_case")]
pub struct ActBatchParams {
    pub steps: Vec<ActionStepParams>,
    #[serde(default = "default_true")]
    pub stop_on_error: bool,
    pub max_duration_ms: Option<u64>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct BatchStepOutcome {
    pub index: usize,
    pub action: String,
    pub status: String,
    pub duration_ms: u64,
    pub error: Option<String>,
    pub state_version: u64,
    pub target_revision: u64,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "snake_case")]
pub struct WaitParams {
    #[serde(default = "default_wait_mode")]
    pub mode: String,
    pub target: Option<String>,
    pub text: Option<String>,
    pub url_contains: Option<String>,
    pub title_contains: Option<String>,
    pub stable_frames: Option<u32>,
    pub timeout_ms: Option<u64>,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "snake_case")]
pub struct CreateCredentialParams {
    pub label: String,
    pub allowed_domain: String,
    pub secret_value: String,
    pub ttl_seconds: Option<u64>,
}

fn default_wait_mode() -> String {
    "element_ready".to_owned()
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "snake_case")]
pub struct ScreenshotParams {
    pub target: Option<String>,
    #[serde(default)]
    pub full_page: bool,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "snake_case")]
pub struct ProfileOpParams {
    pub action: String,
    pub checkpoint_id: Option<String>,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "snake_case")]
pub struct CloseSessionParams {
    #[serde(default = "default_true")]
    pub hibernate: bool,
}

struct WarmInstance {
    warm_id: String,
    cdp_endpoint: String,
    profile_dir: PathBuf,
    cache_dir: PathBuf,
}

struct LiveSessionState {
    runtime_key: String,
    cdp_endpoint: String,
    input: Arc<CdpDesktopInput>,
    last_states: Vec<CurrentState>,
    warm_handoff: Option<(PathBuf, PathBuf, PathBuf)>,
}

pub struct LiteRuntime {
    pub config: LiteConfig,
    pub store: Arc<LiteStore>,
    pub vault: Arc<CredentialVault>,
    pub events: Arc<EventBus>,
    pub metrics: Arc<LiteMetrics>,
    supervisor: Arc<ChromiumRuntimeSupervisor>,
    collector: Arc<CdpStateCollector>,
    live: Arc<Mutex<HashMap<String, LiveSessionState>>>,
    warm_pool: Arc<Mutex<Vec<WarmInstance>>>,
    backpressure: Arc<Mutex<GradedBackpressure>>,
    sys: Arc<Mutex<sysinfo::System>>,
}

impl LiteRuntime {
    pub fn new(config: LiteConfig, store: Arc<LiteStore>) -> Result<Self> {
        std::fs::create_dir_all(config.data_dir.join("profiles"))?;
        std::fs::create_dir_all(config.data_dir.join("cache"))?;
        std::fs::create_dir_all(config.data_dir.join("checkpoints"))?;
        let vault = Arc::new(CredentialVault::open_or_create(
            &config.data_dir.join("vault.key"),
        )?);
        let _ = store.mark_stale_running_hibernated();
        Ok(Self {
            supervisor: Arc::new(ChromiumRuntimeSupervisor::new(config.chromium_path.clone())),
            collector: Arc::new(CdpStateCollector::new()),
            live: Arc::new(Mutex::new(HashMap::new())),
            warm_pool: Arc::new(Mutex::new(Vec::new())),
            backpressure: Arc::new(Mutex::new(GradedBackpressure::new(
                12,
                Duration::from_secs(15),
            ))),
            sys: Arc::new(Mutex::new(sysinfo::System::new())),
            events: Arc::new(EventBus::new()),
            metrics: Arc::new(LiteMetrics::default()),
            vault,
            config,
            store,
        })
    }

    pub fn create_credential(
        &self,
        caller: &AgentTokenRecord,
        params: CreateCredentialParams,
    ) -> Result<CredentialMetadata> {
        let label = params.label.trim();
        ensure!(
            !label.is_empty() && label.len() <= 128,
            "CREDENTIAL_LABEL_INVALID"
        );
        let allowed_domain = normalize_allowed_domain(&params.allowed_domain)?;
        let credential_id = format!("cred_{}", &Uuid::new_v4().simple().to_string()[..16]);
        let ciphertext_b64 = self.vault.encrypt(
            &caller.agent_id,
            &credential_id,
            &allowed_domain,
            &params.secret_value,
        )?;
        let now = Utc::now();
        let expires_at = params.ttl_seconds.map(|ttl| {
            (now + chrono::Duration::seconds(ttl.clamp(60, 31_536_000) as i64)).to_rfc3339()
        });
        let meta = CredentialMetadata {
            credential_id: credential_id.clone(),
            owner_agent_id: caller.agent_id.clone(),
            label: label.to_owned(),
            allowed_domain: allowed_domain.clone(),
            expires_at,
            revoked: false,
            created_at: now.to_rfc3339(),
        };
        self.store.insert_credential(&StoredCredential {
            meta: meta.clone(),
            ciphertext_b64,
        })?;
        self.store.record_audit(
            &caller.agent_id,
            None,
            "CREDENTIAL_CREATE",
            "OK",
            Some(&allowed_domain),
        );
        self.events.publish(
            "credential.created",
            &caller.agent_id,
            None,
            None,
            json!({ "credentialId": credential_id, "allowedDomain": allowed_domain }),
        );
        Ok(meta)
    }

    pub fn list_credentials(&self, caller: &AgentTokenRecord) -> Result<Vec<CredentialMetadata>> {
        self.store.list_credentials_for_agent(&caller.agent_id)
    }

    pub fn revoke_credential(
        &self,
        caller: &AgentTokenRecord,
        credential_id: &str,
    ) -> Result<bool> {
        validate_identifier(credential_id, "cred_")?;
        let revoked = self
            .store
            .revoke_credential(credential_id, &caller.agent_id)?;
        if revoked {
            self.store.record_audit(
                &caller.agent_id,
                None,
                "CREDENTIAL_REVOKE",
                "OK",
                Some(credential_id),
            );
            self.events.publish(
                "credential.revoked",
                &caller.agent_id,
                None,
                None,
                json!({ "credentialId": credential_id }),
            );
        }
        Ok(revoked)
    }

    /// Resolves a `cred_...` value_ref into plaintext strictly in memory after validating owner,
    /// non-revocation, non-expiry, and exact/suffix domain match against the active page URL.
    pub fn resolve_credential_for_url(
        &self,
        caller: &AgentTokenRecord,
        credential_id: &str,
        page_url: &str,
    ) -> Result<String> {
        validate_identifier(credential_id, "cred_")?;
        let stored = self
            .store
            .get_credential(credential_id)?
            .ok_or_else(|| anyhow::anyhow!("CREDENTIAL_NOT_FOUND"))?;
        ensure!(
            stored.meta.owner_agent_id == caller.agent_id,
            "CREDENTIAL_ACCESS_DENIED"
        );
        ensure!(!stored.meta.revoked, "CREDENTIAL_REVOKED");
        if let Some(exp) = stored.meta.expires_at.as_deref() {
            if let Ok(dt) = DateTime::parse_from_rfc3339(exp) {
                ensure!(Utc::now() < dt.with_timezone(&Utc), "CREDENTIAL_EXPIRED");
            }
        }
        ensure!(
            url_matches_allowed_domain(page_url, &stored.meta.allowed_domain),
            "CREDENTIAL_DOMAIN_MISMATCH"
        );
        self.vault.decrypt(
            &stored.meta.owner_agent_id,
            &stored.meta.credential_id,
            &stored.meta.allowed_domain,
            &stored.ciphertext_b64,
        )
    }

    /// Resolves the private loopback active-page CDP WebSocket URL for an authenticated and
    /// owned session so `/api/v1/lite/sessions/:id/cdp` can proxy Playwright/CDP safely.
    pub async fn active_page_ws_for_agent(
        &self,
        caller: &AgentTokenRecord,
        session_id: &str,
    ) -> Result<String> {
        let record = self.require_owned_session(caller, session_id)?;
        self.ensure_running(&record).await?;
        let ws = self.collector.active_page_websocket(session_id).await?;
        self.store.record_audit(
            &caller.agent_id,
            Some(session_id),
            "CDP_PROXY_ATTACH",
            "OK",
            None,
        );
        Ok(ws)
    }

    pub async fn warm_pool_len(&self) -> usize {
        self.warm_pool.lock().await.len()
    }

    /// Replenishes the clean Warm Pool (Section 4.3). Every warm instance starts with a brand-new,
    /// empty profile directory and is never returned to the pool after being assigned to any user
    /// or session.
    pub async fn replenish_warm_pool(&self) {
        if self.config.warm_pool_size == 0 {
            return;
        }
        let bp = self.backpressure_snapshot().await;
        if !matches!(bp.state, crate::backpressure::BackpressureState::Normal) {
            return;
        }
        let needed = {
            let current = self.warm_pool.lock().await.len();
            self.config.warm_pool_size.saturating_sub(current)
        };
        for _ in 0..needed {
            let running = self.store.count_running_sessions_total().unwrap_or(0) as usize;
            let warm_now = self.warm_pool.lock().await.len();
            if running + warm_now >= self.config.max_sessions as usize {
                break;
            }
            let warm_id = format!("warm_{}", &Uuid::new_v4().simple().to_string()[..12]);
            let Ok(cdp_port) = allocate_ephemeral_port() else {
                break;
            };
            let profile_dir = self.config.data_dir.join("profiles").join(&warm_id);
            let cache_dir = self.config.data_dir.join("cache").join(&warm_id);
            if std::fs::create_dir_all(&profile_dir).is_err()
                || std::fs::create_dir_all(&cache_dir).is_err()
            {
                break;
            }
            let spec = RuntimeSpec {
                session_id: warm_id.clone(),
                runtime_build_id: "lite-chromium-warm-v1".to_owned(),
                profile_dir: profile_dir.clone(),
                cache_dir: cache_dir.clone(),
                proxy_server: None,
                display: String::new(),
                cdp_port,
                vnc_port: None,
                extension_dirs: Vec::new(),
                resource_limits: RuntimeResourceLimits::local_test_default(),
                browser_identity: BrowserIdentitySpec::default(),
            };
            match self.supervisor.start(spec).await {
                Ok(handle) => {
                    self.warm_pool.lock().await.push(WarmInstance {
                        warm_id,
                        cdp_endpoint: handle.cdp_endpoint,
                        profile_dir,
                        cache_dir,
                    });
                }
                Err(error) => {
                    tracing::debug!(error = %error, "Warm pool pre-warm skipped");
                    let _ = std::fs::remove_dir_all(&profile_dir);
                    let _ = std::fs::remove_dir_all(&cache_dir);
                    break;
                }
            }
        }
    }

    pub async fn sample_pressure(&self) -> BackpressureSnapshot {
        let mem_percent = {
            let mut sys = self.sys.lock().await;
            sys.refresh_memory();
            let total = sys.total_memory();
            if total == 0 {
                0.0
            } else {
                (sys.used_memory() as f64 / total as f64) * 100.0
            }
        };
        let storage_healthy = self.config.data_dir.is_dir();
        let io_psi = read_host_io_psi_some10();
        let mut bp = self.backpressure.lock().await;
        bp.observe(
            PressureSample {
                io_psi_some10: io_psi,
                memory_used_percent: mem_percent,
                storage_healthy,
            },
            Instant::now(),
        )
    }

    pub async fn backpressure_snapshot(&self) -> BackpressureSnapshot {
        self.backpressure.lock().await.snapshot()
    }

    pub async fn create_session(
        &self,
        caller: &AgentTokenRecord,
        params: CreateSessionParams,
    ) -> Result<Value> {
        let bp = self.backpressure_snapshot().await;
        ensure!(bp.accepts_new_sessions, "NODE_BACKPRESSURE_REJECTED");

        let agent_count = self
            .store
            .count_active_sessions_for_agent(&caller.agent_id)?;
        ensure!(
            agent_count < caller.max_sessions,
            "AGENT_SESSION_QUOTA_EXCEEDED"
        );
        if params.start {
            let total_running = self.store.count_running_sessions_total()?;
            ensure!(
                total_running < self.config.max_sessions,
                "NODE_MAX_SESSIONS_REACHED"
            );
        }

        let proxy_url = match params.proxy_url.as_deref() {
            Some(raw) if !raw.trim().is_empty() => Some(validate_proxy_url(raw)?),
            _ => None,
        };
        let start_url = match params.start_url.as_deref() {
            Some(raw) if !raw.trim().is_empty() => Some(validate_navigation_url(
                raw,
                self.config.allow_private_network,
            )?),
            _ => None,
        };
        let profile_id = match params.profile_id.as_deref() {
            Some(id) if !id.trim().is_empty() => {
                validate_identifier(id, "prf_")?;
                id.to_owned()
            }
            _ => format!("prf_{}", &Uuid::new_v4().simple().to_string()[..16]),
        };

        let session_id = format!("ses_{}", &Uuid::new_v4().simple().to_string()[..16]);
        let display_name = params
            .name
            .as_deref()
            .map(str::trim)
            .filter(|s| !s.is_empty())
            .unwrap_or(&session_id)
            .chars()
            .take(128)
            .collect::<String>();
        let idle_timeout_seconds = params
            .idle_timeout_seconds
            .unwrap_or(self.config.default_idle_timeout_seconds)
            .clamp(30, 86_400);
        let isolation_mode = match params
            .isolation_mode
            .as_deref()
            .map(str::trim)
            .filter(|s| !s.is_empty())
        {
            None | Some("process") => "process".to_owned(),
            Some("context") => "context".to_owned(),
            Some(_) => bail!("ISOLATION_MODE_INVALID"),
        };
        let now = Utc::now().to_rfc3339();
        let initial_state = if params.start {
            "CREATING"
        } else {
            "HIBERNATED"
        };
        let record = SessionRecord {
            session_id: session_id.clone(),
            owner_agent_id: caller.agent_id.clone(),
            display_name,
            profile_id: profile_id.clone(),
            state: initial_state.to_owned(),
            isolation_mode,
            proxy_url,
            current_url: start_url.clone(),
            idle_timeout_seconds,
            created_at: now.clone(),
            updated_at: now.clone(),
            last_activity_at: now,
        };
        self.store.insert_session(&record)?;
        self.store.record_audit(
            &caller.agent_id,
            Some(&session_id),
            "SESSION_CREATE",
            "OK",
            None,
        );

        if params.start {
            if let Err(error) = self.ensure_running(&record).await {
                let _ = self.store.update_session_state(&session_id, "FAILED", None);
                return Err(error);
            }
            if let Some(url) = start_url.as_deref() {
                self.collector.navigate(&session_id, url).await?;
                let _ = self.refresh_state(&session_id).await;
            }
        }

        let updated = self
            .store
            .get_session(&session_id)?
            .ok_or_else(|| anyhow::anyhow!("SESSION_NOT_FOUND"))?;
        self.events.publish(
            "session.created",
            &caller.agent_id,
            Some(&session_id),
            None,
            json!({ "state": updated.state, "profileId": updated.profile_id }),
        );
        Ok(json!({
            "session": updated,
            "backpressure": self.backpressure_snapshot().await,
        }))
    }

    pub async fn list_sessions(
        &self,
        caller: &AgentTokenRecord,
        include_closed: bool,
    ) -> Result<Value> {
        let sessions = self
            .store
            .list_sessions_for_agent(&caller.agent_id, include_closed)?;
        Ok(json!({
            "sessions": sessions,
            "count": sessions.len(),
            "backpressure": self.backpressure_snapshot().await,
        }))
    }

    pub async fn navigate(
        &self,
        caller: &AgentTokenRecord,
        session_id: &str,
        url: &str,
    ) -> Result<Value> {
        let record = self.require_owned_session(caller, session_id)?;
        let validated = validate_navigation_url(url, self.config.allow_private_network)?;
        self.ensure_running(&record).await?;
        self.collector.navigate(session_id, &validated).await?;
        let state = self.refresh_state(session_id).await?;
        self.store
            .touch_session_activity(session_id, Some(&state.url))?;
        self.store.record_audit(
            &caller.agent_id,
            Some(session_id),
            "NAVIGATE",
            "OK",
            Some(&state.url),
        );
        self.events.publish(
            "session.navigated",
            &caller.agent_id,
            Some(session_id),
            None,
            json!({ "url": state.url, "stateVersion": state.state_version }),
        );
        Ok(json!({
            "sessionId": session_id,
            "url": state.url,
            "title": state.title,
            "stateCursor": state_cursor(&state),
            "stateVersion": state.state_version,
            "targetRevision": state.target_revision,
        }))
    }

    pub async fn observe(
        &self,
        caller: &AgentTokenRecord,
        session_id: &str,
        params: ObserveParams,
    ) -> Result<Value> {
        let record = self.require_owned_session(caller, session_id)?;
        self.ensure_running(&record).await?;
        let state = self.refresh_state(session_id).await?;
        self.store
            .touch_session_activity(session_id, Some(&state.url))?;
        let cursor = state_cursor(&state);

        match params.mode.as_str() {
            "find" => {
                let query = params
                    .query
                    .as_deref()
                    .map(str::trim)
                    .filter(|q| !q.is_empty())
                    .ok_or_else(|| anyhow::anyhow!("FIND_QUERY_REQUIRED"))?
                    .to_ascii_lowercase();
                let roles: Vec<String> = params
                    .roles
                    .iter()
                    .map(|r| r.to_ascii_lowercase())
                    .collect();
                let limit = params.limit.unwrap_or(20).clamp(1, 100);
                let matches: Vec<_> = state
                    .targets
                    .iter()
                    .filter(|t| {
                        params.include_hidden
                            || t.visible
                            || (!t.occluded
                                && t.visibility_reason.as_deref() == Some("OUTSIDE_VIEWPORT"))
                    })
                    .filter(|t| roles.is_empty() || roles.contains(&t.role.to_ascii_lowercase()))
                    .filter(|t| target_matches_query(t, &query))
                    .take(limit)
                    .collect();
                Ok(json!({
                    "mode": "find",
                    "stateCursor": cursor,
                    "stateVersion": state.state_version,
                    "targetRevision": state.target_revision,
                    "url": state.url,
                    "title": state.title,
                    "targets": matches,
                }))
            }
            "inspect" => {
                ensure!(
                    !params.element_ids.is_empty() && params.element_ids.len() <= 50,
                    "INSPECT_ELEMENT_COUNT_INVALID"
                );
                let targets: Vec<_> = state
                    .targets
                    .iter()
                    .filter(|t| {
                        params
                            .element_ids
                            .iter()
                            .any(|id| id == &t.element_id || id == &t.target_ref)
                    })
                    .collect();
                Ok(json!({
                    "mode": "inspect",
                    "stateCursor": cursor,
                    "stateVersion": state.state_version,
                    "targetRevision": state.target_revision,
                    "targets": targets,
                }))
            }
            "diff" => {
                let since = params
                    .since_state_version
                    .ok_or_else(|| anyhow::anyhow!("SINCE_STATE_VERSION_REQUIRED"))?;
                let previous = {
                    let live = self.live.lock().await;
                    live.get(session_id)
                        .and_then(|s| s.last_states.iter().find(|st| st.state_version == since))
                        .cloned()
                };
                match previous {
                    Some(prev) => match diff_states(&prev, &state, 262_144, 128)? {
                        DiffOutcome::Diff(diff) => Ok(json!({
                            "mode": "diff",
                            "fullRequired": false,
                            "stateCursor": cursor,
                            "stateVersion": state.state_version,
                            "targetRevision": state.target_revision,
                            "diff": diff,
                        })),
                        DiffOutcome::Truncated(trunc) => Ok(json!({
                            "mode": "diff",
                            "fullRequired": true,
                            "reason": trunc.reason,
                            "stateCursor": cursor,
                            "stateVersion": state.state_version,
                            "targetRevision": state.target_revision,
                            "summary": compact_tree_payload(&state),
                        })),
                    },
                    None => Ok(json!({
                        "mode": "diff",
                        "fullRequired": true,
                        "reason": "BASELINE_EXPIRED",
                        "stateCursor": cursor,
                        "stateVersion": state.state_version,
                        "targetRevision": state.target_revision,
                        "summary": compact_tree_payload(&state),
                    })),
                }
            }
            _ => Ok(json!({
                "mode": "tree",
                "stateCursor": cursor,
                "stateVersion": state.state_version,
                "targetRevision": state.target_revision,
                "summary": compact_tree_payload(&state),
            })),
        }
    }

    pub async fn act(
        &self,
        caller: &AgentTokenRecord,
        session_id: &str,
        step: ActionStepParams,
    ) -> Result<Value> {
        let record = self.require_owned_session(caller, session_id)?;
        self.ensure_running(&record).await?;
        let started = Instant::now();
        let state = self.execute_single_step(caller, session_id, &step).await?;
        self.store
            .touch_session_activity(session_id, Some(&state.url))?;
        self.store.record_audit(
            &caller.agent_id,
            Some(session_id),
            &format!("ACT:{}", step.action),
            "OK",
            None,
        );
        self.events.publish(
            "session.acted",
            &caller.agent_id,
            Some(session_id),
            None,
            json!({ "action": step.action, "stateVersion": state.state_version }),
        );
        Ok(json!({
            "sessionId": session_id,
            "action": step.action,
            "status": "SUCCEEDED",
            "durationMs": started.elapsed().as_millis() as u64,
            "url": state.url,
            "title": state.title,
            "stateCursor": state_cursor(&state),
            "stateVersion": state.state_version,
            "targetRevision": state.target_revision,
        }))
    }

    pub async fn act_batch(
        &self,
        caller: &AgentTokenRecord,
        session_id: &str,
        params: ActBatchParams,
    ) -> Result<Value> {
        ensure!(
            !params.steps.is_empty() && params.steps.len() <= 20,
            "BATCH_STEP_COUNT_INVALID"
        );
        let record = self.require_owned_session(caller, session_id)?;
        self.ensure_running(&record).await?;
        let max_duration =
            Duration::from_millis(params.max_duration_ms.unwrap_or(60_000).min(180_000));
        let batch_start = Instant::now();

        let mut outcomes = Vec::with_capacity(params.steps.len());
        let mut latest_state: Option<CurrentState> = None;
        let mut all_succeeded = true;

        for (index, step) in params.steps.iter().enumerate() {
            if batch_start.elapsed() >= max_duration {
                all_succeeded = false;
                outcomes.push(BatchStepOutcome {
                    index,
                    action: step.action.clone(),
                    status: "TIMED_OUT".to_owned(),
                    duration_ms: 0,
                    error: Some("BATCH_MAX_DURATION_EXCEEDED".to_owned()),
                    state_version: latest_state.as_ref().map(|s| s.state_version).unwrap_or(0),
                    target_revision: latest_state
                        .as_ref()
                        .map(|s| s.target_revision)
                        .unwrap_or(0),
                });
                break;
            }
            let step_start = Instant::now();
            match self.execute_single_step(caller, session_id, step).await {
                Ok(state) => {
                    outcomes.push(BatchStepOutcome {
                        index,
                        action: step.action.clone(),
                        status: "SUCCEEDED".to_owned(),
                        duration_ms: step_start.elapsed().as_millis() as u64,
                        error: None,
                        state_version: state.state_version,
                        target_revision: state.target_revision,
                    });
                    latest_state = Some(state);
                }
                Err(error) => {
                    all_succeeded = false;
                    outcomes.push(BatchStepOutcome {
                        index,
                        action: step.action.clone(),
                        status: "FAILED".to_owned(),
                        duration_ms: step_start.elapsed().as_millis() as u64,
                        error: Some(error.to_string()),
                        state_version: latest_state.as_ref().map(|s| s.state_version).unwrap_or(0),
                        target_revision: latest_state
                            .as_ref()
                            .map(|s| s.target_revision)
                            .unwrap_or(0),
                    });
                    if params.stop_on_error {
                        break;
                    }
                }
            }
        }

        let final_state = match latest_state {
            Some(s) => s,
            None => self.refresh_state(session_id).await?,
        };
        self.store
            .touch_session_activity(session_id, Some(&final_state.url))?;
        self.store.record_audit(
            &caller.agent_id,
            Some(session_id),
            "ACT_BATCH",
            if all_succeeded {
                "OK"
            } else {
                "PARTIAL_OR_FAILED"
            },
            None,
        );

        Ok(json!({
            "sessionId": session_id,
            "succeeded": all_succeeded,
            "durationMs": batch_start.elapsed().as_millis() as u64,
            "steps": outcomes,
            "url": final_state.url,
            "title": final_state.title,
            "stateCursor": state_cursor(&final_state),
            "stateVersion": final_state.state_version,
            "targetRevision": final_state.target_revision,
        }))
    }

    pub async fn wait(
        &self,
        caller: &AgentTokenRecord,
        session_id: &str,
        params: WaitParams,
    ) -> Result<Value> {
        let record = self.require_owned_session(caller, session_id)?;
        self.ensure_running(&record).await?;
        let budget = Duration::from_millis(params.timeout_ms.unwrap_or(5_000).clamp(100, 30_000));
        let deadline = Instant::now() + budget;
        let mut previous_hash: Option<String> = None;
        let mut previous_visual_hash: Option<String> = None;
        let mut stable_visual_count: u32 = 0;
        let required_visual_frames = params.stable_frames.unwrap_or(2).clamp(2, 5);

        loop {
            let state = self.refresh_state(session_id).await?;
            let ready = match params.mode.as_str() {
                "element_ready" => {
                    let target_ref = params
                        .target
                        .as_deref()
                        .ok_or_else(|| anyhow::anyhow!("WAIT_TARGET_REQUIRED"))?;
                    state.targets.iter().any(|t| {
                        (t.element_id == target_ref || t.target_ref == target_ref)
                            && t.enabled
                            && !t.occluded
                            && (t.visible
                                || t.visibility_reason.as_deref() == Some("OUTSIDE_VIEWPORT"))
                    })
                }
                "dom_quiet" => {
                    previous_hash.as_deref() == Some(state.content_hash.as_str())
                        || (state.page_stability.evidence_fresh
                            && state.page_stability.dom_quiet_millis >= 300)
                }
                "visual_stable" => {
                    let ws_url = self.collector.active_page_websocket(session_id).await?;
                    let b64_png =
                        capture_cdp_screenshot(&ws_url, json!({ "format": "png" })).await?;
                    let mut hasher = Sha256::new();
                    hasher.update(b64_png.as_bytes());
                    let frame_hash = hex_encode(&hasher.finalize());
                    if previous_visual_hash.as_deref() == Some(frame_hash.as_str()) {
                        stable_visual_count = stable_visual_count.saturating_add(1);
                    } else {
                        previous_visual_hash = Some(frame_hash);
                        stable_visual_count = 1;
                    }
                    stable_visual_count >= required_visual_frames
                }
                "app_condition" => {
                    let has_any = params.url_contains.is_some()
                        || params.title_contains.is_some()
                        || params.text.is_some();
                    ensure!(has_any, "WAIT_CONDITION_REQUIRED");
                    let url_ok = params
                        .url_contains
                        .as_deref()
                        .map(|needle| state.url.contains(needle))
                        .unwrap_or(true);
                    let title_ok = params
                        .title_contains
                        .as_deref()
                        .map(|needle| state.title.contains(needle))
                        .unwrap_or(true);
                    let text_ok = params
                        .text
                        .as_deref()
                        .map(|needle| {
                            let needle_lc = needle.to_ascii_lowercase();
                            state.title.to_ascii_lowercase().contains(&needle_lc)
                                || state.targets.iter().any(|t| {
                                    t.visible
                                        && !t.sensitive
                                        && (t.name.as_deref().is_some_and(|n| {
                                            n.to_ascii_lowercase().contains(&needle_lc)
                                        }) || t.value.as_deref().is_some_and(|v| {
                                            v.to_ascii_lowercase().contains(&needle_lc)
                                        }))
                                })
                        })
                        .unwrap_or(true);
                    url_ok && title_ok && text_ok
                }
                _ => {
                    state.document_ready_state == "complete"
                        && (previous_hash.as_deref() == Some(state.content_hash.as_str())
                            || state.network_quiet_millis >= 250)
                }
            };
            if ready {
                return Ok(json!({
                    "satisfied": true,
                    "mode": params.mode,
                    "stateCursor": state_cursor(&state),
                    "stateVersion": state.state_version,
                    "targetRevision": state.target_revision,
                    "url": state.url,
                }));
            }
            if Instant::now() >= deadline {
                return Ok(json!({
                    "satisfied": false,
                    "mode": params.mode,
                    "reason": "WAIT_TIMEOUT",
                    "stateCursor": state_cursor(&state),
                    "stateVersion": state.state_version,
                    "targetRevision": state.target_revision,
                    "url": state.url,
                }));
            }
            previous_hash = Some(state.content_hash);
            tokio::time::sleep(Duration::from_millis(200)).await;
        }
    }

    pub async fn screenshot(
        &self,
        caller: &AgentTokenRecord,
        session_id: &str,
        params: ScreenshotParams,
    ) -> Result<(String, Value)> {
        let record = self.require_owned_session(caller, session_id)?;
        self.ensure_running(&record).await?;
        let state = self.refresh_state(session_id).await?;

        let clip = if let Some(target_ref) = params.target.as_deref() {
            let resolved = self
                .collector
                .resolve_target_for_action(session_id, target_ref, state.target_revision)
                .await?;
            Some(json!({
                "x": resolved.bounds.x.max(0.0),
                "y": resolved.bounds.y.max(0.0),
                "width": resolved.bounds.width.max(1.0),
                "height": resolved.bounds.height.max(1.0),
                "scale": 1.0,
            }))
        } else {
            None
        };

        let ws_url = self.collector.active_page_websocket(session_id).await?;
        let mut cdp_params = json!({ "format": "png" });
        if let Some(clip) = clip {
            cdp_params["clip"] = clip;
        } else if params.full_page {
            cdp_params["captureBeyondViewport"] = json!(true);
        }
        let b64_png = capture_cdp_screenshot(&ws_url, cdp_params).await?;
        self.store
            .touch_session_activity(session_id, Some(&state.url))?;
        let meta = json!({
            "sessionId": session_id,
            "mimeType": "image/png",
            "url": state.url,
            "stateCursor": state_cursor(&state),
            "bytesBase64": b64_png.len(),
        });
        Ok((b64_png, meta))
    }

    pub async fn profile_op(
        &self,
        caller: &AgentTokenRecord,
        session_id: &str,
        params: ProfileOpParams,
    ) -> Result<Value> {
        let record = self.require_owned_session(caller, session_id)?;
        let profile_dir = self.profile_dir(&record.profile_id);
        let checkpoints_root = self.config.data_dir.join("checkpoints");

        match params.action.as_str() {
            "list" => {
                let checkpoints = self
                    .store
                    .list_checkpoints(&record.profile_id, &caller.agent_id)?;
                Ok(json!({
                    "profileId": record.profile_id,
                    "checkpoints": checkpoints,
                }))
            }
            "save" => {
                let was_running = self.is_live(session_id).await;
                if was_running {
                    // Gracefully flush Chromium profile before snapshotting.
                    self.stop_live_session(session_id).await?;
                }
                let checkpoint_id = format!("chk_{}", &Uuid::new_v4().simple().to_string()[..16]);
                let manifest = save_profile_checkpoint(
                    &profile_dir,
                    &checkpoints_root,
                    &record.profile_id,
                    &checkpoint_id,
                )?;
                let cp_rec = ProfileCheckpointRecord {
                    checkpoint_id: manifest.checkpoint_id.clone(),
                    profile_id: record.profile_id.clone(),
                    owner_agent_id: caller.agent_id.clone(),
                    archive_sha256: manifest.archive_sha256.clone(),
                    archive_bytes: manifest.archive_bytes,
                    file_count: manifest.file_count,
                    created_at: Utc::now().to_rfc3339(),
                };
                self.store.insert_checkpoint(&cp_rec)?;
                if was_running {
                    self.ensure_running(&record).await?;
                }
                Ok(json!({
                    "saved": true,
                    "checkpoint": cp_rec,
                    "version": manifest.version,
                    "reusedChunks": manifest.reused_chunks,
                    "newChunks": manifest.new_chunks,
                    "newBytes": manifest.new_bytes,
                    "totalRawBytes": manifest.total_raw_bytes,
                    "excludedDirs": manifest.excluded_dirs,
                }))
            }
            "restore" => {
                let checkpoint_id = params
                    .checkpoint_id
                    .as_deref()
                    .ok_or_else(|| anyhow::anyhow!("CHECKPOINT_ID_REQUIRED"))?;
                validate_identifier(checkpoint_id, "chk_")?;
                let owned = self
                    .store
                    .list_checkpoints(&record.profile_id, &caller.agent_id)?
                    .into_iter()
                    .any(|c| c.checkpoint_id == checkpoint_id);
                ensure!(owned, "PROFILE_CHECKPOINT_NOT_FOUND");
                let was_running = self.is_live(session_id).await;
                if was_running {
                    self.stop_live_session(session_id).await?;
                }
                let manifest = restore_profile_checkpoint(
                    &profile_dir,
                    &checkpoints_root,
                    &record.profile_id,
                    checkpoint_id,
                )?;
                if was_running {
                    self.ensure_running(&record).await?;
                }
                Ok(json!({
                    "restored": true,
                    "profileId": record.profile_id,
                    "checkpointId": manifest.checkpoint_id,
                    "fileCount": manifest.file_count,
                }))
            }
            _ => bail!("PROFILE_ACTION_INVALID"),
        }
    }

    pub async fn close_session(
        &self,
        caller: &AgentTokenRecord,
        session_id: &str,
        params: CloseSessionParams,
    ) -> Result<Value> {
        let _record = self.require_owned_session(caller, session_id)?;
        let _ = self.stop_live_session(session_id).await;
        let next_state = if params.hibernate {
            "HIBERNATED"
        } else {
            "CLOSED"
        };
        self.store
            .update_session_state(session_id, next_state, None)?;
        self.store.record_audit(
            &caller.agent_id,
            Some(session_id),
            if params.hibernate {
                "SESSION_HIBERNATE"
            } else {
                "SESSION_CLOSE"
            },
            "OK",
            None,
        );
        self.events.publish(
            "session.closed",
            &caller.agent_id,
            Some(session_id),
            None,
            json!({ "state": next_state }),
        );
        Ok(json!({
            "sessionId": session_id,
            "state": next_state,
            "profilePreserved": true,
        }))
    }

    /// Background maintenance tick: releases stale mouse/key holds (>30s), detects crashed
    /// Chromium processes, hibernates idle sessions, and refreshes host backpressure.
    pub async fn maintenance_tick(&self) {
        let _ = self.sample_pressure().await;
        let live_entries: Vec<(String, String, Arc<CdpDesktopInput>)> = {
            let live = self.live.lock().await;
            live.iter()
                .map(|(id, s)| (id.clone(), s.runtime_key.clone(), s.input.clone()))
                .collect()
        };
        for (session_id, runtime_key, input) in live_entries {
            let _ = input.release_if_idle(INPUT_WATCHDOG_IDLE_TIMEOUT).await;
            if let Ok(health) = self.supervisor.health(&runtime_key).await {
                if matches!(health, RuntimeHealth::Crashed(_)) {
                    tracing::warn!(session_id, ?health, "Detected crashed Chromium runtime");
                    let _ = self.stop_live_session(&session_id).await;
                    let _ = self
                        .store
                        .update_session_state(&session_id, "CRASHED", None);
                }
            }
        }
        self.replenish_warm_pool().await;
        if let Ok(running) = self.store.list_running_sessions() {
            let now = Utc::now();
            for session in running {
                if let Ok(last) = DateTime::parse_from_rfc3339(&session.last_activity_at) {
                    let idle = now
                        .signed_duration_since(last.with_timezone(&Utc))
                        .num_seconds();
                    if idle >= session.idle_timeout_seconds as i64 {
                        tracing::info!(
                            session_id = session.session_id,
                            idle_seconds = idle,
                            "Auto-hibernating idle session"
                        );
                        let _ = self.stop_live_session(&session.session_id).await;
                        let _ = self.store.update_session_state(
                            &session.session_id,
                            "HIBERNATED",
                            None,
                        );
                    }
                }
            }
        }
    }

    pub async fn shutdown(&self) {
        let ids: Vec<String> = self.live.lock().await.keys().cloned().collect();
        for id in ids {
            let _ = self.stop_live_session(&id).await;
            let _ = self.store.update_session_state(&id, "HIBERNATED", None);
        }
        let warm: Vec<WarmInstance> = self.warm_pool.lock().await.drain(..).collect();
        for inst in warm {
            let _ = self.supervisor.stop(&inst.warm_id).await;
            let _ = std::fs::remove_dir_all(&inst.profile_dir);
            let _ = std::fs::remove_dir_all(&inst.cache_dir);
        }
        self.supervisor.stop_all().await;
    }

    fn require_owned_session(
        &self,
        caller: &AgentTokenRecord,
        session_id: &str,
    ) -> Result<SessionRecord> {
        validate_identifier(session_id, "ses_")?;
        let record = self
            .store
            .get_session(session_id)?
            .ok_or_else(|| anyhow::anyhow!("SESSION_NOT_FOUND"))?;
        ensure!(
            record.owner_agent_id == caller.agent_id || caller.has_scope("admin"),
            "SESSION_ACCESS_DENIED"
        );
        ensure!(record.state != "CLOSED", "SESSION_CLOSED");
        Ok(record)
    }

    fn profile_dir(&self, profile_id: &str) -> PathBuf {
        self.config.data_dir.join("profiles").join(profile_id)
    }

    async fn is_live(&self, session_id: &str) -> bool {
        self.live.lock().await.contains_key(session_id)
    }

    async fn ensure_running(&self, record: &SessionRecord) -> Result<()> {
        if let Some(runtime_key) = self
            .live
            .lock()
            .await
            .get(&record.session_id)
            .map(|s| s.runtime_key.clone())
        {
            if matches!(
                self.supervisor.health(&runtime_key).await,
                Ok(RuntimeHealth::Healthy | RuntimeHealth::Degraded(_))
            ) {
                return Ok(());
            }
            let _ = self.stop_live_session(&record.session_id).await;
        }

        let total_running = self.store.count_running_sessions_total()?;
        ensure!(
            total_running < self.config.max_sessions || record.state == "CREATING",
            "NODE_MAX_SESSIONS_REACHED"
        );

        let profile_dir = self.profile_dir(&record.profile_id);
        let profile_is_fresh = !profile_dir.exists()
            || std::fs::read_dir(&profile_dir)
                .map(|mut d| d.next().is_none())
                .unwrap_or(false);

        self.store
            .update_session_state(&record.session_id, "RESTORING", None)?;

        // Clean Warm Pool handoff: only for brand-new empty profiles with no custom proxy, and
        // each warm instance is consumed once and never returned to the pool.
        let claimed_warm = if profile_is_fresh && record.proxy_url.is_none() {
            let mut pool = self.warm_pool.lock().await;
            let mut valid = None;
            while let Some(candidate) = pool.pop() {
                if matches!(
                    self.supervisor.health(&candidate.warm_id).await,
                    Ok(RuntimeHealth::Healthy)
                ) {
                    valid = Some(candidate);
                    break;
                }
                let _ = self.supervisor.stop(&candidate.warm_id).await;
                let _ = std::fs::remove_dir_all(&candidate.profile_dir);
                let _ = std::fs::remove_dir_all(&candidate.cache_dir);
            }
            valid
        } else {
            None
        };

        let (runtime_key, cdp_endpoint, warm_handoff) = if let Some(warm) = claimed_warm {
            (
                warm.warm_id,
                warm.cdp_endpoint,
                Some((warm.profile_dir, profile_dir, warm.cache_dir)),
            )
        } else {
            let cdp_port = allocate_ephemeral_port()?;
            let cache_dir = self.config.data_dir.join("cache").join(&record.session_id);
            std::fs::create_dir_all(&profile_dir)?;
            std::fs::create_dir_all(&cache_dir)?;

            let spec = RuntimeSpec {
                session_id: record.session_id.clone(),
                runtime_build_id: "lite-chromium-v1".to_owned(),
                profile_dir,
                cache_dir,
                proxy_server: record.proxy_url.clone(),
                display: String::new(),
                cdp_port,
                vnc_port: None,
                extension_dirs: Vec::new(),
                resource_limits: RuntimeResourceLimits::local_test_default(),
                browser_identity: BrowserIdentitySpec::default(),
            };
            let handle = self.supervisor.start(spec).await?;
            (record.session_id.clone(), handle.cdp_endpoint, None)
        };

        self.collector
            .register_runtime(&record.session_id, &cdp_endpoint)
            .await?;
        let _ = self
            .collector
            .start_native_dialog_monitor(&record.session_id)
            .await;
        let input = Arc::new(CdpDesktopInput::connect(&cdp_endpoint).await?);

        {
            let mut live = self.live.lock().await;
            live.insert(
                record.session_id.clone(),
                LiveSessionState {
                    runtime_key,
                    cdp_endpoint,
                    input,
                    last_states: Vec::new(),
                    warm_handoff,
                },
            );
        }
        self.store
            .update_session_state(&record.session_id, "RUNNING", None)?;
        Ok(())
    }

    async fn stop_live_session(&self, session_id: &str) -> Result<()> {
        let removed = self.live.lock().await.remove(session_id);
        let (runtime_key, warm_handoff) = if let Some(live) = removed {
            let _ = live.input.release_all().await;
            let _ = live.cdp_endpoint;
            (live.runtime_key, live.warm_handoff)
        } else {
            (session_id.to_owned(), None)
        };
        self.collector.unregister_runtime(session_id).await;
        let _ = self.supervisor.stop(&runtime_key).await;
        if let Some((warm_profile_dir, target_profile_dir, warm_cache_dir)) = warm_handoff {
            if warm_profile_dir.exists() {
                if let Some(parent) = target_profile_dir.parent() {
                    let _ = std::fs::create_dir_all(parent);
                }
                if target_profile_dir.exists() {
                    let _ = std::fs::remove_dir_all(&target_profile_dir);
                }
                if std::fs::rename(&warm_profile_dir, &target_profile_dir).is_err() {
                    let _ = std::fs::create_dir_all(&target_profile_dir);
                }
            }
            let _ = std::fs::remove_dir_all(&warm_cache_dir);
            if let Some(warm_parent) = warm_cache_dir.parent() {
                let _ = std::fs::remove_dir(warm_parent);
            }
        }
        Ok(())
    }

    async fn refresh_state(&self, session_id: &str) -> Result<CurrentState> {
        let state = self.collector.collect_current_state(session_id).await?;
        if let Ok(ws) = self.collector.active_page_websocket(session_id).await {
            if let Some(live) = self.live.lock().await.get(session_id) {
                let _ = live.input.rebind(&ws).await;
            }
        }
        let mut live = self.live.lock().await;
        if let Some(entry) = live.get_mut(session_id) {
            entry.last_states.push(state.clone());
            if entry.last_states.len() > 8 {
                let drop_count = entry.last_states.len() - 8;
                entry.last_states.drain(0..drop_count);
            }
        }
        Ok(state)
    }

    async fn input_for(&self, session_id: &str) -> Result<Arc<CdpDesktopInput>> {
        let live = self.live.lock().await;
        live.get(session_id)
            .map(|s| s.input.clone())
            .ok_or_else(|| anyhow::anyhow!("SESSION_NOT_RUNNING"))
    }

    async fn execute_single_step(
        &self,
        caller: &AgentTokenRecord,
        session_id: &str,
        step: &ActionStepParams,
    ) -> Result<CurrentState> {
        let current = self.refresh_state(session_id).await?;
        let revision = step.target_revision.unwrap_or(current.target_revision);
        let input = self.input_for(session_id).await?;
        let action = step.action.trim().to_ascii_lowercase();

        match action.as_str() {
            "scroll" => {
                let dy = step.delta_y.unwrap_or(480).clamp(-2000, 2000);
                self.collector.scroll(session_id, dy).await?;
            }
            "open_tab" => {
                let raw_url = step
                    .tab_url
                    .as_deref()
                    .or(step.value.as_deref())
                    .ok_or_else(|| anyhow::anyhow!("TAB_URL_REQUIRED"))?;
                let validated =
                    validate_navigation_url(raw_url, self.config.allow_private_network)?;
                self.collector.open_tab(session_id, &validated).await?;
            }
            "switch_tab" => {
                let tab_id = step
                    .tab_id
                    .as_deref()
                    .ok_or_else(|| anyhow::anyhow!("TAB_ID_REQUIRED"))?;
                self.collector.activate_tab(session_id, tab_id).await?;
            }
            "close_tab" => {
                let tab_id = step
                    .tab_id
                    .as_deref()
                    .ok_or_else(|| anyhow::anyhow!("TAB_ID_REQUIRED"))?;
                self.collector.close_tab(session_id, tab_id).await?;
            }
            "accept_dialog" | "dismiss_dialog" => {
                let dialog_id = step
                    .dialog_id
                    .as_deref()
                    .ok_or_else(|| anyhow::anyhow!("DIALOG_ID_REQUIRED"))?;
                self.collector
                    .handle_native_dialog(
                        session_id,
                        dialog_id,
                        action == "accept_dialog",
                        step.value.as_deref(),
                    )
                    .await?;
            }
            "select" | "select_option" => {
                let target_ref = step
                    .target
                    .as_deref()
                    .ok_or_else(|| anyhow::anyhow!("TARGET_REQUIRED"))?;
                let value = step
                    .value
                    .as_deref()
                    .filter(|v| !v.is_empty())
                    .ok_or_else(|| anyhow::anyhow!("SELECT_VALUE_REQUIRED"))?;
                let target = self
                    .collector
                    .resolve_target_for_action(session_id, target_ref, revision)
                    .await?;
                let cx = (target.bounds.x + target.bounds.width / 2.0).round() as i32;
                let cy = (target.bounds.y + target.bounds.height / 2.0).round() as i32;
                match self
                    .collector
                    .select_option_probe(session_id, &target.path, value)
                    .await?
                {
                    DropdownSelectionProbe::NativeSelected => input.keep_alive().await,
                    DropdownSelectionProbe::NativeOptionNotFound => {
                        bail!("DROPDOWN_OPTION_NOT_FOUND")
                    }
                    DropdownSelectionProbe::CustomDropdown { is_text_input } => {
                        let mut seq = input.ledger_snapshot().await.last_sequence;
                        let mut next = || {
                            seq = seq.saturating_add(1);
                            seq
                        };
                        let res: Result<()> = async {
                            input.mouse_move(cx, cy, next()).await?;
                            input.mouse_click(0, 1, next()).await?;
                            tokio::time::sleep(Duration::from_millis(180)).await;
                            input.keep_alive().await;
                            if let Some((ox, oy)) = self
                                .collector
                                .find_visible_dropdown_option(session_id, value)
                                .await?
                            {
                                input.mouse_move(ox, oy, next()).await?;
                                input.mouse_click(0, 1, next()).await?;
                                return Ok(());
                            }
                            if is_text_input {
                                select_all_and_type(&input, value, &mut next).await?;
                                tokio::time::sleep(Duration::from_millis(180)).await;
                                if let Some((ox, oy)) = self
                                    .collector
                                    .find_visible_dropdown_option(session_id, value)
                                    .await?
                                {
                                    input.mouse_move(ox, oy, next()).await?;
                                    input.mouse_click(0, 1, next()).await?;
                                } else {
                                    input.key_down(InputKey::Enter, next()).await?;
                                    input.key_up(InputKey::Enter, next()).await?;
                                }
                                return Ok(());
                            }
                            tokio::time::sleep(Duration::from_millis(220)).await;
                            if let Some((ox, oy)) = self
                                .collector
                                .find_visible_dropdown_option(session_id, value)
                                .await?
                            {
                                input.mouse_move(ox, oy, next()).await?;
                                input.mouse_click(0, 1, next()).await?;
                                return Ok(());
                            }
                            bail!("DROPDOWN_OPTION_NOT_FOUND")
                        }
                        .await;
                        if let Err(err) = res {
                            let _ = input.release_all().await;
                            return Err(err);
                        }
                    }
                    DropdownSelectionProbe::FallbackKeyboard => {
                        let mut seq = input.ledger_snapshot().await.last_sequence;
                        let mut next = || {
                            seq = seq.saturating_add(1);
                            seq
                        };
                        input.mouse_move(cx, cy, next()).await?;
                        input.mouse_click(0, 1, next()).await?;
                        select_all_and_type(&input, value, &mut next).await?;
                        input.key_down(InputKey::Enter, next()).await?;
                        input.key_up(InputKey::Enter, next()).await?;
                    }
                }
            }
            "click" | "click_target" | "double_click" | "right_click" | "hover" | "check"
            | "uncheck" | "clear" | "type" | "type_text" | "fill" | "press_key" | "drag"
            | "drag_target" | "swipe" | "mouse_down" | "mouse_up" | "drop" => {
                let target_ref = step
                    .target
                    .as_deref()
                    .ok_or_else(|| anyhow::anyhow!("TARGET_REQUIRED"))?;
                let target = self
                    .collector
                    .resolve_target_for_action(session_id, target_ref, revision)
                    .await?;
                let cx = (target.bounds.x + target.bounds.width / 2.0).round() as i32;
                let cy = (target.bounds.y + target.bounds.height / 2.0).round() as i32;
                let mut seq = input.ledger_snapshot().await.last_sequence;
                let mut next = || {
                    seq = seq.saturating_add(1);
                    seq
                };

                let exec: Result<()> = async {
                    match action.as_str() {
                        "hover" => input.mouse_move(cx, cy, next()).await?,
                        "click" | "click_target" => {
                            input.mouse_move(cx, cy, next()).await?;
                            input.mouse_click(0, 1, next()).await?;
                        }
                        "double_click" => {
                            input.mouse_move(cx, cy, next()).await?;
                            input.mouse_click(0, 2, next()).await?;
                        }
                        "right_click" => {
                            input.mouse_move(cx, cy, next()).await?;
                            input.mouse_click(2, 1, next()).await?;
                        }
                        "check" => {
                            if target.checked != Some(true) {
                                input.mouse_move(cx, cy, next()).await?;
                                input.mouse_click(0, 1, next()).await?;
                            }
                        }
                        "uncheck" => {
                            if target.checked != Some(false) {
                                input.mouse_move(cx, cy, next()).await?;
                                input.mouse_click(0, 1, next()).await?;
                            }
                        }
                        "clear" => {
                            input.mouse_move(cx, cy, next()).await?;
                            input.mouse_click(0, 1, next()).await?;
                            input.key_down(InputKey::Control, next()).await?;
                            input
                                .key_down(InputKey::Character("a".to_owned()), next())
                                .await?;
                            input
                                .key_up(InputKey::Character("a".to_owned()), next())
                                .await?;
                            input.key_up(InputKey::Control, next()).await?;
                            input.key_down(InputKey::Backspace, next()).await?;
                            input.key_up(InputKey::Backspace, next()).await?;
                        }
                        "type" | "type_text" => {
                            let resolved_secret;
                            let text: &str = if let Some(cred_ref) = step
                                .value_ref
                                .as_deref()
                                .map(str::trim)
                                .filter(|s| !s.is_empty())
                            {
                                resolved_secret = self.resolve_credential_for_url(
                                    caller,
                                    cred_ref,
                                    &current.url,
                                )?;
                                &resolved_secret
                            } else {
                                step.value
                                    .as_deref()
                                    .filter(|v| !v.is_empty())
                                    .ok_or_else(|| anyhow::anyhow!("TEXT_VALUE_REQUIRED"))?
                            };
                            input.mouse_move(cx, cy, next()).await?;
                            input.mouse_click(0, 1, next()).await?;
                            input.insert_text(text, next()).await?;
                        }
                        "fill" => {
                            let resolved_secret;
                            let text: &str = if let Some(cred_ref) = step
                                .value_ref
                                .as_deref()
                                .map(str::trim)
                                .filter(|s| !s.is_empty())
                            {
                                resolved_secret = self.resolve_credential_for_url(
                                    caller,
                                    cred_ref,
                                    &current.url,
                                )?;
                                &resolved_secret
                            } else {
                                step.value
                                    .as_deref()
                                    .filter(|v| !v.is_empty())
                                    .ok_or_else(|| anyhow::anyhow!("TEXT_VALUE_REQUIRED"))?
                            };
                            input.mouse_move(cx, cy, next()).await?;
                            input.mouse_click(0, 1, next()).await?;
                            select_all_and_type(&input, text, &mut next).await?;
                        }
                        "press_key" => {
                            let key = parse_input_key(
                                step.key
                                    .as_deref()
                                    .or(step.value.as_deref())
                                    .ok_or_else(|| anyhow::anyhow!("KEY_REQUIRED"))?,
                            )?;
                            input.mouse_move(cx, cy, next()).await?;
                            input.mouse_click(0, 1, next()).await?;
                            input.key_down(key.clone(), next()).await?;
                            input.key_up(key, next()).await?;
                        }
                        "mouse_down" => {
                            let btn = step.button.unwrap_or(0);
                            input.mouse_move(cx, cy, next()).await?;
                            input.mouse_down(btn, next()).await?;
                        }
                        "mouse_up" | "drop" => {
                            let btn = step.button.unwrap_or(0);
                            input.mouse_move(cx, cy, next()).await?;
                            input.mouse_up(btn, next()).await?;
                        }
                        "drag" | "drag_target" => {
                            let (end_x, end_y) = if let Some(end_ref) = step.end_target.as_deref() {
                                let dest = self
                                    .collector
                                    .resolve_target_for_action(session_id, end_ref, revision)
                                    .await?;
                                (
                                    (dest.bounds.x + dest.bounds.width / 2.0).round() as i32,
                                    (dest.bounds.y + dest.bounds.height / 2.0).round() as i32,
                                )
                            } else {
                                let dx = step.delta_x.unwrap_or(0).clamp(-4000, 4000);
                                let dy = step.delta_y.unwrap_or(0).clamp(-4000, 4000);
                                ((cx + dx).max(0), (cy + dy).max(0))
                            };
                            let duration = step.duration_ms.unwrap_or(240).clamp(80, 5000);
                            input.mouse_move(cx, cy, next()).await?;
                            input.mouse_down(0, next()).await?;
                            if (cx, cy) == (end_x, end_y) {
                                let mut rem = u64::from(duration);
                                while rem > 0 {
                                    let slice = rem.min(250);
                                    tokio::time::sleep(Duration::from_millis(slice)).await;
                                    rem -= slice;
                                    input.keep_alive().await;
                                }
                            } else {
                                let step_ms = u64::from(duration / 8);
                                for i in 1..=8_i32 {
                                    let mx = cx + (end_x - cx) * i / 8;
                                    let my = cy + (end_y - cy) * i / 8;
                                    input.mouse_move(mx, my, next()).await?;
                                    if step_ms > 0 {
                                        tokio::time::sleep(Duration::from_millis(step_ms)).await;
                                    }
                                    input.keep_alive().await;
                                }
                            }
                            input.mouse_up(0, next()).await?;
                        }
                        "swipe" => {
                            let dx = step.delta_x.unwrap_or(0).clamp(-4000, 4000);
                            let dy = step.delta_y.unwrap_or(0).clamp(-4000, 4000);
                            let end_x = (cx + dx).max(0);
                            let end_y = (cy + dy).max(0);
                            let duration = step.duration_ms.unwrap_or(240).clamp(80, 5000);
                            let step_ms = u64::from(duration / 8);
                            input.touch_start(cx, cy, next()).await?;
                            for i in 1..=8_i32 {
                                let mx = cx + (end_x - cx) * i / 8;
                                let my = cy + (end_y - cy) * i / 8;
                                input.touch_move(mx, my, next()).await?;
                                if step_ms > 0 {
                                    tokio::time::sleep(Duration::from_millis(step_ms)).await;
                                }
                                input.keep_alive().await;
                            }
                            input.touch_end(next()).await?;
                        }
                        _ => bail!("ACTION_UNSUPPORTED"),
                    }
                    Ok(())
                }
                .await;
                if let Err(error) = exec {
                    let _ = input.release_all().await;
                    return Err(error);
                }
            }
            _ => bail!("ACTION_UNSUPPORTED"),
        }

        self.refresh_state(session_id).await
    }
}

async fn select_all_and_type<F: FnMut() -> u64>(
    input: &CdpDesktopInput,
    text: &str,
    next: &mut F,
) -> Result<()> {
    input.key_down(InputKey::Control, next()).await?;
    input
        .key_down(InputKey::Character("a".to_owned()), next())
        .await?;
    input
        .key_up(InputKey::Character("a".to_owned()), next())
        .await?;
    input.key_up(InputKey::Control, next()).await?;
    input.insert_text(text, next()).await?;
    Ok(())
}

fn parse_input_key(raw: &str) -> Result<InputKey> {
    match raw {
        "Enter" => Ok(InputKey::Enter),
        "Tab" => Ok(InputKey::Tab),
        "Escape" => Ok(InputKey::Escape),
        "Backspace" => Ok(InputKey::Backspace),
        "Delete" => Ok(InputKey::Delete),
        "ArrowUp" => Ok(InputKey::ArrowUp),
        "ArrowDown" => Ok(InputKey::ArrowDown),
        "ArrowLeft" => Ok(InputKey::ArrowLeft),
        "ArrowRight" => Ok(InputKey::ArrowRight),
        "Shift" => Ok(InputKey::Shift),
        "Control" => Ok(InputKey::Control),
        "Alt" => Ok(InputKey::Alt),
        "Meta" => Ok(InputKey::Meta),
        single if single.chars().count() == 1 => Ok(InputKey::Character(single.to_owned())),
        _ => bail!("KEY_UNSUPPORTED"),
    }
}

fn state_cursor(state: &CurrentState) -> String {
    format!(
        "{}:{}:{}",
        state.state_version, state.target_revision, state.content_hash
    )
}

fn target_matches_query(target: &state_collector::InteractiveTarget, lower_query: &str) -> bool {
    target.element_id.to_ascii_lowercase().contains(lower_query)
        || target.role.to_ascii_lowercase().contains(lower_query)
        || target
            .name
            .as_deref()
            .is_some_and(|n| n.to_ascii_lowercase().contains(lower_query))
        || (!target.sensitive
            && target
                .value
                .as_deref()
                .is_some_and(|v| v.to_ascii_lowercase().contains(lower_query)))
        || target
            .control_type
            .as_deref()
            .is_some_and(|c| c.to_ascii_lowercase().contains(lower_query))
}

fn compact_tree_payload(state: &CurrentState) -> Value {
    let mut text_parts = Vec::new();
    if !state.title.trim().is_empty() {
        text_parts.push(state.title.trim().to_owned());
    }
    for target in &state.targets {
        if target.visible && !target.sensitive {
            if let Some(name) = target
                .name
                .as_deref()
                .map(str::trim)
                .filter(|s| !s.is_empty())
            {
                text_parts.push(name.to_owned());
            }
            if let Some(val) = target
                .value
                .as_deref()
                .map(str::trim)
                .filter(|s| !s.is_empty())
            {
                text_parts.push(val.to_owned());
            }
        }
    }
    let mut visible_text = text_parts.join(" · ");
    if visible_text.len() > 4_000 {
        visible_text.truncate(4_000);
    }
    json!({
        "url": state.url,
        "title": state.title,
        "documentReadyState": state.document_ready_state,
        "visibleTextSummary": visible_text,
        "activeTabId": state.active_tab_id,
        "tabs": state.tabs,
        "nativeDialogs": state.native_dialogs,
        "targets": state.targets,
    })
}

fn allocate_ephemeral_port() -> Result<u16> {
    let listener = TcpListener::bind(("127.0.0.1", 0))
        .context("failed to allocate loopback ephemeral port for Chromium CDP")?;
    Ok(listener.local_addr()?.port())
}

async fn capture_cdp_screenshot(ws_url: &str, params: Value) -> Result<String> {
    let (mut socket, _) = timeout(
        Duration::from_secs(3),
        tokio_tungstenite::connect_async(ws_url),
    )
    .await
    .map_err(|_| anyhow::anyhow!("CDP_SCREENSHOT_CONNECT_TIMEOUT"))??;
    let payload = json!({
        "id": 901,
        "method": "Page.captureScreenshot",
        "params": params,
    });
    socket.send(Message::Text(payload.to_string())).await?;
    let deadline = tokio::time::Instant::now() + Duration::from_secs(5);
    while let Some(msg) = timeout(Duration::from_secs(5), socket.next())
        .await
        .map_err(|_| anyhow::anyhow!("CDP_SCREENSHOT_TIMEOUT"))?
    {
        if tokio::time::Instant::now() >= deadline {
            bail!("CDP_SCREENSHOT_TIMEOUT");
        }
        let Message::Text(text) = msg? else {
            continue;
        };
        let value: Value = serde_json::from_str(&text)?;
        if value.get("id").and_then(Value::as_i64) != Some(901) {
            continue;
        }
        if let Some(err) = value.get("error") {
            bail!("CDP_SCREENSHOT_FAILED: {err}");
        }
        let data = value
            .pointer("/result/data")
            .and_then(Value::as_str)
            .filter(|s| !s.is_empty())
            .ok_or_else(|| anyhow::anyhow!("CDP_SCREENSHOT_EMPTY"))?;
        return Ok(data.to_owned());
    }
    bail!("CDP_SCREENSHOT_CLOSED")
}
