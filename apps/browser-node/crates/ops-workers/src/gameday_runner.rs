//! Pure-Rust fixed-catalog Recovery GameDay runner that always attempts bounded recovery.
//!
//! 100% CLI, NDJSON stage/result protocol, and canonical SHA-256 evidence-hash compatible
//! drop-in replacement for `apps/gameday-worker/gameday_runner.py`.

use anyhow::{anyhow, bail, ensure, Result};
use reqwest::header::{HeaderMap, HeaderValue, ACCEPT, AUTHORIZATION, CONTENT_TYPE, USER_AGENT};
use reqwest::{Certificate, Client, Method};
use serde_json::{json, Map, Value};
use sha2::{Digest, Sha256};
use std::collections::BTreeMap;
use std::fs;
use std::io::Write;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::time::{Duration, Instant};

pub const MAX_CONFIG_BYTES: usize = 1024 * 1024;
pub const MAX_RESPONSE_BYTES: usize = 64 * 1024;

#[derive(Debug, Clone)]
pub struct GameDayRunnerArgs {
    pub catalog_file: PathBuf,
    pub controller_token_file: PathBuf,
    pub ca_file: Option<PathBuf>,
    pub game_day_id: String,
    pub scenario_code: String,
    pub environment: String,
    pub source_region: String,
    pub target_region: String,
    pub maximum_duration_seconds: u64,
    pub recovery_only: bool,
}

#[derive(Debug, Clone)]
pub struct ValidatedScenario {
    pub controller_base_url: String,
    pub inject_path: String,
    pub recover_path: String,
    pub health_path: String,
    pub evidence_path: String,
    pub healthy_status: u16,
    pub fault_status: u16,
    pub observation_seconds: u64,
}

fn bounded_file(path: &Path, maximum: usize) -> Result<Vec<u8>> {
    ensure!(
        path.is_absolute(),
        "configured file must be an absolute non-symlink path"
    );
    let meta = fs::symlink_metadata(path)?;
    ensure!(
        !meta.file_type().is_symlink() && meta.is_file(),
        "configured file must be an absolute non-symlink path"
    );
    ensure!(
        meta.len() as usize <= maximum,
        "configured file exceeds the size limit"
    );
    let data = fs::read(path)?;
    ensure!(
        data.len() <= maximum,
        "configured file exceeds the size limit"
    );
    Ok(data)
}

pub fn read_catalog(path: &Path) -> Result<Value> {
    let raw = bounded_file(path, MAX_CONFIG_BYTES)?;
    let doc: Value = serde_json::from_slice(&raw)?;
    ensure!(
        doc.get("version").and_then(Value::as_i64) == Some(1)
            && doc.get("scenarios").is_some_and(Value::is_object),
        "GameDay catalog must use version 1 and contain scenarios"
    );
    Ok(doc)
}

pub fn read_controller_token(path: &Path) -> Result<String> {
    ensure!(
        path.is_absolute(),
        "controller token must be an absolute regular non-symlink file"
    );
    let meta = fs::symlink_metadata(path)?;
    ensure!(
        !meta.file_type().is_symlink() && meta.is_file(),
        "controller token must be an absolute regular non-symlink file"
    );
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        let mode = meta.permissions().mode() & 0o777;
        ensure!(
            matches!(mode, 0o600 | 0o440 | 0o400),
            "controller token must be owner 0600/0400 or process-group 0440"
        );
    }
    let raw = bounded_file(path, 8192)?;
    let token = String::from_utf8(raw)?.trim().to_owned();
    ensure!(
        !token.is_empty() && !token.contains('\n') && !token.contains('\r'),
        "controller token must be a non-empty single line"
    );
    Ok(token)
}

fn validate_path(val: Option<&Value>, name: &str) -> Result<String> {
    let raw = val
        .and_then(Value::as_str)
        .ok_or_else(|| anyhow!("{name} must be an absolute controller path"))?;
    ensure!(
        raw.starts_with('/') && !raw.starts_with("//"),
        "{name} must be an absolute controller path"
    );
    ensure!(
        !raw.contains('?') && !raw.contains('#') && !raw.contains("://"),
        "{name} cannot change the configured controller origin"
    );
    Ok(raw.to_owned())
}

