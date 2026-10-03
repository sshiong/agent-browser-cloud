//! Session-scoped pixel recording over the Browser Node's loopback CDP endpoint.
//!
//! Frames are captured independently from the interactive Observer connection. The unprivileged
//! Node writes bounded NDJSON segments into the Session ephemeral workspace; only Storage Helper
//! can commit those segments to Object Storage.

use base64::Engine;
use futures_util::{SinkExt, StreamExt};
use helper_client::{StorageHelperClient, StorageWorkspace};
use jpeg_decoder::PixelFormat;
use jpeg_encoder::ColorType;
use serde::Deserialize;
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use std::collections::HashMap;
use std::io::Cursor;
use std::path::PathBuf;
use std::sync::Arc;
use std::time::{Duration, SystemTime, UNIX_EPOCH};
use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};
use tokio::process::{Child, ChildStdin, ChildStdout, Command};
use tokio::sync::{mpsc, oneshot, watch, Mutex};
use tokio::task::JoinHandle;
use tokio_tungstenite::tungstenite::Message;

const FRAME_QUEUE_CAPACITY: usize = 8;
const SEGMENT_MAX_BYTES: u64 = 4 * 1024 * 1024;
const SEGMENT_MAX_DURATION_MS: u64 = 10_000;
const CDP_TIMEOUT: Duration = Duration::from_secs(5);
const EVIDENCE_MAX_BYTES: usize = 8 * 1024 * 1024;
const RECORDING_DOM_REDACTION_POLICY_VERSION: u32 = 1;
const RECORDING_REDACTION_POLICY_VERSION: u32 = 2;
const RECORDING_PRIVACY_SCAN_VERSION: &str = "tesseract-opencv-pii-face-qr-v2";
const RECORDING_PRIVACY_SCAN_TIMEOUT: Duration = Duration::from_secs(30);
const RECORDING_MAX_SENSITIVE_REGIONS: usize = 256;
const RECORDING_MAX_WIDTH: usize = 1920;
const RECORDING_MAX_HEIGHT: usize = 1080;

#[derive(Clone)]
pub struct RecordingSpec {
    pub session_id: String,
    pub cdp_endpoint: String,
    pub workspace: StorageWorkspace,
    pub storage_helper: Arc<StorageHelperClient>,
    pub privacy_scanner_path: PathBuf,
}

struct RegisteredRecording {
    spec: RecordingSpec,
    active: Option<ActiveRecording>,
    pending_summary: Option<RecordingSummary>,
    failure: Option<String>,
}

struct ActiveRecording {
    stop: watch::Sender<bool>,
    task: JoinHandle<anyhow::Result<RecordingSummary>>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RecordingSummary {
    pub recording_id: String,
    pub segment_count: u64,
    pub frame_count: u64,
    pub dropped_frames: u64,
    pub redacted_frame_count: u64,
    pub redacted_region_count: u64,
    pub redaction_policy_version: u32,
    pub manifest_object_key: String,
    pub manifest_sha256: String,
    pub manifest_bytes: u64,
    pub started_at_ms: u64,
    pub ended_at_ms: u64,
}

#[derive(Clone, Default)]
pub struct SessionRecorderRegistry {
    sessions: Arc<Mutex<HashMap<String, RegisteredRecording>>>,
}

#[derive(Clone)]
pub struct EvidenceSpec {
    pub session_id: String,
    pub cdp_endpoint: String,
    pub workspace: StorageWorkspace,
    pub storage_helper: Arc<StorageHelperClient>,
}

#[derive(Clone)]
struct RegisteredEvidence {
    spec: EvidenceSpec,
    success_sample_percent: u32,
    capture_lock: Arc<Mutex<()>>,
}

#[derive(Debug, Clone, PartialEq)]
pub struct EvidenceSummary {
    pub evidence_id: String,
    pub content_sha256: String,
    pub content_bytes: u64,
    pub object_key: String,
    pub captured_at_ms: u64,
    pub redaction_state: String,
    pub redacted_region_count: u32,
    pub screenshot_metadata: Option<ScreenshotMetadata>,
}

#[derive(Debug, Clone, PartialEq)]
pub struct ScreenshotClip {
    pub x: f64,
    pub y: f64,
    pub width: f64,
    pub height: f64,
}

#[derive(Debug, Clone, PartialEq)]
pub struct ScreenshotCaptureOptions {
    pub evidence_id: String,
    pub captured_at_ms: u64,
    pub active_tab_id: String,
    pub capture_mode: String,
    /// CSS-pixel viewport coordinates. Required only for bounded capture modes.
    pub clip: Option<ScreenshotClip>,
}

#[derive(Debug, Clone, PartialEq)]
pub struct ScreenshotMetadata {
    pub capture_mode: String,
    pub active_tab_id: String,
    pub viewport_width: f64,
    pub viewport_height: f64,
    pub device_scale_factor: f64,
    pub region: ScreenshotClip,
    pub coordinate_space: String,
}

#[derive(Debug, Clone, PartialEq)]
pub enum EvidenceCapture {
    Skipped { sample_percent: u32 },
    Committed(Box<EvidenceSummary>),
}

#[derive(Clone, Default)]
pub struct SessionEvidenceRegistry {
    sessions: Arc<Mutex<HashMap<String, RegisteredEvidence>>>,
}

impl SessionEvidenceRegistry {
    pub async fn register(
        &self,
        spec: EvidenceSpec,
        success_sample_percent: u32,
    ) -> anyhow::Result<()> {
        anyhow::ensure!(
            spec.session_id == spec.workspace.session_id,
            "evidence Session identity does not match workspace"
        );
        validate_sample_percent(success_sample_percent)?;
        let previous = self.sessions.lock().await.insert(
            spec.session_id.clone(),
            RegisteredEvidence {
                spec,
                success_sample_percent,
                capture_lock: Arc::new(Mutex::new(())),
            },
        );
        anyhow::ensure!(previous.is_none(), "evidence Session is already registered");
        Ok(())
    }

    pub async fn set_success_sample_percent(
        &self,
        session_id: &str,
        success_sample_percent: u32,
    ) -> anyhow::Result<u32> {
        validate_sample_percent(success_sample_percent)?;
        let mut sessions = self.sessions.lock().await;
        let registered = sessions
            .get_mut(session_id)
            .ok_or_else(|| anyhow::anyhow!("Screenshot evidence actuator is unavailable"))?;
        let previous = registered.success_sample_percent;
        registered.success_sample_percent = success_sample_percent;
        Ok(previous)
    }

    pub async fn success_sample_percent(&self, session_id: &str) -> Option<u32> {
        self.sessions
            .lock()
            .await
            .get(session_id)
            .map(|registered| registered.success_sample_percent)
    }

    pub async fn unregister(&self, session_id: &str) {
        self.sessions.lock().await.remove(session_id);
    }

    pub async fn capture(
        &self,
        session_id: &str,
        evidence_key: &str,
        evidence_kind: &str,
        mandatory: bool,
    ) -> anyhow::Result<EvidenceCapture> {
        self.capture_with_options(session_id, evidence_key, evidence_kind, mandatory, None)
            .await
    }

    pub async fn capture_with_options(
        &self,
        session_id: &str,
        evidence_key: &str,
        evidence_kind: &str,
        mandatory: bool,
        options: Option<ScreenshotCaptureOptions>,
    ) -> anyhow::Result<EvidenceCapture> {
        let registered = self
            .sessions
            .lock()
            .await
            .get(session_id)
            .cloned()
            .ok_or_else(|| anyhow::anyhow!("Screenshot evidence actuator is unavailable"))?;
        if !mandatory && !deterministic_sample(evidence_key, registered.success_sample_percent) {
            return Ok(EvidenceCapture::Skipped {
                sample_percent: registered.success_sample_percent,
            });
        }
        let evidence_id = options
            .as_ref()
            .map(|value| value.evidence_id.clone())
            .unwrap_or_else(|| format!("evd_{}", uuid::Uuid::new_v4().simple()));
        let captured_at_ms = options
            .as_ref()
            .map(|value| value.captured_at_ms)
            .unwrap_or_else(now_millis);
        anyhow::ensure!(
            evidence_id.starts_with("evd_")
                && evidence_id.len() == 36
                && evidence_id[4..]
                    .bytes()
                    .all(|value| value.is_ascii_hexdigit())
                && captured_at_ms > 0,
            "requested evidence identity is invalid"
        );
        let capture = capture_screenshot_serialized(
            &registered.spec.cdp_endpoint,
            options.as_ref(),
            registered.capture_lock,
        )
        .await?;
        let content = capture.content;
        anyhow::ensure!(
            !content.is_empty() && content.len() <= EVIDENCE_MAX_BYTES,
            "CDP screenshot exceeds the bounded evidence size"
        );
        anyhow::ensure!(
            content.starts_with(&[0xff, 0xd8]),
            "CDP screenshot is not a JPEG"
        );
        let content_sha256 = format!("{:x}", Sha256::digest(&content));
        let path = prepare_evidence_path(&registered.spec.workspace, &evidence_id).await?;
        let mut file = tokio::fs::OpenOptions::new()
            .create_new(true)
            .write(true)
            .open(&path)
            .await?;
        file.write_all(&content).await?;
        file.flush().await?;
        file.sync_data().await?;
        drop(file);
        let committed = registered
            .spec
            .storage_helper
            .commit_evidence(
                &registered.spec.workspace,
                &evidence_id,
                evidence_kind,
                &content_sha256,
                content.len() as u64,
                captured_at_ms,
            )
            .await;
        if committed.is_err() {
            let _ = tokio::fs::remove_file(&path).await;
        }
        let committed = committed?;
        anyhow::ensure!(
            committed.committed
                && committed.evidence_id == evidence_id
                && committed.content_sha256 == content_sha256
                && committed.content_bytes == content.len() as u64
                && committed.captured_at_ms == captured_at_ms,
            "Storage Helper evidence acknowledgement mismatch"
        );
        Ok(EvidenceCapture::Committed(Box::new(EvidenceSummary {
            evidence_id,
            content_sha256,
            content_bytes: content.len() as u64,
            object_key: committed.object_key,
            captured_at_ms,
            redaction_state: if capture.redacted_region_count > 0 {
                "MASKED".to_owned()
            } else {
                "NOT_REQUIRED".to_owned()
            },
            redacted_region_count: capture.redacted_region_count,
            screenshot_metadata: capture.metadata,
        })))
    }
}

fn validate_sample_percent(value: u32) -> anyhow::Result<()> {
    anyhow::ensure!(
        (1..=100).contains(&value),
        "success screenshot sample percent must be between 1 and 100"
    );
    Ok(())
}

fn deterministic_sample(key: &str, percentage: u32) -> bool {
    if percentage == 100 {
        return true;
    }
    let hash = key
        .as_bytes()
        .iter()
        .fold(0xcbf29ce484222325_u64, |hash, byte| {
            (hash ^ u64::from(*byte)).wrapping_mul(0x100000001b3)
        });
    hash % 100 < u64::from(percentage)
}

impl SessionRecorderRegistry {
    pub async fn register(&self, spec: RecordingSpec, enabled: bool) -> anyhow::Result<()> {
        anyhow::ensure!(
            spec.session_id == spec.workspace.session_id,
            "recording Session identity does not match workspace"
        );
        anyhow::ensure!(
            !self.sessions.lock().await.contains_key(&spec.session_id),
            "recording Session is already registered"
        );
        let session_id = spec.session_id.clone();
        self.sessions.lock().await.insert(
            session_id.clone(),
            RegisteredRecording {
                spec,
                active: None,
                pending_summary: None,
                failure: None,
            },
        );
        if enabled {
            if let Err(error) = self.set_enabled(&session_id, true).await {
                self.sessions.lock().await.remove(&session_id);
                return Err(error);
            }
        }
        Ok(())
    }

    pub async fn set_enabled(
        &self,
        session_id: &str,
        enabled: bool,
    ) -> anyhow::Result<Option<RecordingSummary>> {
        let (spec, active, stale) = {
            let mut sessions = self.sessions.lock().await;
            let registered = sessions
                .get_mut(session_id)
                .ok_or_else(|| anyhow::anyhow!("recording Session is not registered"))?;
            if enabled {
                anyhow::ensure!(
                    registered.pending_summary.is_none(),
                    "completed recording summary must be acknowledged before restart"
                );
                if registered
                    .active
                    .as_ref()
                    .is_some_and(|active| !active.task.is_finished())
                {
                    return Ok(None);
                }
                let stale = registered.active.take();
                registered.failure = None;
                (Some(registered.spec.clone()), None, stale)
            } else {
                if let Some(summary) = registered.pending_summary.as_ref() {
                    return Ok(Some(summary.clone()));
                }
                if let Some(failure) = registered.failure.as_ref() {
                    anyhow::bail!("previous recording finalization failed: {failure}");
                }
                (None, registered.active.take(), None)
            }
        };

        if let Some(stale) = stale {
            let _ = stale.task.await;
        }
        if let Some(active) = active {
            let _ = active.stop.send(true);
            let summary = match active
                .task
                .await
                .map_err(|error| anyhow::anyhow!("recording task join failed: {error}"))?
            {
                Ok(summary) => summary,
                Err(error) => {
                    if let Some(registered) = self.sessions.lock().await.get_mut(session_id) {
                        registered.failure = Some(error.to_string());
                    }
                    return Err(error);
                }
            };
            if let Some(registered) = self.sessions.lock().await.get_mut(session_id) {
                registered.pending_summary = Some(summary.clone());
            }
            return Ok(Some(summary));
        }
        if let Some(spec) = spec {
            let active = start_recording(spec).await?;
            let mut sessions = self.sessions.lock().await;
            let registered = sessions
                .get_mut(session_id)
                .ok_or_else(|| anyhow::anyhow!("recording Session disappeared during start"))?;
            registered.active = Some(active);
            return Ok(None);
        }
        Ok(None)
    }

