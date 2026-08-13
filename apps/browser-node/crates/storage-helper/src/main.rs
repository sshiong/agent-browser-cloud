use anyhow::Context;
use bytes::Bytes;
use helper_contracts::{
    read_frame, write_frame, StorageCheckpoint, StorageCommand, StorageEvidence,
    StorageEvidenceAccess, StorageProfileExportAccess, StorageRecording, StorageRequest,
    StorageResponse, StorageRestoreStatus, StorageWarmTierSync, StorageWorkspace, SCHEMA_VERSION,
};
use nix::sys::socket::getsockopt;
use nix::unistd::Uid;
use sha2::{Digest, Sha256};
use std::os::unix::fs::{FileTypeExt, PermissionsExt};
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::Duration;
use storage_helper::object_archive::{
    EvidenceDownloadRequest, ObjectArchive, ProfileExportDownloadRequest, S3ArchiveConfig,
};
use storage_helper::{LocalProfileStore, ProfileRestoreStatus, ProfileWorkspace};
use tokio::io::{AsyncReadExt, AsyncSeekExt};
use tokio::net::{UnixListener, UnixStream};
use tokio::sync::Mutex;
use tracing::{error, info, warn};

struct StorageService {
    store: LocalProfileStore,
    archive: Option<ObjectArchive>,
    import_staging_root: PathBuf,
    profile_locks: Vec<Mutex<()>>,
}

impl StorageService {
    fn new(
        store: LocalProfileStore,
        archive: Option<ObjectArchive>,
        import_staging_root: PathBuf,
    ) -> Self {
        Self {
            store,
            archive,
            import_staging_root,
            profile_locks: (0..64).map(|_| Mutex::new(())).collect(),
        }
    }

    async fn execute(
        &self,
        command: &StorageCommand,
    ) -> anyhow::Result<(
        Option<StorageWorkspace>,
        Option<StorageCheckpoint>,
        Option<StorageWarmTierSync>,
        Option<StorageRecording>,
        Option<StorageEvidence>,
        Option<StorageEvidenceAccess>,
        Option<StorageProfileExportAccess>,
    )> {
        let Some((tenant_id, profile_id)) = command_profile(command) else {
            return Ok((None, None, None, None, None, None, None));
        };
        let stripe = profile_lock_stripe(tenant_id, profile_id, self.profile_locks.len());
        let _guard = self.profile_locks[stripe].lock().await;
        execute_storage_operation(
            &self.store,
            self.archive.as_ref(),
            &self.import_staging_root,
            command,
        )
        .await
    }
}

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| "storage_helper=info".into()),
        )
        .init();

    let socket_path = required_absolute_path("STORAGE_HELPER_SOCKET")?;
    let storage_root = required_absolute_path("PROFILE_STORAGE_ROOT")?;
    let import_staging_root = storage_root.join(".imports");
    tokio::fs::create_dir_all(&import_staging_root).await?;
    tokio::fs::set_permissions(&import_staging_root, std::fs::Permissions::from_mode(0o770))
        .await?;
    let object_archive = object_archive_from_environment()?;
    prepare_socket_path(&socket_path).await?;
    let allowed_uid = configured_node_agent_uid()?;
    let service = Arc::new(StorageService::new(
        LocalProfileStore::open(storage_root).await?,
        object_archive,
        import_staging_root,
    ));
    let listener = UnixListener::bind(&socket_path)
        .with_context(|| format!("failed to bind {}", socket_path.display()))?;
    tokio::fs::set_permissions(&socket_path, std::fs::Permissions::from_mode(0o660)).await?;
    info!(
        socket = %socket_path.display(),
        allowed_uid,
        "storage helper ready"
    );

    loop {
        let (stream, _) = listener.accept().await?;
        let service = Arc::clone(&service);
        tokio::spawn(async move {
            if let Err(error) = serve_connection(stream, allowed_uid, service).await {
                warn!(error = %error, "storage helper rejected connection");
            }
        });
    }
}

async fn serve_connection(
    mut stream: UnixStream,
    allowed_uid: u32,
    service: Arc<StorageService>,
) -> anyhow::Result<()> {
    let peer_uid = platform_peer_uid(&stream).context("cannot inspect helper peer")?;
    anyhow::ensure!(peer_uid == allowed_uid, "helper peer UID is not authorized");

    let request: StorageRequest = read_frame(&mut stream).await?;
    let response = if request.schema_version != SCHEMA_VERSION {
        rejected(
            request.request_id,
            "SCHEMA_MISMATCH",
            "unsupported helper IPC schema version",
        )
    } else {
        match service.execute(&request.command).await {
            Ok((
                workspace,
                checkpoint,
                warm_tier_sync,
                recording,
                evidence,
                evidence_access,
                profile_export_access,
            )) => StorageResponse {
                schema_version: SCHEMA_VERSION,
                request_id: request.request_id,
                ok: true,
                workspace,
                checkpoint,
                warm_tier_sync,
                recording,
                evidence,
                evidence_access,
                profile_export_access,
                error_code: None,
                error_message: None,
            },
            Err(error) => {
                error!(request_id = %request.request_id, error = %error, "storage operation failed");
                rejected(
                    request.request_id,
                    "STORAGE_OPERATION_FAILED",
                    "storage helper operation failed",
                )
            }
        }
    };
    write_frame(&mut stream, &response).await
}

