//! Browser Profile 的本地可靠存储原语。
//!
//! Profile Core 会进入检查点；Cache、Crashpad 等 Ephemeral 数据永不进入默认归档。
//! 每个 Profile 同时只允许一个 Session Writer，检查点只有在 Manifest 和 COMMITTED
//! Marker 都落盘后才可恢复。

use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::fs::{self, OpenOptions};
use std::io::{Read, Write};
#[cfg(unix)]
use std::os::unix::fs::{OpenOptionsExt, PermissionsExt};
use std::path::{Component, Path, PathBuf};

const MAX_PROFILE_FILES: usize = 50_000;
const MAX_PROFILE_FILE_BYTES: u64 = 512 * 1024 * 1024;
const MANIFEST_FILE: &str = "manifest.json";
const COMMIT_MARKER_FILE: &str = "COMMITTED";
const LATEST_FILE: &str = "LATEST";
const WRITER_LOCK_FILE: &str = "WRITER";

/// Profile 检查点清单。
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct ProfileCheckpointManifest {
    pub checkpoint_id: String,
    pub checkpoint_epoch: u64,
    pub tenant_id: String,
    pub profile_id: String,
    pub runtime_build_id: String,
    pub profile_write_epoch: u64,
    pub files: Vec<CheckpointFile>,
    pub core_size_bytes: u64,
    pub content_hash: String,
    pub committed: bool,
}