fn bounded_int(
    val: Option<&Value>,
    default: Option<i64>,
    min: i64,
    max: i64,
    name: &str,
) -> Result<i64> {
    let num = match val {
        None => default.ok_or_else(|| anyhow!("{name} is outside the allowed range"))?,
        Some(Value::Number(n)) => n
            .as_i64()
            .ok_or_else(|| anyhow!("{name} is outside the allowed range"))?,
        Some(_) => bail!("{name} is outside the allowed range"),
    };
    ensure!(
        (min..=max).contains(&num),
        "{name} is outside the allowed range"
    );
    Ok(num)
}

pub fn validate_entry(entry: Option<&Value>, environment: &str) -> Result<ValidatedScenario> {
    let obj = entry
        .and_then(Value::as_object)
        .filter(|o| o.get("environment").and_then(Value::as_str) == Some(environment))
        .ok_or_else(|| anyhow!("scenario is not authorized for the claimed environment"))?;
    let base_url = obj
        .get("controllerBaseUrl")
        .and_then(Value::as_str)
        .ok_or_else(|| anyhow!("scenario controllerBaseUrl is required"))?;
    let parsed = reqwest::Url::parse(base_url)
        .map_err(|_| anyhow!("scenario controllerBaseUrl must be a fixed HTTP(S) origin"))?;
    let scheme = parsed.scheme();
    let host = parsed
        .host_str()
        .ok_or_else(|| anyhow!("scenario controllerBaseUrl must be a fixed HTTP(S) origin"))?;
    ensure!(
        matches!(scheme, "http" | "https")
            && parsed.username().is_empty()
            && parsed.password().is_none()
            && parsed.query().is_none()
            && parsed.fragment().is_none()
            && matches!(parsed.path(), "" | "/"),
        "scenario controllerBaseUrl must be a fixed HTTP(S) origin"
    );
    if environment != "TEST" {
        ensure!(
            scheme == "https",
            "non-test GameDay controllers require HTTPS"
        );
    } else if scheme == "http" {
        ensure!(
            matches!(host, "127.0.0.1" | "localhost" | "::1" | "[::1]"),
            "test HTTP GameDay controllers must be loopback-only"
        );
    }

    Ok(ValidatedScenario {
        controller_base_url: base_url.trim_end_matches('/').to_owned(),
        inject_path: validate_path(obj.get("injectPath"), "injectPath")?,
        recover_path: validate_path(obj.get("recoverPath"), "recoverPath")?,
        health_path: validate_path(obj.get("healthPath"), "healthPath")?,
        evidence_path: validate_path(obj.get("evidencePath"), "evidencePath")?,
        healthy_status: bounded_int(
            obj.get("healthyStatus"),
            Some(200),
            100,
            599,
            "healthyStatus",
        )? as u16,
        fault_status: bounded_int(obj.get("faultStatus"), Some(503), 100, 599, "faultStatus")?
            as u16,
        observation_seconds: bounded_int(
            obj.get("observationSeconds"),
            Some(1),
            0,
            30,
            "observationSeconds",
        )? as u64,
    })
}

fn build_client(ca_file: Option<&Path>) -> Result<Client> {
    let mut builder = Client::builder()
        .no_proxy()
        .redirect(reqwest::redirect::Policy::none())
        .timeout(Duration::from_secs(10));
    if let Some(ca) = ca_file {
        let pem = fs::read(ca)?;
        builder = builder.add_root_certificate(Certificate::from_pem(&pem)?);
    }
    Ok(builder.build()?)
}