async fn execute_storage_operation(
    store: &LocalProfileStore,
    archive: Option<&ObjectArchive>,
    import_staging_root: &Path,
    command: &StorageCommand,
) -> anyhow::Result<(
    Option<StorageWorkspace>,
    Option<StorageCheckpoint>,
    Option<StorageWarmTierSync>,
    Option<StorageRecording>,
    Option<StorageEvidence>,
    Option<StorageEvidenceAccess>,
    Option<StorageProfileExportAccess>,
)> {
    match command {
        StorageCommand::Ping => Ok((None, None, None, None, None, None, None)),
        StorageCommand::Acquire {
            tenant_id,
            profile_id,
            session_id,
            checkpoint_id,
        } => {
            if let Some(checkpoint_id) = checkpoint_id.as_deref() {
                let local_checkpoint_ready = store
                    .activate_local_checkpoint(tenant_id, profile_id, checkpoint_id)
                    .await?;
                if !local_checkpoint_ready {
                    let archive = archive.ok_or_else(|| {
                        anyhow::anyhow!(
                            "checkpoint is unavailable locally and object archive is not configured"
                        )
                    })?;
                    archive
                        .restore_checkpoint(store, tenant_id, profile_id, checkpoint_id)
                        .await?;
                }
            }
            let workspace = store
                .acquire_workspace(tenant_id, profile_id, session_id)
                .await?;
            Ok((
                Some(workspace_response(workspace)),
                None,
                None,
                None,
                None,
                None,
                None,
            ))
        }
        StorageCommand::Checkpoint {
            tenant_id,
            profile_id,
            session_id,
            runtime_build_id,
        } => {
            let workspace = store
                .resume_workspace(tenant_id, profile_id, session_id)
                .await?;
            let checkpoint = store.checkpoint(&workspace, runtime_build_id).await?;
            if let Some(archive) = archive {
                archive.commit_checkpoint(store, &checkpoint).await?;
            }
            Ok((
                None,
                Some(StorageCheckpoint {
                    checkpoint_id: checkpoint.checkpoint_id,
                    checkpoint_epoch: checkpoint.checkpoint_epoch,
                    profile_write_epoch: checkpoint.profile_write_epoch,
                    core_size_bytes: checkpoint.core_size_bytes,
                    checkpoint_file_count: checkpoint.files.len() as u64,
                }),
                None,
                None,
                None,
                None,
                None,
            ))
        }
        StorageCommand::SyncWarmTier {
            tenant_id,
            profile_id,
            session_id,
        } => {
            let workspace = store
                .resume_workspace(tenant_id, profile_id, session_id)
                .await?;
            let sync = store.sync_warm_tier(&workspace).await?;
            Ok((
                None,
                None,
                Some(StorageWarmTierSync {
                    profile_id: sync.profile_id,
                    profile_write_epoch: sync.profile_write_epoch,
                    journal_sequence: sync.journal_sequence,
                    transaction_barrier: sync.transaction_barrier,
                    changed_file_count: sync.changed_file_count,
                    deleted_file_count: sync.deleted_file_count,
                    reused_chunk_count: sync.reused_chunk_count,
                    uploaded_bytes: sync.uploaded_bytes,
                    deferred_group_count: sync.deferred_groups.len() as u64,
                    manifest_sha256: sync.content_hash,
                    committed_at_ms: sync.committed_at_ms,
                }),
                None,
                None,
                None,
                None,
            ))
        }
        StorageCommand::ImportCheckpoint {
            tenant_id,
            profile_id,
            import_id,
            checkpoint_id,
            runtime_build_id,
            archive_sha256,
            archive_size_bytes,
        } => {
            validate_import_identifier("import_id", import_id, "pim_")?;
            validate_import_identifier("checkpoint_id", checkpoint_id, "chk_")?;
            anyhow::ensure!(
                archive_sha256.len() == 64
                    && archive_sha256
                        .chars()
                        .all(|character| character.is_ascii_hexdigit()),
                "Profile import SHA-256 is invalid"
            );
            anyhow::ensure!(
                *archive_size_bytes > 0 && *archive_size_bytes <= 256 * 1024 * 1024,
                "Profile import size is invalid"
            );
            let object_archive =
                archive.ok_or_else(|| anyhow::anyhow!("Object Storage is not configured"))?;
            let path = import_staging_root.join(format!("{import_id}.tar.zst"));
            let metadata = tokio::fs::symlink_metadata(&path).await?;
            anyhow::ensure!(
                metadata.is_file()
                    && !metadata.file_type().is_symlink()
                    && metadata.len() == *archive_size_bytes,
                "Profile import staging file is invalid"
            );
            let canonical_root = tokio::fs::canonicalize(import_staging_root).await?;
            let canonical_path = tokio::fs::canonicalize(&path).await?;
            anyhow::ensure!(
                canonical_path.parent() == Some(canonical_root.as_path()),
                "Profile import escaped its staging root"
            );
            let mut file = tokio::fs::OpenOptions::new()
                .read(true)
                .custom_flags(nix::libc::O_NOFOLLOW)
                .open(&canonical_path)
                .await?;
            let opened_metadata = file.metadata().await?;
            anyhow::ensure!(
                opened_metadata.is_file() && opened_metadata.len() == *archive_size_bytes,
                "Profile import changed before secure open"
            );
            let mut digest = Sha256::new();
            let mut observed_size = 0_u64;
            let mut buffer = [0_u8; 256 * 1024];
            loop {
                let read = file.read(&mut buffer).await?;
                if read == 0 {
                    break;
                }
                observed_size = observed_size
                    .checked_add(read as u64)
                    .ok_or_else(|| anyhow::anyhow!("Profile import size overflow"))?;
                anyhow::ensure!(
                    observed_size <= *archive_size_bytes,
                    "Profile import exceeds declared size"
                );
                digest.update(&buffer[..read]);
            }
            anyhow::ensure!(
                observed_size == *archive_size_bytes
                    && format!("{:x}", digest.finalize()) == archive_sha256.to_ascii_lowercase(),
                "Profile import integrity verification failed"
            );
            file.seek(std::io::SeekFrom::Start(0)).await?;
            let manifest = store
                .import_checkpoint_archive_file(
                    tenant_id,
                    profile_id,
                    checkpoint_id,
                    runtime_build_id,
                    *archive_size_bytes,
                    file.into_std().await,
                )
                .await?;
            object_archive.commit_checkpoint(store, &manifest).await?;
            tokio::fs::remove_file(&canonical_path).await?;
            Ok((
                None,
                Some(StorageCheckpoint {
                    checkpoint_id: manifest.checkpoint_id,
                    checkpoint_epoch: manifest.checkpoint_epoch,
                    profile_write_epoch: manifest.profile_write_epoch,
                    core_size_bytes: manifest.core_size_bytes,
                    checkpoint_file_count: manifest.files.len() as u64,
                }),
                None,
                None,
                None,
                None,
                None,
            ))
        }
        StorageCommand::PrepareRecording {
            tenant_id,
            profile_id,
            session_id,
            recording_id,
        } => {
            validate_recording_identifier("recording_id", recording_id)?;
            anyhow::ensure!(
                archive.is_some(),
                "recording requires configured Object Storage"
            );
            let workspace = store
                .resume_workspace(tenant_id, profile_id, session_id)
                .await?;
            verified_recording_directory(&workspace.ephemeral_dir, recording_id, true).await?;
            Ok((
                None,
                None,
                None,
                Some(StorageRecording {
                    recording_id: recording_id.clone(),
                    segment_sequence: None,
                    object_key: None,
                    content_bytes: 0,
                    frame_count: 0,
                    redacted_frame_count: 0,
                    redacted_region_count: 0,
                    redaction_policy_version: 1,
                    completed: false,
                }),
                None,
                None,
                None,
            ))
        }
        StorageCommand::CommitRecordingSegment {
            tenant_id,
            profile_id,
            session_id,
            recording_id,
            segment_sequence,
            content_sha256,
            content_bytes,
            frame_count,
            redacted_frame_count,
            redacted_region_count,
            redaction_policy_version,
            started_at_ms,
            ended_at_ms,
        } => {
            validate_recording_identifier("recording_id", recording_id)?;
            anyhow::ensure!(
                content_sha256.len() == 64
                    && content_sha256
                        .chars()
                        .all(|character| character.is_ascii_hexdigit()),
                "recording segment SHA-256 is invalid"
            );
            anyhow::ensure!(
                *content_bytes > 0 && *content_bytes <= 16 * 1024 * 1024,
                "recording segment size is invalid"
            );
            anyhow::ensure!(
                *frame_count > 0 && *ended_at_ms >= *started_at_ms,
                "recording segment metadata is invalid"
            );
            anyhow::ensure!(
                *redaction_policy_version == 1
                    && *redacted_frame_count <= *frame_count
                    && (*redacted_frame_count == 0 || *redacted_region_count > 0),
                "recording segment redaction metadata is invalid"
            );
            let archive =
                archive.ok_or_else(|| anyhow::anyhow!("Object Storage is not configured"))?;
            let workspace = store
                .resume_workspace(tenant_id, profile_id, session_id)
                .await?;
            let directory =
                verified_recording_directory(&workspace.ephemeral_dir, recording_id, false).await?;
            let path = directory.join(format!("segment-{segment_sequence:020}.ndjson"));
            let metadata = tokio::fs::symlink_metadata(&path).await?;
            anyhow::ensure!(
                metadata.is_file() && !metadata.file_type().is_symlink(),
                "recording segment is not a regular file"
            );
            anyhow::ensure!(
                metadata.len() == *content_bytes,
                "recording segment size does not match command"
            );
            let canonical_path = tokio::fs::canonicalize(&path).await?;
            anyhow::ensure!(
                canonical_path.parent() == Some(directory.as_path()),
                "recording segment escaped its isolated directory"
            );
            let mut file = tokio::fs::OpenOptions::new()
                .read(true)
                .custom_flags(nix::libc::O_NOFOLLOW)
                .open(&canonical_path)
                .await?;
            let opened_metadata = file.metadata().await?;
            anyhow::ensure!(
                opened_metadata.is_file() && opened_metadata.len() == *content_bytes,
                "recording segment changed before secure open"
            );
            let mut content = Vec::with_capacity(*content_bytes as usize);
            file.read_to_end(&mut content).await?;
            anyhow::ensure!(
                content.len() as u64 == *content_bytes,
                "recording segment changed while being read"
            );
            verify_recording_segment_redaction(
                &content,
                *frame_count,
                *redacted_frame_count,
                *redacted_region_count,
                *redaction_policy_version,
            )?;
            let object_key = archive
                .commit_recording_segment(
                    tenant_id,
                    profile_id,
                    session_id,
                    recording_id,
                    *segment_sequence,
                    Bytes::from(content),
                    content_sha256,
                    *frame_count,
                    *redacted_frame_count,
                    *redacted_region_count,
                    *redaction_policy_version,
                    *started_at_ms,
                    *ended_at_ms,
                )
                .await?;
            tokio::fs::remove_file(&canonical_path).await?;
            Ok((
                None,
                None,
                None,
                Some(StorageRecording {
                    recording_id: recording_id.clone(),
                    segment_sequence: Some(*segment_sequence),
                    object_key: Some(object_key),
                    content_bytes: *content_bytes,
                    frame_count: *frame_count,
                    redacted_frame_count: *redacted_frame_count,
                    redacted_region_count: *redacted_region_count,
                    redaction_policy_version: *redaction_policy_version,
                    completed: false,
                }),
                None,
                None,
                None,
            ))
        }
        StorageCommand::CompleteRecording {
            tenant_id,
            profile_id,
            session_id,
            recording_id,
            segment_count,
            frame_count,
            redacted_frame_count,
            redacted_region_count,
            redaction_policy_version,
            started_at_ms,
            ended_at_ms,
        } => {
            validate_recording_identifier("recording_id", recording_id)?;
            anyhow::ensure!(
                *ended_at_ms >= *started_at_ms,
                "recording completion timestamps are invalid"
            );
            anyhow::ensure!(
                *redaction_policy_version == 1
                    && *redacted_frame_count <= *frame_count
                    && (*redacted_frame_count == 0 || *redacted_region_count > 0),
                "recording completion redaction metadata is invalid"
            );
            let archive =
                archive.ok_or_else(|| anyhow::anyhow!("Object Storage is not configured"))?;
            let object_key = archive
                .complete_recording(
                    tenant_id,
                    profile_id,
                    session_id,
                    recording_id,
                    *segment_count,
                    *frame_count,
                    *redacted_frame_count,
                    *redacted_region_count,
                    *redaction_policy_version,
                    *started_at_ms,
                    *ended_at_ms,
                )
                .await?;
            Ok((
                None,
                None,
                None,
                Some(StorageRecording {
                    recording_id: recording_id.clone(),
                    segment_sequence: None,
                    object_key: Some(object_key),
                    content_bytes: 0,
                    frame_count: *frame_count,
                    redacted_frame_count: *redacted_frame_count,
                    redacted_region_count: *redacted_region_count,
                    redaction_policy_version: *redaction_policy_version,
                    completed: true,
                }),
                None,
                None,
                None,
            ))
        }
        StorageCommand::CommitEvidence {
            tenant_id,
            profile_id,
            session_id,
            evidence_id,
            evidence_kind,
            content_sha256,
            content_bytes,
            captured_at_ms,
        } => {
            validate_recording_identifier("evidence_id", evidence_id)?;
            anyhow::ensure!(
                matches!(
                    evidence_kind.as_str(),
                    "AGENT_ACTION_SUCCESS"
                        | "AGENT_ACTION_FAILURE"
                        | "AGENT_NAVIGATION_SUCCESS"
                        | "AGENT_NAVIGATION_FAILURE"
                        | "OBSERVER_MANUAL"
                ),
                "evidence kind is invalid"
            );
            anyhow::ensure!(
                content_sha256.len() == 64
                    && content_sha256
                        .chars()
                        .all(|character| character.is_ascii_hexdigit()),
                "evidence SHA-256 is invalid"
            );
            anyhow::ensure!(
                *content_bytes > 0 && *content_bytes <= 8 * 1024 * 1024,
                "evidence size is invalid"
            );
            anyhow::ensure!(*captured_at_ms > 0, "evidence timestamp is invalid");
            let archive =
                archive.ok_or_else(|| anyhow::anyhow!("Object Storage is not configured"))?;
            let workspace = store
                .resume_workspace(tenant_id, profile_id, session_id)
                .await?;
            let directory =
                verified_evidence_directory(&workspace.ephemeral_dir, evidence_id, false).await?;
            let path = directory.join("screenshot.jpeg");
            let metadata = tokio::fs::symlink_metadata(&path).await?;
            anyhow::ensure!(
                metadata.is_file() && !metadata.file_type().is_symlink(),
                "evidence screenshot is not a regular file"
            );
            anyhow::ensure!(
                metadata.len() == *content_bytes,
                "evidence size does not match command"
            );
            let canonical_path = tokio::fs::canonicalize(&path).await?;
            anyhow::ensure!(
                canonical_path.parent() == Some(directory.as_path()),
                "evidence screenshot escaped its isolated directory"
            );
            let mut file = tokio::fs::OpenOptions::new()
                .read(true)
                .custom_flags(nix::libc::O_NOFOLLOW)
                .open(&canonical_path)
                .await?;
            let opened_metadata = file.metadata().await?;
            anyhow::ensure!(
                opened_metadata.is_file() && opened_metadata.len() == *content_bytes,
                "evidence screenshot changed before secure open"
            );
            let mut content = Vec::with_capacity(*content_bytes as usize);
            file.read_to_end(&mut content).await?;
            anyhow::ensure!(
                content.len() as u64 == *content_bytes,
                "evidence screenshot changed while being read"
            );
            let object_key = archive
                .commit_evidence(
                    tenant_id,
                    profile_id,
                    session_id,
                    evidence_id,
                    evidence_kind,
                    Bytes::from(content),
                    content_sha256,
                    *captured_at_ms,
                )
                .await?;
            tokio::fs::remove_file(&canonical_path).await?;
            Ok((
                None,
                None,
                None,
                None,
                Some(StorageEvidence {
                    evidence_id: evidence_id.clone(),
                    object_key,
                    content_sha256: content_sha256.clone(),
                    content_bytes: *content_bytes,
                    captured_at_ms: *captured_at_ms,
                    committed: true,
                }),
                None,
                None,
            ))
        }
        StorageCommand::SignEvidenceDownload {
            tenant_id,
            profile_id,
            session_id,
            evidence_id,
            content_sha256,
            content_bytes,
            expires_in_seconds,
        } => {
            validate_recording_identifier("evidence_id", evidence_id)?;
            anyhow::ensure!(
                content_sha256.len() == 64
                    && content_sha256
                        .chars()
                        .all(|character| character.is_ascii_hexdigit()),
                "evidence SHA-256 is invalid"
            );
            anyhow::ensure!(
                *content_bytes > 0 && *content_bytes <= 8 * 1024 * 1024,
                "evidence size is invalid"
            );
            anyhow::ensure!(
                (30..=120).contains(expires_in_seconds),
                "evidence access duration is invalid"
            );
            let archive =
                archive.ok_or_else(|| anyhow::anyhow!("Object Storage is not configured"))?;
            let (download_url, expires_at_ms) = archive
                .sign_evidence_download(EvidenceDownloadRequest {
                    tenant_id,
                    profile_id,
                    session_id,
                    evidence_id,
                    content_sha256,
                    content_bytes: *content_bytes,
                    expires_in: Duration::from_secs((*expires_in_seconds).into()),
                })
                .await?;
            Ok((
                None,
                None,
                None,
                None,
                None,
                Some(StorageEvidenceAccess {
                    evidence_id: evidence_id.clone(),
                    download_url,
                    expires_at_ms,
                }),
                None,
            ))
        }
        StorageCommand::SignProfileExportDownload {
            tenant_id,
            profile_id,
            checkpoint_id,
            expires_in_seconds,
        } => {
            validate_import_identifier("checkpoint_id", checkpoint_id, "chk_")?;
            anyhow::ensure!(
                (30..=120).contains(expires_in_seconds),
                "Profile export access duration is invalid"
            );
            let archive =
                archive.ok_or_else(|| anyhow::anyhow!("Object Storage is not configured"))?;
            let signed = archive
                .sign_profile_export_download(ProfileExportDownloadRequest {
                    tenant_id,
                    profile_id,
                    checkpoint_id,
                    expires_in: Duration::from_secs((*expires_in_seconds).into()),
                })
                .await?;
            Ok((
                None,
                None,
                None,
                None,
                None,
                None,
                Some(StorageProfileExportAccess {
                    profile_id: profile_id.clone(),
                    checkpoint_id: checkpoint_id.clone(),
                    archive_sha256: signed.archive_sha256,
                    archive_size_bytes: signed.archive_size_bytes,
                    download_url: signed.download_url,
                    expires_at_ms: signed.expires_at_ms,
                }),
            ))
        }
        StorageCommand::Release {
            tenant_id,
            profile_id,
            session_id,
        } => {
            store
                .release_writer_by_identity(tenant_id, profile_id, session_id)
                .await?;
            Ok((None, None, None, None, None, None, None))
        }
    }
}

