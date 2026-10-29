//! Browser Node SQLite Journal。
//!
//! Journal 是 Node 的本地恢复权威：命令副作用完成后，先持久化 ACK 与待投事件，
//! 再向 Control Plane 发布。进程在两者之间崩溃时，重启后会继续重投事件。

use anyhow::Context;
use helper_contracts::{
    RecordingHeldUploadFinalization, RecordingUploadHoldScope,
    RecordingUploadJournalAcknowledgement, RecordingUploadReceipt, StorageRecordingUploadSeal,
};
use rusqlite::{params, Connection, OptionalExtension, TransactionBehavior};
use serde::{Deserialize, Serialize};
use std::path::{Path, PathBuf};

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(deny_unknown_fields)]
pub struct RecordingUploadSealRequest {
    pub scope: RecordingUploadHoldScope,
    pub finalization: RecordingHeldUploadFinalization,
    pub expected_scope_sha256: String,
}

/// Local immutable evidence only; does not authorize releasing a cloud Hold.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(deny_unknown_fields)]
pub struct PersistedRecordingUploadSeal {
    pub scope: RecordingUploadHoldScope,
    pub finalization: RecordingHeldUploadFinalization,
    pub seal: StorageRecordingUploadSeal,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct PersistedAcknowledgement {
    pub message_id: String,
    pub accepted: bool,
    pub error_code: String,
    pub error_message: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct PersistedCommandResult {
    pub acknowledgement: PersistedAcknowledgement,
    pub event_id: Option<String>,
    pub event_payload: Option<Vec<u8>>,
    pub event_delivered: bool,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TermDecision {
    Accepted,
    Stale { current_term: i64 },
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum RouteDecision {
    Accepted,
    LegacyAccepted,
    Missing,
    Stale { current_epoch: i64 },
    ShardMismatch { current_shard: i32 },
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum CommandFenceDecision {
    Accepted,
    LegacyAccepted,
    RouteMissing,
    RouteStale { current_epoch: i64 },
    ShardMismatch { current_shard: i32 },
    TermStale { current_term: i64 },
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct RuntimeLease {
    pub session_id: String,
    pub tenant_id: String,
    pub runtime_build_id: String,
    pub coordinator_term: i64,
    pub context_epoch: i64,
    pub browser_generation: u64,
    pub pid: u32,
    pub process_started_at: u64,
}

/// 每次操作打开一个短生命周期连接，避免跨 Tokio 线程共享 rusqlite Connection。
#[derive(Debug, Clone)]
pub struct SqliteNodeJournal {
    database_path: PathBuf,
}

impl SqliteNodeJournal {
    pub async fn open(database_path: impl AsRef<Path>) -> anyhow::Result<Self> {
        let database_path = database_path.as_ref().to_path_buf();
        if let Some(parent) = database_path.parent() {
            tokio::fs::create_dir_all(parent).await?;
        }
        let journal = Self { database_path };
        journal
            .with_connection(|connection| {
                connection.execute_batch(
                    r#"
                    PRAGMA journal_mode = WAL;
                    PRAGMA synchronous = FULL;
                    PRAGMA foreign_keys = ON;
                    CREATE TABLE IF NOT EXISTS command_results (
                        message_id TEXT PRIMARY KEY,
                        accepted INTEGER NOT NULL,
                        error_code TEXT NOT NULL,
                        error_message TEXT NOT NULL,
                        event_id TEXT UNIQUE,
                        event_payload BLOB,
                        event_delivered INTEGER NOT NULL DEFAULT 0,
                        created_at_ms INTEGER NOT NULL
                    );
                    CREATE TABLE IF NOT EXISTS coordinator_terms (
                        session_id TEXT PRIMARY KEY,
                        coordinator_term INTEGER NOT NULL
                    );
                    CREATE TABLE IF NOT EXISTS coordinator_routes (
                        session_id TEXT PRIMARY KEY,
                        route_epoch INTEGER NOT NULL,
                        coordinator_shard_id INTEGER NOT NULL
                    );
                    CREATE TABLE IF NOT EXISTS event_sequences (
                        session_id TEXT PRIMARY KEY,
                        sequence INTEGER NOT NULL
                    );
                    CREATE TABLE IF NOT EXISTS runtime_leases (
                        session_id TEXT PRIMARY KEY,
                        tenant_id TEXT NOT NULL,
                        runtime_build_id TEXT NOT NULL,
                        coordinator_term INTEGER NOT NULL,
                        context_epoch INTEGER NOT NULL,
                        browser_generation INTEGER NOT NULL,
                        pid INTEGER NOT NULL,
                        process_started_at INTEGER NOT NULL,
                        active INTEGER NOT NULL,
                        updated_at_ms INTEGER NOT NULL
                    );
                    CREATE INDEX IF NOT EXISTS idx_command_results_pending_event
                        ON command_results(event_delivered, created_at_ms)
                        WHERE event_payload IS NOT NULL;
                    CREATE TABLE IF NOT EXISTS recording_upload_receipts (
                        tenant_id TEXT NOT NULL, session_id TEXT NOT NULL, recording_id TEXT NOT NULL,
                        segment_sequence INTEGER NOT NULL CHECK (segment_sequence BETWEEN 0 AND 99999),
                        receipt_json BLOB NOT NULL CHECK (length(receipt_json) BETWEEN 1 AND 16384),
                        receipt_sha256 TEXT NOT NULL CHECK (length(receipt_sha256) = 64),
                        PRIMARY KEY(tenant_id, session_id, recording_id, segment_sequence)
                    );
                    CREATE TABLE IF NOT EXISTS recording_upload_seal_requests (
                        tenant_id TEXT NOT NULL, session_id TEXT NOT NULL, recording_id TEXT NOT NULL,
                        request_json BLOB NOT NULL CHECK(length(request_json) BETWEEN 1 AND 16384),
                        PRIMARY KEY(tenant_id, session_id, recording_id)
                    );
                    CREATE TRIGGER IF NOT EXISTS recording_upload_seal_request_no_update
                        BEFORE UPDATE ON recording_upload_seal_requests BEGIN
                        SELECT RAISE(ABORT, 'RECORDING_UPLOAD_SEAL_REQUEST_IMMUTABLE'); END;
                    CREATE TRIGGER IF NOT EXISTS recording_upload_seal_request_no_delete
                        BEFORE DELETE ON recording_upload_seal_requests BEGIN
                        SELECT RAISE(ABORT, 'RECORDING_UPLOAD_SEAL_REQUEST_IMMUTABLE'); END;
                    CREATE TRIGGER IF NOT EXISTS recording_upload_prepared_no_new_segment
                        BEFORE INSERT ON recording_upload_receipts
                        WHEN EXISTS(SELECT 1 FROM recording_upload_seal_requests s WHERE s.tenant_id=NEW.tenant_id
                            AND s.session_id=NEW.session_id AND s.recording_id=NEW.recording_id)
                        AND NOT EXISTS(SELECT 1 FROM recording_upload_receipts r WHERE r.tenant_id=NEW.tenant_id
                            AND r.session_id=NEW.session_id AND r.recording_id=NEW.recording_id
                            AND r.segment_sequence=NEW.segment_sequence)
                        BEGIN SELECT RAISE(ABORT, 'RECORDING_UPLOAD_SEAL_PREPARED'); END;
                    CREATE TABLE IF NOT EXISTS recording_upload_seals (
                        tenant_id TEXT NOT NULL, session_id TEXT NOT NULL, recording_id TEXT NOT NULL,
                        seal_json BLOB NOT NULL CHECK(length(seal_json) BETWEEN 1 AND 16384),
                        PRIMARY KEY(tenant_id, session_id, recording_id)
                    );
                    CREATE TRIGGER IF NOT EXISTS recording_upload_seal_no_update
                        BEFORE UPDATE ON recording_upload_seals BEGIN
                        SELECT RAISE(ABORT, 'RECORDING_UPLOAD_SEAL_IMMUTABLE'); END;
                    CREATE TRIGGER IF NOT EXISTS recording_upload_seal_no_delete
                        BEFORE DELETE ON recording_upload_seals BEGIN
                        SELECT RAISE(ABORT, 'RECORDING_UPLOAD_SEAL_IMMUTABLE'); END;
                    CREATE TRIGGER IF NOT EXISTS recording_upload_sealed_no_new_segment
                        BEFORE INSERT ON recording_upload_receipts
                        WHEN EXISTS(SELECT 1 FROM recording_upload_seals s WHERE s.tenant_id=NEW.tenant_id
                            AND s.session_id=NEW.session_id AND s.recording_id=NEW.recording_id)
                        AND NOT EXISTS(SELECT 1 FROM recording_upload_receipts r WHERE r.tenant_id=NEW.tenant_id
                            AND r.session_id=NEW.session_id AND r.recording_id=NEW.recording_id
                            AND r.segment_sequence=NEW.segment_sequence)
                        BEGIN SELECT RAISE(ABORT, 'RECORDING_UPLOAD_ALREADY_SEALED'); END;
                    CREATE TRIGGER IF NOT EXISTS recording_upload_receipt_no_update
                        BEFORE UPDATE ON recording_upload_receipts BEGIN
                        SELECT RAISE(ABORT, 'RECORDING_UPLOAD_RECEIPT_IMMUTABLE'); END;
                    CREATE TRIGGER IF NOT EXISTS recording_upload_receipt_no_delete
                        BEFORE DELETE ON recording_upload_receipts BEGIN
                        SELECT RAISE(ABORT, 'RECORDING_UPLOAD_RECEIPT_IMMUTABLE'); END;
                    "#,
                )?;
                Ok(())
            })
            .await?;
        Ok(journal)
    }

    /// Return an ACK only after an immutable, FULL-synchronous SQLite commit.
    /// This attests local receipt persistence, not Control Plane upload admission.
    pub async fn record_recording_upload_receipt(
        &self,
        receipt: &RecordingUploadReceipt,
    ) -> anyhow::Result<RecordingUploadJournalAcknowledgement> {
        let hash = receipt.sha256()?;
        let receipt = receipt.clone();
        self.with_connection(move |mut connection| {
            connection.pragma_update(None, "synchronous", "FULL")?;
            let transaction = connection.transaction_with_behavior(TransactionBehavior::Immediate)?;
            let s = &receipt.scope;
            let prior: Option<(Vec<u8>, String)> = transaction.query_row(
                "SELECT receipt_json,receipt_sha256 FROM recording_upload_receipts WHERE tenant_id=? AND session_id=? AND recording_id=? LIMIT 1",
                params![s.tenant_id, s.session_id, s.recording_id], |row| Ok((row.get(0)?,row.get(1)?))).optional()?;
            if let Some((bytes, stored_hash)) = prior {
                let prior: RecordingUploadReceipt = serde_json::from_slice(&bytes)?;
                anyhow::ensure!(prior.sha256()? == stored_hash && prior.scope == *s
                    && prior.proof.scope_sha256 == receipt.proof.scope_sha256,
                    "RECORDING_UPLOAD_JOURNAL_SCOPE_FENCED");
            }
            let bytes = serde_json::to_vec(&receipt)?;
            transaction.execute("INSERT INTO recording_upload_receipts VALUES(?,?,?,?,?,?) ON CONFLICT DO NOTHING",
                params![s.tenant_id, s.session_id, s.recording_id, receipt.segment.sequence, bytes, hash])?;
            let (stored, stored_hash): (Vec<u8>, String) = transaction.query_row(
                "SELECT receipt_json,receipt_sha256 FROM recording_upload_receipts WHERE tenant_id=? AND session_id=? AND recording_id=? AND segment_sequence=?",
                params![s.tenant_id, s.session_id, s.recording_id, receipt.segment.sequence],
                |row| Ok((row.get(0)?,row.get(1)?)))?;
            anyhow::ensure!(stored == bytes && stored_hash == hash, "RECORDING_UPLOAD_JOURNAL_RECEIPT_FENCED");
            transaction.commit()?;
            Ok(RecordingUploadJournalAcknowledgement { receipt, journal_receipt_sha256: hash })
        }).await
    }

    /// Read the archive binding from a validated, durable first segment receipt.
    pub async fn recording_upload_scope_hash(
        &self,
        scope: &RecordingUploadHoldScope,
    ) -> anyhow::Result<String> {
        let scope = scope.clone();
        self.with_connection(move |connection| {
            let (bytes,hash):(Vec<u8>,String)=connection.query_row("SELECT receipt_json,receipt_sha256 FROM recording_upload_receipts WHERE tenant_id=? AND session_id=? AND recording_id=? AND segment_sequence=0",
                params![scope.tenant_id,scope.session_id,scope.recording_id],|r|Ok((r.get(0)?,r.get(1)?)))?;
            let receipt:RecordingUploadReceipt=serde_json::from_slice(&bytes)?;
            anyhow::ensure!(receipt.scope==scope && receipt.sha256()?==hash,"RECORDING_UPLOAD_JOURNAL_SCOPE_FENCED");
            Ok(receipt.proof.scope_sha256)
        }).await
    }

    /// Commit the exact request before contacting Helper. Unknown outcomes retain it forever.
    pub async fn prepare_recording_upload_seal(
        &self,
        request: &RecordingUploadSealRequest,
    ) -> anyhow::Result<()> {
        let request = request.clone();
        let bytes = serde_json::to_vec(&request)?;
        let f = &request.finalization;
        anyhow::ensure!(
            bytes.len() <= 16384
                && (1..=100_000).contains(&f.segment_count)
                && f.redaction_policy_version == 2
                && f.frame_count > 0
                && f.started_at_ms <= f.ended_at_ms,
            "RECORDING_UPLOAD_SEAL_REQUEST_REJECTED"
        );
        self.with_connection(move |mut connection| {
            connection.pragma_update(None,"synchronous","FULL")?;
            let transaction=connection.transaction_with_behavior(TransactionBehavior::Immediate)?;
            let s=&request.scope;
            validate_seal_receipts(&transaction,s,&request.finalization,&request.expected_scope_sha256)?;
            let complete:Option<Vec<u8>>=transaction.query_row("SELECT seal_json FROM recording_upload_seals WHERE tenant_id=? AND session_id=? AND recording_id=?",params![s.tenant_id,s.session_id,s.recording_id],|r|r.get(0)).optional()?;
            if let Some(complete)=complete {
                let complete:PersistedRecordingUploadSeal=serde_json::from_slice(&complete)?;
                anyhow::ensure!(complete.scope==*s && complete.finalization==request.finalization && complete.seal.proof.scope_sha256==request.expected_scope_sha256,"RECORDING_UPLOAD_SEAL_REQUEST_CONFLICT");
            }
            transaction.execute("INSERT INTO recording_upload_seal_requests VALUES(?,?,?,?) ON CONFLICT DO NOTHING",params![s.tenant_id,s.session_id,s.recording_id,bytes])?;
            let stored:Vec<u8>=transaction.query_row("SELECT request_json FROM recording_upload_seal_requests WHERE tenant_id=? AND session_id=? AND recording_id=?",params![s.tenant_id,s.session_id,s.recording_id],|r|r.get(0))?;
            anyhow::ensure!(stored==bytes,"RECORDING_UPLOAD_SEAL_REQUEST_CONFLICT");
            transaction.commit()?;
            Ok(())
        }).await
    }

    pub async fn recording_upload_seal_request(
        &self,
        tenant: &str,
        session: &str,
        recording: &str,
    ) -> anyhow::Result<Option<RecordingUploadSealRequest>> {
        let (tenant, session, recording) =
            (tenant.to_owned(), session.to_owned(), recording.to_owned());
        self.with_connection(move |connection| {
            let bytes:Option<Vec<u8>>=connection.query_row("SELECT request_json FROM recording_upload_seal_requests WHERE tenant_id=? AND session_id=? AND recording_id=?",params![tenant,session,recording],|r|r.get(0)).optional()?;
            bytes.map(|b|serde_json::from_slice(&b).map_err(Into::into)).transpose()
        }).await
    }

    /// Persist only when every contiguous segment receipt agrees with the sealed inventory.
    /// The shared SQLite write lock orders sealing against concurrent segment registration.
    pub async fn record_recording_upload_seal(
        &self,
        evidence: &PersistedRecordingUploadSeal,
    ) -> anyhow::Result<()> {
        let evidence = evidence.clone();
        let bytes = serde_json::to_vec(&evidence)?;
        anyhow::ensure!(bytes.len() <= 16384, "RECORDING_UPLOAD_SEAL_BOUND");
        let s = &evidence.scope;
        let f = &evidence.finalization;
        let seal = &evidence.seal;
        let p = &seal.proof;
        let hash = |v: &str| {
            v.len() == 64
                && v.bytes()
                    .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
        };
        anyhow::ensure!(
            seal.operation_id == s.operation_id
                && seal.authority_epoch == s.authority_epoch
                && seal.placement_epoch == s.placement_epoch
                && p.protocol_version == 2
                && (1..=100_000).contains(&f.segment_count)
                && p.segment_count == f.segment_count
                && p.object_version_count == 2 * f.segment_count + 1
                && (1..=16384).contains(&p.manifest_bytes)
                && hash(&p.scope_sha256)
                && hash(&p.manifest_sha256)
                && hash(&p.inventory_sha256)
                && hash(&p.proof_hash)
                && p.completed_at_ms > 0
                && f.frame_count > 0
                && f.redaction_policy_version == 2
                && f.ended_at_ms >= f.started_at_ms,
            "RECORDING_UPLOAD_SEAL_PROOF_REJECTED"
        );
        self.with_connection(move |mut connection| {
            connection.pragma_update(None, "synchronous", "FULL")?;
            let transaction = connection.transaction_with_behavior(TransactionBehavior::Immediate)?;
            let s = &evidence.scope;
            let f = &evidence.finalization;
            validate_seal_receipts(&transaction, s, f, &evidence.seal.proof.scope_sha256)?;
            let pending: Option<Vec<u8>> = transaction.query_row("SELECT request_json FROM recording_upload_seal_requests WHERE tenant_id=? AND session_id=? AND recording_id=?",params![s.tenant_id,s.session_id,s.recording_id],|r|r.get(0)).optional()?;
            if let Some(pending) = pending {
                let request: RecordingUploadSealRequest = serde_json::from_slice(&pending)?;
                anyhow::ensure!(request.scope==*s && request.finalization==*f && request.expected_scope_sha256==evidence.seal.proof.scope_sha256,"RECORDING_UPLOAD_SEAL_REQUEST_CONFLICT");
            }
            transaction.execute("INSERT INTO recording_upload_seals VALUES(?,?,?,?) ON CONFLICT DO NOTHING",params![s.tenant_id,s.session_id,s.recording_id,bytes])?;
            let stored: Vec<u8> = transaction.query_row("SELECT seal_json FROM recording_upload_seals WHERE tenant_id=? AND session_id=? AND recording_id=?", params![s.tenant_id,s.session_id,s.recording_id],|r|r.get(0))?;
            anyhow::ensure!(stored==bytes,"RECORDING_UPLOAD_SEAL_CONFLICT");
            transaction.commit()?;
            Ok(())
        }).await
    }

    pub async fn recording_upload_seal(
        &self,
        tenant_id: &str,
        session_id: &str,
        recording_id: &str,
    ) -> anyhow::Result<Option<PersistedRecordingUploadSeal>> {
        let (tenant, session, recording) = (
            tenant_id.to_owned(),
            session_id.to_owned(),
            recording_id.to_owned(),
        );
        self.with_connection(move |connection| {
            let bytes: Option<Vec<u8>>=connection.query_row("SELECT seal_json FROM recording_upload_seals WHERE tenant_id=? AND session_id=? AND recording_id=?",params![tenant,session,recording],|r|r.get(0)).optional()?;
            bytes.map(|b|serde_json::from_slice(&b).map_err(Into::into)).transpose()
        }).await
    }

    pub async fn command_result(
        &self,
        message_id: &str,
    ) -> anyhow::Result<Option<PersistedCommandResult>> {
        let message_id = message_id.to_owned();
        self.with_connection(move |connection| {
            connection
                .query_row(
                    r#"
                    SELECT message_id, accepted, error_code, error_message,
                           event_id, event_payload, event_delivered
                    FROM command_results
                    WHERE message_id = ?1
                    "#,
                    params![message_id],
                    map_command_result,
                )
                .optional()
                .context("read persisted command result")
        })
        .await
    }

    pub async fn record_command_result(
        &self,
        result: &PersistedCommandResult,
    ) -> anyhow::Result<()> {
        let result = result.clone();
        self.with_connection(move |connection| {
            connection.execute(
                r#"
                INSERT INTO command_results (
                    message_id, accepted, error_code, error_message,
                    event_id, event_payload, event_delivered, created_at_ms
                ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7,
                          CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER))
                ON CONFLICT(message_id) DO NOTHING
                "#,
                params![
                    result.acknowledgement.message_id,
                    result.acknowledgement.accepted,
                    result.acknowledgement.error_code,
                    result.acknowledgement.error_message,
                    result.event_id,
                    result.event_payload,
                    result.event_delivered,
                ],
            )?;
            Ok(())
        })
        .await
    }

    /// 原子记录一个命令产生的有序事件批次。首项仍以原始 command message_id 作为
    /// 幂等权威，后续项使用确定性的派生 message_id。created_at_ms 单调递增，确保
    /// Node 重启后的 pending_events 仍按 Begin -> Chunk -> Commit 重放。
    pub async fn record_command_results_atomic(
        &self,
        results: &[PersistedCommandResult],
    ) -> anyhow::Result<()> {
        anyhow::ensure!(
            !results.is_empty(),
            "command result batch must not be empty"
        );
        let results = results.to_vec();
        self.with_connection(move |mut connection| {
            let transaction =
                connection.transaction_with_behavior(TransactionBehavior::Immediate)?;
            let created_at_ms: i64 = transaction.query_row(
                "SELECT CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER)",
                [],
                |row| row.get(0),
            )?;
            for (index, result) in results.iter().enumerate() {
                let offset = i64::try_from(index).unwrap_or(i64::MAX);
                transaction.execute(
                    r#"
                    INSERT INTO command_results (
                        message_id, accepted, error_code, error_message,
                        event_id, event_payload, event_delivered, created_at_ms
                    ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8)
                    ON CONFLICT(message_id) DO NOTHING
                    "#,
                    params![
                        result.acknowledgement.message_id,
                        result.acknowledgement.accepted,
                        result.acknowledgement.error_code,
                        result.acknowledgement.error_message,
                        result.event_id,
                        result.event_payload,
                        result.event_delivered,
                        created_at_ms.saturating_add(offset),
                    ],
                )?;
            }
            transaction.commit()?;
            Ok(())
        })
        .await
    }

    /// 原子记录 Crash Event 并关闭 Runtime Lease，避免 Node 重启产生重复恢复。
    pub async fn record_crash_and_stop_runtime(
        &self,
        session_id: &str,
        result: &PersistedCommandResult,
    ) -> anyhow::Result<()> {
        let session_id = session_id.to_owned();
        let result = result.clone();
        self.with_connection(move |mut connection| {
            let transaction =
                connection.transaction_with_behavior(TransactionBehavior::Immediate)?;
            transaction.execute(
                r#"
                INSERT INTO command_results (
                    message_id, accepted, error_code, error_message,
                    event_id, event_payload, event_delivered, created_at_ms
                ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7,
                          CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER))
                ON CONFLICT(message_id) DO NOTHING
                "#,
                params![
                    result.acknowledgement.message_id,
                    result.acknowledgement.accepted,
                    result.acknowledgement.error_code,
                    result.acknowledgement.error_message,
                    result.event_id,
                    result.event_payload,
                    result.event_delivered,
                ],
            )?;
            transaction.execute(
                r#"
                UPDATE runtime_leases
                   SET active = 0,
                       updated_at_ms =
                           CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER)
                 WHERE session_id = ?1
                "#,
                params![session_id],
            )?;
            transaction.commit()?;
            Ok(())
        })
        .await
    }

    /// 原子比较并提升 Coordinator Term。旧 Term 永远不会覆盖新 Term。
    pub async fn validate_and_record_term(
        &self,
        session_id: &str,
        coordinator_term: i64,
    ) -> anyhow::Result<TermDecision> {
        let session_id = session_id.to_owned();
        self.with_connection(move |mut connection| {
            let transaction =
                connection.transaction_with_behavior(TransactionBehavior::Immediate)?;
            let current = transaction
                .query_row(
                    "SELECT coordinator_term FROM coordinator_terms WHERE session_id = ?1",
                    params![session_id],
                    |row| row.get::<_, i64>(0),
                )
                .optional()?
                .unwrap_or_default();
            if coordinator_term < current {
                return Ok(TermDecision::Stale {
                    current_term: current,
                });
            }
            transaction.execute(
                r#"
                INSERT INTO coordinator_terms(session_id, coordinator_term)
                VALUES (?1, ?2)
                ON CONFLICT(session_id) DO UPDATE
                SET coordinator_term = MAX(coordinator_terms.coordinator_term, excluded.coordinator_term)
                "#,
                params![session_id, coordinator_term],
            )?;
            transaction.commit()?;
            Ok(TermDecision::Accepted)
        })
        .await
    }

    /// 读取 Node 已接受的最新 Coordinator Term。
    pub async fn current_coordinator_term(&self, session_id: &str) -> anyhow::Result<Option<i64>> {
        let session_id = session_id.to_owned();
        self.with_connection(move |connection| {
            connection
                .query_row(
                    "SELECT coordinator_term FROM coordinator_terms WHERE session_id = ?1",
                    params![session_id],
                    |row| row.get(0),
                )
                .optional()
                .context("read current Coordinator Term")
        })
        .await
    }

    /// Atomically fences stale physical Shard dispatch.
    ///
    /// Legacy route_epoch=0 is accepted only before this Node has observed a routed command for the
    /// Session and only while rollout compatibility is enabled. Once an epoch is recorded, an
    /// unversioned command can never bypass the fence.
    pub async fn validate_and_record_route(
        &self,
        session_id: &str,
        route_epoch: i64,
        coordinator_shard_id: i32,
        require_route_epoch: bool,
    ) -> anyhow::Result<RouteDecision> {
        let session_id = session_id.to_owned();
        self.with_connection(move |mut connection| {
            let transaction =
                connection.transaction_with_behavior(TransactionBehavior::Immediate)?;
            let current = transaction
                .query_row(
                    r#"
                    SELECT route_epoch, coordinator_shard_id
                      FROM coordinator_routes
                     WHERE session_id = ?1
                    "#,
                    params![session_id],
                    |row| Ok((row.get::<_, i64>(0)?, row.get::<_, i32>(1)?)),
                )
                .optional()?;

            if route_epoch <= 0 {
                return Ok(if require_route_epoch || current.is_some() {
                    RouteDecision::Missing
                } else {
                    RouteDecision::LegacyAccepted
                });
            }
            if !(0..=4095).contains(&coordinator_shard_id) {
                return Ok(RouteDecision::ShardMismatch {
                    current_shard: current.map(|route| route.1).unwrap_or(-1),
                });
            }
            if let Some((current_epoch, current_shard)) = current {
                if route_epoch < current_epoch {
                    return Ok(RouteDecision::Stale { current_epoch });
                }
                if route_epoch == current_epoch && coordinator_shard_id != current_shard {
                    return Ok(RouteDecision::ShardMismatch { current_shard });
                }
            }

            transaction.execute(
                r#"
                INSERT INTO coordinator_routes(
                    session_id, route_epoch, coordinator_shard_id
                ) VALUES (?1, ?2, ?3)
                ON CONFLICT(session_id) DO UPDATE SET
                    route_epoch = excluded.route_epoch,
                    coordinator_shard_id = excluded.coordinator_shard_id
                WHERE excluded.route_epoch >= coordinator_routes.route_epoch
                "#,
                params![session_id, route_epoch, coordinator_shard_id],
            )?;
            transaction.commit()?;
            Ok(RouteDecision::Accepted)
        })
        .await
    }

    /// Atomically validates and advances both physical Route and Coordinator Term fences.
    ///
    /// Neither fence is persisted when the other one rejects the command. This prevents a command
    /// carrying a future Route Epoch but a stale Coordinator Term from poisoning the durable Node
    /// route, and prevents a wrong-shard command from advancing the accepted Term.
    pub async fn validate_and_record_command_fence(
        &self,
        session_id: &str,
        route_epoch: i64,
        coordinator_shard_id: i32,
        coordinator_term: i64,
        require_route_epoch: bool,
    ) -> anyhow::Result<CommandFenceDecision> {
        let session_id = session_id.to_owned();
        self.with_connection(move |mut connection| {
            let transaction =
                connection.transaction_with_behavior(TransactionBehavior::Immediate)?;
            let current_route = transaction
                .query_row(
                    r#"
                    SELECT route_epoch, coordinator_shard_id
                      FROM coordinator_routes
                     WHERE session_id = ?1
                    "#,
                    params![session_id],
                    |row| Ok((row.get::<_, i64>(0)?, row.get::<_, i32>(1)?)),
                )
                .optional()?;
            let current_term = transaction
                .query_row(
                    "SELECT coordinator_term FROM coordinator_terms WHERE session_id = ?1",
                    params![session_id],
                    |row| row.get::<_, i64>(0),
                )
                .optional()?
                .unwrap_or_default();

            if coordinator_term < current_term {
                return Ok(CommandFenceDecision::TermStale { current_term });
            }
            let legacy = route_epoch <= 0;
            if legacy && (require_route_epoch || current_route.is_some()) {
                return Ok(CommandFenceDecision::RouteMissing);
            }
            if !legacy {
                if !(0..=4095).contains(&coordinator_shard_id) {
                    return Ok(CommandFenceDecision::ShardMismatch {
                        current_shard: current_route.map(|route| route.1).unwrap_or(-1),
                    });
                }
                if let Some((current_epoch, current_shard)) = current_route {
                    if route_epoch < current_epoch {
                        return Ok(CommandFenceDecision::RouteStale { current_epoch });
                    }
                    if route_epoch == current_epoch && coordinator_shard_id != current_shard {
                        return Ok(CommandFenceDecision::ShardMismatch { current_shard });
                    }
                }
            }

            if !legacy {
                transaction.execute(
                    r#"
                    INSERT INTO coordinator_routes(
                        session_id, route_epoch, coordinator_shard_id
                    ) VALUES (?1, ?2, ?3)
                    ON CONFLICT(session_id) DO UPDATE SET
                        route_epoch = excluded.route_epoch,
                        coordinator_shard_id = excluded.coordinator_shard_id
                    WHERE excluded.route_epoch >= coordinator_routes.route_epoch
                    "#,
                    params![session_id, route_epoch, coordinator_shard_id],
                )?;
            }
            transaction.execute(
                r#"
                INSERT INTO coordinator_terms(session_id, coordinator_term)
                VALUES (?1, ?2)
                ON CONFLICT(session_id) DO UPDATE
                SET coordinator_term = MAX(coordinator_terms.coordinator_term, excluded.coordinator_term)
                "#,
                params![session_id, coordinator_term],
            )?;
            transaction.commit()?;
            Ok(if legacy {
                CommandFenceDecision::LegacyAccepted
            } else {
                CommandFenceDecision::Accepted
            })
        })
        .await
    }

    pub async fn next_event_sequence(&self, session_id: &str) -> anyhow::Result<i64> {
        let session_id = session_id.to_owned();
        self.with_connection(move |mut connection| {
            let transaction =
                connection.transaction_with_behavior(TransactionBehavior::Immediate)?;
            transaction.execute(
                r#"
                INSERT INTO event_sequences(session_id, sequence)
                VALUES (?1, 1)
                ON CONFLICT(session_id) DO UPDATE SET sequence = sequence + 1
                "#,
                params![session_id],
            )?;
            let sequence = transaction.query_row(
                "SELECT sequence FROM event_sequences WHERE session_id = ?1",
                params![session_id],
                |row| row.get(0),
            )?;
            transaction.commit()?;
            Ok(sequence)
        })
        .await
    }

    pub async fn pending_events(
        &self,
        limit: usize,
    ) -> anyhow::Result<Vec<PersistedCommandResult>> {
        let limit = i64::try_from(limit).unwrap_or(i64::MAX);
        self.with_connection(move |connection| {
            let mut statement = connection.prepare(
                r#"
                SELECT message_id, accepted, error_code, error_message,
                       event_id, event_payload, event_delivered
                FROM command_results
                WHERE event_payload IS NOT NULL AND event_delivered = 0
                ORDER BY created_at_ms, message_id
                LIMIT ?1
                "#,
            )?;
            let rows = statement.query_map(params![limit], map_command_result)?;
            rows.collect::<Result<Vec<_>, _>>()
                .context("read pending Node events")
        })
        .await
    }

    pub async fn is_event_delivered(&self, event_id: &str) -> anyhow::Result<bool> {
        let event_id = event_id.to_owned();
        self.with_connection(move |connection| {
            connection
                .query_row(
                    "SELECT event_delivered FROM command_results WHERE event_id = ?1",
                    params![event_id],
                    |row| row.get::<_, bool>(0),
                )
                .optional()
                .map(|delivered| delivered.unwrap_or(false))
                .context("read persisted event delivery state")
        })
        .await
    }

    pub async fn mark_event_delivered(&self, event_id: &str) -> anyhow::Result<()> {
        let event_id = event_id.to_owned();
        self.with_connection(move |connection| {
            let updated = connection.execute(
                "UPDATE command_results SET event_delivered = 1 WHERE event_id = ?1",
                params![event_id],
            )?;
            anyhow::ensure!(updated == 1, "event is not present in Node Journal");
            Ok(())
        })
        .await
    }

    pub async fn record_runtime_started(&self, lease: &RuntimeLease) -> anyhow::Result<()> {
        let lease = lease.clone();
        self.with_connection(move |connection| {
            connection.execute(
                r#"
                INSERT INTO runtime_leases (
                    session_id, tenant_id, runtime_build_id, coordinator_term,
                    context_epoch, browser_generation, pid, process_started_at,
                    active, updated_at_ms
                ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, 1,
                          CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER))
                ON CONFLICT(session_id) DO UPDATE SET
                    tenant_id = excluded.tenant_id,
                    runtime_build_id = excluded.runtime_build_id,
                    coordinator_term = excluded.coordinator_term,
                    context_epoch = excluded.context_epoch,
                    browser_generation = excluded.browser_generation,
                    pid = excluded.pid,
                    process_started_at = excluded.process_started_at,
                    active = 1,
                    updated_at_ms = excluded.updated_at_ms
                "#,
                params![
                    lease.session_id,
                    lease.tenant_id,
                    lease.runtime_build_id,
                    lease.coordinator_term,
                    lease.context_epoch,
                    lease.browser_generation,
                    lease.pid,
                    lease.process_started_at,
                ],
            )?;
            Ok(())
        })
        .await
    }

    pub async fn record_command_result_and_start_runtime(
        &self,
        result: &PersistedCommandResult,
        lease: &RuntimeLease,
    ) -> anyhow::Result<()> {
        let result = result.clone();
        let lease = lease.clone();
        self.with_connection(move |mut connection| {
            let transaction =
                connection.transaction_with_behavior(TransactionBehavior::Immediate)?;
            insert_command_result(&transaction, &result)?;
            upsert_runtime_lease(&transaction, &lease)?;
            transaction.commit()?;
            Ok(())
        })
        .await
    }

    pub async fn record_command_result_and_stop_runtime(
        &self,
        result: &PersistedCommandResult,
        session_id: &str,
    ) -> anyhow::Result<()> {
        let result = result.clone();
        let session_id = session_id.to_owned();
        self.with_connection(move |mut connection| {
            let transaction =
                connection.transaction_with_behavior(TransactionBehavior::Immediate)?;
            insert_command_result(&transaction, &result)?;
            mark_runtime_stopped_in(&transaction, &session_id)?;
            transaction.commit()?;
            Ok(())
        })
        .await
    }

    /// Atomically journals every event produced by StopRuntime and closes the Runtime Lease.
    /// Recording finalization is an additional event in this batch; persisting only the first
    /// result would acknowledge shutdown while losing the immutable recording manifest.
    pub async fn record_command_results_and_stop_runtime_atomic(
        &self,
        results: &[PersistedCommandResult],
        session_id: &str,
    ) -> anyhow::Result<()> {
        anyhow::ensure!(
            !results.is_empty(),
            "stop runtime command result batch must not be empty"
        );
        let results = results.to_vec();
        let session_id = session_id.to_owned();
        self.with_connection(move |mut connection| {
            let transaction =
                connection.transaction_with_behavior(TransactionBehavior::Immediate)?;
            let created_at_ms: i64 = transaction.query_row(
                "SELECT CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER)",
                [],
                |row| row.get(0),
            )?;
            for (index, result) in results.iter().enumerate() {
                let offset = i64::try_from(index).unwrap_or(i64::MAX);
                transaction.execute(
                    r#"
                    INSERT INTO command_results (
                        message_id, accepted, error_code, error_message,
                        event_id, event_payload, event_delivered, created_at_ms
                    ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8)
                    ON CONFLICT(message_id) DO NOTHING
                    "#,
                    params![
                        result.acknowledgement.message_id,
                        result.acknowledgement.accepted,
                        result.acknowledgement.error_code,
                        result.acknowledgement.error_message,
                        result.event_id,
                        result.event_payload,
                        result.event_delivered,
                        created_at_ms.saturating_add(offset),
                    ],
                )?;
            }
            mark_runtime_stopped_in(&transaction, &session_id)?;
            transaction.commit()?;
            Ok(())
        })
        .await
    }

    pub async fn mark_runtime_stopped(&self, session_id: &str) -> anyhow::Result<()> {
        let session_id = session_id.to_owned();
        self.with_connection(move |connection| {
            connection.execute(
                r#"
                UPDATE runtime_leases
                   SET active = 0,
                       updated_at_ms =
                           CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER)
                 WHERE session_id = ?1
                "#,
                params![session_id],
            )?;
            Ok(())
        })
        .await
    }

    pub async fn active_runtime_leases(&self) -> anyhow::Result<Vec<RuntimeLease>> {
        self.with_connection(move |connection| {
            let mut statement = connection.prepare(
                r#"
                SELECT session_id, tenant_id, runtime_build_id, coordinator_term,
                       context_epoch, browser_generation, pid, process_started_at
                  FROM runtime_leases
                 WHERE active = 1
                 ORDER BY updated_at_ms, session_id
                "#,
            )?;
            let rows = statement.query_map([], |row| {
                Ok(RuntimeLease {
                    session_id: row.get(0)?,
                    tenant_id: row.get(1)?,
                    runtime_build_id: row.get(2)?,
                    coordinator_term: row.get(3)?,
                    context_epoch: row.get(4)?,
                    browser_generation: row.get(5)?,
                    pid: row.get(6)?,
                    process_started_at: row.get(7)?,
                })
            })?;
            rows.collect::<Result<Vec<_>, _>>()
                .context("read active runtime leases")
        })
        .await
    }

    async fn with_connection<T, F>(&self, operation: F) -> anyhow::Result<T>
    where
        T: Send + 'static,
        F: FnOnce(Connection) -> anyhow::Result<T> + Send + 'static,
    {
        let database_path = self.database_path.clone();
        tokio::task::spawn_blocking(move || {
            let connection =
                Connection::open(&database_path).context("open Browser Node Journal")?;
            connection.busy_timeout(std::time::Duration::from_secs(5))?;
            operation(connection)
        })
        .await
        .context("join Browser Node Journal operation")?
    }
}

fn validate_seal_receipts(
    transaction: &rusqlite::Transaction<'_>,
    s: &RecordingUploadHoldScope,
    f: &RecordingHeldUploadFinalization,
    expected_scope_sha256: &str,
) -> anyhow::Result<()> {
    let mut statement = transaction.prepare("SELECT segment_sequence,receipt_json,receipt_sha256 FROM recording_upload_receipts WHERE tenant_id=? AND session_id=? AND recording_id=? ORDER BY segment_sequence")?;
    let mut rows = statement.query(params![s.tenant_id, s.session_id, s.recording_id])?;
    let mut count = 0u64;
    let mut totals = [0u64; 3];
    while let Some(row) = rows.next()? {
        let sequence: u64 = row.get(0)?;
        let receipt: RecordingUploadReceipt = serde_json::from_slice(&row.get::<_, Vec<u8>>(1)?)?;
        anyhow::ensure!(
            count < f.segment_count
                && sequence == count
                && receipt.segment.sequence == sequence
                && receipt.scope == *s
                && receipt.sha256()? == row.get::<_, String>(2)?
                && receipt.proof.scope_sha256 == expected_scope_sha256
                && receipt.segment.started_at_ms >= f.started_at_ms
                && receipt.segment.ended_at_ms <= f.ended_at_ms,
            "RECORDING_UPLOAD_SEAL_RECEIPTS_REJECTED"
        );
        for (total, value) in totals.iter_mut().zip([
            receipt.segment.frame_count,
            receipt.segment.redacted_frame_count,
            receipt.segment.redacted_region_count,
        ]) {
            *total = total
                .checked_add(value)
                .ok_or_else(|| anyhow::anyhow!("RECORDING_UPLOAD_SEAL_COUNTER_BOUND"))?;
        }
        count += 1;
    }
    anyhow::ensure!(
        count == f.segment_count
            && totals
                == [
                    f.frame_count,
                    f.redacted_frame_count,
                    f.redacted_region_count
                ],
        "RECORDING_UPLOAD_SEAL_RECEIPTS_INCOMPLETE"
    );
    Ok(())
}

fn map_command_result(row: &rusqlite::Row<'_>) -> rusqlite::Result<PersistedCommandResult> {
    Ok(PersistedCommandResult {
        acknowledgement: PersistedAcknowledgement {
            message_id: row.get(0)?,
            accepted: row.get(1)?,
            error_code: row.get(2)?,
            error_message: row.get(3)?,
        },
        event_id: row.get(4)?,
        event_payload: row.get(5)?,
        event_delivered: row.get(6)?,
    })
}

fn insert_command_result(
    connection: &rusqlite::Connection,
    result: &PersistedCommandResult,
) -> anyhow::Result<()> {
    connection.execute(
        r#"
        INSERT INTO command_results (
            message_id, accepted, error_code, error_message,
            event_id, event_payload, event_delivered, created_at_ms
        ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7,
                  CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER))
        ON CONFLICT(message_id) DO NOTHING
        "#,
        params![
            result.acknowledgement.message_id,
            result.acknowledgement.accepted,
            result.acknowledgement.error_code,
            result.acknowledgement.error_message,
            result.event_id,
            result.event_payload,
            result.event_delivered,
        ],
    )?;
    Ok(())
}

