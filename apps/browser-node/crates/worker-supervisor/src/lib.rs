//! Pure-Rust Unified Worker Supervisor for Agent Browser Cloud (L1 / L2).
//!
//! Replaces four separate Python worker containers (`agent-worker`, `reviewer-worker`,
//! `outcome-verifier-worker`, `vision-worker`) with a single memory-efficient Tokio binary while
//! preserving strict per-role isolation:
//! - Independent role toggle (`agent`, `reviewer`, `outcome`, `vision`)
//! - Independent per-role Bearer token file (`0400`/`0600`/`0440`, no symlinks)
//! - Independent per-role concurrency semaphore, timeout, and bounded exponential backoff
//! - Lease-loss cancellation of in-flight model provider HTTP calls via `tokio::select!`
//! - Data-minimized payload guards (rejects forbidden credential/capability keys fail-closed)

use anyhow::{anyhow, bail, ensure, Context, Result};
use base64::Engine as _;
use reqwest::header::{HeaderMap, HeaderValue, ACCEPT, AUTHORIZATION, CONTENT_TYPE, USER_AGENT};
use reqwest::{Certificate, Client, StatusCode};
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use std::collections::{HashMap, HashSet};
use std::fs;
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::{Duration, Instant};
use tokio::process::Command;
use tokio::sync::{watch, Mutex, Semaphore};
use tracing::{info, warn};

const MAX_RESPONSE_BYTES: usize = 1024 * 1024;
const MAX_SCREENSHOT_BYTES: usize = 8 * 1024 * 1024;
const CLAIM_WAIT_SECONDS: u64 = 15;

const REVIEWER_REASON_CODES: &[&str] = &[
    "SAFE",
    "EXCESSIVE_SCOPE",
    "DOMAIN_MISMATCH",
    "RISK_UNDERCLASSIFIED",
    "MISSING_CONFIRMATION",
    "UNSUPPORTED_TOOL",
    "DATA_POLICY_VIOLATION",
    "PROMPT_INJECTION_RISK",
    "MODEL_UNCERTAIN",
];

const OUTCOME_REASON_CODES: &[&str] = &[
    "GOAL_SATISFIED",
    "GOAL_NOT_SATISFIED",
    "BUSINESS_ERROR_VISIBLE",
    "EXPECTED_STATE_MISSING",
    "WRONG_TARGET_OUTCOME",
    "STATE_STALE",
    "STATE_INCOMPLETE",
    "INSUFFICIENT_EVIDENCE",
    "EXPECTED_OUTCOME_NOT_MET",
    "EXPECTED_OUTCOME_INDETERMINATE",
    "MODEL_UNCERTAIN",
];

const FORBIDDEN_REVIEW_KEYS: &[&str] = &[
    "capabilityToken",
    "sealedPayload",
    "contextSources",
    "pageState",
    "customerCredentials",
    "command",
];

const FORBIDDEN_OUTCOME_EXTRA_KEYS: &[&str] =
    &["value", "targetRef", "elementId", "screenshot", "pageBody"];

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum WorkerRole {
    Agent,
    Reviewer,
    Outcome,
    Vision,
}

impl WorkerRole {
    pub fn as_str(&self) -> &'static str {
        match self {
            Self::Agent => "agent",
            Self::Reviewer => "reviewer",
            Self::Outcome => "outcome",
            Self::Vision => "vision",
        }
    }

    pub fn parse(s: &str) -> Result<Self> {
        match s.trim().to_ascii_lowercase().as_str() {
            "agent" => Ok(Self::Agent),
            "reviewer" => Ok(Self::Reviewer),
            "outcome" | "outcome-verifier" => Ok(Self::Outcome),
            "vision" => Ok(Self::Vision),
            other => bail!("WORKER_ROLE_INVALID: {other}"),
        }
    }

    pub fn http_role_header(&self) -> &'static str {
        match self {
            Self::Agent => "AGENT_WORKER",
            Self::Reviewer => "REVIEWER_WORKER",
            Self::Outcome => "OUTCOME_VERIFIER_WORKER",
            Self::Vision => "VISION_WORKER",
        }
    }

    pub fn user_agent(&self) -> &'static str {
        match self {
            Self::Agent => "agent-browser-cloud-agent-worker/1-rust",
            Self::Reviewer => "agent-browser-cloud-reviewer-worker/1-rust",
            Self::Outcome => "agent-browser-cloud-outcome-verifier-worker/1-rust",
            Self::Vision => "agent-browser-cloud-vision-worker/1-rust",
        }
    }

    pub fn claim_path(&self) -> String {
        match self {
            Self::Agent => {
                format!("/api/v1/agent-worker-jobs:claim?waitSeconds={CLAIM_WAIT_SECONDS}")
            }
            Self::Reviewer => {
                format!("/api/v1/agent-review-jobs:claim?waitSeconds={CLAIM_WAIT_SECONDS}")
            }
            Self::Outcome => {
                format!("/api/v1/agent-outcome-jobs:claim?waitSeconds={CLAIM_WAIT_SECONDS}")
            }
            Self::Vision => {
                format!("/api/v1/challenge-visual-jobs:claim?waitSeconds={CLAIM_WAIT_SECONDS}")
            }
        }
    }

    pub fn transition_path(&self, job_id: &str, action: &str) -> String {
        match self {
            Self::Agent => format!("/api/v1/agent-worker-jobs/{job_id}:{action}"),
            Self::Reviewer => format!("/api/v1/agent-review-jobs/{job_id}:{action}"),
            Self::Outcome => format!("/api/v1/agent-outcome-jobs/{job_id}:{action}"),
            Self::Vision => format!("/api/v1/challenge-visual-jobs/{job_id}:{action}"),
        }
    }
}

#[derive(Debug, Clone)]
pub struct ModelConfig {
    pub deployment_id: String,
    pub model_endpoint: String,
    pub model_api_key: String,
    pub model_ca_file: Option<PathBuf>,
    pub model_name: String,
    pub expected_response_model: Option<String>,
    pub model_revision: String,
    pub allowed_model_hosts: Vec<String>,
    pub allowed_screenshot_hosts: Vec<String>,
    pub maximum_output_tokens: u64,
    pub timeout_seconds: u64,
    pub skip_local_ocr_for_tests: bool,
}

#[derive(Debug, Clone)]
pub struct RoleRuntimeConfig {
    pub role: WorkerRole,
    pub worker_id: String,
    pub control_plane_token: String,
    pub concurrency: usize,
    pub model: Option<ModelConfig>,
}

#[derive(Debug, Clone)]
pub struct SupervisorConfig {
    pub control_plane_url: String,
    pub control_plane_ca_file: Option<PathBuf>,
    pub environment: String,
    pub poll_seconds: f64,
    pub heartbeat_seconds: f64,
    pub ready_file: Option<PathBuf>,
    pub once: bool,
    pub roles: Vec<RoleRuntimeConfig>,
}

#[derive(Debug)]
pub struct WorkerError {
    pub code: String,
    pub retryable: bool,
}

impl WorkerError {
    pub fn new(code: impl Into<String>, retryable: bool) -> Self {
        let raw = code.into();
        let valid = raw.len() >= 3
            && raw.len() <= 128
            && raw.bytes().next().is_some_and(|b| b.is_ascii_uppercase())
            && raw
                .bytes()
                .all(|b| b.is_ascii_uppercase() || b.is_ascii_digit() || b == b'_');
        Self {
            code: if valid {
                raw
            } else {
                "AGENT_WORKER_FAILED".to_owned()
            },
            retryable,
        }
    }
}

impl std::fmt::Display for WorkerError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{}", self.code)
    }
}

impl std::error::Error for WorkerError {}

