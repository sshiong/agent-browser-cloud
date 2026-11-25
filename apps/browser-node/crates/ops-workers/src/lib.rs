//! Pure-Rust least-privilege Recovery GameDay Worker and Runtime Validation Worker.
//!
//! 100% protocol-compatible drop-in replacements for `apps/gameday-worker/gameday_worker.py`
//! and `apps/validation-worker/validation_worker.py`, eliminating the last Python worker
//! containers from L1/L2 operations.

use anyhow::{anyhow, bail, ensure, Context, Result};
use reqwest::header::{HeaderMap, HeaderValue, ACCEPT, AUTHORIZATION, CONTENT_TYPE, USER_AGENT};
use reqwest::{Certificate, Client, StatusCode};
use serde_json::{json, Value};
use std::collections::{BTreeMap, BTreeSet};
use std::fs;
use std::path::{Path, PathBuf};
use std::process::Stdio;
use std::time::Duration;
use tokio::io::{AsyncBufReadExt, AsyncReadExt, AsyncWriteExt, BufReader};
use tokio::process::Command;
use tokio::sync::watch;

pub const MAX_RESPONSE_BYTES: usize = 1024 * 1024;
pub const MAX_RUNNER_OUTPUT_BYTES: usize = 1024 * 1024;
pub const CLAIM_WAIT_SECONDS: u64 = 15;

pub fn read_private_secret(path: &Path) -> Result<String> {
    ensure!(path.is_absolute(), "SECRET_PATH_NOT_ABSOLUTE");
    let meta = fs::symlink_metadata(path).map_err(|_| anyhow!("SECRET_FILE_UNAVAILABLE"))?;
    ensure!(
        !meta.file_type().is_symlink() && meta.is_file(),
        "SECRET_FILE_NOT_REGULAR"
    );
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        let mode = meta.permissions().mode() & 0o777;
        ensure!(
            (mode & 0o007) == 0 && matches!(mode, 0o400 | 0o600 | 0o440),
            "SECRET_FILE_PERMISSIONS_TOO_BROAD"
        );
    }
    let raw = fs::read(path)?;
    ensure!(
        !raw.is_empty() && raw.len() <= 16 * 1024,
        "SECRET_FILE_SIZE_INVALID"
    );
    let val = String::from_utf8(raw)?.trim().to_owned();
    ensure!(
        !val.is_empty() && !val.contains('\n') && !val.contains('\r') && !val.contains('\0'),
        "SECRET_VALUE_INVALID"
    );
    Ok(val)
}

pub fn validate_origin(raw: &str, environment: &str) -> Result<String> {
    let uri: http::Uri = raw
        .parse()
        .map_err(|_| anyhow!("CONTROL_PLANE_URL_REJECTED"))?;
    let scheme = uri
        .scheme_str()
        .ok_or_else(|| anyhow!("CONTROL_PLANE_URL_REJECTED"))?;
    ensure!(
        matches!(scheme, "http" | "https"),
        "CONTROL_PLANE_URL_REJECTED"
    );
    let authority = uri
        .authority()
        .ok_or_else(|| anyhow!("CONTROL_PLANE_URL_REJECTED"))?;
    ensure!(
        !authority.as_str().contains('@') && uri.query().is_none(),
        "CONTROL_PLANE_URL_REJECTED"
    );
    let local = matches!(environment, "local" | "test");
    if !local {
        ensure!(scheme == "https", "CONTROL_PLANE_HTTPS_REQUIRED");
    }
    if scheme == "http" {
        ensure!(
            matches!(
                authority.host(),
                "127.0.0.1" | "localhost" | "::1" | "[::1]"
            ),
            "CONTROL_PLANE_HTTP_LOOPBACK_ONLY"
        );
    }
    Ok(raw.trim_end_matches('/').to_owned())
}

