//! Real Chromium regressions use only an owned loopback page and CDP transport.
use super::*;
use std::os::unix::fs::PermissionsExt;
use std::process::Stdio;
use tokio::io::AsyncReadExt;
use tokio::net::TcpListener;
use tokio_tungstenite::accept_async;

struct OwnedChrome {
    child: Child,
    profile: PathBuf,
    endpoint: String,
    target: String,
    websocket: String,
    fixture_url: String,
    fixture: JoinHandle<()>,
}

impl OwnedChrome {
    async fn start() -> Self {
        let fixture = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let fixture_url = format!("http://{}", fixture.local_addr().unwrap());
        let fixture = tokio::spawn(async move {
            loop {
                let (mut stream, _) = fixture.accept().await.unwrap();
                tokio::spawn(async move {
                    let mut request = [0_u8; 4096];
                    let _ = stream.read(&mut request).await;
                    let body = b"<!doctype html><title>Owned screenshot fixture</title><div id='root'></div><input type='password' value='owned-test-only'><script>document.getElementById('root').innerHTML='<button>Login</button>'; window.ticks=0; setInterval(()=>window.ticks++,20);</script>";
                    let header = format!("HTTP/1.1 200 OK\r\ncontent-type: text/html\r\ncontent-length: {}\r\nconnection: close\r\n\r\n", body.len());
                    let _ = stream.write_all(header.as_bytes()).await;
                    let _ = stream.write_all(body).await;
                });
            }
        });
        let profile = std::env::temp_dir().join(format!(
            "ab-screenshot-chrome-{}",
            uuid::Uuid::new_v4().simple()
        ));
        tokio::fs::create_dir(&profile).await.unwrap();
        tokio::fs::set_permissions(&profile, std::fs::Permissions::from_mode(0o700))
            .await
            .unwrap();
        let child = Command::new(
            std::env::var("REAL_CHROMIUM_PATH").expect("REAL_CHROMIUM_PATH is required"),
        )
        .args([
            "--headless=new",
            "--no-first-run",
            "--no-default-browser-check",
            "--disable-background-networking",
            "--no-proxy-server",
            "--remote-debugging-address=127.0.0.1",
            "--remote-debugging-port=0",
        ])
        .arg(format!("--user-data-dir={}", profile.display()))
        .arg("about:blank")
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .kill_on_drop(true)
        .spawn()
        .unwrap();
        let deadline = tokio::time::Instant::now() + Duration::from_secs(10);
        let endpoint = loop {
            if let Ok(active) = tokio::fs::read_to_string(profile.join("DevToolsActivePort")).await
            {
                if let Some(port) = active
                    .lines()
                    .next()
                    .and_then(|port| port.parse::<u16>().ok())
                    .filter(|port| *port > 0)
                {
                    break format!("http://127.0.0.1:{port}");
                }
            }
            assert!(
                tokio::time::Instant::now() < deadline,
                "owned Chrome startup deadline"
            );
            tokio::time::sleep(Duration::from_millis(50)).await;
        };
        let version: Value = reqwest::Client::new()
            .get(format!("{endpoint}/json/version"))
            .timeout(CDP_TIMEOUT)
            .send()
            .await
            .unwrap()
            .json()
            .await
            .unwrap();
        let (mut browser, _) =
            tokio_tungstenite::connect_async(version["webSocketDebuggerUrl"].as_str().unwrap())
                .await
                .unwrap();
        let target = send_command_value(
            &mut browser,
            1,
            "Target.createTarget",
            json!({"url": "about:blank"}),
        )
        .await
        .unwrap()["result"]["targetId"]
            .as_str()
            .unwrap()
            .to_owned();
        browser.close(None).await.unwrap();
        let websocket = target_websocket(&endpoint, Some(&target)).await.unwrap();
        Self {
            child,
            profile,
            endpoint,
            target,
            websocket,
            fixture_url,
            fixture,
        }
    }

    async fn connect(&self) -> ScreenshotSocket {
        tokio_tungstenite::connect_async(&self.websocket)
            .await
            .unwrap()
            .0
    }

