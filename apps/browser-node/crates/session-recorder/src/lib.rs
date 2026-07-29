//! Session-scoped pixel recording over the Browser Node's loopback CDP endpoint.
//!
//! Frames are captured independently from the interactive Observer connection. The unprivileged
//! Node writes bounded NDJSON segments into the Session ephemeral workspace; only Storage Helper
//! can commit those segments to Object Storage.

use base64::Engine;
use futures_util::{SinkExt, StreamExt};
use helper_client::{StorageHelperClient, StorageWorkspace};
use serde::Deserialize;
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::Arc;
use std::time::{Duration, SystemTime, UNIX_EPOCH};
use tokio::io::AsyncWriteExt;
use tokio::sync::{mpsc, oneshot, watch, Mutex};
use tokio::task::JoinHandle;
use tokio_tungstenite::tungstenite::Message;

const FRAME_QUEUE_CAPACITY: usize = 8;
const SEGMENT_MAX_BYTES: u64 = 4 * 1024 * 1024;
const SEGMENT_MAX_DURATION_MS: u64 = 10_000;
const CDP_TIMEOUT: Duration = Duration::from_secs(5);
const EVIDENCE_MAX_BYTES: usize = 8 * 1024 * 1024;

#[derive(Clone)]
pub struct RecordingSpec {
    pub session_id: String,
    pub cdp_endpoint: String,
    pub workspace: StorageWorkspace,
    pub storage_helper: Arc<StorageHelperClient>,
}