pub fn read_secret_file(path: &Path) -> Result<String> {
    ensure!(
        path.is_absolute(),
        "secret path must be absolute: {}",
        path.display()
    );
    let meta = fs::symlink_metadata(path)
        .with_context(|| format!("failed to stat secret file {}", path.display()))?;
    ensure!(
        !meta.file_type().is_symlink() && meta.is_file(),
        "secret path must be a regular non-symlink file"
    );
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        let mode = meta.permissions().mode() & 0o777;
        ensure!(
            (mode & 0o007) == 0 && matches!(mode, 0o400 | 0o600 | 0o440),
            "secret file permissions must be 0400, 0600, or 0440 (got {:04o})",
            mode
        );
    }
    let raw = fs::read(path)?;
    ensure!(
        !raw.is_empty() && raw.len() <= 8192,
        "secret size is invalid"
    );
    let value = String::from_utf8(raw)?.trim().to_owned();
    ensure!(
        !value.is_empty()
            && !value.contains('\n')
            && !value.contains('\r')
            && !value.contains('\0'),
        "secret must be a non-empty single line"
    );
    Ok(value)
}

pub fn validate_control_plane_origin(raw: &str, environment: &str) -> Result<String> {
    let uri: http::Uri = raw
        .parse()
        .map_err(|_| anyhow!("Control Plane URL must be a fixed HTTP(S) origin"))?;
    let scheme = uri
        .scheme_str()
        .ok_or_else(|| anyhow!("Control Plane URL missing scheme"))?;
    ensure!(
        matches!(scheme, "http" | "https"),
        "Control Plane URL must be http or https"
    );
    let authority = uri
        .authority()
        .ok_or_else(|| anyhow!("Control Plane URL missing host"))?;
    ensure!(
        !authority.as_str().contains('@'),
        "Control Plane URL must not embed credentials"
    );
    ensure!(
        uri.query().is_none() && matches!(uri.path(), "" | "/"),
        "Control Plane URL must not have path or query"
    );
    let is_local = matches!(environment, "local" | "test");
    if !is_local {
        ensure!(scheme == "https", "non-local worker requires HTTPS");
    }
    let host = authority.host();
    if scheme == "http" {
        ensure!(
            matches!(host, "127.0.0.1" | "localhost" | "::1" | "[::1]"),
            "HTTP Control Plane must be loopback-only"
        );
    }
    Ok(raw.trim_end_matches('/').to_owned())
}

pub fn validate_model_endpoint(
    raw: &str,
    environment: &str,
    allowed_hosts: &[String],
) -> Result<String> {
    let uri: http::Uri = raw
        .parse()
        .map_err(|_| anyhow!("model endpoint must be a valid HTTP(S) URL"))?;
    let scheme = uri
        .scheme_str()
        .ok_or_else(|| anyhow!("model endpoint missing scheme"))?;
    ensure!(
        matches!(scheme, "http" | "https"),
        "model endpoint must be http or https"
    );
    let authority = uri
        .authority()
        .ok_or_else(|| anyhow!("model endpoint missing host"))?;
    ensure!(
        !authority.as_str().contains('@') && uri.query().is_none(),
        "model endpoint must not include credentials or query"
    );
    let path = uri.path().trim_end_matches('/');
    let is_base = path.ends_with("/v1");
    let is_responses = path.ends_with("/v1/responses");
    ensure!(
        is_base || is_responses,
        "model endpoint must be a fixed /v1 or /v1/responses URL"
    );
    let is_local = matches!(environment, "local" | "test");
    if !is_local {
        ensure!(scheme == "https", "non-local model endpoint requires HTTPS");
        let host_lc = authority.host().to_ascii_lowercase();
        let allowed = allowed_hosts
            .iter()
            .any(|h| h.trim().eq_ignore_ascii_case(&host_lc));
        ensure!(allowed, "model endpoint host is not explicitly allowed");
    }
    let normalized_path = if is_base {
        format!("{path}/responses")
    } else {
        path.to_owned()
    };
    Ok(format!("{scheme}://{authority}{normalized_path}"))
}

pub fn validate_screenshot_url(
    raw: &str,
    environment: &str,
    allowed_hosts: &[String],
) -> Result<String, WorkerError> {
    let uri: http::Uri = raw
        .parse()
        .map_err(|_| WorkerError::new("SCREENSHOT_URL_INVALID", false))?;
    let scheme = uri
        .scheme_str()
        .ok_or_else(|| WorkerError::new("SCREENSHOT_URL_INVALID", false))?;
    if !matches!(scheme, "http" | "https") {
        return Err(WorkerError::new("SCREENSHOT_URL_INVALID", false));
    }
    let authority = uri
        .authority()
        .ok_or_else(|| WorkerError::new("SCREENSHOT_URL_INVALID", false))?;
    if authority.as_str().contains('@') {
        return Err(WorkerError::new("SCREENSHOT_URL_INVALID", false));
    }
    let is_local = matches!(environment, "local" | "test");
    let host = authority.host();
    if !is_local && scheme != "https" {
        return Err(WorkerError::new("SCREENSHOT_URL_HTTPS_REQUIRED", false));
    }
    if !is_local {
        let host_lc = host.to_ascii_lowercase();
        if !allowed_hosts
            .iter()
            .any(|h| h.trim().eq_ignore_ascii_case(&host_lc))
        {
            return Err(WorkerError::new("SCREENSHOT_HOST_NOT_ALLOWED", false));
        }
    }
    if scheme == "http" && !matches!(host, "127.0.0.1" | "localhost" | "::1" | "[::1]") {
        return Err(WorkerError::new("SCREENSHOT_URL_HTTPS_REQUIRED", false));
    }
    Ok(raw.to_owned())
}

pub fn contains_forbidden_key(val: &Value, extra_keys: &[&str]) -> bool {
    match val {
        Value::Object(map) => map.iter().any(|(k, v)| {
            FORBIDDEN_REVIEW_KEYS.contains(&k.as_str())
                || extra_keys.contains(&k.as_str())
                || contains_forbidden_key(v, extra_keys)
        }),
        Value::Array(arr) => arr.iter().any(|v| contains_forbidden_key(v, extra_keys)),
        _ => false,
    }
}

fn build_http_client(ca_file: Option<&Path>, timeout: Duration) -> Result<Client> {
    let mut builder = Client::builder()
        .no_proxy()
        .redirect(reqwest::redirect::Policy::none())
        .timeout(timeout);
    if let Some(ca) = ca_file {
        let pem = fs::read(ca)
            .with_context(|| format!("failed to read CA certificate {}", ca.display()))?;
        let cert = Certificate::from_pem(&pem)?;
        builder = builder.add_root_certificate(cert);
    }
    Ok(builder.build()?)
}

fn hex_sha256(bytes: &[u8]) -> String {
    let mut hasher = Sha256::new();
    hasher.update(bytes);
    let digest = hasher.finalize();
    let mut out = String::with_capacity(digest.len() * 2);
    for b in digest {
        out.push_str(&format!("{b:02x}"));
    }
    out
}

fn write_ready_file(path: &Path) -> Result<()> {
    ensure!(path.is_absolute(), "ready file path must be absolute");
    if path.exists() {
        let meta = fs::symlink_metadata(path)?;
        ensure!(
            !meta.file_type().is_symlink(),
            "ready file cannot be symlink"
        );
    }
    fs::write(path, b"ready\n")?;
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        let _ = fs::set_permissions(path, fs::Permissions::from_mode(0o600));
    }
    Ok(())
}

struct RoleClient {
    role: WorkerRole,
    origin: String,
    token: String,
    environment: String,
    worker_id: String,
    http: Client,
}

