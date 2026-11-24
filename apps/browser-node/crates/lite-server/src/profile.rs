//! Local Profile checkpointing with disposable-cache exclusion and SHA-256 integrity manifests.

use crate::security::hex_encode;
use anyhow::{ensure, Context, Result};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::fs::{self, File};
use std::io::{Read, Write};
use std::path::{Component, Path, PathBuf};

/// Disposable Chromium cache directories excluded from Profile checkpoints (Section 5.5).
/// Critical State (Cookies, Local Storage, IndexedDB, Preferences, Session Storage) and
/// Conditional State (Service Worker, CacheStorage, Extensions) are preserved.
const DISPOSABLE_CACHE_DIRS: &[&str] = &[
    "Cache",
    "Code Cache",
    "GPUCache",
    "DawnCache",
    "DawnGraphiteCache",
    "DawnWebGPUCache",
    "GrShaderCache",
    "ShaderCache",
    "GraphiteDawnCache",
    "Crashpad",
    "BrowserMetrics",
    "component_crx_cache",
    "Safe Browsing",
];

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CheckpointManifest {
    pub version: u32,
    pub checkpoint_id: String,
    pub profile_id: String,
    pub archive_sha256: String,
    pub archive_bytes: u64,
    pub file_count: u64,
    pub excluded_dirs: Vec<String>,
    pub committed: bool,
}

pub fn save_profile_checkpoint(
    profile_dir: &Path,
    checkpoints_root: &Path,
    profile_id: &str,
    checkpoint_id: &str,
) -> Result<CheckpointManifest> {
    fs::create_dir_all(profile_dir)?;
    let target_dir = checkpoints_root.join(profile_id);
    fs::create_dir_all(&target_dir)?;

    let staging_archive = target_dir.join(format!("{checkpoint_id}.tar.zst.tmp"));
    let final_archive = target_dir.join(format!("{checkpoint_id}.tar.zst"));
    let staging_manifest = target_dir.join(format!("{checkpoint_id}.manifest.json.tmp"));
    let final_manifest = target_dir.join(format!("{checkpoint_id}.manifest.json"));

    let file_count = {
        let out = File::create(&staging_archive)?;
        let encoder = zstd::stream::write::Encoder::new(out, 3)?;
        let mut tar = tar::Builder::new(encoder);
        let mut count = 0u64;
        append_filtered_dir(&mut tar, profile_dir, Path::new(""), &mut count)?;
        let encoder = tar.into_inner()?;
        let mut out = encoder.finish()?;
        out.flush()?;
        count
    };

    let (archive_sha256, archive_bytes) = hash_file(&staging_archive)?;
    fs::rename(&staging_archive, &final_archive)?;

    let manifest = CheckpointManifest {
        version: 1,
        checkpoint_id: checkpoint_id.to_owned(),
        profile_id: profile_id.to_owned(),
        archive_sha256,
        archive_bytes,
        file_count,
        excluded_dirs: DISPOSABLE_CACHE_DIRS
            .iter()
            .map(|s| (*s).to_owned())
            .collect(),
        committed: true,
    };
    fs::write(&staging_manifest, serde_json::to_vec_pretty(&manifest)?)?;
    fs::rename(&staging_manifest, &final_manifest)?;
    Ok(manifest)
}

pub fn restore_profile_checkpoint(
    profile_dir: &Path,
    checkpoints_root: &Path,
    profile_id: &str,
    checkpoint_id: &str,
) -> Result<CheckpointManifest> {
    let target_dir = checkpoints_root.join(profile_id);
    let manifest_path = target_dir.join(format!("{checkpoint_id}.manifest.json"));
    let archive_path = target_dir.join(format!("{checkpoint_id}.tar.zst"));

    let manifest: CheckpointManifest =
        serde_json::from_slice(&fs::read(&manifest_path).context("PROFILE_CHECKPOINT_NOT_FOUND")?)?;
    ensure!(
        manifest.committed
            && manifest.profile_id == profile_id
            && manifest.checkpoint_id == checkpoint_id,
        "PROFILE_CHECKPOINT_MANIFEST_INVALID"
    );

    let (actual_sha, actual_bytes) = hash_file(&archive_path)?;
    ensure!(
        actual_sha == manifest.archive_sha256 && actual_bytes == manifest.archive_bytes,
        "PROFILE_CHECKPOINT_INTEGRITY_MISMATCH"
    );

    // Stage restoration into a sibling temporary directory first so an error never corrupts the
    // existing profile directory.
    let parent = profile_dir
        .parent()
        .ok_or_else(|| anyhow::anyhow!("invalid profile_dir"))?;
    fs::create_dir_all(parent)?;
    let staging_dir = parent.join(format!(".restore-{profile_id}-{checkpoint_id}"));
    if staging_dir.exists() {
        fs::remove_dir_all(&staging_dir)?;
    }
    fs::create_dir_all(&staging_dir)?;

    let unpack_result: Result<()> = (|| {
        let file = File::open(&archive_path)?;
        let decoder = zstd::stream::read::Decoder::new(file)?;
        let mut archive = tar::Archive::new(decoder);
        for entry in archive.entries()? {
            let mut entry = entry?;
            let rel = entry.path()?.into_owned();
            ensure!(is_safe_relative_path(&rel), "PROFILE_ARCHIVE_UNSAFE_PATH");
            let dest = staging_dir.join(&rel);
            if let Some(p) = dest.parent() {
                fs::create_dir_all(p)?;
            }
            entry.unpack(&dest)?;
        }
        Ok(())
    })();

    if let Err(error) = unpack_result {
        let _ = fs::remove_dir_all(&staging_dir);
        return Err(error);
    }

    let backup_dir = parent.join(format!(".backup-{profile_id}-{checkpoint_id}"));
    if backup_dir.exists() {
        let _ = fs::remove_dir_all(&backup_dir);
    }
    let had_existing = profile_dir.exists();
    if had_existing {
        fs::rename(profile_dir, &backup_dir)?;
    }
    if let Err(error) = fs::rename(&staging_dir, profile_dir) {
        if had_existing {
            let _ = fs::rename(&backup_dir, profile_dir);
        }
        let _ = fs::remove_dir_all(&staging_dir);
        return Err(error.into());
    }
    if had_existing {
        let _ = fs::remove_dir_all(&backup_dir);
    }
    Ok(manifest)
}

