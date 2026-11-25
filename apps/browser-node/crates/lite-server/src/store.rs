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
    #[serde(default)]
    pub allowed_private_hosts: Vec<String>,
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
    #[serde(default)]
    pub owner_token_id: String,
    pub display_name: String,
    pub profile_id: String,
    pub state: String,
    pub proxy_url: Option<String>,
    pub current_url: Option<String>,
    pub isolation_mode: String,
    #[serde(default)]
    pub allowed_private_hosts: Vec<String>,
    pub idle_timeout_seconds: u64,
    pub created_at: String,
    pub updated_at: String,
    pub last_activity_at: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ProfileRecord {
    pub profile_id: String,
    pub owner_account_id: String,
    pub display_name: String,
    #[serde(default)]
    pub allowed_private_hosts: Vec<String>,
    pub created_at: String,
    pub updated_at: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ProfileGrantRecord {
    pub profile_id: String,
    pub grantee_agent_id: String,
    pub granted_by: String,
    pub granted_at: String,
    pub revoked_at: Option<String>,
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

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CredentialMetadata {
    pub credential_id: String,
    pub owner_agent_id: String,
    pub label: String,
    pub allowed_domain: String,
    pub expires_at: Option<String>,
    pub revoked: bool,
    pub created_at: String,
}

#[derive(Debug, Clone)]
pub struct StoredCredential {
    pub meta: CredentialMetadata,
    pub ciphertext_b64: String,
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
        // Cap SQLite page cache at 2 MiB and disable mmap for predictable low-memory RSS.
        conn.pragma_update(None, "cache_size", -2048)?;
        conn.pragma_update(None, "temp_store", "MEMORY")?;
        conn.pragma_update(None, "mmap_size", 0)?;
        conn.execute_batch(
            r#"
            CREATE TABLE IF NOT EXISTS agent_tokens (
                token_id TEXT PRIMARY KEY,
                agent_id TEXT NOT NULL,
                label TEXT NOT NULL,
                token_hash TEXT NOT NULL UNIQUE,
                scopes_json TEXT NOT NULL,
                max_sessions INTEGER NOT NULL,
                allowed_private_hosts_json TEXT NOT NULL DEFAULT '[]',
                revoked INTEGER NOT NULL DEFAULT 0,
                created_at TEXT NOT NULL,
                rotated_at TEXT
            );
            CREATE INDEX IF NOT EXISTS idx_agent_tokens_hash ON agent_tokens(token_hash);

            CREATE TABLE IF NOT EXISTS browser_sessions (
                session_id TEXT PRIMARY KEY,
                owner_agent_id TEXT NOT NULL,
                owner_token_id TEXT NOT NULL DEFAULT '',
                display_name TEXT NOT NULL,
                profile_id TEXT NOT NULL,
                state TEXT NOT NULL,
                proxy_url TEXT,
                current_url TEXT,
                isolation_mode TEXT NOT NULL DEFAULT 'process',
                allowed_private_hosts_json TEXT NOT NULL DEFAULT '[]',
                idle_timeout_seconds INTEGER NOT NULL,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL,
                last_activity_at TEXT NOT NULL
            );
            CREATE INDEX IF NOT EXISTS idx_browser_sessions_owner ON browser_sessions(owner_agent_id);
            CREATE INDEX IF NOT EXISTS idx_browser_sessions_profile ON browser_sessions(profile_id);

            CREATE TABLE IF NOT EXISTS profiles (
                profile_id TEXT PRIMARY KEY,
                owner_account_id TEXT NOT NULL,
                display_name TEXT NOT NULL,
                allowed_private_hosts_json TEXT NOT NULL DEFAULT '[]',
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL
            );
            CREATE INDEX IF NOT EXISTS idx_profiles_owner ON profiles(owner_account_id);

            CREATE TABLE IF NOT EXISTS profile_grants (
                profile_id TEXT NOT NULL,
                grantee_agent_id TEXT NOT NULL,
                granted_by TEXT NOT NULL,
                granted_at TEXT NOT NULL,
                revoked_at TEXT,
                PRIMARY KEY (profile_id, grantee_agent_id)
            );
            CREATE INDEX IF NOT EXISTS idx_profile_grants_grantee ON profile_grants(grantee_agent_id);

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

            CREATE TABLE IF NOT EXISTS credentials (
                credential_id TEXT PRIMARY KEY,
                owner_agent_id TEXT NOT NULL,
                label TEXT NOT NULL,
                allowed_domain TEXT NOT NULL,
                ciphertext_b64 TEXT NOT NULL,
                expires_at TEXT,
                revoked INTEGER NOT NULL DEFAULT 0,
                created_at TEXT NOT NULL
            );
            CREATE INDEX IF NOT EXISTS idx_credentials_owner ON credentials(owner_agent_id);
            "#,
        )?;
        // Expand-only migrations for existing L0 SQLite databases.
        let _ = conn.execute(
            "ALTER TABLE agent_tokens ADD COLUMN allowed_private_hosts_json TEXT NOT NULL DEFAULT '[]'",
            [],
        );
        let _ = conn.execute(
            "ALTER TABLE browser_sessions ADD COLUMN isolation_mode TEXT NOT NULL DEFAULT 'process'",
            [],
        );
        let _ = conn.execute(
            "ALTER TABLE browser_sessions ADD COLUMN owner_token_id TEXT NOT NULL DEFAULT ''",
            [],
        );
        let _ = conn.execute(
            "ALTER TABLE browser_sessions ADD COLUMN allowed_private_hosts_json TEXT NOT NULL DEFAULT '[]'",
            [],
        );
        let _ = conn.execute(
            "ALTER TABLE profiles ADD COLUMN allowed_private_hosts_json TEXT NOT NULL DEFAULT '[]'",
            [],
        );
        // Post-migration composite indexes for O(1) hot-path quota, single-writer, and retention queries.
        conn.execute_batch(
            r#"
            CREATE INDEX IF NOT EXISTS idx_browser_sessions_state ON browser_sessions(state);
            CREATE INDEX IF NOT EXISTS idx_browser_sessions_token_state ON browser_sessions(owner_token_id, state);
            CREATE INDEX IF NOT EXISTS idx_browser_sessions_profile_state ON browser_sessions(profile_id, state);
            CREATE INDEX IF NOT EXISTS idx_audit_events_created_at ON audit_events(created_at);
            "#,
        )?;
        Self::backfill_legacy_profiles_and_grants(&conn)?;
        Ok(Self {
            conn: Mutex::new(conn),
        })
    }

    /// Backfills `profiles` and `profile_grants` for legacy L0 SQLite databases upgraded from
    /// versions that only stored `browser_sessions` / `profile_checkpoints`.
    ///
    /// Grants are only created for profiles that were missing from `profiles` before this call,
    /// ensuring later explicit admin revocations are never reverted across restarts.
    fn backfill_legacy_profiles_and_grants(conn: &Connection) -> Result<()> {
        let admin_owner: String = conn
            .query_row(
                "SELECT agent_id FROM agent_tokens
                 WHERE scopes_json LIKE '%\"admin\"%'
                 ORDER BY created_at ASC LIMIT 1",
                [],
                |row| row.get(0),
            )
            .optional()?
            .unwrap_or_else(|| "agent_admin".to_owned());

        let missing_profile_ids: Vec<String> = {
            let mut stmt = conn.prepare(
                "SELECT DISTINCT profile_id FROM (
                    SELECT profile_id FROM browser_sessions
                    UNION
                    SELECT profile_id FROM profile_checkpoints
                 ) legacy
                 WHERE legacy.profile_id != ''
                   AND NOT EXISTS (
                       SELECT 1 FROM profiles p WHERE p.profile_id = legacy.profile_id
                   )",
            )?;
            let rows = stmt.query_map([], |row| row.get::<_, String>(0))?;
            rows.collect::<std::result::Result<Vec<_>, _>>()?
        };

        if missing_profile_ids.is_empty() {
            return Ok(());
        }

        let now = Utc::now().to_rfc3339();
        for profile_id in missing_profile_ids {
            let latest_session: Option<(String, String, String)> = conn
                .query_row(
                    "SELECT display_name, created_at, updated_at
                     FROM browser_sessions
                     WHERE profile_id = ?1
                     ORDER BY updated_at DESC LIMIT 1",
                    params![&profile_id],
                    |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?)),
                )
                .optional()?;

            let (display_name, created_at, updated_at) = match latest_session {
                Some(tuple) => tuple,
                None => {
                    let cp_created: String = conn
                        .query_row(
                            "SELECT created_at FROM profile_checkpoints
                             WHERE profile_id = ?1 ORDER BY created_at DESC LIMIT 1",
                            params![&profile_id],
                            |row| row.get(0),
                        )
                        .optional()?
                        .unwrap_or_else(|| now.clone());
                    (profile_id.clone(), cp_created.clone(), cp_created)
                }
            };

            let mut merged_hosts: Vec<String> = Vec::new();
            {
                let mut hstmt = conn.prepare(
                    "SELECT allowed_private_hosts_json FROM browser_sessions WHERE profile_id = ?1",
                )?;
                let host_jsons =
                    hstmt.query_map(params![&profile_id], |row| row.get::<_, String>(0))?;
                for raw in host_jsons.flatten() {
                    if let Ok(parsed) = serde_json::from_str::<Vec<String>>(&raw) {
                        for host in parsed {
                            if !merged_hosts.contains(&host) {
                                merged_hosts.push(host);
                            }
                        }
                    }
                }
            }
            let hosts_json = serde_json::to_string(&merged_hosts)?;

            conn.execute(
                "INSERT OR IGNORE INTO profiles
                 (profile_id, owner_account_id, display_name, allowed_private_hosts_json, created_at, updated_at)
                 VALUES (?1, ?2, ?3, ?4, ?5, ?6)",
                params![
                    &profile_id,
                    &admin_owner,
                    &display_name,
                    &hosts_json,
                    &created_at,
                    &updated_at,
                ],
            )?;

            let legacy_agents: Vec<String> = {
                let mut astmt = conn.prepare(
                    "SELECT DISTINCT owner_agent_id FROM (
                        SELECT owner_agent_id FROM browser_sessions WHERE profile_id = ?1
                        UNION
                        SELECT owner_agent_id FROM profile_checkpoints WHERE profile_id = ?1
                     ) owners
                     WHERE owner_agent_id != '' AND owner_agent_id != ?2",
                )?;
                let rows = astmt.query_map(params![&profile_id, &admin_owner], |row| {
                    row.get::<_, String>(0)
                })?;
                rows.collect::<std::result::Result<Vec<_>, _>>()?
            };

            for agent_id in legacy_agents {
                conn.execute(
                    "INSERT OR IGNORE INTO profile_grants
                     (profile_id, grantee_agent_id, granted_by, granted_at, revoked_at)
                     VALUES (?1, ?2, ?3, ?4, NULL)",
                    params![&profile_id, &agent_id, &admin_owner, &now],
                )?;
            }
        }
        Ok(())
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
            Vec::new(),
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
        self.create_token_with_policy(agent_id, label, scopes, max_sessions, Vec::new())
    }

    pub fn create_token_with_policy(
        &self,
        agent_id: &str,
        label: &str,
        scopes: Vec<String>,
        max_sessions: u32,
        allowed_private_hosts: Vec<String>,
    ) -> Result<IssuedAgentToken> {
        let plaintext = generate_agent_token()?;
        self.insert_token(
            agent_id,
            label,
            scopes,
            max_sessions,
            allowed_private_hosts,
            &plaintext,
        )
    }

    fn insert_token(
        &self,
        agent_id: &str,
        label: &str,
        scopes: Vec<String>,
        max_sessions: u32,
        allowed_private_hosts: Vec<String>,
        plaintext: &str,
    ) -> Result<IssuedAgentToken> {
        let token_id = format!("tok_{}", &Uuid::new_v4().simple().to_string()[..16]);
        let now = Utc::now().to_rfc3339();
        let hash = hash_token(plaintext);
        let scopes_json = serde_json::to_string(&scopes)?;
        let hosts_json = serde_json::to_string(&allowed_private_hosts)?;
        let conn = self.conn.lock().expect("sqlite mutex poisoned");
        conn.execute(
            "INSERT INTO agent_tokens (token_id, agent_id, label, token_hash, scopes_json, max_sessions, allowed_private_hosts_json, revoked, created_at)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, 0, ?8)",
            params![token_id, agent_id, label, hash, scopes_json, max_sessions, hosts_json, now],
        )?;
        Ok(IssuedAgentToken {
            token: AgentTokenRecord {
                token_id,
                agent_id: agent_id.to_owned(),
                label: label.to_owned(),
                scopes,
                max_sessions,
                allowed_private_hosts,
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
        let mut stmt = conn.prepare_cached(
            "SELECT token_id, agent_id, label, scopes_json, max_sessions, allowed_private_hosts_json, revoked, created_at, rotated_at
             FROM agent_tokens WHERE token_hash = ?1",
        )?;
        let row = stmt.query_row(params![hash], Self::map_token).optional()?;
        Ok(row.filter(|record| !record.revoked))
    }

    pub fn list_tokens(&self) -> Result<Vec<AgentTokenRecord>> {
        let conn = self.conn.lock().expect("sqlite mutex poisoned");
        let mut stmt = conn.prepare(
            "SELECT token_id, agent_id, label, scopes_json, max_sessions, allowed_private_hosts_json, revoked, created_at, rotated_at
             FROM agent_tokens ORDER BY created_at DESC",
        )?;
        let rows = stmt
            .query_map([], Self::map_token)?
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
            "SELECT token_id, agent_id, label, scopes_json, max_sessions, allowed_private_hosts_json, revoked, created_at, rotated_at
             FROM agent_tokens WHERE token_id = ?1",
            params![token_id],
            Self::map_token,
        )?;
        Ok(Some(IssuedAgentToken {
            token: record,
            bearer_token: plaintext,
        }))
    }

    pub fn update_token_quota(
        &self,
        token_id: &str,
        max_sessions: u32,
    ) -> Result<Option<AgentTokenRecord>> {
        self.update_token_policy(token_id, Some(max_sessions), None)
    }

    pub fn update_token_policy(
        &self,
        token_id: &str,
        max_sessions: Option<u32>,
        allowed_private_hosts: Option<&[String]>,
    ) -> Result<Option<AgentTokenRecord>> {
        let conn = self.conn.lock().expect("sqlite mutex poisoned");
        let existing = conn
            .query_row(
                "SELECT token_id, agent_id, label, scopes_json, max_sessions, allowed_private_hosts_json, revoked, created_at, rotated_at
                 FROM agent_tokens WHERE token_id = ?1 AND revoked = 0",
                params![token_id],
                Self::map_token,
            )
            .optional()?;
        let Some(current) = existing else {
            return Ok(None);
        };
        let next_max = max_sessions.unwrap_or(current.max_sessions);
        let next_hosts = allowed_private_hosts
            .map(|h| h.to_vec())
            .unwrap_or(current.allowed_private_hosts);
        let hosts_json = serde_json::to_string(&next_hosts)?;
        conn.execute(
            "UPDATE agent_tokens SET max_sessions = ?1, allowed_private_hosts_json = ?2 WHERE token_id = ?3 AND revoked = 0",
            params![next_max, hosts_json, token_id],
        )?;
        let record = conn.query_row(
            "SELECT token_id, agent_id, label, scopes_json, max_sessions, allowed_private_hosts_json, revoked, created_at, rotated_at
             FROM agent_tokens WHERE token_id = ?1",
            params![token_id],
            Self::map_token,
        )?;
        Ok(Some(record))
    }

    pub fn get_token_by_id(&self, token_id: &str) -> Result<Option<AgentTokenRecord>> {
        let conn = self.conn.lock().expect("sqlite mutex poisoned");
        let row = conn
            .query_row(
                "SELECT token_id, agent_id, label, scopes_json, max_sessions, allowed_private_hosts_json, revoked, created_at, rotated_at
                 FROM agent_tokens WHERE token_id = ?1",
                params![token_id],
                Self::map_token,
            )
            .optional()?;
        Ok(row)
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

    /// Counts currently running browser sessions owned by a specific Agent Token (with legacy
    /// fallback to `owner_agent_id` for pre-migration rows where `owner_token_id` is empty).
    pub fn count_running_sessions_for_token(
        &self,
        token_id: &str,
        agent_id: &str,
        exclude_session_id: Option<&str>,
    ) -> Result<u32> {
        let conn = self.conn.lock().expect("sqlite mutex poisoned");
        let mut stmt = conn.prepare_cached(
            "SELECT COUNT(*) FROM browser_sessions
             WHERE ((owner_token_id != '' AND owner_token_id = ?1)
                    OR (owner_token_id = '' AND owner_agent_id = ?2))
               AND state IN ('CREATING', 'RUNNING', 'IDLE', 'RESTORING')
               AND (?3 IS NULL OR session_id != ?3)",
        )?;
        let count: i64 = stmt
            .query_row(params![token_id, agent_id, exclude_session_id], |row| {
                row.get(0)
            })?;
        Ok(count.max(0) as u32)
    }

    pub fn count_running_sessions_total(&self) -> Result<u32> {
        self.count_running_sessions_excluding(None)
    }

    pub fn count_running_sessions_excluding(
        &self,
        exclude_session_id: Option<&str>,
    ) -> Result<u32> {
        let conn = self.conn.lock().expect("sqlite mutex poisoned");
        let mut stmt = conn.prepare_cached(
            "SELECT COUNT(*) FROM browser_sessions
             WHERE state IN ('CREATING', 'RUNNING', 'IDLE', 'RESTORING')
               AND (?1 IS NULL OR session_id != ?1)",
        )?;
        let count: i64 = stmt.query_row(params![exclude_session_id], |row| row.get(0))?;
        Ok(count.max(0) as u32)
    }

    pub fn insert_session(&self, record: &SessionRecord) -> Result<()> {
        let hosts_json = serde_json::to_string(&record.allowed_private_hosts)?;
        let conn = self.conn.lock().expect("sqlite mutex poisoned");
        conn.execute(
            "INSERT INTO browser_sessions
             (session_id, owner_agent_id, owner_token_id, display_name, profile_id, state, proxy_url, current_url,
              isolation_mode, allowed_private_hosts_json, idle_timeout_seconds, created_at, updated_at, last_activity_at)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12, ?13, ?14)",
            params![
                record.session_id,
                record.owner_agent_id,
                record.owner_token_id,
                record.display_name,
                record.profile_id,
                record.state,
                record.proxy_url,
                record.current_url,
                record.isolation_mode,
                hosts_json,
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
        let mut stmt = conn.prepare_cached(
            "SELECT session_id, owner_agent_id, owner_token_id, display_name, profile_id, state, proxy_url,
                    current_url, isolation_mode, allowed_private_hosts_json, idle_timeout_seconds,
                    created_at, updated_at, last_activity_at
             FROM browser_sessions WHERE session_id = ?1",
        )?;
        let row = stmt
            .query_row(params![session_id], Self::map_session)
            .optional()?;
        Ok(row)
    }

    pub fn list_sessions_for_admin(&self, include_closed: bool) -> Result<Vec<SessionRecord>> {
        let conn = self.conn.lock().expect("sqlite mutex poisoned");
        let sql = if include_closed {
            "SELECT session_id, owner_agent_id, owner_token_id, display_name, profile_id, state, proxy_url,
                    current_url, isolation_mode, allowed_private_hosts_json, idle_timeout_seconds,
                    created_at, updated_at, last_activity_at
             FROM browser_sessions ORDER BY created_at DESC"
        } else {
            "SELECT session_id, owner_agent_id, owner_token_id, display_name, profile_id, state, proxy_url,
                    current_url, isolation_mode, allowed_private_hosts_json, idle_timeout_seconds,
                    created_at, updated_at, last_activity_at
             FROM browser_sessions WHERE state != 'CLOSED' ORDER BY created_at DESC"
        };
        let mut stmt = conn.prepare(sql)?;
        let rows = stmt
            .query_map([], Self::map_session)?
            .collect::<std::result::Result<Vec<_>, _>>()?;
        Ok(rows)
    }

    pub fn list_sessions_for_agent(
        &self,
        agent_id: &str,
        include_closed: bool,
    ) -> Result<Vec<SessionRecord>> {
        let conn = self.conn.lock().expect("sqlite mutex poisoned");
        let closed_filter = if include_closed {
            ""
        } else {
            "AND s.state != 'CLOSED'"
        };
        let sql = format!(
            "SELECT s.session_id, s.owner_agent_id, s.owner_token_id, s.display_name, s.profile_id,
                    s.state, s.proxy_url, s.current_url, s.isolation_mode, s.allowed_private_hosts_json,
                    s.idle_timeout_seconds, s.created_at, s.updated_at, s.last_activity_at
             FROM browser_sessions s
             WHERE (
                 (s.owner_agent_id = ?1 AND (
                     NOT EXISTS (SELECT 1 FROM profiles p WHERE p.profile_id = s.profile_id)
                     OR EXISTS (
                         SELECT 1 FROM profile_grants g
                         WHERE g.profile_id = s.profile_id AND g.grantee_agent_id = ?1 AND g.revoked_at IS NULL
                     )
                 ))
                 OR (s.state IN ('CREATING', 'RUNNING', 'IDLE', 'RESTORING') AND EXISTS (
                     SELECT 1 FROM profile_grants g
                     WHERE g.profile_id = s.profile_id AND g.grantee_agent_id = ?1 AND g.revoked_at IS NULL
                 ))
             ) {closed_filter}
             ORDER BY s.created_at DESC"
        );
        let mut stmt = conn.prepare(&sql)?;
        let rows = stmt
            .query_map(params![agent_id], Self::map_session)?
            .collect::<std::result::Result<Vec<_>, _>>()?;
        Ok(rows)
    }

    pub fn find_active_session_for_profile(
        &self,
        profile_id: &str,
        exclude_session_id: Option<&str>,
    ) -> Result<Option<SessionRecord>> {
        let conn = self.conn.lock().expect("sqlite mutex poisoned");
        let row = conn
            .query_row(
                "SELECT session_id, owner_agent_id, owner_token_id, display_name, profile_id, state, proxy_url,
                        current_url, isolation_mode, allowed_private_hosts_json, idle_timeout_seconds,
                        created_at, updated_at, last_activity_at
                 FROM browser_sessions
                 WHERE profile_id = ?1
                   AND state IN ('CREATING', 'RUNNING', 'IDLE', 'RESTORING')
                   AND (?2 IS NULL OR session_id != ?2)
                 ORDER BY updated_at DESC
                 LIMIT 1",
                params![profile_id, exclude_session_id],
                Self::map_session,
            )
            .optional()?;
        Ok(row)
    }

    pub fn update_session_private_hosts(&self, session_id: &str, hosts: &[String]) -> Result<()> {
        let now = Utc::now().to_rfc3339();
        let hosts_json = serde_json::to_string(hosts)?;
        let conn = self.conn.lock().expect("sqlite mutex poisoned");
        conn.execute(
            "UPDATE browser_sessions SET allowed_private_hosts_json = ?1, updated_at = ?2 WHERE session_id = ?3",
            params![hosts_json, now, session_id],
        )?;
        Ok(())
    }

    // ---------- Admin-Owned Profiles & Grants ----------

    pub fn upsert_profile(&self, profile: &ProfileRecord) -> Result<()> {
        let hosts_json = serde_json::to_string(&profile.allowed_private_hosts)?;
        let conn = self.conn.lock().expect("sqlite mutex poisoned");
        conn.execute(
            "INSERT INTO profiles (profile_id, owner_account_id, display_name, allowed_private_hosts_json, created_at, updated_at)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6)
             ON CONFLICT(profile_id) DO UPDATE SET
               display_name = excluded.display_name,
               allowed_private_hosts_json = excluded.allowed_private_hosts_json,
               updated_at = excluded.updated_at",
            params![
                profile.profile_id,
                profile.owner_account_id,
                profile.display_name,
                hosts_json,
                profile.created_at,
                profile.updated_at,
            ],
        )?;
        Ok(())
    }

    pub fn get_profile(&self, profile_id: &str) -> Result<Option<ProfileRecord>> {
        let conn = self.conn.lock().expect("sqlite mutex poisoned");
        let row = conn
            .query_row(
                "SELECT profile_id, owner_account_id, display_name, allowed_private_hosts_json, created_at, updated_at
                 FROM profiles WHERE profile_id = ?1",
                params![profile_id],
                Self::map_profile,
            )
            .optional()?;
        Ok(row)
    }

    pub fn list_profiles_for_admin(&self) -> Result<Vec<ProfileRecord>> {
        let conn = self.conn.lock().expect("sqlite mutex poisoned");
        let mut stmt = conn.prepare(
            "SELECT profile_id, owner_account_id, display_name, allowed_private_hosts_json, created_at, updated_at
             FROM profiles ORDER BY created_at DESC",
        )?;
        let rows = stmt
            .query_map([], Self::map_profile)?
            .collect::<std::result::Result<Vec<_>, _>>()?;
        Ok(rows)
    }

    pub fn list_profiles_for_agent(&self, agent_id: &str) -> Result<Vec<ProfileRecord>> {
        let conn = self.conn.lock().expect("sqlite mutex poisoned");
        let mut stmt = conn.prepare(
            "SELECT p.profile_id, p.owner_account_id, p.display_name, p.allowed_private_hosts_json,
                    p.created_at, p.updated_at
             FROM profiles p
             INNER JOIN profile_grants g ON p.profile_id = g.profile_id
             WHERE g.grantee_agent_id = ?1 AND g.revoked_at IS NULL
             ORDER BY p.created_at DESC",
        )?;
        let rows = stmt
            .query_map(params![agent_id], Self::map_profile)?
            .collect::<std::result::Result<Vec<_>, _>>()?;
        Ok(rows)
    }

    pub fn grant_profile(
        &self,
        profile_id: &str,
        grantee_agent_id: &str,
        granted_by: &str,
    ) -> Result<ProfileGrantRecord> {
        let now = Utc::now().to_rfc3339();
        let conn = self.conn.lock().expect("sqlite mutex poisoned");
        conn.execute(
            "INSERT INTO profile_grants (profile_id, grantee_agent_id, granted_by, granted_at, revoked_at)
             VALUES (?1, ?2, ?3, ?4, NULL)
             ON CONFLICT(profile_id, grantee_agent_id) DO UPDATE SET
               granted_by = excluded.granted_by,
               granted_at = excluded.granted_at,
               revoked_at = NULL",
            params![profile_id, grantee_agent_id, granted_by, now],
        )?;
        Ok(ProfileGrantRecord {
            profile_id: profile_id.to_owned(),
            grantee_agent_id: grantee_agent_id.to_owned(),
            granted_by: granted_by.to_owned(),
            granted_at: now,
            revoked_at: None,
        })
    }

    pub fn revoke_profile_grant(&self, profile_id: &str, grantee_agent_id: &str) -> Result<bool> {
        let now = Utc::now().to_rfc3339();
        let conn = self.conn.lock().expect("sqlite mutex poisoned");
        let affected = conn.execute(
            "UPDATE profile_grants SET revoked_at = ?1
             WHERE profile_id = ?2 AND grantee_agent_id = ?3 AND revoked_at IS NULL",
            params![now, profile_id, grantee_agent_id],
        )?;
        Ok(affected > 0)
    }

    pub fn has_active_profile_grant(&self, profile_id: &str, agent_id: &str) -> Result<bool> {
        let conn = self.conn.lock().expect("sqlite mutex poisoned");
        let mut stmt = conn.prepare_cached(
            "SELECT COUNT(*) FROM profile_grants
             WHERE profile_id = ?1 AND grantee_agent_id = ?2 AND revoked_at IS NULL",
        )?;
        let count: i64 = stmt.query_row(params![profile_id, agent_id], |row| row.get(0))?;
        Ok(count > 0)
    }

    pub fn list_active_grants_for_profile(&self, profile_id: &str) -> Result<Vec<String>> {
        let conn = self.conn.lock().expect("sqlite mutex poisoned");
        let mut stmt = conn.prepare(
            "SELECT grantee_agent_id FROM profile_grants
             WHERE profile_id = ?1 AND revoked_at IS NULL
             ORDER BY grantee_agent_id ASC",
        )?;
        let rows = stmt
            .query_map(params![profile_id], |row| row.get(0))?
            .collect::<std::result::Result<Vec<String>, _>>()?;
        Ok(rows)
    }

    pub fn update_profile_private_hosts(&self, profile_id: &str, hosts: &[String]) -> Result<()> {
        let now = Utc::now().to_rfc3339();
        let hosts_json = serde_json::to_string(hosts)?;
        let conn = self.conn.lock().expect("sqlite mutex poisoned");
        conn.execute(
            "UPDATE profiles SET allowed_private_hosts_json = ?1, updated_at = ?2 WHERE profile_id = ?3",
            params![hosts_json, now, profile_id],
        )?;
        Ok(())
    }

    pub fn list_running_sessions(&self) -> Result<Vec<SessionRecord>> {
        let conn = self.conn.lock().expect("sqlite mutex poisoned");
        let mut stmt = conn.prepare(
            "SELECT session_id, owner_agent_id, owner_token_id, display_name, profile_id, state, proxy_url,
                    current_url, isolation_mode, allowed_private_hosts_json, idle_timeout_seconds,
                    created_at, updated_at, last_activity_at
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
        let mut stmt = conn.prepare_cached(
            "UPDATE browser_sessions
             SET state = ?1, current_url = COALESCE(?2, current_url), updated_at = ?3, last_activity_at = ?3
             WHERE session_id = ?4",
        )?;
        stmt.execute(params![state, current_url, now, session_id])?;
        Ok(())
    }

    pub fn touch_session_activity(
        &self,
        session_id: &str,
        current_url: Option<&str>,
    ) -> Result<()> {
        let now = Utc::now().to_rfc3339();
        let conn = self.conn.lock().expect("sqlite mutex poisoned");
        let mut stmt = conn.prepare_cached(
            "UPDATE browser_sessions
             SET current_url = COALESCE(?1, current_url), updated_at = ?2, last_activity_at = ?2
             WHERE session_id = ?3",
        )?;
        stmt.execute(params![current_url, now, session_id])?;
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

    pub fn list_checkpoints_for_profile(
        &self,
        profile_id: &str,
    ) -> Result<Vec<ProfileCheckpointRecord>> {
        let conn = self.conn.lock().expect("sqlite mutex poisoned");
        let mut stmt = conn.prepare(
            "SELECT checkpoint_id, profile_id, owner_agent_id, archive_sha256, archive_bytes, file_count, created_at
             FROM profile_checkpoints
             WHERE profile_id = ?1
             ORDER BY created_at DESC",
        )?;
        let rows = stmt
            .query_map(params![profile_id], Self::map_checkpoint)?
            .collect::<std::result::Result<Vec<_>, _>>()?;
        Ok(rows)
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
            .query_map(params![profile_id, owner_agent_id], Self::map_checkpoint)?
            .collect::<std::result::Result<Vec<_>, _>>()?;
        Ok(rows)
    }

    pub fn insert_credential(&self, cred: &StoredCredential) -> Result<()> {
        let conn = self.conn.lock().expect("sqlite mutex poisoned");
        conn.execute(
            "INSERT INTO credentials
             (credential_id, owner_agent_id, label, allowed_domain, ciphertext_b64, expires_at, revoked, created_at)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8)",
            params![
                cred.meta.credential_id,
                cred.meta.owner_agent_id,
                cred.meta.label,
                cred.meta.allowed_domain,
                cred.ciphertext_b64,
                cred.meta.expires_at,
                if cred.meta.revoked { 1i64 } else { 0i64 },
                cred.meta.created_at,
            ],
        )?;
        Ok(())
    }

    pub fn list_credentials_for_agent(
        &self,
        owner_agent_id: &str,
    ) -> Result<Vec<CredentialMetadata>> {
        let conn = self.conn.lock().expect("sqlite mutex poisoned");
        let mut stmt = conn.prepare(
            "SELECT credential_id, owner_agent_id, label, allowed_domain, expires_at, revoked, created_at
             FROM credentials
             WHERE owner_agent_id = ?1 AND revoked = 0
             ORDER BY created_at DESC",
        )?;
        let rows = stmt
            .query_map(params![owner_agent_id], |row| {
                Ok(CredentialMetadata {
                    credential_id: row.get(0)?,
                    owner_agent_id: row.get(1)?,
                    label: row.get(2)?,
                    allowed_domain: row.get(3)?,
                    expires_at: row.get(4)?,
                    revoked: row.get::<_, i64>(5)? != 0,
                    created_at: row.get(6)?,
                })
            })?
            .collect::<std::result::Result<Vec<_>, _>>()?;
        Ok(rows)
    }

    pub fn get_credential(&self, credential_id: &str) -> Result<Option<StoredCredential>> {
        let conn = self.conn.lock().expect("sqlite mutex poisoned");
        let row = conn
            .query_row(
                "SELECT credential_id, owner_agent_id, label, allowed_domain, ciphertext_b64, expires_at, revoked, created_at
                 FROM credentials WHERE credential_id = ?1",
                params![credential_id],
                |row| {
                    Ok(StoredCredential {
                        meta: CredentialMetadata {
                            credential_id: row.get(0)?,
                            owner_agent_id: row.get(1)?,
                            label: row.get(2)?,
                            allowed_domain: row.get(3)?,
                            expires_at: row.get(5)?,
                            revoked: row.get::<_, i64>(6)? != 0,
                            created_at: row.get(7)?,
                        },
                        ciphertext_b64: row.get(4)?,
                    })
                },
            )
            .optional()?;
        Ok(row)
    }

    pub fn revoke_credential(&self, credential_id: &str, owner_agent_id: &str) -> Result<bool> {
        let conn = self.conn.lock().expect("sqlite mutex poisoned");
        let affected = conn.execute(
            "UPDATE credentials SET revoked = 1 WHERE credential_id = ?1 AND owner_agent_id = ?2",
            params![credential_id, owner_agent_id],
        )?;
        Ok(affected > 0)
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
            if let Ok(mut stmt) = conn.prepare_cached(
                "INSERT INTO audit_events (agent_id, session_id, action, outcome, detail, created_at)
                 VALUES (?1, ?2, ?3, ?4, ?5, ?6)",
            ) {
                let _ = stmt.execute(params![agent_id, session_id, action, outcome, detail, now]);
            }
        }
    }

    /// Retains at most `keep_latest` most recent audit rows so 72h+ long-running L0 deployments
    /// maintain bounded SQLite disk footprint.
    pub fn prune_old_audit_events(&self, keep_latest: usize) -> Result<usize> {
        let conn = self.conn.lock().expect("sqlite mutex poisoned");
        let deleted = conn.execute(
            "DELETE FROM audit_events
             WHERE event_id NOT IN (
                 SELECT event_id FROM audit_events ORDER BY event_id DESC LIMIT ?1
             )",
            params![keep_latest as i64],
        )?;
        Ok(deleted)
    }

    fn map_token(row: &rusqlite::Row<'_>) -> rusqlite::Result<AgentTokenRecord> {
        let scopes_json: String = row.get(3)?;
        let scopes: Vec<String> = serde_json::from_str(&scopes_json).unwrap_or_default();
        let hosts_json: String = row.get(5)?;
        let allowed_private_hosts: Vec<String> =
            serde_json::from_str(&hosts_json).unwrap_or_default();
        Ok(AgentTokenRecord {
            token_id: row.get(0)?,
            agent_id: row.get(1)?,
            label: row.get(2)?,
            scopes,
            max_sessions: row.get(4)?,
            allowed_private_hosts,
            revoked: row.get::<_, i64>(6)? != 0,
            created_at: row.get(7)?,
            rotated_at: row.get(8)?,
        })
    }

    fn map_checkpoint(row: &rusqlite::Row<'_>) -> rusqlite::Result<ProfileCheckpointRecord> {
        Ok(ProfileCheckpointRecord {
            checkpoint_id: row.get(0)?,
            profile_id: row.get(1)?,
            owner_agent_id: row.get(2)?,
            archive_sha256: row.get(3)?,
            archive_bytes: row.get::<_, i64>(4)?.max(0) as u64,
            file_count: row.get::<_, i64>(5)?.max(0) as u64,
            created_at: row.get(6)?,
        })
    }

    fn map_profile(row: &rusqlite::Row<'_>) -> rusqlite::Result<ProfileRecord> {
        let hosts_json: String = row.get(3)?;
        let allowed_private_hosts: Vec<String> =
            serde_json::from_str(&hosts_json).unwrap_or_default();
        Ok(ProfileRecord {
            profile_id: row.get(0)?,
            owner_account_id: row.get(1)?,
            display_name: row.get(2)?,
            allowed_private_hosts,
            created_at: row.get(4)?,
            updated_at: row.get(5)?,
        })
    }

    fn map_session(row: &rusqlite::Row<'_>) -> rusqlite::Result<SessionRecord> {
        let hosts_json: String = row.get(9)?;
        let allowed_private_hosts: Vec<String> =
            serde_json::from_str(&hosts_json).unwrap_or_default();
        Ok(SessionRecord {
            session_id: row.get(0)?,
            owner_agent_id: row.get(1)?,
            owner_token_id: row.get(2)?,
            display_name: row.get(3)?,
            profile_id: row.get(4)?,
            state: row.get(5)?,
            proxy_url: row.get(6)?,
            current_url: row.get(7)?,
            isolation_mode: row.get(8)?,
            allowed_private_hosts,
            idle_timeout_seconds: row.get::<_, i64>(10)?.max(0) as u64,
            created_at: row.get(11)?,
            updated_at: row.get(12)?,
            last_activity_at: row.get(13)?,
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
                owner_token_id: agent_a.token.token_id.clone(),
                display_name: "A1".to_owned(),
                profile_id: "prf_a1".to_owned(),
                state: "RUNNING".to_owned(),
                proxy_url: None,
                current_url: None,
                isolation_mode: "process".to_owned(),
                allowed_private_hosts: Vec::new(),
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

        for idx in 0..10 {
            store.record_audit("agent_a", Some("ses_a1"), &format!("ACT_{idx}"), "OK", None);
        }
        let pruned = store.prune_old_audit_events(3).unwrap();
        assert_eq!(pruned, 7);

        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn migrates_legacy_sqlite_sessions_into_admin_profiles_and_preserves_revocations() {
        let dir = std::env::temp_dir().join(format!("lite-legacy-mig-{}", Uuid::new_v4().simple()));
        std::fs::create_dir_all(&dir).unwrap();
        let db_path = dir.join("lite.sqlite3");

        // Build an exact pre-profile-table legacy L0 SQLite database.
        {
            let conn = Connection::open(&db_path).unwrap();
            conn.execute_batch(
                r#"
                CREATE TABLE agent_tokens (
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
                CREATE TABLE browser_sessions (
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
                CREATE TABLE profile_checkpoints (
                    checkpoint_id TEXT PRIMARY KEY,
                    profile_id TEXT NOT NULL,
                    owner_agent_id TEXT NOT NULL,
                    archive_sha256 TEXT NOT NULL,
                    archive_bytes INTEGER NOT NULL,
                    file_count INTEGER NOT NULL,
                    created_at TEXT NOT NULL
                );
                INSERT INTO agent_tokens
                    (token_id, agent_id, label, token_hash, scopes_json, max_sessions, revoked, created_at)
                VALUES
                    ('tok_adm', 'agent_admin', 'Admin', 'h_adm', '["admin","mcp","sessions"]', 4, 0, '2026-01-01T00:00:00Z'),
                    ('tok_a', 'agent_legacy', 'Legacy', 'h_a', '["mcp","sessions"]', 2, 0, '2026-01-01T00:00:01Z');
                INSERT INTO browser_sessions
                    (session_id, owner_agent_id, display_name, profile_id, state, idle_timeout_seconds, created_at, updated_at, last_activity_at)
                VALUES
                    ('ses_old1', 'agent_legacy', 'Legacy Shop', 'prf_old1', 'HIBERNATED', 900, '2026-01-01T00:00:02Z', '2026-01-01T00:00:05Z', '2026-01-01T00:00:05Z'),
                    ('ses_old2', 'agent_admin', 'Admin Portal', 'prf_old2', 'HIBERNATED', 900, '2026-01-01T00:00:03Z', '2026-01-01T00:00:06Z', '2026-01-01T00:00:06Z');
                INSERT INTO profile_checkpoints
                    (checkpoint_id, profile_id, owner_agent_id, archive_sha256, archive_bytes, file_count, created_at)
                VALUES
                    ('chk_old3', 'prf_old3', 'agent_legacy', 'abc', 128, 1, '2026-01-01T00:00:04Z');
                "#,
            )
            .unwrap();
        }

        // Open with new LiteStore: legacy profiles & grants MUST be backfilled automatically.
        let store = LiteStore::open(&db_path).unwrap();
        let all_profiles = store.list_profiles_for_admin().unwrap();
        assert_eq!(all_profiles.len(), 3);
        let p1 = store.get_profile("prf_old1").unwrap().unwrap();
        assert_eq!(p1.owner_account_id, "agent_admin");
        assert_eq!(p1.display_name, "Legacy Shop");
        assert!(store
            .has_active_profile_grant("prf_old1", "agent_legacy")
            .unwrap());
        assert!(store
            .has_active_profile_grant("prf_old3", "agent_legacy")
            .unwrap());
        assert!(!store
            .has_active_profile_grant("prf_old2", "agent_legacy")
            .unwrap());
        assert_eq!(
            store.list_profiles_for_agent("agent_legacy").unwrap().len(),
            2
        );

        // Revoking a migrated grant MUST stay revoked across subsequent restarts/reopens.
        assert!(store
            .revoke_profile_grant("prf_old1", "agent_legacy")
            .unwrap());
        drop(store);
        let reopened = LiteStore::open(&db_path).unwrap();
        assert!(!reopened
            .has_active_profile_grant("prf_old1", "agent_legacy")
            .unwrap());
        assert!(reopened
            .has_active_profile_grant("prf_old3", "agent_legacy")
            .unwrap());

        let _ = std::fs::remove_dir_all(&dir);
    }
}
