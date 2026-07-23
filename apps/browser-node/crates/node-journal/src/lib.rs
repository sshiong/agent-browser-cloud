//! Browser Node SQLite Journal。
//!
//! Journal 是 Node 的本地恢复权威：命令副作用完成后，先持久化 ACK 与待投事件，
//! 再向 Control Plane 发布。进程在两者之间崩溃时，重启后会继续重投事件。

use anyhow::Context;
use rusqlite::{params, Connection, OptionalExtension, TransactionBehavior};
use serde::{Deserialize, Serialize};
use std::path::{Path, PathBuf};

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
                    CREATE TABLE IF NOT EXISTS event_sequences (
                        session_id TEXT PRIMARY KEY,
                        sequence INTEGER NOT NULL
                    );
                    CREATE INDEX IF NOT EXISTS idx_command_results_pending_event
                        ON command_results(event_delivered, created_at_ms)
                        WHERE event_payload IS NOT NULL;
                    "#,
                )?;
                Ok(())
            })
            .await?;
        Ok(journal)
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
                          CAST(strftime('%s', 'now') AS INTEGER) * 1000)
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
        reopened.mark_event_delivered("evt_1").await.unwrap();
        assert!(reopened.pending_events(10).await.unwrap().is_empty());
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
}