async fn request_json(
    http: &Client,
    method: Method,
    url: &str,
    token: &str,
    body: Option<Value>,
    timeout_secs: u64,
) -> Result<(u16, Option<Value>)> {
    let mut headers = HeaderMap::new();
    headers.insert(ACCEPT, HeaderValue::from_static("application/json"));
    headers.insert(
        USER_AGENT,
        HeaderValue::from_static("agent-browser-cloud-gameday-runner/1"),
    );
    headers.insert(
        AUTHORIZATION,
        HeaderValue::from_str(&format!("Bearer {token}"))?,
    );
    let mut req = http
        .request(method, url)
        .timeout(Duration::from_secs(timeout_secs))
        .headers(headers);
    if let Some(payload) = body {
        req = req
            .header(CONTENT_TYPE, "application/json")
            .body(serde_json::to_vec(&payload)?);
    }
    let resp = req.send().await?;
    let status = resp.status().as_u16();
    let content_type = resp
        .headers()
        .get(CONTENT_TYPE)
        .and_then(|v| v.to_str().ok())
        .map(|v| {
            v.split(';')
                .next()
                .unwrap_or("")
                .trim()
                .to_ascii_lowercase()
        })
        .unwrap_or_default();
    let bytes = resp.bytes().await?;
    ensure!(
        bytes.len() <= MAX_RESPONSE_BYTES,
        "controller response exceeded the size limit"
    );
    if bytes.is_empty() {
        return Ok((status, None));
    }
    ensure!(
        content_type == "application/json",
        "controller response must be application/json"
    );
    let parsed: Value = serde_json::from_slice(&bytes)?;
    ensure!(
        parsed.is_object(),
        "controller response must be a JSON object"
    );
    Ok((status, Some(parsed)))
}

fn emit_stage(stage: &str) {
    let line = json!({ "type": "stage", "stage": stage });
    println!("{}", serde_json::to_string(&line).unwrap_or_default());
    let _ = std::io::stdout().flush();
}

pub fn emit_result(result: &Value) {
    let line = json!({ "type": "result", "result": result });
    println!("{}", serde_json::to_string(&line).unwrap_or_default());
    let _ = std::io::stdout().flush();
}

async fn poll_health(
    http: &Client,
    entry: &ValidatedScenario,
    token: &str,
    expected_status: u16,
    deadline: Instant,
    cancelled: &AtomicBool,
) -> Result<f64> {
    let started = Instant::now();
    let mut last_status: Option<u16> = None;
    let url = format!("{}{}", entry.controller_base_url, entry.health_path);
    while Instant::now() < deadline {
        let (status, _) = request_json(http, Method::GET, &url, token, None, 5).await?;
        last_status = Some(status);
        if status == expected_status {
            return Ok(started.elapsed().as_secs_f64());
        }
        if cancelled.load(Ordering::SeqCst) && expected_status == entry.fault_status {
            bail!("GAMEDAY_ABORT_REQUESTED");
        }
        tokio::time::sleep(Duration::from_millis(250)).await;
    }
    bail!("health transition timed out at HTTP_{last_status:?}")
}

fn evidence_metrics(payload: Option<&Value>) -> Result<Value> {
    let obj = payload
        .and_then(Value::as_object)
        .ok_or_else(|| anyhow!("controller evidence is missing"))?;
    let mut out = Map::new();
    for key in [
        "dataLossRecords",
        "staleOperationCount",
        "userImpactCount",
        "manualSteps",
        "runbookAccuracyPercent",
    ] {
        let upper = if key == "runbookAccuracyPercent" {
            100
        } else {
            2_147_483_647
        };
        let v = bounded_int(obj.get(key), None, 0, upper, key)?;
        out.insert(key.to_owned(), Value::Number(v.into()));
    }
    Ok(Value::Object(out))
}

