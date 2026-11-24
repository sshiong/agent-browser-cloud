//! SQLite authoritative state store for L0 Lite (zero PostgreSQL / Redis dependency).

use crate::security::{generate_agent_token, hash_token};
use anyhow::{Context, Result};
use chrono::Utc;
use rusqlite::{params, Connection, OptionalExtension};
use serde::{Deserialize, Serialize};
use std::path::Path;
use std::sync::Mutex;
use uuid::Uuid;

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AgentTokenRecord {
    pub token_id: String,
    pub agent_id: String,
    pub label: String,
    pub scopes: Vec<String>,
    pub max_sessions: u32,
    pub revoked: bool,
    pub created_at: String,
    pub rotated_at: Option<String>,
}

impl AgentTokenRecord {
    pub fn has_scope(&self, scope: &str) -> bool {
        self.scopes.iter().any(|s| s == scope || s == "admin")
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct IssuedAgentToken {
    pub token: AgentTokenRecord,
    pub bearer_token: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SessionRecord {
    pub session_id: String,
    pub owner_agent_id: String,
    pub display_name: String,
    pub profile_id: String,
    pub state: String,
    pub proxy_url: Option<String>,
    pub current_url: Option<String>,
    pub idle_timeout_seconds: u64,
    pub created_at: String,
    pub updated_at: String,
    pub last_activity_at: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ProfileCheckpointRecord {
    pub checkpoint_id: String,
    pub profile_id: String,
    pub owner_agent_id: String,
    pub archive_sha256: String,
    pub archive_bytes: u64,
    pub file_count: u64,
    pub created_at: String,
}

pub struct LiteStore {
    conn: Mutex<Connection>,
}

impl LiteStore {
    pub fn open(path: &Path) -> Result<Self> {
        if let Some(parent) = path.parent() {
            std::fs::create_dir_all(parent).with_context(|| {
                format!("failed to create sqlite directory {}", parent.display())
            })?;
        }
        let conn = Connection::open(path)
            .with_context(|| format!("failed to open sqlite db {}", path.display()))?;
        conn.pragma_update(None, "journal_mode", "WAL")?;
        conn.pragma_update(None, "synchronous", "NORMAL")?;
        conn.pragma_update(None, "foreign_keys", "ON")?;
        conn.pragma_update(None, "busy_timeout", 5000)?;
        conn.execute_batch(
            r#"
            CREATE TABLE IF NOT EXISTS agent_tokens (
                token_id TEXT PRIMARY KEY,
                agent_id TEXT NOT NULL,
                label TEXT NOT NULL,
                token_hash TEXT NOT NULL UNIQUE,
                scopes_json TEXT NOT NULL,
                max_sessions INTEGER NOT NULL,
                revoked INTEGER NOT NULL DEFAULT 0,
                created_at TEXT NOT NULL,
                rotated_at TEXT
            );
            CREATE INDEX IF NOT EXISTS idx_agent_tokens_hash ON agent_tokens(token_hash);

            CREATE TABLE IF NOT EXISTS browser_sessions (
                session_id TEXT PRIMARY KEY,
                owner_agent_id TEXT NOT NULL,
                display_name TEXT NOT NULL,
                profile_id TEXT NOT NULL,
                state TEXT NOT NULL,
                proxy_url TEXT,
                current_url TEXT,
                idle_timeout_seconds INTEGER NOT NULL,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL,
                last_activity_at TEXT NOT NULL
            );
            CREATE INDEX IF NOT EXISTS idx_browser_sessions_owner ON browser_sessions(owner_agent_id);

            CREATE TABLE IF NOT EXISTS profile_checkpoints (
                checkpoint_id TEXT PRIMARY KEY,
                profile_id TEXT NOT NULL,
                owner_agent_id TEXT NOT NULL,
                archive_sha256 TEXT NOT NULL,
                archive_bytes INTEGER NOT NULL,
                file_count INTEGER NOT NULL,
                created_at TEXT NOT NULL
            );
            CREATE INDEX IF NOT EXISTS idx_profile_checkpoints_profile ON profile_checkpoints(profile_id);

            CREATE TABLE IF NOT EXISTS audit_events (
                event_id INTEGER PRIMARY KEY AUTOINCREMENT,
                agent_id TEXT NOT NULL,
                session_id TEXT,
                action TEXT NOT NULL,
                outcome TEXT NOT NULL,
                detail TEXT,
                created_at TEXT NOT NULL
            );
            "#,
        )?;
        Ok(Self {
            conn: Mutex::new(conn),
        })
    }

    /// Ensures at least one bootstrap admin token exists. Returns `Some(plaintext)` only when a
    /// brand-new token was created on an empty store.
    pub fn ensure_bootstrap_token(
        &self,
        explicit_token: Option<&str>,
        max_sessions: u32,
    ) -> Result<Option<IssuedAgentToken>> {
        let count: i64 = {
            let conn = self.conn.lock().expect("sqlite mutex poisoned");
            conn.query_row("SELECT COUNT(*) FROM agent_tokens", [], |row| row.get(0))?
        };
        if count > 0 {
            return Ok(None);
        }
        let plaintext = match explicit_token {
            Some(token) if !token.trim().is_empty() => token.trim().to_owned(),
            _ => generate_agent_token()?,
        };
        let issued = self.insert_token(
            "agent_admin",
            "Bootstrap Admin",
            vec!["admin".to_owned(), "mcp".to_owned(), "sessions".to_owned()],
            max_sessions,
            &plaintext,
        )?;
        Ok(Some(issued))
    }

    pub fn create_token(
        &self,
        agent_id: &str,
        label: &str,
        scopes: Vec<String>,
        max_sessions: u32,
    ) -> Result<IssuedAgentToken> {
        let plaintext = generate_agent_token()?;
        self.insert_token(agent_id, label, scopes, max_sessions, &plaintext)
    }

    fn insert_token(
        &self,
        agent_id: &str,
        label: &str,
        scopes: Vec<String>,
        max_sessions: u32,
        plaintext: &str,
    ) -> Result<IssuedAgentToken> {
        let token_id = format!("tok_{}", &Uuid::new_v4().simple().to_string()[..16]);
        let now = Utc::now().to_rfc3339();
        let hash = hash_token(plaintext);
        let scopes_json = serde_json::to_string(&scopes)?;
        let conn = self.conn.lock().expect("sqlite mutex poisoned");
        conn.execute(
            "INSERT INTO agent_tokens (token_id, agent_id, label, token_hash, scopes_json, max_sessions, revoked, created_at)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, 0, ?7)",
            params![token_id, agent_id, label, hash, scopes_json, max_sessions, now],
        )?;
        Ok(IssuedAgentToken {
            token: AgentTokenRecord {
                token_id,
                agent_id: agent_id.to_owned(),
                label: label.to_owned(),
                scopes,
                max_sessions,
                revoked: false,
                created_at: now,
                rotated_at: None,
            },
            bearer_token: plaintext.to_owned(),
        })
    }

    pub fn authenticate(&self, bearer_token: &str) -> Result<Option<AgentTokenRecord>> {
        let hash = hash_token(bearer_token.trim());
        let conn = self.conn.lock().expect("sqlite mutex poisoned");
        let row = conn
            .query_row(
                "SELECT token_id, agent_id, label, scopes_json, max_sessions, revoked, created_at, rotated_at
                 FROM agent_tokens WHERE token_hash = ?1",
                params![hash],
                |row| {
                    let scopes_json: String = row.get(3)?;
                    let scopes: Vec<String> =
                        serde_json::from_str(&scopes_json).unwrap_or_default();
                    Ok(AgentTokenRecord {
                        token_id: row.get(0)?,
                        agent_id: row.get(1)?,
                        label: row.get(2)?,
                        scopes,
                        max_sessions: row.get(4)?,
                        revoked: row.get::<_, i64>(5)? != 0,
                        created_at: row.get(6)?,
                        rotated_at: row.get(7)?,
                    })
                },
            )
            .optional()?;
        Ok(row.filter(|record| !record.revoked))
    }

    pub fn list_tokens(&self) -> Result<Vec<AgentTokenRecord>> {
        let conn = self.conn.lock().expect("sqlite mutex poisoned");
        let mut stmt = conn.prepare(
            "SELECT token_id, agent_id, label, scopes_json, max_sessions, revoked, created_at, rotated_at
             FROM agent_tokens ORDER BY created_at DESC",
        )?;
        let rows = stmt
            .query_map([], |row| {
                let scopes_json: String = row.get(3)?;
                let scopes: Vec<String> = serde_json::from_str(&scopes_json).unwrap_or_default();
                Ok(AgentTokenRecord {
                    token_id: row.get(0)?,
                    agent_id: row.get(1)?,
                    label: row.get(2)?,
                    scopes,
                    max_sessions: row.get(4)?,
                    revoked: row.get::<_, i64>(5)? != 0,
                    created_at: row.get(6)?,
                    rotated_at: row.get(7)?,
                })
            })?
            .collect::<std::result::Result<Vec<_>, _>>()?;
        Ok(rows)
    }

    pub fn revoke_token(&self, token_id: &str) -> Result<bool> {
        let conn = self.conn.lock().expect("sqlite mutex poisoned");
        let affected = conn.execute(
            "UPDATE agent_tokens SET revoked = 1 WHERE token_id = ?1",
            params![token_id],
        )?;
        Ok(affected > 0)
    }

    pub fn rotate_token(&self, token_id: &str) -> Result<Option<IssuedAgentToken>> {
        let plaintext = generate_agent_token()?;
        let hash = hash_token(&plaintext);
        let now = Utc::now().to_rfc3339();
        let conn = self.conn.lock().expect("sqlite mutex poisoned");
        let affected = conn.execute(
            "UPDATE agent_tokens SET token_hash = ?1, rotated_at = ?2 WHERE token_id = ?3 AND revoked = 0",
            params![hash, now, token_id],
        )?;
        if affected == 0 {
            return Ok(None);
        }
        let record = conn.query_row(
            "SELECT token_id, agent_id, label, scopes_json, max_sessions, revoked, created_at, rotated_at
             FROM agent_tokens WHERE token_id = ?1",
            params![token_id],
            |row| {
                let scopes_json: String = row.get(3)?;
                let scopes: Vec<String> = serde_json::from_str(&scopes_json).unwrap_or_default();
                Ok(AgentTokenRecord {
                    token_id: row.get(0)?,
                    agent_id: row.get(1)?,
                    label: row.get(2)?,
                    scopes,
                    max_sessions: row.get(4)?,
                    revoked: row.get::<_, i64>(5)? != 0,
                    created_at: row.get(6)?,
                    rotated_at: row.get(7)?,
                })
            },
        )?;
        Ok(Some(IssuedAgentToken {
            token: record,
            bearer_token: plaintext,
        }))
    }

    pub fn count_active_sessions_for_agent(&self, agent_id: &str) -> Result<u32> {
        let conn = self.conn.lock().expect("sqlite mutex poisoned");
        let count: i64 = conn.query_row(
            "SELECT COUNT(*) FROM browser_sessions WHERE owner_agent_id = ?1 AND state != 'CLOSED'",
            params![agent_id],
            |row| row.get(0),
        )?;
        Ok(count.max(0) as u32)
    }

    pub fn count_running_sessions_total(&self) -> Result<u32> {
        let conn = self.conn.lock().expect("sqlite mutex poisoned");
        let count: i64 = conn.query_row(
            "SELECT COUNT(*) FROM browser_sessions WHERE state IN ('CREATING', 'RUNNING', 'IDLE', 'RESTORING')",
            [],
            |row| row.get(0),
        )?;
        Ok(count.max(0) as u32)
    }

    pub fn insert_session(&self, record: &SessionRecord) -> Result<()> {
        let conn = self.conn.lock().expect("sqlite mutex poisoned");
        conn.execute(
            "INSERT INTO browser_sessions
             (session_id, owner_agent_id, display_name, profile_id, state, proxy_url, current_url,
              idle_timeout_seconds, created_at, updated_at, last_activity_at)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11)",
            params![
                record.session_id,
                record.owner_agent_id,
                record.display_name,
                record.profile_id,
                record.state,
                record.proxy_url,
                record.current_url,
                record.idle_timeout_seconds as i64,
                record.created_at,
                record.updated_at,
                record.last_activity_at,
            ],
        )?;
        Ok(())
    }

    pub fn get_session(&self, session_id: &str) -> Result<Option<SessionRecord>> {
        let conn = self.conn.lock().expect("sqlite mutex poisoned");
        let row = conn
            .query_row(
                "SELECT session_id, owner_agent_id, display_name, profile_id, state, proxy_url,
                        current_url, idle_timeout_seconds, created_at, updated_at, last_activity_at
                 FROM browser_sessions WHERE session_id = ?1",
                params![session_id],
                Self::map_session,
            )
            .optional()?;
        Ok(row)
    }

    pub fn list_sessions_for_agent(
        &self,
        agent_id: &str,
        include_closed: bool,
    ) -> Result<Vec<SessionRecord>> {
        let conn = self.conn.lock().expect("sqlite mutex poisoned");
        let sql = if include_closed {
            "SELECT session_id, owner_agent_id, display_name, profile_id, state, proxy_url,
                    current_url, idle_timeout_seconds, created_at, updated_at, last_activity_at
             FROM browser_sessions WHERE owner_agent_id = ?1 ORDER BY created_at DESC"
        } else {
            "SELECT session_id, owner_agent_id, display_name, profile_id, state, proxy_url,
                    current_url, idle_timeout_seconds, created_at, updated_at, last_activity_at
             FROM browser_sessions WHERE owner_agent_id = ?1 AND state != 'CLOSED' ORDER BY created_at DESC"
        };
        let mut stmt = conn.prepare(sql)?;
        let rows = stmt
            .query_map(params![agent_id], Self::map_session)?
            .collect::<std::result::Result<Vec<_>, _>>()?;
        Ok(rows)
    }

    pub fn list_running_sessions(&self) -> Result<Vec<SessionRecord>> {
        let conn = self.conn.lock().expect("sqlite mutex poisoned");
        let mut stmt = conn.prepare(
            "SELECT session_id, owner_agent_id, display_name, profile_id, state, proxy_url,
                    current_url, idle_timeout_seconds, created_at, updated_at, last_activity_at
             FROM browser_sessions WHERE state IN ('RUNNING', 'IDLE')",
        )?;
        let rows = stmt
            .query_map([], Self::map_session)?
            .collect::<std::result::Result<Vec<_>, _>>()?;
        Ok(rows)
    }

    /// Marks any leftover running sessions from a previous process crash as HIBERNATED so they
    /// cleanly auto-wake on next request.
    pub fn mark_stale_running_hibernated(&self) -> Result<usize> {
        let now = Utc::now().to_rfc3339();
        let conn = self.conn.lock().expect("sqlite mutex poisoned");
        let affected = conn.execute(
            "UPDATE browser_sessions SET state = 'HIBERNATED', updated_at = ?1
             WHERE state IN ('CREATING', 'RUNNING', 'IDLE', 'RESTORING')",
            params![now],
        )?;
        Ok(affected)
    }

    pub fn update_session_state(
        &self,
        session_id: &str,
        state: &str,
        current_url: Option<&str>,
    ) -> Result<()> {
        let now = Utc::now().to_rfc3339();
        let conn = self.conn.lock().expect("sqlite mutex poisoned");
        conn.execute(
            "UPDATE browser_sessions
             SET state = ?1, current_url = COALESCE(?2, current_url), updated_at = ?3, last_activity_at = ?3
             WHERE session_id = ?4",
            params![state, current_url, now, session_id],
        )?;
        Ok(())
    }

    pub fn touch_session_activity(
        &self,
        session_id: &str,
        current_url: Option<&str>,
    ) -> Result<()> {
        let now = Utc::now().to_rfc3339();
        let conn = self.conn.lock().expect("sqlite mutex poisoned");
        conn.execute(
            "UPDATE browser_sessions
             SET current_url = COALESCE(?1, current_url), updated_at = ?2, last_activity_at = ?2
             WHERE session_id = ?3",
            params![current_url, now, session_id],
        )?;
        Ok(())
    }

    pub fn insert_checkpoint(&self, checkpoint: &ProfileCheckpointRecord) -> Result<()> {
        let conn = self.conn.lock().expect("sqlite mutex poisoned");
        conn.execute(
            "INSERT INTO profile_checkpoints
             (checkpoint_id, profile_id, owner_agent_id, archive_sha256, archive_bytes, file_count, created_at)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7)",
            params![
                checkpoint.checkpoint_id,
                checkpoint.profile_id,
                checkpoint.owner_agent_id,
                checkpoint.archive_sha256,
                checkpoint.archive_bytes as i64,
                checkpoint.file_count as i64,
                checkpoint.created_at,
            ],
        )?;
        Ok(())
    }

    pub fn list_checkpoints(
        &self,
        profile_id: &str,
        owner_agent_id: &str,
    ) -> Result<Vec<ProfileCheckpointRecord>> {
        let conn = self.conn.lock().expect("sqlite mutex poisoned");
        let mut stmt = conn.prepare(
            "SELECT checkpoint_id, profile_id, owner_agent_id, archive_sha256, archive_bytes, file_count, created_at
             FROM profile_checkpoints
             WHERE profile_id = ?1 AND owner_agent_id = ?2
             ORDER BY created_at DESC",
        )?;
        let rows = stmt
            .query_map(params![profile_id, owner_agent_id], |row| {
                Ok(ProfileCheckpointRecord {
                    checkpoint_id: row.get(0)?,
                    profile_id: row.get(1)?,
                    owner_agent_id: row.get(2)?,
                    archive_sha256: row.get(3)?,
                    archive_bytes: row.get::<_, i64>(4)?.max(0) as u64,
                    file_count: row.get::<_, i64>(5)?.max(0) as u64,
                    created_at: row.get(6)?,
                })
            })?
            .collect::<std::result::Result<Vec<_>, _>>()?;
        Ok(rows)
    }

    pub fn record_audit(
        &self,
        agent_id: &str,
        session_id: Option<&str>,
        action: &str,
        outcome: &str,
        detail: Option<&str>,
    ) {
        let now = Utc::now().to_rfc3339();
        if let Ok(conn) = self.conn.lock() {
            let _ = conn.execute(
                "INSERT INTO audit_events (agent_id, session_id, action, outcome, detail, created_at)
                 VALUES (?1, ?2, ?3, ?4, ?5, ?6)",
                params![agent_id, session_id, action, outcome, detail, now],
            );
        }
    }

    fn map_session(row: &rusqlite::Row<'_>) -> rusqlite::Result<SessionRecord> {
        Ok(SessionRecord {
            session_id: row.get(0)?,
            owner_agent_id: row.get(1)?,
            display_name: row.get(2)?,
            profile_id: row.get(3)?,
            state: row.get(4)?,
            proxy_url: row.get(5)?,
            current_url: row.get(6)?,
            idle_timeout_seconds: row.get::<_, i64>(7)?.max(0) as u64,
            created_at: row.get(8)?,
            updated_at: row.get(9)?,
            last_activity_at: row.get(10)?,
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn enforces_token_auth_rotation_revocation_and_agent_isolation() {
        let dir = std::env::temp_dir().join(format!("lite-store-{}", Uuid::new_v4().simple()));
        let store = LiteStore::open(&dir.join("lite.sqlite3")).unwrap();

        let bootstrap = store.ensure_bootstrap_token(None, 5).unwrap().unwrap();
        assert!(store.ensure_bootstrap_token(None, 5).unwrap().is_none());
        let authed = store
            .authenticate(&bootstrap.bearer_token)
            .unwrap()
            .unwrap();
        assert_eq!(authed.agent_id, "agent_admin");

        let agent_a = store
            .create_token("agent_a", "Agent A", vec!["mcp".to_owned()], 2)
            .unwrap();
        let agent_b = store
            .create_token("agent_b", "Agent B", vec!["mcp".to_owned()], 2)
            .unwrap();

        let now = Utc::now().to_rfc3339();
        store
            .insert_session(&SessionRecord {
                session_id: "ses_a1".to_owned(),
                owner_agent_id: "agent_a".to_owned(),
                display_name: "A1".to_owned(),
                profile_id: "prf_a1".to_owned(),
                state: "RUNNING".to_owned(),
                proxy_url: None,
                current_url: None,
                idle_timeout_seconds: 600,
                created_at: now.clone(),
                updated_at: now.clone(),
                last_activity_at: now,
            })
            .unwrap();

        assert_eq!(
            store
                .list_sessions_for_agent("agent_a", false)
                .unwrap()
                .len(),
            1
        );
        assert_eq!(
            store
                .list_sessions_for_agent("agent_b", false)
                .unwrap()
                .len(),
            0
        );

        let rotated = store
            .rotate_token(&agent_a.token.token_id)
            .unwrap()
            .unwrap();
        assert!(store.authenticate(&agent_a.bearer_token).unwrap().is_none());
        assert!(store.authenticate(&rotated.bearer_token).unwrap().is_some());

        assert!(store.revoke_token(&agent_b.token.token_id).unwrap());
        assert!(store.authenticate(&agent_b.bearer_token).unwrap().is_none());

        let _ = std::fs::remove_dir_all(&dir);
    }
}