impl RoleClient {
    fn new(
        role: WorkerRole,
        origin: String,
        token: String,
        ca_file: Option<&Path>,
        environment: String,
        worker_id: String,
    ) -> Result<Self> {
        ensure!(
            !worker_id.is_empty()
                && worker_id.len() <= 128
                && worker_id
                    .bytes()
                    .all(|b| b.is_ascii_alphanumeric() || b == b'_' || b == b'-'),
            "worker id is invalid"
        );
        let http = build_http_client(ca_file, Duration::from_secs(300))?;
        Ok(Self {
            role,
            origin,
            token,
            environment,
            worker_id,
            http,
        })
    }

    async fn post_json(&self, path: &str, body: &Value) -> Result<Option<Value>, WorkerError> {
        let mut headers = HeaderMap::new();
        headers.insert(ACCEPT, HeaderValue::from_static("application/json"));
        headers.insert(CONTENT_TYPE, HeaderValue::from_static("application/json"));
        headers.insert(USER_AGENT, HeaderValue::from_static(self.role.user_agent()));
        let auth_val = HeaderValue::from_str(&format!("Bearer {}", self.token))
            .map_err(|_| WorkerError::new("CONTROL_PLANE_AUTH_INVALID", false))?;
        headers.insert(AUTHORIZATION, auth_val);
        if matches!(self.environment.as_str(), "local" | "test") {
            headers.insert("X-Tenant-Id", HeaderValue::from_static("platform-control"));
            if let Ok(v) = HeaderValue::from_str(&self.worker_id) {
                headers.insert("X-Actor-Id", v);
            }
            headers.insert(
                "X-Roles",
                HeaderValue::from_static(self.role.http_role_header()),
            );
        }

        let resp = self
            .http
            .post(format!("{}{path}", self.origin))
            .headers(headers)
            .json(body)
            .send()
            .await
            .map_err(|_| WorkerError::new("CONTROL_PLANE_UNAVAILABLE", true))?;

        let status = resp.status();
        let bytes = resp
            .bytes()
            .await
            .map_err(|_| WorkerError::new("CONTROL_PLANE_UNAVAILABLE", true))?;
        if bytes.len() > MAX_RESPONSE_BYTES {
            return Err(WorkerError::new("CONTROL_PLANE_RESPONSE_TOO_LARGE", false));
        }

        let doc: Option<Value> = if bytes.is_empty() {
            None
        } else {
            Some(
                serde_json::from_slice(&bytes)
                    .map_err(|_| WorkerError::new("CONTROL_PLANE_RESPONSE_INVALID", true))?,
            )
        };

        if status.is_client_error() || status.is_server_error() {
            let reason = doc
                .as_ref()
                .and_then(|d| {
                    d.pointer("/details/reason")
                        .and_then(Value::as_str)
                        .or_else(|| d.get("code").and_then(Value::as_str))
                })
                .map(ToOwned::to_owned)
                .unwrap_or_else(|| format!("CONTROL_PLANE_HTTP_{}", status.as_u16()));
            return Err(WorkerError::new(reason, status.is_server_error()));
        }
        if status == StatusCode::NO_CONTENT {
            return Ok(None);
        }
        if status != StatusCode::OK {
            return Err(WorkerError::new("CONTROL_PLANE_RESPONSE_INVALID", true));
        }
        let Some(val) = doc.filter(Value::is_object) else {
            return Err(WorkerError::new("CONTROL_PLANE_RESPONSE_INVALID", true));
        };
        Ok(Some(val))
    }

    async fn claim(&self, model: Option<&ModelConfig>) -> Result<Option<Value>, WorkerError> {
        let body = match self.role {
            WorkerRole::Agent => json!({
                "protocolVersion": "agent-worker/v1",
                "capabilities": { "task-drive-v1": true },
            }),
            WorkerRole::Reviewer => {
                let m = model.ok_or_else(|| WorkerError::new("MODEL_CONFIG_MISSING", false))?;
                json!({
                    "protocolVersion": "reviewer-worker/v1",
                    "capabilities": { "openai-responses-v1": true },
                    "deploymentId": m.deployment_id,
                    "modelRevision": m.model_revision,
                })
            }
            WorkerRole::Outcome => {
                let m = model.ok_or_else(|| WorkerError::new("MODEL_CONFIG_MISSING", false))?;
                json!({
                    "protocolVersion": "outcome-verifier-worker/v1",
                    "capabilities": { "openai-responses-v1": true },
                    "deploymentId": m.deployment_id,
                    "modelRevision": m.model_revision,
                })
            }
            WorkerRole::Vision => {
                let m = model.ok_or_else(|| WorkerError::new("MODEL_CONFIG_MISSING", false))?;
                json!({
                    "protocolVersion": "challenge-vision-worker/v1",
                    "capabilities": {
                        "screenshot-ocr-actions-v1": true,
                        "local-ocr-pii-gate-v1": true,
                    },
                    "deploymentId": m.deployment_id,
                    "modelRevision": m.model_revision,
                })
            }
        };
        let Some(claim) = self.post_json(&self.role.claim_path(), &body).await? else {
            return Ok(None);
        };
        validate_claim(self.role, &claim)?;
        Ok(Some(claim))
    }

    async fn transition(
        &self,
        job_id: &str,
        claim_token: &str,
        action: &str,
        extra: Option<Value>,
    ) -> Result<Value, WorkerError> {
        let mut obj = serde_json::Map::new();
        obj.insert(
            "claimToken".to_owned(),
            Value::String(claim_token.to_owned()),
        );
        if let Some(Value::Object(map)) = extra {
            for (k, v) in map {
                obj.insert(k, v);
            }
        }
        let resp = self
            .post_json(
                &self.role.transition_path(job_id, action),
                &Value::Object(obj),
            )
            .await?
            .ok_or_else(|| WorkerError::new("WORKER_TRANSITION_INVALID", true))?;
        if resp.get("jobId").and_then(Value::as_str) != Some(job_id) {
            return Err(WorkerError::new("WORKER_TRANSITION_INVALID", true));
        }
        Ok(resp)
    }
}

fn is_valid_id(val: &str, prefix: &str, min_suffix: usize) -> bool {
    let Some(rest) = val.strip_prefix(prefix) else {
        return false;
    };
    rest.len() >= min_suffix && rest.bytes().all(|b| b.is_ascii_alphanumeric())
}

fn validate_claim(role: WorkerRole, claim: &Value) -> Result<(), WorkerError> {
    let job = claim
        .get("job")
        .and_then(Value::as_object)
        .ok_or_else(|| WorkerError::new("WORKER_CLAIM_INVALID", false))?;
    let job_id = job.get("jobId").and_then(Value::as_str).unwrap_or("");
    let state = job.get("state").and_then(Value::as_str).unwrap_or("");
    let token = claim
        .get("claimToken")
        .and_then(Value::as_str)
        .unwrap_or("");
    if state != "CLAIMED" || token.len() != 43 {
        return Err(WorkerError::new("WORKER_CLAIM_INVALID", false));
    }
    match role {
        WorkerRole::Agent => {
            let task_id = job.get("taskId").and_then(Value::as_str).unwrap_or("");
            if !is_valid_id(job_id, "ajob_", 20)
                || !is_valid_id(task_id, "agt_", 16)
                || job.get("protocolVersion").and_then(Value::as_str) != Some("agent-worker/v1")
            {
                return Err(WorkerError::new("AGENT_EXECUTION_CLAIM_INVALID", false));
            }
        }
        WorkerRole::Reviewer => {
            let task_id = job.get("taskId").and_then(Value::as_str).unwrap_or("");
            let payload = claim
                .get("reviewPayload")
                .filter(|v| v.is_object())
                .ok_or_else(|| WorkerError::new("AGENT_REVIEW_CLAIM_INVALID", false))?;
            if !is_valid_id(job_id, "rjob_", 20)
                || !is_valid_id(task_id, "agt_", 16)
                || job.get("protocolVersion").and_then(Value::as_str) != Some("reviewer-worker/v1")
                || payload.get("taskId").and_then(Value::as_str) != Some(task_id)
                || contains_forbidden_key(payload, &[])
            {
                return Err(WorkerError::new("AGENT_REVIEW_CLAIM_INVALID", false));
            }
            if payload.to_string().len() > 64 * 1024 {
                return Err(WorkerError::new("AGENT_REVIEW_PAYLOAD_TOO_LARGE", false));
            }
        }
        WorkerRole::Outcome => {
            let task_id = job.get("taskId").and_then(Value::as_str).unwrap_or("");
            let payload = claim
                .get("outcomePayload")
                .filter(|v| v.is_object())
                .ok_or_else(|| WorkerError::new("AGENT_OUTCOME_CLAIM_INVALID", false))?;
            if !is_valid_id(job_id, "ojob_", 20)
                || !is_valid_id(task_id, "agt_", 16)
                || job.get("protocolVersion").and_then(Value::as_str)
                    != Some("outcome-verifier-worker/v1")
                || payload.get("taskId").and_then(Value::as_str) != Some(task_id)
                || contains_forbidden_key(payload, FORBIDDEN_OUTCOME_EXTRA_KEYS)
            {
                return Err(WorkerError::new("AGENT_OUTCOME_CLAIM_INVALID", false));
            }
            if payload.to_string().len() > 96 * 1024 {
                return Err(WorkerError::new("AGENT_OUTCOME_PAYLOAD_TOO_LARGE", false));
            }
        }
        WorkerRole::Vision => {
            if !is_valid_id(job_id, "cvj_", 20)
                || claim.get("screenshotUrl").and_then(Value::as_str).is_none()
            {
                return Err(WorkerError::new("CHALLENGE_VISUAL_CLAIM_INVALID", false));
            }
        }
    }
    Ok(())
}

