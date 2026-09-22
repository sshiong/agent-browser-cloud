use super::{
    hash_file, path_to_manifest, safe_relative_path, secure_create_dir_all,
    secure_file_permissions, ApplicationAwareBarrier, DeltaJournalFile, MAX_PROFILE_FILES,
    MAX_PROFILE_FILE_BYTES,
};
use anyhow::Context;
use rusqlite::backup::{Backup, StepResult};
use rusqlite::{Connection, OpenFlags};
use rusty_leveldb::{LdbIterator, Options, DB};
use sha2::{Digest, Sha256};
use std::collections::{HashMap, HashSet};
use std::fs;
use std::path::{Path, PathBuf};
use std::time::{Duration, Instant};

const SQLITE_HEADER: &[u8; 16] = b"SQLite format 3\0";
const SQLITE_BACKUP_TIMEOUT: Duration = Duration::from_secs(20);
const SQLITE_BACKUP_PAGES_PER_STEP: i32 = 256;
const APPLICATION_ADAPTER_VERSION: &str = "application-aware-v1";

pub(crate) struct ApplicationAwareSnapshot {
    root: PathBuf,
    pub(crate) files: Vec<DeltaJournalFile>,
    pub(crate) sources: HashMap<String, PathBuf>,
    pub(crate) excluded_source_paths: HashSet<String>,
    pub(crate) barriers: Vec<ApplicationAwareBarrier>,
}

impl Drop for ApplicationAwareSnapshot {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.root);
    }
}

pub(crate) fn prepare_application_snapshot(
    core_dir: &Path,
    warm_root: &Path,
    observed: &[DeltaJournalFile],
) -> anyhow::Result<ApplicationAwareSnapshot> {
    let snapshot_root = warm_root.join(format!(
        ".application-snapshot-{}",
        uuid::Uuid::new_v4().simple()
    ));
    secure_create_dir_all(&snapshot_root)?;
    let result = (|| {
        let mut excluded_source_paths = HashSet::new();
        let mut sqlite_bases = HashSet::new();
        let mut leveldb_roots = HashSet::new();
        for file in observed {
            match file.database_group.as_str() {
                "SQLITE" => {
                    excluded_source_paths.insert(file.relative_path.clone());
                    sqlite_bases.insert(sqlite_base_path(&file.relative_path));
                }
                "LEVELDB" => {
                    excluded_source_paths.insert(file.relative_path.clone());
                    leveldb_roots.insert(leveldb_root_path(&file.relative_path)?);
                }
                _ => {}
            }
        }

        let mut files = Vec::new();
        let mut sources = HashMap::new();
        let mut barriers = Vec::new();
        let mut sqlite_bases = sqlite_bases.into_iter().collect::<Vec<_>>();
        sqlite_bases.sort();
        for relative in sqlite_bases {
            let source = core_dir.join(safe_relative_path(&relative)?);
            anyhow::ensure!(source.is_file(), "SQLite database base file is missing");
            require_sqlite_header(&source)?;
            let target = snapshot_root.join(safe_relative_path(&relative)?);
            if let Some(parent) = target.parent() {
                secure_create_dir_all(parent)?;
            }
            let (schema_version, user_version) = snapshot_sqlite(&source, &target)?;
            let metadata = fs::metadata(&target)?;
            anyhow::ensure!(
                metadata.len() <= MAX_PROFILE_FILE_BYTES,
                "SQLite snapshot exceeds the file size limit"
            );
            let sha256 = hash_file(&target)?;
            files.push(DeltaJournalFile {
                relative_path: relative.clone(),
                size: metadata.len(),
                sha256: sha256.clone(),
                database_group: "SQLITE".to_owned(),
                changed: false,
            });
            sources.insert(relative.clone(), target);
            barriers.push(ApplicationAwareBarrier {
                database_group: "SQLITE".to_owned(),
                relative_root: relative,
                adapter_version: APPLICATION_ADAPTER_VERSION.to_owned(),
                source_sequence: format!("schema={schema_version};user={user_version}"),
                file_count: 1,
                content_hash: sha256,
                integrity_state: "VERIFIED".to_owned(),
            });
        }

        let mut leveldb_roots = leveldb_roots.into_iter().collect::<Vec<_>>();
        leveldb_roots.sort();
        for relative_root in leveldb_roots {
            let source_root = core_dir.join(safe_relative_path(&relative_root)?);
            anyhow::ensure!(source_root.is_dir(), "LevelDB group root is missing");
            let before = collect_group_metadata(&source_root, &relative_root)?;
            anyhow::ensure!(!before.is_empty(), "LevelDB group is empty");
            let target_root = snapshot_root.join(safe_relative_path(&relative_root)?);
            copy_leveldb_group(&source_root, &target_root, &before)?;
            let after = collect_group_metadata(&source_root, &relative_root)?;
            anyhow::ensure!(
                metadata_map(&before) == metadata_map(&after),
                "LevelDB source changed before its transaction barrier"
            );
            let current_manifest = validate_leveldb_snapshot(&target_root)?;
            let final_files = collect_group_metadata(&target_root, &relative_root)?;
            anyhow::ensure!(
                files.len().saturating_add(final_files.len()) <= MAX_PROFILE_FILES,
                "application-aware snapshot contains too many files"
            );
            let content_hash = group_content_hash(&final_files);
            for file in &final_files {
                let local = Path::new(&file.relative_path)
                    .strip_prefix(Path::new(&relative_root))
                    .context("LevelDB snapshot path escaped its group")?;
                sources.insert(file.relative_path.clone(), target_root.join(local));
            }
            barriers.push(ApplicationAwareBarrier {
                database_group: "LEVELDB".to_owned(),
                relative_root,
                adapter_version: APPLICATION_ADAPTER_VERSION.to_owned(),
                source_sequence: current_manifest,
                file_count: final_files.len() as u64,
                content_hash,
                integrity_state: "VERIFIED".to_owned(),
            });
            files.extend(final_files);
        }
        files.sort_by(|left, right| left.relative_path.cmp(&right.relative_path));
        barriers.sort_by(|left, right| left.relative_root.cmp(&right.relative_root));
        Ok(ApplicationAwareSnapshot {
            root: snapshot_root.clone(),
            files,
            sources,
            excluded_source_paths,
            barriers,
        })
    })();
    if result.is_err() {
        let _ = fs::remove_dir_all(&snapshot_root);
    }
    result
}

