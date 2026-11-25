//! Pure-Rust fixed-suite Chromium runner for the isolated Runtime Validation Worker.
//!
//! 100% CLI and JSON-wire compatible drop-in replacement for
//! `apps/validation-worker/runtime_validation_runner.py`.

use serde_json::{json, Map, Value};
use std::collections::{BTreeMap, HashSet};
use std::fs;
use std::io::Read;
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::thread;
use std::time::{Duration, Instant};
use uuid::Uuid;

pub const MAX_CATALOG_BYTES: usize = 1024 * 1024;
pub const MAX_DOM_BYTES: usize = 4 * 1024 * 1024;
pub const MAX_BROWSER_STDERR_BYTES: usize = 1024 * 1024;
pub const MAX_REPLAY_CASES: usize = 1000;

#[derive(Debug, Clone)]
pub struct ValidationRunnerArgs {
    pub browser: String,
    pub browser_args: Vec<String>,
    pub suite_catalog: PathBuf,
    pub case_timeout_seconds: u64,
    pub allow_http: bool,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RunnerError(pub String);

impl RunnerError {
    pub fn new(code: impl Into<String>) -> Self {
        Self(code.into())
    }
}

impl std::fmt::Display for RunnerError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(&self.0)
    }
}

impl std::error::Error for RunnerError {}

pub fn load_catalog(path: &Path) -> Result<Value, RunnerError> {
    let meta = fs::metadata(path).map_err(|_| RunnerError::new("SUITE_CATALOG_INVALID"))?;
    if !meta.is_file() || meta.len() == 0 || meta.len() as usize > MAX_CATALOG_BYTES {
        return Err(RunnerError::new("SUITE_CATALOG_INVALID"));
    }
    let raw = fs::read_to_string(path).map_err(|_| RunnerError::new("SUITE_CATALOG_INVALID"))?;
    let doc: Value =
        serde_json::from_str(&raw).map_err(|_| RunnerError::new("SUITE_CATALOG_INVALID"))?;
    if !doc.is_object() || !doc.get("datasets").is_some_and(Value::is_object) {
        return Err(RunnerError::new("SUITE_CATALOG_INVALID"));
    }
    Ok(doc)
}

#[cfg(unix)]
fn kill_process_tree(child: &mut Child) {
    let pid = child.id() as i32;
    if pid > 0 {
        // Kill the process group started with setsid/setpgid via libc-free `kill -9 -<pgid>` fallback
        // or direct child.kill().
        let _ = Command::new("/bin/kill")
            .args(["-9", &format!("-{pid}")])
            .stdin(Stdio::null())
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .status();
    }
    let _ = child.kill();
}

#[cfg(not(unix))]
fn kill_process_tree(child: &mut Child) {
    let _ = child.kill();
}