async fn execute_recovery(
    http: &Client,
    entry: &ValidatedScenario,
    token: &str,
    game_day_id: &str,
    deadline: Instant,
    cancelled: &AtomicBool,
) -> (bool, Option<f64>, Option<Value>, Option<String>) {
    let fut = async {
        let recovery_deadline = deadline.max(Instant::now() + Duration::from_secs(30));
        emit_stage("RECOVERING");
        let recover_url = format!("{}{}", entry.controller_base_url, entry.recover_path);
        let (status, _) = request_json(
            http,
            Method::POST,
            &recover_url,
            token,
            Some(json!({
                "gameDayId": game_day_id,
                "idempotencyKey": format!("{game_day_id}:recover"),
            })),
            10,
        )
        .await?;
        ensure!(status == 200, "recover returned HTTP_{status}");
        let recovery_seconds = poll_health(
            http,
            entry,
            token,
            entry.healthy_status,
            recovery_deadline,
            cancelled,
        )
        .await?;
        emit_stage("VALIDATING");
        let evidence_url = format!("{}{}", entry.controller_base_url, entry.evidence_path);
        let (ev_status, evidence) =
            request_json(http, Method::GET, &evidence_url, token, None, 10).await?;
        ensure!(ev_status == 200, "evidence returned HTTP_{ev_status}");
        let metrics = evidence_metrics(evidence.as_ref())?;
        Ok::<(f64, Value), anyhow::Error>((recovery_seconds, metrics))
    };
    match fut.await {
        Ok((secs, metrics)) => (true, Some(secs), Some(metrics), None),
        Err(_) => (false, None, None, Some("RUNTIMEERROR".to_owned())),
    }
}

fn sort_json(v: &Value) -> Value {
    match v {
        Value::Object(map) => {
            let mut sorted = BTreeMap::new();
            for (k, val) in map {
                sorted.insert(k.clone(), sort_json(val));
            }
            let mut out = Map::new();
            for (k, val) in sorted {
                out.insert(k, val);
            }
            Value::Object(out)
        }
        Value::Array(arr) => Value::Array(arr.iter().map(sort_json).collect()),
        other => other.clone(),
    }
}

fn hex_encode(bytes: &[u8]) -> String {
    let mut out = String::with_capacity(bytes.len() * 2);
    for b in bytes {
        use std::fmt::Write;
        let _ = write!(&mut out, "{b:02x}");
    }
    out
}

#[allow(clippy::too_many_arguments)]
fn build_result_payload(
    args: &GameDayRunnerArgs,
    started: Instant,
    detection_seconds: Option<f64>,
    recovery_seconds: Option<f64>,
    evidence: Option<Value>,
    recovery_confirmed: bool,
    aborted: bool,
    failure_code: Option<String>,
    fault_injected: bool,
) -> Value {
    let metrics = evidence.unwrap_or_else(|| {
        json!({
            "dataLossRecords": 0,
            "staleOperationCount": 0,
            "userImpactCount": 0,
            "manualSteps": 0,
            "runbookAccuracyPercent": 0,
        })
    });
    let observed_rto = started.elapsed().as_secs_f64().ceil().max(0.0) as i64;
    let proof = json!({
        "gameDayId": args.game_day_id,
        "scenarioCode": args.scenario_code,
        "environment": args.environment,
        "detectionTimeSeconds": detection_seconds.map(|s| s.ceil() as i64),
        "recoveryTimeSeconds": recovery_seconds.map(|s| s.ceil() as i64),
        "observedRtoSeconds": observed_rto,
        "metrics": metrics,
        "recoveryConfirmed": recovery_confirmed,
        "faultInjected": fault_injected,
        "aborted": aborted,
        "failureCode": failure_code,
    });
    let canonical = serde_json::to_vec(&sort_json(&proof)).unwrap_or_default();
    let digest = hex_encode(&Sha256::digest(&canonical));

    let mut result = proof.as_object().cloned().unwrap_or_default();
    result.insert("observedRpoSeconds".to_owned(), Value::Number(0.into()));
    result.insert(
        "runnerEvidenceHash".to_owned(),
        Value::String(format!("sha256:{digest}")),
    );
    Value::Object(result)
}