    pub async fn enabled(&self, session_id: &str) -> Option<bool> {
        self.sessions.lock().await.get(session_id).map(|recording| {
            recording
                .active
                .as_ref()
                .is_some_and(|active| !active.task.is_finished())
        })
    }

    pub async fn unregister(&self, session_id: &str) -> anyhow::Result<Option<RecordingSummary>> {
        // StopRuntime is intentionally idempotent: failover cleanup may target a Runtime that
        // never completed StartRuntime, or retry after the recorder registry was already
        // drained. In both cases there is no manifest to finalize and cleanup must continue.
        if !self.sessions.lock().await.contains_key(session_id) {
            return Ok(None);
        }
        if let Some(summary) = self.set_enabled(session_id, false).await? {
            return Ok(Some(summary));
        }
        self.sessions.lock().await.remove(session_id);
        Ok(None)
    }

    pub async fn acknowledge_summary(
        &self,
        session_id: &str,
        recording_id: &str,
    ) -> anyhow::Result<()> {
        let mut sessions = self.sessions.lock().await;
        let registered = sessions
            .get_mut(session_id)
            .ok_or_else(|| anyhow::anyhow!("recording Session is not registered"))?;
        anyhow::ensure!(
            registered
                .pending_summary
                .as_ref()
                .is_some_and(|summary| summary.recording_id == recording_id),
            "recording summary acknowledgement does not match pending manifest"
        );
        registered.pending_summary = None;
        Ok(())
    }

    /*
     * The pending summary remains in the registry until Node Journal has durably accepted the
     * finalization event. This makes Control Plane projection retryable across dispatch retries.
     */
    pub async fn remove_inactive(&self, session_id: &str) -> anyhow::Result<()> {
        let active = {
            let mut sessions = self.sessions.lock().await;
            let Some(registered) = sessions.get_mut(session_id) else {
                return Ok(());
            };
            anyhow::ensure!(registered.active.is_none(), "recording is still active");
            anyhow::ensure!(
                registered.pending_summary.is_none(),
                "recording summary is still pending acknowledgement"
            );
            sessions.remove(session_id);
            None::<ActiveRecording>
        };
        drop(active);
        Ok(())
    }
}

async fn start_recording(spec: RecordingSpec) -> anyhow::Result<ActiveRecording> {
    let recording_id = format!("rec_{}", uuid::Uuid::new_v4().simple());
    let prepared = spec
        .storage_helper
        .prepare_recording(&spec.workspace, &recording_id)
        .await?;
    anyhow::ensure!(
        prepared.redaction_policy_version == RECORDING_REDACTION_POLICY_VERSION,
        "storage helper does not acknowledge frame-level recording redaction"
    );
    let (stop, stop_rx) = watch::channel(false);
    let (ready_tx, ready_rx) = oneshot::channel();
    let task = tokio::spawn(run_recording(spec, recording_id, stop_rx, ready_tx));
    match tokio::time::timeout(CDP_TIMEOUT, ready_rx).await {
        Ok(Ok(Ok(()))) => Ok(ActiveRecording { stop, task }),
        Ok(Ok(Err(message))) => {
            let _ = stop.send(true);
            let _ = task.await;
            anyhow::bail!("{message}")
        }
        Ok(Err(_)) => {
            let _ = stop.send(true);
            let _ = task.await;
            anyhow::bail!("recording task stopped before CDP acknowledged start")
        }
        Err(_) => {
            let _ = stop.send(true);
            let _ = task.await;
            anyhow::bail!("CDP recording start timed out")
        }
    }
}

#[derive(Debug)]
struct CapturedFrame {
    captured_at_ms: u64,
    session_id: i64,
    data: String,
    metadata: Value,
    redaction_state: &'static str,
    redacted_region_count: u32,
    privacy_scan: RecordingPrivacyAttestation,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
struct RecordingPrivacyAttestation {
    scan_version: String,
    sanitized_image_base64: String,
    sanitized_image_sha256: String,
    ocr_detected_signal_count: u32,
    ocr_masked_region_count: u32,
    ocr_residual_signal_count: u32,
    visual_detected_signal_count: u32,
    visual_masked_region_count: u32,
    visual_residual_signal_count: u32,
}

async fn run_recording(
    spec: RecordingSpec,
    recording_id: String,
    mut stop: watch::Receiver<bool>,
    ready: oneshot::Sender<Result<(), String>>,
) -> anyhow::Result<RecordingSummary> {
    let (frames_tx, frames_rx) = mpsc::channel(FRAME_QUEUE_CAPACITY);
    let dropped_frames = Arc::new(std::sync::atomic::AtomicU64::new(0));
    let writer_spec = spec.clone();
    let writer_recording_id = recording_id.clone();
    let writer_drops = Arc::clone(&dropped_frames);
    // Remains true unless the capture loop reaches an orderly stop. The writer must not publish a
    // completion marker after a CDP/redaction failure, even if all earlier frames were safe.
    let capture_failed = Arc::new(std::sync::atomic::AtomicBool::new(true));
    let writer_capture_failed = Arc::clone(&capture_failed);
    let mut writer = tokio::spawn(async move {
        write_segments(
            writer_spec,
            writer_recording_id,
            frames_rx,
            writer_drops,
            writer_capture_failed,
        )
        .await
    });
    let (capture_stop_tx, capture_stop_rx) = watch::channel(false);
    let capture_endpoint = spec.cdp_endpoint.clone();
    let privacy_scanner_path = spec.privacy_scanner_path.clone();
    let capture_drops = Arc::clone(&dropped_frames);
    let capture_completion_signal = Arc::clone(&capture_failed);
    let mut capture = tokio::spawn(async move {
        capture_frames(
            &capture_endpoint,
            &privacy_scanner_path,
            frames_tx,
            capture_stop_rx,
            ready,
            capture_drops.as_ref(),
            capture_completion_signal.as_ref(),
        )
        .await
    });
    let controller_stop = capture_stop_tx.clone();
    let stop_forwarder = tokio::spawn(async move {
        if stop.changed().await.is_ok() && *stop.borrow() {
            let _ = controller_stop.send(true);
        }
    });
    let (capture_result, writer_result) = tokio::select! {
        capture_result = &mut capture => {
            let capture_result = capture_result
                .map_err(|error| anyhow::anyhow!("recording capture join failed: {error}"))?;
            let writer_result = writer
                .await
                .map_err(|error| anyhow::anyhow!("recording segment writer join failed: {error}"))?;
            (capture_result, writer_result)
        }
        writer_result = &mut writer => {
            let writer_result = writer_result
                .map_err(|error| anyhow::anyhow!("recording segment writer join failed: {error}"))?;
            let _ = capture_stop_tx.send(true);
            let capture_result = capture
                .await
                .map_err(|error| anyhow::anyhow!("recording capture join failed: {error}"))?;
            (capture_result, writer_result)
        }
    };
    stop_forwarder.abort();
    match (capture_result, writer_result) {
        (Ok(()), Ok(summary)) => Ok(summary),
        (Err(capture), Ok(_)) => Err(capture),
        (Ok(()), Err(writer)) => Err(writer),
        (Err(capture), Err(writer)) => Err(anyhow::anyhow!(
            "recording capture failed: {capture}; segment writer failed: {writer}"
        )),
    }
}

async fn capture_frames(
    cdp_endpoint: &str,
    privacy_scanner_path: &std::path::Path,
    frames: mpsc::Sender<CapturedFrame>,
    mut stop: watch::Receiver<bool>,
    ready: oneshot::Sender<Result<(), String>>,
    dropped_frames: &std::sync::atomic::AtomicU64,
    capture_failed: &std::sync::atomic::AtomicBool,
) -> anyhow::Result<()> {
    let mut privacy_scanner = RecordingPrivacyScanner::start(privacy_scanner_path).await?;
    let websocket_url = target_websocket(cdp_endpoint, None).await?;
    require_loopback_websocket(&websocket_url)?;
    let (mut socket, _) = tokio::time::timeout(
        CDP_TIMEOUT,
        tokio_tungstenite::connect_async(&websocket_url),
    )
    .await
    .map_err(|_| anyhow::anyhow!("CDP recording connection timed out"))??;
    send_command(&mut socket, 1, "Page.enable", json!({})).await?;
    if let Err(error) = send_command(
        &mut socket,
        2,
        "Page.startScreencast",
        json!({
            "format": "jpeg",
            "quality": 60,
            "maxWidth": 1920,
            "maxHeight": 1080,
            "everyNthFrame": 1
        }),
    )
    .await
    {
        let _ = ready.send(Err(error.to_string()));
        return Err(error);
    }
    let _ = ready.send(Ok(()));

    loop {
        tokio::select! {
            changed = stop.changed() => {
                if changed.is_err() || *stop.borrow() {
                    let _ = send_command(&mut socket, 3, "Page.stopScreencast", json!({})).await;
                    break;
                }
            }
            message = socket.next() => {
                let Some(message) = message else {
                    anyhow::bail!("CDP recording websocket closed");
                };
                let message = message?;
                let Message::Text(text) = message else {
                    if matches!(message, Message::Close(_)) {
                        anyhow::bail!("CDP recording websocket closed");
                    }
                    continue;
                };
                let event: Value = serde_json::from_str(&text)?;
                if event.get("method").and_then(Value::as_str) != Some("Page.screencastFrame") {
                    continue;
                }
                let Some(params) = event.get("params") else {
                    continue;
                };
                let Some(session_id) = params.get("sessionId").and_then(Value::as_i64) else {
                    continue;
                };
                let raw_data = params
                    .get("data")
                    .and_then(Value::as_str)
                    .unwrap_or_default();
                let redaction_result = if raw_data.is_empty()
                    || raw_data.len() > SEGMENT_MAX_BYTES as usize
                {
                    Err(anyhow::anyhow!("CDP recording frame is empty or exceeds the bound"))
                } else {
                    redact_recording_frame(&mut socket, raw_data, session_id).await
                };
                let privacy_scan_result = match redaction_result.as_ref() {
                    Ok(redacted) => Some(privacy_scanner.scan(&redacted.data).await),
                    Err(_) => None,
                };
                // ACK only after the full-frame scan to make Chromium's screencast protocol the
                // privacy pipeline's backpressure boundary. On any failure this frame is still
                // acknowledged, then discarded; raw or partially sanitized pixels are never
                // queued.
                socket
                    .send(Message::Text(
                        json!({
                            "id": 10_000_i64.saturating_add(session_id),
                            "method": "Page.screencastFrameAck",
                            "params": {"sessionId": session_id}
                        })
                        .to_string(),
                    ))
                    .await?;
                let redacted = redaction_result?;
                let privacy_scan = privacy_scan_result
                    .ok_or_else(|| anyhow::anyhow!("recording privacy scan was not attempted"))??;
                let total_redacted_regions = redacted
                    .redacted_region_count
                    .checked_add(privacy_scan.ocr_masked_region_count)
                    .and_then(|count| count.checked_add(privacy_scan.visual_masked_region_count))
                    .ok_or_else(|| anyhow::anyhow!("recording privacy region count overflow"))?;
                let frame = CapturedFrame {
                    captured_at_ms: now_millis(),
                    session_id,
                    data: privacy_scan.sanitized_image_base64.clone(),
                    metadata: params.get("metadata").cloned().unwrap_or_else(|| json!({})),
                    redaction_state: if total_redacted_regions > 0 {
                        "MASKED"
                    } else {
                        "NOT_REQUIRED"
                    },
                    redacted_region_count: total_redacted_regions,
                    privacy_scan,
                };
                if frames.try_send(frame).is_err() {
                    dropped_frames.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
                }
            }
        }
    }
    capture_failed.store(false, std::sync::atomic::Ordering::Release);
    drop(frames);
    Ok(())
}

struct RecordingPrivacyScanner {
    _child: Child,
    stdin: ChildStdin,
    stdout: BufReader<ChildStdout>,
}

impl RecordingPrivacyScanner {
    async fn start(scanner_path: &std::path::Path) -> anyhow::Result<Self> {
        anyhow::ensure!(
            scanner_path.is_absolute(),
            "recording privacy scanner path is not absolute"
        );
        let mut child = Command::new(scanner_path)
            .stdin(std::process::Stdio::piped())
            .stdout(std::process::Stdio::piped())
            .stderr(std::process::Stdio::null())
            .env_clear()
            .env("PATH", "/usr/bin:/bin")
            .env("LANG", "C.UTF-8")
            .kill_on_drop(true)
            .spawn()
            .map_err(|error| {
                anyhow::anyhow!("recording privacy scanner could not start: {error}")
            })?;
        let stdin = child
            .stdin
            .take()
            .ok_or_else(|| anyhow::anyhow!("recording privacy scanner stdin is unavailable"))?;
        let stdout = child
            .stdout
            .take()
            .ok_or_else(|| anyhow::anyhow!("recording privacy scanner stdout is unavailable"))?;
        Ok(Self {
            _child: child,
            stdin,
            stdout: BufReader::new(stdout),
        })
    }

