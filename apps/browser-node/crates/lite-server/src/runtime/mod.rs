//! Multi-browser L0 Lite Session Manager built directly on Rust Browser Runtime crates.

mod actions;
mod network_policy;
mod profiles;

use crate::backpressure::{
    read_host_io_psi_some10, BackpressureSnapshot, GradedBackpressure, PressureSample,
};
use crate::egress::EgressGuardHandle;
use crate::events::{EventBus, LiteMetrics};
use crate::security::{
    normalize_private_host_rules, validate_identifier, validate_navigation_url_with_policy,
    validate_proxy_url,
};
use crate::store::{
    AgentTokenRecord, CredentialMetadata, LiteStore, ProfileRecord, SessionRecord, StoredCredential,
};
use crate::vault::{normalize_allowed_domain, url_matches_allowed_domain, CredentialVault};
use anyhow::{bail, ensure, Context, Result};
use chrono::{DateTime, Utc};
use input_sandbox::{CdpDesktopInput, DesktopInput};
use runtime_supervisor::{
    BrowserIdentitySpec, ChromiumRuntimeSupervisor, RuntimeHealth, RuntimeResourceLimits,
    RuntimeSpec, RuntimeSupervisor,
};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use state_collector::{BrowserStateCollector, CdpStateCollector, CurrentState};
use std::collections::HashMap;
use std::net::TcpListener;
use std::path::PathBuf;
use std::sync::Arc;
use std::time::{Duration, Instant};
use tokio::sync::Mutex;
use uuid::Uuid;

const DEFAULT_INPUT_WATCHDOG_IDLE_TIMEOUT_SECS: u64 = 120;
const DEFAULT_IDLE_TIMEOUT_SECS: u64 = 900;

fn input_watchdog_idle_timeout() -> Duration {
    let secs = std::env::var("INPUT_WATCHDOG_IDLE_TIMEOUT_SECONDS")
        .ok()
        .and_then(|v| v.parse::<u64>().ok())
        .unwrap_or(DEFAULT_INPUT_WATCHDOG_IDLE_TIMEOUT_SECS)
        .clamp(15, 600);
    Duration::from_secs(secs)
}

/// RAII guard tracking an in-flight operation (wait, action, observe, navigate, screenshot,
/// profile save/restore, or live CDP WebSocket proxy) so `maintenance_tick` never auto-hibernates
/// an active session mid-operation.
pub(crate) struct ActiveOpGuard {
    session_id: String,
    in_flight: Arc<std::sync::Mutex<HashMap<String, usize>>>,
    store: Arc<LiteStore>,
}