/// 检查点文件。
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct CheckpointFile {
    pub relative_path: String,
    pub size: u64,
    pub sha256: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum ProfileRestoreStatus {
    Empty,
    TechnicalReady,
}

#[derive(Debug, Clone)]
pub struct ProfileWorkspace {
    pub tenant_id: String,
    pub profile_id: String,
    pub session_id: String,
    pub core_dir: PathBuf,
    pub ephemeral_dir: PathBuf,
    pub profile_write_epoch: u64,
    pub restored_checkpoint_id: Option<String>,
    pub restore_status: ProfileRestoreStatus,
}

#[derive(Debug, Clone)]
pub struct LocalProfileStore {
    root: PathBuf,
}

impl LocalProfileStore {
    pub async fn open(root: PathBuf) -> anyhow::Result<Self> {
        tokio::fs::create_dir_all(&root).await?;
        #[cfg(unix)]
        tokio::fs::set_permissions(&root, fs::Permissions::from_mode(0o700)).await?;
        Ok(Self { root })
    }

    pub async fn acquire_workspace(
        &self,
        tenant_id: &str,
        profile_id: &str,
        session_id: &str,
    ) -> anyhow::Result<ProfileWorkspace> {
        validate_identifier("tenant_id", tenant_id)?;
        validate_identifier("profile_id", profile_id)?;
        validate_identifier("session_id", session_id)?;
        let root = self.root.clone();
        let tenant_id = tenant_id.to_owned();
        let profile_id = profile_id.to_owned();
        let session_id = session_id.to_owned();
        tokio::task::spawn_blocking(move || {
            acquire_workspace_blocking(&root, &tenant_id, &profile_id, &session_id)
        })
        .await?
    }

    pub async fn checkpoint(
        &self,
        workspace: &ProfileWorkspace,
        runtime_build_id: &str,
    ) -> anyhow::Result<ProfileCheckpointManifest> {
        validate_identifier("runtime_build_id", runtime_build_id)?;
        let root = self.root.clone();
        let workspace = workspace.clone();
        let runtime_build_id = runtime_build_id.to_owned();
        tokio::task::spawn_blocking(move || {
            checkpoint_blocking(&root, &workspace, &runtime_build_id)
        })
        .await?
    }

    pub async fn release_writer(&self, workspace: &ProfileWorkspace) -> anyhow::Result<()> {
        let root = self.root.clone();
        let workspace = workspace.clone();
        tokio::task::spawn_blocking(move || release_writer_blocking(&root, &workspace)).await?
    }
}

fn acquire_workspace_blocking(
    root: &Path,
    tenant_id: &str,
    profile_id: &str,
    session_id: &str,
) -> anyhow::Result<ProfileWorkspace> {
    let profile_root = profile_root(root, tenant_id, profile_id);
    secure_create_dir_all(&profile_root)?;
    secure_create_dir_all(&profile_root.join("checkpoints"))?;
    secure_create_dir_all(&profile_root.join("workspaces"))?;
    let writer_created = acquire_writer(&profile_root, session_id)?;
    let workspace_root = profile_root.join("workspaces").join(session_id);
    if !writer_created {
        anyhow::ensure!(
            workspace_root.join("core").is_dir() && workspace_root.join("ephemeral").is_dir(),
            "existing profile writer has no reusable workspace"
        );
        let write_epoch = read_u64(&profile_root.join("WRITE_EPOCH"))?;
        anyhow::ensure!(write_epoch > 0, "profile write epoch is missing");
        let latest = read_optional_text(&profile_root.join(LATEST_FILE))?;
        return Ok(ProfileWorkspace {
            tenant_id: tenant_id.to_owned(),
            profile_id: profile_id.to_owned(),
            session_id: session_id.to_owned(),
            core_dir: workspace_root.join("core"),
            ephemeral_dir: workspace_root.join("ephemeral"),
            profile_write_epoch: write_epoch,
            restore_status: if latest.is_some() {
                ProfileRestoreStatus::TechnicalReady
            } else {
                ProfileRestoreStatus::Empty
            },
            restored_checkpoint_id: latest,
        });
    }

    let result = (|| {
        let write_epoch_path = profile_root.join("WRITE_EPOCH");
        let write_epoch = read_u64(&write_epoch_path)?.saturating_add(1);
        atomic_write(&write_epoch_path, write_epoch.to_string().as_bytes())?;

        if workspace_root.exists() {
            fs::remove_dir_all(&workspace_root)?;
        }
        let core_dir = workspace_root.join("core");
        let ephemeral_dir = workspace_root.join("ephemeral");
        secure_create_dir_all(&core_dir)?;
        secure_create_dir_all(&ephemeral_dir)?;

        let latest = read_optional_text(&profile_root.join(LATEST_FILE))?;
        let restore_status = if let Some(checkpoint_id) = latest.as_deref() {
            restore_checkpoint(&profile_root, checkpoint_id, &core_dir)?;
            ProfileRestoreStatus::TechnicalReady
        } else {
            ProfileRestoreStatus::Empty
        };

        Ok(ProfileWorkspace {
            tenant_id: tenant_id.to_owned(),
            profile_id: profile_id.to_owned(),
            session_id: session_id.to_owned(),
            core_dir,
            ephemeral_dir,
            profile_write_epoch: write_epoch,
            restored_checkpoint_id: latest,
            restore_status,
        })
    })();
    if result.is_err() {
        let _ = fs::remove_file(profile_root.join(WRITER_LOCK_FILE));
    }
    result
}

fn checkpoint_blocking(
    root: &Path,
    workspace: &ProfileWorkspace,
    runtime_build_id: &str,
) -> anyhow::Result<ProfileCheckpointManifest> {
    let profile_root = profile_root(root, &workspace.tenant_id, &workspace.profile_id);
    require_writer(&profile_root, &workspace.session_id)?;
    anyhow::ensure!(
        workspace
            .core_dir
            .starts_with(profile_root.join("workspaces")),
        "workspace core path escaped profile root"
    );

    let previous_epoch = read_latest_manifest(&profile_root)?
        .map(|manifest| manifest.checkpoint_epoch)
        .unwrap_or_default();
    let checkpoint_epoch = previous_epoch.saturating_add(1);
    let checkpoint_id = format!("chk_{}_{}", checkpoint_epoch, uuid::Uuid::new_v4().simple());
    let staging = profile_root
        .join("checkpoints")
        .join(format!(".staging-{checkpoint_id}"));
    let committed_dir = profile_root.join("checkpoints").join(&checkpoint_id);
    secure_create_dir_all(&staging.join("core"))?;

    let mut files = Vec::new();
    copy_core(
        &workspace.core_dir,
        &staging.join("core"),
        Path::new(""),
        &mut files,
    )?;
    files.sort_by(|left, right| left.relative_path.cmp(&right.relative_path));
    anyhow::ensure!(
        files.len() <= MAX_PROFILE_FILES,
        "profile contains too many files"
    );
    let core_size_bytes = files.iter().map(|file| file.size).sum();
    let content_hash = manifest_content_hash(&files);
    let manifest = ProfileCheckpointManifest {
        checkpoint_id: checkpoint_id.clone(),
        checkpoint_epoch,
        tenant_id: workspace.tenant_id.clone(),
        profile_id: workspace.profile_id.clone(),
        runtime_build_id: runtime_build_id.to_owned(),
        profile_write_epoch: workspace.profile_write_epoch,
        files,
        core_size_bytes,
        content_hash: content_hash.clone(),
        committed: true,
    };
    atomic_write(
        &staging.join(MANIFEST_FILE),
        &serde_json::to_vec_pretty(&manifest)?,
    )?;
    fs::rename(&staging, &committed_dir)?;
    sync_directory(&profile_root.join("checkpoints"))?;
    atomic_write(
        &committed_dir.join(COMMIT_MARKER_FILE),
        content_hash.as_bytes(),
    )?;
    atomic_write(&profile_root.join(LATEST_FILE), checkpoint_id.as_bytes())?;
    Ok(manifest)
}

fn release_writer_blocking(root: &Path, workspace: &ProfileWorkspace) -> anyhow::Result<()> {
    let profile_root = profile_root(root, &workspace.tenant_id, &workspace.profile_id);
    require_writer(&profile_root, &workspace.session_id)?;
    fs::remove_file(profile_root.join(WRITER_LOCK_FILE))?;
    Ok(())
}

fn acquire_writer(profile_root: &Path, session_id: &str) -> anyhow::Result<bool> {
    let lock_path = profile_root.join(WRITER_LOCK_FILE);
    match create_private_file(&lock_path) {
        Ok(mut file) => {
            file.write_all(session_id.as_bytes())?;
            file.sync_all()?;
            Ok(true)
        }
        Err(error) if error.kind() == std::io::ErrorKind::AlreadyExists => {
            let owner = fs::read_to_string(&lock_path)?;
            anyhow::ensure!(
                owner.trim() == session_id,
                "profile already has an active writer"
            );
            Ok(false)
        }
        Err(error) => Err(error.into()),
    }
}

fn require_writer(profile_root: &Path, session_id: &str) -> anyhow::Result<()> {
    let owner = fs::read_to_string(profile_root.join(WRITER_LOCK_FILE))?;
    anyhow::ensure!(
        owner.trim() == session_id,
        "profile writer ownership mismatch"
    );
    Ok(())
}

fn restore_checkpoint(
    profile_root: &Path,
    checkpoint_id: &str,
    destination: &Path,
) -> anyhow::Result<ProfileCheckpointManifest> {
    validate_identifier("checkpoint_id", checkpoint_id)?;
    let checkpoint = profile_root.join("checkpoints").join(checkpoint_id);
    let manifest: ProfileCheckpointManifest =
        serde_json::from_slice(&fs::read(checkpoint.join(MANIFEST_FILE))?)?;
    anyhow::ensure!(manifest.committed, "checkpoint manifest is not committed");
    anyhow::ensure!(
        manifest.checkpoint_id == checkpoint_id,
        "checkpoint manifest identity mismatch"
    );
    anyhow::ensure!(
        manifest.tenant_id
            == profile_root
                .parent()
                .and_then(Path::parent)
                .and_then(Path::file_name)
                .and_then(|value| value.to_str())
                .unwrap_or_default(),
        "checkpoint tenant identity mismatch"
    );
    anyhow::ensure!(
        manifest.profile_id
            == profile_root
                .file_name()
                .and_then(|value| value.to_str())
                .unwrap_or_default(),
        "checkpoint profile identity mismatch"
    );
    anyhow::ensure!(
        manifest.files.len() <= MAX_PROFILE_FILES,
        "checkpoint contains too many files"
    );
    let marker = fs::read_to_string(checkpoint.join(COMMIT_MARKER_FILE))?;
    anyhow::ensure!(
        marker.trim() == manifest.content_hash,
        "checkpoint commit marker mismatch"
    );
    anyhow::ensure!(
        manifest_content_hash(&manifest.files) == manifest.content_hash,
        "checkpoint manifest content hash mismatch"
    );

    for file in &manifest.files {
        anyhow::ensure!(
            file.size <= MAX_PROFILE_FILE_BYTES,
            "checkpoint file exceeds size limit"
        );
        let relative = safe_relative_path(&file.relative_path)?;
        let source = checkpoint.join("core").join(&relative);
        let metadata = fs::symlink_metadata(&source)?;
        anyhow::ensure!(metadata.is_file(), "checkpoint contains a non-file entry");
        anyhow::ensure!(metadata.len() == file.size, "checkpoint file size mismatch");
        anyhow::ensure!(
            hash_file(&source)? == file.sha256,
            "checkpoint file hash mismatch"
        );
        let target = destination.join(&relative);
        if let Some(parent) = target.parent() {
            secure_create_dir_all(parent)?;
        }
        fs::copy(source, target)?;
        secure_file_permissions(&destination.join(relative))?;
    }
    Ok(manifest)
}

fn copy_core(
    source_root: &Path,
    destination_root: &Path,
    relative: &Path,
    files: &mut Vec<CheckpointFile>,
) -> anyhow::Result<()> {
    let source = source_root.join(relative);
    for entry in fs::read_dir(&source)? {
        let entry = entry?;
        let file_type = entry.file_type()?;
        let next_relative = relative.join(entry.file_name());
        if is_ephemeral(&next_relative) {
            continue;
        }
        anyhow::ensure!(!file_type.is_symlink(), "profile symlinks are not allowed");
        if file_type.is_dir() {
            copy_core(source_root, destination_root, &next_relative, files)?;
        } else if file_type.is_file() {
            let metadata = entry.metadata()?;
            anyhow::ensure!(
                metadata.len() <= MAX_PROFILE_FILE_BYTES,
                "profile file exceeds size limit"
            );
            anyhow::ensure!(
                files.len() < MAX_PROFILE_FILES,
                "profile contains too many files"
            );
            let target = destination_root.join(&next_relative);
            if let Some(parent) = target.parent() {
                secure_create_dir_all(parent)?;
            }
            fs::copy(entry.path(), &target)?;
            secure_file_permissions(&target)?;
            files.push(CheckpointFile {
                relative_path: path_to_manifest(&next_relative)?,
                size: metadata.len(),
                sha256: hash_file(&entry.path())?,
            });
        }
    }
    Ok(())
}

fn is_ephemeral(relative: &Path) -> bool {
    relative.components().any(|component| {
        matches!(
            component.as_os_str().to_string_lossy().as_ref(),
            "Cache"
                | "Code Cache"
                | "GPUCache"
                | "Crashpad"
                | "ShaderCache"
                | "GrShaderCache"
                | "DawnCache"
                | "GraphiteDawnCache"
                | "BrowserMetrics"
                | "Temp"
                | "DevToolsActivePort"
                | "SingletonLock"
                | "SingletonSocket"
                | "SingletonCookie"
        )
    })
}

fn hash_file(path: &Path) -> anyhow::Result<String> {
    let mut file = fs::File::open(path)?;
    let mut hasher = Sha256::new();
    let mut buffer = [0_u8; 64 * 1024];
    loop {
        let read = file.read(&mut buffer)?;
        if read == 0 {
            break;
        }
        hasher.update(&buffer[..read]);
    }
    Ok(format!("{:x}", hasher.finalize()))
}

fn manifest_content_hash(files: &[CheckpointFile]) -> String {
    let mut hasher = Sha256::new();
    for file in files {
        hasher.update(file.relative_path.as_bytes());
        hasher.update([0]);
        hasher.update(file.size.to_be_bytes());
        hasher.update(file.sha256.as_bytes());
        hasher.update([0]);
    }
    format!("{:x}", hasher.finalize())
}

fn atomic_write(path: &Path, content: &[u8]) -> anyhow::Result<()> {
    let parent = path
        .parent()
        .ok_or_else(|| anyhow::anyhow!("atomic write path has no parent"))?;
    secure_create_dir_all(parent)?;
    let temporary = parent.join(format!(".tmp-{}", uuid::Uuid::new_v4().simple()));
    let mut file = create_private_file(&temporary)?;
    file.write_all(content)?;
    file.sync_all()?;
    fs::rename(temporary, path)?;
    sync_directory(parent)?;
    Ok(())
}

fn secure_create_dir_all(path: &Path) -> anyhow::Result<()> {
    fs::create_dir_all(path)?;
    #[cfg(unix)]
    fs::set_permissions(path, fs::Permissions::from_mode(0o700))?;
    Ok(())
}

fn secure_file_permissions(path: &Path) -> anyhow::Result<()> {
    #[cfg(unix)]
    fs::set_permissions(path, fs::Permissions::from_mode(0o600))?;
    Ok(())
}

fn create_private_file(path: &Path) -> std::io::Result<fs::File> {
    let mut options = OpenOptions::new();
    options.write(true).create_new(true);
    #[cfg(unix)]
    options.mode(0o600);
    options.open(path)
}

fn sync_directory(path: &Path) -> anyhow::Result<()> {
    #[cfg(unix)]
    fs::File::open(path)?.sync_all()?;
    Ok(())
}

fn read_u64(path: &Path) -> anyhow::Result<u64> {
    Ok(read_optional_text(path)?
        .map(|value| value.parse())
        .transpose()?
        .unwrap_or_default())
}

fn read_optional_text(path: &Path) -> anyhow::Result<Option<String>> {
    match fs::read_to_string(path) {
        Ok(value) => {
            let value = value.trim().to_owned();
            anyhow::ensure!(!value.is_empty(), "metadata file is empty");
            Ok(Some(value))
        }
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(None),
        Err(error) => Err(error.into()),
    }
}

fn read_latest_manifest(profile_root: &Path) -> anyhow::Result<Option<ProfileCheckpointManifest>> {
    let Some(checkpoint_id) = read_optional_text(&profile_root.join(LATEST_FILE))? else {
        return Ok(None);
    };
    let manifest = serde_json::from_slice(&fs::read(
        profile_root
            .join("checkpoints")
            .join(checkpoint_id)
            .join(MANIFEST_FILE),
    )?)?;
    Ok(Some(manifest))
}

fn profile_root(root: &Path, tenant_id: &str, profile_id: &str) -> PathBuf {
    root.join("tenants")
        .join(tenant_id)
        .join("profiles")
        .join(profile_id)
}

fn validate_identifier(name: &str, value: &str) -> anyhow::Result<()> {
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

fn safe_relative_path(value: &str) -> anyhow::Result<PathBuf> {
    let path = PathBuf::from(value);
    anyhow::ensure!(
        !path.as_os_str().is_empty()
            && path
                .components()
                .all(|component| matches!(component, Component::Normal(_))),
        "manifest contains an unsafe relative path"
    );
    Ok(path)
}

fn path_to_manifest(path: &Path) -> anyhow::Result<String> {
    let path = safe_relative_path(&path.to_string_lossy())?;
    Ok(path
        .components()
        .map(|component| component.as_os_str().to_string_lossy())
        .collect::<Vec<_>>()
        .join("/"))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn temp_root() -> PathBuf {
        std::env::temp_dir().join(format!(
            "browsercloud-profile-test-{}",
            uuid::Uuid::new_v4().simple()
        ))
    }

    #[tokio::test]
    async fn checkpoints_core_excludes_cache_and_restores_with_integrity_check() {
        let root = temp_root();
        let store = LocalProfileStore::open(root.clone()).await.unwrap();
        let workspace = store
            .acquire_workspace("tenant-test", "profile-test", "session-one")
            .await
            .unwrap();
        fs::create_dir_all(workspace.core_dir.join("Default/Cache")).unwrap();
        fs::write(workspace.core_dir.join("Default/Cookies"), b"login-cookie").unwrap();
        fs::write(
            workspace.core_dir.join("Default/Cache/cache.bin"),
            b"not-durable",
        )
        .unwrap();
        #[cfg(unix)]
        std::os::unix::fs::symlink(
            "/tmp/chromium-singleton",
            workspace.core_dir.join("SingletonSocket"),
        )
        .unwrap();

        let manifest = store.checkpoint(&workspace, "runtime-test").await.unwrap();
        assert_eq!(manifest.files.len(), 1);
        assert_eq!(manifest.files[0].relative_path, "Default/Cookies");
        assert!(manifest.committed);
        store.release_writer(&workspace).await.unwrap();

        let restored = store
            .acquire_workspace("tenant-test", "profile-test", "session-two")
            .await
            .unwrap();
        assert_eq!(
            fs::read(restored.core_dir.join("Default/Cookies")).unwrap(),
            b"login-cookie"
        );
        assert!(!restored.core_dir.join("Default/Cache/cache.bin").exists());
        assert_eq!(
            restored.restore_status,
            ProfileRestoreStatus::TechnicalReady
        );
        store.release_writer(&restored).await.unwrap();
        fs::remove_dir_all(root).unwrap();
    }

    #[tokio::test]
    async fn rejects_a_second_profile_writer() {
        let root = temp_root();
        let store = LocalProfileStore::open(root.clone()).await.unwrap();
        let first = store
            .acquire_workspace("tenant-test", "profile-test", "session-one")
            .await
            .unwrap();
        let error = store
            .acquire_workspace("tenant-test", "profile-test", "session-two")
            .await
            .unwrap_err();
        assert!(error.to_string().contains("active writer"));
        store.release_writer(&first).await.unwrap();
        fs::remove_dir_all(root).unwrap();
    }

    #[cfg(unix)]
    #[tokio::test]
    async fn rejects_unknown_profile_symlinks() {
        let root = temp_root();
        let store = LocalProfileStore::open(root.clone()).await.unwrap();
        let workspace = store
            .acquire_workspace("tenant-test", "profile-test", "session-one")
            .await
            .unwrap();
        std::os::unix::fs::symlink("/etc/passwd", workspace.core_dir.join("UnexpectedLink"))
            .unwrap();

        let error = store
            .checkpoint(&workspace, "runtime-test")
            .await
            .unwrap_err();
        assert!(error.to_string().contains("symlinks are not allowed"));
        store.release_writer(&workspace).await.unwrap();
        fs::remove_dir_all(root).unwrap();
    }

    #[tokio::test]
    async fn reuses_the_same_session_workspace_after_agent_restart() {
        let root = temp_root();
        let first_store = LocalProfileStore::open(root.clone()).await.unwrap();
        let first = first_store
            .acquire_workspace("tenant-test", "profile-test", "session-one")
            .await
            .unwrap();
        fs::write(first.core_dir.join("Uncheckpointed"), b"survives").unwrap();

        let restarted_store = LocalProfileStore::open(root.clone()).await.unwrap();
        let recovered = restarted_store
            .acquire_workspace("tenant-test", "profile-test", "session-one")
            .await
            .unwrap();
        assert_eq!(recovered.profile_write_epoch, first.profile_write_epoch);
        assert_eq!(
            fs::read(recovered.core_dir.join("Uncheckpointed")).unwrap(),
            b"survives"
        );
        restarted_store.release_writer(&recovered).await.unwrap();
        fs::remove_dir_all(root).unwrap();
    }

    #[tokio::test]
    async fn rejects_corrupted_committed_checkpoint() {
        let root = temp_root();
        let store = LocalProfileStore::open(root.clone()).await.unwrap();
        let first = store
            .acquire_workspace("tenant-test", "profile-test", "session-one")
            .await
            .unwrap();
        fs::write(first.core_dir.join("Cookies"), b"valid").unwrap();
        let manifest = store.checkpoint(&first, "runtime-test").await.unwrap();
        store.release_writer(&first).await.unwrap();
        fs::write(
            root.join("tenants/tenant-test/profiles/profile-test/checkpoints")
                .join(manifest.checkpoint_id)
                .join("core/Cookies"),
            b"tampered",
        )
        .unwrap();

        let error = store
            .acquire_workspace("tenant-test", "profile-test", "session-two")
            .await
            .unwrap_err();
        assert!(error.to_string().contains("size mismatch"));
        assert!(!root
            .join("tenants/tenant-test/profiles/profile-test/WRITER")
            .exists());
        fs::remove_dir_all(root).unwrap();
    }
}