fn object_archive_from_environment() -> anyhow::Result<Option<ObjectArchive>> {
    let enabled = std::env::var("OBJECT_STORAGE_ENABLED")
        .map(|value| value.eq_ignore_ascii_case("true"))
        .unwrap_or(false);
    if !enabled {
        return Ok(None);
    }
    let environment = std::env::var("APP_ENVIRONMENT").unwrap_or_else(|_| "local".to_owned());
    let endpoint = required_environment("OBJECT_STORAGE_ENDPOINT")?;
    let allow_http = endpoint.starts_with("http://");
    anyhow::ensure!(
        !environment.eq_ignore_ascii_case("production") || !allow_http,
        "production Object Storage requires HTTPS"
    );
    let connect_timeout = duration_from_environment("OBJECT_STORAGE_CONNECT_TIMEOUT_MS", 1_000)?;
    let operation_timeout =
        duration_from_environment("OBJECT_STORAGE_OPERATION_TIMEOUT_MS", 3_000)?;
    Ok(Some(ObjectArchive::s3(S3ArchiveConfig {
        bucket: required_environment("OBJECT_STORAGE_BUCKET")?,
        region: std::env::var("OBJECT_STORAGE_REGION").unwrap_or_else(|_| "us-east-1".to_owned()),
        endpoint,
        access_key_id: required_environment("OBJECT_STORAGE_ACCESS_KEY_ID")?,
        secret_access_key: required_environment("OBJECT_STORAGE_SECRET_ACCESS_KEY")?,
        prefix: std::env::var("OBJECT_STORAGE_PREFIX").unwrap_or_default(),
        connect_timeout,
        operation_timeout,
        allow_http,
    })?))
}