impl Drop for ActiveOpGuard {
    fn drop(&mut self) {
        if let Ok(mut map) = self.in_flight.lock() {
            if let Some(count) = map.get_mut(&self.session_id) {
                *count = count.saturating_sub(1);
                if *count == 0 {
                    map.remove(&self.session_id);
                }
            }
        }
        let _ = self.store.touch_session_activity(&self.session_id, None);
    }
}

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
    /// Per-Profile async lock acquired BEFORE `session_locks` to serialize open/save/restore/close
    /// on the same Profile while keeping different Profiles 100% parallel.
    profile_locks: Arc<Mutex<HashMap<String, Arc<Mutex<()>>>>>,
    session_locks: Arc<Mutex<HashMap<String, Arc<Mutex<()>>>>>,
    in_flight_ops: Arc<std::sync::Mutex<HashMap<String, usize>>>,
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
            profile_locks: Arc::new(Mutex::new(HashMap::new())),
            session_locks: Arc::new(Mutex::new(HashMap::new())),
            in_flight_ops: Arc::new(std::sync::Mutex::new(HashMap::new())),
            capacity_lock: Arc::new(Mutex::new(())),
            temporary_private_grants: Arc::new(std::sync::Mutex::new(HashMap::new())),
            warm_pool: Arc::new(Mutex::new(Vec::new())),
            backpressure: Arc::new(Mutex::new(GradedBackpressure::new(
                12,
                Duration::from_secs(15),
            ))),
            sys: Arc::new(Mutex::new(sysinfo::System::new_with_specifics(
                sysinfo::RefreshKind::nothing()
                    .with_memory(sysinfo::MemoryRefreshKind::everything()),
            ))),
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

        if !params.allowed_private_hosts.is_empty() && !caller.has_scope("admin") {
            bail!("ADMIN_SCOPE_REQUIRED");
        }
        let target_profile_id = match params.profile_id.as_deref() {
            Some(id) if !id.trim().is_empty() => {
                validate_identifier(id, "prf_")?;
                id.to_owned()
            }
            _ => format!("prf_{}", &Uuid::new_v4().simple().to_string()[..16]),
        };

        let prof_lock = self.profile_lock(&target_profile_id).await;
        let _prof_guard = prof_lock.lock().await;
        self.create_session_profile_locked(caller, params, target_profile_id)
            .await
    }

    pub(super) async fn create_session_profile_locked(
        &self,
        caller: &AgentTokenRecord,
        params: CreateSessionParams,
        profile_id: String,
    ) -> Result<Value> {
        let bp = self.backpressure_snapshot().await;
        ensure!(bp.accepts_new_sessions, "NODE_BACKPRESSURE_REJECTED");

        if !params.allowed_private_hosts.is_empty() && !caller.has_scope("admin") {
            bail!("ADMIN_SCOPE_REQUIRED");
        }
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
        let profile_and_request_hosts =
            if let Some(existing) = self.store.get_profile(&profile_id)? {
                ensure!(
                    caller.has_scope("admin")
                        || self
                            .store
                            .has_active_profile_grant(&profile_id, &caller.agent_id)?,
                    "PROFILE_ACCESS_DENIED"
                );
                merge_private_hosts(&existing.allowed_private_hosts, &requested_private_hosts)
            } else {
                let owner_account = if caller.has_scope("admin") {
                    caller.agent_id.clone()
                } else {
                    "agent_admin".to_owned()
                };
                self.store.upsert_profile(&ProfileRecord {
                    profile_id: profile_id.clone(),
                    owner_account_id: owner_account,
                    display_name: display_name.clone(),
                    allowed_private_hosts: requested_private_hosts.clone(),
                    created_at: now.clone(),
                    updated_at: now.clone(),
                })?;
                if !caller.has_scope("admin") {
                    self.store
                        .grant_profile(&profile_id, &caller.agent_id, "agent_admin")?;
                }
                requested_private_hosts.clone()
            };

        let effective_private_hosts =
            merge_private_hosts(&caller.allowed_private_hosts, &profile_and_request_hosts);

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
            allowed_private_hosts: profile_and_request_hosts,
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
            let sess_lock = self.session_lock(&session_id).await;
            let _sess_guard = sess_lock.lock().await;
            if let Err(error) = self.ensure_running_locked(&record).await {
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

    pub async fn close_session(
        &self,
        caller: &AgentTokenRecord,
        session_id: &str,
        params: CloseSessionParams,
    ) -> Result<Value> {
        let initial = self.require_owned_session(caller, session_id)?;
        let prof_lock = self.profile_lock(&initial.profile_id).await;
        let _prof_guard = prof_lock.lock().await;
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
        drop(_sess_guard);
        drop(sess_lock);
        drop(_prof_guard);
        drop(prof_lock);
        if !params.hibernate {
            self.prune_idle_session_locks().await;
        }
        Ok(json!({
            "sessionId": session_id,
            "state": next_state,
            "profilePreserved": true,
        }))
    }

    async fn prune_idle_session_locks(&self) {
        let live_keys: std::collections::HashSet<String> =
            self.live.lock().await.keys().cloned().collect();
        {
            let mut locks = self.session_locks.lock().await;
            locks.retain(|id, lock| live_keys.contains(id) || Arc::strong_count(lock) > 1);
        }
        {
            let mut plocks = self.profile_locks.lock().await;
            plocks.retain(|_, lock| Arc::strong_count(lock) > 1);
        }
    }

    /// Background maintenance tick: releases stale mouse/key holds, detects crashed Chromium
    /// processes, hibernates truly idle sessions (skipping any session with active in-flight
    /// waits/actions/CDP proxy), prunes idle locks & old audit rows, and refreshes host
    /// backpressure.
    pub async fn maintenance_tick(&self) {
        let _ = self.sample_pressure().await;
        let watchdog_timeout = input_watchdog_idle_timeout();
        let live_entries: Vec<(String, String, Arc<CdpDesktopInput>)> = {
            let live = self.live.lock().await;
            live.iter()
                .map(|(id, s)| (id.clone(), s.runtime_key.clone(), s.input.clone()))
                .collect()
        };
        for (session_id, runtime_key, input) in live_entries {
            let _ = input.release_if_idle(watchdog_timeout).await;
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
                if self.has_in_flight_ops(&session.session_id) {
                    let _ = self.store.touch_session_activity(&session.session_id, None);
                    continue;
                }
                let Ok(last) = DateTime::parse_from_rfc3339(&session.last_activity_at) else {
                    continue;
                };
                let idle = now
                    .signed_duration_since(last.with_timezone(&Utc))
                    .num_seconds();
                if idle < session.idle_timeout_seconds as i64 {
                    continue;
                }
                // Acquire locks non-blockingly in canonical order (profile -> session); if busy,
                // an operation/save/restore is actively running so skip auto-hibernation.
                let prof_lock = self.profile_lock(&session.profile_id).await;
                let Ok(_prof_guard) = prof_lock.try_lock() else {
                    continue;
                };
                let sess_lock = self.session_lock(&session.session_id).await;
                let Ok(_sess_guard) = sess_lock.try_lock() else {
                    continue;
                };
                if self.has_in_flight_ops(&session.session_id) {
                    let _ = self.store.touch_session_activity(&session.session_id, None);
                    continue;
                }
                // Re-read authoritative last_activity_at under lock to eliminate TOCTOU races.
                let Ok(Some(fresh)) = self.store.get_session(&session.session_id) else {
                    continue;
                };
                if !is_running_state(&fresh.state) {
                    continue;
                }
                if let Ok(fresh_last) = DateTime::parse_from_rfc3339(&fresh.last_activity_at) {
                    let fresh_idle = Utc::now()
                        .signed_duration_since(fresh_last.with_timezone(&Utc))
                        .num_seconds();
                    if fresh_idle >= fresh.idle_timeout_seconds as i64 {
                        tracing::info!(
                            session_id = fresh.session_id,
                            idle_seconds = fresh_idle,
                            "Auto-hibernating idle session"
                        );
                        let _ = self.stop_live_session(&fresh.session_id).await;
                        let _ =
                            self.store
                                .update_session_state(&fresh.session_id, "HIBERNATED", None);
                    }
                }
            }
        }
        self.prune_idle_session_locks().await;
        let _ = self.store.prune_old_audit_events(10_000);
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

    pub(crate) fn begin_active_op(&self, session_id: &str) -> ActiveOpGuard {
        if let Ok(mut map) = self.in_flight_ops.lock() {
            *map.entry(session_id.to_owned()).or_insert(0) += 1;
        }
        let _ = self.store.touch_session_activity(session_id, None);
        ActiveOpGuard {
            session_id: session_id.to_owned(),
            in_flight: self.in_flight_ops.clone(),
            store: self.store.clone(),
        }
    }

    fn has_in_flight_ops(&self, session_id: &str) -> bool {
        self.in_flight_ops
            .lock()
            .map(|map| map.get(session_id).copied().unwrap_or(0) > 0)
            .unwrap_or(false)
    }

    pub(super) async fn profile_lock(&self, profile_id: &str) -> Arc<Mutex<()>> {
        let mut locks = self.profile_locks.lock().await;
        locks
            .entry(profile_id.to_owned())
            .or_insert_with(|| Arc::new(Mutex::new(())))
            .clone()
    }

    async fn session_lock(&self, session_id: &str) -> Arc<Mutex<()>> {
        let mut locks = self.session_locks.lock().await;
        locks
            .entry(session_id.to_owned())
            .or_insert_with(|| Arc::new(Mutex::new(())))
            .clone()
    }

    async fn ensure_running(&self, record: &SessionRecord) -> Result<()> {
        let prof_lock = self.profile_lock(&record.profile_id).await;
        let _prof_guard = prof_lock.lock().await;
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

                    // Always launch Chromium behind the built-in egress guard so custom proxy_url
                    // cannot bypass cloud-metadata blocking or Admin-configured intranet scope.
                    let guard = EgressGuardHandle::spawn_with_upstream(
                        self.config.allow_private_network,
                        permanent_private_hosts.clone(),
                        temporary_egress_pairs.clone(),
                        record.proxy_url.clone(),
                    )
                    .await?;
                    let proxy_server = Some(guard.proxy_url.clone());
                    let guard = Some(guard);

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

        // Closing a session prunes idle per-session locks to prevent unbounded HashMap growth.
        rt.close_session(&admin.token, &sid2, CloseSessionParams { hibernate: false })
            .await
            .unwrap();
        assert!(!rt.session_locks.lock().await.contains_key(&sid2));

        let _ = std::fs::remove_dir_all(&dir);
    }

    #[tokio::test]
    async fn in_flight_operation_prevents_auto_hibernate_until_finished() {
        let dir = std::env::temp_dir().join(format!("lite-inflight-{}", Uuid::new_v4().simple()));
        let store = Arc::new(LiteStore::open(&dir.join("lite.sqlite3")).unwrap());
        let admin = store.ensure_bootstrap_token(None, 4).unwrap().unwrap();
        let cfg = LiteConfig {
            data_dir: dir.clone(),
            chromium_path: PathBuf::from("/nonexistent/chromium-test-bin"),
            max_sessions: 4,
            ..LiteConfig::default()
        };
        let rt = Arc::new(LiteRuntime::new(cfg, store.clone()).unwrap());

        let created = rt
            .create_session(
                &admin.token,
                CreateSessionParams {
                    name: Some("WaitSession".to_owned()),
                    profile_id: None,
                    proxy_url: None,
                    start_url: None,
                    isolation_mode: None,
                    allowed_private_hosts: Vec::new(),
                    idle_timeout_seconds: Some(30),
                    start: false,
                },
            )
            .await
            .unwrap();
        let sid = created["session"]["sessionId"].as_str().unwrap().to_owned();
        store.update_session_state(&sid, "RUNNING", None).unwrap();

        // Simulate a 30s wait/action in flight while last_activity_at would otherwise be stale.
        let guard = rt.begin_active_op(&sid);
        {
            let conn = rusqlite::Connection::open(dir.join("lite.sqlite3")).unwrap();
            let old_ts = (Utc::now() - chrono::Duration::seconds(120)).to_rfc3339();
            conn.execute(
                "UPDATE browser_sessions SET last_activity_at = ?1 WHERE session_id = ?2",
                rusqlite::params![old_ts, &sid],
            )
            .unwrap();
        }
        rt.maintenance_tick().await;
        assert_eq!(
            store.get_session(&sid).unwrap().unwrap().state,
            "RUNNING",
            "in-flight wait/action must NOT be auto-hibernated"
        );

        // Once the operation completes and the session is genuinely idle past timeout, hibernate works.
        drop(guard);
        {
            let conn = rusqlite::Connection::open(dir.join("lite.sqlite3")).unwrap();
            let old_ts = (Utc::now() - chrono::Duration::seconds(120)).to_rfc3339();
            conn.execute(
                "UPDATE browser_sessions SET last_activity_at = ?1 WHERE session_id = ?2",
                rusqlite::params![old_ts, &sid],
            )
            .unwrap();
        }
        rt.maintenance_tick().await;
        assert_eq!(
            store.get_session(&sid).unwrap().unwrap().state,
            "HIBERNATED",
            "truly idle session with zero in-flight ops should auto-hibernate"
        );

        let _ = std::fs::remove_dir_all(&dir);
    }

    #[tokio::test]
    async fn per_profile_lock_serializes_same_profile_and_allows_different_profiles_in_parallel() {
        let dir = std::env::temp_dir().join(format!("lite-proflock-{}", Uuid::new_v4().simple()));
        let store = Arc::new(LiteStore::open(&dir.join("lite.sqlite3")).unwrap());
        let admin = store.ensure_bootstrap_token(None, 4).unwrap().unwrap();
        let cfg = LiteConfig {
            data_dir: dir.clone(),
            chromium_path: PathBuf::from("/nonexistent/chromium-test-bin"),
            max_sessions: 4,
            ..LiteConfig::default()
        };
        let rt = Arc::new(LiteRuntime::new(cfg, store.clone()).unwrap());

        let p1 = rt
            .create_profile(
                &admin.token,
                CreateProfileParams {
                    name: "ProfileOne".to_owned(),
                    allowed_private_hosts: Vec::new(),
                    grant_agents: Vec::new(),
                },
            )
            .unwrap();
        let p2 = rt
            .create_profile(
                &admin.token,
                CreateProfileParams {
                    name: "ProfileTwo".to_owned(),
                    allowed_private_hosts: Vec::new(),
                    grant_agents: Vec::new(),
                },
            )
            .unwrap();
        let pid1 = p1["profile"]["profileId"].as_str().unwrap().to_owned();
        let pid2 = p2["profile"]["profileId"].as_str().unwrap().to_owned();

        // Hold profile_lock(pid1) to simulate an active save/restore on Profile 1.
        let lock1 = rt.profile_lock(&pid1).await;
        let guard1 = lock1.lock().await;

        let rt_c1 = rt.clone();
        let admin_c1 = admin.token.clone();
        let pid1_c = pid1.clone();
        let open_p1 = tokio::spawn(async move {
            rt_c1
                .open_profile(
                    &admin_c1,
                    &pid1_c,
                    OpenProfileParams {
                        name: None,
                        proxy_url: None,
                        start_url: None,
                        allowed_private_hosts: Vec::new(),
                        idle_timeout_seconds: None,
                        start: false,
                    },
                )
                .await
        });

        // Profile 2 MUST open immediately in parallel without waiting for Profile 1's lock!
        let opened_p2 = tokio::time::timeout(
            Duration::from_millis(500),
            rt.open_profile(
                &admin.token,
                &pid2,
                OpenProfileParams {
                    name: None,
                    proxy_url: None,
                    start_url: None,
                    allowed_private_hosts: Vec::new(),
                    idle_timeout_seconds: None,
                    start: false,
                },
            ),
        )
        .await
        .expect("different profile must not be blocked by Profile 1 lock")
        .unwrap();
        assert_eq!(opened_p2["session"]["profileId"], pid2);

        // Profile 1 MUST still be waiting until guard1 is released.
        tokio::time::sleep(Duration::from_millis(40)).await;
        assert!(
            !open_p1.is_finished(),
            "concurrent open on same profile must wait for in-flight save/restore lock"
        );

        drop(guard1);
        let opened_p1 = tokio::time::timeout(Duration::from_millis(500), open_p1)
            .await
            .expect("Profile 1 open should proceed once lock released")
            .unwrap()
            .unwrap();
        assert_eq!(opened_p1["session"]["profileId"], pid1);

        let _ = std::fs::remove_dir_all(&dir);
    }

    #[tokio::test]
    async fn admin_preconfigures_token_intranet_scope_for_agent_direct_operation() {
        let dir = std::env::temp_dir().join(format!("lite-tokscope-{}", Uuid::new_v4().simple()));
        let store = Arc::new(LiteStore::open(&dir.join("lite.sqlite3")).unwrap());
        let admin = store.ensure_bootstrap_token(None, 4).unwrap().unwrap();
        let agent = store
            .create_token_with_policy(
                "agent_ops",
                "Ops Agent",
                vec!["mcp".to_owned(), "sessions".to_owned()],
                2,
                vec![
                    "10.20.0.0/16".to_owned(),
                    "grafana.internal:3000".to_owned(),
                ],
            )
            .unwrap();
        let cfg = LiteConfig {
            data_dir: dir.clone(),
            chromium_path: PathBuf::from("/nonexistent/chromium-test-bin"),
            max_sessions: 4,
            allow_private_network: false,
            ..LiteConfig::default()
        };
        let rt = Arc::new(LiteRuntime::new(cfg, store.clone()).unwrap());

        // Agent creates a session without specifying allowed_private_hosts -> automatically inherits
        // Admin's token-level intranet scope and can navigate directly within it!
        let created = rt
            .create_session(
                &agent.token,
                CreateSessionParams {
                    name: Some("OpsSession".to_owned()),
                    profile_id: None,
                    proxy_url: None,
                    start_url: Some("http://grafana.internal:3000/dash".to_owned()),
                    isolation_mode: None,
                    allowed_private_hosts: Vec::new(),
                    idle_timeout_seconds: None,
                    start: false,
                },
            )
            .await
            .unwrap();
        let sid = created["session"]["sessionId"].as_str().unwrap().to_owned();
        let policy = rt
            .get_session_network_policy(&agent.token, &sid)
            .await
            .unwrap();
        assert_eq!(
            policy["permanentAllowedPrivateHosts"],
            json!(["10.20.0.0/16", "grafana.internal:3000"])
        );

        // Agent attempting to expand scope on create_session or authorize_session_private_hosts fails.
        let esc = rt
            .create_session(
                &agent.token,
                CreateSessionParams {
                    name: Some("Bad".to_owned()),
                    profile_id: None,
                    proxy_url: None,
                    start_url: None,
                    isolation_mode: None,
                    allowed_private_hosts: vec!["192.168.0.0/16".to_owned()],
                    idle_timeout_seconds: None,
                    start: false,
                },
            )
            .await
            .unwrap_err()
            .to_string();
        assert!(esc.contains("ADMIN_SCOPE_REQUIRED"), "err={esc}");

        let _ = admin;
        let _ = std::fs::remove_dir_all(&dir);
    }
}
