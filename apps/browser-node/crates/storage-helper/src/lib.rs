//! Browser Profile 的本地可靠存储原语。
//!
//! Profile Core 会进入检查点；Cache、Crashpad 等 Ephemeral 数据永不进入默认归档。
//! 每个 Profile 同时只允许一个 Session Writer，检查点只有在 Manifest 和 COMMITTED
//! Marker 都落盘后才可恢复。

use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::collections::{HashMap, HashSet};
use std::fs::{self, OpenOptions};
use std::io::{Cursor, Read, Write};
#[cfg(unix)]
use std::os::unix::fs::{OpenOptionsExt, PermissionsExt};
use std::path::{Component, Path, PathBuf};

pub mod object_archive;

const MAX_PROFILE_FILES: usize = 50_000;
const MAX_PROFILE_FILE_BYTES: u64 = 512 * 1024 * 1024;
const MAX_PROFILE_CORE_BYTES: u64 = 1024 * 1024 * 1024;
const MANIFEST_FILE: &str = "manifest.json";
const COMMIT_MARKER_FILE: &str = "COMMITTED";
const LATEST_FILE: &str = "LATEST";
const WRITER_LOCK_FILE: &str = "WRITER";
const MAX_CHECKPOINT_ARCHIVE_BYTES: usize = 1024 * 1024 * 1024;
const WARM_TIER_DIR: &str = "warm-tier";
const WARM_TIER_LATEST_FILE: &str = "LATEST";
const MAX_WARM_TIER_DELTA_BYTES: u64 = 64 * 1024 * 1024;

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
#[serde(rename_all = "camelCase")]
pub struct ProfileDeltaJournalManifest {
    pub tenant_id: String,
    pub profile_id: String,
    pub profile_write_epoch: u64,
    pub journal_sequence: u64,
    pub transaction_barrier: String,
    pub files: Vec<DeltaJournalFile>,
    pub deleted_files: Vec<String>,
    pub changed_file_count: u64,
    pub deleted_file_count: u64,
    pub reused_chunk_count: u64,
    pub uploaded_bytes: u64,
    pub deferred_groups: Vec<String>,
    pub content_hash: String,
    pub committed_at_ms: u64,
    pub committed: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct DeltaJournalFile {
    pub relative_path: String,
    pub size: u64,
    pub sha256: String,
    pub database_group: String,
    pub changed: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum ProfileRestoreStatus {
    Empty,
    TechnicalReady,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
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
        tokio::fs::set_permissions(&root, fs::Permissions::from_mode(0o770)).await?;
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

    pub async fn sync_warm_tier(
        &self,
        workspace: &ProfileWorkspace,
    ) -> anyhow::Result<ProfileDeltaJournalManifest> {
        let root = self.root.clone();
        let workspace = workspace.clone();
        tokio::task::spawn_blocking(move || sync_warm_tier_blocking(&root, &workspace)).await?
    }

    pub async fn pack_checkpoint(
        &self,
        manifest: &ProfileCheckpointManifest,
    ) -> anyhow::Result<Vec<u8>> {
        validate_identifier("tenant_id", &manifest.tenant_id)?;
        validate_identifier("profile_id", &manifest.profile_id)?;
        validate_identifier("checkpoint_id", &manifest.checkpoint_id)?;
        let checkpoint = profile_root(&self.root, &manifest.tenant_id, &manifest.profile_id)
            .join("checkpoints")
            .join(&manifest.checkpoint_id);
        let expected = manifest.clone();
        tokio::task::spawn_blocking(move || {
            let validated = validate_committed_checkpoint(
                checkpoint
                    .parent()
                    .and_then(Path::parent)
                    .ok_or_else(|| anyhow::anyhow!("invalid checkpoint path"))?,
                &expected.checkpoint_id,
            )?;
            anyhow::ensure!(validated == expected, "checkpoint changed before archive");
            let encoder = zstd::Encoder::new(Vec::new(), 3)?;
            let mut archive = tar::Builder::new(encoder);
            archive.follow_symlinks(false);
            archive.append_path_with_name(checkpoint.join(MANIFEST_FILE), MANIFEST_FILE)?;
            archive
                .append_path_with_name(checkpoint.join(COMMIT_MARKER_FILE), COMMIT_MARKER_FILE)?;
            for file in &expected.files {
                let relative = safe_relative_path(&file.relative_path)?;
                archive.append_path_with_name(
                    checkpoint.join("core").join(&relative),
                    Path::new("core").join(relative),
                )?;
            }
            let encoder = archive.into_inner()?;
            Ok(encoder.finish()?)
        })
        .await?
    }

    pub async fn install_checkpoint_archive(
        &self,
        tenant_id: &str,
        profile_id: &str,
        checkpoint_id: &str,
        archive: Vec<u8>,
    ) -> anyhow::Result<ProfileCheckpointManifest> {
        validate_identifier("tenant_id", tenant_id)?;
        validate_identifier("profile_id", profile_id)?;
        validate_identifier("checkpoint_id", checkpoint_id)?;
        anyhow::ensure!(
            archive.len() <= MAX_CHECKPOINT_ARCHIVE_BYTES,
            "checkpoint archive exceeds the bounded restore size"
        );
        let root = self.root.clone();
        let tenant_id = tenant_id.to_owned();
        let profile_id = profile_id.to_owned();
        let checkpoint_id = checkpoint_id.to_owned();
        tokio::task::spawn_blocking(move || {
            install_checkpoint_archive_blocking(
                &root,
                &tenant_id,
                &profile_id,
                &checkpoint_id,
                &archive,
            )
        })
        .await?
    }

    /// Imports an untrusted exported checkpoint into a new tenant/Profile identity.
    ///
    /// Source manifest identities are deliberately ignored. Only regular files below `core/`
    /// survive, and a new manifest/commit marker is produced from verified bytes.
    pub async fn import_checkpoint_archive(
        &self,
        tenant_id: &str,
        profile_id: &str,
        checkpoint_id: &str,
        runtime_build_id: &str,
        archive: Vec<u8>,
    ) -> anyhow::Result<ProfileCheckpointManifest> {
        validate_identifier("tenant_id", tenant_id)?;
        validate_identifier("profile_id", profile_id)?;
        validate_identifier("checkpoint_id", checkpoint_id)?;
        validate_identifier("runtime_build_id", runtime_build_id)?;
        anyhow::ensure!(
            archive.len() <= MAX_CHECKPOINT_ARCHIVE_BYTES,
            "checkpoint archive exceeds the bounded import size"
        );
        let root = self.root.clone();
        let tenant_id = tenant_id.to_owned();
        let profile_id = profile_id.to_owned();
        let checkpoint_id = checkpoint_id.to_owned();
        let runtime_build_id = runtime_build_id.to_owned();
        tokio::task::spawn_blocking(move || {
            import_checkpoint_archive_blocking(
                &root,
                &tenant_id,
                &profile_id,
                &checkpoint_id,
                &runtime_build_id,
                Cursor::new(archive),
            )
        })
        .await?
    }

    /// Imports from an already securely opened staging file without buffering the compressed
    /// archive in helper memory.
    pub async fn import_checkpoint_archive_file(
        &self,
        tenant_id: &str,
        profile_id: &str,
        checkpoint_id: &str,
        runtime_build_id: &str,
        archive_size_bytes: u64,
        archive: fs::File,
    ) -> anyhow::Result<ProfileCheckpointManifest> {
        validate_identifier("tenant_id", tenant_id)?;
        validate_identifier("profile_id", profile_id)?;
        validate_identifier("checkpoint_id", checkpoint_id)?;
        validate_identifier("runtime_build_id", runtime_build_id)?;
        anyhow::ensure!(
            archive_size_bytes > 0 && archive_size_bytes <= MAX_CHECKPOINT_ARCHIVE_BYTES as u64,
            "checkpoint archive exceeds the bounded import size"
        );
        anyhow::ensure!(
            archive.metadata()?.len() == archive_size_bytes,
            "checkpoint archive size changed before import"
        );
        let root = self.root.clone();
        let tenant_id = tenant_id.to_owned();
        let profile_id = profile_id.to_owned();
        let checkpoint_id = checkpoint_id.to_owned();
        let runtime_build_id = runtime_build_id.to_owned();
        tokio::task::spawn_blocking(move || {
            import_checkpoint_archive_blocking(
                &root,
                &tenant_id,
                &profile_id,
                &checkpoint_id,
                &runtime_build_id,
                archive,
            )
        })
        .await?
    }

    pub async fn activate_local_checkpoint(
        &self,
        tenant_id: &str,
        profile_id: &str,
        checkpoint_id: &str,
    ) -> anyhow::Result<bool> {
        validate_identifier("tenant_id", tenant_id)?;
        validate_identifier("profile_id", profile_id)?;
        validate_identifier("checkpoint_id", checkpoint_id)?;
        let root = self.root.clone();
        let tenant_id = tenant_id.to_owned();
        let profile_id = profile_id.to_owned();
        let checkpoint_id = checkpoint_id.to_owned();
        tokio::task::spawn_blocking(move || {
            activate_local_checkpoint_blocking(&root, &tenant_id, &profile_id, &checkpoint_id)
        })
        .await?
    }

    pub async fn resume_workspace(
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
            resume_workspace_blocking(&root, &tenant_id, &profile_id, &session_id)
        })
        .await?
    }