pub fn require_absolute_executable(path: &Path) -> Result<PathBuf> {
    ensure!(path.is_absolute(), "RUNNER_ABSOLUTE_PATH_REQUIRED");
    let meta = fs::symlink_metadata(path).map_err(|_| anyhow!("RUNNER_UNAVAILABLE"))?;
    ensure!(
        !meta.file_type().is_symlink() && meta.is_file(),
        "RUNNER_NOT_REGULAR_FILE"
    );
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        ensure!(
            (meta.permissions().mode() & 0o111) != 0,
            "RUNNER_NOT_EXECUTABLE"
        );
    }
    Ok(path.to_path_buf())
}

fn build_http(ca_file: Option<&Path>, timeout: Duration) -> Result<Client> {
    let mut b = Client::builder()
        .no_proxy()
        .redirect(reqwest::redirect::Policy::none())
        .timeout(timeout);
    if let Some(ca) = ca_file {
        let pem = fs::read(ca)?;
        b = b.add_root_certificate(Certificate::from_pem(&pem)?);
    }
    Ok(b.build()?)
}

pub struct OpsControlPlaneClient {
    origin: String,
    token: String,
    environment: String,
    worker_id: String,
    role_header: &'static str,
    user_agent: &'static str,
    http: Client,
}

impl OpsControlPlaneClient {
    pub fn new(
        origin: String,
        token: String,
        ca_file: Option<&Path>,
        environment: String,
        worker_id: String,
        role_header: &'static str,
        user_agent: &'static str,
    ) -> Result<Self> {
        let validated = validate_origin(&origin, &environment)?;
        let http = build_http(ca_file, Duration::from_secs(25))?;
        Ok(Self {
            origin: validated,
            token,
            environment,
            worker_id,
            role_header,
            user_agent,
            http,
        })
    }

    pub async fn post_json(&self, path: &str, body: Value) -> Result<Option<Value>> {
        let mut headers = HeaderMap::new();
        headers.insert(ACCEPT, HeaderValue::from_static("application/json"));
        headers.insert(CONTENT_TYPE, HeaderValue::from_static("application/json"));
        headers.insert(USER_AGENT, HeaderValue::from_static(self.user_agent));
        headers.insert(
            AUTHORIZATION,
            HeaderValue::from_str(&format!("Bearer {}", self.token))?,
        );
        if matches!(self.environment.as_str(), "local" | "test") {
            headers.insert("X-Tenant-Id", HeaderValue::from_static("platform-control"));
            headers.insert("X-Actor-Id", HeaderValue::from_str(&self.worker_id)?);
            headers.insert("X-Roles", HeaderValue::from_static(self.role_header));
        }
        let resp = self
            .http
            .post(format!("{}{path}", self.origin))
            .headers(headers)
            .json(&body)
            .send()
            .await
            .context("CONTROL_PLANE_UNAVAILABLE")?;
        let status = resp.status();
        let bytes = resp.bytes().await?;
        ensure!(
            bytes.len() <= MAX_RESPONSE_BYTES,
            "CONTROL_PLANE_RESPONSE_TOO_LARGE"
        );
        if status == StatusCode::NO_CONTENT {
            return Ok(None);
        }
        if status.is_client_error() || status.is_server_error() {
            bail!("CONTROL_PLANE_HTTP_{}", status.as_u16());
        }
        let doc: Value = serde_json::from_slice(&bytes)?;
        Ok(Some(doc))
    }
}

// =========================================================================
// GameDay Worker
// =========================================================================

