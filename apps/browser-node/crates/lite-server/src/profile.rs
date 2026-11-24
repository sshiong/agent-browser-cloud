//! Local Profile checkpointing with v2 content-addressed incremental chunk deduplication,
//! disposable-cache exclusion, SHA-256 integrity verification, and v1 `.tar.zst` restore
//! compatibility.

use crate::security::hex_encode;
use anyhow::{bail, ensure, Context, Result};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::collections::HashSet;
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

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct ChunkFileEntry {
    pub rel_path: String,
    pub sha256: String,
    pub raw_bytes: u64,
    pub compressed_bytes: u64,
}

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
    #[serde(default)]
    pub files: Vec<ChunkFileEntry>,
    #[serde(default)]
    pub reused_chunks: u64,
    #[serde(default)]
    pub new_chunks: u64,
    #[serde(default)]
    pub new_bytes: u64,
    #[serde(default)]
    pub total_raw_bytes: u64,
}

fn chunk_path(checkpoints_root: &Path, sha256: &str) -> Result<PathBuf> {
    ensure!(
        sha256.len() == 64 && sha256.bytes().all(|b| b.is_ascii_hexdigit()),
        "PROFILE_CHUNK_HASH_INVALID"
    );
    Ok(checkpoints_root
        .join("_chunks")
        .join(&sha256[..2])
        .join(format!("{sha256}.zst")))
}