fn duration_from_environment(name: &str, default_millis: u64) -> anyhow::Result<Duration> {
    let millis = std::env::var(name)
        .unwrap_or_else(|_| default_millis.to_string())
        .parse::<u64>()
        .with_context(|| format!("{name} must be an integer"))?;
    anyhow::ensure!((100..=60_000).contains(&millis), "{name} is out of range");
    Ok(Duration::from_millis(millis))
}

fn command_profile(command: &StorageCommand) -> Option<(&str, &str)> {
    match command {
        StorageCommand::Ping => None,
        StorageCommand::Acquire {
            tenant_id,
            profile_id,
            ..
        }
        | StorageCommand::Checkpoint {
            tenant_id,
            profile_id,
            ..
        }
        | StorageCommand::SyncWarmTier {
            tenant_id,
            profile_id,
            ..
        }
        | StorageCommand::ImportCheckpoint {
            tenant_id,
            profile_id,
            ..
        }
        | StorageCommand::PrepareRecording {
            tenant_id,
            profile_id,
            ..
        }
        | StorageCommand::CommitRecordingSegment {
            tenant_id,
            profile_id,
            ..
        }
        | StorageCommand::CompleteRecording {
            tenant_id,
            profile_id,
            ..
        }
        | StorageCommand::CommitEvidence {
            tenant_id,
            profile_id,
            ..
        }
        | StorageCommand::SignEvidenceDownload {
            tenant_id,
            profile_id,
            ..
        }
        | StorageCommand::SignProfileExportDownload {
            tenant_id,
            profile_id,
            ..
        }
        | StorageCommand::Release {
            tenant_id,
            profile_id,
            ..
        } => Some((tenant_id, profile_id)),
    }
}