pub(crate) fn verify_restored_application_groups(
    core_dir: &Path,
    barriers: &[ApplicationAwareBarrier],
) -> anyhow::Result<()> {
    for barrier in barriers {
        anyhow::ensure!(
            barrier.adapter_version == APPLICATION_ADAPTER_VERSION
                && barrier.integrity_state == "VERIFIED",
            "Warm Tier application adapter proof is unsupported"
        );
        match barrier.database_group.as_str() {
            "SQLITE" => {
                let database = core_dir.join(safe_relative_path(&barrier.relative_root)?);
                require_sqlite_header(&database)?;
                let connection = Connection::open_with_flags(
                    &database,
                    OpenFlags::SQLITE_OPEN_READ_ONLY | OpenFlags::SQLITE_OPEN_NO_MUTEX,
                )?;
                verify_sqlite_integrity(&connection)?;
                let schema_version: i64 =
                    connection.query_row("PRAGMA schema_version", [], |row| row.get(0))?;
                let user_version: i64 =
                    connection.query_row("PRAGMA user_version", [], |row| row.get(0))?;
                anyhow::ensure!(
                    barrier.source_sequence
                        == format!("schema={schema_version};user={user_version}"),
                    "restored SQLite schema barrier does not match"
                );
                anyhow::ensure!(
                    barrier.file_count == 1 && barrier.content_hash == hash_file(&database)?,
                    "restored SQLite content proof does not match"
                );
            }
            "LEVELDB" => {
                let database = core_dir.join(safe_relative_path(&barrier.relative_root)?);
                let files = collect_group_metadata(&database, &barrier.relative_root)?;
                anyhow::ensure!(
                    barrier.file_count == files.len() as u64
                        && barrier.content_hash == group_content_hash(&files),
                    "restored LevelDB content proof does not match"
                );
                let verification = database
                    .parent()
                    .ok_or_else(|| anyhow::anyhow!("LevelDB group has no parent"))?
                    .join(format!(".leveldb-verify-{}", uuid::Uuid::new_v4().simple()));
                copy_leveldb_group(&database, &verification, &files)?;
                let result = validate_leveldb_snapshot(&verification);
                let _ = fs::remove_dir_all(&verification);
                anyhow::ensure!(
                    result? == barrier.source_sequence,
                    "restored LevelDB sequence barrier does not match"
                );
            }
            _ => anyhow::bail!("Warm Tier application adapter group is unsupported"),
        }
    }
    Ok(())
}