fn compute_v2_manifest_digest(files: &[ChunkFileEntry]) -> String {
    let mut hasher = Sha256::new();
    for entry in files {
        hasher.update(entry.rel_path.as_bytes());
        hasher.update(b"\0");
        hasher.update(entry.sha256.as_bytes());
        hasher.update(b"\0");
        hasher.update(entry.raw_bytes.to_string().as_bytes());
        hasher.update(b"\n");
    }
    hex_encode(&hasher.finalize())
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

    let mut discovered = Vec::new();
    collect_filtered_files(profile_dir, Path::new(""), &mut discovered)?;
    discovered.sort_by(|a, b| a.1.cmp(&b.1));

    let mut files = Vec::with_capacity(discovered.len());
    let mut reused_chunks = 0u64;
    let mut new_chunks = 0u64;
    let mut new_bytes = 0u64;
    let mut total_raw_bytes = 0u64;
    let mut total_compressed_bytes = 0u64;
    let mut seen_new_hashes = HashSet::new();

    for (disk_path, rel_path) in discovered {
        let rel_str = rel_path.to_string_lossy().replace('\\', "/");
        ensure!(
            is_safe_relative_path(Path::new(&rel_str)),
            "PROFILE_ARCHIVE_UNSAFE_PATH"
        );
        let (sha256, raw_bytes) = hash_file(&disk_path)?;
        let cpath = chunk_path(checkpoints_root, &sha256)?;
        let compressed_bytes = if cpath.exists() {
            reused_chunks += 1;
            fs::metadata(&cpath)?.len()
        } else {
            if let Some(parent) = cpath.parent() {
                fs::create_dir_all(parent)?;
            }
            let tmp_chunk = cpath.with_extension(format!("zst.tmp.{}", std::process::id()));
            {
                let mut src = File::open(&disk_path)?;
                let dst = File::create(&tmp_chunk)?;
                let mut encoder = zstd::stream::write::Encoder::new(dst, 3)?;
                std::io::copy(&mut src, &mut encoder)?;
                let mut dst = encoder.finish()?;
                dst.flush()?;
            }
            let cbytes = fs::metadata(&tmp_chunk)?.len();
            if cpath.exists() {
                let _ = fs::remove_file(&tmp_chunk);
                reused_chunks += 1;
            } else {
                fs::rename(&tmp_chunk, &cpath)?;
                if seen_new_hashes.insert(sha256.clone()) {
                    new_chunks += 1;
                    new_bytes += cbytes;
                }
            }
            cbytes
        };

        total_raw_bytes += raw_bytes;
        total_compressed_bytes += compressed_bytes;
        files.push(ChunkFileEntry {
            rel_path: rel_str,
            sha256,
            raw_bytes,
            compressed_bytes,
        });
    }

    let archive_sha256 = compute_v2_manifest_digest(&files);
    let manifest = CheckpointManifest {
        version: 2,
        checkpoint_id: checkpoint_id.to_owned(),
        profile_id: profile_id.to_owned(),
        archive_sha256,
        archive_bytes: total_compressed_bytes,
        file_count: files.len() as u64,
        excluded_dirs: DISPOSABLE_CACHE_DIRS
            .iter()
            .map(|s| (*s).to_owned())
            .collect(),
        committed: true,
        files,
        reused_chunks,
        new_chunks,
        new_bytes,
        total_raw_bytes,
    };

    let staging_manifest = target_dir.join(format!("{checkpoint_id}.manifest.json.tmp"));
    let final_manifest = target_dir.join(format!("{checkpoint_id}.manifest.json"));
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

    let manifest: CheckpointManifest =
        serde_json::from_slice(&fs::read(&manifest_path).context("PROFILE_CHECKPOINT_NOT_FOUND")?)?;
    ensure!(
        manifest.committed
            && manifest.profile_id == profile_id
            && manifest.checkpoint_id == checkpoint_id,
        "PROFILE_CHECKPOINT_MANIFEST_INVALID"
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

    let unpack_result: Result<()> = match manifest.version {
        2 => restore_v2_chunks(checkpoints_root, &manifest, &staging_dir),
        1 => restore_v1_archive(&target_dir, &manifest, &staging_dir),
        other => bail!("PROFILE_CHECKPOINT_VERSION_UNSUPPORTED: {other}"),
    };

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

fn restore_v2_chunks(
    checkpoints_root: &Path,
    manifest: &CheckpointManifest,
    staging_dir: &Path,
) -> Result<()> {
    let expected_digest = compute_v2_manifest_digest(&manifest.files);
    ensure!(
        expected_digest == manifest.archive_sha256,
        "PROFILE_CHECKPOINT_INTEGRITY_MISMATCH"
    );

    for entry in &manifest.files {
        let rel = Path::new(&entry.rel_path);
        ensure!(is_safe_relative_path(rel), "PROFILE_ARCHIVE_UNSAFE_PATH");
        let cpath = chunk_path(checkpoints_root, &entry.sha256)?;
        let chunk_file = File::open(&cpath).context("PROFILE_CHUNK_MISSING")?;
        let mut decoder = zstd::stream::read::Decoder::new(chunk_file)?;

        let dest = staging_dir.join(rel);
        if let Some(p) = dest.parent() {
            fs::create_dir_all(p)?;
        }
        let mut out = File::create(&dest)?;
        let mut hasher = Sha256::new();
        let mut buf = [0u8; 16 * 1024];
        let mut written = 0u64;
        loop {
            let n = decoder.read(&mut buf)?;
            if n == 0 {
                break;
            }
            hasher.update(&buf[..n]);
            out.write_all(&buf[..n])?;
            written += n as u64;
        }
        out.flush()?;
        let actual_sha = hex_encode(&hasher.finalize());
        ensure!(
            actual_sha == entry.sha256 && written == entry.raw_bytes,
            "PROFILE_CHUNK_INTEGRITY_MISMATCH"
        );
    }
    Ok(())
}

fn restore_v1_archive(
    target_dir: &Path,
    manifest: &CheckpointManifest,
    staging_dir: &Path,
) -> Result<()> {
    let archive_path = target_dir.join(format!("{}.tar.zst", manifest.checkpoint_id));
    let (actual_sha, actual_bytes) = hash_file(&archive_path)?;
    ensure!(
        actual_sha == manifest.archive_sha256 && actual_bytes == manifest.archive_bytes,
        "PROFILE_CHECKPOINT_INTEGRITY_MISMATCH"
    );
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
}

fn collect_filtered_files(
    disk_dir: &Path,
    rel_prefix: &Path,
    out: &mut Vec<(PathBuf, PathBuf)>,
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
            collect_filtered_files(&disk_path, &rel_path, out)?;
        } else if meta.is_file() {
            out.push((disk_path, rel_path));
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
    fn excludes_disposable_caches_deduplicates_chunks_and_rejects_tampering() {
        let root = std::env::temp_dir().join(format!("lite-prof-{}", Uuid::new_v4().simple()));
        let profile = root.join("profiles/prf_test");
        let checkpoints = root.join("checkpoints");

        fs::create_dir_all(profile.join("Default/Cache")).unwrap();
        fs::create_dir_all(profile.join("Default/Code Cache")).unwrap();
        fs::create_dir_all(profile.join("Default/Local Storage")).unwrap();
        fs::write(profile.join("Default/Cookies"), b"cookie-bytes-v1").unwrap();
        fs::write(profile.join("Default/Local Storage/data.ldb"), b"ls-bytes").unwrap();
        fs::write(profile.join("Default/Cache/junk.bin"), b"disposable-cache").unwrap();
        fs::write(
            profile.join("Default/Code Cache/junk.js"),
            b"disposable-code",
        )
        .unwrap();

        // First checkpoint writes 2 new chunks.
        let m1 = save_profile_checkpoint(&profile, &checkpoints, "prf_test", "chk_001").unwrap();
        assert_eq!(m1.version, 2);
        assert!(m1.committed);
        assert_eq!(m1.file_count, 2);
        assert_eq!(m1.new_chunks, 2);
        assert_eq!(m1.reused_chunks, 0);
        assert!(m1.new_bytes > 0);

        // Second checkpoint with zero changes reuses 100% of chunks and writes 0 new bytes!
        let m2 = save_profile_checkpoint(&profile, &checkpoints, "prf_test", "chk_002").unwrap();
        assert_eq!(m2.file_count, 2);
        assert_eq!(m2.new_chunks, 0);
        assert_eq!(m2.reused_chunks, 2);
        assert_eq!(m2.new_bytes, 0);
        assert_eq!(m2.archive_sha256, m1.archive_sha256);

        // Third checkpoint modifies 1 file -> 1 reused chunk + 1 new chunk.
        fs::write(profile.join("Default/Cookies"), b"cookie-bytes-v2").unwrap();
        let m3 = save_profile_checkpoint(&profile, &checkpoints, "prf_test", "chk_003").unwrap();
        assert_eq!(m3.file_count, 2);
        assert_eq!(m3.reused_chunks, 1);
        assert_eq!(m3.new_chunks, 1);

        // Restore chk_001 brings back v1 cookies and excludes caches.
        fs::remove_dir_all(&profile).unwrap();
        restore_profile_checkpoint(&profile, &checkpoints, "prf_test", "chk_001").unwrap();
        assert_eq!(
            fs::read(profile.join("Default/Cookies")).unwrap(),
            b"cookie-bytes-v1"
        );
        assert_eq!(
            fs::read(profile.join("Default/Local Storage/data.ldb")).unwrap(),
            b"ls-bytes"
        );
        assert!(!profile.join("Default/Cache").exists());
        assert!(!profile.join("Default/Code Cache").exists());

        // Tampering with a stored chunk fails closed and leaves existing profile intact.
        let tampered_chunk = chunk_path(&checkpoints, &m3.files[0].sha256).unwrap();
        let mut tampered_bytes = Vec::new();
        {
            let mut enc = zstd::stream::write::Encoder::new(&mut tampered_bytes, 1).unwrap();
            enc.write_all(b"corrupted-payload").unwrap();
            enc.finish().unwrap();
        }
        fs::write(&tampered_chunk, tampered_bytes).unwrap();
        let err = restore_profile_checkpoint(&profile, &checkpoints, "prf_test", "chk_003")
            .unwrap_err()
            .to_string();
        assert!(
            err.contains("PROFILE_CHUNK_INTEGRITY_MISMATCH"),
            "unexpected error: {err}"
        );
        // Existing profile still preserved on failed restore.
        assert_eq!(
            fs::read(profile.join("Default/Cookies")).unwrap(),
            b"cookie-bytes-v1"
        );

        let _ = fs::remove_dir_all(&root);
    }
}