pub fn read_gameday_catalog(path: &Path) -> Result<(Vec<String>, Vec<String>)> {
    ensure!(path.is_absolute(), "CATALOG_NOT_ABSOLUTE");
    let meta = fs::symlink_metadata(path)?;
    ensure!(
        !meta.file_type().is_symlink() && meta.is_file(),
        "CATALOG_INVALID"
    );
    let raw = fs::read(path)?;
    ensure!(raw.len() <= 1024 * 1024, "CATALOG_TOO_LARGE");
    let doc: Value = serde_json::from_slice(&raw)?;
    ensure!(
        doc.get("version").and_then(Value::as_i64) == Some(1),
        "CATALOG_VERSION_INVALID"
    );
    let scenarios = doc
        .get("scenarios")
        .and_then(Value::as_object)
        .ok_or_else(|| anyhow!("CATALOG_SCENARIOS_INVALID"))?;
    let mut codes = Vec::new();
    let mut envs = BTreeSet::new();
    for (code, entry) in scenarios {
        ensure!(
            (3..=64).contains(&code.len())
                && code.bytes().next().is_some_and(|b| b.is_ascii_uppercase())
                && code
                    .bytes()
                    .all(|b| b.is_ascii_uppercase() || b.is_ascii_digit() || b == b'_'),
            "CATALOG_SCENARIO_CODE_INVALID"
        );
        codes.push(code.clone());
        if let Some(env) = entry.get("environment").and_then(Value::as_str) {
            if matches!(env, "TEST" | "STAGING" | "PRODUCTION") {
                envs.insert(env.to_owned());
            }
        }
    }
    codes.sort();
    Ok((codes, envs.into_iter().collect()))
}

pub struct GameDayConfig {
    pub catalog_file: PathBuf,
    pub controller_token_file: PathBuf,
    pub controller_ca_file: Option<PathBuf>,
    pub runner: PathBuf,
    pub poll_seconds: f64,
    pub heartbeat_seconds: f64,
}