    pub async fn release_writer(&self, workspace: &ProfileWorkspace) -> anyhow::Result<()> {
        let root = self.root.clone();
        let workspace = workspace.clone();
        tokio::task::spawn_blocking(move || release_writer_blocking(&root, &workspace)).await?
    }

    pub async fn release_writer_by_identity(
        &self,
        tenant_id: &str,
        profile_id: &str,
        session_id: &str,
    ) -> anyhow::Result<()> {
        validate_identifier("tenant_id", tenant_id)?;
        validate_identifier("profile_id", profile_id)?;
        validate_identifier("session_id", session_id)?;
        let root = self.root.clone();
        let tenant_id = tenant_id.to_owned();
        let profile_id = profile_id.to_owned();
        let session_id = session_id.to_owned();
        tokio::task::spawn_blocking(move || {
            release_writer_by_identity_blocking(&root, &tenant_id, &profile_id, &session_id)
        })
        .await?
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

fn install_checkpoint_archive_blocking(
    root: &Path,
    tenant_id: &str,
    profile_id: &str,
    checkpoint_id: &str,
    archive_bytes: &[u8],
) -> anyhow::Result<ProfileCheckpointManifest> {
    let profile_root = profile_root(root, tenant_id, profile_id);
    let checkpoints = profile_root.join("checkpoints");
    secure_create_dir_all(&checkpoints)?;
    let committed = checkpoints.join(checkpoint_id);
    if committed.is_dir() {
        let manifest = validate_committed_checkpoint(&profile_root, checkpoint_id)?;
        anyhow::ensure!(
            manifest.tenant_id == tenant_id && manifest.profile_id == profile_id,
            "local checkpoint identity mismatch"
        );
        atomic_write(&profile_root.join(LATEST_FILE), checkpoint_id.as_bytes())?;
        return Ok(manifest);
    }

    let staging = checkpoints.join(format!(
        ".restore-{checkpoint_id}-{}",
        uuid::Uuid::new_v4().simple()
    ));
    secure_create_dir_all(&staging)?;
    let result = (|| {
        let decoder = zstd::Decoder::new(archive_bytes)?;
        let mut archive = tar::Archive::new(decoder);
        let mut file_count = 0usize;
        let mut entry_count = 0usize;
        for entry in archive.entries()? {
            let mut entry = entry?;
            entry_count = entry_count.saturating_add(1);
            anyhow::ensure!(
                entry_count <= MAX_PROFILE_FILES + 3,
                "checkpoint archive contains too many entries"
            );
            let entry_type = entry.header().entry_type();
            anyhow::ensure!(
                entry_type.is_file() || entry_type.is_dir(),
                "checkpoint archive contains a non-regular entry"
            );
            let path = entry.path()?.into_owned();
            let safe = safe_relative_path(&path.to_string_lossy())?;
            let permitted = safe == Path::new(MANIFEST_FILE)
                || safe == Path::new(COMMIT_MARKER_FILE)
                || safe.starts_with("core");
            anyhow::ensure!(permitted, "checkpoint archive contains an unexpected path");
            if entry_type.is_file() {
                file_count = file_count.saturating_add(1);
                anyhow::ensure!(
                    file_count <= MAX_PROFILE_FILES + 2,
                    "checkpoint archive contains too many files"
                );
                anyhow::ensure!(
                    entry.header().size()? <= MAX_PROFILE_FILE_BYTES,
                    "checkpoint archive file exceeds size limit"
                );
            }
            let target = staging.join(&safe);
            if let Some(parent) = target.parent() {
                secure_create_dir_all(parent)?;
            }
            entry.unpack(&target)?;
            if entry_type.is_file() {
                secure_file_permissions(&target)?;
            }
        }
        let manifest: ProfileCheckpointManifest =
            serde_json::from_slice(&fs::read(staging.join(MANIFEST_FILE))?)?;
        anyhow::ensure!(manifest.committed, "restored checkpoint is not committed");
        anyhow::ensure!(
            manifest.checkpoint_id == checkpoint_id
                && manifest.tenant_id == tenant_id
                && manifest.profile_id == profile_id,
            "restored checkpoint identity mismatch"
        );
        fs::rename(&staging, &committed)?;
        sync_directory(&checkpoints)?;
        atomic_write(&profile_root.join(LATEST_FILE), checkpoint_id.as_bytes())?;
        validate_committed_checkpoint(&profile_root, checkpoint_id)
    })();
    if result.is_err() {
        let _ = fs::remove_dir_all(&staging);
    }
    result
}

fn import_checkpoint_archive_blocking(
    root: &Path,
    tenant_id: &str,
    profile_id: &str,
    checkpoint_id: &str,
    runtime_build_id: &str,
    archive_reader: impl Read,
) -> anyhow::Result<ProfileCheckpointManifest> {
    let profile_root = profile_root(root, tenant_id, profile_id);
    let checkpoints = profile_root.join("checkpoints");
    secure_create_dir_all(&checkpoints)?;
    let committed = checkpoints.join(checkpoint_id);
    if committed.is_dir() {
        let manifest = validate_committed_checkpoint(&profile_root, checkpoint_id)?;
        anyhow::ensure!(
            manifest.tenant_id == tenant_id
                && manifest.profile_id == profile_id
                && manifest.runtime_build_id == runtime_build_id,
            "imported checkpoint identity mismatch"
        );
        atomic_write(&profile_root.join(LATEST_FILE), checkpoint_id.as_bytes())?;
        return Ok(manifest);
    }

    let staging = checkpoints.join(format!(
        ".import-{checkpoint_id}-{}",
        uuid::Uuid::new_v4().simple()
    ));
    secure_create_dir_all(&staging.join("core"))?;
    let result = (|| {
        let decoder = zstd::Decoder::new(archive_reader)?;
        let mut archive = tar::Archive::new(decoder);
        let mut files = Vec::new();
        let mut seen = HashSet::new();
        let mut entry_count = 0usize;
        let mut core_size_bytes = 0_u64;
        for entry in archive.entries()? {
            let mut entry = entry?;
            entry_count = entry_count.saturating_add(1);
            anyhow::ensure!(
                entry_count <= MAX_PROFILE_FILES + 3,
                "checkpoint import contains too many entries"
            );
            let entry_type = entry.header().entry_type();
            anyhow::ensure!(
                entry_type.is_file() || entry_type.is_dir(),
                "checkpoint import contains a non-regular entry"
            );
            let path = safe_relative_path(&entry.path()?.to_string_lossy())?;
            if path == Path::new(MANIFEST_FILE) || path == Path::new(COMMIT_MARKER_FILE) {
                anyhow::ensure!(
                    entry_type.is_file(),
                    "checkpoint metadata entry must be a regular file"
                );
                continue;
            }
            anyhow::ensure!(
                path.starts_with("core"),
                "checkpoint import contains an unexpected path"
            );
            let relative = path
                .strip_prefix("core")
                .map_err(|_| anyhow::anyhow!("checkpoint core path is invalid"))?;
            if relative.as_os_str().is_empty() {
                anyhow::ensure!(
                    entry_type.is_dir(),
                    "checkpoint core root must be a directory"
                );
                continue;
            }
            let relative = safe_relative_path(&relative.to_string_lossy())?;
            if is_ephemeral(&relative) {
                continue;
            }
            anyhow::ensure!(
                seen.insert(relative.clone()),
                "checkpoint import contains a duplicate path"
            );
            let target = staging.join("core").join(&relative);
            if entry_type.is_dir() {
                secure_create_dir_all(&target)?;
                continue;
            }
            let size = entry.header().size()?;
            anyhow::ensure!(
                size <= MAX_PROFILE_FILE_BYTES,
                "checkpoint import file exceeds size limit"
            );
            core_size_bytes = core_size_bytes
                .checked_add(size)
                .ok_or_else(|| anyhow::anyhow!("checkpoint import size overflow"))?;
            anyhow::ensure!(
                core_size_bytes <= MAX_PROFILE_CORE_BYTES,
                "checkpoint import core exceeds size limit"
            );
            anyhow::ensure!(
                files.len() < MAX_PROFILE_FILES,
                "checkpoint import contains too many files"
            );
            if let Some(parent) = target.parent() {
                secure_create_dir_all(parent)?;
            }
            entry.unpack(&target)?;
            secure_file_permissions(&target)?;
            files.push(CheckpointFile {
                relative_path: path_to_manifest(&relative)?,
                size,
                sha256: hash_file(&target)?,
            });
        }
        anyhow::ensure!(
            !files.is_empty(),
            "checkpoint import contains no Profile Core files"
        );
        files.sort_by(|left, right| left.relative_path.cmp(&right.relative_path));
        let content_hash = manifest_content_hash(&files);
        let manifest = ProfileCheckpointManifest {
            checkpoint_id: checkpoint_id.to_owned(),
            checkpoint_epoch: 1,
            tenant_id: tenant_id.to_owned(),
            profile_id: profile_id.to_owned(),
            runtime_build_id: runtime_build_id.to_owned(),
            profile_write_epoch: 0,
            files,
            core_size_bytes,
            content_hash: content_hash.clone(),
            committed: true,
        };
        atomic_write(
            &staging.join(MANIFEST_FILE),
            &serde_json::to_vec_pretty(&manifest)?,
        )?;
        fs::rename(&staging, &committed)?;
        sync_directory(&checkpoints)?;
        atomic_write(&committed.join(COMMIT_MARKER_FILE), content_hash.as_bytes())?;
        atomic_write(&profile_root.join(LATEST_FILE), checkpoint_id.as_bytes())?;
        validate_committed_checkpoint(&profile_root, checkpoint_id)
    })();
    if result.is_err() {
        let _ = fs::remove_dir_all(&staging);
    }
    result
}

fn activate_local_checkpoint_blocking(
    root: &Path,
    tenant_id: &str,
    profile_id: &str,
    checkpoint_id: &str,
) -> anyhow::Result<bool> {
    let profile_root = profile_root(root, tenant_id, profile_id);
    let committed = profile_root.join("checkpoints").join(checkpoint_id);
    if !committed.is_dir() {
        return Ok(false);
    }
    let manifest = validate_committed_checkpoint(&profile_root, checkpoint_id)?;
    anyhow::ensure!(
        manifest.tenant_id == tenant_id && manifest.profile_id == profile_id,
        "local checkpoint identity mismatch"
    );
    atomic_write(&profile_root.join(LATEST_FILE), checkpoint_id.as_bytes())?;
    Ok(true)
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

    let previous = read_latest_manifest(&profile_root)?;
    if let Some(previous) = previous.as_ref() {
        if previous.profile_write_epoch == workspace.profile_write_epoch
            && previous.runtime_build_id == runtime_build_id
        {
            return validate_committed_checkpoint(&profile_root, &previous.checkpoint_id);
        }
    }
    let previous_epoch = previous
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

fn sync_warm_tier_blocking(
    root: &Path,
    workspace: &ProfileWorkspace,
) -> anyhow::Result<ProfileDeltaJournalManifest> {
    let profile_root = profile_root(root, &workspace.tenant_id, &workspace.profile_id);
    require_writer(&profile_root, &workspace.session_id)?;
    anyhow::ensure!(
        workspace
            .core_dir
            .starts_with(profile_root.join("workspaces")),
        "workspace core path escaped profile root"
    );

    let warm_root = profile_root.join(WARM_TIER_DIR);
    let journal_root = warm_root.join("journal");
    let chunk_root = warm_root.join("chunks");
    secure_create_dir_all(&journal_root)?;
    secure_create_dir_all(&chunk_root)?;

    let previous = read_latest_warm_tier_manifest(&warm_root)?;
    let previous_by_path = previous
        .as_ref()
        .filter(|manifest| manifest.profile_write_epoch == workspace.profile_write_epoch)
        .map(|manifest| {
            manifest
                .files
                .iter()
                .map(|file| (file.relative_path.clone(), file.sha256.clone()))
                .collect::<HashMap<_, _>>()
        })
        .unwrap_or_default();

    let mut observed = Vec::new();
    collect_core_metadata(&workspace.core_dir, Path::new(""), &mut observed)?;
    observed.sort_by(|left, right| left.relative_path.cmp(&right.relative_path));
    anyhow::ensure!(
        observed.len() <= MAX_PROFILE_FILES,
        "profile contains too many files"
    );

    let observed_hashes = observed
        .iter()
        .map(|file| (file.relative_path.clone(), file.sha256.clone()))
        .collect::<HashMap<_, _>>();
    let observed_paths = observed_hashes.keys().cloned().collect::<HashSet<_>>();
    let mut changed = Vec::new();
    let mut reused_chunk_count = 0_u64;
    let mut deferred_groups = HashSet::new();
    for mut file in observed {
        let prior_hash = previous_by_path.get(&file.relative_path);
        file.changed = prior_hash != Some(&file.sha256);
        if file.changed {
            if is_transactional_database_path(&file.relative_path) {
                deferred_groups.insert(file.database_group.clone());
                if prior_hash.is_none() {
                    continue;
                }
            } else {
                changed.push(file.relative_path.clone());
            }
        } else {
            reused_chunk_count = reused_chunk_count.saturating_add(1);
        }
    }
    let mut deleted_files = previous_by_path
        .keys()
        .filter(|path| !observed_paths.contains(*path))
        .filter(|path| !is_transactional_database_path(path))
        .cloned()
        .collect::<Vec<_>>();
    deleted_files.sort();

    // Re-scan immediately before copy and require exact metadata equality. This bounded
    // transaction barrier prevents a changing file from being committed under an old hash.
    let mut barrier_files = Vec::new();
    collect_core_metadata(&workspace.core_dir, Path::new(""), &mut barrier_files)?;
    barrier_files.sort_by(|left, right| left.relative_path.cmp(&right.relative_path));
    let barrier_hashes = barrier_files
        .iter()
        .map(|file| (file.relative_path.clone(), file.sha256.clone()))
        .collect::<HashMap<_, _>>();
    anyhow::ensure!(
        barrier_hashes == observed_hashes,
        "Profile changed before the Warm Tier transaction barrier"
    );
    let mut eligible_files = barrier_files
        .into_iter()
        .filter(|file| !is_transactional_database_path(&file.relative_path))
        .collect::<Vec<_>>();
    let current_hashes = eligible_files
        .iter()
        .map(|file| (file.relative_path.clone(), file.sha256.clone()))
        .collect::<HashMap<_, _>>();
    let changed = changed
        .into_iter()
        .filter(|path| current_hashes.contains_key(path))
        .collect::<Vec<_>>();
    let uploaded_bytes = eligible_files
        .iter()
        .filter(|file| changed.contains(&file.relative_path))
        .map(|file| file.size)
        .try_fold(0_u64, |total, size| total.checked_add(size))
        .ok_or_else(|| anyhow::anyhow!("Warm Tier delta size overflow"))?;
    anyhow::ensure!(
        uploaded_bytes <= MAX_WARM_TIER_DELTA_BYTES,
        "Warm Tier delta exceeds the bounded sync size"
    );

    let sequence = previous
        .as_ref()
        .map(|manifest| manifest.journal_sequence)
        .unwrap_or_default()
        .saturating_add(1);
    let transaction_barrier = format!(
        "wtb_{}_{}_{}",
        workspace.profile_write_epoch,
        sequence,
        uuid::Uuid::new_v4().simple()
    );
    let staging = journal_root.join(format!(".staging-{transaction_barrier}"));
    let committed = journal_root.join(format!("{sequence:020}"));
    if committed.exists() {
        // Recover commit-last crashes deterministically. A fully committed manifest only missing
        // LATEST can be promoted without copying bytes again. A directory installed before its
        // COMMITTED marker is not visible state and is rebuilt from the active writer workspace.
        match validate_warm_tier_manifest(&warm_root, sequence) {
            Ok(manifest)
                if manifest.tenant_id == workspace.tenant_id
                    && manifest.profile_id == workspace.profile_id
                    && manifest.profile_write_epoch == workspace.profile_write_epoch =>
            {
                atomic_write(
                    &warm_root.join(WARM_TIER_LATEST_FILE),
                    sequence.to_string().as_bytes(),
                )?;
                return Ok(manifest);
            }
            _ => fs::remove_dir_all(&committed)?,
        }
    }
    secure_create_dir_all(&staging)?;

    let result = (|| {
        for path in &changed {
            let metadata = eligible_files
                .iter()
                .find(|file| &file.relative_path == path)
                .ok_or_else(|| anyhow::anyhow!("Warm Tier changed file disappeared"))?;
            let source = workspace.core_dir.join(safe_relative_path(path)?);
            let chunk = chunk_root.join(&metadata.sha256);
            if !chunk.is_file() {
                let temporary = chunk_root.join(format!(".tmp-{}", uuid::Uuid::new_v4().simple()));
                fs::copy(&source, &temporary)?;
                secure_file_permissions(&temporary)?;
                anyhow::ensure!(
                    fs::metadata(&temporary)?.len() == metadata.size
                        && hash_file(&temporary)? == metadata.sha256,
                    "Warm Tier source changed during copy"
                );
                fs::rename(&temporary, &chunk)?;
                sync_directory(&chunk_root)?;
            } else {
                anyhow::ensure!(
                    fs::metadata(&chunk)?.len() == metadata.size
                        && hash_file(&chunk)? == metadata.sha256,
                    "Warm Tier chunk integrity mismatch"
                );
            }
        }
        for file in &mut eligible_files {
            file.changed = changed.contains(&file.relative_path);
        }
        let content_hash = delta_manifest_content_hash(
            workspace.profile_write_epoch,
            sequence,
            &eligible_files,
            &deleted_files,
            &deferred_groups,
        );
        let committed_at_ms = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)?
            .as_millis()
            .try_into()
            .unwrap_or(u64::MAX);
        let mut deferred_groups = deferred_groups.into_iter().collect::<Vec<_>>();
        deferred_groups.sort();
        let manifest = ProfileDeltaJournalManifest {
            tenant_id: workspace.tenant_id.clone(),
            profile_id: workspace.profile_id.clone(),
            profile_write_epoch: workspace.profile_write_epoch,
            journal_sequence: sequence,
            transaction_barrier: transaction_barrier.clone(),
            files: eligible_files,
            deleted_files: deleted_files.clone(),
            changed_file_count: changed.len() as u64,
            deleted_file_count: deleted_files.len() as u64,
            reused_chunk_count,
            uploaded_bytes,
            deferred_groups,
            content_hash: content_hash.clone(),
            committed_at_ms,
            committed: true,
        };
        atomic_write(
            &staging.join(MANIFEST_FILE),
            &serde_json::to_vec_pretty(&manifest)?,
        )?;
        fs::rename(&staging, &committed)?;
        sync_directory(&journal_root)?;
        atomic_write(&committed.join(COMMIT_MARKER_FILE), content_hash.as_bytes())?;
        atomic_write(
            &warm_root.join(WARM_TIER_LATEST_FILE),
            sequence.to_string().as_bytes(),
        )?;
        validate_warm_tier_manifest(&warm_root, sequence)
    })();
    if result.is_err() {
        let _ = fs::remove_dir_all(&staging);
    }
    result
}

fn collect_core_metadata(
    source_root: &Path,
    relative: &Path,
    files: &mut Vec<DeltaJournalFile>,
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
            collect_core_metadata(source_root, &next_relative, files)?;
        } else if file_type.is_file() {
            anyhow::ensure!(
                files.len() < MAX_PROFILE_FILES,
                "profile contains too many files"
            );
            let metadata = entry.metadata()?;
            anyhow::ensure!(
                metadata.len() <= MAX_PROFILE_FILE_BYTES,
                "profile file exceeds size limit"
            );
            let relative_path = path_to_manifest(&next_relative)?;
            files.push(DeltaJournalFile {
                database_group: database_group(&relative_path),
                relative_path,
                size: metadata.len(),
                sha256: hash_file(&entry.path())?,
                changed: false,
            });
        }
    }
    Ok(())
}