fn append_filtered_dir<W: Write>(
    tar: &mut tar::Builder<W>,
    disk_dir: &Path,
    rel_prefix: &Path,
    count: &mut u64,
) -> Result<()> {
    for entry in fs::read_dir(disk_dir)? {
        let entry = entry?;
        let file_name = entry.file_name();
        let name_str = file_name.to_string_lossy();
        if DISPOSABLE_CACHE_DIRS.iter().any(|skip| *skip == name_str) {
            continue;
        }
        // Skip Chromium singleton lock sockets/files.
        if name_str.starts_with("Singleton") || name_str.ends_with(".lock") {
            continue;
        }
        let disk_path = entry.path();
        let rel_path: PathBuf = if rel_prefix.as_os_str().is_empty() {
            PathBuf::from(&file_name)
        } else {
            rel_prefix.join(&file_name)
        };
        let meta = fs::symlink_metadata(&disk_path)?;
        if meta.file_type().is_symlink() {
            continue;
        }
        if meta.is_dir() {
            append_filtered_dir(tar, &disk_path, &rel_path, count)?;
        } else if meta.is_file() {
            tar.append_path_with_name(&disk_path, &rel_path)?;
            *count += 1;
        }
    }
    Ok(())
}

fn is_safe_relative_path(path: &Path) -> bool {
    !path.is_absolute()
        && path
            .components()
            .all(|c| matches!(c, Component::Normal(_) | Component::CurDir))
}

fn hash_file(path: &Path) -> Result<(String, u64)> {
    let mut file = File::open(path)?;
    let mut hasher = Sha256::new();
    let mut buf = [0u8; 16 * 1024];
    let mut total = 0u64;
    loop {
        let n = file.read(&mut buf)?;
        if n == 0 {
            break;
        }
        hasher.update(&buf[..n]);
        total += n as u64;
    }
    Ok((hex_encode(&hasher.finalize()), total))
}

#[cfg(test)]
mod tests {
    use super::*;
    use uuid::Uuid;

    #[test]
    fn excludes_disposable_caches_and_preserves_critical_state_on_restore() {
        let root = std::env::temp_dir().join(format!("lite-prof-{}", Uuid::new_v4().simple()));
        let profile = root.join("profiles/prf_test");
        let checkpoints = root.join("checkpoints");

        fs::create_dir_all(profile.join("Default/Cache")).unwrap();
        fs::create_dir_all(profile.join("Default/Code Cache")).unwrap();
        fs::create_dir_all(profile.join("Default/Local Storage")).unwrap();
        fs::write(profile.join("Default/Cookies"), b"cookie-bytes").unwrap();
        fs::write(profile.join("Default/Local Storage/data.ldb"), b"ls-bytes").unwrap();
        fs::write(profile.join("Default/Cache/junk.bin"), b"disposable-cache").unwrap();
        fs::write(
            profile.join("Default/Code Cache/junk.js"),
            b"disposable-code",
        )
        .unwrap();

        let manifest =
            save_profile_checkpoint(&profile, &checkpoints, "prf_test", "chk_001").unwrap();
        assert!(manifest.committed);
        assert_eq!(manifest.file_count, 2);

        fs::remove_dir_all(&profile).unwrap();
        restore_profile_checkpoint(&profile, &checkpoints, "prf_test", "chk_001").unwrap();

        assert_eq!(
            fs::read(profile.join("Default/Cookies")).unwrap(),
            b"cookie-bytes"
        );
        assert_eq!(
            fs::read(profile.join("Default/Local Storage/data.ldb")).unwrap(),
            b"ls-bytes"
        );
        assert!(!profile.join("Default/Cache").exists());
        assert!(!profile.join("Default/Code Cache").exists());

        let _ = fs::remove_dir_all(&root);
    }
}