fn validate_import_identifier(name: &str, value: &str, prefix: &str) -> anyhow::Result<()> {
    anyhow::ensure!(
        value.starts_with(prefix)
            && value.len() <= 128
            && value.chars().all(
                |character| character.is_ascii_alphanumeric() || matches!(character, '_' | '-')
            ),
        "{name} is invalid"
    );
    Ok(())
}

async fn verified_recording_directory(
    ephemeral_dir: &Path,
    recording_id: &str,
    create: bool,
) -> anyhow::Result<PathBuf> {
    let canonical_ephemeral = tokio::fs::canonicalize(ephemeral_dir).await?;
    let recordings = ephemeral_dir.join("recordings");
    if create {
        match tokio::fs::create_dir(&recordings).await {
            Ok(()) => {}
            Err(error) if error.kind() == std::io::ErrorKind::AlreadyExists => {}
            Err(error) => return Err(error.into()),
        }
    }
    let recordings_metadata = tokio::fs::symlink_metadata(&recordings).await?;
    anyhow::ensure!(
        recordings_metadata.is_dir() && !recordings_metadata.file_type().is_symlink(),
        "recordings root is not a regular directory"
    );
    let canonical_recordings = tokio::fs::canonicalize(&recordings).await?;
    anyhow::ensure!(
        canonical_recordings.parent() == Some(canonical_ephemeral.as_path()),
        "recordings root escaped the Session ephemeral directory"
    );
    let directory = recordings.join(recording_id);
    if create {
        match tokio::fs::create_dir(&directory).await {
            Ok(()) => {}
            Err(error) if error.kind() == std::io::ErrorKind::AlreadyExists => {}
            Err(error) => return Err(error.into()),
        }
    }
    let metadata = tokio::fs::symlink_metadata(&directory).await?;
    anyhow::ensure!(
        metadata.is_dir() && !metadata.file_type().is_symlink(),
        "recording path is not a regular directory"
    );
    let canonical_directory = tokio::fs::canonicalize(&directory).await?;
    anyhow::ensure!(
        canonical_directory.parent() == Some(canonical_recordings.as_path()),
        "recording directory escaped the recordings root"
    );
    Ok(canonical_directory)
}