    async fn navigate(&self, socket: &mut ScreenshotSocket, path: &str, id: i64) {
        send_command_value(
            socket,
            id,
            "Page.navigate",
            json!({"url": format!("{}/{path}", self.fixture_url)}),
        )
        .await
        .unwrap();
        let deadline = tokio::time::Instant::now() + CDP_TIMEOUT;
        loop {
            let state = send_command_value(socket, id + 1, "Runtime.evaluate", json!({
                "expression": "document.readyState==='complete' && !!document.getElementById('root')",
                "returnByValue": true,
            })).await.unwrap();
            if state
                .pointer("/result/result/value")
                .and_then(Value::as_bool)
                == Some(true)
            {
                break;
            }
            assert!(
                tokio::time::Instant::now() < deadline,
                "owned page readiness deadline"
            );
            tokio::time::sleep(Duration::from_millis(20)).await;
        }
    }

    async fn state(&self, socket: &mut ScreenshotSocket, id: i64) -> Value {
        send_command_value(socket, id, "Runtime.evaluate", json!({
            "expression": "({visible:document.visibilityState==='visible', mounted:document.getElementById('root')?.childElementCount===1, masks:document.querySelectorAll('#__agent_browser_sensitive_redaction_v1').length, ticks:window.ticks})",
            "returnByValue": true,
        })).await.unwrap()["result"]["result"]["value"].clone()
    }

    async fn stop(mut self) {
        let _ = self.child.kill().await;
        let _ = self.child.wait().await;
        self.fixture.abort();
        let _ = tokio::fs::remove_dir_all(self.profile).await;
    }
}

#[derive(Clone, Copy)]
enum Injection {
    Navigate,
    Cancel,
}

async fn proxy(
    chrome: &OwnedChrome,
    injection: Injection,
) -> (
    String,
    oneshot::Receiver<()>,
    JoinHandle<()>,
    JoinHandle<()>,
) {
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let address = listener.local_addr().unwrap();
    let upstream = chrome.websocket.clone();
    let navigation = format!("{}/during-capture", chrome.fixture_url);
    let (injected, receiver) = oneshot::channel();
    let websocket_task = tokio::spawn(async move {
        let (stream, _) = listener.accept().await.unwrap();
        let mut client = accept_async(stream).await.unwrap();
        let (mut upstream_socket, _) = tokio_tungstenite::connect_async(&upstream).await.unwrap();
        let mut injected = Some(injected);
        loop {
            tokio::select! {
                message = client.next() => {
                    let Some(Ok(message)) = message else { break };
                    if let Message::Text(text) = &message {
                        let command: Value = serde_json::from_str(text).unwrap();
                        if injected.is_some() && matches!(injection, Injection::Navigate)
                            && command["method"] == "DOM.getDocument" {
                            let (mut control, _) = tokio_tungstenite::connect_async(&upstream).await.unwrap();
                            send_command_value(&mut control, 1000, "Page.navigate", json!({"url": navigation})).await.unwrap();
                            let deadline = tokio::time::Instant::now() + CDP_TIMEOUT;
                            loop {
                                let state = send_command_value(&mut control, 1001, "Runtime.evaluate", json!({
                                    "expression": "document.readyState==='complete' && !!document.getElementById('root')",
                                    "returnByValue": true,
                                })).await.unwrap();
                                if state.pointer("/result/result/value").and_then(Value::as_bool) == Some(true) { break; }
                                assert!(tokio::time::Instant::now() < deadline, "injected navigation deadline");
                                tokio::time::sleep(Duration::from_millis(20)).await;
                            }
                            control.close(None).await.unwrap();
                            let _ = injected.take().unwrap().send(());
                        }
                        if injected.is_some() && matches!(injection, Injection::Cancel)
                            && command["method"] == "Page.captureScreenshot" {
                            let _ = injected.take().unwrap().send(());
                            // Leave this command unanswered; continue forwarding cleanup commands.
                            continue;
                        }
                    }
                    if matches!(message, Message::Close(_)) { break; }
                    if upstream_socket.send(message).await.is_err() { break; }
                }
                message = upstream_socket.next() => {
                    let Some(Ok(message)) = message else { break };
                    if client.send(message).await.is_err() { break; }
                }
            }
        }
        let _ = upstream_socket.close(None).await;
    });
    let http = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let http_address = http.local_addr().unwrap();
    let target = chrome.target.clone();
    let http_task = tokio::spawn(async move {
        let (mut stream, _) = http.accept().await.unwrap();
        let mut request = [0_u8; 2048];
        let _ = stream.read(&mut request).await.unwrap();
        let body = json!([{"id": target, "type": "page", "webSocketDebuggerUrl": format!("ws://{address}/owned")}]).to_string();
        stream.write_all(format!("HTTP/1.1 200 OK\r\ncontent-type: application/json\r\ncontent-length: {}\r\nconnection: close\r\n\r\n{}",body.len(),body).as_bytes()).await.unwrap();
    });
    (
        format!("http://{http_address}"),
        receiver,
        websocket_task,
        http_task,
    )
}