struct RegisteredRecording {
    spec: RecordingSpec,
    active: Option<ActiveRecording>,
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
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct EvidenceSummary {
    pub evidence_id: String,
    pub content_sha256: String,
    pub content_bytes: u64,
    pub object_key: String,
    pub captured_at_ms: u64,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum EvidenceCapture {
    Skipped { sample_percent: u32 },
    Committed(EvidenceSummary),
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
        let evidence_id = format!("evd_{}", uuid::Uuid::new_v4().simple());
        let captured_at_ms = now_millis();
        let content = capture_screenshot(&registered.spec.cdp_endpoint).await?;
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
        Ok(EvidenceCapture::Committed(EvidenceSummary {
            evidence_id,
            content_sha256,
            content_bytes: content.len() as u64,
            object_key: committed.object_key,
            captured_at_ms,
        }))
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

    pub async fn set_enabled(&self, session_id: &str, enabled: bool) -> anyhow::Result<bool> {
        let (spec, active, stale) = {
            let mut sessions = self.sessions.lock().await;
            let registered = sessions
                .get_mut(session_id)
                .ok_or_else(|| anyhow::anyhow!("recording Session is not registered"))?;
            if enabled {
                if registered
                    .active
                    .as_ref()
                    .is_some_and(|active| !active.task.is_finished())
                {
                    return Ok(true);
                }
                let stale = registered.active.take();
                registered.failure = None;
                (Some(registered.spec.clone()), None, stale)
            } else {
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
            let result = active
                .task
                .await
                .map_err(|error| anyhow::anyhow!("recording task join failed: {error}"))?;
            if let Err(error) = result {
                if let Some(registered) = self.sessions.lock().await.get_mut(session_id) {
                    registered.failure = Some(error.to_string());
                }
                return Err(error);
            }
            return Ok(false);
        }
        if let Some(spec) = spec {
            let active = start_recording(spec).await?;
            let mut sessions = self.sessions.lock().await;
            let registered = sessions
                .get_mut(session_id)
                .ok_or_else(|| anyhow::anyhow!("recording Session disappeared during start"))?;
            registered.active = Some(active);
            return Ok(true);
        }
        Ok(false)
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
        let active = {
            let mut sessions = self.sessions.lock().await;
            let Some(registered) = sessions.get_mut(session_id) else {
                return Ok(None);
            };
            if let Some(failure) = registered.failure.as_ref() {
                anyhow::bail!("previous recording finalization failed: {failure}");
            }
            let active = registered.active.take();
            if active.is_none() {
                sessions.remove(session_id);
                return Ok(None);
            }
            active.expect("checked active recording")
        };
        let _ = active.stop.send(true);
        let result = active
            .task
            .await
            .map_err(|error| anyhow::anyhow!("recording task join failed: {error}"))?;
        match result {
            Ok(summary) => {
                self.sessions.lock().await.remove(session_id);
                Ok(Some(summary))
            }
            Err(error) => {
                if let Some(registered) = self.sessions.lock().await.get_mut(session_id) {
                    registered.failure = Some(error.to_string());
                }
                Err(error)
            }
        }
    }
}

async fn start_recording(spec: RecordingSpec) -> anyhow::Result<ActiveRecording> {
    let recording_id = format!("rec_{}", uuid::Uuid::new_v4().simple());
    spec.storage_helper
        .prepare_recording(&spec.workspace, &recording_id)
        .await?;
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
    let mut writer = tokio::spawn(async move {
        write_segments(writer_spec, writer_recording_id, frames_rx, writer_drops).await
    });
    let (capture_stop_tx, capture_stop_rx) = watch::channel(false);
    let capture_endpoint = spec.cdp_endpoint.clone();
    let capture_drops = Arc::clone(&dropped_frames);
    let mut capture = tokio::spawn(async move {
        capture_frames(
            &capture_endpoint,
            frames_tx,
            capture_stop_rx,
            ready,
            capture_drops.as_ref(),
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
    frames: mpsc::Sender<CapturedFrame>,
    mut stop: watch::Receiver<bool>,
    ready: oneshot::Sender<Result<(), String>>,
    dropped_frames: &std::sync::atomic::AtomicU64,
) -> anyhow::Result<()> {
    let websocket_url = target_websocket(cdp_endpoint).await?;
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
                let frame = CapturedFrame {
                    captured_at_ms: now_millis(),
                    session_id,
                    data: params
                        .get("data")
                        .and_then(Value::as_str)
                        .unwrap_or_default()
                        .to_owned(),
                    metadata: params.get("metadata").cloned().unwrap_or_else(|| json!({})),
                };
                if frame.data.is_empty()
                    || frame.data.len() > SEGMENT_MAX_BYTES as usize
                    || frames.try_send(frame).is_err()
                {
                    dropped_frames.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
                }
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
            }
        }
    }
    drop(frames);
    Ok(())
}

async fn write_segments(
    spec: RecordingSpec,
    recording_id: String,
    mut frames: mpsc::Receiver<CapturedFrame>,
    dropped_frames: Arc<std::sync::atomic::AtomicU64>,
) -> anyhow::Result<RecordingSummary> {
    let recording_started_at = now_millis();
    let directory = PathBuf::from(&spec.workspace.ephemeral_dir)
        .join("recordings")
        .join(&recording_id);
    let mut sequence = 0_u64;
    let mut total_frames = 0_u64;
    let mut segment: Option<SegmentWriter> = None;
    while let Some(frame) = frames.recv().await {
        if segment.is_none() {
            segment = Some(SegmentWriter::open(&directory, sequence, frame.captured_at_ms).await?);
        }
        let current = segment.as_mut().expect("segment opened");
        current.write(&frame).await?;
        total_frames = total_frames.saturating_add(1);
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
    let ended_at_ms = now_millis();
    spec.storage_helper
        .complete_recording(
            &spec.workspace,
            &recording_id,
            sequence,
            total_frames,
            recording_started_at,
            ended_at_ms,
        )
        .await?;
    Ok(RecordingSummary {
        recording_id,
        segment_count: sequence,
        frame_count: total_frames,
        dropped_frames: dropped_frames.load(std::sync::atomic::Ordering::Relaxed),
    })
}

struct SegmentWriter {
    sequence: u64,
    file: tokio::fs::File,
    hasher: Sha256,
    bytes: u64,
    frames: u64,
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
            "data": frame.data
        }))?;
        line.push(b'\n');
        self.file.write_all(&line).await?;
        self.hasher.update(&line);
        self.bytes = self.bytes.saturating_add(line.len() as u64);
        self.frames = self.frames.saturating_add(1);
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
    spec.storage_helper
        .commit_recording_segment(
            &spec.workspace,
            recording_id,
            segment.sequence,
            &content_sha256,
            segment.bytes,
            segment.frames,
            segment.started_at_ms,
            segment.ended_at_ms,
        )
        .await?;
    Ok(())
}

async fn capture_screenshot(cdp_endpoint: &str) -> anyhow::Result<Vec<u8>> {
    let websocket_url = target_websocket(cdp_endpoint).await?;
    require_loopback_websocket(&websocket_url)?;
    let (mut socket, _) = tokio::time::timeout(
        CDP_TIMEOUT,
        tokio_tungstenite::connect_async(&websocket_url),
    )
    .await
    .map_err(|_| anyhow::anyhow!("CDP evidence connection timed out"))??;
    send_command(&mut socket, 1, "Page.enable", json!({})).await?;
    let response = send_command_value(
        &mut socket,
        2,
        "Page.captureScreenshot",
        json!({
            "format": "jpeg",
            "quality": 70,
            "fromSurface": true,
            "captureBeyondViewport": false
        }),
    )
    .await?;
    let encoded = response
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
    Ok(content)
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
    #[serde(rename = "type")]
    target_type: String,
    #[serde(rename = "webSocketDebuggerUrl")]
    websocket_url: Option<String>,
}

async fn target_websocket(endpoint: &str) -> anyhow::Result<String> {
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
        .find(|target| target.target_type == "page")
        .and_then(|target| target.websocket_url)
        .ok_or_else(|| anyhow::anyhow!("CDP has no recordable Page target"))
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
    while let Some(message) = tokio::time::timeout(CDP_TIMEOUT, socket.next())
        .await
        .map_err(|_| anyhow::anyhow!("CDP {method} timed out"))?
    {
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
mod tests {
    use super::*;
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    use tokio::net::TcpListener;
    use tokio_tungstenite::accept_async;

    #[test]
    fn rejects_non_loopback_cdp_endpoints() {
        assert!(require_loopback_http("http://127.0.0.1:9222").is_ok());
        assert!(require_loopback_http("http://example.com:9222").is_err());
        assert!(require_loopback_websocket("ws://localhost:9222/devtools/page/1").is_ok());
        assert!(require_loopback_websocket("wss://localhost/devtools/page/1").is_err());
    }

    #[tokio::test]
    async fn captures_real_bounded_jpeg_screenshot_over_cdp() {
        let websocket_listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let websocket_address = websocket_listener.local_addr().unwrap();
        let websocket_task = tokio::spawn(async move {
            let (stream, _) = websocket_listener.accept().await.unwrap();
            let mut socket = accept_async(stream).await.unwrap();
            let Message::Text(enable) = socket.next().await.unwrap().unwrap() else {
                panic!("expected Page.enable");
            };
            let enable: Value = serde_json::from_str(&enable).unwrap();
            assert_eq!(enable["method"], "Page.enable");
            socket
                .send(Message::Text(json!({"id": 1, "result": {}}).to_string()))
                .await
                .unwrap();

            let Message::Text(capture) = socket.next().await.unwrap().unwrap() else {
                panic!("expected Page.captureScreenshot");
            };
            let capture: Value = serde_json::from_str(&capture).unwrap();
            assert_eq!(capture["method"], "Page.captureScreenshot");
            assert_eq!(capture["params"]["format"], "jpeg");
            assert_eq!(capture["params"]["quality"], 70);
            socket
                .send(Message::Text(
                    json!({"id": 2, "result": {"data": "/9j/2Q=="}}).to_string(),
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

        let screenshot = capture_screenshot(&format!("http://{http_address}"))
            .await
            .unwrap();
        assert_eq!(screenshot, vec![0xff, 0xd8, 0xff, 0xd9]);
        websocket_task.await.unwrap();
        http_task.await.unwrap();
    }

    #[tokio::test]
    async fn captures_and_acknowledges_a_real_cdp_screencast_frame() {
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
                            "data": "/9j/test-frame",
                            "metadata": {"timestamp": 1.25}
                        }
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
        let capture = tokio::spawn(async move {
            capture_frames(
                &format!("http://{http_address}"),
                frames_tx,
                stop_rx,
                ready_tx,
                capture_dropped.as_ref(),
            )
            .await
        });
        ready_rx.await.unwrap().unwrap();
        let frame = tokio::time::timeout(Duration::from_secs(1), frames_rx.recv())
            .await
            .unwrap()
            .unwrap();
        assert_eq!(frame.session_id, 7);
        assert_eq!(frame.data, "/9j/test-frame");
        stop_tx.send(true).unwrap();
        capture.await.unwrap().unwrap();
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
                },
                active: Some(ActiveRecording {
                    stop,
                    task: tokio::spawn(async {
                        anyhow::bail!("Object Storage finalization failed")
                    }),
                }),
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
}