pub async fn run_gameday_once(client: &OpsControlPlaneClient, cfg: &GameDayConfig) -> Result<bool> {
    let (codes, envs) = read_gameday_catalog(&cfg.catalog_file)?;
    if codes.is_empty() || envs.is_empty() {
        return Ok(false);
    }
    let Some(claim) = client
        .post_json(
            &format!(
                "/api/v1/enterprise/recovery-gameday-jobs:claim?waitSeconds={CLAIM_WAIT_SECONDS}"
            ),
            json!({
                "environments": envs,
                "scenarioCodes": codes,
                "capabilities": {
                    "faultInjection": true,
                    "recovery": true,
                    "measurement": true,
                },
            }),
        )
        .await?
    else {
        return Ok(false);
    };

    let gd = &claim["gameDay"];
    let job = &gd["job"];
    let gd_id = gd["gameDayId"]
        .as_str()
        .ok_or_else(|| anyhow!("GAMEDAY_CLAIM_INVALID"))?
        .to_owned();
    let claim_token = claim["claimToken"]
        .as_str()
        .ok_or_else(|| anyhow!("GAMEDAY_CLAIM_INVALID"))?
        .to_owned();
    let recovery_only = claim
        .get("recoveryOnly")
        .and_then(Value::as_bool)
        .unwrap_or(false);
    let max_duration = gd["maximumDurationSeconds"].as_u64().unwrap_or(300);

    client
        .post_json(
            &format!("/api/v1/enterprise/recovery-gameday-jobs/{gd_id}:start"),
            json!({ "claimToken": claim_token }),
        )
        .await?;

    let mut cmd = Command::new(&cfg.runner);
    cmd.arg("--catalog-file")
        .arg(&cfg.catalog_file)
        .arg("--controller-token-file")
        .arg(&cfg.controller_token_file)
        .arg("--game-day-id")
        .arg(&gd_id)
        .arg("--scenario-code")
        .arg(job["scenarioCode"].as_str().unwrap_or(""))
        .arg("--environment")
        .arg(gd["environment"].as_str().unwrap_or(""))
        .arg("--source-region")
        .arg(gd["sourceRegion"].as_str().unwrap_or(""))
        .arg("--target-region")
        .arg(gd["targetRegion"].as_str().unwrap_or(""))
        .arg("--maximum-duration-seconds")
        .arg(max_duration.to_string())
        .env_clear()
        .env("PATH", "/usr/local/bin:/usr/bin:/bin")
        .env("LANG", "C.UTF-8")
        .env("LC_ALL", "C.UTF-8")
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::null());
    if let Some(ca) = &cfg.controller_ca_file {
        cmd.arg("--ca-file").arg(ca);
    }
    if recovery_only {
        cmd.arg("--recovery-only");
    }

    let mut child = cmd.spawn().context("GAMEDAY_RUNNER_START_FAILED")?;
    let stdout = child
        .stdout
        .take()
        .ok_or_else(|| anyhow!("GAMEDAY_RUNNER_STDOUT_MISSING"))?;
    let mut reader = BufReader::new(stdout).lines();

    let (abort_tx, mut abort_rx) = watch::channel(false);
    let hb_secs = cfg.heartbeat_seconds.clamp(5.0, 30.0);
    let hb_url = format!(
        "{}/api/v1/enterprise/recovery-gameday-jobs/{gd_id}:heartbeat",
        client.origin
    );
    let hb_http = client.http.clone();
    let hb_tok = client.token.clone();
    let hb_ctok = claim_token.clone();
    let hb_env = client.environment.clone();
    let hb_wid = client.worker_id.clone();

    let hb_task = tokio::spawn(async move {
        loop {
            tokio::time::sleep(Duration::from_secs_f64(hb_secs)).await;
            let mut req = hb_http
                .post(&hb_url)
                .header(AUTHORIZATION, format!("Bearer {hb_tok}"))
                .json(&json!({ "claimToken": hb_ctok }));
            if matches!(hb_env.as_str(), "local" | "test") {
                req = req
                    .header("X-Tenant-Id", "platform-control")
                    .header("X-Actor-Id", &hb_wid)
                    .header("X-Roles", "GAMEDAY_WORKER");
            }
            if let Ok(resp) = req.send().await {
                if let Ok(doc) = resp.json::<Value>().await {
                    if doc.get("abortRequested").and_then(Value::as_bool) == Some(true) {
                        let _ = abort_tx.send(true);
                        return;
                    }
                }
            }
        }
    });

    let mut total_bytes = 0usize;
    let mut final_result: Option<Value> = None;
    let deadline = tokio::time::Instant::now() + Duration::from_secs(max_duration + 60);
    let exec_res: Result<()> = async {
        loop {
            tokio::select! {
                _ = tokio::time::sleep_until(deadline) => {
                    let _ = child.kill().await;
                    bail!("GAMEDAY_RUNNER_TIMEOUT");
                }
                _ = abort_rx.changed() => {
                    if *abort_rx.borrow() {
                        let _ = child.kill().await;
                        bail!("GAMEDAY_ABORT_REQUESTED");
                    }
                }
                line = reader.next_line() => {
                    match line? {
                        Some(text) => {
                            total_bytes += text.len();
                            ensure!(total_bytes <= MAX_RUNNER_OUTPUT_BYTES, "GAMEDAY_OUTPUT_TOO_LARGE");
                            let ev: Value = serde_json::from_str(&text)?;
                            match ev.get("type").and_then(Value::as_str) {
                                Some("stage") => {
                                    let stage = ev["stage"].as_str().unwrap_or("");
                                    client
                                        .post_json(
                                            &format!("/api/v1/enterprise/recovery-gameday-jobs/{gd_id}:stage"),
                                            json!({ "claimToken": claim_token, "stage": stage }),
                                        )
                                        .await?;
                                }
                                Some("result") if ev.get("result").is_some_and(Value::is_object) => {
                                    ensure!(final_result.is_none(), "GAMEDAY_DUPLICATE_RESULT");
                                    final_result = Some(ev["result"].clone());
                                }
                                _ => bail!("GAMEDAY_UNAUTHORIZED_EVENT"),
                            }
                        }
                        None => break,
                    }
                }
            }
        }
        let status = child.wait().await?;
        ensure!(status.success() && final_result.is_some(), "GAMEDAY_RUNNER_FAILED");
        Ok(())
    }
    .await;

    hb_task.abort();

    match exec_res {
        Ok(()) => {
            let result = final_result.unwrap();
            let recovery_confirmed = result
                .get("recoveryConfirmed")
                .and_then(Value::as_bool)
                .unwrap_or(false);
            let failure_code = result.get("failureCode").and_then(Value::as_str);
            let aborted = result
                .get("aborted")
                .and_then(Value::as_bool)
                .unwrap_or(false);
            if recovery_only || failure_code.is_some() || aborted {
                let fault_injected = result
                    .get("faultInjected")
                    .and_then(Value::as_bool)
                    .unwrap_or(false);
                client
                    .post_json(
                        &format!("/api/v1/enterprise/recovery-gameday-jobs/{gd_id}:fail"),
                        json!({
                            "claimToken": claim_token,
                            "failureCode": failure_code.unwrap_or("GAMEDAY_RECOVERY_ONLY_COMPLETED"),
                            "retryable": !recovery_only && !fault_injected && !aborted,
                            "recoveryConfirmed": recovery_confirmed,
                        }),
                    )
                    .await?;
                return Ok(true);
            }
            let metrics = &result["metrics"];
            client
                .post_json(
                    &format!("/api/v1/enterprise/recovery-gameday-jobs/{gd_id}:complete"),
                    json!({
                        "claimToken": claim_token,
                        "result": {
                            "observedRtoSeconds": result["observedRtoSeconds"],
                            "observedRpoSeconds": result["observedRpoSeconds"],
                            "dataLossRecords": metrics["dataLossRecords"],
                            "detectionTimeSeconds": result["detectionTimeSeconds"],
                            "failoverTimeSeconds": result["observedRtoSeconds"],
                            "staleOperationCount": metrics["staleOperationCount"],
                            "userImpactCount": metrics["userImpactCount"],
                            "manualSteps": metrics["manualSteps"],
                            "runbookAccuracyPercent": metrics["runbookAccuracyPercent"],
                            "runnerEvidenceHash": result["runnerEvidenceHash"],
                            "recoveryConfirmed": recovery_confirmed,
                        }
                    }),
                )
                .await?;
            Ok(true)
        }
        Err(err) => {
            let _ = client
                .post_json(
                    &format!("/api/v1/enterprise/recovery-gameday-jobs/{gd_id}:fail"),
                    json!({
                        "claimToken": claim_token,
                        "failureCode": "GAMEDAY_WORKER_EXECUTION_FAILED",
                        "retryable": true,
                        "recoveryConfirmed": false,
                    }),
                )
                .await;
            Err(err)
        }
    }
}