#[tokio::test]
#[ignore = "requires REAL_CHROMIUM_PATH and launches an owned loopback browser"]
async fn real_chromium_screenshot_navigation_and_cancellation_preserve_page_bootstrap() {
    let chrome = OwnedChrome::start().await;
    let mut control = chrome.connect().await;
    send_command(&mut control, 1, "Page.enable", json!({}))
        .await
        .unwrap();
    chrome.navigate(&mut control, "initial", 10).await;
    let options = ScreenshotCaptureOptions {
        evidence_id: format!("evd_{}", uuid::Uuid::new_v4().simple()),
        captured_at_ms: now_millis(),
        active_tab_id: chrome.target.clone(),
        capture_mode: "VIEWPORT".to_owned(),
        clip: None,
    };
    let capture = capture_screenshot(&chrome.endpoint, Some(&options))
        .await
        .unwrap();
    assert!(capture.content.starts_with(&[0xff, 0xd8]));
    assert_eq!(capture.redacted_region_count, 1);
    let state = chrome.state(&mut control, 20).await;
    assert_eq!(state["visible"], true);
    assert_eq!(state["mounted"], true);
    assert_eq!(state["masks"], 0);

    let (endpoint, injected, websocket, http) = proxy(&chrome, Injection::Navigate).await;
    assert!(
        capture_screenshot(&endpoint, Some(&options)).await.is_err(),
        "pixels across a document change must never return"
    );
    injected.await.unwrap();
    tokio::time::timeout(CDP_TIMEOUT, websocket)
        .await
        .unwrap()
        .unwrap();
    http.await.unwrap();
    tokio::time::sleep(Duration::from_millis(100)).await;
    let state = chrome.state(&mut control, 30).await;
    assert_eq!(state["visible"], true);
    assert_eq!(
        state["mounted"], true,
        "navigation during screenshot must preserve new-page bootstrap"
    );
    assert_eq!(state["masks"], 0);

    let (endpoint, injected, websocket, http) = proxy(&chrome, Injection::Cancel).await;
    let options_copy = options.clone();
    let caller =
        tokio::spawn(async move { capture_screenshot(&endpoint, Some(&options_copy)).await });
    injected.await.unwrap();
    caller.abort();
    assert!(caller.await.unwrap_err().is_cancelled());
    tokio::time::timeout(CDP_TIMEOUT, websocket)
        .await
        .unwrap()
        .unwrap();
    http.await.unwrap();
    let before = chrome.state(&mut control, 40).await;
    tokio::time::sleep(Duration::from_millis(100)).await;
    let after = chrome.state(&mut control, 41).await;
    assert_eq!(after["visible"], true);
    assert_eq!(after["mounted"], true);
    assert_eq!(after["masks"], 0);
    assert!(
        after["ticks"].as_u64().unwrap() > before["ticks"].as_u64().unwrap(),
        "cancelled screenshot must resume real page timers"
    );
    // A separate debugger client's pause must remain under that client's control.
    send_command(&mut control, 50, "Debugger.enable", json!({}))
        .await
        .unwrap();
    control.send(Message::Text(json!({"id": 51, "method": "Runtime.evaluate", "params": {
        "expression": "debugger;\n//# sourceURL=owned-operator-pause", "returnByValue": true,
    }}).to_string())).await.unwrap();
    tokio::time::timeout(CDP_TIMEOUT, async {
        loop {
            let Message::Text(text) = control.next().await.unwrap().unwrap() else {
                continue;
            };
            let event: Value = serde_json::from_str(&text).unwrap();
            if event["method"] == "Debugger.paused" {
                break;
            }
        }
    })
    .await
    .unwrap();
    assert!(capture_screenshot(&chrome.endpoint, Some(&options))
        .await
        .is_err());
    let paused_before = chrome.state(&mut control, 52).await;
    tokio::time::sleep(Duration::from_millis(100)).await;
    let paused_after = chrome.state(&mut control, 53).await;
    assert_eq!(
        paused_before["ticks"], paused_after["ticks"],
        "screenshot must preserve operator pause"
    );
    assert_eq!(paused_after["masks"], 0);
    send_command(&mut control, 54, "Debugger.resume", json!({}))
        .await
        .unwrap();
    send_command(&mut control, 55, "Debugger.disable", json!({}))
        .await
        .unwrap();
    control.close(None).await.unwrap();
    chrome.stop().await;
}