async fn verified_evidence_directory(
    ephemeral_dir: &Path,
    evidence_id: &str,
    create: bool,
) -> anyhow::Result<PathBuf> {
    let canonical_ephemeral = tokio::fs::canonicalize(ephemeral_dir).await?;
    let evidence_root = ephemeral_dir.join("evidence");
    if create {
        match tokio::fs::create_dir(&evidence_root).await {
            Ok(()) => {}
            Err(error) if error.kind() == std::io::ErrorKind::AlreadyExists => {}
            Err(error) => return Err(error.into()),
        }
    }
    let root_metadata = tokio::fs::symlink_metadata(&evidence_root).await?;
    anyhow::ensure!(
        root_metadata.is_dir() && !root_metadata.file_type().is_symlink(),
        "evidence root is not a regular directory"
    );
    let canonical_root = tokio::fs::canonicalize(&evidence_root).await?;
    anyhow::ensure!(
        canonical_root.parent() == Some(canonical_ephemeral.as_path()),
        "evidence root escaped the Session ephemeral directory"
    );
    let directory = evidence_root.join(evidence_id);
    if create {
        match tokio::fs::create_dir(&directory).await {
            Ok(()) => {}
            Err(error) if error.kind() == std::io::ErrorKind::AlreadyExists => {}
            Err(error) => return Err(error.into()),
        }
    }
    let metadata = tokio::fs::symlink_metadata(&directory).await?;
    anyhow::ensure!(
        metadata.is_dir() && !metadata.file_type().is_symlink(),
        "evidence path is not a regular directory"
    );
    let canonical_directory = tokio::fs::canonicalize(&directory).await?;
    anyhow::ensure!(
        canonical_directory.parent() == Some(canonical_root.as_path()),
        "evidence directory escaped the evidence root"
    );
    Ok(canonical_directory)
}