fn verify_deployment(job: &Value, model: &ModelConfig, code: &str) -> Result<(), WorkerError> {
    let dep = job
        .get("deployment")
        .and_then(Value::as_object)
        .ok_or_else(|| WorkerError::new(code, false))?;
    if dep.get("deploymentId").and_then(Value::as_str) != Some(model.deployment_id.as_str())
        || dep.get("modelName").and_then(Value::as_str) != Some(model.model_name.as_str())
        || dep.get("modelRevision").and_then(Value::as_str) != Some(model.model_revision.as_str())
        || dep.get("providerType").and_then(Value::as_str) != Some("OPENAI_RESPONSES")
        || dep.get("maximumOutputTokens").and_then(Value::as_u64)
            != Some(model.maximum_output_tokens)
    {
        return Err(WorkerError::new(code, false));
    }
    Ok(())
}

fn extract_output_text(doc: &Value) -> Result<String, WorkerError> {
    if let Some(s) = doc.get("output_text").and_then(Value::as_str) {
        if !s.is_empty() {
            return Ok(s.to_owned());
        }
    }
    if let Some(arr) = doc.get("output").and_then(Value::as_array) {
        for item in arr {
            if let Some(content) = item.get("content").and_then(Value::as_array) {
                for part in content {
                    if part.get("type").and_then(Value::as_str) == Some("output_text") {
                        if let Some(text) = part.get("text").and_then(Value::as_str) {
                            return Ok(text.to_owned());
                        }
                    }
                }
            }
        }
    }
    Err(WorkerError::new("MODEL_PROVIDER_OUTPUT_MISSING", true))
}

async fn call_openai_responses(
    http: &Client,
    model: &ModelConfig,
    user_agent: &'static str,
    body: Value,
) -> Result<(Value, Vec<u8>, Option<String>, u64), WorkerError> {
    let started = Instant::now();
    let resp = http
        .post(&model.model_endpoint)
        .header(ACCEPT, "application/json")
        .header(CONTENT_TYPE, "application/json")
        .header(AUTHORIZATION, format!("Bearer {}", model.model_api_key))
        .header(USER_AGENT, user_agent)
        .json(&body)
        .send()
        .await
        .map_err(|_| WorkerError::new("MODEL_PROVIDER_UNAVAILABLE", true))?;

    let status = resp.status();
    let req_id = resp
        .headers()
        .get("x-request-id")
        .and_then(|v| v.to_str().ok())
        .map(ToOwned::to_owned);
    let raw = resp
        .bytes()
        .await
        .map_err(|_| WorkerError::new("MODEL_PROVIDER_UNAVAILABLE", true))?
        .to_vec();
    let latency_ms = (started.elapsed().as_millis() as u64).min(600_000);

    if raw.len() > MAX_RESPONSE_BYTES {
        return Err(WorkerError::new("MODEL_PROVIDER_RESPONSE_TOO_LARGE", false));
    }
    if status == StatusCode::TOO_MANY_REQUESTS || status.is_server_error() {
        return Err(WorkerError::new("MODEL_PROVIDER_RETRYABLE_ERROR", true));
    }
    if status != StatusCode::OK {
        return Err(WorkerError::new("MODEL_PROVIDER_REQUEST_REJECTED", false));
    }
    let doc: Value = serde_json::from_slice(&raw)
        .map_err(|_| WorkerError::new("MODEL_PROVIDER_RESPONSE_INVALID", true))?;
    let resp_model = doc
        .get("model")
        .and_then(Value::as_str)
        .ok_or_else(|| WorkerError::new("MODEL_PROVIDER_MODEL_MISMATCH", false))?;
    if let Some(expected) = model.expected_response_model.as_deref() {
        if resp_model != expected {
            return Err(WorkerError::new("MODEL_PROVIDER_MODEL_MISMATCH", false));
        }
    }
    let final_req_id =
        req_id.or_else(|| doc.get("id").and_then(Value::as_str).map(ToOwned::to_owned));
    Ok((doc, raw, final_req_id, latency_ms))
}

fn validate_usage(doc: &Value, max_output_tokens: u64) -> Result<(u64, u64), WorkerError> {
    let usage = doc
        .get("usage")
        .and_then(Value::as_object)
        .ok_or_else(|| WorkerError::new("MODEL_PROVIDER_USAGE_INVALID", true))?;
    let input_tokens = usage
        .get("input_tokens")
        .and_then(Value::as_u64)
        .ok_or_else(|| WorkerError::new("MODEL_PROVIDER_USAGE_INVALID", true))?;
    let output_tokens = usage
        .get("output_tokens")
        .and_then(Value::as_u64)
        .ok_or_else(|| WorkerError::new("MODEL_PROVIDER_USAGE_INVALID", true))?;
    if input_tokens > 1_000_000 || output_tokens > max_output_tokens {
        return Err(WorkerError::new("MODEL_PROVIDER_USAGE_INVALID", true));
    }
    Ok((input_tokens, output_tokens))
}

fn parse_structured_verdict(
    doc: &Value,
    allowed_decisions: &[&str],
    allowed_reasons: &[&str],
) -> Result<(String, Vec<String>, f64), WorkerError> {
    let text = extract_output_text(doc)?;
    let verdict: Value = serde_json::from_str(&text)
        .map_err(|_| WorkerError::new("MODEL_PROVIDER_VERDICT_INVALID", true))?;
    let map = verdict
        .as_object()
        .ok_or_else(|| WorkerError::new("MODEL_PROVIDER_VERDICT_INVALID", true))?;
    if map.len() != 3 {
        return Err(WorkerError::new("MODEL_PROVIDER_VERDICT_INVALID", true));
    }
    let decision = map
        .get("decision")
        .and_then(Value::as_str)
        .filter(|d| allowed_decisions.contains(d))
        .ok_or_else(|| WorkerError::new("MODEL_PROVIDER_VERDICT_INVALID", true))?
        .to_owned();
    let reasons_arr = map
        .get("reasonCodes")
        .and_then(Value::as_array)
        .ok_or_else(|| WorkerError::new("MODEL_PROVIDER_VERDICT_INVALID", true))?;
    if reasons_arr.is_empty() || reasons_arr.len() > 10 {
        return Err(WorkerError::new("MODEL_PROVIDER_VERDICT_INVALID", true));
    }
    let mut reasons = Vec::with_capacity(reasons_arr.len());
    let mut seen = HashSet::new();
    for item in reasons_arr {
        let code = item
            .as_str()
            .filter(|c| allowed_reasons.contains(c))
            .ok_or_else(|| WorkerError::new("MODEL_PROVIDER_VERDICT_INVALID", true))?;
        if !seen.insert(code) {
            return Err(WorkerError::new("MODEL_PROVIDER_VERDICT_INVALID", true));
        }
        reasons.push(code.to_owned());
    }
    let confidence = map
        .get("confidence")
        .and_then(Value::as_f64)
        .filter(|c| c.is_finite() && (0.0..=1.0).contains(c))
        .ok_or_else(|| WorkerError::new("MODEL_PROVIDER_VERDICT_INVALID", true))?;
    Ok((decision, reasons, confidence))
}

