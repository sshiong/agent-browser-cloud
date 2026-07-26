use anyhow::Context;
use helper_contracts::{
    read_frame, write_frame, StorageCheckpoint, StorageCommand, StorageRequest, StorageResponse,
    StorageRestoreStatus, StorageWorkspace, SCHEMA_VERSION,
};
use nix::sys::socket::getsockopt;
use nix::unistd::Uid;
use std::os::unix::fs::{FileTypeExt, PermissionsExt};
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::Duration;
use storage_helper::object_archive::{ObjectArchive, S3ArchiveConfig};
use storage_helper::{LocalProfileStore, ProfileRestoreStatus, ProfileWorkspace};
use tokio::net::{UnixListener, UnixStream};
use tokio::sync::Mutex;
use tracing::{error, info, warn};

struct StorageService {
    store: LocalProfileStore,
    archive: Option<ObjectArchive>,
    profile_locks: Vec<Mutex<()>>,
}

impl StorageService {
    fn new(store: LocalProfileStore, archive: Option<ObjectArchive>) -> Self {
        Self {
            store,
            archive,
            profile_locks: (0..64).map(|_| Mutex::new(())).collect(),
        }
    }

    async fn execute(
        &self,
        command: &StorageCommand,
    ) -> anyhow::Result<(Option<StorageWorkspace>, Option<StorageCheckpoint>)> {
        let Some((tenant_id, profile_id)) = command_profile(command) else {
            return Ok((None, None));
        };
        let stripe = profile_lock_stripe(tenant_id, profile_id, self.profile_locks.len());
        let _guard = self.profile_locks[stripe].lock().await;
        execute_storage_operation(&self.store, self.archive.as_ref(), command).await
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
    let object_archive = object_archive_from_environment()?;
    prepare_socket_path(&socket_path).await?;
    let allowed_uid = configured_node_agent_uid()?;
    let service = Arc::new(StorageService::new(
        LocalProfileStore::open(storage_root).await?,
        object_archive,
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
            Ok((workspace, checkpoint)) => StorageResponse {
                schema_version: SCHEMA_VERSION,
                request_id: request.request_id,
                ok: true,
                workspace,
                checkpoint,
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
    command: &StorageCommand,
) -> anyhow::Result<(Option<StorageWorkspace>, Option<StorageCheckpoint>)> {
    match command {
        StorageCommand::Ping => Ok((None, None)),
        StorageCommand::Acquire {
            tenant_id,
            profile_id,
            session_id,
        } => {
            let workspace = store
                .acquire_workspace(tenant_id, profile_id, session_id)
                .await?;
            Ok((Some(workspace_response(workspace)), None))
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
            Ok((None, None))
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
        | StorageCommand::Release {
            tenant_id,
            profile_id,
            ..
        } => Some((tenant_id, profile_id)),
    }
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
}