    async fn scan(&mut self, encoded: &str) -> anyhow::Result<RecordingPrivacyAttestation> {
        let mut request = serde_json::to_vec(&json!({"imageBase64": encoded}))?;
        anyhow::ensure!(
            request.len() <= EVIDENCE_MAX_BYTES.saturating_mul(2),
            "recording privacy scan request exceeds the bound"
        );
        request.push(b'\n');
        self.stdin.write_all(&request).await?;
        self.stdin.flush().await?;
        let mut output = Vec::new();
        let bytes = tokio::time::timeout(
            RECORDING_PRIVACY_SCAN_TIMEOUT,
            self.stdout.read_until(b'\n', &mut output),
        )
        .await
        .map_err(|_| anyhow::anyhow!("recording privacy scanner timed out"))??;
        anyhow::ensure!(
            bytes > 0 && output.len() <= EVIDENCE_MAX_BYTES.saturating_mul(2),
            "recording privacy scanner failed closed"
        );
        let attestation: RecordingPrivacyAttestation = serde_json::from_slice(&output)
            .map_err(|_| anyhow::anyhow!("recording privacy scanner returned invalid output"))?;
        anyhow::ensure!(
            attestation.scan_version == RECORDING_PRIVACY_SCAN_VERSION
                && attestation.ocr_residual_signal_count == 0
                && attestation.visual_residual_signal_count == 0
                && attestation.sanitized_image_sha256.len() == 64
                && attestation.ocr_masked_region_count <= RECORDING_MAX_SENSITIVE_REGIONS as u32
                && attestation.visual_masked_region_count <= RECORDING_MAX_SENSITIVE_REGIONS as u32,
            "recording privacy attestation failed validation"
        );
        let sanitized = base64::engine::general_purpose::STANDARD
            .decode(&attestation.sanitized_image_base64)
            .map_err(|_| {
                anyhow::anyhow!("recording privacy scanner returned invalid image base64")
            })?;
        anyhow::ensure!(
            sanitized.starts_with(&[0xff, 0xd8, 0xff])
                && sanitized.len() <= EVIDENCE_MAX_BYTES
                && format!("{:x}", Sha256::digest(&sanitized))
                    == attestation.sanitized_image_sha256,
            "recording privacy scanner image proof does not match its pixels"
        );
        Ok(attestation)
    }
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct SensitiveRegionSnapshot {
    version: u32,
    viewport_width: f64,
    viewport_height: f64,
    regions: Vec<SensitiveRegion>,
    #[serde(default)]
    error: Option<String>,
}

#[derive(Debug, Deserialize)]
struct SensitiveRegion {
    left: f64,
    top: f64,
    width: f64,
    height: f64,
}

struct RedactedRecordingFrame {
    data: String,
    redacted_region_count: u32,
}

const COLLECT_RECORDING_REDACTION_REGIONS_SCRIPT: &str = r#"
(() => {
  const sensitiveName = /(^|[^a-z])(password|passwd|pwd|passcode|otp|one.?time.?code|pin|cvv|cvc|card.?number|account.?number|routing.?number|secret|token|api.?key|private.?key|ssn|social.?security)([^a-z]|$)/i;
  const sensitiveAutocomplete = new Set([
    'current-password', 'new-password', 'one-time-code', 'cc-number', 'cc-csc',
    'cc-exp', 'cc-exp-month', 'cc-exp-year', 'transaction-amount',
    'transaction-currency'
  ]);
  const candidateSelector =
    'input, textarea, select, iframe, [contenteditable="true"], ' +
    '[data-sensitive], [data-private], [data-redact], [data-classification]';
  const candidates = new Set();
  const roots = [document];
  let scannedElements = 0;
  while (roots.length > 0) {
    const currentRoot = roots.pop();
    for (const element of currentRoot.querySelectorAll('*')) {
      scannedElements += 1;
      if (scannedElements > 10000) {
        return {version: 1, error: 'SENSITIVE_SCAN_LIMIT_EXCEEDED'};
      }
      if (element.matches(candidateSelector)) candidates.add(element);
      if (element.shadowRoot) roots.push(element.shadowRoot);
    }
  }
  const isSensitive = (element) => {
    if (element.matches('iframe, [data-sensitive], [data-private], [data-redact]')) return true;
    const classification = (element.getAttribute('data-classification') || '').toUpperCase();
    if (classification === 'SENSITIVE' || classification === 'HIGHLY_SENSITIVE') return true;
    if (['password', 'hidden'].includes((element.getAttribute('type') || '').toLowerCase())) return true;
    const autocomplete = (element.getAttribute('autocomplete') || '')
      .toLowerCase().split(/\s+/).filter(Boolean);
    if (autocomplete.some((token) => sensitiveAutocomplete.has(token))) return true;
    const identity = [
      element.getAttribute('name'), element.getAttribute('id'),
      element.getAttribute('aria-label'), element.getAttribute('placeholder')
    ].filter(Boolean).join(' ')
      .replace(/([A-Z]+)([A-Z][a-z])/g, '$1 $2')
      .replace(/([a-z0-9])([A-Z])/g, '$1 $2');
    return sensitiveName.test(identity);
  };
  const viewportWidth = window.innerWidth;
  const viewportHeight = window.innerHeight;
  const regions = [];
  for (const element of candidates) {
    if (!isSensitive(element)) continue;
    const rect = element.getBoundingClientRect();
    const left = Math.max(0, rect.left);
    const top = Math.max(0, rect.top);
    const right = Math.min(viewportWidth, rect.right);
    const bottom = Math.min(viewportHeight, rect.bottom);
    if (right <= left || bottom <= top) continue;
    if (regions.length >= 256) {
      return {version: 1, error: 'SENSITIVE_REGION_LIMIT_EXCEEDED'};
    }
    regions.push({left, top, width: right - left, height: bottom - top});
  }
  return {version: 1, viewportWidth, viewportHeight, regions};
})()
"#;

async fn redact_recording_frame<S>(
    socket: &mut tokio_tungstenite::WebSocketStream<S>,
    encoded: &str,
    frame_session_id: i64,
) -> anyhow::Result<RedactedRecordingFrame>
where
    S: tokio::io::AsyncRead + tokio::io::AsyncWrite + Unpin,
{
    let command_id = 20_000_i64.saturating_add(frame_session_id);
    let response = send_command_value(
        socket,
        command_id,
        "Runtime.evaluate",
        json!({
            "expression": COLLECT_RECORDING_REDACTION_REGIONS_SCRIPT,
            "returnByValue": true,
            "awaitPromise": false,
            "userGesture": false
        }),
    )
    .await?;
    let snapshot: SensitiveRegionSnapshot = serde_json::from_value(
        response
            .pointer("/result/result/value")
            .cloned()
            .ok_or_else(|| anyhow::anyhow!("recording redaction response omitted region data"))?,
    )?;
    anyhow::ensure!(
        snapshot.version == RECORDING_DOM_REDACTION_POLICY_VERSION && snapshot.error.is_none(),
        "recording redaction region collection failed closed: {}",
        snapshot
            .error
            .as_deref()
            .unwrap_or("unsupported policy version")
    );
    anyhow::ensure!(
        snapshot.viewport_width.is_finite()
            && snapshot.viewport_height.is_finite()
            && snapshot.viewport_width > 0.0
            && snapshot.viewport_height > 0.0
            && snapshot.regions.len() <= RECORDING_MAX_SENSITIVE_REGIONS,
        "recording redaction viewport or region count is invalid"
    );
    let compressed = base64::engine::general_purpose::STANDARD
        .decode(encoded)
        .map_err(|_| anyhow::anyhow!("CDP recording frame is not valid base64"))?;
    anyhow::ensure!(
        compressed.len() <= EVIDENCE_MAX_BYTES,
        "CDP recording frame exceeds the compressed image bound"
    );
    let mut decoder = jpeg_decoder::Decoder::new(Cursor::new(compressed));
    decoder.set_max_decoding_buffer_size(
        RECORDING_MAX_WIDTH
            .saturating_mul(RECORDING_MAX_HEIGHT)
            .saturating_mul(4),
    );
    let mut pixels = decoder
        .decode()
        .map_err(|error| anyhow::anyhow!("CDP recording JPEG decode failed: {error}"))?;
    let info = decoder
        .info()
        .ok_or_else(|| anyhow::anyhow!("CDP recording JPEG omitted image metadata"))?;
    let width = usize::from(info.width);
    let height = usize::from(info.height);
    anyhow::ensure!(
        width > 0 && height > 0 && width <= RECORDING_MAX_WIDTH && height <= RECORDING_MAX_HEIGHT,
        "CDP recording JPEG dimensions exceed the bound"
    );
    let channels = match info.pixel_format {
        PixelFormat::L8 => 1,
        PixelFormat::RGB24 => 3,
        _ => anyhow::bail!("CDP recording JPEG pixel format is unsupported"),
    };
    anyhow::ensure!(
        pixels.len() == width.saturating_mul(height).saturating_mul(channels),
        "CDP recording JPEG pixel buffer has an invalid size"
    );
    for region in &snapshot.regions {
        mask_sensitive_region(
            &mut pixels,
            width,
            height,
            channels,
            snapshot.viewport_width,
            snapshot.viewport_height,
            region,
        )?;
    }
    let mut redacted_jpeg = Vec::with_capacity(pixels.len() / 2);
    jpeg_encoder::Encoder::new(&mut redacted_jpeg, 60)
        .encode(
            &pixels,
            info.width,
            info.height,
            if channels == 1 {
                ColorType::Luma
            } else {
                ColorType::Rgb
            },
        )
        .map_err(|error| anyhow::anyhow!("recording redaction JPEG encode failed: {error}"))?;
    let data = base64::engine::general_purpose::STANDARD.encode(redacted_jpeg);
    anyhow::ensure!(
        data.len() <= SEGMENT_MAX_BYTES as usize,
        "redacted recording frame exceeds the segment bound"
    );
    Ok(RedactedRecordingFrame {
        data,
        redacted_region_count: snapshot
            .regions
            .len()
            .try_into()
            .map_err(|_| anyhow::anyhow!("recording redaction region count overflow"))?,
    })
}

#[allow(clippy::too_many_arguments)]
fn mask_sensitive_region(
    pixels: &mut [u8],
    image_width: usize,
    image_height: usize,
    channels: usize,
    viewport_width: f64,
    viewport_height: f64,
    region: &SensitiveRegion,
) -> anyhow::Result<()> {
    anyhow::ensure!(
        region.left.is_finite()
            && region.top.is_finite()
            && region.width.is_finite()
            && region.height.is_finite()
            && region.left >= 0.0
            && region.top >= 0.0
            && region.width > 0.0
            && region.height > 0.0
            && region.left + region.width <= viewport_width
            && region.top + region.height <= viewport_height,
        "recording redaction region is invalid"
    );
    let scale_x = image_width as f64 / viewport_width;
    let scale_y = image_height as f64 / viewport_height;
    let left = (region.left * scale_x).floor().max(0.0) as usize;
    let top = (region.top * scale_y).floor().max(0.0) as usize;
    let right = ((region.left + region.width) * scale_x)
        .ceil()
        .min(image_width as f64) as usize;
    let bottom = ((region.top + region.height) * scale_y)
        .ceil()
        .min(image_height as f64) as usize;
    anyhow::ensure!(
        left < right && top < bottom && right <= image_width && bottom <= image_height,
        "recording redaction region is outside the image"
    );
    for y in top..bottom {
        for x in left..right {
            let offset = (y * image_width + x) * channels;
            if channels == 1 {
                pixels[offset] = 7;
            } else {
                pixels[offset..offset + 3].copy_from_slice(&[5, 8, 13]);
            }
        }
    }
    Ok(())
}

async fn write_segments(
    spec: RecordingSpec,
    recording_id: String,
    mut frames: mpsc::Receiver<CapturedFrame>,
    dropped_frames: Arc<std::sync::atomic::AtomicU64>,
    capture_failed: Arc<std::sync::atomic::AtomicBool>,
) -> anyhow::Result<RecordingSummary> {
    let recording_started_at = now_millis();
    let directory = PathBuf::from(&spec.workspace.ephemeral_dir)
        .join("recordings")
        .join(&recording_id);
    let mut sequence = 0_u64;
    let mut total_frames = 0_u64;
    let mut redacted_frame_count = 0_u64;
    let mut redacted_region_count = 0_u64;
    let mut segment: Option<SegmentWriter> = None;
    while let Some(frame) = frames.recv().await {
        if segment.is_none() {
            segment = Some(SegmentWriter::open(&directory, sequence, frame.captured_at_ms).await?);
        }
        let current = segment.as_mut().expect("segment opened");
        current.write(&frame).await?;
        total_frames = total_frames.saturating_add(1);
        if frame.redacted_region_count > 0 {
            redacted_frame_count = redacted_frame_count.saturating_add(1);
        }
        redacted_region_count =
            redacted_region_count.saturating_add(u64::from(frame.redacted_region_count));
        if current.bytes >= SEGMENT_MAX_BYTES
            || current.ended_at_ms.saturating_sub(current.started_at_ms) >= SEGMENT_MAX_DURATION_MS
        {
            commit_segment(&spec, &recording_id, current).await?;
            segment = None;
            sequence = sequence.saturating_add(1);
        }
    }
    if let Some(current) = segment.as_mut() {
        commit_segment(&spec, &recording_id, current).await?;
        sequence = sequence.saturating_add(1);
    }
    anyhow::ensure!(
        !capture_failed.load(std::sync::atomic::Ordering::Acquire),
        "recording capture failed before a redaction-safe completion marker could be committed"
    );
    let ended_at_ms = now_millis();
    let completed = spec
        .storage_helper
        .complete_recording(
            &spec.workspace,
            &recording_id,
            sequence,
            total_frames,
            redacted_frame_count,
            redacted_region_count,
            RECORDING_REDACTION_POLICY_VERSION,
            recording_started_at,
            ended_at_ms,
        )
        .await?;
    anyhow::ensure!(
        completed.completed
            && completed.frame_count == total_frames
            && completed.redacted_frame_count == redacted_frame_count
            && completed.redacted_region_count == redacted_region_count
            && completed.redaction_policy_version == RECORDING_REDACTION_POLICY_VERSION
            && completed
                .object_key
                .as_deref()
                .is_some_and(|key| !key.is_empty())
            && completed
                .manifest_sha256
                .as_deref()
                .is_some_and(|hash| hash.len() == 64)
            && completed.manifest_bytes > 0,
        "storage helper did not acknowledge the recording redaction manifest"
    );
    Ok(RecordingSummary {
        recording_id,
        segment_count: sequence,
        frame_count: total_frames,
        dropped_frames: dropped_frames.load(std::sync::atomic::Ordering::Relaxed),
        redacted_frame_count,
        redacted_region_count,
        redaction_policy_version: RECORDING_REDACTION_POLICY_VERSION,
        manifest_object_key: completed.object_key.expect("validated manifest object key"),
        manifest_sha256: completed
            .manifest_sha256
            .expect("validated manifest SHA-256"),
        manifest_bytes: completed.manifest_bytes,
        started_at_ms: recording_started_at,
        ended_at_ms,
    })
}

struct SegmentWriter {
    sequence: u64,
    file: tokio::fs::File,
    hasher: Sha256,
    bytes: u64,
    frames: u64,
    redacted_frames: u64,
    redacted_regions: u64,
    started_at_ms: u64,
    ended_at_ms: u64,
}

impl SegmentWriter {
    async fn open(
        directory: &std::path::Path,
        sequence: u64,
        started_at_ms: u64,
    ) -> anyhow::Result<Self> {
        let path = directory.join(format!("segment-{sequence:020}.ndjson"));
        let file = tokio::fs::OpenOptions::new()
            .create_new(true)
            .write(true)
            .open(&path)
            .await?;
        Ok(Self {
            sequence,
            file,
            hasher: Sha256::new(),
            bytes: 0,
            frames: 0,
            redacted_frames: 0,
            redacted_regions: 0,
            started_at_ms,
            ended_at_ms: started_at_ms,
        })
    }