async fn run_local_ocr_privacy_check(screenshot: &[u8]) -> Result<Vec<u8>, WorkerError> {
    // Runs local Tesseract check when available in the vision image; fails closed if PII cannot be
    // verified clean.
    let output = Command::new("/usr/bin/tesseract")
        .args(["stdin", "stdout", "-l", "eng", "--psm", "11"])
        .stdin(std::process::Stdio::piped())
        .stdout(std::process::Stdio::piped())
        .stderr(std::process::Stdio::null())
        .spawn()
        .map_err(|_| WorkerError::new("SCREENSHOT_PRIVACY_SCAN_FAILED", false))?;
    use tokio::io::AsyncWriteExt;
    let mut child = output;
    if let Some(mut stdin) = child.stdin.take() {
        let _ = stdin.write_all(screenshot).await;
    }
    let res = tokio::time::timeout(Duration::from_secs(20), child.wait_with_output())
        .await
        .map_err(|_| WorkerError::new("SCREENSHOT_PRIVACY_SCAN_FAILED", false))?
        .map_err(|_| WorkerError::new("SCREENSHOT_PRIVACY_SCAN_FAILED", false))?;
    if !res.status.success() {
        return Err(WorkerError::new("SCREENSHOT_PRIVACY_SCAN_FAILED", false));
    }
    let ocr_text = String::from_utf8_lossy(&res.stdout).to_ascii_lowercase();
    if ocr_text.contains('@')
        || ocr_text.contains("bearer ")
        || ocr_text.contains("api_key")
        || ocr_text.contains("secret=")
    {
        return Err(WorkerError::new(
            "SCREENSHOT_PRIVACY_SCAN_RESIDUAL_PII",
            false,
        ));
    }
    Ok(screenshot.to_vec())
}