struct OwnedRecordingHelper {
    child: Child,
    root: PathBuf,
}

impl Drop for OwnedRecordingHelper {
    fn drop(&mut self) {
        let _ = self.child.start_kill();
        let _ = std::fs::remove_dir_all(&self.root);
    }
}

#[test]
#[ignore = "requires owned MinIO, real Chrome, compiled Helper and real privacy scanner"]
fn real_chromium_held_recording_scans_and_seals_native_on_versions() {
    std::thread::Builder::new()
        .stack_size(8 * 1024 * 1024)
        .spawn(|| {
            tokio::runtime::Builder::new_multi_thread()
                .worker_threads(2)
                .thread_stack_size(8 * 1024 * 1024)
                .enable_all()
                .build()
                .unwrap()
                .block_on(Box::pin(real_held_recording_body()));
        })
        .unwrap()
        .join()
        .unwrap();
}

async fn real_held_recording_body() {
    use aws_sdk_s3::config::{BehaviorVersion, Credentials, Region};
    let endpoint = std::env::var("TEST_OBJECT_STORAGE_ENDPOINT").unwrap();
    let url = reqwest::Url::parse(&endpoint).unwrap();
    assert_eq!(url.scheme(), "http");
    assert_eq!(url.host_str(), Some("127.0.0.1"));
    let bucket = std::env::var("TEST_OBJECT_STORAGE_BUCKET").unwrap();
    let access = std::env::var("TEST_OBJECT_STORAGE_ACCESS_KEY_ID").unwrap();
    let secret = std::env::var("TEST_OBJECT_STORAGE_SECRET_ACCESS_KEY").unwrap();
    assert!(
        access == "browsercloud-test" && secret == "browsercloud-test-secret",
        "owned fixture credentials required"
    );
    let helper_path = PathBuf::from(std::env::var("REAL_STORAGE_HELPER_PATH").unwrap());
    let scanner = PathBuf::from(std::env::var("REAL_RECORDING_PRIVACY_SCANNER_PATH").unwrap());
    assert!(helper_path.is_absolute() && scanner.is_absolute());
    let scan_test = Command::new(&scanner)
        .arg("--self-test")
        .output()
        .await
        .unwrap();
    assert!(scan_test.status.success(), "real privacy self-test failed");
    let self_test: Value = serde_json::from_slice(&scan_test.stdout).unwrap();
    assert_eq!(self_test["ready"], true);
    assert_eq!(self_test["scanVersion"], RECORDING_PRIVACY_SCAN_VERSION);
    let suffix = uuid::Uuid::new_v4().simple().to_string();
    let root = PathBuf::from("/tmp").join(format!("ab-rhe-{suffix}"));
    tokio::fs::create_dir(&root).await.unwrap();
    tokio::fs::set_permissions(&root, std::fs::Permissions::from_mode(0o700))
        .await
        .unwrap();
    let keyring = root.join("keys.json");
    tokio::fs::write(
        &keyring,
        include_bytes!("../../../../../tests/fixtures/profile-archive-keyring.json"),
    )
    .await
    .unwrap();
    tokio::fs::set_permissions(&keyring, std::fs::Permissions::from_mode(0o600))
        .await
        .unwrap();
    let socket = root.join("h.sock");
    let storage = root.join("profiles");
    let prefix = format!("real-held-e2e/{suffix}");
    let helper_child = Command::new(helper_path)
        .env("APP_ENVIRONMENT", "test")
        .env("STORAGE_HELPER_SOCKET", &socket)
        .env("PROFILE_STORAGE_ROOT", &storage)
        .env("OBJECT_STORAGE_ENABLED", "true")
        .env("OBJECT_STORAGE_ENDPOINT", &endpoint)
        .env("OBJECT_STORAGE_BUCKET", &bucket)
        .env("OBJECT_STORAGE_ACCESS_KEY_ID", &access)
        .env("OBJECT_STORAGE_SECRET_ACCESS_KEY", &secret)
        .env("OBJECT_STORAGE_PREFIX", &prefix)
        .env("PROFILE_ARCHIVE_KEYRING_FILE", &keyring)
        .env("OBJECT_STORAGE_OPERATION_TIMEOUT_MS", "10000")
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .kill_on_drop(true)
        .spawn()
        .unwrap();
    let mut helper = OwnedRecordingHelper {
        child: helper_child,
        root: root.clone(),
    };
    let deadline = tokio::time::Instant::now() + Duration::from_secs(15);
    while !socket.exists() {
        assert!(
            helper.child.try_wait().unwrap().is_none(),
            "owned Helper exited before ready"
        );
        assert!(
            tokio::time::Instant::now() < deadline,
            "owned Helper ready deadline"
        );
        tokio::time::sleep(Duration::from_millis(50)).await;
    }
    let client =
        Arc::new(StorageHelperClient::new(socket, Duration::from_secs(120), storage).unwrap());
    let session = format!("ses_{suffix}");
    let recording = format!("rec_{suffix}");
    let workspace = client
        .acquire_workspace("tenant-held-e2e", "profile-held-e2e", &session)
        .await
        .unwrap();
    let scope = helper_client::RecordingUploadHoldScope {
        operation_id: format!("rua_{suffix}"),
        authority_epoch: 1,
        placement_epoch: 1,
        tenant_id: workspace.tenant_id.clone(),
        profile_id: workspace.profile_id.clone(),
        session_id: session.clone(),
        recording_id: recording.clone(),
    };
    let journal_path = root.join("node.sqlite");
    let journal = Arc::new(
        node_journal::SqliteNodeJournal::open(&journal_path)
            .await
            .unwrap(),
    );
    let chrome = OwnedChrome::start().await;
    let version: Value = reqwest::Client::new()
        .get(format!("{}/json/version", chrome.endpoint))
        .timeout(CDP_TIMEOUT)
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    let (mut browser, _) =
        tokio_tungstenite::connect_async(version["webSocketDebuggerUrl"].as_str().unwrap())
            .await
            .unwrap();
    let targets = send_command_value(&mut browser, 1, "Target.getTargets", json!({}))
        .await
        .unwrap();
    for (index, target) in targets["result"]["targetInfos"]
        .as_array()
        .unwrap()
        .iter()
        .enumerate()
    {
        if target["type"] == "page" && target["targetId"] != chrome.target {
            send_command(
                &mut browser,
                index as i64 + 2,
                "Target.closeTarget",
                json!({"targetId":target["targetId"]}),
            )
            .await
            .unwrap();
        }
    }
    browser.close(None).await.unwrap();
    let mut page = chrome.connect().await;
    chrome.navigate(&mut page, "held-recording", 10).await;
    send_command(&mut page,30,"Runtime.evaluate",json!({"expression":
        "const p=document.createElement('div');p.style='font:32px Arial;background:white;color:black';p.textContent='owned.user@example.com';document.body.appendChild(p);setInterval(()=>document.getElementById('root').textContent='Owned frame '+window.ticks,100);true"})).await.unwrap();
    let registry = SessionRecorderRegistry::default();
    registry
        .register(
            RecordingSpec {
                session_id: session.clone(),
                cdp_endpoint: chrome.endpoint.clone(),
                workspace: workspace.clone(),
                storage_helper: Arc::clone(&client),
                privacy_scanner_path: scanner,
                upload_hold: Some(RecordingUploadContext {
                    scope: scope.clone(),
                    journal: Arc::clone(&journal),
                }),
            },
            true,
        )
        .await
        .unwrap();
    let oracle = aws_sdk_s3::Client::from_conf(
        aws_sdk_s3::Config::builder()
            .behavior_version(BehaviorVersion::latest())
            .endpoint_url(&endpoint)
            .region(Region::new("us-east-1"))
            .force_path_style(true)
            .credentials_provider(Credentials::new(
                access,
                secret,
                None,
                None,
                "owned-held-recording",
            ))
            .build(),
    );
    let object_prefix = format!(
        "{prefix}/tenants/{}/profiles/{}/sessions/{}/recordings/{}/",
        scope.tenant_id, scope.profile_id, session, recording
    );
    let deadline = tokio::time::Instant::now() + Duration::from_secs(90);
    loop {
        if journal.recording_upload_scope_hash(&scope).await.is_ok() {
            break;
        }
        assert!(
            tokio::time::Instant::now() < deadline,
            "first held segment deadline"
        );
        tokio::time::sleep(Duration::from_millis(200)).await;
    }
    // Producer is still enabled: observe native ON before requesting finalization.
    {
        let sessions = registry.sessions.lock().await;
        assert!(
            !sessions
                .get(&session)
                .unwrap()
                .active
                .as_ref()
                .unwrap()
                .task
                .is_finished(),
            "capture ended before native upload observation"
        );
    }
    let during = oracle
        .list_object_versions()
        .bucket(&bucket)
        .prefix(&object_prefix)
        .send()
        .await
        .unwrap();
    assert!(during.versions().len() >= 2);
    for version in during.versions() {
        assert!(
            !version.key().unwrap().ends_with("/COMMITTED")
                || version.key().unwrap().contains("/segments/")
        );
        let hold = oracle
            .get_object_legal_hold()
            .bucket(&bucket)
            .key(version.key().unwrap())
            .version_id(version.version_id().unwrap())
            .send()
            .await
            .unwrap();
        assert_eq!(
            hold.legal_hold().unwrap().status(),
            Some(&aws_sdk_s3::types::ObjectLockLegalHoldStatus::On)
        );
    }
    let summary = registry
        .set_enabled(&session, false)
        .await
        .unwrap()
        .unwrap();
    assert!(
        summary.frame_count > 0 && summary.segment_count > 0 && summary.redacted_frame_count > 0
    );
    let versions = oracle
        .list_object_versions()
        .bucket(&bucket)
        .prefix(&object_prefix)
        .send()
        .await
        .unwrap();
    assert_eq!(
        versions.versions().len() as u64,
        summary.segment_count * 2 + 1
    );
    assert!(versions.delete_markers().is_empty());
    let mut scanned = 0u64;
    let mut ocr_detections = 0u64;
    for version in versions.versions() {
        let hold = oracle
            .get_object_legal_hold()
            .bucket(&bucket)
            .key(version.key().unwrap())
            .version_id(version.version_id().unwrap())
            .send()
            .await
            .unwrap();
        assert_eq!(
            hold.legal_hold().unwrap().status(),
            Some(&aws_sdk_s3::types::ObjectLockLegalHoldStatus::On)
        );
        if version.key().unwrap().ends_with(".ndjson") {
            let object = oracle
                .get_object()
                .bucket(&bucket)
                .key(version.key().unwrap())
                .version_id(version.version_id().unwrap())
                .send()
                .await
                .unwrap();
            let body = object.body.collect().await.unwrap().into_bytes();
            for line in body.split(|b| *b == b'\n').filter(|l| !l.is_empty()) {
                let frame: Value = serde_json::from_slice(line).unwrap();
                assert_eq!(frame["privacyScanState"], "VERIFIED");
                assert_eq!(frame["redactionPolicyVersion"], 2);
                assert_eq!(frame["ocrResidualSignalCount"], 0);
                assert_eq!(frame["visualResidualSignalCount"], 0);
                let pixels = base64::engine::general_purpose::STANDARD
                    .decode(frame["data"].as_str().unwrap())
                    .unwrap();
                assert_eq!(
                    frame["sanitizedImageSha256"],
                    format!("{:x}", Sha256::digest(&pixels))
                );
                ocr_detections += frame["ocrDetectedSignalCount"].as_u64().unwrap();
                scanned += 1;
            }
        }
    }
    assert_eq!(scanned, summary.frame_count);
    assert!(ocr_detections > 0, "owned visible PII was not classified");
    assert!(journal
        .recording_upload_seal(&scope.tenant_id, &session, &recording)
        .await
        .unwrap()
        .is_some());
    registry
        .acknowledge_summary(&session, &recording)
        .await
        .unwrap();
    registry.unregister(&session).await.unwrap();
    drop(registry);
    drop(journal);
    drop(page);
    drop(chrome);
    drop(client);
    helper.child.kill().await.unwrap();
    let _ = helper.child.wait().await;
    println!(
        "REAL_HELD_RECORDING_E2E_OK frames={} segments={} versions={} ocr_detections={}",
        summary.frame_count,
        summary.segment_count,
        summary.segment_count * 2 + 1,
        ocr_detections
    );
}