fn validate_recording_identifier(name: &str, value: &str) -> anyhow::Result<()> {
    anyhow::ensure!(
        !value.is_empty()
            && value.len() <= 128
            && value.chars().all(
                |character| character.is_ascii_alphanumeric() || matches!(character, '_' | '-')
            ),
        "{name} is invalid"
    );
    Ok(())
}

fn verify_recording_segment_redaction(
    content: &[u8],
    expected_frames: u64,
    expected_redacted_frames: u64,
    expected_redacted_regions: u64,
    expected_policy_version: u32,
) -> anyhow::Result<()> {
    let mut frames = 0_u64;
    let mut redacted_frames = 0_u64;
    let mut redacted_regions = 0_u64;
    for line in content.split(|byte| *byte == b'\n') {
        if line.is_empty() {
            continue;
        }
        let frame: serde_json::Value =
            serde_json::from_slice(line).context("recording segment contains invalid NDJSON")?;
        let state = frame
            .get("redactionState")
            .and_then(serde_json::Value::as_str)
            .ok_or_else(|| anyhow::anyhow!("recording frame omitted redaction state"))?;
        anyhow::ensure!(
            matches!(state, "MASKED" | "NOT_REQUIRED"),
            "recording frame redaction state is invalid"
        );
        let policy_version = frame
            .get("redactionPolicyVersion")
            .and_then(serde_json::Value::as_u64)
            .and_then(|value| u32::try_from(value).ok());
        anyhow::ensure!(
            policy_version == Some(expected_policy_version),
            "recording frame redaction policy version does not match command"
        );
        let regions = frame
            .get("redactedRegionCount")
            .and_then(serde_json::Value::as_u64)
            .ok_or_else(|| anyhow::anyhow!("recording frame omitted redacted region count"))?;
        anyhow::ensure!(
            (state == "MASKED") == (regions > 0),
            "recording frame redaction state does not match region count"
        );
        frames = frames.saturating_add(1);
        if regions > 0 {
            redacted_frames = redacted_frames.saturating_add(1);
        }
        redacted_regions = redacted_regions.saturating_add(regions);
    }
    anyhow::ensure!(
        frames == expected_frames
            && redacted_frames == expected_redacted_frames
            && redacted_regions == expected_redacted_regions,
        "recording segment redaction summary does not match its frames"
    );
    Ok(())
}

fn profile_lock_stripe(tenant_id: &str, profile_id: &str, stripes: usize) -> usize {
    use std::hash::{Hash, Hasher};
    let mut hasher = std::collections::hash_map::DefaultHasher::new();
    tenant_id.hash(&mut hasher);
    profile_id.hash(&mut hasher);
    (hasher.finish() as usize) % stripes
}

fn workspace_response(workspace: ProfileWorkspace) -> StorageWorkspace {
    StorageWorkspace {
        tenant_id: workspace.tenant_id,
        profile_id: workspace.profile_id,
        session_id: workspace.session_id,
        core_dir: workspace.core_dir.to_string_lossy().into_owned(),
        ephemeral_dir: workspace.ephemeral_dir.to_string_lossy().into_owned(),
        profile_write_epoch: workspace.profile_write_epoch,
        restored_checkpoint_id: workspace.restored_checkpoint_id,
        restore_status: match workspace.restore_status {
            ProfileRestoreStatus::Empty => StorageRestoreStatus::Empty,
            ProfileRestoreStatus::TechnicalReady => StorageRestoreStatus::TechnicalReady,
        },
    }
}

fn rejected(request_id: String, code: &str, message: &str) -> StorageResponse {
    StorageResponse {
        schema_version: SCHEMA_VERSION,
        request_id,
        ok: false,
        workspace: None,
        checkpoint: None,
        warm_tier_sync: None,
        recording: None,
        evidence: None,
        evidence_access: None,
        profile_export_access: None,
        error_code: Some(code.to_owned()),
        error_message: Some(message.to_owned()),
    }
}

fn configured_node_agent_uid() -> anyhow::Result<u32> {
    let environment = std::env::var("APP_ENVIRONMENT").unwrap_or_else(|_| "local".to_owned());
    match std::env::var("NODE_AGENT_UID") {
        Ok(value) => value.parse().context("NODE_AGENT_UID must be an integer"),
        Err(_) if environment.eq_ignore_ascii_case("production") => {
            anyhow::bail!("NODE_AGENT_UID is required in production")
        }
        Err(_) => Ok(Uid::current().as_raw()),
    }
}

