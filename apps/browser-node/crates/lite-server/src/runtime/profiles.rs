//! Admin-owned Profile management, grants/revocations, same-browser collaboration, and checkpoints.

use super::{
    is_running_state, CreateProfileParams, CreateSessionParams, LiteRuntime, OpenProfileParams,
    ProfileOpParams,
};
use crate::profile::{restore_profile_checkpoint, save_profile_checkpoint};
use crate::security::{normalize_private_host_rules, validate_identifier};
use crate::store::{AgentTokenRecord, ProfileCheckpointRecord, ProfileRecord};
use anyhow::{bail, ensure, Result};
use chrono::Utc;
use serde_json::{json, Value};
use uuid::Uuid;

impl LiteRuntime {
    // ---------- Admin-Owned Profile Management, Authorization & Same-Browser Collaboration ----------

    pub fn create_profile(
        &self,
        caller: &AgentTokenRecord,
        params: CreateProfileParams,
    ) -> Result<Value> {
        ensure!(caller.has_scope("admin"), "ADMIN_SCOPE_REQUIRED");
        let display_name = params.name.trim();
        ensure!(
            !display_name.is_empty() && display_name.len() <= 128,
            "PROFILE_NAME_INVALID"
        );
        let allowed_private_hosts = normalize_private_host_rules(&params.allowed_private_hosts)?;
        let profile_id = format!("prf_{}", &Uuid::new_v4().simple().to_string()[..16]);
        let now = Utc::now().to_rfc3339();
        let profile = ProfileRecord {
            profile_id: profile_id.clone(),
            owner_account_id: caller.agent_id.clone(),
            display_name: display_name.to_owned(),
            allowed_private_hosts,
            created_at: now.clone(),
            updated_at: now,
        };
        self.store.upsert_profile(&profile)?;
        let mut granted = Vec::new();
        for agent in &params.grant_agents {
            let trimmed = agent.trim();
            if !trimmed.is_empty() && trimmed.len() <= 64 {
                self.store
                    .grant_profile(&profile_id, trimmed, &caller.agent_id)?;
                granted.push(trimmed.to_owned());
            }
        }
        self.store.record_audit(
            &caller.agent_id,
            None,
            "PROFILE_CREATE",
            "OK",
            Some(&profile_id),
        );
        Ok(json!({
            "profile": profile,
            "activeGrants": granted,
        }))
    }

    pub fn list_profiles(&self, caller: &AgentTokenRecord) -> Result<Value> {
        let is_admin = caller.has_scope("admin");
        let profiles = if is_admin {
            self.store.list_profiles_for_admin()?
        } else {
            self.store.list_profiles_for_agent(&caller.agent_id)?
        };
        let mut items = Vec::with_capacity(profiles.len());
        for profile in profiles {
            let active_session = self
                .store
                .find_active_session_for_profile(&profile.profile_id, None)?;
            let mut entry = json!({
                "profile": profile,
                "activeSessionId": active_session.as_ref().map(|s| s.session_id.clone()),
                "activeSessionState": active_session.as_ref().map(|s| s.state.clone()),
            });
            if is_admin {
                let grants = self
                    .store
                    .list_active_grants_for_profile(&profile.profile_id)?;
                entry["activeGrants"] = json!(grants);
            }
            items.push(entry);
        }
        Ok(json!({
            "profiles": items,
            "count": items.len(),
        }))
    }

    pub fn grant_profile(
        &self,
        caller: &AgentTokenRecord,
        profile_id: &str,
        grantee_agent_id: &str,
    ) -> Result<Value> {
        ensure!(caller.has_scope("admin"), "ADMIN_SCOPE_REQUIRED");
        validate_identifier(profile_id, "prf_")?;
        let grantee = grantee_agent_id.trim();
        ensure!(
            !grantee.is_empty() && grantee.len() <= 64,
            "AGENT_ID_INVALID"
        );
        ensure!(
            self.store.get_profile(profile_id)?.is_some(),
            "PROFILE_NOT_FOUND"
        );
        let grant = self
            .store
            .grant_profile(profile_id, grantee, &caller.agent_id)?;
        let active_grants = self.store.list_active_grants_for_profile(profile_id)?;
        self.store.record_audit(
            &caller.agent_id,
            None,
            "PROFILE_GRANT",
            "OK",
            Some(&format!("{profile_id}->{grantee}")),
        );
        self.events.publish(
            "profile.granted",
            grantee,
            None,
            None,
            json!({ "profileId": profile_id, "granteeAgentId": grantee }),
        );
        Ok(json!({
            "grant": grant,
            "activeGrants": active_grants,
        }))
    }