fn database_group(path: &str) -> String {
    let lower = path.to_ascii_lowercase();
    if lower.ends_with(".sqlite")
        || lower.ends_with(".sqlite-wal")
        || lower.ends_with(".sqlite-shm")
        || lower.ends_with("/cookies")
        || lower.ends_with("/history")
    {
        "SQLITE".to_owned()
    } else if lower.contains("leveldb/")
        || lower.ends_with("/current")
        || lower.contains("manifest-")
    {
        "LEVELDB".to_owned()
    } else if lower.ends_with("preferences") || lower.ends_with("secure preferences") {
        "PREFERENCES".to_owned()
    } else if lower.contains("extension state/") {
        "EXTENSION_STATE".to_owned()
    } else {
        "FILE".to_owned()
    }
}

fn is_transactional_database_path(path: &str) -> bool {
    matches!(database_group(path).as_str(), "SQLITE" | "LEVELDB")
}

fn delta_manifest_content_hash(
    write_epoch: u64,
    sequence: u64,
    files: &[DeltaJournalFile],
    deleted: &[String],
    deferred: &HashSet<String>,
) -> String {
    let mut hasher = Sha256::new();
    hasher.update(write_epoch.to_be_bytes());
    hasher.update(sequence.to_be_bytes());
    for file in files {
        hasher.update(file.relative_path.as_bytes());
        hasher.update([0]);
        hasher.update(file.size.to_be_bytes());
        hasher.update(file.sha256.as_bytes());
        hasher.update([file.changed as u8]);
    }
    for path in deleted {
        hasher.update(b"delete\0");
        hasher.update(path.as_bytes());
    }
    let mut deferred = deferred.iter().collect::<Vec<_>>();
    deferred.sort();
    for group in deferred {
        hasher.update(b"defer\0");
        hasher.update(group.as_bytes());
    }
    format!("{:x}", hasher.finalize())
}