#[cfg(any(target_os = "linux", target_os = "android"))]
fn platform_peer_uid(stream: &UnixStream) -> anyhow::Result<u32> {
    let credentials = getsockopt(stream, nix::sys::socket::sockopt::PeerCredentials)?;
    Ok(credentials.uid())
}

#[cfg(any(
    target_os = "macos",
    target_os = "ios",
    target_os = "freebsd",
    target_os = "dragonfly"
))]
fn platform_peer_uid(stream: &UnixStream) -> anyhow::Result<u32> {
    let credentials = getsockopt(stream, nix::sys::socket::sockopt::LocalPeerCred)?;
    Ok(credentials.uid())
}

fn required_environment(name: &str) -> anyhow::Result<String> {
    let value = std::env::var(name)
        .with_context(|| format!("{name} is required"))?
        .trim()
        .to_owned();
    anyhow::ensure!(!value.is_empty(), "{name} cannot be empty");
    Ok(value)
}

fn required_absolute_path(name: &str) -> anyhow::Result<PathBuf> {
    let path = PathBuf::from(required_environment(name)?);
    anyhow::ensure!(path.is_absolute(), "{name} must be an absolute path");
    Ok(path)
}

async fn prepare_socket_path(socket_path: &Path) -> anyhow::Result<()> {
    let parent = socket_path
        .parent()
        .ok_or_else(|| anyhow::anyhow!("helper socket must have a parent directory"))?;
    tokio::fs::create_dir_all(parent).await?;
    match tokio::fs::symlink_metadata(socket_path).await {
        Ok(metadata) => {
            anyhow::ensure!(
                metadata.file_type().is_socket(),
                "refusing to replace a non-socket helper path"
            );
            tokio::fs::remove_file(socket_path).await?;
        }
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => {}
        Err(error) => return Err(error.into()),
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::os::unix::fs::symlink;
    use std::sync::atomic::{AtomicU64, Ordering};

    static TEST_SEQUENCE: AtomicU64 = AtomicU64::new(1);

    #[tokio::test]
    async fn rejects_a_peer_whose_kernel_uid_is_not_allowed() {
        let sequence = TEST_SEQUENCE.fetch_add(1, Ordering::Relaxed);
        let root = std::env::temp_dir().join(format!(
            "browsercloud-storage-helper-peer-{}-{sequence}",
            std::process::id()
        ));
        let socket_path = root.join("storage.sock");
        tokio::fs::create_dir_all(&root).await.unwrap();
        let listener = UnixListener::bind(&socket_path).unwrap();
        let client_path = socket_path.clone();
        let client = tokio::spawn(async move { UnixStream::connect(client_path).await.unwrap() });
        let (stream, _) = listener.accept().await.unwrap();
        let connected_client = client.await.unwrap();
        let service = Arc::new(StorageService::new(
            LocalProfileStore::open(root.join("profiles"))
                .await
                .unwrap(),
            None,
            root.join("imports"),
        ));
        let disallowed_uid = Uid::current().as_raw().saturating_add(1);
        let error = serve_connection(stream, disallowed_uid, service)
            .await
            .unwrap_err();
        assert!(error.to_string().contains("UID is not authorized"));
        drop(connected_client);
        drop(listener);
        tokio::fs::remove_dir_all(root).await.unwrap();
    }

    #[tokio::test]
    async fn rejects_a_recording_root_symlink_escape() {
        let sequence = TEST_SEQUENCE.fetch_add(1, Ordering::Relaxed);
        let root = std::env::temp_dir().join(format!(
            "browsercloud-storage-helper-recording-{}-{sequence}",
            std::process::id()
        ));
        let ephemeral = root.join("ephemeral");
        let escaped = root.join("escaped");
        tokio::fs::create_dir_all(&ephemeral).await.unwrap();
        tokio::fs::create_dir_all(&escaped).await.unwrap();
        symlink(&escaped, ephemeral.join("recordings")).unwrap();

        let error = verified_recording_directory(&ephemeral, "rec-test", true)
            .await
            .unwrap_err();
        assert!(error
            .to_string()
            .contains("recordings root is not a regular directory"));
        tokio::fs::remove_dir_all(root).await.unwrap();
    }

    #[test]
    fn verifies_recording_redaction_metadata_from_every_ndjson_frame() {
        let content = br#"{"redactionState":"MASKED","redactedRegionCount":2,"redactionPolicyVersion":1,"data":"safe"}
{"redactionState":"NOT_REQUIRED","redactedRegionCount":0,"redactionPolicyVersion":1,"data":"safe"}
"#;

        verify_recording_segment_redaction(content, 2, 1, 2, 1).unwrap();
        assert!(verify_recording_segment_redaction(content, 2, 0, 0, 1)
            .unwrap_err()
            .to_string()
            .contains("summary does not match"));
    }

    #[test]
    fn rejects_recording_frame_without_a_redaction_attestation() {
        let legacy = br#"{"capturedAtMs":1,"data":"raw"}
"#;

        assert!(verify_recording_segment_redaction(legacy, 1, 0, 0, 1)
            .unwrap_err()
            .to_string()
            .contains("omitted redaction state"));
    }
}
