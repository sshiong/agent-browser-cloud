//! Per-environment intranet authorization (`once` ephemeral vs `permanent` profile-persisted).

use super::{merge_private_hosts, IntranetAuthMode, LiteRuntime, TemporaryPrivateHostGrant};
use crate::security::{normalize_private_host_rules, validate_identifier};
use crate::store::{AgentTokenRecord, SessionRecord};
use anyhow::{ensure, Result};
use chrono::Utc;
use serde_json::{json, Value};
use std::time::{Duration, Instant};

impl LiteRuntime {
    pub async fn get_session_network_policy(
        &self,
        caller: &AgentTokenRecord,
        session_id: &str,
    ) -> Result<Value> {
        let record = self.require_owned_session(caller, session_id)?;
        Ok(self.build_network_policy_view(&record))
    }

    /// Authorizes one or more intranet hosts/CIDRs on a session in either:
    /// - `IntranetAuthMode::Once`: temporary in-memory grant valid ONLY for the current running
    ///   session (and optional `ttl_seconds`); automatically cleared on hibernate/close/restart.
    /// - `IntranetAuthMode::Permanent`: persisted into the Profile & Session allowlist so
    ///   subsequent visits, restarts, and re-grants never prompt again.
    pub async fn authorize_session_private_hosts(
        &self,
        caller: &AgentTokenRecord,
        session_id: &str,
        raw_hosts: &[String],
        mode: IntranetAuthMode,
        ttl_seconds: Option<u64>,
    ) -> Result<Value> {
        let record = self.require_owned_session(caller, session_id)?;
        let normalized = normalize_private_host_rules(raw_hosts)?;
        ensure!(!normalized.is_empty(), "PRIVATE_HOST_REQUIRED");

        match mode {
            IntranetAuthMode::Permanent => {
                let merged =
                    merge_private_hosts(&self.permanent_private_hosts(&record), &normalized);
                self.store
                    .update_session_private_hosts(session_id, &merged)?;
                let _ = self
                    .store
                    .update_profile_private_hosts(&record.profile_id, &merged);
                self.sync_live_egress_policy(session_id, &record.profile_id, &merged)
                    .await;
                self.store.record_audit(
                    &caller.agent_id,
                    Some(session_id),
                    "NETWORK_POLICY_AUTHORIZE_PERMANENT",
                    "OK",
                    Some(&normalized.join(",")),
                );
            }
            IntranetAuthMode::Once => {
                let now_mono = Instant::now();
                let now_utc = Utc::now();
                let (exp_mono, exp_utc) = match ttl_seconds {
                    Some(secs) => {
                        let clamped = secs.clamp(10, 86_400);
                        (
                            Some(now_mono + Duration::from_secs(clamped)),
                            Some(now_utc + chrono::Duration::seconds(clamped as i64)),
                        )
                    }
                    None => (None, None),
                };
                {
                    let mut map = self
                        .temporary_private_grants
                        .lock()
                        .expect("temporary_private_grants mutex poisoned");
                    let list = map.entry(session_id.to_owned()).or_default();
                    list.retain(|g| g.expires_at_mono.is_none_or(|e| now_mono < e));
                    for rule in &normalized {
                        list.retain(|g| g.rule != *rule);
                        list.push(TemporaryPrivateHostGrant {
                            rule: rule.clone(),
                            expires_at_mono: exp_mono,
                            expires_at_utc: exp_utc,
                        });
                    }
                }
                let perm = self.permanent_private_hosts(&record);
                self.sync_live_egress_policy(session_id, &record.profile_id, &perm)
                    .await;
                self.store.record_audit(
                    &caller.agent_id,
                    Some(session_id),
                    "NETWORK_POLICY_AUTHORIZE_ONCE",
                    "OK",
                    Some(&normalized.join(",")),
                );
            }
        }

        let refreshed = self.require_owned_session(caller, session_id)?;
        let mut view = self.build_network_policy_view(&refreshed);
        view["mode"] = json!(mode);
        Ok(view)
    }

    pub async fn revoke_session_private_hosts(
        &self,
        caller: &AgentTokenRecord,
        session_id: &str,
        raw_hosts: &[String],
        mode: Option<IntranetAuthMode>,
    ) -> Result<Value> {
        let record = self.require_owned_session(caller, session_id)?;
        let targets: Vec<String> = raw_hosts
            .iter()
            .map(|r| r.trim().to_ascii_lowercase())
            .filter(|r| !r.is_empty())
            .collect();
        ensure!(!targets.is_empty(), "PRIVATE_HOST_REQUIRED");

        if matches!(mode, None | Some(IntranetAuthMode::Once)) {
            let mut map = self
                .temporary_private_grants
                .lock()
                .expect("temporary_private_grants mutex poisoned");
            if let Some(list) = map.get_mut(session_id) {
                list.retain(|g| !targets.contains(&g.rule));
            }
        }
        if matches!(mode, None | Some(IntranetAuthMode::Permanent)) {
            let mut perm = self.permanent_private_hosts(&record);
            perm.retain(|r| !targets.contains(r));
            self.store.update_session_private_hosts(session_id, &perm)?;
            let _ = self
                .store
                .update_profile_private_hosts(&record.profile_id, &perm);
            self.sync_live_egress_policy(session_id, &record.profile_id, &perm)
                .await;
        } else {
            let perm = self.permanent_private_hosts(&record);
            self.sync_live_egress_policy(session_id, &record.profile_id, &perm)
                .await;
        }
        self.store.record_audit(
            &caller.agent_id,
            Some(session_id),
            "NETWORK_POLICY_REVOKE",
            "OK",
            Some(&targets.join(",")),
        );
        let refreshed = self.require_owned_session(caller, session_id)?;
        Ok(self.build_network_policy_view(&refreshed))
    }