fn read_latest_warm_tier_manifest(
    warm_root: &Path,
) -> anyhow::Result<Option<ProfileDeltaJournalManifest>> {
    let Some(sequence) = read_optional_text(&warm_root.join(WARM_TIER_LATEST_FILE))? else {
        return Ok(None);
    };
    Ok(Some(validate_warm_tier_manifest(
        warm_root,
        sequence.parse()?,
    )?))
}

fn validate_warm_tier_manifest(
    warm_root: &Path,
    sequence: u64,
) -> anyhow::Result<ProfileDeltaJournalManifest> {
    let committed = warm_root.join("journal").join(format!("{sequence:020}"));
    let manifest: ProfileDeltaJournalManifest =
        serde_json::from_slice(&fs::read(committed.join(MANIFEST_FILE))?)?;
    anyhow::ensure!(
        manifest.committed && manifest.journal_sequence == sequence,
        "Warm Tier manifest identity mismatch"
    );
    anyhow::ensure!(
        manifest.files.len() <= MAX_PROFILE_FILES,
        "Warm Tier manifest has too many files"
    );
    anyhow::ensure!(
        fs::read_to_string(committed.join(COMMIT_MARKER_FILE))?.trim() == manifest.content_hash,
        "Warm Tier commit marker mismatch"
    );
    let deferred = manifest
        .deferred_groups
        .iter()
        .cloned()
        .collect::<HashSet<_>>();
    anyhow::ensure!(
        delta_manifest_content_hash(
            manifest.profile_write_epoch,
            manifest.journal_sequence,
            &manifest.files,
            &manifest.deleted_files,
            &deferred,
        ) == manifest.content_hash,
        "Warm Tier manifest content hash mismatch"
    );
    for file in &manifest.files {
        let chunk = warm_root.join("chunks").join(&file.sha256);
        anyhow::ensure!(
            chunk.is_file()
                && fs::metadata(&chunk)?.len() == file.size
                && hash_file(&chunk)? == file.sha256,
            "Warm Tier chunk integrity mismatch"
        );
    }
    Ok(manifest)
}