fn sqlite_base_path(path: &str) -> String {
    path.strip_suffix("-wal")
        .or_else(|| path.strip_suffix("-shm"))
        .or_else(|| path.strip_suffix("-journal"))
        .unwrap_or(path)
        .to_owned()
}

fn leveldb_root_path(path: &str) -> anyhow::Result<String> {
    let components = Path::new(path).components().collect::<Vec<_>>();
    if let Some(index) = components.iter().position(|component| {
        component
            .as_os_str()
            .to_string_lossy()
            .eq_ignore_ascii_case("leveldb")
    }) {
        return path_to_manifest(&components[..=index].iter().collect::<PathBuf>());
    }
    Path::new(path)
        .parent()
        .filter(|parent| !parent.as_os_str().is_empty())
        .ok_or_else(|| anyhow::anyhow!("LevelDB file has no bounded group root"))
        .and_then(path_to_manifest)
}

fn require_sqlite_header(path: &Path) -> anyhow::Result<()> {
    use std::io::Read;
    let mut header = [0_u8; 16];
    let bytes_read = fs::File::open(path)?.read(&mut header)?;
    anyhow::ensure!(
        bytes_read == SQLITE_HEADER.len() && &header == SQLITE_HEADER,
        "SQLite database header is invalid"
    );
    Ok(())
}

fn snapshot_sqlite(source: &Path, target: &Path) -> anyhow::Result<(i64, i64)> {
    let source = Connection::open_with_flags(
        source,
        OpenFlags::SQLITE_OPEN_READ_ONLY | OpenFlags::SQLITE_OPEN_NO_MUTEX,
    )?;
    source.busy_timeout(Duration::from_secs(1))?;
    let mut target_connection = Connection::open(target)?;
    {
        let backup = Backup::new(&source, &mut target_connection)?;
        let deadline = Instant::now() + SQLITE_BACKUP_TIMEOUT;
        loop {
            anyhow::ensure!(Instant::now() < deadline, "SQLite online backup timed out");
            match backup.step(SQLITE_BACKUP_PAGES_PER_STEP)? {
                StepResult::Done => break,
                StepResult::More => {}
                StepResult::Busy | StepResult::Locked => {
                    std::thread::sleep(Duration::from_millis(5))
                }
                _ => std::thread::sleep(Duration::from_millis(1)),
            }
        }
    }
    verify_sqlite_integrity(&target_connection)?;
    let schema_version =
        target_connection.query_row("PRAGMA schema_version", [], |row| row.get(0))?;
    let user_version = target_connection.query_row("PRAGMA user_version", [], |row| row.get(0))?;
    target_connection.execute_batch("PRAGMA wal_checkpoint(TRUNCATE)")?;
    drop(target_connection);
    secure_file_permissions(target)?;
    Ok((schema_version, user_version))
}

fn verify_sqlite_integrity(connection: &Connection) -> anyhow::Result<()> {
    let result: String = connection.query_row("PRAGMA integrity_check", [], |row| row.get(0))?;
    anyhow::ensure!(result == "ok", "SQLite integrity check failed");
    Ok(())
}

fn collect_group_metadata(
    root: &Path,
    relative_root: &str,
) -> anyhow::Result<Vec<DeltaJournalFile>> {
    let mut files = Vec::new();
    collect_group_metadata_at(root, Path::new(""), relative_root, &mut files)?;
    files.sort_by(|left, right| left.relative_path.cmp(&right.relative_path));
    Ok(files)
}

fn collect_group_metadata_at(
    root: &Path,
    relative: &Path,
    relative_root: &str,
    files: &mut Vec<DeltaJournalFile>,
) -> anyhow::Result<()> {
    for entry in fs::read_dir(root.join(relative))? {
        let entry = entry?;
        let file_type = entry.file_type()?;
        let next = relative.join(entry.file_name());
        anyhow::ensure!(!file_type.is_symlink(), "LevelDB group contains a symlink");
        if file_type.is_dir() {
            collect_group_metadata_at(root, &next, relative_root, files)?;
        } else if file_type.is_file() && entry.file_name() != "LOCK" {
            anyhow::ensure!(
                files.len() < MAX_PROFILE_FILES,
                "LevelDB group has too many files"
            );
            let metadata = entry.metadata()?;
            anyhow::ensure!(
                metadata.len() <= MAX_PROFILE_FILE_BYTES,
                "LevelDB file exceeds the size limit"
            );
            let relative_path = Path::new(relative_root).join(&next);
            files.push(DeltaJournalFile {
                relative_path: path_to_manifest(&relative_path)?,
                size: metadata.len(),
                sha256: hash_file(&entry.path())?,
                database_group: "LEVELDB".to_owned(),
                changed: false,
            });
        }
    }
    Ok(())
}

