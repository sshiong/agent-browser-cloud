//! Multi-browser L0 Lite Session Manager built directly on Rust Browser Runtime crates.

use crate::backpressure::{
    read_host_io_psi_some10, BackpressureSnapshot, GradedBackpressure, PressureSample,
};
use crate::egress::EgressGuardHandle;
use crate::events::{EventBus, LiteMetrics};
use crate::profile::{restore_profile_checkpoint, save_profile_checkpoint};
use crate::security::{
    hex_encode, normalize_private_host_rules, validate_identifier,
    validate_navigation_url_with_policy, validate_proxy_url,
};
use crate::store::{
    AgentTokenRecord, CredentialMetadata, LiteStore, ProfileCheckpointRecord, ProfileRecord,
    SessionRecord, StoredCredential,
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
    #[serde(default)]
    pub allowed_private_hosts: Vec<String>,
    pub idle_timeout_seconds: Option<u64>,
    #[serde(default = "default_true")]
    pub start: bool,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "snake_case")]
pub struct CreateProfileParams {
    pub name: String,
    #[serde(default)]
    pub allowed_private_hosts: Vec<String>,
    #[serde(default)]
    pub grant_agents: Vec<String>,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "snake_case")]
pub struct OpenProfileParams {
    pub name: Option<String>,
    pub proxy_url: Option<String>,
    pub start_url: Option<String>,
    #[serde(default)]
    pub allowed_private_hosts: Vec<String>,
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

#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum IntranetAuthMode {
    Once,
    Permanent,
}

#[derive(Debug, Clone)]
struct TemporaryPrivateHostGrant {
    rule: String,
    expires_at_mono: Option<Instant>,
    expires_at_utc: Option<DateTime<Utc>>,
}

struct WarmInstance {
    warm_id: String,
    cdp_endpoint: String,
    profile_dir: PathBuf,
    cache_dir: PathBuf,
    egress_guard: EgressGuardHandle,
}