    async fn write(&mut self, frame: &CapturedFrame) -> anyhow::Result<()> {
        let mut line = serde_json::to_vec(&json!({
            "capturedAtMs": frame.captured_at_ms,
            "cdpSessionId": frame.session_id,
            "format": "jpeg",
            "metadata": frame.metadata,
            "redactionState": frame.redaction_state,
            "redactedRegionCount": frame.redacted_region_count,
            "redactionPolicyVersion": RECORDING_REDACTION_POLICY_VERSION,
            "privacyScanState": "VERIFIED",
            "privacyScanVersion": frame.privacy_scan.scan_version,
            "sanitizedImageSha256": frame.privacy_scan.sanitized_image_sha256,
            "ocrDetectedSignalCount": frame.privacy_scan.ocr_detected_signal_count,
            "ocrMaskedRegionCount": frame.privacy_scan.ocr_masked_region_count,
            "ocrResidualSignalCount": frame.privacy_scan.ocr_residual_signal_count,
            "visualDetectedSignalCount": frame.privacy_scan.visual_detected_signal_count,
            "visualMaskedRegionCount": frame.privacy_scan.visual_masked_region_count,
            "visualResidualSignalCount": frame.privacy_scan.visual_residual_signal_count,
            "data": frame.data
        }))?;
        line.push(b'\n');
        self.file.write_all(&line).await?;
        self.hasher.update(&line);
        self.bytes = self.bytes.saturating_add(line.len() as u64);
        self.frames = self.frames.saturating_add(1);
        if frame.redacted_region_count > 0 {
            self.redacted_frames = self.redacted_frames.saturating_add(1);
        }
        self.redacted_regions = self
            .redacted_regions
            .saturating_add(u64::from(frame.redacted_region_count));
        self.ended_at_ms = frame.captured_at_ms;
        Ok(())
    }
}

async fn commit_segment(
    spec: &RecordingSpec,
    recording_id: &str,
    segment: &mut SegmentWriter,
) -> anyhow::Result<()> {
    segment.file.flush().await?;
    segment.file.sync_data().await?;
    let content_sha256 = format!("{:x}", segment.hasher.clone().finalize());
    let committed = spec
        .storage_helper
        .commit_recording_segment(
            &spec.workspace,
            recording_id,
            segment.sequence,
            &content_sha256,
            segment.bytes,
            segment.frames,
            segment.redacted_frames,
            segment.redacted_regions,
            RECORDING_REDACTION_POLICY_VERSION,
            segment.started_at_ms,
            segment.ended_at_ms,
        )
        .await?;
    anyhow::ensure!(
        committed.segment_sequence == Some(segment.sequence)
            && committed.frame_count == segment.frames
            && committed.redacted_frame_count == segment.redacted_frames
            && committed.redacted_region_count == segment.redacted_regions
            && committed.redaction_policy_version == RECORDING_REDACTION_POLICY_VERSION,
        "storage helper did not acknowledge recording segment redaction metadata"
    );
    Ok(())
}

#[derive(Debug, PartialEq)]
struct RedactedScreenshot {
    content: Vec<u8>,
    redacted_region_count: u32,
    metadata: Option<ScreenshotMetadata>,
}

const INSTALL_REDACTION_SCRIPT: &str = r#"
(() => {
  const rootId = '__agent_browser_sensitive_redaction_v1';
  document.getElementById(rootId)?.remove();
  const root = document.createElement('div');
  root.id = rootId;
  root.setAttribute('aria-hidden', 'true');
  const documentWidth = Math.max(
    document.documentElement.scrollWidth,
    document.documentElement.clientWidth,
    document.body?.scrollWidth || 0,
    document.body?.clientWidth || 0
  );
  const documentHeight = Math.max(
    document.documentElement.scrollHeight,
    document.documentElement.clientHeight,
    document.body?.scrollHeight || 0,
    document.body?.clientHeight || 0
  );
  for (const [name, value] of Object.entries({
    position: 'absolute',
    left: '0',
    top: '0',
    width: `${documentWidth}px`,
    height: `${documentHeight}px`,
    overflow: 'hidden',
    'pointer-events': 'none',
    'z-index': '2147483647'
  })) root.style.setProperty(name, value, 'important');

  const sensitiveName = /(^|[^a-z])(password|passwd|pwd|passcode|otp|one.?time.?code|pin|cvv|cvc|card.?number|account.?number|routing.?number|secret|token|api.?key|private.?key|ssn|social.?security)([^a-z]|$)/i;
  const sensitiveAutocomplete = new Set([
    'current-password', 'new-password', 'one-time-code', 'cc-number', 'cc-csc',
    'cc-exp', 'cc-exp-month', 'cc-exp-year', 'transaction-amount',
    'transaction-currency'
  ]);
  const candidateSelector =
    'input, textarea, select, iframe, [contenteditable="true"], ' +
    '[data-sensitive], [data-private], [data-redact], [data-classification]';
  const candidates = new Set();
  const roots = [document];
  let scannedElements = 0;
  while (roots.length > 0) {
    const currentRoot = roots.pop();
    for (const element of currentRoot.querySelectorAll('*')) {
      scannedElements += 1;
      if (scannedElements > 10000) {
        return {version: 1, error: 'SENSITIVE_SCAN_LIMIT_EXCEEDED'};
      }
      if (element.matches(candidateSelector)) candidates.add(element);
      if (element.shadowRoot) roots.push(element.shadowRoot);
    }
  }

  const isSensitive = (element) => {
    if (element.matches('iframe, [data-sensitive], [data-private], [data-redact]')) return true;
    const classification = (element.getAttribute('data-classification') || '').toUpperCase();
    if (classification === 'SENSITIVE' || classification === 'HIGHLY_SENSITIVE') return true;
    const type = (element.getAttribute('type') || '').toLowerCase();
    if (type === 'password' || type === 'hidden') return true;
    const autocomplete = (element.getAttribute('autocomplete') || '')
      .toLowerCase().split(/\s+/).filter(Boolean);
    if (autocomplete.some((token) => sensitiveAutocomplete.has(token))) return true;
    const identity = [
      element.getAttribute('name'),
      element.getAttribute('id'),
      element.getAttribute('aria-label'),
      element.getAttribute('placeholder')
    ].filter(Boolean).join(' ')
      .replace(/([A-Z]+)([A-Z][a-z])/g, '$1 $2')
      .replace(/([a-z0-9])([A-Z])/g, '$1 $2');
    return sensitiveName.test(identity);
  };

  let count = 0;
  for (const element of candidates) {
    if (!isSensitive(element)) continue;
    const rect = element.getBoundingClientRect();
    const left = Math.max(0, rect.left + window.scrollX);
    const top = Math.max(0, rect.top + window.scrollY);
    const right = Math.min(documentWidth, rect.right + window.scrollX);
    const bottom = Math.min(documentHeight, rect.bottom + window.scrollY);
    if (right <= left || bottom <= top) continue;
    const mask = document.createElement('div');
    mask.setAttribute('data-agent-browser-redaction-region', String(count + 1));
    for (const [name, value] of Object.entries({
      position: 'absolute',
      left: `${left}px`,
      top: `${top}px`,
      width: `${right - left}px`,
      height: `${bottom - top}px`,
      background: '#05080d',
      border: '1px solid #26313d',
      'box-sizing': 'border-box',
      opacity: '1',
      visibility: 'visible'
    })) mask.style.setProperty(name, value, 'important');
    root.appendChild(mask);
    count += 1;
  }
  document.documentElement.appendChild(root);
  return {
    version: 1,
    redactedRegionCount: count,
    viewportWidth: window.innerWidth,
    viewportHeight: window.innerHeight,
    deviceScaleFactor: window.devicePixelRatio,
    pageX: window.scrollX,
    pageY: window.scrollY,
    documentWidth,
    documentHeight
  };
})()
"#;

const VERIFY_REDACTION_SCRIPT: &str = r#"
(() => {
  const root = document.getElementById('__agent_browser_sensitive_redaction_v1');
  if (!root || !root.isConnected) return {valid: false, redactedRegionCount: 0};
  const regions = root.querySelectorAll('[data-agent-browser-redaction-region]');
  return {valid: true, redactedRegionCount: regions.length};
})()
"#;

const REMOVE_REDACTION_SCRIPT: &str =
    "document.getElementById('__agent_browser_sensitive_redaction_v1')?.remove(); true";

fn verified_redacted_region_count(installed: &Value, verified: &Value) -> anyhow::Result<u32> {
    let installed_count = installed
        .pointer("/result/result/value/redactedRegionCount")
        .and_then(Value::as_u64)
        .and_then(|value| value.try_into().ok())
        .ok_or_else(|| anyhow::anyhow!("sensitive redaction installation was not acknowledged"))?;
    let verified_count = verified
        .pointer("/result/result/value/redactedRegionCount")
        .and_then(Value::as_u64)
        .and_then(|value| value.try_into().ok());
    anyhow::ensure!(
        verified
            .pointer("/result/result/value/valid")
            .and_then(Value::as_bool)
            == Some(true)
            && verified_count == Some(installed_count),
        "sensitive redaction verification failed closed"
    );
    Ok(installed_count)
}

#[derive(Debug, Clone, Copy)]
struct CaptureLayout {
    viewport_width: f64,
    viewport_height: f64,
    device_scale_factor: f64,
    page_x: f64,
    page_y: f64,
    document_width: f64,
    document_height: f64,
}

fn capture_layout(installed: &Value) -> anyhow::Result<CaptureLayout> {
    let number = |name: &str| {
        installed
            .pointer(&format!("/result/result/value/{name}"))
            .and_then(Value::as_f64)
            .ok_or_else(|| anyhow::anyhow!("screenshot layout {name} is unavailable"))
    };
    let layout = CaptureLayout {
        viewport_width: number("viewportWidth")?,
        viewport_height: number("viewportHeight")?,
        device_scale_factor: number("deviceScaleFactor")?,
        page_x: number("pageX")?,
        page_y: number("pageY")?,
        document_width: number("documentWidth")?,
        document_height: number("documentHeight")?,
    };
    anyhow::ensure!(
        layout.viewport_width.is_finite()
            && layout.viewport_height.is_finite()
            && layout.device_scale_factor.is_finite()
            && layout.page_x.is_finite()
            && layout.page_y.is_finite()
            && layout.document_width.is_finite()
            && layout.document_height.is_finite()
            && (1.0..=7680.0).contains(&layout.viewport_width)
            && (1.0..=4320.0).contains(&layout.viewport_height)
            && (0.25..=8.0).contains(&layout.device_scale_factor)
            && layout.page_x >= 0.0
            && layout.page_y >= 0.0
            && (1.0..=16384.0).contains(&layout.document_width)
            && (1.0..=16384.0).contains(&layout.document_height),
        "screenshot layout is outside the bounded capture range"
    );
    Ok(layout)
}

fn screenshot_capture_plan(
    options: &ScreenshotCaptureOptions,
    layout: CaptureLayout,
) -> anyhow::Result<(Value, ScreenshotMetadata)> {
    anyhow::ensure!(
        !options.active_tab_id.is_empty()
            && options.active_tab_id.len() <= 128
            && !options.active_tab_id.chars().any(char::is_control),
        "active screenshot Page Target ID is invalid"
    );
    let (params, region, coordinate_space) = match options.capture_mode.as_str() {
        "VIEWPORT" => {
            anyhow::ensure!(
                options.clip.is_none(),
                "viewport screenshot cannot contain a clip"
            );
            (
                json!({
                    "format": "jpeg",
                    "quality": 70,
                    "fromSurface": true,
                    "captureBeyondViewport": false
                }),
                ScreenshotClip {
                    x: 0.0,
                    y: 0.0,
                    width: layout.viewport_width,
                    height: layout.viewport_height,
                },
                "VIEWPORT".to_owned(),
            )
        }
        "FULL_PAGE" => {
            anyhow::ensure!(
                options.clip.is_none(),
                "full-page screenshot cannot contain a clip"
            );
            (
                json!({
                    "format": "jpeg",
                    "quality": 70,
                    "fromSurface": true,
                    "captureBeyondViewport": true,
                    "clip": {
                        "x": 0.0,
                        "y": 0.0,
                        "width": layout.document_width,
                        "height": layout.document_height,
                        "scale": 1.0
                    }
                }),
                ScreenshotClip {
                    x: 0.0,
                    y: 0.0,
                    width: layout.document_width,
                    height: layout.document_height,
                },
                "DOCUMENT".to_owned(),
            )
        }
        "ELEMENT" | "OPAQUE_FRAME" | "REGION" | "CHALLENGE_REGION" => {
            let clip = options
                .clip
                .as_ref()
                .ok_or_else(|| anyhow::anyhow!("bounded screenshot clip is required"))?;
            anyhow::ensure!(
                clip.x.is_finite()
                    && clip.y.is_finite()
                    && clip.width.is_finite()
                    && clip.height.is_finite()
                    && clip.x >= 0.0
                    && clip.y >= 0.0
                    && clip.width >= 1.0
                    && clip.height >= 1.0
                    && clip.x + clip.width <= layout.viewport_width + 0.001
                    && clip.y + clip.height <= layout.viewport_height + 0.001,
                "bounded screenshot clip is outside the current viewport"
            );
            (
                json!({
                    "format": "jpeg",
                    "quality": 70,
                    "fromSurface": true,
                    "captureBeyondViewport": false,
                    "clip": {
                        "x": layout.page_x + clip.x,
                        "y": layout.page_y + clip.y,
                        "width": clip.width,
                        "height": clip.height,
                        "scale": 1.0
                    }
                }),
                clip.clone(),
                "VIEWPORT".to_owned(),
            )
        }
        _ => anyhow::bail!("screenshot capture mode is unsupported"),
    };
    let pixel_width = region.width * layout.device_scale_factor;
    let pixel_height = region.height * layout.device_scale_factor;
    anyhow::ensure!(
        pixel_width.is_finite()
            && pixel_height.is_finite()
            && pixel_width <= 16384.0
            && pixel_height <= 16384.0
            && pixel_width * pixel_height <= 33_554_432.0,
        "screenshot capture exceeds the bounded pixel budget"
    );
    Ok((
        params,
        ScreenshotMetadata {
            capture_mode: options.capture_mode.clone(),
            active_tab_id: options.active_tab_id.clone(),
            viewport_width: layout.viewport_width,
            viewport_height: layout.viewport_height,
            device_scale_factor: layout.device_scale_factor,
            region,
            coordinate_space,
        },
    ))
}

#[cfg(test)]
async fn capture_screenshot(
    cdp_endpoint: &str,
    options: Option<&ScreenshotCaptureOptions>,
) -> anyhow::Result<RedactedScreenshot> {
    capture_screenshot_serialized(cdp_endpoint, options, Arc::new(Mutex::new(()))).await
}

/// The socket and Session lock remain owned by this bounded task if the caller is cancelled.
/// Only the capture result returns to the caller; cancellation never starts an object commit.
async fn capture_screenshot_serialized(
    cdp_endpoint: &str,
    options: Option<&ScreenshotCaptureOptions>,
    capture_lock: Arc<Mutex<()>>,
) -> anyhow::Result<RedactedScreenshot> {
    let endpoint = cdp_endpoint.to_owned();
    let options = options.cloned();
    let (mut result, receiver) = oneshot::channel();
    tokio::spawn(async move {
        let _guard = tokio::select! {
            guard = capture_lock.lock_owned() => guard,
            _ = result.closed() => return,
        };
        let connected = tokio::select! {
            connected = ScreenshotCdp::connect(&endpoint, options.as_ref()) => connected,
            _ = result.closed() => return,
        };
        let mut cdp = match connected {
            Ok(cdp) => cdp,
            Err(error) => {
                let _ = result.send(Err(error));
                return;
            }
        };
        let capture = tokio::select! {
            capture = cdp.capture(options.as_ref()) => capture,
            _ = result.closed() => Err(anyhow::anyhow!("screenshot capture cancelled")),
        };
        let cleanup = cdp.cleanup().await;
        let _ = result.send(cleanup.and(capture));
    });
    receiver
        .await
        .map_err(|_| anyhow::anyhow!("screenshot capture task unavailable"))?
}

type ScreenshotSocket =
    tokio_tungstenite::WebSocketStream<tokio_tungstenite::MaybeTlsStream<tokio::net::TcpStream>>;

#[derive(Debug, PartialEq, Eq)]
struct ScreenshotDocument {
    frame_id: String,
    loader_id: String,
}

fn screenshot_document(value: &Value) -> anyhow::Result<ScreenshotDocument> {
    let frame = value
        .pointer("/result/frameTree/frame")
        .ok_or_else(|| anyhow::anyhow!("screenshot document identity unavailable"))?;
    let identity = |field| {
        frame
            .get(field)
            .and_then(Value::as_str)
            .filter(|value| {
                !value.is_empty() && value.len() <= 256 && !value.chars().any(char::is_control)
            })
            .map(str::to_owned)
            .ok_or_else(|| anyhow::anyhow!("screenshot document identity unavailable"))
    };
    anyhow::ensure!(
        frame.get("parentId").is_none(),
        "screenshot main document identity invalid"
    );
    Ok(ScreenshotDocument {
        frame_id: identity("id")?,
        loader_id: identity("loaderId")?,
    })
}

struct ScreenshotCdp {
    socket: ScreenshotSocket,
    sequence: i64,
    freeze_url: String,
    freeze_script: Option<String>,
    paused: bool,
    owned_pause: bool,
    debugger_attempted: bool,
    redaction_attempted: bool,
}

impl ScreenshotCdp {
    async fn connect(
        endpoint: &str,
        options: Option<&ScreenshotCaptureOptions>,
    ) -> anyhow::Result<Self> {
        let websocket =
            target_websocket(endpoint, options.map(|value| value.active_tab_id.as_str())).await?;
        require_loopback_websocket(&websocket)?;
        let (socket, _) =
            tokio::time::timeout(CDP_TIMEOUT, tokio_tungstenite::connect_async(&websocket))
                .await
                .map_err(|_| anyhow::anyhow!("CDP evidence connection timed out"))??;
        Ok(Self {
            socket,
            sequence: 0,
            freeze_url: format!(
                "agent-browser-evidence-freeze-{}",
                uuid::Uuid::new_v4().simple()
            ),
            freeze_script: None,
            paused: false,
            owned_pause: false,
            debugger_attempted: false,
            redaction_attempted: false,
        })
    }