pub fn run_bounded(
    command: &[String],
    timeout_seconds: u64,
    max_stdout_bytes: usize,
) -> Result<(i32, Vec<u8>), RunnerError> {
    if command.is_empty() {
        return Err(RunnerError::new("BROWSER_START_FAILED"));
    }
    let mut cmd = Command::new(&command[0]);
    cmd.args(&command[1..])
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped());

    #[cfg(unix)]
    {
        use std::os::unix::process::CommandExt;
        // Put child in its own process group so timeout/oversize cleanup terminates Chromium helpers.
        cmd.process_group(0);
    }

    let mut child = cmd
        .spawn()
        .map_err(|_| RunnerError::new("BROWSER_START_FAILED"))?;
    let mut stdout_pipe = child
        .stdout
        .take()
        .ok_or_else(|| RunnerError::new("BROWSER_START_FAILED"))?;
    let mut stderr_pipe = child
        .stderr
        .take()
        .ok_or_else(|| RunnerError::new("BROWSER_START_FAILED"))?;

    let output_too_large = Arc::new(AtomicBool::new(false));
    let out_flag = output_too_large.clone();
    let stdout_thread = thread::spawn(move || {
        let mut out = Vec::new();
        let mut buf = [0u8; 64 * 1024];
        loop {
            match stdout_pipe.read(&mut buf) {
                Ok(0) => break,
                Ok(n) => {
                    let remaining = max_stdout_bytes.saturating_add(1).saturating_sub(out.len());
                    if remaining > 0 {
                        let take = n.min(remaining);
                        out.extend_from_slice(&buf[..take]);
                    }
                    if n > remaining || out.len() > max_stdout_bytes {
                        out_flag.store(true, Ordering::SeqCst);
                        break;
                    }
                }
                Err(_) => break,
            }
        }
        out
    });

    let err_flag = output_too_large.clone();
    let stderr_thread = thread::spawn(move || {
        let mut err_len = 0usize;
        let mut buf = [0u8; 64 * 1024];
        loop {
            match stderr_pipe.read(&mut buf) {
                Ok(0) => break,
                Ok(n) => {
                    err_len = err_len.saturating_add(n);
                    if err_len > MAX_BROWSER_STDERR_BYTES {
                        err_flag.store(true, Ordering::SeqCst);
                        break;
                    }
                }
                Err(_) => break,
            }
        }
    });

    let deadline = Instant::now() + Duration::from_secs(timeout_seconds.max(1));
    let mut timed_out = false;
    let status = loop {
        if output_too_large.load(Ordering::SeqCst) {
            kill_process_tree(&mut child);
            break child.wait().ok();
        }
        if Instant::now() >= deadline {
            timed_out = true;
            kill_process_tree(&mut child);
            break child.wait().ok();
        }
        match child.try_wait() {
            Ok(Some(st)) => break Some(st),
            Ok(None) => thread::sleep(Duration::from_millis(50)),
            Err(_) => break None,
        }
    };

    let stdout_bytes = stdout_thread
        .join()
        .map_err(|_| RunnerError::new("BROWSER_OUTPUT_TOO_LARGE"))?;
    let _ = stderr_thread.join();

    if timed_out {
        return Err(RunnerError::new("BROWSER_TIMEOUT"));
    }
    if output_too_large.load(Ordering::SeqCst) {
        return Err(RunnerError::new("BROWSER_OUTPUT_TOO_LARGE"));
    }
    let code = status.and_then(|s| s.code()).unwrap_or(-1);
    Ok((code, stdout_bytes))
}

pub fn extract_version_token(raw: &[u8]) -> Option<String> {
    let text = String::from_utf8_lossy(raw);
    for token in text.split(|c: char| !c.is_ascii_alphanumeric() && c != '.') {
        let parts: Vec<&str> = token.split('.').collect();
        if (2..=4).contains(&parts.len())
            && parts
                .iter()
                .all(|p| !p.is_empty() && p.bytes().all(|b| b.is_ascii_digit()))
        {
            return Some(token.to_owned());
        }
    }
    None
}

pub fn browser_version(browser: &str) -> Result<String, RunnerError> {
    let cmd = vec![browser.to_owned(), "--version".to_owned()];
    let (code, stdout) = run_bounded(&cmd, 10, 64 * 1024)?;
    if code != 0 {
        return Err(RunnerError::new("BROWSER_VERSION_UNAVAILABLE"));
    }
    extract_version_token(&stdout).ok_or_else(|| RunnerError::new("BROWSER_VERSION_UNAVAILABLE"))
}

pub fn valid_host(host: &str) -> bool {
    if host.is_empty() || host.len() > 253 || host != host.to_ascii_lowercase() {
        return false;
    }
    host.split('.').all(|label| {
        let bytes = label.as_bytes();
        (1..=63).contains(&bytes.len())
            && bytes[0].is_ascii_alphanumeric()
            && bytes[bytes.len() - 1].is_ascii_alphanumeric()
            && bytes
                .iter()
                .all(|b| b.is_ascii_alphanumeric() || *b == b'-')
    })
}

fn valid_case_id(id: &str) -> bool {
    let bytes = id.as_bytes();
    (2..=128).contains(&bytes.len())
        && bytes[0].is_ascii_uppercase()
        && bytes
            .iter()
            .all(|b| b.is_ascii_uppercase() || b.is_ascii_digit() || *b == b'_')
}

fn validate_case_url(
    url: &str,
    allow_http: bool,
    authorized_hosts: Option<&HashSet<String>>,
    err_code: &'static str,
) -> Result<(), RunnerError> {
    let parsed = reqwest::Url::parse(url).map_err(|_| RunnerError::new(err_code))?;
    let scheme_ok = match parsed.scheme() {
        "https" => true,
        "http" => allow_http,
        _ => false,
    };
    if !scheme_ok
        || !parsed.username().is_empty()
        || parsed.password().is_some()
        || parsed.fragment().is_some()
    {
        return Err(RunnerError::new(err_code));
    }
    let host = parsed
        .host_str()
        .ok_or_else(|| RunnerError::new(err_code))?
        .to_ascii_lowercase();
    if let Some(allowed) = authorized_hosts {
        if !valid_host(&host) || !allowed.contains(&host) {
            return Err(RunnerError::new(err_code));
        }
    }
    if let Some(port) = parsed.port() {
        if port == 0 {
            return Err(RunnerError::new(err_code));
        }
    }
    Ok(())
}