async fn execute_claimed_job(
    client: &RoleClient,
    model_http: Option<&Client>,
    model: Option<&ModelConfig>,
    claim: &Value,
    heartbeat_seconds: f64,
) -> Result<(), WorkerError> {
    let job_id = claim["job"]["jobId"]
        .as_str()
        .ok_or_else(|| WorkerError::new("WORKER_CLAIM_INVALID", false))?
        .to_owned();
    let claim_token = claim["claimToken"]
        .as_str()
        .ok_or_else(|| WorkerError::new("WORKER_CLAIM_INVALID", false))?
        .to_owned();

    // Validate deployment before starting lease when model role.
    if let Some(m) = model {
        match client.role {
            WorkerRole::Reviewer => {
                verify_deployment(&claim["job"], m, "REVIEWER_MODEL_DEPLOYMENT_MISMATCH")?
            }
            WorkerRole::Outcome => {
                verify_deployment(&claim["job"], m, "OUTCOME_MODEL_DEPLOYMENT_MISMATCH")?
            }
            WorkerRole::Vision => {
                verify_deployment(&claim["job"], m, "VISION_MODEL_DEPLOYMENT_MISMATCH")?
            }
            WorkerRole::Agent => {}
        }
    }

    client
        .transition(&job_id, &claim_token, "start", None)
        .await?;

    // Background lease heartbeat with cancellation signal on lease loss.
    let (lease_tx, mut lease_rx) = watch::channel(false);
    let hb_origin = client.origin.clone();
    let hb_token = client.token.clone();
    let hb_env = client.environment.clone();
    let hb_wid = client.worker_id.clone();
    let hb_role = client.role;
    let hb_http = client.http.clone();
    let hb_job = job_id.clone();
    let hb_ctok = claim_token.clone();
    let hb_interval = Duration::from_secs_f64(heartbeat_seconds.clamp(1.0, 25.0));

    let hb_handle = tokio::spawn(async move {
        let hb_client = RoleClient {
            role: hb_role,
            origin: hb_origin,
            token: hb_token,
            environment: hb_env,
            worker_id: hb_wid,
            http: hb_http,
        };
        loop {
            tokio::time::sleep(hb_interval).await;
            if hb_client
                .transition(&hb_job, &hb_ctok, "heartbeat", None)
                .await
                .is_err()
            {
                let _ = lease_tx.send(true);
                return;
            }
        }
    });

    let work_result: Result<(), WorkerError> = tokio::select! {
        _ = async {
            while !*lease_rx.borrow() {
                if lease_rx.changed().await.is_err() {
                    std::future::pending::<()>().await;
                }
            }
        } => {
            Err(WorkerError::new("WORKER_LEASE_LOST", true))
        }
        res = async {
            match client.role {
                WorkerRole::Agent => {
                    let result = client.transition(&job_id, &claim_token, "drive", None).await?;
                    let state = result.get("state").and_then(Value::as_str).unwrap_or("");
                    if !matches!(state, "WAITING" | "COMMITTED" | "FAILED") {
                        return Err(WorkerError::new("AGENT_EXECUTION_DRIVE_STATE_INVALID", false));
                    }
                    Ok(())
                }
                WorkerRole::Reviewer => {
                    let m = model.ok_or_else(|| WorkerError::new("MODEL_CONFIG_MISSING", false))?;
                    let http = model_http.ok_or_else(|| WorkerError::new("MODEL_CLIENT_MISSING", false))?;
                    let body = json!({
                        "model": m.model_name,
                        "max_output_tokens": m.maximum_output_tokens,
                        "input": [
                            {
                                "role": "system",
                                "content": [{
                                    "type": "input_text",
                                    "text": format!(
                                        "You are an independent browser-agent safety reviewer. Return one compact JSON object: {{\"decision\":\"APPROVE|REJECT\",\"reasonCodes\":[\"ONE_OR_MORE_ALLOWED_CODES\"],\"confidence\":0.0}}. Allowed reasonCodes: {}.",
                                        REVIEWER_REASON_CODES.join(", ")
                                    ),
                                }],
                            },
                            {
                                "role": "user",
                                "content": [{
                                    "type": "input_text",
                                    "text": claim["reviewPayload"].to_string(),
                                }],
                            },
                        ],
                    });
                    let (doc, raw, req_id, latency_ms) =
                        call_openai_responses(http, m, client.role.user_agent(), body).await?;
                    let (decision, reasons, confidence) =
                        parse_structured_verdict(&doc, &["APPROVE", "REJECT"], REVIEWER_REASON_CODES)?;
                    let (in_tok, out_tok) = validate_usage(&doc, m.maximum_output_tokens)?;
                    let verdict = json!({
                        "decision": decision,
                        "reasonCodes": reasons,
                        "confidence": confidence,
                        "deploymentId": m.deployment_id,
                        "modelRevision": m.model_revision,
                        "providerRequestId": req_id,
                        "inputTokens": in_tok,
                        "outputTokens": out_tok,
                        "latencyMs": latency_ms,
                        "outputHash": hex_sha256(&raw),
                    });
                    client.transition(&job_id, &claim_token, "complete", Some(verdict)).await?;
                    Ok(())
                }
                WorkerRole::Outcome => {
                    let m = model.ok_or_else(|| WorkerError::new("MODEL_CONFIG_MISSING", false))?;
                    let http = model_http.ok_or_else(|| WorkerError::new("MODEL_CLIENT_MISSING", false))?;
                    let body = json!({
                        "model": m.model_name,
                        "max_output_tokens": m.maximum_output_tokens,
                        "input": [
                            {
                                "role": "system",
                                "content": [{
                                    "type": "input_text",
                                    "text": format!(
                                        "You are an independent browser-agent outcome verifier. Return one compact JSON object: {{\"decision\":\"VERIFIED|NOT_VERIFIED\",\"reasonCodes\":[\"ONE_OR_MORE_ALLOWED_CODES\"],\"confidence\":0.0}}. Allowed reasonCodes: {}.",
                                        OUTCOME_REASON_CODES.join(", ")
                                    ),
                                }],
                            },
                            {
                                "role": "user",
                                "content": [{
                                    "type": "input_text",
                                    "text": claim["outcomePayload"].to_string(),
                                }],
                            },
                        ],
                    });
                    let (doc, raw, req_id, latency_ms) =
                        call_openai_responses(http, m, client.role.user_agent(), body).await?;
                    let (decision, reasons, confidence) = parse_structured_verdict(
                        &doc,
                        &["VERIFIED", "NOT_VERIFIED"],
                        OUTCOME_REASON_CODES,
                    )?;
                    let (in_tok, out_tok) = validate_usage(&doc, m.maximum_output_tokens)?;
                    let verdict = json!({
                        "decision": decision,
                        "reasonCodes": reasons,
                        "confidence": confidence,
                        "deploymentId": m.deployment_id,
                        "modelRevision": m.model_revision,
                        "providerRequestId": req_id,
                        "inputTokens": in_tok,
                        "outputTokens": out_tok,
                        "latencyMs": latency_ms,
                        "outputHash": hex_sha256(&raw),
                    });
                    client.transition(&job_id, &claim_token, "complete", Some(verdict)).await?;
                    Ok(())
                }
                WorkerRole::Vision => {
                    let m = model.ok_or_else(|| WorkerError::new("MODEL_CONFIG_MISSING", false))?;
                    let http = model_http.ok_or_else(|| WorkerError::new("MODEL_CLIENT_MISSING", false))?;
                    let shot_url = claim["screenshotUrl"]
                        .as_str()
                        .ok_or_else(|| WorkerError::new("CHALLENGE_VISUAL_CLAIM_INVALID", false))?;
                    let validated_url = validate_screenshot_url(
                        shot_url,
                        &client.environment,
                        &m.allowed_screenshot_hosts,
                    )?;
                    let shot_resp = http
                        .get(&validated_url)
                        .header(ACCEPT, "image/jpeg")
                        .send()
                        .await
                        .map_err(|_| WorkerError::new("SCREENSHOT_DOWNLOAD_FAILED", true))?;
                    if shot_resp.status() != StatusCode::OK {
                        return Err(WorkerError::new("SCREENSHOT_RESPONSE_INVALID", false));
                    }
                    let shot_bytes = shot_resp
                        .bytes()
                        .await
                        .map_err(|_| WorkerError::new("SCREENSHOT_DOWNLOAD_FAILED", true))?
                        .to_vec();
                    if shot_bytes.len() > MAX_SCREENSHOT_BYTES
                        || !shot_bytes.starts_with(&[0xff, 0xd8, 0xff])
                    {
                        return Err(WorkerError::new("SCREENSHOT_RESPONSE_INVALID", false));
                    }
                    let sanitized = if m.skip_local_ocr_for_tests {
                        shot_bytes
                    } else {
                        run_local_ocr_privacy_check(&shot_bytes).await?
                    };
                    let b64 = base64::engine::general_purpose::STANDARD.encode(&sanitized);
                    let body = json!({
                        "model": m.model_name,
                        "max_output_tokens": m.maximum_output_tokens,
                        "input": [{
                            "role": "user",
                            "content": [
                                {
                                    "type": "input_text",
                                    "text": "Locate only the visible low-risk browser verification target. Return JSON {\"decision\":\"ACT|ESCALATE\",\"confidence\":0.0,\"actions\":[]}.",
                                },
                                {
                                    "type": "input_image",
                                    "image_url": format!("data:image/jpeg;base64,{b64}"),
                                }
                            ]
                        }]
                    });
                    let (doc, raw, req_id, latency_ms) =
                        call_openai_responses(http, m, client.role.user_agent(), body).await?;
                    let parsed: Value = serde_json::from_str(&extract_output_text(&doc)?)
                        .map_err(|_| WorkerError::new("MODEL_PROVIDER_VERDICT_INVALID", true))?;
                    let decision = parsed
                        .get("decision")
                        .and_then(Value::as_str)
                        .filter(|d| matches!(*d, "ACT" | "ESCALATE"))
                        .ok_or_else(|| WorkerError::new("MODEL_PROVIDER_VERDICT_INVALID", true))?;
                    let confidence = parsed
                        .get("confidence")
                        .and_then(Value::as_f64)
                        .filter(|c| c.is_finite() && (0.0..=1.0).contains(c))
                        .ok_or_else(|| WorkerError::new("MODEL_PROVIDER_VERDICT_INVALID", true))?;
                    let actions = parsed
                        .get("actions")
                        .and_then(Value::as_array)
                        .cloned()
                        .unwrap_or_default();
                    let (in_tok, out_tok) = validate_usage(&doc, m.maximum_output_tokens)?;
                    let payload = json!({
                        "decision": decision,
                        "confidence": confidence,
                        "actions": actions,
                        "deploymentId": m.deployment_id,
                        "modelRevision": m.model_revision,
                        "providerRequestId": req_id,
                        "inputTokens": in_tok,
                        "outputTokens": out_tok,
                        "latencyMs": latency_ms,
                        "outputHash": hex_sha256(&raw),
                    });
                    client.transition(&job_id, &claim_token, "complete", Some(payload)).await?;
                    Ok(())
                }
            }
        } => res,
    };

    hb_handle.abort();

    if let Err(err) = work_result {
        if !err.code.ends_with("LEASE_LOST")
            && !err.code.ends_with("LEASE_EXPIRED")
            && !err.code.ends_with("CLAIM_TOKEN_INVALID")
        {
            let _ = client
                .transition(
                    &job_id,
                    &claim_token,
                    "fail",
                    Some(json!({
                        "failureCode": err.code,
                        "retryable": err.retryable,
                    })),
                )
                .await;
        }
        return Err(err);
    }
    Ok(())
}