    fn observe(&mut self, response: &Value) {
        match response.get("method").and_then(Value::as_str) {
            Some("Debugger.scriptParsed")
                if response.pointer("/params/url").and_then(Value::as_str)
                    == Some(&self.freeze_url) =>
            {
                self.freeze_script = response
                    .pointer("/params/scriptId")
                    .and_then(Value::as_str)
                    .filter(|value| !value.is_empty() && value.len() <= 256)
                    .map(str::to_owned);
            }
            Some("Debugger.paused") => {
                self.paused = true;
                self.owned_pause = self.freeze_script.as_deref().is_some_and(|script| {
                    response
                        .pointer("/params/callFrames/0/location/scriptId")
                        .and_then(Value::as_str)
                        == Some(script)
                        && response.pointer("/params/reason").and_then(Value::as_str)
                            == Some("other")
                        && response
                            .pointer("/params/hitBreakpoints")
                            .and_then(Value::as_array)
                            .is_some_and(Vec::is_empty)
                });
            }
            Some("Debugger.resumed") => {
                self.paused = false;
                self.owned_pause = false;
            }
            _ => {}
        }
    }

    async fn receive(&mut self, deadline: tokio::time::Instant) -> anyhow::Result<Value> {
        loop {
            let message = tokio::time::timeout_at(deadline, self.socket.next())
                .await
                .map_err(|_| anyhow::anyhow!("CDP screenshot response timed out"))?
                .ok_or_else(|| anyhow::anyhow!("CDP screenshot socket closed"))??;
            anyhow::ensure!(
                tokio::time::Instant::now() < deadline,
                "CDP screenshot response timed out"
            );
            if let Message::Text(text) = message {
                let value: Value = serde_json::from_str(&text)?;
                self.observe(&value);
                return Ok(value);
            }
        }
    }

    async fn send(&mut self, method: &str, params: Value) -> anyhow::Result<i64> {
        self.sequence += 1;
        let id = self.sequence;
        tokio::time::timeout(
            CDP_TIMEOUT,
            self.socket.send(Message::Text(
                json!({"id": id, "method": method, "params": params}).to_string(),
            )),
        )
        .await
        .map_err(|_| anyhow::anyhow!("CDP screenshot command timed out"))??;
        Ok(id)
    }

    async fn command(&mut self, method: &str, params: Value) -> anyhow::Result<Value> {
        let id = self.send(method, params).await?;
        let deadline = tokio::time::Instant::now() + CDP_TIMEOUT;
        loop {
            let reply = self.receive(deadline).await?;
            if reply.get("id").and_then(Value::as_i64) == Some(id) {
                anyhow::ensure!(
                    reply.get("error").is_none(),
                    "CDP screenshot command rejected"
                );
                return Ok(reply);
            }
        }
    }

    async fn document(&mut self) -> anyhow::Result<ScreenshotDocument> {
        screenshot_document(&self.command("Page.getFrameTree", json!({})).await?)
    }

    async fn require_document(&mut self, expected: &ScreenshotDocument) -> anyhow::Result<()> {
        anyhow::ensure!(
            self.document().await? == *expected,
            "screenshot document changed"
        );
        Ok(())
    }

    async fn freeze(&mut self) -> anyhow::Result<()> {
        anyhow::ensure!(!self.paused, "screenshot page already paused");
        // A uniquely named fixed debugger statement proves this connection owns the pause.
        // Disabling script execution would silently discard a new document's bootstrap scripts.
        let id = self
            .send(
                "Runtime.evaluate",
                json!({
                    "expression": format!("debugger;\n//# sourceURL={}", self.freeze_url),
                    "returnByValue": true, "awaitPromise": false, "userGesture": false,
                }),
            )
            .await?;
        let deadline = tokio::time::Instant::now() + CDP_TIMEOUT;
        loop {
            let reply = self.receive(deadline).await?;
            if self.paused {
                anyhow::ensure!(self.owned_pause, "screenshot pause ownership unavailable");
                return Ok(());
            }
            anyhow::ensure!(
                reply.get("id").and_then(Value::as_i64) != Some(id),
                "screenshot script pause unavailable"
            );
        }
    }

    async fn capture(
        &mut self,
        options: Option<&ScreenshotCaptureOptions>,
    ) -> anyhow::Result<RedactedScreenshot> {
        self.command("Page.enable", json!({})).await?;
        let expected = self.document().await?;
        self.debugger_attempted = true;
        self.command("Debugger.enable", json!({"maxScriptsCacheSize": 0}))
            .await?;
        anyhow::ensure!(!self.paused, "screenshot page already paused");
        self.redaction_attempted = true;
        let installed = self
            .command(
                "Runtime.evaluate",
                json!({
                    "expression": INSTALL_REDACTION_SCRIPT, "returnByValue": true,
                    "awaitPromise": false, "userGesture": false,
                }),
            )
            .await?;
        let verified = self
            .command(
                "Runtime.evaluate",
                json!({
                    "expression": VERIFY_REDACTION_SCRIPT, "returnByValue": true,
                    "awaitPromise": false, "userGesture": false,
                }),
            )
            .await?;
        let redacted_region_count = verified_redacted_region_count(&installed, &verified)?;
        let plan = options
            .map(|options| {
                capture_layout(&installed)
                    .and_then(|layout| screenshot_capture_plan(options, layout))
            })
            .transpose()?;
        self.require_document(&expected).await?;
        self.freeze().await?;
        self.require_document(&expected).await?;
        anyhow::ensure!(
            self.paused && self.owned_pause,
            "screenshot script pause lost"
        );
        let document = self
            .command("DOM.getDocument", json!({"depth": 0, "pierce": true}))
            .await?;
        let node = document
            .pointer("/result/root/nodeId")
            .and_then(Value::as_i64)
            .filter(|value| *value > 0)
            .ok_or_else(|| anyhow::anyhow!("sensitive redaction frozen document unavailable"))?;
        let root = self
            .command(
                "DOM.querySelector",
                json!({
                    "nodeId": node, "selector": "#__agent_browser_sensitive_redaction_v1",
                }),
            )
            .await?;
        let root = root
            .pointer("/result/nodeId")
            .and_then(Value::as_i64)
            .filter(|value| *value > 0)
            .ok_or_else(|| {
                anyhow::anyhow!("sensitive redaction root disappeared before capture")
            })?;
        let regions = self
            .command(
                "DOM.querySelectorAll",
                json!({
                    "nodeId": root, "selector": "[data-agent-browser-redaction-region]",
                }),
            )
            .await?;
        anyhow::ensure!(
            regions
                .pointer("/result/nodeIds")
                .and_then(Value::as_array)
                .and_then(|values| u32::try_from(values.len()).ok())
                == Some(redacted_region_count),
            "sensitive redaction frozen DOM verification failed closed"
        );
        anyhow::ensure!(
            self.paused && self.owned_pause,
            "screenshot script pause lost"
        );
        let captured = self
            .command(
                "Page.captureScreenshot",
                plan.as_ref()
                    .map(|(parameters, _)| parameters.clone())
                    .unwrap_or_else(|| {
                        json!({
                            "format": "jpeg", "quality": 70, "fromSurface": true,
                            "captureBeyondViewport": false,
                        })
                    }),
            )
            .await?;
        self.require_document(&expected).await?;
        anyhow::ensure!(
            self.paused && self.owned_pause,
            "screenshot script pause lost"
        );
        let encoded = captured
            .pointer("/result/data")
            .and_then(Value::as_str)
            .ok_or_else(|| anyhow::anyhow!("CDP screenshot response omitted image data"))?;
        let content = base64::engine::general_purpose::STANDARD
            .decode(encoded)
            .map_err(|_| anyhow::anyhow!("CDP screenshot response is not valid base64"))?;
        anyhow::ensure!(
            content.len() <= EVIDENCE_MAX_BYTES,
            "CDP screenshot exceeds the bounded evidence size"
        );
        Ok(RedactedScreenshot {
            content,
            redacted_region_count,
            metadata: plan.map(|(_, metadata)| metadata),
        })
    }