fn release_writer_blocking(root: &Path, workspace: &ProfileWorkspace) -> anyhow::Result<()> {
    let profile_root = profile_root(root, &workspace.tenant_id, &workspace.profile_id);
    require_writer(&profile_root, &workspace.session_id)?;
    fs::remove_file(profile_root.join(WRITER_LOCK_FILE))?;
    Ok(())
}

fn resume_workspace_blocking(
    root: &Path,
    tenant_id: &str,
    profile_id: &str,
    session_id: &str,
) -> anyhow::Result<ProfileWorkspace> {
    let profile_root = profile_root(root, tenant_id, profile_id);
    require_writer(&profile_root, session_id)?;
    let workspace_root = profile_root.join("workspaces").join(session_id);
    anyhow::ensure!(
        workspace_root.join("core").is_dir() && workspace_root.join("ephemeral").is_dir(),
        "active profile writer has no reusable workspace"
    );
    let profile_write_epoch = read_u64(&profile_root.join("WRITE_EPOCH"))?;
    anyhow::ensure!(profile_write_epoch > 0, "profile write epoch is missing");
    let restored_checkpoint_id = read_optional_text(&profile_root.join(LATEST_FILE))?;
    Ok(ProfileWorkspace {
        tenant_id: tenant_id.to_owned(),
        profile_id: profile_id.to_owned(),
        session_id: session_id.to_owned(),
        core_dir: workspace_root.join("core"),
        ephemeral_dir: workspace_root.join("ephemeral"),
        profile_write_epoch,
        restore_status: if restored_checkpoint_id.is_some() {
            ProfileRestoreStatus::TechnicalReady
        } else {
            ProfileRestoreStatus::Empty
        },
        restored_checkpoint_id,
    })
}