fn copy_leveldb_group(
    source_root: &Path,
    target_root: &Path,
    files: &[DeltaJournalFile],
) -> anyhow::Result<()> {
    if target_root.exists() {
        fs::remove_dir_all(target_root)?;
    }
    secure_create_dir_all(target_root)?;
    let relative_root = common_group_root(files)?;
    for file in files {
        let relative = Path::new(&file.relative_path)
            .strip_prefix(&relative_root)
            .context("LevelDB file escaped its group")?;
        let source = source_root.join(relative);
        let target = target_root.join(relative);
        if let Some(parent) = target.parent() {
            secure_create_dir_all(parent)?;
        }
        fs::copy(&source, &target)?;
        secure_file_permissions(&target)?;
        anyhow::ensure!(
            fs::metadata(&target)?.len() == file.size && hash_file(&target)? == file.sha256,
            "LevelDB source changed during copy"
        );
    }
    Ok(())
}

fn common_group_root(files: &[DeltaJournalFile]) -> anyhow::Result<PathBuf> {
    let first = files
        .first()
        .ok_or_else(|| anyhow::anyhow!("LevelDB group is empty"))?;
    let path = Path::new(&first.relative_path);
    let components = path.components().collect::<Vec<_>>();
    let index = components
        .iter()
        .position(|component| {
            component
                .as_os_str()
                .to_string_lossy()
                .eq_ignore_ascii_case("leveldb")
        })
        .unwrap_or_else(|| components.len().saturating_sub(2));
    Ok(components[..=index].iter().collect())
}

fn validate_leveldb_snapshot(root: &Path) -> anyhow::Result<String> {
    anyhow::ensure!(
        root.join("CURRENT").is_file(),
        "LevelDB CURRENT pointer is missing"
    );
    let current = fs::read_to_string(root.join("CURRENT"))?;
    let current = current.trim();
    anyhow::ensure!(
        !current.is_empty()
            && current.len() <= 128
            && current.starts_with("MANIFEST-")
            && current
                .bytes()
                .all(|byte| byte.is_ascii_alphanumeric() || byte == b'-'),
        "LevelDB CURRENT pointer is invalid"
    );
    anyhow::ensure!(root.join(current).is_file(), "LevelDB MANIFEST is missing");
    let options = Options {
        create_if_missing: false,
        paranoid_checks: true,
        reuse_logs: true,
        reuse_manifest: true,
        ..Options::default()
    };
    let mut database = DB::open(root, options)
        .map_err(|error| anyhow::anyhow!("LevelDB restore-open verification failed: {error}"))?;
    let mut iterator = database
        .new_iter()
        .map_err(|error| anyhow::anyhow!("LevelDB iterator verification failed: {error}"))?;
    while iterator.advance() {
        anyhow::ensure!(
            iterator.current().is_some(),
            "LevelDB iterator returned an invalid entry"
        );
    }
    drop(iterator);
    drop(database);
    let lock = root.join("LOCK");
    if lock.exists() {
        fs::remove_file(lock)?;
    }
    let verified_current = fs::read_to_string(root.join("CURRENT"))?;
    let verified_current = verified_current.trim();
    anyhow::ensure!(
        !verified_current.is_empty()
            && verified_current.len() <= 128
            && verified_current.starts_with("MANIFEST-")
            && root.join(verified_current).is_file(),
        "verified LevelDB CURRENT pointer is invalid"
    );
    Ok(verified_current.to_owned())
}

fn metadata_map(files: &[DeltaJournalFile]) -> HashMap<&str, (u64, &str)> {
    files
        .iter()
        .map(|file| {
            (
                file.relative_path.as_str(),
                (file.size, file.sha256.as_str()),
            )
        })
        .collect()
}

fn group_content_hash(files: &[DeltaJournalFile]) -> String {
    let mut hasher = Sha256::new();
    for file in files {
        hasher.update(file.relative_path.as_bytes());
        hasher.update([0]);
        hasher.update(file.size.to_be_bytes());
        hasher.update(file.sha256.as_bytes());
    }
    format!("{:x}", hasher.finalize())
}