    async fn cleanup(&mut self) -> anyhow::Result<()> {
        let mut resumed = Ok(());
        if self.debugger_attempted {
            // A renderer response, unlike browser-side FrameTree, drains our queued pause.
            let barrier = self
                .command(
                    "Runtime.evaluate",
                    json!({
                        "expression": "void 0", "returnByValue": true,
                        "awaitPromise": false, "userGesture": false,
                    }),
                )
                .await;
            if self.paused && self.owned_pause {
                resumed = self.command("Debugger.resume", json!({})).await.map(|_| ());
            }
            let disabled = self
                .command("Debugger.disable", json!({}))
                .await
                .map(|_| ());
            resumed = barrier.map(|_| ()).and(resumed).and(disabled);
        }
        let cleaned = if self.redaction_attempted {
            self.command(
                "Runtime.evaluate",
                json!({
                    "expression": REMOVE_REDACTION_SCRIPT, "returnByValue": true,
                    "awaitPromise": false, "userGesture": false,
                }),
            )
            .await
            .map(|_| ())
        } else {
            Ok(())
        };
        let closed = tokio::time::timeout(CDP_TIMEOUT, self.socket.close(None))
            .await
            .map_err(|_| anyhow::anyhow!("CDP screenshot close timed out"))?
            .map_err(anyhow::Error::from);
        resumed.and(cleaned).and(closed)
    }
}
async fn prepare_evidence_path(
    workspace: &StorageWorkspace,
    evidence_id: &str,
) -> anyhow::Result<PathBuf> {
    let ephemeral = PathBuf::from(&workspace.ephemeral_dir);
    let canonical_ephemeral = tokio::fs::canonicalize(&ephemeral).await?;
    let root = ephemeral.join("evidence");
    match tokio::fs::create_dir(&root).await {
        Ok(()) => {}
        Err(error) if error.kind() == std::io::ErrorKind::AlreadyExists => {}
        Err(error) => return Err(error.into()),
    }
    let root_metadata = tokio::fs::symlink_metadata(&root).await?;
    anyhow::ensure!(
        root_metadata.is_dir() && !root_metadata.file_type().is_symlink(),
        "evidence root is not a regular directory"
    );
    let canonical_root = tokio::fs::canonicalize(&root).await?;
    anyhow::ensure!(
        canonical_root.parent() == Some(canonical_ephemeral.as_path()),
        "evidence root escaped the Session ephemeral directory"
    );
    let directory = root.join(evidence_id);
    tokio::fs::create_dir(&directory).await?;
    let metadata = tokio::fs::symlink_metadata(&directory).await?;
    anyhow::ensure!(
        metadata.is_dir() && !metadata.file_type().is_symlink(),
        "evidence path is not a regular directory"
    );
    let canonical_directory = tokio::fs::canonicalize(&directory).await?;
    anyhow::ensure!(
        canonical_directory.parent() == Some(canonical_root.as_path()),
        "evidence path escaped the evidence root"
    );
    Ok(canonical_directory.join("screenshot.jpeg"))
}

#[derive(Deserialize)]
struct CdpTarget {
    id: String,
    #[serde(rename = "type")]
    target_type: String,
    #[serde(rename = "webSocketDebuggerUrl")]
    websocket_url: Option<String>,
}

async fn target_websocket(endpoint: &str, target_id: Option<&str>) -> anyhow::Result<String> {
    require_loopback_http(endpoint)?;
    let targets = reqwest::Client::new()
        .get(format!("{}/json/list", endpoint.trim_end_matches('/')))
        .timeout(CDP_TIMEOUT)
        .send()
        .await?
        .error_for_status()?
        .json::<Vec<CdpTarget>>()
        .await?;
    targets
        .into_iter()
        .find(|target| {
            target.target_type == "page" && target_id.is_none_or(|expected| target.id == expected)
        })
        .and_then(|target| target.websocket_url)
        .ok_or_else(|| anyhow::anyhow!("CDP has no matching recordable Page target"))
}

fn require_loopback_http(endpoint: &str) -> anyhow::Result<()> {
    let url = reqwest::Url::parse(endpoint)?;
    anyhow::ensure!(url.scheme() == "http", "CDP endpoint must use HTTP");
    let host = url
        .host_str()
        .ok_or_else(|| anyhow::anyhow!("CDP endpoint host is unavailable"))?;
    anyhow::ensure!(
        matches!(host, "127.0.0.1" | "localhost" | "::1"),
        "CDP endpoint must use the Browser Node loopback"
    );
    Ok(())
}

fn require_loopback_websocket(endpoint: &str) -> anyhow::Result<()> {
    let url = reqwest::Url::parse(endpoint)?;
    anyhow::ensure!(url.scheme() == "ws", "CDP websocket must use WS");
    let host = url
        .host_str()
        .ok_or_else(|| anyhow::anyhow!("CDP websocket host is unavailable"))?;
    anyhow::ensure!(
        matches!(host, "127.0.0.1" | "localhost" | "::1"),
        "CDP websocket must use the Browser Node loopback"
    );
    Ok(())
}

async fn send_command<S>(
    socket: &mut tokio_tungstenite::WebSocketStream<S>,
    id: i64,
    method: &str,
    params: Value,
) -> anyhow::Result<()>
where
    S: tokio::io::AsyncRead + tokio::io::AsyncWrite + Unpin,
{
    send_command_value(socket, id, method, params).await?;
    Ok(())
}

async fn send_command_value<S>(
    socket: &mut tokio_tungstenite::WebSocketStream<S>,
    id: i64,
    method: &str,
    params: Value,
) -> anyhow::Result<Value>
where
    S: tokio::io::AsyncRead + tokio::io::AsyncWrite + Unpin,
{
    socket
        .send(Message::Text(
            json!({"id": id, "method": method, "params": params}).to_string(),
        ))
        .await?;
    let response_deadline = tokio::time::Instant::now() + CDP_TIMEOUT;
    while let Some(message) = tokio::time::timeout_at(response_deadline, socket.next())
        .await
        .map_err(|_| anyhow::anyhow!("CDP {method} timed out"))?
    {
        anyhow::ensure!(
            tokio::time::Instant::now() < response_deadline,
            "CDP {method} timed out"
        );
        let Message::Text(text) = message? else {
            continue;
        };
        let response: Value = serde_json::from_str(&text)?;
        if response.get("id").and_then(Value::as_i64) != Some(id) {
            continue;
        }
        if let Some(error) = response.get("error") {
            anyhow::bail!("CDP {method} failed: {error}");
        }
        anyhow::ensure!(
            tokio::time::Instant::now() < response_deadline,
            "CDP {method} timed out"
        );
        return Ok(response);
    }
    anyhow::bail!("CDP websocket closed before {method} completed")
}

fn now_millis() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis()
        .try_into()
        .unwrap_or(u64::MAX)
}

#[cfg(test)]
mod screenshot_tests;

#[cfg(test)]
mod tests {
    use super::*;
    use std::os::unix::fs::PermissionsExt;
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    use tokio::net::TcpListener;
    use tokio_tungstenite::accept_async;

    #[tokio::test]
    async fn screenshot_response_expires_despite_unrelated_messages() {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let address = listener.local_addr().unwrap();
        let server = tokio::spawn(async move {
            let (stream, _) = listener.accept().await.unwrap();
            let mut socket = accept_async(stream).await.unwrap();
            let Message::Text(request) = socket.next().await.unwrap().unwrap() else {
                panic!("expected screenshot command");
            };
            let request: Value = serde_json::from_str(&request).unwrap();
            assert_eq!(request["method"], "Page.captureScreenshot");
            for index in 0..26 {
                tokio::time::sleep(Duration::from_millis(200)).await;
                let message = match index % 2 {
                    0 => Message::Text(json!({"method":"Page.frameStartedLoading"}).to_string()),
                    _ => Message::Text(json!({"id":999,"result":{}}).to_string()),
                };
                if socket.send(message).await.is_err() {
                    return;
                }
            }
            let _ = socket
                .send(Message::Text(
                    json!({"id":request["id"],"result":{"data":"late"}}).to_string(),
                ))
                .await;
        });
        let (mut socket, _) = tokio_tungstenite::connect_async(format!("ws://{address}"))
            .await
            .unwrap();
        let started = std::time::Instant::now();
        let result = tokio::time::timeout(
            Duration::from_secs(7),
            send_command_value(&mut socket, 1, "Page.captureScreenshot", json!({})),
        )
        .await;
        drop(socket);
        server.abort();
        let _ = server.await;
        let result = result.unwrap();
        assert!(
            result.is_err(),
            "unrelated events must not extend the screenshot response budget"
        );
        assert_eq!(
            result.unwrap_err().to_string(),
            "CDP Page.captureScreenshot timed out"
        );
        assert!(started.elapsed() < Duration::from_secs(6));
    }

    fn solid_rgb_jpeg(width: u16, height: u16, rgb: [u8; 3]) -> String {
        let mut pixels = Vec::with_capacity(usize::from(width) * usize::from(height) * 3);
        for _ in 0..usize::from(width) * usize::from(height) {
            pixels.extend_from_slice(&rgb);
        }
        let mut jpeg = Vec::new();
        jpeg_encoder::Encoder::new(&mut jpeg, 90)
            .encode(&pixels, width, height, ColorType::Rgb)
            .unwrap();
        base64::engine::general_purpose::STANDARD.encode(jpeg)
    }

    async fn passthrough_privacy_scanner() -> PathBuf {
        let path = std::env::temp_dir().join(format!(
            "browsercloud-recording-privacy-fixture-{}",
            uuid::Uuid::new_v4()
        ));
        tokio::fs::write(
            &path,
            r#"#!/usr/bin/python3
import base64, hashlib, json, sys
for line in sys.stdin:
  request = json.loads(line)
  image = base64.b64decode(request["imageBase64"], validate=True)
  digest = hashlib.sha256(image).hexdigest()
  print(json.dumps({
    "scanVersion": "tesseract-opencv-pii-face-qr-v2",
    "sanitizedImageBase64": request["imageBase64"],
    "sanitizedImageSha256": digest,
    "ocrDetectedSignalCount": 0,
    "ocrMaskedRegionCount": 0,
    "ocrResidualSignalCount": 0,
    "visualDetectedSignalCount": 0,
    "visualMaskedRegionCount": 0,
    "visualResidualSignalCount": 0
  }), flush=True)
"#,
        )
        .await
        .unwrap();
        let mut permissions = tokio::fs::metadata(&path).await.unwrap().permissions();
        permissions.set_mode(0o500);
        tokio::fs::set_permissions(&path, permissions)
            .await
            .unwrap();
        path
    }

    #[test]
    fn rejects_non_loopback_cdp_endpoints() {
        assert!(require_loopback_http("http://127.0.0.1:9222").is_ok());
        assert!(require_loopback_http("http://example.com:9222").is_err());
        assert!(require_loopback_websocket("ws://localhost:9222/devtools/page/1").is_ok());
        assert!(require_loopback_websocket("wss://localhost/devtools/page/1").is_err());
    }

    #[test]
    fn fails_closed_when_the_page_removes_a_sensitive_redaction_region() {
        let installed = json!({"result": {"result": {"value": {"redactedRegionCount": 2}}}});
        let tampered =
            json!({"result": {"result": {"value": {"valid": true, "redactedRegionCount": 1}}}});
        assert!(verified_redacted_region_count(&installed, &tampered)
            .unwrap_err()
            .to_string()
            .contains("failed closed"));
    }