    pub async fn update_session_network_policy(
        &self,
        caller: &AgentTokenRecord,
        session_id: &str,
        raw_hosts: &[String],
    ) -> Result<Value> {
        let record = self.require_owned_session(caller, session_id)?;
        let normalized = normalize_private_host_rules(raw_hosts)?;
        self.store
            .update_session_private_hosts(session_id, &normalized)?;
        let _ = self
            .store
            .update_profile_private_hosts(&record.profile_id, &normalized);
        self.sync_live_egress_policy(session_id, &record.profile_id, &normalized)
            .await;
        self.store.record_audit(
            &caller.agent_id,
            Some(session_id),
            "NETWORK_POLICY_UPDATE",
            "OK",
            Some(&normalized.join(",")),
        );
        let refreshed = self.require_owned_session(caller, session_id)?;
        Ok(self.build_network_policy_view(&refreshed))
    }

    pub async fn update_profile_network_policy(
        &self,
        caller: &AgentTokenRecord,
        profile_id: &str,
        raw_hosts: &[String],
    ) -> Result<Value> {
        ensure!(caller.has_scope("admin"), "ADMIN_SCOPE_REQUIRED");
        validate_identifier(profile_id, "prf_")?;
        ensure!(
            self.store.get_profile(profile_id)?.is_some(),
            "PROFILE_NOT_FOUND"
        );
        let normalized = normalize_private_host_rules(raw_hosts)?;
        self.store
            .update_profile_private_hosts(profile_id, &normalized)?;
        if let Some(active) = self
            .store
            .find_active_session_for_profile(profile_id, None)?
        {
            let _ = self
                .store
                .update_session_private_hosts(&active.session_id, &normalized);
            self.sync_live_egress_policy(&active.session_id, profile_id, &normalized)
                .await;
        }
        self.store.record_audit(
            &caller.agent_id,
            None,
            "PROFILE_NETWORK_POLICY_UPDATE",
            "OK",
            Some(&normalized.join(",")),
        );
        Ok(json!({
            "profileId": profile_id,
            "mode": "permanent",
            "permanentAllowedPrivateHosts": normalized,
            "allowedPrivateHosts": normalized,
        }))
    }

    async fn sync_live_egress_policy(
        &self,
        session_id: &str,
        _profile_id: &str,
        permanent_hosts: &[String],
    ) {
        let temp_pairs = self.active_temporary_egress_pairs(session_id);
        if let Some(live) = self.live.lock().await.get(session_id) {
            if let Some(guard) = live.egress_guard.as_ref() {
                guard.set_policy(permanent_hosts.to_vec(), temp_pairs);
            }
        }
    }

    pub(super) fn permanent_private_hosts(&self, record: &SessionRecord) -> Vec<String> {
        let profile_hosts = self
            .store
            .get_profile(&record.profile_id)
            .ok()
            .flatten()
            .map(|p| p.allowed_private_hosts)
            .unwrap_or_default();
        merge_private_hosts(&profile_hosts, &record.allowed_private_hosts)
    }

    fn active_temporary_grants(&self, session_id: &str) -> Vec<TemporaryPrivateHostGrant> {
        let now = Instant::now();
        let mut map = self
            .temporary_private_grants
            .lock()
            .expect("temporary_private_grants mutex poisoned");
        let Some(list) = map.get_mut(session_id) else {
            return Vec::new();
        };
        list.retain(|g| g.expires_at_mono.is_none_or(|e| now < e));
        list.clone()
    }

    pub(super) fn active_temporary_egress_pairs(
        &self,
        session_id: &str,
    ) -> Vec<(String, Option<Instant>)> {
        self.active_temporary_grants(session_id)
            .into_iter()
            .map(|g| (g.rule, g.expires_at_mono))
            .collect()
    }

    pub(super) fn effective_private_hosts(&self, record: &SessionRecord) -> Vec<String> {
        let perm = self.permanent_private_hosts(record);
        let temp: Vec<String> = self
            .active_temporary_grants(&record.session_id)
            .into_iter()
            .map(|g| g.rule)
            .collect();
        merge_private_hosts(&perm, &temp)
    }

    fn build_network_policy_view(&self, record: &SessionRecord) -> Value {
        let permanent = self.permanent_private_hosts(record);
        let once_grants = self.active_temporary_grants(&record.session_id);
        let once_json: Vec<Value> = once_grants
            .iter()
            .map(|g| {
                json!({
                    "rule": g.rule,
                    "mode": "once",
                    "expiresAt": g.expires_at_utc.map(|dt| dt.to_rfc3339()),
                })
            })
            .collect();
        let once_rules: Vec<String> = once_grants.into_iter().map(|g| g.rule).collect();
        let effective = merge_private_hosts(&permanent, &once_rules);
        json!({
            "sessionId": record.session_id,
            "profileId": record.profile_id,
            "permanentAllowedPrivateHosts": permanent,
            "onceAllowedPrivateHosts": once_json,
            "effectiveAllowedPrivateHosts": effective,
            "allowedPrivateHosts": effective,
        })
    }
}