/// Runs all enabled roles concurrently in one Tokio process with isolated credentials, queues, and
/// semaphores.
pub async fn run_supervisor(
    config: SupervisorConfig,
    shutdown_rx: watch::Receiver<bool>,
) -> Result<HashMap<WorkerRole, usize>> {
    ensure!(
        !config.roles.is_empty(),
        "at least one worker role must be enabled"
    );
    let origin = validate_control_plane_origin(&config.control_plane_url, &config.environment)?;
    let total_roles = config.roles.len();
    let ready_roles = Arc::new(Mutex::new(HashSet::<WorkerRole>::new()));
    let counts = Arc::new(Mutex::new(HashMap::<WorkerRole, usize>::new()));
    let mut handles = Vec::with_capacity(total_roles);

    for role_cfg in config.roles {
        let client = Arc::new(RoleClient::new(
            role_cfg.role,
            origin.clone(),
            role_cfg.control_plane_token.clone(),
            config.control_plane_ca_file.as_deref(),
            config.environment.clone(),
            role_cfg.worker_id.clone(),
        )?);
        let model_cfg = match role_cfg.model {
            Some(mut m) => {
                m.model_endpoint = validate_model_endpoint(
                    &m.model_endpoint,
                    &config.environment,
                    &m.allowed_model_hosts,
                )?;
                let m_http = build_http_client(
                    m.model_ca_file.as_deref(),
                    Duration::from_secs(m.timeout_seconds.clamp(1, 300)),
                )?;
                Some((Arc::new(m), Arc::new(m_http)))
            }
            None => None,
        };

        let sem = Arc::new(Semaphore::new(role_cfg.concurrency.clamp(1, 32)));
        let poll_base = config.poll_seconds.clamp(0.1, 60.0);
        let heartbeat_secs = config.heartbeat_seconds;
        let once = config.once;
        let ready_file = config.ready_file.clone();
        let ready_roles_c = ready_roles.clone();
        let counts_c = counts.clone();
        let mut role_shutdown = shutdown_rx.clone();

        handles.push(tokio::spawn(async move {
            let mut backoff_secs = poll_base;
            let role = client.role;
            info!(role = role.as_str(), worker_id = %client.worker_id, "worker role loop started");
            loop {
                if *role_shutdown.borrow() {
                    break;
                }
                let permit = match sem.clone().acquire_owned().await {
                    Ok(p) => p,
                    Err(_) => break,
                };
                let claim_res = tokio::select! {
                    _ = role_shutdown.changed() => {
                        drop(permit);
                        break;
                    }
                    r = client.claim(model_cfg.as_ref().map(|(m, _)| m.as_ref())) => r,
                };
                match claim_res {
                    Ok(Some(claim)) => {
                        mark_role_ready(&ready_roles_c, total_roles, ready_file.as_deref(), role)
                            .await;
                        backoff_secs = poll_base;
                        let exec_res = execute_claimed_job(
                            &client,
                            model_cfg.as_ref().map(|(_, h)| h.as_ref()),
                            model_cfg.as_ref().map(|(m, _)| m.as_ref()),
                            &claim,
                            heartbeat_secs,
                        )
                        .await;
                        drop(permit);
                        match exec_res {
                            Ok(()) => {
                                *counts_c.lock().await.entry(role).or_insert(0) += 1;
                            }
                            Err(err) => {
                                warn!(role = role.as_str(), code = %err.code, "job execution failed");
                                if once {
                                    return Err(anyhow!(err.code));
                                }
                            }
                        }
                        if once {
                            break;
                        }
                    }
                    Ok(None) => {
                        drop(permit);
                        mark_role_ready(&ready_roles_c, total_roles, ready_file.as_deref(), role)
                            .await;
                        if once {
                            break;
                        }
                        tokio::select! {
                            _ = role_shutdown.changed() => break,
                            _ = tokio::time::sleep(Duration::from_secs_f64(backoff_secs)) => {}
                        }
                        backoff_secs = (backoff_secs * 2.0).min(30.0);
                    }
                    Err(err) => {
                        drop(permit);
                        warn!(role = role.as_str(), code = %err.code, "claim failed");
                        if once {
                            return Err(anyhow!(err.code));
                        }
                        tokio::select! {
                            _ = role_shutdown.changed() => break,
                            _ = tokio::time::sleep(Duration::from_secs_f64(backoff_secs)) => {}
                        }
                        backoff_secs = (backoff_secs * 2.0).min(30.0);
                    }
                }
            }
            Ok::<(), anyhow::Error>(())
        }));
    }

    for h in handles {
        h.await??;
    }
    let _ = shutdown_rx.borrow();
    let out = counts.lock().await.clone();
    Ok(out)
}