    pub fn revoke_profile_grant(
        &self,
        caller: &AgentTokenRecord,
        profile_id: &str,
        grantee_agent_id: &str,
    ) -> Result<Value> {
        ensure!(caller.has_scope("admin"), "ADMIN_SCOPE_REQUIRED");
        validate_identifier(profile_id, "prf_")?;
        let grantee = grantee_agent_id.trim();
        ensure!(
            !grantee.is_empty() && grantee.len() <= 64,
            "AGENT_ID_INVALID"
        );
        ensure!(
            self.store.get_profile(profile_id)?.is_some(),
            "PROFILE_NOT_FOUND"
        );
        let revoked = self.store.revoke_profile_grant(profile_id, grantee)?;
        let active_grants = self.store.list_active_grants_for_profile(profile_id)?;
        self.store.record_audit(
            &caller.agent_id,
            None,
            "PROFILE_REVOKE",
            "OK",
            Some(&format!("{profile_id}-x-{grantee}")),
        );
        self.events.publish(
            "profile.revoked",
            grantee,
            None,
            None,
            json!({ "profileId": profile_id, "granteeAgentId": grantee }),
        );
        Ok(json!({
            "revoked": revoked,
            "profileId": profile_id,
            "granteeAgentId": grantee,
            "activeGrants": active_grants,
        }))
    }

    /// Opens an authorized Profile. If a browser instance is already writing to this Profile,
    /// attaches the caller (e.g., Admin + Agent collaboration) to that SAME running browser
    /// instead of spawning a second writer that would corrupt login cookies/storage.
    pub async fn open_profile(
        &self,
        caller: &AgentTokenRecord,
        profile_id: &str,
        params: OpenProfileParams,
    ) -> Result<Value> {
        validate_identifier(profile_id, "prf_")?;
        let profile = self
            .store
            .get_profile(profile_id)?
            .ok_or_else(|| anyhow::anyhow!("PROFILE_NOT_FOUND"))?;
        ensure!(
            caller.has_scope("admin")
                || self
                    .store
                    .has_active_profile_grant(profile_id, &caller.agent_id)?,
            "PROFILE_ACCESS_DENIED"
        );

        if let Some(existing) = self
            .store
            .find_active_session_for_profile(profile_id, None)?
        {
            self.store.record_audit(
                &caller.agent_id,
                Some(&existing.session_id),
                "SESSION_COLLABORATE",
                "OK",
                Some(profile_id),
            );
            return Ok(json!({
                "session": existing,
                "collaborating": true,
                "singleWriterSessionId": existing.session_id,
                "backpressure": self.backpressure_snapshot().await,
            }));
        }

        let mut created = self
            .create_session(
                caller,
                CreateSessionParams {
                    name: params.name.or(Some(profile.display_name)),
                    profile_id: Some(profile_id.to_owned()),
                    proxy_url: params.proxy_url,
                    start_url: params.start_url,
                    isolation_mode: None,
                    allowed_private_hosts: params.allowed_private_hosts,
                    idle_timeout_seconds: params.idle_timeout_seconds,
                    start: params.start,
                },
            )
            .await?;
        let sid = created["session"]["sessionId"]
            .as_str()
            .unwrap_or_default()
            .to_owned();
        created["collaborating"] = json!(false);
        created["singleWriterSessionId"] = json!(sid);
        Ok(created)
    }

    pub fn list_profile_checkpoints(
        &self,
        caller: &AgentTokenRecord,
        profile_id: &str,
    ) -> Result<Value> {
        validate_identifier(profile_id, "prf_")?;
        ensure!(
            self.store.get_profile(profile_id)?.is_some(),
            "PROFILE_NOT_FOUND"
        );
        ensure!(
            caller.has_scope("admin")
                || self
                    .store
                    .has_active_profile_grant(profile_id, &caller.agent_id)?,
            "PROFILE_ACCESS_DENIED"
        );
        let checkpoints = self.store.list_checkpoints_for_profile(profile_id)?;
        Ok(json!({
            "profileId": profile_id,
            "checkpoints": checkpoints,
        }))
    }

    pub async fn restore_profile_by_id(
        &self,
        caller: &AgentTokenRecord,
        profile_id: &str,
        checkpoint_id: &str,
    ) -> Result<Value> {
        validate_identifier(profile_id, "prf_")?;
        validate_identifier(checkpoint_id, "chk_")?;
        ensure!(
            self.store.get_profile(profile_id)?.is_some(),
            "PROFILE_NOT_FOUND"
        );
        ensure!(
            caller.has_scope("admin")
                || self
                    .store
                    .has_active_profile_grant(profile_id, &caller.agent_id)?,
            "PROFILE_ACCESS_DENIED"
        );
        if let Some(active) = self
            .store
            .find_active_session_for_profile(profile_id, None)?
        {
            return self
                .profile_op(
                    caller,
                    &active.session_id,
                    ProfileOpParams {
                        action: "restore".to_owned(),
                        checkpoint_id: Some(checkpoint_id.to_owned()),
                    },
                )
                .await;
        }
        let exists = self
            .store
            .list_checkpoints_for_profile(profile_id)?
            .into_iter()
            .any(|c| c.checkpoint_id == checkpoint_id);
        ensure!(exists, "PROFILE_CHECKPOINT_NOT_FOUND");
        let manifest = restore_profile_checkpoint(
            &self.profile_dir(profile_id),
            &self.config.data_dir.join("checkpoints"),
            profile_id,
            checkpoint_id,
        )?;
        self.store.record_audit(
            &caller.agent_id,
            None,
            "PROFILE_RESTORE",
            "OK",
            Some(checkpoint_id),
        );
        Ok(json!({
            "restored": true,
            "profileId": profile_id,
            "checkpointId": manifest.checkpoint_id,
            "fileCount": manifest.file_count,
        }))
    }