pub async fn run_gameday(args: &GameDayRunnerArgs, cancelled: Arc<AtomicBool>) -> Result<Value> {
    ensure!(
        (30..=7200).contains(&args.maximum_duration_seconds),
        "maximum duration is outside the platform bound"
    );
    let catalog = read_catalog(&args.catalog_file)?;
    let entry = validate_entry(
        catalog
            .get("scenarios")
            .and_then(Value::as_object)
            .and_then(|s| s.get(&args.scenario_code)),
        &args.environment,
    )?;
    let token = read_controller_token(&args.controller_token_file)?;
    let http = build_client(args.ca_file.as_deref())?;
    let started = Instant::now();
    let deadline = started + Duration::from_secs(args.maximum_duration_seconds);

    if args.recovery_only {
        let (confirmed, recovery_seconds, evidence, recovery_error) = execute_recovery(
            &http,
            &entry,
            &token,
            &args.game_day_id,
            deadline,
            &cancelled,
        )
        .await;
        let failure_code = if confirmed {
            Some("GAMEDAY_RECOVERY_ONLY_COMPLETED".to_owned())
        } else {
            recovery_error
        };
        return Ok(build_result_payload(
            args,
            started,
            None,
            recovery_seconds,
            evidence,
            confirmed,
            true,
            failure_code,
            true,
        ));
    }

    let mut injected = false;
    let mut detection_seconds: Option<f64> = None;
    let mut failure_code: Option<String> = None;
    let mut aborted = false;

    let phase_res: Result<()> = async {
        emit_stage("INJECTING");
        let inject_url = format!("{}{}", entry.controller_base_url, entry.inject_path);
        let (status, _) = request_json(
            &http,
            Method::POST,
            &inject_url,
            &token,
            Some(json!({
                "gameDayId": args.game_day_id,
                "scenarioCode": args.scenario_code,
                "sourceRegion": args.source_region,
                "targetRegion": args.target_region,
                "idempotencyKey": format!("{}:inject", args.game_day_id),
            })),
            10,
        )
        .await?;
        ensure!(status == 200, "inject returned HTTP_{status}");
        injected = true;
        emit_stage("FAULT_INJECTED");
        let det = poll_health(
            &http,
            &entry,
            &token,
            entry.fault_status,
            deadline,
            &cancelled,
        )
        .await?;
        detection_seconds = Some(det);
        emit_stage("OBSERVING");
        let obs_deadline = Instant::now() + Duration::from_secs(entry.observation_seconds);
        while Instant::now() < obs_deadline {
            if cancelled.load(Ordering::SeqCst) {
                bail!("GAMEDAY_ABORT_REQUESTED");
            }
            let remaining = obs_deadline.saturating_duration_since(Instant::now());
            tokio::time::sleep(Duration::from_millis(250).min(remaining)).await;
        }
        Ok(())
    }
    .await;

    if let Err(err) = phase_res {
        let msg = err.to_string();
        if msg.contains("GAMEDAY_ABORT_REQUESTED") || cancelled.load(Ordering::SeqCst) {
            aborted = true;
            failure_code = Some("GAMEDAY_ABORT_REQUESTED".to_owned());
        } else {
            failure_code = Some("GAMEDAY_RUNTIMEERROR".to_owned());
        }
    }

    let mut recovery_confirmed = false;
    let mut recovery_seconds = None;
    let mut evidence = None;
    if injected || failure_code.is_some() || cancelled.load(Ordering::SeqCst) {
        let (confirmed, rec_secs, ev, rec_err) = execute_recovery(
            &http,
            &entry,
            &token,
            &args.game_day_id,
            deadline,
            &cancelled,
        )
        .await;
        recovery_confirmed = confirmed;
        recovery_seconds = rec_secs;
        evidence = ev;
        if !confirmed {
            failure_code =
                Some(rec_err.unwrap_or_else(|| "GAMEDAY_RECOVERY_UNCONFIRMED".to_owned()));
        }
    }

    Ok(build_result_payload(
        args,
        started,
        detection_seconds,
        recovery_seconds,
        evidence,
        recovery_confirmed,
        aborted,
        failure_code,
        injected,
    ))
}