pub fn validate_replay_dataset<'a>(
    dataset: &'a Value,
    allow_http: bool,
    declared_capabilities: &HashSet<String>,
) -> Result<&'a Vec<Value>, RunnerError> {
    let auth = dataset
        .get("authorization")
        .and_then(Value::as_object)
        .ok_or_else(|| RunnerError::new("REPLAY_AUTHORIZATION_INVALID"))?;
    if auth.get("containsProductionData") != Some(&Value::Bool(false))
        || auth.get("personalData") != Some(&Value::Bool(false))
        || auth.get("credentials") != Some(&Value::Bool(false))
        || !auth
            .get("basis")
            .and_then(Value::as_str)
            .is_some_and(|b| !b.trim().is_empty())
    {
        return Err(RunnerError::new("REPLAY_AUTHORIZATION_INVALID"));
    }

    let raw_hosts = auth
        .get("allowedHosts")
        .and_then(Value::as_array)
        .ok_or_else(|| RunnerError::new("REPLAY_AUTHORIZED_HOSTS_INVALID"))?;
    if raw_hosts.is_empty() || raw_hosts.len() > 100 {
        return Err(RunnerError::new("REPLAY_AUTHORIZED_HOSTS_INVALID"));
    }
    let mut authorized_hosts = HashSet::with_capacity(raw_hosts.len());
    for entry in raw_hosts {
        let Some(h) = entry.as_str() else {
            return Err(RunnerError::new("REPLAY_AUTHORIZED_HOSTS_INVALID"));
        };
        if !valid_host(h) || !authorized_hosts.insert(h.to_owned()) {
            return Err(RunnerError::new("REPLAY_AUTHORIZED_HOSTS_INVALID"));
        }
    }

    let cases = dataset
        .get("cases")
        .and_then(Value::as_array)
        .ok_or_else(|| RunnerError::new("REPLAY_DATASET_INVALID"))?;
    if cases.is_empty() || cases.len() > MAX_REPLAY_CASES {
        return Err(RunnerError::new("REPLAY_DATASET_INVALID"));
    }

    let mut seen_ids = HashSet::with_capacity(cases.len());
    let mut required_count = 0usize;
    for case in cases {
        let obj = case
            .as_object()
            .ok_or_else(|| RunnerError::new("REPLAY_CASE_INVALID"))?;
        let id = obj
            .get("id")
            .and_then(Value::as_str)
            .ok_or_else(|| RunnerError::new("REPLAY_CASE_INVALID"))?;
        if !valid_case_id(id) || !seen_ids.insert(id.to_owned()) {
            return Err(RunnerError::new("REPLAY_CASE_INVALID"));
        }
        let required = match obj.get("required") {
            None => true,
            Some(Value::Bool(b)) => *b,
            Some(_) => return Err(RunnerError::new("REPLAY_CASE_INVALID")),
        };
        if let Some(cap) = obj.get("capability") {
            if !cap.is_null() {
                let Some(cap_str) = cap.as_str() else {
                    return Err(RunnerError::new("REPLAY_CASE_CAPABILITY_INVALID"));
                };
                if !declared_capabilities.contains(cap_str) {
                    return Err(RunnerError::new("REPLAY_CASE_CAPABILITY_INVALID"));
                }
            }
        }
        if required {
            required_count += 1;
        }
        let url = obj
            .get("url")
            .and_then(Value::as_str)
            .ok_or_else(|| RunnerError::new("REPLAY_CASE_URL_REJECTED"))?;
        validate_case_url(
            url,
            allow_http,
            Some(&authorized_hosts),
            "REPLAY_CASE_URL_REJECTED",
        )?;
    }

    if required_count < 1 {
        return Err(RunnerError::new("REPLAY_REQUIRED_CASE_MISSING"));
    }
    Ok(cases)
}