    pub async fn profile_op(
        &self,
        caller: &AgentTokenRecord,
        session_id: &str,
        params: ProfileOpParams,
    ) -> Result<Value> {
        let record = self.require_owned_session(caller, session_id)?;
        let profile_dir = self.profile_dir(&record.profile_id);
        let checkpoints_root = self.config.data_dir.join("checkpoints");

        match params.action.as_str() {
            "list" => {
                let checkpoints = self
                    .store
                    .list_checkpoints_for_profile(&record.profile_id)?;
                Ok(json!({
                    "profileId": record.profile_id,
                    "checkpoints": checkpoints,
                }))
            }
            "save" => {
                let sess_lock = self.session_lock(session_id).await;
                let _sess_guard = sess_lock.lock().await;
                let record = self.require_owned_session(caller, session_id)?;
                let was_running = self.is_live(session_id).await || is_running_state(&record.state);
                if was_running {
                    // Gracefully flush Chromium profile and mark HIBERNATED before snapshotting so
                    // DB running-slot accounting stays accurate even at max_sessions capacity.
                    self.stop_live_session(session_id).await?;
                }
                let checkpoint_id = format!("chk_{}", &Uuid::new_v4().simple().to_string()[..16]);
                let manifest = save_profile_checkpoint(
                    &profile_dir,
                    &checkpoints_root,
                    &record.profile_id,
                    &checkpoint_id,
                )?;
                let cp_rec = ProfileCheckpointRecord {
                    checkpoint_id: manifest.checkpoint_id.clone(),
                    profile_id: record.profile_id.clone(),
                    owner_agent_id: caller.agent_id.clone(),
                    archive_sha256: manifest.archive_sha256.clone(),
                    archive_bytes: manifest.archive_bytes,
                    file_count: manifest.file_count,
                    created_at: Utc::now().to_rfc3339(),
                };
                self.store.insert_checkpoint(&cp_rec)?;
                if was_running {
                    let latest = self.require_owned_session(caller, session_id)?;
                    self.ensure_running_locked(&latest).await?;
                }
                Ok(json!({
                    "saved": true,
                    "checkpoint": cp_rec,
                    "version": manifest.version,
                    "reusedChunks": manifest.reused_chunks,
                    "newChunks": manifest.new_chunks,
                    "newBytes": manifest.new_bytes,
                    "totalRawBytes": manifest.total_raw_bytes,
                    "excludedDirs": manifest.excluded_dirs,
                }))
            }
            "restore" => {
                let checkpoint_id = params
                    .checkpoint_id
                    .as_deref()
                    .ok_or_else(|| anyhow::anyhow!("CHECKPOINT_ID_REQUIRED"))?;
                validate_identifier(checkpoint_id, "chk_")?;
                let sess_lock = self.session_lock(session_id).await;
                let _sess_guard = sess_lock.lock().await;
                let record = self.require_owned_session(caller, session_id)?;
                let owned = self
                    .store
                    .list_checkpoints_for_profile(&record.profile_id)?
                    .into_iter()
                    .any(|c| c.checkpoint_id == checkpoint_id);
                ensure!(owned, "PROFILE_CHECKPOINT_NOT_FOUND");
                let was_running = self.is_live(session_id).await || is_running_state(&record.state);
                if was_running {
                    self.stop_live_session(session_id).await?;
                }
                let manifest = restore_profile_checkpoint(
                    &profile_dir,
                    &checkpoints_root,
                    &record.profile_id,
                    checkpoint_id,
                )?;
                if was_running {
                    let latest = self.require_owned_session(caller, session_id)?;
                    self.ensure_running_locked(&latest).await?;
                }
                Ok(json!({
                    "restored": true,
                    "profileId": record.profile_id,
                    "checkpointId": manifest.checkpoint_id,
                    "fileCount": manifest.file_count,
                }))
            }
            _ => bail!("PROFILE_ACTION_INVALID"),
        }
    }
}