fn release_writer_by_identity_blocking(
    root: &Path,
    tenant_id: &str,
    profile_id: &str,
    session_id: &str,
) -> anyhow::Result<()> {
    let profile_root = profile_root(root, tenant_id, profile_id);
    let writer = profile_root.join(WRITER_LOCK_FILE);
    match fs::read_to_string(&writer) {
        Ok(owner) => {
            anyhow::ensure!(
                owner.trim() == session_id,
                "profile writer ownership mismatch"
            );
            fs::remove_file(writer)?;
            Ok(())
        }
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(()),
        Err(error) => Err(error.into()),
    }
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
    let manifest = validate_committed_checkpoint(profile_root, checkpoint_id)?;
    let checkpoint = profile_root.join("checkpoints").join(checkpoint_id);
    for file in &manifest.files {
        let relative = safe_relative_path(&file.relative_path)?;
        let source = checkpoint.join("core").join(&relative);
        let target = destination.join(&relative);
        if let Some(parent) = target.parent() {
            secure_create_dir_all(parent)?;
        }
        fs::copy(source, &target)?;
        secure_file_permissions(&target)?;
    }
    Ok(manifest)
}

fn validate_committed_checkpoint(
    profile_root: &Path,
    checkpoint_id: &str,
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
        anyhow::ensure!(
            !file_type.is_symlink(),
            "profile symlinks are not allowed: {}",
            next_relative.display()
        );
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
                | "RunningChromeVersion"
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
    fs::set_permissions(path, fs::Permissions::from_mode(0o770))?;
    Ok(())
}

fn secure_file_permissions(path: &Path) -> anyhow::Result<()> {
    #[cfg(unix)]
    fs::set_permissions(path, fs::Permissions::from_mode(0o660))?;
    Ok(())
}