pub fn run_case(
    browser: &str,
    browser_args: &[String],
    case: &Value,
    allow_http: bool,
    timeout_seconds: u64,
) -> Result<bool, RunnerError> {
    let url = case.get("url").and_then(Value::as_str).unwrap_or_default();
    validate_case_url(url, allow_http, None, "SUITE_CASE_URL_REJECTED")?;

    let profile_dir = std::env::temp_dir().join(format!(
        "browsercloud-validation-{}",
        Uuid::new_v4().simple()
    ));
    fs::create_dir_all(&profile_dir).map_err(|_| RunnerError::new("RUNNER_INTERNAL_ERROR"))?;

    let mut command = vec![
        browser.to_owned(),
        "--headless=new".to_owned(),
        "--disable-gpu".to_owned(),
        "--disable-dev-shm-usage".to_owned(),
        format!("--user-data-dir={}", profile_dir.display()),
    ];
    command.extend(browser_args.iter().cloned());
    command.push("--dump-dom".to_owned());
    command.push(url.to_owned());

    let run_res = run_bounded(&command, timeout_seconds, MAX_DOM_BYTES);
    let _ = fs::remove_dir_all(&profile_dir);
    let (returncode, stdout) = run_res?;
    if returncode != 0 {
        return Ok(false);
    }
    let Ok(dom) = String::from_utf8(stdout) else {
        return Ok(false);
    };
    let expected_ok = match case.get("expectedText") {
        None | Some(Value::Null) => true,
        Some(Value::String(s)) => dom.contains(s.as_str()),
        Some(other) => dom.contains(&other.to_string()),
    };
    let forbidden_ok = match case.get("forbiddenText") {
        None | Some(Value::Null) => true,
        Some(Value::String(s)) => !dom.contains(s.as_str()),
        Some(other) => !dom.contains(&other.to_string()),
    };
    Ok(expected_ok && forbidden_ok)
}

pub fn execute(args: &ValidationRunnerArgs, validation: &Value) -> Result<Value, RunnerError> {
    let job = validation
        .get("job")
        .and_then(Value::as_object)
        .ok_or_else(|| RunnerError::new("VALIDATION_JOB_MISSING"))?;
    let actual_version = browser_version(&args.browser)?;
    let expected_version = job
        .get("browserVersion")
        .and_then(Value::as_str)
        .unwrap_or_default();
    if expected_version != "stable" && actual_version != expected_version {
        return Err(RunnerError::new("BROWSER_VERSION_MISMATCH"));
    }

    let catalog = load_catalog(&args.suite_catalog)?;
    let dataset_id = validation
        .get("replayDatasetId")
        .and_then(Value::as_str)
        .ok_or_else(|| RunnerError::new("REPLAY_DATASET_NOT_FOUND"))?;
    let dataset = catalog
        .get("datasets")
        .and_then(Value::as_object)
        .and_then(|d| d.get(dataset_id))
        .filter(|v| v.is_object())
        .ok_or_else(|| RunnerError::new("REPLAY_DATASET_NOT_FOUND"))?;

    if dataset.get("suiteVersion") != validation.get("suiteVersion") {
        return Err(RunnerError::new("SUITE_VERSION_MISMATCH"));
    }
    if dataset.get("persona") != validation.get("persona") {
        return Err(RunnerError::new("PERSONA_MISMATCH"));
    }

    let declared_obj = dataset
        .get("declaredCapabilities")
        .and_then(Value::as_object)
        .ok_or_else(|| RunnerError::new("REPLAY_DATASET_INVALID"))?;
    let mut declared_keys = HashSet::with_capacity(declared_obj.len());
    let mut observed: BTreeMap<String, bool> = BTreeMap::new();
    for (k, v) in declared_obj {
        if !v.is_boolean() {
            return Err(RunnerError::new("REPLAY_DATASET_INVALID"));
        }
        declared_keys.insert(k.clone());
        observed.insert(k.clone(), false);
    }

    let cases = validate_replay_dataset(dataset, args.allow_http, &declared_keys)?;

    let mut required_tests = 0usize;
    let mut required_failures = 0usize;
    let mut optional_tests = 0usize;
    let mut optional_failures = 0usize;
    let mut optional_codes: Vec<String> = Vec::new();

    for case in cases {
        let required = case
            .get("required")
            .and_then(Value::as_bool)
            .unwrap_or(true);
        let passed = run_case(
            &args.browser,
            &args.browser_args,
            case,
            args.allow_http,
            args.case_timeout_seconds,
        )?;
        if let Some(cap) = case.get("capability").and_then(Value::as_str) {
            if let Some(slot) = observed.get_mut(cap) {
                *slot = *slot || passed;
            }
        }
        if required {
            required_tests += 1;
            if !passed {
                required_failures += 1;
            }
        } else {
            optional_tests += 1;
            if !passed {
                optional_failures += 1;
                if let Some(id) = case.get("id").and_then(Value::as_str) {
                    optional_codes.push(id.to_owned());
                }
            }
        }
    }

    if required_tests < 1 {
        return Err(RunnerError::new("REPLAY_REQUIRED_CASE_MISSING"));
    }

    let observed_map: Map<String, Value> = observed
        .into_iter()
        .map(|(k, v)| (k, Value::Bool(v)))
        .collect();

    Ok(json!({
        "requiredTests": required_tests,
        "requiredFailures": required_failures,
        "optionalTests": optional_tests,
        "optionalFailures": optional_failures,
        "declaredCapabilities": declared_obj,
        "observedCapabilities": Value::Object(observed_map),
        "optionalFailureCodes": optional_codes,
        "personaConsistent": true,
    }))
}