#[cfg(test)]
mod tests {
    use super::*;
    use axum::http::StatusCode;
    use axum::routing::{get, post};
    use axum::{Json, Router};
    #[cfg(unix)]
    use std::os::unix::fs::PermissionsExt;
    use uuid::Uuid;

    #[tokio::test]
    async fn executes_full_inject_observe_recover_and_hashes_canonical_proof() {
        let faulted = Arc::new(AtomicBool::new(false));
        let f_inj = faulted.clone();
        let f_rec = faulted.clone();
        let f_hlth = faulted.clone();

        let app = Router::new()
            .route(
                "/inject",
                post(move || {
                    let f = f_inj.clone();
                    async move {
                        f.store(true, Ordering::SeqCst);
                        (StatusCode::OK, Json(json!({ "ok": true })))
                    }
                }),
            )
            .route(
                "/recover",
                post(move || {
                    let f = f_rec.clone();
                    async move {
                        f.store(false, Ordering::SeqCst);
                        (StatusCode::OK, Json(json!({ "ok": true })))
                    }
                }),
            )
            .route(
                "/health",
                get(move || {
                    let f = f_hlth.clone();
                    async move {
                        if f.load(Ordering::SeqCst) {
                            (
                                StatusCode::SERVICE_UNAVAILABLE,
                                Json(json!({ "status": "DOWN" })),
                            )
                        } else {
                            (StatusCode::OK, Json(json!({ "status": "UP" })))
                        }
                    }
                }),
            )
            .route(
                "/evidence",
                get(|| async {
                    Json(json!({
                        "dataLossRecords": 0,
                        "staleOperationCount": 0,
                        "userImpactCount": 0,
                        "manualSteps": 0,
                        "runbookAccuracyPercent": 100
                    }))
                }),
            );

        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap();
        let srv = tokio::spawn(async move { axum::serve(listener, app).await.unwrap() });

        let dir = std::env::temp_dir().join(format!("gd-runner-test-{}", Uuid::new_v4().simple()));
        fs::create_dir_all(&dir).unwrap();
        let tok_file = dir.join("controller.token");
        fs::write(&tok_file, "gd-secret-token\n").unwrap();
        #[cfg(unix)]
        fs::set_permissions(&tok_file, fs::Permissions::from_mode(0o600)).unwrap();

        let cat_file = dir.join("catalog.json");
        fs::write(
            &cat_file,
            serde_json::to_vec(&json!({
                "version": 1,
                "scenarios": {
                    "REGION_FAILOVER": {
                        "environment": "TEST",
                        "controllerBaseUrl": format!("http://{addr}"),
                        "injectPath": "/inject",
                        "recoverPath": "/recover",
                        "healthPath": "/health",
                        "evidencePath": "/evidence",
                        "healthyStatus": 200,
                        "faultStatus": 503,
                        "observationSeconds": 0
                    }
                }
            }))
            .unwrap(),
        )
        .unwrap();

        let args = GameDayRunnerArgs {
            catalog_file: cat_file,
            controller_token_file: tok_file,
            ca_file: None,
            game_day_id: "gd_1".to_owned(),
            scenario_code: "REGION_FAILOVER".to_owned(),
            environment: "TEST".to_owned(),
            source_region: "us-east-1".to_owned(),
            target_region: "us-west-2".to_owned(),
            maximum_duration_seconds: 60,
            recovery_only: false,
        };
        let out = run_gameday(&args, Arc::new(AtomicBool::new(false)))
            .await
            .unwrap();
        assert_eq!(out["recoveryConfirmed"], true);
        assert_eq!(out["faultInjected"], true);
        assert_eq!(out["aborted"], false);
        assert_eq!(out["observedRpoSeconds"], 0);
        assert!(out["runnerEvidenceHash"]
            .as_str()
            .unwrap()
            .starts_with("sha256:"));
        assert_eq!(out["metrics"]["runbookAccuracyPercent"], 100);

        srv.abort();
        let _ = fs::remove_dir_all(&dir);
    }
}