fn create_private_file(path: &Path) -> std::io::Result<fs::File> {
    let mut options = OpenOptions::new();
    options.write(true).create_new(true);
    #[cfg(unix)]
    options.mode(0o660);
    let file = options.open(path)?;
    #[cfg(unix)]
    file.set_permissions(fs::Permissions::from_mode(0o660))?;
    Ok(file)
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
        #[cfg(unix)]
        std::os::unix::fs::symlink(
            "/Applications/Google Chrome.app",
            workspace.core_dir.join("RunningChromeVersion"),
        )
        .unwrap();

        let manifest = store.checkpoint(&workspace, "runtime-test").await.unwrap();
        assert_eq!(manifest.files.len(), 1);
        assert_eq!(manifest.files[0].relative_path, "Default/Cookies");
        assert!(manifest.committed);
        let packed = store.pack_checkpoint(&manifest).await.unwrap();
        let decoder = zstd::Decoder::new(packed.as_slice()).unwrap();
        let mut archive = tar::Archive::new(decoder);
        let paths = archive
            .entries()
            .unwrap()
            .map(|entry| {
                entry
                    .unwrap()
                    .path()
                    .unwrap()
                    .to_string_lossy()
                    .into_owned()
            })
            .collect::<Vec<_>>();
        assert!(paths.iter().any(|path| path.ends_with("manifest.json")));
        assert!(paths.iter().any(|path| path.ends_with("COMMITTED")));
        assert!(paths
            .iter()
            .any(|path| path.ends_with("core/Default/Cookies")));
        assert!(!paths.iter().any(|path| path.contains("Cache/cache.bin")));
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
    async fn warm_tier_journal_commits_only_stable_file_deltas() {
        let root = temp_root();
        let store = LocalProfileStore::open(root.clone()).await.unwrap();
        let workspace = store
            .acquire_workspace("tenant-test", "profile-test", "session-one")
            .await
            .unwrap();
        fs::create_dir_all(workspace.core_dir.join("Default/Local Storage/leveldb")).unwrap();
        fs::write(
            workspace.core_dir.join("Default/Preferences"),
            b"{\"theme\":1}",
        )
        .unwrap();
        fs::write(workspace.core_dir.join("Default/Cookies"), b"sqlite-live").unwrap();
        fs::write(
            workspace
                .core_dir
                .join("Default/Local Storage/leveldb/CURRENT"),
            b"MANIFEST-1",
        )
        .unwrap();

        let first = store.sync_warm_tier(&workspace).await.unwrap();
        assert_eq!(first.journal_sequence, 1);
        assert_eq!(first.changed_file_count, 1);
        assert_eq!(first.deferred_groups, vec!["LEVELDB", "SQLITE"]);
        assert!(first.committed);

        fs::write(
            workspace.core_dir.join("Default/Preferences"),
            b"{\"theme\":2}",
        )
        .unwrap();
        fs::write(workspace.core_dir.join("Default/NewFile"), b"new-value").unwrap();
        let second = store.sync_warm_tier(&workspace).await.unwrap();
        assert_eq!(second.journal_sequence, 2);
        assert_eq!(second.changed_file_count, 2);
        assert!(second.uploaded_bytes > 0);
        assert_ne!(first.content_hash, second.content_hash);
        assert!(second.transaction_barrier.starts_with("wtb_"));

        fs::remove_file(workspace.core_dir.join("Default/NewFile")).unwrap();
        let third = store.sync_warm_tier(&workspace).await.unwrap();
        assert_eq!(third.deleted_files, vec!["Default/NewFile"]);
        assert_eq!(third.deleted_file_count, 1);
        store.release_writer(&workspace).await.unwrap();
        fs::remove_dir_all(root).unwrap();
    }

    #[tokio::test]
    async fn warm_tier_journal_rejects_a_stale_write_epoch() {
        let root = temp_root();
        let store = LocalProfileStore::open(root.clone()).await.unwrap();
        let first = store
            .acquire_workspace("tenant-test", "profile-test", "session-one")
            .await
            .unwrap();
        fs::write(first.core_dir.join("Preferences"), b"one").unwrap();
        store.sync_warm_tier(&first).await.unwrap();
        store.release_writer(&first).await.unwrap();
        let second = store
            .acquire_workspace("tenant-test", "profile-test", "session-two")
            .await
            .unwrap();
        fs::write(second.core_dir.join("Preferences"), b"two").unwrap();
        let error = store.sync_warm_tier(&first).await.unwrap_err();
        assert!(error.to_string().contains("writer ownership mismatch"));
        let second_sync = store.sync_warm_tier(&second).await.unwrap();
        assert_eq!(second_sync.profile_write_epoch, second.profile_write_epoch);
        assert_eq!(second_sync.journal_sequence, 2);
        store.release_writer(&second).await.unwrap();
        fs::remove_dir_all(root).unwrap();
    }

    #[tokio::test]
    async fn warm_tier_journal_recovers_commit_last_crash_windows() {
        let root = temp_root();
        let store = LocalProfileStore::open(root.clone()).await.unwrap();
        let workspace = store
            .acquire_workspace("tenant-test", "profile-test", "session-one")
            .await
            .unwrap();
        fs::write(workspace.core_dir.join("Preferences"), b"stable").unwrap();
        let first = store.sync_warm_tier(&workspace).await.unwrap();
        let warm_root = profile_root(&root, "tenant-test", "profile-test").join(WARM_TIER_DIR);

        // Crash after COMMITTED but before LATEST: promote the verified existing barrier.
        fs::remove_file(warm_root.join(WARM_TIER_LATEST_FILE)).unwrap();
        let promoted = store.sync_warm_tier(&workspace).await.unwrap();
        assert_eq!(promoted.transaction_barrier, first.transaction_barrier);
        assert_eq!(promoted.journal_sequence, 1);

        // Crash after directory install but before COMMITTED: discard the invisible orphan and
        // rebuild the same sequence from the still-authoritative active writer workspace.
        fs::remove_file(warm_root.join(WARM_TIER_LATEST_FILE)).unwrap();
        fs::remove_file(
            warm_root
                .join("journal")
                .join(format!("{:020}", first.journal_sequence))
                .join(COMMIT_MARKER_FILE),
        )
        .unwrap();
        let rebuilt = store.sync_warm_tier(&workspace).await.unwrap();
        assert_eq!(rebuilt.journal_sequence, 1);
        assert_ne!(rebuilt.transaction_barrier, first.transaction_barrier);
        validate_warm_tier_manifest(&warm_root, 1).unwrap();

        store.release_writer(&workspace).await.unwrap();
        fs::remove_dir_all(root).unwrap();
    }

    #[tokio::test]
    async fn imports_checkpoint_into_a_new_tenant_profile_identity() {
        let root = temp_root();
        let store = LocalProfileStore::open(root.clone()).await.unwrap();
        let source = store
            .acquire_workspace("tenant-source", "profile-source", "session-source")
            .await
            .unwrap();
        fs::create_dir_all(source.core_dir.join("Default")).unwrap();
        fs::write(source.core_dir.join("Default/Cookies"), b"portable-session").unwrap();
        let source_manifest = store.checkpoint(&source, "runtime-source").await.unwrap();
        let archive = store.pack_checkpoint(&source_manifest).await.unwrap();
        store.release_writer(&source).await.unwrap();

        let imported = store
            .import_checkpoint_archive(
                "tenant-target",
                "profile-target",
                "chk_import_1234567890abcdef",
                "runtime-target",
                archive,
            )
            .await
            .unwrap();
        assert_eq!(imported.tenant_id, "tenant-target");
        assert_eq!(imported.profile_id, "profile-target");
        assert_eq!(imported.checkpoint_id, "chk_import_1234567890abcdef");
        assert_eq!(imported.runtime_build_id, "runtime-target");
        assert_eq!(imported.checkpoint_epoch, 1);
        assert_eq!(imported.profile_write_epoch, 0);
        assert_eq!(imported.files.len(), 1);

        let restored = store
            .acquire_workspace("tenant-target", "profile-target", "session-target")
            .await
            .unwrap();
        assert_eq!(
            fs::read(restored.core_dir.join("Default/Cookies")).unwrap(),
            b"portable-session"
        );
        assert_eq!(
            restored.restored_checkpoint_id.as_deref(),
            Some("chk_import_1234567890abcdef")
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
    async fn resumes_after_helper_restart_and_checkpoints_idempotently() {
        let root = temp_root();
        let first_store = LocalProfileStore::open(root.clone()).await.unwrap();
        let workspace = first_store
            .acquire_workspace("tenant-test", "profile-test", "session-one")
            .await
            .unwrap();
        fs::write(workspace.core_dir.join("DurableState"), b"survives").unwrap();

        let restarted_store = LocalProfileStore::open(root.clone()).await.unwrap();
        let resumed = restarted_store
            .resume_workspace("tenant-test", "profile-test", "session-one")
            .await
            .unwrap();
        assert_eq!(resumed.profile_write_epoch, workspace.profile_write_epoch);
        let first_checkpoint = restarted_store
            .checkpoint(&resumed, "runtime-test")
            .await
            .unwrap();
        let retried_checkpoint = restarted_store
            .checkpoint(&resumed, "runtime-test")
            .await
            .unwrap();
        assert_eq!(
            first_checkpoint.checkpoint_id,
            retried_checkpoint.checkpoint_id
        );
        assert_eq!(first_checkpoint.checkpoint_epoch, 1);
        restarted_store
            .release_writer_by_identity("tenant-test", "profile-test", "session-one")
            .await
            .unwrap();
        restarted_store
            .release_writer_by_identity("tenant-test", "profile-test", "session-one")
            .await
            .unwrap();
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

    #[tokio::test]
    async fn installs_verified_archive_for_cross_node_restore() {
        let source_root = temp_root();
        let source = LocalProfileStore::open(source_root.clone()).await.unwrap();
        let workspace = source
            .acquire_workspace("tenant-test", "profile-test", "session-source")
            .await
            .unwrap();
        fs::write(workspace.core_dir.join("Cookies"), b"portable-state").unwrap();
        let manifest = source.checkpoint(&workspace, "runtime-test").await.unwrap();
        let packed = source.pack_checkpoint(&manifest).await.unwrap();
        source.release_writer(&workspace).await.unwrap();

        let target_root = temp_root();
        let target = LocalProfileStore::open(target_root.clone()).await.unwrap();
        let restored_manifest = target
            .install_checkpoint_archive(
                "tenant-test",
                "profile-test",
                &manifest.checkpoint_id,
                packed,
            )
            .await
            .unwrap();
        let restored = target
            .acquire_workspace("tenant-test", "profile-test", "session-target")
            .await
            .unwrap();

        assert_eq!(restored_manifest, manifest);
        assert_eq!(
            fs::read(restored.core_dir.join("Cookies")).unwrap(),
            b"portable-state"
        );
        assert_eq!(
            restored.restored_checkpoint_id.as_deref(),
            Some(manifest.checkpoint_id.as_str())
        );
        target.release_writer(&restored).await.unwrap();
        fs::remove_dir_all(source_root).unwrap();
        fs::remove_dir_all(target_root).unwrap();
    }

    #[tokio::test]
    async fn activates_verified_local_checkpoint_without_object_archive() {
        let root = temp_root();
        let store = LocalProfileStore::open(root.clone()).await.unwrap();
        let workspace = store
            .acquire_workspace("tenant-test", "profile-test", "session-source")
            .await
            .unwrap();
        fs::write(workspace.core_dir.join("Cookies"), b"local-state").unwrap();
        let manifest = store.checkpoint(&workspace, "runtime-test").await.unwrap();
        store.release_writer(&workspace).await.unwrap();
        fs::remove_file(
            root.join("tenants/tenant-test/profiles/profile-test")
                .join(LATEST_FILE),
        )
        .unwrap();

        assert!(store
            .activate_local_checkpoint("tenant-test", "profile-test", &manifest.checkpoint_id,)
            .await
            .unwrap());
        let restored = store
            .acquire_workspace("tenant-test", "profile-test", "session-target")
            .await
            .unwrap();

        assert_eq!(
            fs::read(restored.core_dir.join("Cookies")).unwrap(),
            b"local-state"
        );
        assert_eq!(
            restored.restored_checkpoint_id.as_deref(),
            Some(manifest.checkpoint_id.as_str())
        );
        store.release_writer(&restored).await.unwrap();
        fs::remove_dir_all(root).unwrap();
    }
}