// =========================================================================
// Runtime Validation Worker
// =========================================================================

#[derive(Debug, Clone)]
pub struct ValidationConfig {
    pub browser_engine: String,
    pub browser_versions: Vec<String>,
    pub operating_system: String,
    pub architecture: String,
    pub capabilities: BTreeMap<String, bool>,
    pub runner_command: Vec<String>,
    pub runner_timeout_seconds: u64,
    pub heartbeat_seconds: f64,
    pub idle_seconds: f64,
}

pub fn validate_validation_result(result: &Value) -> Result<()> {
    let map = result
        .as_object()
        .ok_or_else(|| anyhow!("RUNNER_RESULT_INVALID"))?;
    let req_tests = map
        .get("requiredTests")
        .and_then(Value::as_i64)
        .filter(|&v| v >= 1)
        .ok_or_else(|| anyhow!("RUNNER_RESULT_COUNTS_INVALID"))?;
    let req_fail = map
        .get("requiredFailures")
        .and_then(Value::as_i64)
        .filter(|&v| v >= 0 && v <= req_tests)
        .ok_or_else(|| anyhow!("RUNNER_RESULT_COUNTS_INVALID"))?;
    let opt_tests = map
        .get("optionalTests")
        .and_then(Value::as_i64)
        .filter(|&v| v >= 0)
        .ok_or_else(|| anyhow!("RUNNER_RESULT_COUNTS_INVALID"))?;
    let opt_fail = map
        .get("optionalFailures")
        .and_then(Value::as_i64)
        .filter(|&v| v >= 0 && v <= opt_tests)
        .ok_or_else(|| anyhow!("RUNNER_RESULT_COUNTS_INVALID"))?;
    let _ = (req_fail, opt_fail);
    for key in ["declaredCapabilities", "observedCapabilities"] {
        let caps = map
            .get(key)
            .and_then(Value::as_object)
            .ok_or_else(|| anyhow!("RUNNER_RESULT_CAPABILITIES_INVALID"))?;
        ensure!(
            caps.values().all(Value::is_boolean),
            "RUNNER_RESULT_CAPABILITIES_INVALID"
        );
    }
    let codes = map
        .get("optionalFailureCodes")
        .and_then(Value::as_array)
        .ok_or_else(|| anyhow!("RUNNER_RESULT_FAILURE_CODES_INVALID"))?;
    ensure!(
        codes.len() <= 256
            && codes
                .iter()
                .all(|c| c.as_str().is_some_and(|s| s.len() <= 128)),
        "RUNNER_RESULT_FAILURE_CODES_INVALID"
    );
    ensure!(
        map.get("personaConsistent").is_some_and(Value::is_boolean),
        "RUNNER_RESULT_PERSONA_INVALID"
    );
    Ok(())
}