    #[test]
    fn plans_viewport_full_page_and_scrolled_region_captures() {
        let layout = CaptureLayout {
            viewport_width: 1280.0,
            viewport_height: 720.0,
            device_scale_factor: 2.0,
            page_x: 0.0,
            page_y: 480.0,
            document_width: 1280.0,
            document_height: 2400.0,
        };
        let options = |capture_mode: &str, clip: Option<ScreenshotClip>| ScreenshotCaptureOptions {
            evidence_id: "evd_1234567890abcdef".to_owned(),
            captured_at_ms: 1_785_283_200_000,
            active_tab_id: "tab-login".to_owned(),
            capture_mode: capture_mode.to_owned(),
            clip,
        };

        let (viewport, viewport_metadata) =
            screenshot_capture_plan(&options("VIEWPORT", None), layout).unwrap();
        assert_eq!(viewport["captureBeyondViewport"], false);
        assert_eq!(viewport_metadata.coordinate_space, "VIEWPORT");
        assert_eq!(viewport_metadata.region.width, 1280.0);

        let (full_page, full_page_metadata) =
            screenshot_capture_plan(&options("FULL_PAGE", None), layout).unwrap();
        assert_eq!(full_page["clip"]["height"], 2400.0);
        assert_eq!(full_page_metadata.coordinate_space, "DOCUMENT");

        let clip = ScreenshotClip {
            x: 10.0,
            y: 20.0,
            width: 300.0,
            height: 180.0,
        };
        let (region, region_metadata) =
            screenshot_capture_plan(&options("REGION", Some(clip.clone())), layout).unwrap();
        assert_eq!(region["clip"]["x"], 10.0);
        assert_eq!(region["clip"]["y"], 500.0);
        assert_eq!(region_metadata.region, clip);
        assert_eq!(region_metadata.coordinate_space, "VIEWPORT");
    }

    #[test]
    fn rejects_out_of_viewport_clips_and_oversized_pixel_captures() {
        let layout = CaptureLayout {
            viewport_width: 1280.0,
            viewport_height: 720.0,
            device_scale_factor: 2.0,
            page_x: 0.0,
            page_y: 0.0,
            document_width: 12000.0,
            document_height: 8000.0,
        };
        let options = |capture_mode: &str, clip: Option<ScreenshotClip>| ScreenshotCaptureOptions {
            evidence_id: "evd_1234567890abcdef".to_owned(),
            captured_at_ms: 1_785_283_200_000,
            active_tab_id: "tab-login".to_owned(),
            capture_mode: capture_mode.to_owned(),
            clip,
        };
        let outside = ScreenshotClip {
            x: 1200.0,
            y: 0.0,
            width: 200.0,
            height: 100.0,
        };

        assert!(
            screenshot_capture_plan(&options("ELEMENT", Some(outside)), layout)
                .unwrap_err()
                .to_string()
                .contains("outside the current viewport")
        );
        assert!(screenshot_capture_plan(&options("FULL_PAGE", None), layout)
            .unwrap_err()
            .to_string()
            .contains("pixel budget"));
    }

    #[derive(Clone, Copy, PartialEq)]
    enum ScreenshotFault {
        None,
        ChangedBeforePause,
        ChangedDuringPause,
        ChangedAfterCapture,
        PauseLost,
        ForeignPause,
        ForeignPauseAtFreeze,
        ResumeRejected,
        Cancel,
    }

    async fn screenshot_fixture(
        fault: ScreenshotFault,
    ) -> (
        String,
        Arc<Mutex<Vec<String>>>,
        oneshot::Receiver<()>,
        JoinHandle<()>,
        JoinHandle<()>,
    ) {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let address = listener.local_addr().unwrap();
        let commands = Arc::new(Mutex::new(Vec::new()));
        let observed = commands.clone();
        let (capturing, receiver) = oneshot::channel();
        let websocket_task = tokio::spawn(async move {
            let (stream, _) = listener.accept().await.unwrap();
            let mut socket = accept_async(stream).await.unwrap();
            let mut document_reads = 0;
            let mut capturing = Some(capturing);
            let mut pending_evaluation = None;
            while let Some(message) = socket.next().await {
                let Message::Text(message) = message.unwrap() else {
                    break;
                };
                let command: Value = serde_json::from_str(&message).unwrap();
                let method = command["method"].as_str().unwrap();
                let id = command["id"].as_i64().unwrap();
                observed.lock().await.push(method.to_owned());
                let response = match method {
                    "Page.getFrameTree" => {
                        document_reads += 1;
                        let changed = match fault {
                            ScreenshotFault::ChangedBeforePause => document_reads >= 2,
                            ScreenshotFault::ChangedDuringPause => document_reads >= 3,
                            ScreenshotFault::ChangedAfterCapture => document_reads >= 4,
                            _ => false,
                        };
                        json!({"frameTree": {"frame": {"id": "main", "loaderId": if changed {"new"} else {"original"}}}})
                    }
                    "Debugger.enable" => {
                        if fault == ScreenshotFault::ForeignPause {
                            socket
                                .send(Message::Text(
                                    json!({"method": "Debugger.paused", "params": {
                                        "reason": "other", "hitBreakpoints": [],
                                        "callFrames": [{"location": {"scriptId": "foreign"}}]
                                    }})
                                    .to_string(),
                                ))
                                .await
                                .unwrap();
                        }
                        json!({"debuggerId": "fixture"})
                    }
                    "Runtime.evaluate" => {
                        let expression = command["params"]["expression"].as_str().unwrap();
                        if fault == ScreenshotFault::ForeignPause {
                            assert_eq!(expression, "void 0");
                        }
                        if expression.starts_with("debugger;\n//# sourceURL=") {
                            let url = expression.split_once("sourceURL=").unwrap().1;
                            socket
                                .send(Message::Text(
                                    json!({"method": "Debugger.scriptParsed", "params": {
                                        "scriptId": "owned", "url": url,
                                    }})
                                    .to_string(),
                                ))
                                .await
                                .unwrap();
                            socket.send(Message::Text(json!({"method": "Debugger.paused", "params": {
                                "reason": "other", "hitBreakpoints": [],
                                "callFrames": [{"location": {"scriptId":
                                    if fault == ScreenshotFault::ForeignPauseAtFreeze {"foreign"} else {"owned"}}}]
                            }}).to_string())).await.unwrap();
                            pending_evaluation = Some(id);
                            continue;
                        } else if expression == INSTALL_REDACTION_SCRIPT {
                            json!({"result": {"value": {"version": 1, "redactedRegionCount": 2}}})
                        } else if expression == VERIFY_REDACTION_SCRIPT {
                            json!({"result": {"value": {"valid": true, "redactedRegionCount": 2}}})
                        } else if expression == "void 0" {
                            json!({"result": {"type": "undefined"}})
                        } else {
                            assert_eq!(expression, REMOVE_REDACTION_SCRIPT);
                            json!({"result": {"value": true}})
                        }
                    }
                    "DOM.getDocument" => json!({"root": {"nodeId": 11}}),
                    "DOM.querySelector" => json!({"nodeId": 12}),
                    "DOM.querySelectorAll" => json!({"nodeIds": [13, 14]}),
                    "Page.captureScreenshot" => {
                        assert_eq!(command["params"]["format"], "jpeg");
                        assert_eq!(command["params"]["quality"], 70);
                        if let Some(capturing) = capturing.take() {
                            let _ = capturing.send(());
                        }
                        if fault == ScreenshotFault::Cancel {
                            continue;
                        }
                        if fault == ScreenshotFault::PauseLost {
                            socket
                                .send(Message::Text(
                                    json!({"method": "Debugger.resumed", "params": {}}).to_string(),
                                ))
                                .await
                                .unwrap();
                        }
                        json!({"data": "/9j/2Q=="})
                    }
                    "Debugger.resume" => {
                        assert!(!matches!(
                            fault,
                            ScreenshotFault::ForeignPause
                                | ScreenshotFault::ForeignPauseAtFreeze
                                | ScreenshotFault::PauseLost
                        ));
                        if fault == ScreenshotFault::ResumeRejected {
                            socket
                                .send(Message::Text(
                                    json!({"id": id, "error": {
                                        "code": -32000, "message": "fixture resume rejected",
                                    }})
                                    .to_string(),
                                ))
                                .await
                                .unwrap();
                            continue;
                        }
                        socket
                            .send(Message::Text(
                                json!({"method": "Debugger.resumed", "params": {}}).to_string(),
                            ))
                            .await
                            .unwrap();
                        if let Some(evaluation) = pending_evaluation.take() {
                            socket
                                .send(Message::Text(
                                    json!({"id": evaluation, "result": {}}).to_string(),
                                ))
                                .await
                                .unwrap();
                        }
                        json!({})
                    }
                    "Page.enable" | "Debugger.disable" => json!({}),
                    _ => panic!("unexpected screenshot command"),
                };
                socket
                    .send(Message::Text(
                        json!({"id": id, "result": response}).to_string(),
                    ))
                    .await
                    .unwrap();
            }
        });
        let http_listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let http_address = http_listener.local_addr().unwrap();
        let http_task = tokio::spawn(async move {
            let (mut stream, _) = http_listener.accept().await.unwrap();
            let mut request = [0_u8; 2048];
            let count = stream.read(&mut request).await.unwrap();
            assert!(String::from_utf8_lossy(&request[..count]).starts_with("GET /json/list "));
            let body = json!([{"id": "page-1", "type": "page",
                "webSocketDebuggerUrl": format!("ws://{address}/devtools/page/1")}])
            .to_string();
            stream.write_all(format!("HTTP/1.1 200 OK\r\ncontent-type: application/json\r\ncontent-length: {}\r\nconnection: close\r\n\r\n{}", body.len(), body).as_bytes()).await.unwrap();
        });
        (
            format!("http://{http_address}"),
            commands,
            receiver,
            websocket_task,
            http_task,
        )
    }

    #[tokio::test]
    async fn captures_real_bounded_jpeg_screenshot_over_cdp() {
        let (endpoint, commands, _, websocket, http) =
            screenshot_fixture(ScreenshotFault::None).await;
        let screenshot = capture_screenshot(&endpoint, None).await.unwrap();
        assert_eq!(
            screenshot,
            RedactedScreenshot {
                content: vec![0xff, 0xd8, 0xff, 0xd9],
                redacted_region_count: 2,
                metadata: None,
            }
        );
        websocket.await.unwrap();
        http.await.unwrap();
        let methods = commands.lock().await;
        assert_eq!(
            methods
                .iter()
                .filter(|method| *method == "Debugger.resume")
                .count(),
            1
        );
        assert_eq!(
            methods
                .iter()
                .filter(|method| *method == "Page.getFrameTree")
                .count(),
            4
        );
        assert!(!methods.iter().any(|method| method.contains("Emulation")));
        assert_eq!(methods.last().unwrap(), "Runtime.evaluate");
    }

    #[tokio::test]
    async fn screenshot_rejects_document_change_before_pause_during_pause_and_after_capture() {
        for (fault, captures, resumes) in [
            (ScreenshotFault::ChangedBeforePause, 0, 0),
            (ScreenshotFault::ChangedDuringPause, 0, 1),
            (ScreenshotFault::ChangedAfterCapture, 1, 1),
        ] {
            let (endpoint, commands, _, websocket, http) = screenshot_fixture(fault).await;
            let error = capture_screenshot(&endpoint, None).await.unwrap_err();
            assert!(error.to_string().contains("document changed"));
            websocket.await.unwrap();
            http.await.unwrap();
            let methods = commands.lock().await;
            assert_eq!(
                methods
                    .iter()
                    .filter(|method| *method == "Page.captureScreenshot")
                    .count(),
                captures
            );
            assert_eq!(
                methods
                    .iter()
                    .filter(|method| *method == "Debugger.resume")
                    .count(),
                resumes
            );
            assert!(methods.iter().any(|method| method == "Debugger.disable"));
        }
    }

    #[tokio::test]
    async fn screenshot_preserves_foreign_pause_and_rejects_lost_pause() {
        for fault in [
            ScreenshotFault::ForeignPause,
            ScreenshotFault::ForeignPauseAtFreeze,
            ScreenshotFault::PauseLost,
        ] {
            let (endpoint, commands, _, websocket, http) = screenshot_fixture(fault).await;
            assert!(capture_screenshot(&endpoint, None).await.is_err());
            websocket.await.unwrap();
            http.await.unwrap();
            let methods = commands.lock().await;
            assert!(!methods.iter().any(|method| method == "Debugger.resume"));
            assert!(methods.iter().any(|method| method == "Debugger.disable"));
            if fault == ScreenshotFault::ForeignPause {
                assert_eq!(
                    methods
                        .iter()
                        .filter(|method| *method == "Runtime.evaluate")
                        .count(),
                    1
                );
                assert!(!methods
                    .iter()
                    .any(|method| method == "Page.captureScreenshot"));
            }
        }
    }

    #[tokio::test]
    async fn cancelled_screenshot_retains_lock_until_owned_pause_cleanup() {
        let (endpoint, commands, capturing, websocket, http) =
            screenshot_fixture(ScreenshotFault::Cancel).await;
        let capture_lock = Arc::new(Mutex::new(()));
        let owned_lock = capture_lock.clone();
        let caller = tokio::spawn(async move {
            capture_screenshot_serialized(&endpoint, None, owned_lock).await
        });
        capturing.await.unwrap();
        assert!(capture_lock.try_lock().is_err());
        caller.abort();
        assert!(caller.await.unwrap_err().is_cancelled());
        tokio::time::timeout(Duration::from_secs(2), websocket)
            .await
            .unwrap()
            .unwrap();
        http.await.unwrap();
        assert!(capture_lock.try_lock().is_ok());
        let methods = commands.lock().await;
        assert_eq!(
            methods
                .iter()
                .filter(|method| *method == "Debugger.resume")
                .count(),
            1
        );
        assert_eq!(methods.last().unwrap(), "Runtime.evaluate");
    }