#[cfg(test)]
mod tests {
    use super::*;
    #[cfg(unix)]
    use std::os::unix::fs::PermissionsExt;

    #[test]
    fn validates_dataset_authorization_and_runs_mock_chromium_suite() {
        let dir = std::env::temp_dir().join(format!("val-runner-test-{}", Uuid::new_v4().simple()));
        fs::create_dir_all(&dir).unwrap();

        let fake_browser = dir.join("fake-chromium.sh");
        fs::write(
            &fake_browser,
            r#"#!/bin/sh
if [ "$1" = "--version" ]; then
  echo "Chromium 156.0.7000.1"
  exit 0
fi
last=""
for arg in "$@"; do last="$arg"; done
case "$last" in
  *pass*) echo "<html><body>READY_OK</body></html>"; exit 0 ;;
  *) echo "<html><body>BLOCKED_ERR</body></html>"; exit 0 ;;
esac
"#,
        )
        .unwrap();
        #[cfg(unix)]
        fs::set_permissions(&fake_browser, fs::Permissions::from_mode(0o755)).unwrap();

        let catalog_path = dir.join("catalog.json");
        fs::write(
            &catalog_path,
            serde_json::to_vec(&json!({
                "datasets": {
                    "ds_1": {
                        "suiteVersion": "v1",
                        "persona": "standard",
                        "declaredCapabilities": { "dom": true, "optional_feature": true },
                        "authorization": {
                            "containsProductionData": false,
                            "personalData": false,
                            "credentials": false,
                            "basis": "synthetic test fixture",
                            "allowedHosts": ["example.test"]
                        },
                        "cases": [
                            {
                                "id": "REQ_PASS",
                                "required": true,
                                "capability": "dom",
                                "url": "https://example.test/pass",
                                "expectedText": "READY_OK"
                            },
                            {
                                "id": "OPT_FAIL",
                                "required": false,
                                "capability": "optional_feature",
                                "url": "https://example.test/fail",
                                "expectedText": "READY_OK",
                                "forbiddenText": "BLOCKED_ERR"
                            }
                        ]
                    }
                }
            }))
            .unwrap(),
        )
        .unwrap();

        let args = ValidationRunnerArgs {
            browser: fake_browser.to_string_lossy().into_owned(),
            browser_args: Vec::new(),
            suite_catalog: catalog_path,
            case_timeout_seconds: 10,
            allow_http: false,
        };
        let input = json!({
            "job": { "browserVersion": "156.0.7000.1" },
            "replayDatasetId": "ds_1",
            "suiteVersion": "v1",
            "persona": "standard"
        });
        let out = execute(&args, &input).unwrap();
        assert_eq!(out["requiredTests"], 1);
        assert_eq!(out["requiredFailures"], 0);
        assert_eq!(out["optionalTests"], 1);
        assert_eq!(out["optionalFailures"], 1);
        assert_eq!(out["observedCapabilities"]["dom"], true);
        assert_eq!(out["observedCapabilities"]["optional_feature"], false);
        assert_eq!(out["optionalFailureCodes"], json!(["OPT_FAIL"]));
        assert_eq!(out["personaConsistent"], true);

        // Version mismatch fails closed.
        let bad_ver = json!({
            "job": { "browserVersion": "999.0.0.0" },
            "replayDatasetId": "ds_1",
            "suiteVersion": "v1",
            "persona": "standard"
        });
        assert_eq!(
            execute(&args, &bad_ver).unwrap_err(),
            RunnerError::new("BROWSER_VERSION_MISMATCH")
        );

        let _ = fs::remove_dir_all(&dir);
    }
}