fn upsert_runtime_lease(
    connection: &rusqlite::Connection,
    lease: &RuntimeLease,
) -> anyhow::Result<()> {
    connection.execute(
        r#"
        INSERT INTO runtime_leases (
            session_id, tenant_id, runtime_build_id, coordinator_term,
            context_epoch, browser_generation, pid, process_started_at,
            active, updated_at_ms
        ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, 1,
                  CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER))
        ON CONFLICT(session_id) DO UPDATE SET
            tenant_id = excluded.tenant_id,
            runtime_build_id = excluded.runtime_build_id,
            coordinator_term = excluded.coordinator_term,
            context_epoch = excluded.context_epoch,
            browser_generation = excluded.browser_generation,
            pid = excluded.pid,
            process_started_at = excluded.process_started_at,
            active = 1,
            updated_at_ms = excluded.updated_at_ms
        "#,
        params![
            lease.session_id,
            lease.tenant_id,
            lease.runtime_build_id,
            lease.coordinator_term,
            lease.context_epoch,
            lease.browser_generation,
            lease.pid,
            lease.process_started_at,
        ],
    )?;
    Ok(())
}

fn mark_runtime_stopped_in(
    connection: &rusqlite::Connection,
    session_id: &str,
) -> anyhow::Result<()> {
    connection.execute(
        r#"
        UPDATE runtime_leases
           SET active = 0,
               updated_at_ms =
                   CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER)
         WHERE session_id = ?1
        "#,
        params![session_id],
    )?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::{SystemTime, UNIX_EPOCH};

    fn temporary_database(name: &str) -> PathBuf {
        let nonce = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        std::env::temp_dir().join(format!("browsercloud-{name}-{nonce}.sqlite3"))
    }

    #[tokio::test]
    async fn persists_deduplication_and_pending_events_across_reopen() {
        let path = temporary_database("dedup");
        let journal = SqliteNodeJournal::open(&path).await.unwrap();
        journal
            .record_command_result(&PersistedCommandResult {
                acknowledgement: PersistedAcknowledgement {
                    message_id: "msg_1".into(),
                    accepted: true,
                    error_code: String::new(),
                    error_message: String::new(),
                },
                event_id: Some("evt_1".into()),
                event_payload: Some(vec![1, 2, 3]),
                event_delivered: false,
            })
            .await
            .unwrap();
        drop(journal);

        let reopened = SqliteNodeJournal::open(&path).await.unwrap();
        let result = reopened.command_result("msg_1").await.unwrap().unwrap();
        assert_eq!(result.event_payload, Some(vec![1, 2, 3]));
        assert_eq!(reopened.pending_events(10).await.unwrap().len(), 1);
        assert!(!reopened.is_event_delivered("evt_1").await.unwrap());
        assert!(!reopened.is_event_delivered("evt_unknown").await.unwrap());
        reopened.mark_event_delivered("evt_1").await.unwrap();
        assert!(reopened.is_event_delivered("evt_1").await.unwrap());
        assert!(reopened.pending_events(10).await.unwrap().is_empty());
        let _ = std::fs::remove_file(path);
    }

    fn upload_receipt() -> RecordingUploadReceipt {
        use helper_contracts::{
            RecordingUploadHoldScope, StorageRecordingSegmentDescriptor,
            StorageRecordingUploadHoldProof,
        };
        RecordingUploadReceipt {
            schema_version: 1,
            scope: RecordingUploadHoldScope {
                operation_id: "rua_journal".into(),
                authority_epoch: 1,
                placement_epoch: 2,
                tenant_id: "tenant-journal".into(),
                profile_id: "profile-journal".into(),
                session_id: "ses_journal".into(),
                recording_id: "rec_journal".into(),
            },
            segment: StorageRecordingSegmentDescriptor {
                sequence: 0,
                content_sha256: "c".repeat(64),
                content_bytes: 900,
                frame_count: 1,
                redacted_frame_count: 1,
                redacted_region_count: 3,
                redaction_policy_version: 2,
                started_at_ms: 1,
                ended_at_ms: 2,
            },
            proof: StorageRecordingUploadHoldProof {
                operation_id: "rua_journal".into(),
                authority_epoch: 1,
                placement_epoch: 2,
                scope_sha256: "a".repeat(64),
                proof_hash: "b".repeat(64),
                object_version_count: 2,
            },
        }
    }

    #[tokio::test]
    async fn prepared_seal_survives_unknown_and_fences_changed_requests_and_new_segments() {
        let path = temporary_database("seal-prepared");
        let journal = SqliteNodeJournal::open(&path).await.unwrap();
        let evidence = upload_seal();
        let request = RecordingUploadSealRequest {
            scope: evidence.scope.clone(),
            finalization: evidence.finalization.clone(),
            expected_scope_sha256: evidence.seal.proof.scope_sha256.clone(),
        };
        assert!(journal
            .prepare_recording_upload_seal(&request)
            .await
            .is_err());
        journal
            .record_recording_upload_receipt(&upload_receipt())
            .await
            .unwrap();
        journal
            .prepare_recording_upload_seal(&request)
            .await
            .unwrap();
        drop(journal);
        let recovered = SqliteNodeJournal::open(&path).await.unwrap();
        let s = &request.scope;
        assert_eq!(
            recovered
                .recording_upload_seal_request(&s.tenant_id, &s.session_id, &s.recording_id)
                .await
                .unwrap(),
            Some(request.clone())
        );
        assert!(recovered
            .recording_upload_seal(&s.tenant_id, &s.session_id, &s.recording_id)
            .await
            .unwrap()
            .is_none());
        recovered
            .prepare_recording_upload_seal(&request)
            .await
            .unwrap();
        let mut changed = request.clone();
        changed.finalization.ended_at_ms += 1;
        assert!(recovered
            .prepare_recording_upload_seal(&changed)
            .await
            .is_err());
        let mut late = upload_receipt();
        late.segment.sequence = 1;
        assert!(recovered
            .record_recording_upload_receipt(&late)
            .await
            .is_err());
        recovered
            .record_recording_upload_receipt(&upload_receipt())
            .await
            .unwrap();
        let mut wrong = evidence.clone();
        wrong.finalization.ended_at_ms += 1;
        assert!(recovered
            .record_recording_upload_seal(&wrong)
            .await
            .is_err());
        recovered
            .record_recording_upload_seal(&evidence)
            .await
            .unwrap();
        let connection = Connection::open(&path).unwrap();
        assert!(connection
            .execute(
                "UPDATE recording_upload_seal_requests SET request_json=request_json",
                []
            )
            .is_err());
        assert!(connection
            .execute("DELETE FROM recording_upload_seal_requests", [])
            .is_err());
        drop(connection);
        drop(recovered);
        let _ = std::fs::remove_file(path);
    }

    #[tokio::test]
    async fn prepare_seal_and_append_choose_one_sqlite_order() {
        let path = temporary_database("seal-prepare-race");
        let a = SqliteNodeJournal::open(&path).await.unwrap();
        let b = SqliteNodeJournal::open(&path).await.unwrap();
        a.record_recording_upload_receipt(&upload_receipt())
            .await
            .unwrap();
        let e = upload_seal();
        let request = RecordingUploadSealRequest {
            scope: e.scope,
            finalization: e.finalization,
            expected_scope_sha256: e.seal.proof.scope_sha256,
        };
        let mut late = upload_receipt();
        late.segment.sequence = 1;
        let (prepared, appended) = tokio::join!(
            a.prepare_recording_upload_seal(&request),
            b.record_recording_upload_receipt(&late)
        );
        assert_ne!(prepared.is_ok(), appended.is_ok());
        assert_eq!(
            a.recording_upload_seal_request(
                &request.scope.tenant_id,
                &request.scope.session_id,
                &request.scope.recording_id
            )
            .await
            .unwrap()
            .is_some(),
            prepared.is_ok()
        );
        drop(a);
        drop(b);
        let _ = std::fs::remove_file(path);
    }

    fn upload_seal() -> PersistedRecordingUploadSeal {
        let r = upload_receipt();
        PersistedRecordingUploadSeal {
            scope: r.scope,
            finalization: RecordingHeldUploadFinalization {
                segment_count: 1,
                frame_count: 1,
                redacted_frame_count: 1,
                redacted_region_count: 3,
                redaction_policy_version: 2,
                started_at_ms: 1,
                ended_at_ms: 2,
            },
            seal: StorageRecordingUploadSeal {
                operation_id: r.proof.operation_id,
                authority_epoch: 1,
                placement_epoch: 2,
                proof: helper_contracts::RecordingHeldUploadSealProof {
                    protocol_version: 2,
                    scope_sha256: "a".repeat(64),
                    manifest_sha256: "c".repeat(64),
                    manifest_bytes: 100,
                    segment_count: 1,
                    object_version_count: 3,
                    inventory_sha256: "d".repeat(64),
                    proof_hash: "e".repeat(64),
                    completed_at_ms: 3,
                },
            },
        }
    }

    #[tokio::test]
    async fn upload_seal_survives_reopen_and_fences_new_segments_and_sql_mutations() {
        let path = temporary_database("seal-durable");
        let journal = SqliteNodeJournal::open(&path).await.unwrap();
        let receipt = upload_receipt();
        let evidence = upload_seal();
        assert!(journal
            .record_recording_upload_seal(&evidence)
            .await
            .is_err());
        journal
            .record_recording_upload_receipt(&receipt)
            .await
            .unwrap();
        journal
            .record_recording_upload_seal(&evidence)
            .await
            .unwrap();
        drop(journal);
        let journal = SqliteNodeJournal::open(&path).await.unwrap();
        let s = &evidence.scope;
        assert_eq!(
            journal
                .recording_upload_seal(&s.tenant_id, &s.session_id, &s.recording_id)
                .await
                .unwrap(),
            Some(evidence.clone())
        );
        assert!(journal
            .recording_upload_seal("other", &s.session_id, &s.recording_id)
            .await
            .unwrap()
            .is_none());
        journal
            .record_recording_upload_seal(&evidence)
            .await
            .unwrap();
        journal
            .record_recording_upload_receipt(&receipt)
            .await
            .unwrap();
        let mut late = receipt.clone();
        late.segment.sequence = 1;
        assert!(journal
            .record_recording_upload_receipt(&late)
            .await
            .is_err());
        let connection = Connection::open(&path).unwrap();
        assert!(connection
            .execute("UPDATE recording_upload_seals SET seal_json=seal_json", [])
            .is_err());
        assert!(connection
            .execute("DELETE FROM recording_upload_seals", [])
            .is_err());
        assert!(connection
            .execute(
                "INSERT INTO recording_upload_receipts VALUES(?,?,?,?,?,?)",
                params![
                    s.tenant_id,
                    s.session_id,
                    s.recording_id,
                    1,
                    serde_json::to_vec(&late).unwrap(),
                    late.sha256().unwrap()
                ]
            )
            .is_err());
        drop(connection);
        drop(journal);
        let _ = std::fs::remove_file(path);
    }

    #[tokio::test]
    async fn upload_seal_rejects_gaps_counters_scope_and_conflicting_replay() {
        let path = temporary_database("seal-reject");
        let journal = SqliteNodeJournal::open(&path).await.unwrap();
        let mut gap = upload_receipt();
        gap.segment.sequence = 1;
        journal.record_recording_upload_receipt(&gap).await.unwrap();
        assert!(journal
            .record_recording_upload_seal(&upload_seal())
            .await
            .is_err());
        let receipt = upload_receipt();
        journal
            .record_recording_upload_receipt(&receipt)
            .await
            .unwrap();
        assert!(journal
            .record_recording_upload_seal(&upload_seal())
            .await
            .is_err());
        let mut good = upload_seal();
        good.finalization.segment_count = 2;
        good.finalization.frame_count = 2;
        good.finalization.redacted_frame_count = 2;
        good.finalization.redacted_region_count = 6;
        good.seal.proof.segment_count = 2;
        good.seal.proof.object_version_count = 5;
        for mutation in ["scope", "counter", "time", "epoch", "protocol"] {
            let mut bad = good.clone();
            match mutation {
                "scope" => bad.seal.proof.scope_sha256 = "f".repeat(64),
                "counter" => bad.finalization.redacted_region_count = 5,
                "time" => bad.finalization.started_at_ms = 2,
                "epoch" => bad.scope.placement_epoch = 3,
                _ => bad.seal.proof.protocol_version = 1,
            }
            assert!(
                journal.record_recording_upload_seal(&bad).await.is_err(),
                "{mutation}"
            );
        }
        journal.record_recording_upload_seal(&good).await.unwrap();
        let mut conflict = good;
        conflict.seal.proof.proof_hash = "f".repeat(64);
        assert!(journal
            .record_recording_upload_seal(&conflict)
            .await
            .is_err());
        drop(journal);
        let _ = std::fs::remove_file(path);
    }

    #[tokio::test]
    async fn upload_seal_races_append_with_one_authoritative_order() {
        let path = temporary_database("seal-race");
        let a = SqliteNodeJournal::open(&path).await.unwrap();
        let b = SqliteNodeJournal::open(&path).await.unwrap();
        a.record_recording_upload_receipt(&upload_receipt())
            .await
            .unwrap();
        let mut late = upload_receipt();
        late.segment.sequence = 1;
        let evidence = upload_seal();
        let (seal, append) = tokio::join!(
            a.record_recording_upload_seal(&evidence),
            b.record_recording_upload_receipt(&late)
        );
        assert_ne!(seal.is_ok(), append.is_ok());
        if append.is_ok() {
            assert!(a
                .recording_upload_seal(
                    &evidence.scope.tenant_id,
                    &evidence.scope.session_id,
                    &evidence.scope.recording_id
                )
                .await
                .unwrap()
                .is_none());
        } else {
            assert!(a
                .recording_upload_seal(
                    &evidence.scope.tenant_id,
                    &evidence.scope.session_id,
                    &evidence.scope.recording_id
                )
                .await
                .unwrap()
                .is_some());
        }
        drop(a);
        drop(b);
        let _ = std::fs::remove_file(path);
    }

    #[tokio::test]
    async fn upload_receipt_is_durable_idempotent_and_immutable() {
        let path = temporary_database("upload-durable");
        let receipt = upload_receipt();
        let journal = SqliteNodeJournal::open(&path).await.unwrap();
        let ack = journal
            .record_recording_upload_receipt(&receipt)
            .await
            .unwrap();
        assert_eq!(ack.journal_receipt_sha256, receipt.sha256().unwrap());
        drop(journal);
        let reopened = SqliteNodeJournal::open(&path).await.unwrap();
        assert_eq!(
            reopened
                .record_recording_upload_receipt(&receipt)
                .await
                .unwrap(),
            ack
        );
        let connection = Connection::open(&path).unwrap();
        assert_eq!(
            connection
                .query_row("SELECT count(*) FROM recording_upload_receipts", [], |r| {
                    r.get::<_, i64>(0)
                })
                .unwrap(),
            1
        );
        assert!(connection
            .execute(
                "UPDATE recording_upload_receipts SET receipt_sha256=?",
                params!["f".repeat(64)]
            )
            .is_err());
        assert!(connection
            .execute("DELETE FROM recording_upload_receipts", [])
            .is_err());
        drop(connection);
        drop(reopened);
        let _ = std::fs::remove_file(path);
    }

    #[tokio::test]
    async fn upload_receipt_conflicts_do_not_replace_prior_proof_or_scope() {
        let path = temporary_database("upload-conflict");
        let journal = SqliteNodeJournal::open(&path).await.unwrap();
        let receipt = upload_receipt();
        let ack = journal
            .record_recording_upload_receipt(&receipt)
            .await
            .unwrap();
        let mut conflict = receipt.clone();
        conflict.proof.proof_hash = "d".repeat(64);
        assert!(journal
            .record_recording_upload_receipt(&conflict)
            .await
            .is_err());
        conflict = receipt.clone();
        conflict.scope.placement_epoch += 1;
        conflict.proof.placement_epoch = conflict.scope.placement_epoch;
        conflict.segment.sequence += 1;
        assert!(journal
            .record_recording_upload_receipt(&conflict)
            .await
            .is_err());
        conflict = receipt.clone();
        conflict.proof.scope_sha256 = "e".repeat(64);
        conflict.segment.sequence += 1;
        assert!(journal
            .record_recording_upload_receipt(&conflict)
            .await
            .is_err());
        assert_eq!(
            journal
                .record_recording_upload_receipt(&receipt)
                .await
                .unwrap(),
            ack
        );
        let _ = std::fs::remove_file(path);
    }

    #[tokio::test]
    async fn concurrent_upload_receipts_choose_one_immutable_proof() {
        let path = temporary_database("upload-concurrent");
        let a = SqliteNodeJournal::open(&path).await.unwrap();
        let b = SqliteNodeJournal::open(&path).await.unwrap();
        let left = upload_receipt();
        let mut right = left.clone();
        right.proof.proof_hash = "e".repeat(64);
        let (one, two) = tokio::join!(
            a.record_recording_upload_receipt(&left),
            b.record_recording_upload_receipt(&right)
        );
        assert_ne!(one.is_ok(), two.is_ok());
        let (accepted, denied) = if one.is_ok() {
            (left, right)
        } else {
            (right, left)
        };
        assert!(a.record_recording_upload_receipt(&accepted).await.is_ok());
        assert!(a.record_recording_upload_receipt(&denied).await.is_err());
        let _ = std::fs::remove_file(path);
    }

    #[tokio::test]
    async fn atomically_persists_snapshot_events_in_declared_order() {
        let path = temporary_database("snapshot-batch");
        let journal = SqliteNodeJournal::open(&path).await.unwrap();
        let results = (0..4)
            .map(|index| PersistedCommandResult {
                acknowledgement: PersistedAcknowledgement {
                    message_id: format!("msg_snapshot_{index}"),
                    accepted: true,
                    error_code: String::new(),
                    error_message: String::new(),
                },
                event_id: Some(format!("evt_snapshot_{index}")),
                event_payload: Some(vec![index as u8]),
                event_delivered: false,
            })
            .collect::<Vec<_>>();

        journal
            .record_command_results_atomic(&results)
            .await
            .unwrap();
        drop(journal);

        let reopened = SqliteNodeJournal::open(&path).await.unwrap();
        let pending = reopened.pending_events(10).await.unwrap();
        assert_eq!(pending.len(), 4);
        assert_eq!(
            pending
                .iter()
                .map(|result| result.event_id.as_deref().unwrap())
                .collect::<Vec<_>>(),
            vec![
                "evt_snapshot_0",
                "evt_snapshot_1",
                "evt_snapshot_2",
                "evt_snapshot_3"
            ]
        );
        let _ = std::fs::remove_file(path);
    }

    #[tokio::test]
    async fn rejects_stale_term_after_reopen() {
        let path = temporary_database("term");
        let journal = SqliteNodeJournal::open(&path).await.unwrap();
        assert_eq!(
            journal.validate_and_record_term("ses_1", 8).await.unwrap(),
            TermDecision::Accepted
        );
        drop(journal);

        let reopened = SqliteNodeJournal::open(&path).await.unwrap();
        assert_eq!(
            reopened.validate_and_record_term("ses_1", 7).await.unwrap(),
            TermDecision::Stale { current_term: 8 }
        );
        assert_eq!(
            reopened.current_coordinator_term("ses_1").await.unwrap(),
            Some(8)
        );
        let _ = std::fs::remove_file(path);
    }

    #[tokio::test]
    async fn persists_route_epoch_and_rejects_stale_or_wrong_shard_dispatch() {
        let path = temporary_database("route");
        let journal = SqliteNodeJournal::open(&path).await.unwrap();
        assert_eq!(
            journal
                .validate_and_record_route("ses_route", 0, 0, false)
                .await
                .unwrap(),
            RouteDecision::LegacyAccepted
        );
        assert_eq!(
            journal
                .validate_and_record_route("ses_route", 2, 7, false)
                .await
                .unwrap(),
            RouteDecision::Accepted
        );
        drop(journal);

        let reopened = SqliteNodeJournal::open(&path).await.unwrap();
        assert_eq!(
            reopened
                .validate_and_record_route("ses_route", 1, 7, false)
                .await
                .unwrap(),
            RouteDecision::Stale { current_epoch: 2 }
        );
        assert_eq!(
            reopened
                .validate_and_record_route("ses_route", 2, 8, false)
                .await
                .unwrap(),
            RouteDecision::ShardMismatch { current_shard: 7 }
        );
        assert_eq!(
            reopened
                .validate_and_record_route("ses_route", 0, 0, false)
                .await
                .unwrap(),
            RouteDecision::Missing
        );
        assert_eq!(
            reopened
                .validate_and_record_route("ses_fresh", 0, 0, true)
                .await
                .unwrap(),
            RouteDecision::Missing
        );
        let _ = std::fs::remove_file(path);
    }

    #[tokio::test]
    async fn commits_route_and_term_fences_atomically() {
        let path = temporary_database("command-fence");
        let journal = SqliteNodeJournal::open(&path).await.unwrap();
        assert_eq!(
            journal
                .validate_and_record_command_fence("ses_fenced", 2, 7, 2, false)
                .await
                .unwrap(),
            CommandFenceDecision::Accepted
        );
        assert_eq!(
            journal
                .validate_and_record_command_fence("ses_fenced", 3, 9, 1, false)
                .await
                .unwrap(),
            CommandFenceDecision::TermStale { current_term: 2 }
        );
        assert_eq!(
            journal
                .validate_and_record_command_fence("ses_fenced", 2, 7, 2, false)
                .await
                .unwrap(),
            CommandFenceDecision::Accepted,
            "a future route with a stale term must not poison the route fence"
        );
        assert_eq!(
            journal
                .validate_and_record_command_fence("ses_fenced", 2, 8, 3, false)
                .await
                .unwrap(),
            CommandFenceDecision::ShardMismatch { current_shard: 7 }
        );
        assert_eq!(
            journal
                .current_coordinator_term("ses_fenced")
                .await
                .unwrap(),
            Some(2),
            "a wrong-shard command must not advance the term fence"
        );
        let _ = std::fs::remove_file(path);
    }

    #[tokio::test]
    async fn persists_monotonic_event_sequence() {
        let path = temporary_database("sequence");
        let journal = SqliteNodeJournal::open(&path).await.unwrap();
        assert_eq!(journal.next_event_sequence("ses_1").await.unwrap(), 1);
        assert_eq!(journal.next_event_sequence("ses_1").await.unwrap(), 2);
        drop(journal);

        let reopened = SqliteNodeJournal::open(&path).await.unwrap();
        assert_eq!(reopened.next_event_sequence("ses_1").await.unwrap(), 3);
        let _ = std::fs::remove_file(path);
    }

    #[tokio::test]
    async fn persists_active_runtime_lease_for_restart_reconciliation() {
        let path = temporary_database("runtime-lease");
        let journal = SqliteNodeJournal::open(&path).await.unwrap();
        let lease = RuntimeLease {
            session_id: "ses_lease".into(),
            tenant_id: "tenant-1".into(),
            runtime_build_id: "runtime-1".into(),
            coordinator_term: 4,
            context_epoch: 8,
            browser_generation: 3,
            pid: 1234,
            process_started_at: 5678,
        };
        journal.record_runtime_started(&lease).await.unwrap();
        drop(journal);

        let reopened = SqliteNodeJournal::open(&path).await.unwrap();
        assert_eq!(reopened.active_runtime_leases().await.unwrap(), vec![lease]);
        reopened.mark_runtime_stopped("ses_lease").await.unwrap();
        assert!(reopened.active_runtime_leases().await.unwrap().is_empty());
        let _ = std::fs::remove_file(path);
    }

    #[tokio::test]
    async fn atomically_commits_command_result_and_runtime_lease_transition() {
        let path = temporary_database("runtime-lease-atomic");
        let journal = SqliteNodeJournal::open(&path).await.unwrap();
        let lease = RuntimeLease {
            session_id: "ses_atomic".into(),
            tenant_id: "tenant-1".into(),
            runtime_build_id: "runtime-1".into(),
            coordinator_term: 1,
            context_epoch: 2,
            browser_generation: 2,
            pid: 4321,
            process_started_at: 8765,
        };
        let started = PersistedCommandResult {
            acknowledgement: PersistedAcknowledgement {
                message_id: "msg_start".into(),
                accepted: true,
                error_code: String::new(),
                error_message: String::new(),
            },
            event_id: Some("evt_start".into()),
            event_payload: Some(vec![1]),
            event_delivered: false,
        };
        journal
            .record_command_result_and_start_runtime(&started, &lease)
            .await
            .unwrap();
        assert!(journal.command_result("msg_start").await.unwrap().is_some());
        assert_eq!(journal.active_runtime_leases().await.unwrap(), vec![lease]);

        let stopped = PersistedCommandResult {
            acknowledgement: PersistedAcknowledgement {
                message_id: "msg_stop".into(),
                accepted: true,
                error_code: String::new(),
                error_message: String::new(),
            },
            event_id: Some("evt_stop".into()),
            event_payload: Some(vec![2]),
            event_delivered: false,
        };
        let finalized = PersistedCommandResult {
            acknowledgement: PersistedAcknowledgement {
                message_id: "msg_stop#event-1".into(),
                accepted: true,
                error_code: String::new(),
                error_message: String::new(),
            },
            event_id: Some("evt_recording_finalized".into()),
            event_payload: Some(vec![3]),
            event_delivered: false,
        };
        journal
            .record_command_results_and_stop_runtime_atomic(&[stopped, finalized], "ses_atomic")
            .await
            .unwrap();
        assert!(journal.command_result("msg_stop").await.unwrap().is_some());
        let pending = journal.pending_events(10).await.unwrap();
        assert_eq!(
            pending
                .iter()
                .filter_map(|item| item.event_id.as_deref())
                .collect::<Vec<_>>(),
            vec!["evt_start", "evt_stop", "evt_recording_finalized"]
        );
        assert!(journal.active_runtime_leases().await.unwrap().is_empty());
        let _ = std::fs::remove_file(path);
    }
}