    #[tokio::test]
    async fn screenshot_discards_pixels_when_resume_is_not_acknowledged() {
        let (endpoint, commands, _, websocket, http) =
            screenshot_fixture(ScreenshotFault::ResumeRejected).await;
        assert!(capture_screenshot(&endpoint, None).await.is_err());
        websocket.await.unwrap();
        http.await.unwrap();
        let methods = commands.lock().await;
        assert!(methods
            .iter()
            .any(|method| method == "Page.captureScreenshot"));
        assert!(methods.iter().any(|method| method == "Debugger.disable"));
        assert_eq!(methods.last().unwrap(), "Runtime.evaluate");
    }

    #[test]
    fn screenshot_document_requires_exact_bounded_main_frame_loader_identity() {
        for frame in [
            json!({}),
            json!({"id": "main"}),
            json!({"id": "main", "loaderId": ""}),
            json!({"id": "main", "loaderId": "loader", "parentId": "parent"}),
            json!({"id": "main\n", "loaderId": "loader"}),
            json!({"id": "main", "loaderId": "x".repeat(257)}),
        ] {
            assert!(
                screenshot_document(&json!({"result": {"frameTree": {"frame": frame}}})).is_err()
            );
        }
    }

    #[tokio::test]
    async fn captures_and_acknowledges_a_real_cdp_screencast_frame() {
        let raw_frame = solid_rgb_jpeg(16, 16, [240, 20, 20]);
        let websocket_frame = raw_frame.clone();
        let websocket_listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let websocket_address = websocket_listener.local_addr().unwrap();
        let websocket_task = tokio::spawn(async move {
            let (stream, _) = websocket_listener.accept().await.unwrap();
            let mut socket = accept_async(stream).await.unwrap();
            for expected_id in [1_i64, 2_i64] {
                let Message::Text(command) = socket.next().await.unwrap().unwrap() else {
                    panic!("expected CDP command");
                };
                let command: Value = serde_json::from_str(&command).unwrap();
                assert_eq!(command.get("id").and_then(Value::as_i64), Some(expected_id));
                socket
                    .send(Message::Text(
                        json!({"id": expected_id, "result": {}}).to_string(),
                    ))
                    .await
                    .unwrap();
            }
            socket
                .send(Message::Text(
                    json!({
                        "method": "Page.screencastFrame",
                        "params": {
                            "sessionId": 7,
                            "data": websocket_frame,
                            "metadata": {"timestamp": 1.25}
                        }
                    })
                    .to_string(),
                ))
                .await
                .unwrap();
            let Message::Text(redaction) = socket.next().await.unwrap().unwrap() else {
                panic!("expected frame redaction region query");
            };
            let redaction: Value = serde_json::from_str(&redaction).unwrap();
            assert_eq!(redaction["method"], "Runtime.evaluate");
            assert!(redaction["params"]["expression"]
                .as_str()
                .unwrap()
                .contains("SENSITIVE_REGION_LIMIT_EXCEEDED"));
            socket
                .send(Message::Text(
                    json!({
                        "id": redaction["id"],
                        "result": {"result": {"value": {
                            "version": 1,
                            "viewportWidth": 16,
                            "viewportHeight": 16,
                            "regions": [{"left": 0, "top": 0, "width": 16, "height": 16}]
                        }}}
                    })
                    .to_string(),
                ))
                .await
                .unwrap();
            let Message::Text(ack) = socket.next().await.unwrap().unwrap() else {
                panic!("expected screencast ACK");
            };
            let ack: Value = serde_json::from_str(&ack).unwrap();
            assert_eq!(
                ack.get("method").and_then(Value::as_str),
                Some("Page.screencastFrameAck")
            );
            assert_eq!(
                ack.pointer("/params/sessionId").and_then(Value::as_i64),
                Some(7)
            );
            let Message::Text(stop) = socket.next().await.unwrap().unwrap() else {
                panic!("expected stop command");
            };
            let stop: Value = serde_json::from_str(&stop).unwrap();
            assert_eq!(
                stop.get("method").and_then(Value::as_str),
                Some("Page.stopScreencast")
            );
            socket
                .send(Message::Text(
                    json!({"id": stop["id"], "result": {}}).to_string(),
                ))
                .await
                .unwrap();
        });

        let http_listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let http_address = http_listener.local_addr().unwrap();
        let http_task = tokio::spawn(async move {
            let (mut stream, _) = http_listener.accept().await.unwrap();
            let mut request = [0_u8; 2048];
            let count = stream.read(&mut request).await.unwrap();
            assert!(String::from_utf8_lossy(&request[..count]).starts_with("GET /json/list "));
            let body = json!([{
                "id": "page-1",
                "type": "page",
                "webSocketDebuggerUrl": format!("ws://{websocket_address}/devtools/page/1")
            }])
            .to_string();
            stream
                .write_all(
                    format!(
                        "HTTP/1.1 200 OK\r\ncontent-type: application/json\r\ncontent-length: {}\r\nconnection: close\r\n\r\n{}",
                        body.len(),
                        body
                    )
                    .as_bytes(),
                )
                .await
                .unwrap();
        });

        let (frames_tx, mut frames_rx) = mpsc::channel(1);
        let (stop_tx, stop_rx) = watch::channel(false);
        let (ready_tx, ready_rx) = oneshot::channel();
        let dropped = Arc::new(std::sync::atomic::AtomicU64::new(0));
        let capture_dropped = Arc::clone(&dropped);
        let capture_failed = Arc::new(std::sync::atomic::AtomicBool::new(true));
        let capture_failure_signal = Arc::clone(&capture_failed);
        let privacy_scanner_path = passthrough_privacy_scanner().await;
        let capture_privacy_scanner_path = privacy_scanner_path.clone();
        let capture = tokio::spawn(async move {
            capture_frames(
                &format!("http://{http_address}"),
                &capture_privacy_scanner_path,
                frames_tx,
                stop_rx,
                ready_tx,
                capture_dropped.as_ref(),
                capture_failure_signal.as_ref(),
            )
            .await
        });
        ready_rx.await.unwrap().unwrap();
        // Full-frame privacy scanning runs before the CDP ACK and can contend with the parallel
        // test suite on cold builders. Keep this bounded by the same deadline as CDP operations.
        let frame = tokio::time::timeout(CDP_TIMEOUT, frames_rx.recv())
            .await
            .unwrap();
        let Some(frame) = frame else {
            panic!(
                "capture stopped before a safe frame: {:?}",
                capture.await.unwrap()
            );
        };
        assert_eq!(frame.session_id, 7);
        assert_ne!(frame.data, raw_frame);
        assert_eq!(frame.redaction_state, "MASKED");
        assert_eq!(frame.redacted_region_count, 1);
        let redacted_jpeg = base64::engine::general_purpose::STANDARD
            .decode(frame.data)
            .unwrap();
        let mut decoder = jpeg_decoder::Decoder::new(Cursor::new(redacted_jpeg));
        let pixels = decoder.decode().unwrap();
        assert!(pixels.iter().all(|component| *component < 40));
        stop_tx.send(true).unwrap();
        capture.await.unwrap().unwrap();
        assert!(!capture_failed.load(std::sync::atomic::Ordering::Acquire));
        assert_eq!(dropped.load(std::sync::atomic::Ordering::Relaxed), 0);
        http_task.await.unwrap();
        websocket_task.await.unwrap();
    }

    #[tokio::test]
    async fn recording_redaction_failure_acks_but_never_queues_the_raw_frame() {
        let websocket_listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let websocket_address = websocket_listener.local_addr().unwrap();
        let websocket_task = tokio::spawn(async move {
            let (stream, _) = websocket_listener.accept().await.unwrap();
            let mut socket = accept_async(stream).await.unwrap();
            for expected_id in [1_i64, 2_i64] {
                let Message::Text(command) = socket.next().await.unwrap().unwrap() else {
                    panic!("expected CDP command");
                };
                let command: Value = serde_json::from_str(&command).unwrap();
                assert_eq!(command["id"], expected_id);
                socket
                    .send(Message::Text(
                        json!({"id": expected_id, "result": {}}).to_string(),
                    ))
                    .await
                    .unwrap();
            }
            socket
                .send(Message::Text(
                    json!({
                        "method": "Page.screencastFrame",
                        "params": {
                            "sessionId": 9,
                            "data": base64::engine::general_purpose::STANDARD.encode(b"raw-secret-not-a-jpeg"),
                            "metadata": {"timestamp": 2.5}
                        }
                    })
                    .to_string(),
                ))
                .await
                .unwrap();
            let Message::Text(redaction) = socket.next().await.unwrap().unwrap() else {
                panic!("expected redaction region query");
            };
            let redaction: Value = serde_json::from_str(&redaction).unwrap();
            socket
                .send(Message::Text(
                    json!({
                        "id": redaction["id"],
                        "result": {"result": {"value": {
                            "version": 1,
                            "viewportWidth": 16,
                            "viewportHeight": 16,
                            "regions": []
                        }}}
                    })
                    .to_string(),
                ))
                .await
                .unwrap();
            let Message::Text(ack) = socket.next().await.unwrap().unwrap() else {
                panic!("expected failed-frame ACK");
            };
            let ack: Value = serde_json::from_str(&ack).unwrap();
            assert_eq!(ack["method"], "Page.screencastFrameAck");
            assert_eq!(ack["params"]["sessionId"], 9);
        });

        let http_listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let http_address = http_listener.local_addr().unwrap();
        let http_task = tokio::spawn(async move {
            let (mut stream, _) = http_listener.accept().await.unwrap();
            let mut request = [0_u8; 2048];
            let count = stream.read(&mut request).await.unwrap();
            assert!(String::from_utf8_lossy(&request[..count]).starts_with("GET /json/list "));
            let body = json!([{
                "id": "page-1",
                "type": "page",
                "webSocketDebuggerUrl": format!("ws://{websocket_address}/devtools/page/1")
            }])
            .to_string();
            stream
                .write_all(
                    format!(
                        "HTTP/1.1 200 OK\r\ncontent-type: application/json\r\ncontent-length: {}\r\nconnection: close\r\n\r\n{}",
                        body.len(), body
                    )
                    .as_bytes(),
                )
                .await
                .unwrap();
        });

        let (frames_tx, mut frames_rx) = mpsc::channel(1);
        let (_stop_tx, stop_rx) = watch::channel(false);
        let (ready_tx, ready_rx) = oneshot::channel();
        let dropped = std::sync::atomic::AtomicU64::new(0);
        let capture_failed = std::sync::atomic::AtomicBool::new(true);
        let privacy_scanner_path = passthrough_privacy_scanner().await;
        let cdp_endpoint = format!("http://{http_address}");
        let capture = capture_frames(
            &cdp_endpoint,
            &privacy_scanner_path,
            frames_tx,
            stop_rx,
            ready_tx,
            &dropped,
            &capture_failed,
        );
        let (ready, result) = tokio::join!(ready_rx, capture);

        assert!(
            matches!(&ready, Ok(Ok(()))),
            "capture did not confirm ready: {ready:?}; capture result: {result:?}"
        );
        let error = result.unwrap_err().to_string();
        assert!(error.contains("JPEG decode failed"), "{error}");
        assert!(frames_rx.recv().await.is_none());
        assert!(capture_failed.load(std::sync::atomic::Ordering::Acquire));
        assert_eq!(dropped.load(std::sync::atomic::Ordering::Relaxed), 0);
        http_task.await.unwrap();
        websocket_task.await.unwrap();
    }

    #[tokio::test]
    async fn finalization_failure_remains_fail_closed_across_retries() {
        let registry = SessionRecorderRegistry::default();
        let helper = Arc::new(
            StorageHelperClient::new(
                PathBuf::from("/tmp/browsercloud-missing-storage-helper.sock"),
                Duration::from_secs(1),
                PathBuf::from("/tmp/browsercloud-profile-storage"),
            )
            .unwrap(),
        );
        let (stop, _) = watch::channel(false);
        registry.sessions.lock().await.insert(
            "session-test".to_owned(),
            RegisteredRecording {
                spec: RecordingSpec {
                    session_id: "session-test".to_owned(),
                    cdp_endpoint: "http://127.0.0.1:9222".to_owned(),
                    workspace: StorageWorkspace {
                        tenant_id: "tenant-test".to_owned(),
                        profile_id: "profile-test".to_owned(),
                        session_id: "session-test".to_owned(),
                        core_dir: "/tmp/browsercloud-profile-storage/tenants/tenant-test/profiles/profile-test/workspaces/session-test/core".to_owned(),
                        ephemeral_dir: "/tmp/browsercloud-profile-storage/tenants/tenant-test/profiles/profile-test/workspaces/session-test/ephemeral".to_owned(),
                        profile_write_epoch: 1,
                        restored_checkpoint_id: None,
                        restore_status: helper_client::StorageRestoreStatus::Empty,
                    },
                    storage_helper: helper,
                    privacy_scanner_path: PathBuf::from("/privacy-scanner-not-reached"),
                },
                active: Some(ActiveRecording {
                    stop,
                    task: tokio::spawn(async {
                        anyhow::bail!("Object Storage finalization failed")
                    }),
                }),
                pending_summary: None,
                failure: None,
            },
        );

        assert!(registry
            .set_enabled("session-test", false)
            .await
            .unwrap_err()
            .to_string()
            .contains("Object Storage finalization failed"));
        assert!(registry
            .set_enabled("session-test", false)
            .await
            .unwrap_err()
            .to_string()
            .contains("previous recording finalization failed"));
    }

    #[tokio::test]
    async fn unregister_is_idempotent_for_an_unregistered_session() {
        let registry = SessionRecorderRegistry::default();

        assert!(registry
            .unregister("session-never-started")
            .await
            .unwrap()
            .is_none());
        assert!(registry
            .unregister("session-never-started")
            .await
            .unwrap()
            .is_none());
    }
}