struct LiveSessionState {
    runtime_key: String,
    cdp_endpoint: String,
    input: Arc<CdpDesktopInput>,
    last_states: Vec<CurrentState>,
    warm_handoff: Option<(PathBuf, PathBuf, PathBuf)>,
    egress_guard: Option<EgressGuardHandle>,
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
    session_locks: Arc<Mutex<HashMap<String, Arc<Mutex<()>>>>>,
    capacity_lock: Arc<Mutex<()>>,
    temporary_private_grants:
        Arc<std::sync::Mutex<HashMap<String, Vec<TemporaryPrivateHostGrant>>>>,
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
            session_locks: Arc::new(Mutex::new(HashMap::new())),
            capacity_lock: Arc::new(Mutex::new(())),
            temporary_private_grants: Arc::new(std::sync::Mutex::new(HashMap::new())),
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
            let Ok(egress_guard) =
                EgressGuardHandle::spawn(self.config.allow_private_network, Vec::new(), Vec::new())
                    .await
            else {
                break;
            };
            let spec = RuntimeSpec {
                session_id: warm_id.clone(),
                runtime_build_id: "lite-chromium-warm-v1".to_owned(),
                profile_dir: profile_dir.clone(),
                cache_dir: cache_dir.clone(),
                proxy_server: Some(egress_guard.proxy_url.clone()),
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
                        egress_guard,
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

        let requested_private_hosts = normalize_private_host_rules(&params.allowed_private_hosts)?;
        let proxy_url = match params.proxy_url.as_deref() {
            Some(raw) if !raw.trim().is_empty() => Some(validate_proxy_url(raw)?),
            _ => None,
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

        let now = Utc::now().to_rfc3339();
        let (profile_id, effective_private_hosts) = match params.profile_id.as_deref() {
            Some(id) if !id.trim().is_empty() => {
                validate_identifier(id, "prf_")?;
                if let Some(existing) = self.store.get_profile(id)? {
                    ensure!(
                        caller.has_scope("admin")
                            || self.store.has_active_profile_grant(id, &caller.agent_id)?,
                        "PROFILE_ACCESS_DENIED"
                    );
                    let merged = merge_private_hosts(
                        &existing.allowed_private_hosts,
                        &requested_private_hosts,
                    );
                    (id.to_owned(), merged)
                } else {
                    let owner_account = if caller.has_scope("admin") {
                        caller.agent_id.clone()
                    } else {
                        "agent_admin".to_owned()
                    };
                    self.store.upsert_profile(&ProfileRecord {
                        profile_id: id.to_owned(),
                        owner_account_id: owner_account,
                        display_name: display_name.clone(),
                        allowed_private_hosts: requested_private_hosts.clone(),
                        created_at: now.clone(),
                        updated_at: now.clone(),
                    })?;
                    if !caller.has_scope("admin") {
                        self.store
                            .grant_profile(id, &caller.agent_id, "agent_admin")?;
                    }
                    (id.to_owned(), requested_private_hosts.clone())
                }
            }
            _ => {
                let pid = format!("prf_{}", &Uuid::new_v4().simple().to_string()[..16]);
                let owner_account = if caller.has_scope("admin") {
                    caller.agent_id.clone()
                } else {
                    "agent_admin".to_owned()
                };
                self.store.upsert_profile(&ProfileRecord {
                    profile_id: pid.clone(),
                    owner_account_id: owner_account,
                    display_name: display_name.clone(),
                    allowed_private_hosts: requested_private_hosts.clone(),
                    created_at: now.clone(),
                    updated_at: now.clone(),
                })?;
                if !caller.has_scope("admin") {
                    self.store
                        .grant_profile(&pid, &caller.agent_id, "agent_admin")?;
                }
                (pid, requested_private_hosts.clone())
            }
        };

        let start_url = match params.start_url.as_deref() {
            Some(raw) if !raw.trim().is_empty() => Some(validate_navigation_url_with_policy(
                raw,
                self.config.allow_private_network,
                &effective_private_hosts,
            )?),
            _ => None,
        };

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

        // Pre-flight running-quota, single-writer, and node capacity checks when starting immediately.
        if params.start {
            let _cap = self.capacity_lock.lock().await;
            let token_running = self.store.count_running_sessions_for_token(
                &caller.token_id,
                &caller.agent_id,
                None,
            )?;
            ensure!(
                token_running < caller.max_sessions,
                "AGENT_SESSION_QUOTA_EXCEEDED"
            );
            if let Some(active) = self
                .store
                .find_active_session_for_profile(&profile_id, None)?
            {
                bail!("PROFILE_ALREADY_IN_USE:{}", active.session_id);
            }
            let total_running = self.store.count_running_sessions_total()?;
            ensure!(
                total_running < self.config.max_sessions,
                "NODE_MAX_SESSIONS_REACHED"
            );
        }

        let initial_state = if params.start {
            "CREATING"
        } else {
            "HIBERNATED"
        };
        let record = SessionRecord {
            session_id: session_id.clone(),
            owner_agent_id: caller.agent_id.clone(),
            owner_token_id: caller.token_id.clone(),
            display_name,
            profile_id: profile_id.clone(),
            state: initial_state.to_owned(),
            isolation_mode,
            proxy_url,
            current_url: start_url.clone(),
            allowed_private_hosts: effective_private_hosts,
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
        let sessions = if caller.has_scope("admin") {
            self.store.list_sessions_for_admin(include_closed)?
        } else {
            self.store
                .list_sessions_for_agent(&caller.agent_id, include_closed)?
        };
        Ok(json!({
            "sessions": sessions,
            "count": sessions.len(),
            "backpressure": self.backpressure_snapshot().await,
        }))
    }

    // ---------- Admin-Owned Profile Management, Authorization & Same-Browser Collaboration ----------

    pub fn create_profile(
        &self,
        caller: &AgentTokenRecord,
        params: CreateProfileParams,
    ) -> Result<Value> {
        ensure!(caller.has_scope("admin"), "ADMIN_SCOPE_REQUIRED");
        let display_name = params.name.trim();
        ensure!(
            !display_name.is_empty() && display_name.len() <= 128,
            "PROFILE_NAME_INVALID"
        );
        let allowed_private_hosts = normalize_private_host_rules(&params.allowed_private_hosts)?;
        let profile_id = format!("prf_{}", &Uuid::new_v4().simple().to_string()[..16]);
        let now = Utc::now().to_rfc3339();
        let profile = ProfileRecord {
            profile_id: profile_id.clone(),
            owner_account_id: caller.agent_id.clone(),
            display_name: display_name.to_owned(),
            allowed_private_hosts,
            created_at: now.clone(),
            updated_at: now,
        };
        self.store.upsert_profile(&profile)?;
        let mut granted = Vec::new();
        for agent in &params.grant_agents {
            let trimmed = agent.trim();
            if !trimmed.is_empty() && trimmed.len() <= 64 {
                self.store
                    .grant_profile(&profile_id, trimmed, &caller.agent_id)?;
                granted.push(trimmed.to_owned());
            }
        }
        self.store.record_audit(
            &caller.agent_id,
            None,
            "PROFILE_CREATE",
            "OK",
            Some(&profile_id),
        );
        Ok(json!({
            "profile": profile,
            "activeGrants": granted,
        }))
    }

    pub fn list_profiles(&self, caller: &AgentTokenRecord) -> Result<Value> {
        let is_admin = caller.has_scope("admin");
        let profiles = if is_admin {
            self.store.list_profiles_for_admin()?
        } else {
            self.store.list_profiles_for_agent(&caller.agent_id)?
        };
        let mut items = Vec::with_capacity(profiles.len());
        for profile in profiles {
            let active_session = self
                .store
                .find_active_session_for_profile(&profile.profile_id, None)?;
            let mut entry = json!({
                "profile": profile,
                "activeSessionId": active_session.as_ref().map(|s| s.session_id.clone()),
                "activeSessionState": active_session.as_ref().map(|s| s.state.clone()),
            });
            if is_admin {
                let grants = self
                    .store
                    .list_active_grants_for_profile(&profile.profile_id)?;
                entry["activeGrants"] = json!(grants);
            }
            items.push(entry);
        }
        Ok(json!({
            "profiles": items,
            "count": items.len(),
        }))
    }

    pub fn grant_profile(
        &self,
        caller: &AgentTokenRecord,
        profile_id: &str,
        grantee_agent_id: &str,
    ) -> Result<Value> {
        ensure!(caller.has_scope("admin"), "ADMIN_SCOPE_REQUIRED");
        validate_identifier(profile_id, "prf_")?;
        let grantee = grantee_agent_id.trim();
        ensure!(
            !grantee.is_empty() && grantee.len() <= 64,
            "AGENT_ID_INVALID"
        );
        ensure!(
            self.store.get_profile(profile_id)?.is_some(),
            "PROFILE_NOT_FOUND"
        );
        let grant = self
            .store
            .grant_profile(profile_id, grantee, &caller.agent_id)?;
        let active_grants = self.store.list_active_grants_for_profile(profile_id)?;
        self.store.record_audit(
            &caller.agent_id,
            None,
            "PROFILE_GRANT",
            "OK",
            Some(&format!("{profile_id}->{grantee}")),
        );
        self.events.publish(
            "profile.granted",
            grantee,
            None,
            None,
            json!({ "profileId": profile_id, "granteeAgentId": grantee }),
        );
        Ok(json!({
            "grant": grant,
            "activeGrants": active_grants,
        }))
    }

    pub fn revoke_profile_grant(
        &self,
        caller: &AgentTokenRecord,
        profile_id: &str,
        grantee_agent_id: &str,
    ) -> Result<Value> {
        ensure!(caller.has_scope("admin"), "ADMIN_SCOPE_REQUIRED");
        validate_identifier(profile_id, "prf_")?;
        let grantee = grantee_agent_id.trim();
        ensure!(
            !grantee.is_empty() && grantee.len() <= 64,
            "AGENT_ID_INVALID"
        );
        ensure!(
            self.store.get_profile(profile_id)?.is_some(),
            "PROFILE_NOT_FOUND"
        );
        let revoked = self.store.revoke_profile_grant(profile_id, grantee)?;
        let active_grants = self.store.list_active_grants_for_profile(profile_id)?;
        self.store.record_audit(
            &caller.agent_id,
            None,
            "PROFILE_REVOKE",
            "OK",
            Some(&format!("{profile_id}-x-{grantee}")),
        );
        self.events.publish(
            "profile.revoked",
            grantee,
            None,
            None,
            json!({ "profileId": profile_id, "granteeAgentId": grantee }),
        );
        Ok(json!({
            "revoked": revoked,
            "profileId": profile_id,
            "granteeAgentId": grantee,
            "activeGrants": active_grants,
        }))
    }

    /// Opens an authorized Profile. If a browser instance is already writing to this Profile,
    /// attaches the caller (e.g., Admin + Agent collaboration) to that SAME running browser
    /// instead of spawning a second writer that would corrupt login cookies/storage.
    pub async fn open_profile(
        &self,
        caller: &AgentTokenRecord,
        profile_id: &str,
        params: OpenProfileParams,
    ) -> Result<Value> {
        validate_identifier(profile_id, "prf_")?;
        let profile = self
            .store
            .get_profile(profile_id)?
            .ok_or_else(|| anyhow::anyhow!("PROFILE_NOT_FOUND"))?;
        ensure!(
            caller.has_scope("admin")
                || self
                    .store
                    .has_active_profile_grant(profile_id, &caller.agent_id)?,
            "PROFILE_ACCESS_DENIED"
        );

        if let Some(existing) = self
            .store
            .find_active_session_for_profile(profile_id, None)?
        {
            self.store.record_audit(
                &caller.agent_id,
                Some(&existing.session_id),
                "SESSION_COLLABORATE",
                "OK",
                Some(profile_id),
            );
            return Ok(json!({
                "session": existing,
                "collaborating": true,
                "singleWriterSessionId": existing.session_id,
                "backpressure": self.backpressure_snapshot().await,
            }));
        }

        let mut created = self
            .create_session(
                caller,
                CreateSessionParams {
                    name: params.name.or(Some(profile.display_name)),
                    profile_id: Some(profile_id.to_owned()),
                    proxy_url: params.proxy_url,
                    start_url: params.start_url,
                    isolation_mode: None,
                    allowed_private_hosts: params.allowed_private_hosts,
                    idle_timeout_seconds: params.idle_timeout_seconds,
                    start: params.start,
                },
            )
            .await?;
        let sid = created["session"]["sessionId"]
            .as_str()
            .unwrap_or_default()
            .to_owned();
        created["collaborating"] = json!(false);
        created["singleWriterSessionId"] = json!(sid);
        Ok(created)
    }

    pub fn list_profile_checkpoints(
        &self,
        caller: &AgentTokenRecord,
        profile_id: &str,
    ) -> Result<Value> {
        validate_identifier(profile_id, "prf_")?;
        ensure!(
            self.store.get_profile(profile_id)?.is_some(),
            "PROFILE_NOT_FOUND"
        );
        ensure!(
            caller.has_scope("admin")
                || self
                    .store
                    .has_active_profile_grant(profile_id, &caller.agent_id)?,
            "PROFILE_ACCESS_DENIED"
        );
        let checkpoints = self.store.list_checkpoints_for_profile(profile_id)?;
        Ok(json!({
            "profileId": profile_id,
            "checkpoints": checkpoints,
        }))
    }

    pub async fn restore_profile_by_id(
        &self,
        caller: &AgentTokenRecord,
        profile_id: &str,
        checkpoint_id: &str,
    ) -> Result<Value> {
        validate_identifier(profile_id, "prf_")?;
        validate_identifier(checkpoint_id, "chk_")?;
        ensure!(
            self.store.get_profile(profile_id)?.is_some(),
            "PROFILE_NOT_FOUND"
        );
        ensure!(
            caller.has_scope("admin")
                || self
                    .store
                    .has_active_profile_grant(profile_id, &caller.agent_id)?,
            "PROFILE_ACCESS_DENIED"
        );
        if let Some(active) = self
            .store
            .find_active_session_for_profile(profile_id, None)?
        {
            return self
                .profile_op(
                    caller,
                    &active.session_id,
                    ProfileOpParams {
                        action: "restore".to_owned(),
                        checkpoint_id: Some(checkpoint_id.to_owned()),
                    },
                )
                .await;
        }
        let exists = self
            .store
            .list_checkpoints_for_profile(profile_id)?
            .into_iter()
            .any(|c| c.checkpoint_id == checkpoint_id);
        ensure!(exists, "PROFILE_CHECKPOINT_NOT_FOUND");
        let manifest = restore_profile_checkpoint(
            &self.profile_dir(profile_id),
            &self.config.data_dir.join("checkpoints"),
            profile_id,
            checkpoint_id,
        )?;
        self.store.record_audit(
            &caller.agent_id,
            None,
            "PROFILE_RESTORE",
            "OK",
            Some(checkpoint_id),
        );
        Ok(json!({
            "restored": true,
            "profileId": profile_id,
            "checkpointId": manifest.checkpoint_id,
            "fileCount": manifest.file_count,
        }))
    }

    pub async fn get_session_network_policy(
        &self,
        caller: &AgentTokenRecord,
        session_id: &str,
    ) -> Result<Value> {
        let record = self.require_owned_session(caller, session_id)?;
        Ok(self.build_network_policy_view(&record))
    }

    /// Authorizes one or more intranet hosts/CIDRs on a session in either:
    /// - `IntranetAuthMode::Once`: temporary in-memory grant valid ONLY for the current running
    ///   session (and optional `ttl_seconds`); automatically cleared on hibernate/close/restart.
    /// - `IntranetAuthMode::Permanent`: persisted into the Profile & Session allowlist so
    ///   subsequent visits, restarts, and re-grants never prompt again.
    pub async fn authorize_session_private_hosts(
        &self,
        caller: &AgentTokenRecord,
        session_id: &str,
        raw_hosts: &[String],
        mode: IntranetAuthMode,
        ttl_seconds: Option<u64>,
    ) -> Result<Value> {
        let record = self.require_owned_session(caller, session_id)?;
        let normalized = normalize_private_host_rules(raw_hosts)?;
        ensure!(!normalized.is_empty(), "PRIVATE_HOST_REQUIRED");

        match mode {
            IntranetAuthMode::Permanent => {
                let merged =
                    merge_private_hosts(&self.permanent_private_hosts(&record), &normalized);
                self.store
                    .update_session_private_hosts(session_id, &merged)?;
                let _ = self
                    .store
                    .update_profile_private_hosts(&record.profile_id, &merged);
                self.sync_live_egress_policy(session_id, &record.profile_id, &merged)
                    .await;
                self.store.record_audit(
                    &caller.agent_id,
                    Some(session_id),
                    "NETWORK_POLICY_AUTHORIZE_PERMANENT",
                    "OK",
                    Some(&normalized.join(",")),
                );
            }
            IntranetAuthMode::Once => {
                let now_mono = Instant::now();
                let now_utc = Utc::now();
                let (exp_mono, exp_utc) = match ttl_seconds {
                    Some(secs) => {
                        let clamped = secs.clamp(10, 86_400);
                        (
                            Some(now_mono + Duration::from_secs(clamped)),
                            Some(now_utc + chrono::Duration::seconds(clamped as i64)),
                        )
                    }
                    None => (None, None),
                };
                {
                    let mut map = self
                        .temporary_private_grants
                        .lock()
                        .expect("temporary_private_grants mutex poisoned");
                    let list = map.entry(session_id.to_owned()).or_default();
                    list.retain(|g| g.expires_at_mono.is_none_or(|e| now_mono < e));
                    for rule in &normalized {
                        list.retain(|g| g.rule != *rule);
                        list.push(TemporaryPrivateHostGrant {
                            rule: rule.clone(),
                            expires_at_mono: exp_mono,
                            expires_at_utc: exp_utc,
                        });
                    }
                }
                let perm = self.permanent_private_hosts(&record);
                self.sync_live_egress_policy(session_id, &record.profile_id, &perm)
                    .await;
                self.store.record_audit(
                    &caller.agent_id,
                    Some(session_id),
                    "NETWORK_POLICY_AUTHORIZE_ONCE",
                    "OK",
                    Some(&normalized.join(",")),
                );
            }
        }

        let refreshed = self.require_owned_session(caller, session_id)?;
        let mut view = self.build_network_policy_view(&refreshed);
        view["mode"] = json!(mode);
        Ok(view)
    }

    pub async fn revoke_session_private_hosts(
        &self,
        caller: &AgentTokenRecord,
        session_id: &str,
        raw_hosts: &[String],
        mode: Option<IntranetAuthMode>,
    ) -> Result<Value> {
        let record = self.require_owned_session(caller, session_id)?;
        let targets: Vec<String> = raw_hosts
            .iter()
            .map(|r| r.trim().to_ascii_lowercase())
            .filter(|r| !r.is_empty())
            .collect();
        ensure!(!targets.is_empty(), "PRIVATE_HOST_REQUIRED");

        if matches!(mode, None | Some(IntranetAuthMode::Once)) {
            let mut map = self
                .temporary_private_grants
                .lock()
                .expect("temporary_private_grants mutex poisoned");
            if let Some(list) = map.get_mut(session_id) {
                list.retain(|g| !targets.contains(&g.rule));
            }
        }
        if matches!(mode, None | Some(IntranetAuthMode::Permanent)) {
            let mut perm = self.permanent_private_hosts(&record);
            perm.retain(|r| !targets.contains(r));
            self.store.update_session_private_hosts(session_id, &perm)?;
            let _ = self
                .store
                .update_profile_private_hosts(&record.profile_id, &perm);
            self.sync_live_egress_policy(session_id, &record.profile_id, &perm)
                .await;
        } else {
            let perm = self.permanent_private_hosts(&record);
            self.sync_live_egress_policy(session_id, &record.profile_id, &perm)
                .await;
        }
        self.store.record_audit(
            &caller.agent_id,
            Some(session_id),
            "NETWORK_POLICY_REVOKE",
            "OK",
            Some(&targets.join(",")),
        );
        let refreshed = self.require_owned_session(caller, session_id)?;
        Ok(self.build_network_policy_view(&refreshed))
    }

    pub async fn update_session_network_policy(
        &self,
        caller: &AgentTokenRecord,
        session_id: &str,
        raw_hosts: &[String],
    ) -> Result<Value> {
        let record = self.require_owned_session(caller, session_id)?;
        let normalized = normalize_private_host_rules(raw_hosts)?;
        self.store
            .update_session_private_hosts(session_id, &normalized)?;
        let _ = self
            .store
            .update_profile_private_hosts(&record.profile_id, &normalized);
        self.sync_live_egress_policy(session_id, &record.profile_id, &normalized)
            .await;
        self.store.record_audit(
            &caller.agent_id,
            Some(session_id),
            "NETWORK_POLICY_UPDATE",
            "OK",
            Some(&normalized.join(",")),
        );
        let refreshed = self.require_owned_session(caller, session_id)?;
        Ok(self.build_network_policy_view(&refreshed))
    }

    pub async fn update_profile_network_policy(
        &self,
        caller: &AgentTokenRecord,
        profile_id: &str,
        raw_hosts: &[String],
    ) -> Result<Value> {
        ensure!(caller.has_scope("admin"), "ADMIN_SCOPE_REQUIRED");
        validate_identifier(profile_id, "prf_")?;
        ensure!(
            self.store.get_profile(profile_id)?.is_some(),
            "PROFILE_NOT_FOUND"
        );
        let normalized = normalize_private_host_rules(raw_hosts)?;
        self.store
            .update_profile_private_hosts(profile_id, &normalized)?;
        if let Some(active) = self
            .store
            .find_active_session_for_profile(profile_id, None)?
        {
            let _ = self
                .store
                .update_session_private_hosts(&active.session_id, &normalized);
            self.sync_live_egress_policy(&active.session_id, profile_id, &normalized)
                .await;
        }
        self.store.record_audit(
            &caller.agent_id,
            None,
            "PROFILE_NETWORK_POLICY_UPDATE",
            "OK",
            Some(&normalized.join(",")),
        );
        Ok(json!({
            "profileId": profile_id,
            "mode": "permanent",
            "permanentAllowedPrivateHosts": normalized,
            "allowedPrivateHosts": normalized,
        }))
    }

    async fn sync_live_egress_policy(
        &self,
        session_id: &str,
        _profile_id: &str,
        permanent_hosts: &[String],
    ) {
        let temp_pairs = self.active_temporary_egress_pairs(session_id);
        if let Some(live) = self.live.lock().await.get(session_id) {
            if let Some(guard) = live.egress_guard.as_ref() {
                guard.set_policy(permanent_hosts.to_vec(), temp_pairs);
            }
        }
    }

    fn permanent_private_hosts(&self, record: &SessionRecord) -> Vec<String> {
        let profile_hosts = self
            .store
            .get_profile(&record.profile_id)
            .ok()
            .flatten()
            .map(|p| p.allowed_private_hosts)
            .unwrap_or_default();
        merge_private_hosts(&profile_hosts, &record.allowed_private_hosts)
    }

    fn active_temporary_grants(&self, session_id: &str) -> Vec<TemporaryPrivateHostGrant> {
        let now = Instant::now();
        let mut map = self
            .temporary_private_grants
            .lock()
            .expect("temporary_private_grants mutex poisoned");
        let Some(list) = map.get_mut(session_id) else {
            return Vec::new();
        };
        list.retain(|g| g.expires_at_mono.is_none_or(|e| now < e));
        list.clone()
    }

    fn active_temporary_egress_pairs(&self, session_id: &str) -> Vec<(String, Option<Instant>)> {
        self.active_temporary_grants(session_id)
            .into_iter()
            .map(|g| (g.rule, g.expires_at_mono))
            .collect()
    }

    fn effective_private_hosts(&self, record: &SessionRecord) -> Vec<String> {
        let perm = self.permanent_private_hosts(record);
        let temp: Vec<String> = self
            .active_temporary_grants(&record.session_id)
            .into_iter()
            .map(|g| g.rule)
            .collect();
        merge_private_hosts(&perm, &temp)
    }

    fn build_network_policy_view(&self, record: &SessionRecord) -> Value {
        let permanent = self.permanent_private_hosts(record);
        let once_grants = self.active_temporary_grants(&record.session_id);
        let once_json: Vec<Value> = once_grants
            .iter()
            .map(|g| {
                json!({
                    "rule": g.rule,
                    "mode": "once",
                    "expiresAt": g.expires_at_utc.map(|dt| dt.to_rfc3339()),
                })
            })
            .collect();
        let once_rules: Vec<String> = once_grants.into_iter().map(|g| g.rule).collect();
        let effective = merge_private_hosts(&permanent, &once_rules);
        json!({
            "sessionId": record.session_id,
            "profileId": record.profile_id,
            "permanentAllowedPrivateHosts": permanent,
            "onceAllowedPrivateHosts": once_json,
            "effectiveAllowedPrivateHosts": effective,
            "allowedPrivateHosts": effective,
        })
    }

    pub async fn navigate(
        &self,
        caller: &AgentTokenRecord,
        session_id: &str,
        url: &str,
    ) -> Result<Value> {
        let record = self.require_owned_session(caller, session_id)?;
        let allowed_private = self.effective_private_hosts(&record);
        let validated = match validate_navigation_url_with_policy(
            url,
            self.config.allow_private_network,
            &allowed_private,
        ) {
            Ok(v) => v,
            Err(err) => {
                // Reject ONLY this network request; keep browser session and task running untouched!
                let reason = err.to_string();
                self.store.record_audit(
                    &caller.agent_id,
                    Some(session_id),
                    "NAVIGATION_PRIVATE_DENIED",
                    "DENIED",
                    Some(&reason),
                );
                self.events.publish(
                    "network.request_blocked",
                    &caller.agent_id,
                    Some(session_id),
                    None,
                    json!({
                        "url": url,
                        "reason": reason,
                        "sessionPreserved": true,
                        "authorizationOptions": ["once", "permanent"],
                    }),
                );
                return Err(err);
            }
        };
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
                    .list_checkpoints_for_profile(&record.profile_id)?;
                Ok(json!({
                    "profileId": record.profile_id,
                    "checkpoints": checkpoints,
                }))
            }
            "save" => {
                let sess_lock = self.session_lock(session_id).await;
                let _sess_guard = sess_lock.lock().await;
                let record = self.require_owned_session(caller, session_id)?;
                let was_running = self.is_live(session_id).await || is_running_state(&record.state);
                if was_running {
                    // Gracefully flush Chromium profile and mark HIBERNATED before snapshotting so
                    // DB running-slot accounting stays accurate even at max_sessions capacity.
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
                    let latest = self.require_owned_session(caller, session_id)?;
                    self.ensure_running_locked(&latest).await?;
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
                let sess_lock = self.session_lock(session_id).await;
                let _sess_guard = sess_lock.lock().await;
                let record = self.require_owned_session(caller, session_id)?;
                let owned = self
                    .store
                    .list_checkpoints_for_profile(&record.profile_id)?
                    .into_iter()
                    .any(|c| c.checkpoint_id == checkpoint_id);
                ensure!(owned, "PROFILE_CHECKPOINT_NOT_FOUND");
                let was_running = self.is_live(session_id).await || is_running_state(&record.state);
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
                    let latest = self.require_owned_session(caller, session_id)?;
                    self.ensure_running_locked(&latest).await?;
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
        let sess_lock = self.session_lock(session_id).await;
        let _sess_guard = sess_lock.lock().await;
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
        ensure!(record.state != "CLOSED", "SESSION_CLOSED");
        if caller.has_scope("admin") {
            return Ok(record);
        }
        let profile_managed = self.store.get_profile(&record.profile_id)?.is_some();
        let has_profile_grant = if profile_managed {
            self.store
                .has_active_profile_grant(&record.profile_id, &caller.agent_id)?
        } else {
            true
        };
        if record.owner_agent_id == caller.agent_id {
            ensure!(has_profile_grant, "PROFILE_ACCESS_DENIED");
            return Ok(record);
        }
        // Authorized grantee may collaborate on an already-running browser bound to that Profile.
        if profile_managed && has_profile_grant && is_running_state(&record.state) {
            return Ok(record);
        }
        bail!("SESSION_ACCESS_DENIED");
    }

    fn profile_dir(&self, profile_id: &str) -> PathBuf {
        self.config.data_dir.join("profiles").join(profile_id)
    }

    async fn is_live(&self, session_id: &str) -> bool {
        self.live.lock().await.contains_key(session_id)
    }

    async fn session_lock(&self, session_id: &str) -> Arc<Mutex<()>> {
        let mut locks = self.session_locks.lock().await;
        locks
            .entry(session_id.to_owned())
            .or_insert_with(|| Arc::new(Mutex::new(())))
            .clone()
    }

    async fn ensure_running(&self, record: &SessionRecord) -> Result<()> {
        let sess_lock = self.session_lock(&record.session_id).await;
        let _sess_guard = sess_lock.lock().await;
        self.ensure_running_locked(record).await
    }

    async fn ensure_running_locked(&self, record: &SessionRecord) -> Result<()> {
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

        // Atomically verify per-token running quota, single-writer profile lock, and node capacity
        // excluding this session itself, then reserve the slot via DB transition to RESTORING.
        {
            let _cap = self.capacity_lock.lock().await;
            if !record.owner_token_id.is_empty() {
                if let Some(owner_token) = self.store.get_token_by_id(&record.owner_token_id)? {
                    let token_running = self.store.count_running_sessions_for_token(
                        &owner_token.token_id,
                        &owner_token.agent_id,
                        Some(&record.session_id),
                    )?;
                    ensure!(
                        token_running < owner_token.max_sessions,
                        "AGENT_SESSION_QUOTA_EXCEEDED"
                    );
                }
            }
            if let Some(other) = self
                .store
                .find_active_session_for_profile(&record.profile_id, Some(&record.session_id))?
            {
                bail!("PROFILE_ALREADY_IN_USE:{}", other.session_id);
            }
            let other_running = self
                .store
                .count_running_sessions_excluding(Some(&record.session_id))?;
            ensure!(
                other_running < self.config.max_sessions,
                "NODE_MAX_SESSIONS_REACHED"
            );
            self.store
                .update_session_state(&record.session_id, "RESTORING", None)?;
        }

        let permanent_private_hosts = self.permanent_private_hosts(record);
        let temporary_egress_pairs = self.active_temporary_egress_pairs(&record.session_id);

        let launch_result: Result<()> = async {
            let profile_dir = self.profile_dir(&record.profile_id);
            let profile_is_fresh = !profile_dir.exists()
                || std::fs::read_dir(&profile_dir)
                    .map(|mut d| d.next().is_none())
                    .unwrap_or(false);

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

            let (runtime_key, cdp_endpoint, warm_handoff, egress_guard) =
                if let Some(warm) = claimed_warm {
                    warm.egress_guard.set_policy(
                        permanent_private_hosts.clone(),
                        temporary_egress_pairs.clone(),
                    );
                    (
                        warm.warm_id,
                        warm.cdp_endpoint,
                        Some((warm.profile_dir, profile_dir, warm.cache_dir)),
                        Some(warm.egress_guard),
                    )
                } else {
                    let cdp_port = allocate_ephemeral_port()?;
                    let cache_dir = self.config.data_dir.join("cache").join(&record.session_id);
                    std::fs::create_dir_all(&profile_dir)?;
                    std::fs::create_dir_all(&cache_dir)?;

                    let (proxy_server, guard) = if let Some(user_proxy) = record.proxy_url.clone() {
                        (Some(user_proxy), None)
                    } else {
                        let guard = EgressGuardHandle::spawn(
                            self.config.allow_private_network,
                            permanent_private_hosts.clone(),
                            temporary_egress_pairs.clone(),
                        )
                        .await?;
                        let url = guard.proxy_url.clone();
                        (Some(url), Some(guard))
                    };

                    let spec = RuntimeSpec {
                        session_id: record.session_id.clone(),
                        runtime_build_id: "lite-chromium-v1".to_owned(),
                        profile_dir,
                        cache_dir,
                        proxy_server,
                        display: String::new(),
                        cdp_port,
                        vnc_port: None,
                        extension_dirs: Vec::new(),
                        resource_limits: RuntimeResourceLimits::local_test_default(),
                        browser_identity: BrowserIdentitySpec::default(),
                    };
                    let handle = self.supervisor.start(spec).await?;
                    (record.session_id.clone(), handle.cdp_endpoint, None, guard)
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
                        egress_guard,
                    },
                );
            }
            self.store
                .update_session_state(&record.session_id, "RUNNING", None)?;
            Ok(())
        }
        .await;

        if let Err(err) = launch_result {
            let _ = self.stop_live_session(&record.session_id).await;
            return Err(err);
        }
        Ok(())
    }

    async fn stop_live_session(&self, session_id: &str) -> Result<()> {
        if let Ok(mut grants) = self.temporary_private_grants.lock() {
            grants.remove(session_id);
        }
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
        if let Ok(Some(current)) = self.store.get_session(session_id) {
            if is_running_state(&current.state) {
                let _ = self
                    .store
                    .update_session_state(session_id, "HIBERNATED", None);
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
                let allowed_private = self
                    .store
                    .get_session(session_id)?
                    .map(|rec| self.effective_private_hosts(&rec))
                    .unwrap_or_default();
                let validated = validate_navigation_url_with_policy(
                    raw_url,
                    self.config.allow_private_network,
                    &allowed_private,
                )?;
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

fn merge_private_hosts(base: &[String], extra: &[String]) -> Vec<String> {
    let mut out = Vec::with_capacity(base.len() + extra.len());
    for item in base.iter().chain(extra.iter()) {
        if !out.contains(item) {
            out.push(item.clone());
        }
    }
    out
}

fn is_running_state(state: &str) -> bool {
    matches!(state, "CREATING" | "RUNNING" | "IDLE" | "RESTORING")
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

#[cfg(test)]
mod quota_and_save_tests {
    use super::*;

    #[tokio::test]
    async fn full_capacity_save_and_restore_do_not_self_block_and_rollback_on_launch_failure() {
        let dir = std::env::temp_dir().join(format!("lite-save-quota-{}", Uuid::new_v4().simple()));
        let store = Arc::new(LiteStore::open(&dir.join("lite.sqlite3")).unwrap());
        let admin = store.ensure_bootstrap_token(None, 2).unwrap().unwrap();
        let cfg = LiteConfig {
            data_dir: dir.clone(),
            chromium_path: PathBuf::from("/nonexistent/chromium-test-bin"),
            max_sessions: 1,
            ..LiteConfig::default()
        };
        let rt = Arc::new(LiteRuntime::new(cfg, store.clone()).unwrap());

        // Create a hibernated session, write a profile file, then simulate RUNNING at full 1/1 capacity.
        let created = rt
            .create_session(
                &admin.token,
                CreateSessionParams {
                    name: Some("FullCap".to_owned()),
                    profile_id: None,
                    proxy_url: None,
                    start_url: None,
                    isolation_mode: None,
                    allowed_private_hosts: Vec::new(),
                    idle_timeout_seconds: None,
                    start: false,
                },
            )
            .await
            .unwrap();
        let sid = created["session"]["sessionId"].as_str().unwrap().to_owned();
        let pid = created["session"]["profileId"].as_str().unwrap().to_owned();
        std::fs::create_dir_all(dir.join("profiles").join(&pid)).unwrap();
        std::fs::write(dir.join("profiles").join(&pid).join("Cookies"), b"login=ok").unwrap();
        store.update_session_state(&sid, "RUNNING", None).unwrap();
        assert_eq!(store.count_running_sessions_total().unwrap(), 1);
        assert_eq!(
            store.count_running_sessions_excluding(Some(&sid)).unwrap(),
            0
        );

        // Save while at 1/1 capacity: must NOT fail with NODE_MAX_SESSIONS_REACHED.
        // Because chromium_path is nonexistent, restart fails at binary spawn, and DB MUST roll back to HIBERNATED.
        let save_err = rt
            .profile_op(
                &admin.token,
                &sid,
                ProfileOpParams {
                    action: "save".to_owned(),
                    checkpoint_id: None,
                },
            )
            .await
            .unwrap_err()
            .to_string();
        assert!(
            !save_err.contains("NODE_MAX_SESSIONS_REACHED"),
            "unexpected self-quota block on save: {save_err}"
        );
        assert_eq!(
            store.get_session(&sid).unwrap().unwrap().state,
            "HIBERNATED"
        );
        assert_eq!(store.count_running_sessions_total().unwrap(), 0);

        // Checkpoint WAS saved cleanly; simulate RUNNING at 1/1 again and verify restore also avoids self-block.
        let checkpoints = store.list_checkpoints(&pid, &admin.token.agent_id).unwrap();
        assert_eq!(checkpoints.len(), 1);
        store.update_session_state(&sid, "RUNNING", None).unwrap();
        let restore_err = rt
            .profile_op(
                &admin.token,
                &sid,
                ProfileOpParams {
                    action: "restore".to_owned(),
                    checkpoint_id: Some(checkpoints[0].checkpoint_id.clone()),
                },
            )
            .await
            .unwrap_err()
            .to_string();
        assert!(
            !restore_err.contains("NODE_MAX_SESSIONS_REACHED"),
            "unexpected self-quota block on restore: {restore_err}"
        );
        assert_eq!(
            store.get_session(&sid).unwrap().unwrap().state,
            "HIBERNATED"
        );
        assert_eq!(store.count_running_sessions_total().unwrap(), 0);

        // When ANOTHER session truly occupies the 1/1 slot, a second session MUST get NODE_MAX_SESSIONS_REACHED.
        let second = rt
            .create_session(
                &admin.token,
                CreateSessionParams {
                    name: Some("Second".to_owned()),
                    profile_id: None,
                    proxy_url: None,
                    start_url: None,
                    isolation_mode: None,
                    allowed_private_hosts: Vec::new(),
                    idle_timeout_seconds: None,
                    start: false,
                },
            )
            .await
            .unwrap();
        let sid2 = second["session"]["sessionId"].as_str().unwrap().to_owned();
        store.update_session_state(&sid, "RUNNING", None).unwrap();
        let rec2 = store.get_session(&sid2).unwrap().unwrap();
        let cap_err = rt.ensure_running(&rec2).await.unwrap_err().to_string();
        assert!(
            cap_err.contains("NODE_MAX_SESSIONS_REACHED"),
            "err={cap_err}"
        );

        let _ = std::fs::remove_dir_all(&dir);
    }
}