pub async fn run_validation_once(
    client: &OpsControlPlaneClient,
    cfg: &ValidationConfig,
) -> Result<bool> {
    ensure!(
        !cfg.runner_command.is_empty(),
        "RUNNER_ABSOLUTE_PATH_REQUIRED"
    );
    require_absolute_executable(Path::new(&cfg.runner_command[0]))?;

    let Some(claim) = client
        .post_json(
            &format!(
                "/api/v1/enterprise/runtime-validation-jobs:claim?waitSeconds={CLAIM_WAIT_SECONDS}"
            ),
            json!({
                "browserEngine": cfg.browser_engine,
                "browserVersions": cfg.browser_versions,
                "operatingSystem": cfg.operating_system,
                "architecture": cfg.architecture,
                "capabilities": cfg.capabilities,
            }),
        )
        .await?
    else {
        return Ok(false);
    };

    let val_id = claim["validation"]["validationId"]
        .as_str()
        .filter(|s| s.starts_with("val_") && s.len() == 24)
        .ok_or_else(|| anyhow!("VALIDATION_CLAIM_INVALID"))?
        .to_owned();
    let claim_token = claim["claimToken"]
        .as_str()
        .filter(|s| s.len() == 43)
        .ok_or_else(|| anyhow!("VALIDATION_CLAIM_INVALID"))?
        .to_owned();
    let claim_epoch = claim["claimEpoch"]
        .as_i64()
        .ok_or_else(|| anyhow!("VALIDATION_CLAIM_INVALID"))?;

    client
        .post_json(
            &format!("/api/v1/enterprise/runtime-validation-jobs/{val_id}:start"),
            json!({ "claimToken": claim_token }),
        )
        .await?;

    let (lease_tx, mut lease_rx) = watch::channel(false);
    let hb_secs = cfg.heartbeat_seconds.clamp(1.0, 60.0);
    let hb_url = format!(
        "{}/api/v1/enterprise/runtime-validation-jobs/{val_id}:heartbeat",
        client.origin
    );
    let hb_http = client.http.clone();
    let hb_tok = client.token.clone();
    let hb_ctok = claim_token.clone();
    let hb_env = client.environment.clone();
    let hb_wid = client.worker_id.clone();

    let hb_task = tokio::spawn(async move {
        loop {
            tokio::time::sleep(Duration::from_secs_f64(hb_secs)).await;
            let mut req = hb_http
                .post(&hb_url)
                .header(AUTHORIZATION, format!("Bearer {hb_tok}"))
                .json(&json!({ "claimToken": hb_ctok }));
            if matches!(hb_env.as_str(), "local" | "test") {
                req = req
                    .header("X-Tenant-Id", "platform-control")
                    .header("X-Actor-Id", &hb_wid)
                    .header("X-Roles", "VALIDATION_WORKER");
            }
            match req.send().await {
                Ok(r) if r.status().is_success() => {}
                _ => {
                    let _ = lease_tx.send(true);
                    return;
                }
            }
        }
    });

    let mut cmd = Command::new(&cfg.runner_command[0]);
    cmd.args(&cfg.runner_command[1..])
        .env_clear()
        .env("PATH", "/usr/local/bin:/usr/bin:/bin")
        .env("LANG", "C.UTF-8")
        .env("VALIDATION_ID", &val_id)
        .env("VALIDATION_CLAIM_EPOCH", claim_epoch.to_string())
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::null());

    let mut child = cmd.spawn().context("RUNNER_START_FAILED")?;
    if let Some(mut stdin) = child.stdin.take() {
        let payload = serde_json::to_vec(&claim["validation"])?;
        stdin.write_all(&payload).await?;
    }
    let mut stdout = child
        .stdout
        .take()
        .ok_or_else(|| anyhow!("RUNNER_OUTPUT_READ_FAILED"))?;

    let timeout_dur = Duration::from_secs(cfg.runner_timeout_seconds.clamp(1, 86400));
    let exec_res: Result<Value> = tokio::select! {
        _ = tokio::time::sleep(timeout_dur) => {
            let _ = child.kill().await;
            Err(anyhow!("RUNNER_TIMEOUT"))
        }
        _ = async {
            while !*lease_rx.borrow() {
                if lease_rx.changed().await.is_err() {
                    std::future::pending::<()>().await;
                }
            }
        } => {
            let _ = child.kill().await;
            Err(anyhow!("VALIDATION_LEASE_LOST"))
        }
        res = async {
            let mut buf = Vec::new();
            let mut limited = (&mut stdout).take((MAX_RUNNER_OUTPUT_BYTES + 1) as u64);
            limited.read_to_end(&mut buf).await?;
            ensure!(buf.len() <= MAX_RUNNER_OUTPUT_BYTES, "RUNNER_OUTPUT_TOO_LARGE");
            let status = child.wait().await?;
            ensure!(status.success(), "RUNNER_EXIT_NONZERO");
            let parsed: Value = serde_json::from_slice(&buf)
                .map_err(|_| anyhow!("RUNNER_RESULT_INVALID"))?;
            validate_validation_result(&parsed)?;
            Ok(parsed)
        } => res,
    };

    hb_task.abort();

    match exec_res {
        Ok(result) => {
            client
                .post_json(
                    &format!("/api/v1/enterprise/runtime-validation-jobs/{val_id}:complete"),
                    json!({ "claimToken": claim_token, "result": result }),
                )
                .await?;
            Ok(true)
        }
        Err(err) => {
            let _ = client
                .post_json(
                    &format!("/api/v1/enterprise/runtime-validation-jobs/{val_id}:fail"),
                    json!({
                        "claimToken": claim_token,
                        "failureCode": err.to_string(),
                        "retryable": !err.to_string().contains("INVALID"),
                    }),
                )
                .await;
            Err(err)
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use axum::extract::{Path as AxumPath, State};
    use axum::routing::post;
    use axum::{Json, Router};
    use std::sync::{Arc, Mutex};
    use uuid::Uuid;

    #[tokio::test]
    async fn gameday_and_validation_workers_complete_real_runner_subprocesses() {
        let completed = Arc::new(Mutex::new(Vec::<String>::new()));
        let app = Router::new()
            .route(
                "/api/v1/enterprise/:endpoint",
                post(|AxumPath(ep): AxumPath<String>| async move {
                    match ep.as_str() {
                        "recovery-gameday-jobs:claim" => Json(json!({
                            "claimToken": "G".repeat(43),
                            "recoveryOnly": false,
                            "gameDay": {
                                "gameDayId": "gd_12345678901234567890",
                                "environment": "TEST",
                                "sourceRegion": "us-east-1",
                                "targetRegion": "us-west-2",
                                "maximumDurationSeconds": 30,
                                "job": { "scenarioCode": "PRIMARY_FAILOVER" }
                            }
                        })),
                        "runtime-validation-jobs:claim" => Json(json!({
                            "claimToken": "V".repeat(43),
                            "claimEpoch": 1,
                            "validation": {
                                "validationId": "val_12345678901234567890",
                                "browserEngine": "chromium"
                            }
                        })),
                        _ => Json(json!({})),
                    }
                }),
            )
            .route(
                "/api/v1/enterprise/:queue/:action",
                post(
                    |State(done): State<Arc<Mutex<Vec<String>>>>,
                     AxumPath((queue, action)): AxumPath<(String, String)>| async move {
                        if action.ends_with(":complete") {
                            done.lock().unwrap().push(queue);
                        }
                        Json(json!({ "ok": true }))
                    },
                ),
            )
            .with_state(completed.clone());

        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap();
        let srv = tokio::spawn(async move { axum::serve(listener, app).await.unwrap() });

        let dir = std::env::temp_dir().join(format!("ops-w-{}", Uuid::new_v4().simple()));
        fs::create_dir_all(&dir).unwrap();

        let catalog = dir.join("catalog.json");
        fs::write(
            &catalog,
            json!({ "version": 1, "scenarios": { "PRIMARY_FAILOVER": { "environment": "TEST" } } })
                .to_string(),
        )
        .unwrap();
        let ctrl_tok = dir.join("ctrl.tok");
        fs::write(&ctrl_tok, "secret-token").unwrap();

        let gd_runner = dir.join("gd-runner.sh");
        fs::write(
            &gd_runner,
            "#!/bin/sh\necho '{\"type\":\"stage\",\"stage\":\"INJECTING\"}'\necho '{\"type\":\"result\",\"result\":{\"recoveryConfirmed\":true,\"observedRtoSeconds\":12,\"observedRpoSeconds\":0,\"detectionTimeSeconds\":3,\"runnerEvidenceHash\":\"abc\",\"metrics\":{\"dataLossRecords\":0,\"staleOperationCount\":0,\"userImpactCount\":0,\"manualSteps\":0,\"runbookAccuracyPercent\":100}}}'\n",
        )
        .unwrap();
        let val_runner = dir.join("val-runner.sh");
        fs::write(
            &val_runner,
            "#!/bin/sh\ncat >/dev/null\necho '{\"requiredTests\":5,\"requiredFailures\":0,\"optionalTests\":2,\"optionalFailures\":0,\"declaredCapabilities\":{\"cdp\":true},\"observedCapabilities\":{\"cdp\":true},\"optionalFailureCodes\":[],\"personaConsistent\":true}'\n",
        )
        .unwrap();
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            fs::set_permissions(&gd_runner, fs::Permissions::from_mode(0o755)).unwrap();
            fs::set_permissions(&val_runner, fs::Permissions::from_mode(0o755)).unwrap();
        }

        let gd_client = OpsControlPlaneClient::new(
            format!("http://{addr}"),
            "tok".into(),
            None,
            "test".into(),
            "gd-1".into(),
            "GAMEDAY_WORKER",
            "agent-browser-cloud-gameday-worker/1-rust",
        )
        .unwrap();
        let worked_gd = run_gameday_once(
            &gd_client,
            &GameDayConfig {
                catalog_file: catalog,
                controller_token_file: ctrl_tok,
                controller_ca_file: None,
                runner: gd_runner,
                poll_seconds: 1.0,
                heartbeat_seconds: 10.0,
            },
        )
        .await
        .unwrap();
        assert!(worked_gd);

        let val_client = OpsControlPlaneClient::new(
            format!("http://{addr}"),
            "tok".into(),
            None,
            "test".into(),
            "val-1".into(),
            "VALIDATION_WORKER",
            "agent-browser-cloud-validation-worker/1-rust",
        )
        .unwrap();
        let worked_val = run_validation_once(
            &val_client,
            &ValidationConfig {
                browser_engine: "chromium".into(),
                browser_versions: vec!["156".into()],
                operating_system: "linux".into(),
                architecture: "arm64".into(),
                capabilities: BTreeMap::from([("cdp".into(), true)]),
                runner_command: vec![val_runner.display().to_string()],
                runner_timeout_seconds: 30,
                heartbeat_seconds: 10.0,
                idle_seconds: 1.0,
            },
        )
        .await
        .unwrap();
        assert!(worked_val);

        let list = completed.lock().unwrap().clone();
        assert!(list.contains(&"recovery-gameday-jobs".to_owned()));
        assert!(list.contains(&"runtime-validation-jobs".to_owned()));

        let _ = fs::remove_dir_all(&dir);
        srv.abort();
    }
}