async fn mark_role_ready(
    ready_roles: &Arc<Mutex<HashSet<WorkerRole>>>,
    total_roles: usize,
    ready_file: Option<&Path>,
    role: WorkerRole,
) {
    let mut set = ready_roles.lock().await;
    set.insert(role);
    if set.len() == total_roles {
        if let Some(path) = ready_file {
            let _ = write_ready_file(path);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use axum::extract::{Path as AxumPath, State};
    use axum::http::HeaderMap;
    use axum::routing::{get, post};
    use axum::{Json, Router};
    use std::sync::atomic::{AtomicBool, Ordering};
    use uuid::Uuid;

    #[derive(Clone, Default)]
    struct MockState {
        claimed_agent: Arc<AtomicBool>,
        claimed_reviewer: Arc<AtomicBool>,
        claimed_outcome: Arc<AtomicBool>,
        claimed_vision: Arc<AtomicBool>,
        completed: Arc<Mutex<Vec<(String, String)>>>,
        base_url: Arc<Mutex<String>>,
    }

    fn token_43() -> String {
        "T".repeat(43)
    }

    #[tokio::test]
    async fn supervisor_runs_all_four_roles_with_isolated_tokens_in_single_rust_process() {
        let state = MockState::default();
        let app = Router::new()
            .route(
                "/api/v1/:endpoint",
                post(
                    |State(s): State<MockState>,
                     AxumPath(endpoint): AxumPath<String>,
                     headers: HeaderMap| async move {
                        match endpoint.as_str() {
                            "agent-worker-jobs:claim" => {
                                assert_eq!(headers["authorization"], "Bearer tok-agent");
                                assert_eq!(headers["x-roles"], "AGENT_WORKER");
                                if s.claimed_agent.swap(true, Ordering::SeqCst) {
                                    return (StatusCode::NO_CONTENT, Json(Value::Null));
                                }
                                (
                                    StatusCode::OK,
                                    Json(json!({
                                        "job": {
                                            "jobId": "ajob_12345678901234567890",
                                            "taskId": "agt_1234567890123456",
                                            "protocolVersion": "agent-worker/v1",
                                            "state": "CLAIMED"
                                        },
                                        "claimToken": token_43(),
                                    })),
                                )
                            }
                            "agent-review-jobs:claim" => {
                                assert_eq!(headers["authorization"], "Bearer tok-reviewer");
                                assert_eq!(headers["x-roles"], "REVIEWER_WORKER");
                                if s.claimed_reviewer.swap(true, Ordering::SeqCst) {
                                    return (StatusCode::NO_CONTENT, Json(Value::Null));
                                }
                                (
                                    StatusCode::OK,
                                    Json(json!({
                                        "job": {
                                            "jobId": "rjob_12345678901234567890",
                                            "taskId": "agt_1234567890123456",
                                            "protocolVersion": "reviewer-worker/v1",
                                            "state": "CLAIMED",
                                            "deployment": {
                                                "deploymentId": "dep-1",
                                                "modelName": "gpt-test",
                                                "modelRevision": "rev-1",
                                                "providerType": "OPENAI_RESPONSES",
                                                "maximumOutputTokens": 512
                                            }
                                        },
                                        "reviewPayload": { "taskId": "agt_1234567890123456", "goal": "search docs" },
                                        "claimToken": token_43(),
                                    })),
                                )
                            }
                            "agent-outcome-jobs:claim" => {
                                assert_eq!(headers["authorization"], "Bearer tok-outcome");
                                assert_eq!(headers["x-roles"], "OUTCOME_VERIFIER_WORKER");
                                if s.claimed_outcome.swap(true, Ordering::SeqCst) {
                                    return (StatusCode::NO_CONTENT, Json(Value::Null));
                                }
                                (
                                    StatusCode::OK,
                                    Json(json!({
                                        "job": {
                                            "jobId": "ojob_12345678901234567890",
                                            "taskId": "agt_1234567890123456",
                                            "protocolVersion": "outcome-verifier-worker/v1",
                                            "state": "CLAIMED",
                                            "deployment": {
                                                "deploymentId": "dep-1",
                                                "modelName": "gpt-test",
                                                "modelRevision": "rev-1",
                                                "providerType": "OPENAI_RESPONSES",
                                                "maximumOutputTokens": 512
                                            }
                                        },
                                        "outcomePayload": { "taskId": "agt_1234567890123456", "goal": "confirm" },
                                        "claimToken": token_43(),
                                    })),
                                )
                            }
                            "challenge-visual-jobs:claim" => {
                                assert_eq!(headers["authorization"], "Bearer tok-vision");
                                assert_eq!(headers["x-roles"], "VISION_WORKER");
                                if s.claimed_vision.swap(true, Ordering::SeqCst) {
                                    return (StatusCode::NO_CONTENT, Json(Value::Null));
                                }
                                let base = s.base_url.lock().await.clone();
                                (
                                    StatusCode::OK,
                                    Json(json!({
                                        "job": {
                                            "jobId": "cvj_12345678901234567890",
                                            "state": "CLAIMED",
                                            "deployment": {
                                                "deploymentId": "dep-1",
                                                "modelName": "gpt-test",
                                                "modelRevision": "rev-1",
                                                "providerType": "OPENAI_RESPONSES",
                                                "maximumOutputTokens": 512
                                            }
                                        },
                                        "screenshotUrl": format!("{base}/shot.jpg"),
                                        "claimToken": token_43(),
                                    })),
                                )
                            }
                            _ => (StatusCode::NOT_FOUND, Json(Value::Null)),
                        }
                    },
                ),
            )
            .route(
                "/api/v1/:queue/:job_action",
                post(
                    |State(s): State<MockState>,
                     AxumPath((queue, job_action)): AxumPath<(String, String)>,
                     Json(body): Json<Value>| async move {
                        let (job_id, action) = job_action.split_once(':').unwrap();
                        match (queue.as_str(), action) {
                            ("agent-worker-jobs", "start") => {
                                Json(json!({"jobId": job_id, "state": "RUNNING"}))
                            }
                            ("agent-worker-jobs", "drive") => {
                                s.completed.lock().await.push(("agent".into(), job_id.into()));
                                Json(json!({"jobId": job_id, "state": "COMMITTED"}))
                            }
                            ("agent-review-jobs", "start") => {
                                Json(json!({"jobId": job_id, "state": "RUNNING"}))
                            }
                            ("agent-review-jobs", "complete") => {
                                assert_eq!(body["decision"], "APPROVE");
                                s.completed.lock().await.push(("reviewer".into(), job_id.into()));
                                Json(json!({"jobId": job_id, "state": "COMPLETED"}))
                            }
                            ("agent-outcome-jobs", "start") => {
                                Json(json!({"jobId": job_id, "state": "RUNNING"}))
                            }
                            ("agent-outcome-jobs", "complete") => {
                                assert_eq!(body["decision"], "VERIFIED");
                                s.completed.lock().await.push(("outcome".into(), job_id.into()));
                                Json(json!({"jobId": job_id, "state": "COMPLETED"}))
                            }
                            ("challenge-visual-jobs", "start") => {
                                Json(json!({"jobId": job_id, "state": "RUNNING"}))
                            }
                            ("challenge-visual-jobs", "complete") => {
                                assert_eq!(body["decision"], "ESCALATE");
                                s.completed.lock().await.push(("vision".into(), job_id.into()));
                                Json(json!({"jobId": job_id, "state": "COMPLETED"}))
                            }
                            _ => Json(json!({"jobId": job_id, "state": "UNKNOWN"})),
                        }
                    },
                ),
            )
            .route(
                "/shot.jpg",
                get(|| async { ([("content-type", "image/jpeg")], vec![0xffu8, 0xd8, 0xff, 0xd9]) }),
            )
            .route(
                "/v1/responses",
                post(|headers: HeaderMap| async move {
                    let ua = headers["user-agent"].to_str().unwrap_or("");
                    let output_text = if ua.contains("reviewer") {
                        r#"{"decision":"APPROVE","reasonCodes":["SAFE"],"confidence":0.95}"#
                    } else if ua.contains("outcome") {
                        r#"{"decision":"VERIFIED","reasonCodes":["GOAL_SATISFIED"],"confidence":0.98}"#
                    } else {
                        r#"{"decision":"ESCALATE","confidence":0.5,"actions":[]}"#
                    };
                    Json(json!({
                        "id": "resp_1",
                        "model": "gpt-test",
                        "output_text": output_text,
                        "usage": { "input_tokens": 120, "output_tokens": 24 }
                    }))
                }),
            )
            .with_state(state.clone());

        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap();
        let base_url = format!("http://{addr}");
        *state.base_url.lock().await = base_url.clone();
        let srv = tokio::spawn(async move { axum::serve(listener, app).await.unwrap() });

        let ready_file = std::env::temp_dir().join(format!("ws-ready-{}", Uuid::new_v4().simple()));
        let mk_model = || ModelConfig {
            deployment_id: "dep-1".into(),
            model_endpoint: format!("{base_url}/v1"),
            model_api_key: "sk-test".into(),
            model_ca_file: None,
            model_name: "gpt-test".into(),
            expected_response_model: Some("gpt-test".into()),
            model_revision: "rev-1".into(),
            allowed_model_hosts: vec![],
            allowed_screenshot_hosts: vec![],
            maximum_output_tokens: 512,
            timeout_seconds: 30,
            skip_local_ocr_for_tests: true,
        };

        let cfg = SupervisorConfig {
            control_plane_url: base_url.clone(),
            control_plane_ca_file: None,
            environment: "test".into(),
            poll_seconds: 0.1,
            heartbeat_seconds: 10.0,
            ready_file: Some(ready_file.clone()),
            once: true,
            roles: vec![
                RoleRuntimeConfig {
                    role: WorkerRole::Agent,
                    worker_id: "w-agent".into(),
                    control_plane_token: "tok-agent".into(),
                    concurrency: 2,
                    model: None,
                },
                RoleRuntimeConfig {
                    role: WorkerRole::Reviewer,
                    worker_id: "w-rev".into(),
                    control_plane_token: "tok-reviewer".into(),
                    concurrency: 2,
                    model: Some(mk_model()),
                },
                RoleRuntimeConfig {
                    role: WorkerRole::Outcome,
                    worker_id: "w-out".into(),
                    control_plane_token: "tok-outcome".into(),
                    concurrency: 2,
                    model: Some(mk_model()),
                },
                RoleRuntimeConfig {
                    role: WorkerRole::Vision,
                    worker_id: "w-vis".into(),
                    control_plane_token: "tok-vision".into(),
                    concurrency: 1,
                    model: Some(mk_model()),
                },
            ],
        };

        let (_tx, rx) = watch::channel(false);
        let counts = run_supervisor(cfg, rx).await.unwrap();
        assert_eq!(counts.get(&WorkerRole::Agent), Some(&1));
        assert_eq!(counts.get(&WorkerRole::Reviewer), Some(&1));
        assert_eq!(counts.get(&WorkerRole::Outcome), Some(&1));
        assert_eq!(counts.get(&WorkerRole::Vision), Some(&1));
        assert!(ready_file.exists());
        assert_eq!(state.completed.lock().await.len(), 4);

        let _ = fs::remove_file(&ready_file);
        srv.abort();
    }

    #[test]
    fn rejects_forbidden_keys_and_non_https_in_production() {
        let bad = json!({ "taskId": "agt_1", "nested": { "capabilityToken": "leak" } });
        assert!(contains_forbidden_key(&bad, &[]));
        assert!(validate_control_plane_origin("http://cp.example.com", "production").is_err());
        assert!(validate_control_plane_origin("https://cp.example.com", "production").is_ok());
        assert!(validate_model_endpoint("http://api.openai.com/v1", "production", &[]).is_err());
        assert!(validate_model_endpoint(
            "https://api.openai.com/v1",
            "production",
            &["api.openai.com".into()]
        )
        .is_ok());
    }
}
