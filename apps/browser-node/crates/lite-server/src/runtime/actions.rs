//! Browser navigation, structured observation, batched actions, waits, and screenshots.

use super::{
    ActBatchParams, ActionStepParams, BatchStepOutcome, LiteRuntime, ObserveParams,
    ScreenshotParams, WaitParams,
};
use crate::security::{hex_encode, validate_navigation_url_with_policy};
use crate::store::AgentTokenRecord;
use anyhow::{bail, ensure, Result};
use futures_util::{SinkExt, StreamExt};
use input_sandbox::{CdpDesktopInput, DesktopInput, InputKey};
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use state_collector::{diff_states, CurrentState, DiffOutcome, DropdownSelectionProbe};
use std::time::{Duration, Instant};
use tokio::time::timeout;
use tokio_tungstenite::tungstenite::Message;

impl LiteRuntime {
    pub async fn navigate(
        &self,
        caller: &AgentTokenRecord,
        session_id: &str,
        url: &str,
    ) -> Result<Value> {
        let record = self.require_owned_session(caller, session_id)?;
        let _active_op = self.begin_active_op(session_id);
        let allowed_private = self.effective_private_hosts(&record);
        let validated = match validate_navigation_url_with_policy(
            url,
            self.config.allow_private_network,
            &allowed_private,
        ) {
            Ok(v) => v,
            Err(err) => {
                // Reject ONLY this network request; keep browser session and task running untouched!
                let reason = err.to_string();
                self.store.record_audit(
                    &caller.agent_id,
                    Some(session_id),
                    "NAVIGATION_PRIVATE_DENIED",
                    "DENIED",
                    Some(&reason),
                );
                self.events.publish(
                    "network.request_blocked",
                    &caller.agent_id,
                    Some(session_id),
                    None,
                    json!({
                        "url": url,
                        "reason": reason,
                        "sessionPreserved": true,
                        "authorizationOptions": ["once", "permanent"],
                    }),
                );
                return Err(err);
            }
        };
        self.ensure_running(&record).await?;
        self.collector.navigate(session_id, &validated).await?;
        let state = self.refresh_state(session_id).await?;
        self.store
            .touch_session_activity(session_id, Some(&state.url))?;
        self.store.record_audit(
            &caller.agent_id,
            Some(session_id),
            "NAVIGATE",
            "OK",
            Some(&state.url),
        );
        self.events.publish(
            "session.navigated",
            &caller.agent_id,
            Some(session_id),
            None,
            json!({ "url": state.url, "stateVersion": state.state_version }),
        );
        Ok(json!({
            "sessionId": session_id,
            "url": state.url,
            "title": state.title,
            "stateCursor": state_cursor(&state),
            "stateVersion": state.state_version,
            "targetRevision": state.target_revision,
        }))
    }

    pub async fn observe(
        &self,
        caller: &AgentTokenRecord,
        session_id: &str,
        params: ObserveParams,
    ) -> Result<Value> {
        let record = self.require_owned_session(caller, session_id)?;
        let _active_op = self.begin_active_op(session_id);
        self.ensure_running(&record).await?;
        let state = self.refresh_state(session_id).await?;
        self.store
            .touch_session_activity(session_id, Some(&state.url))?;
        let cursor = state_cursor(&state);

        match params.mode.as_str() {
            "find" => {
                let query = params
                    .query
                    .as_deref()
                    .map(str::trim)
                    .filter(|q| !q.is_empty())
                    .ok_or_else(|| anyhow::anyhow!("FIND_QUERY_REQUIRED"))?
                    .to_ascii_lowercase();
                let roles: Vec<String> = params
                    .roles
                    .iter()
                    .map(|r| r.to_ascii_lowercase())
                    .collect();
                let limit = params.limit.unwrap_or(20).clamp(1, 100);
                let matches: Vec<_> = state
                    .targets
                    .iter()
                    .filter(|t| {
                        params.include_hidden
                            || t.visible
                            || (!t.occluded
                                && t.visibility_reason.as_deref() == Some("OUTSIDE_VIEWPORT"))
                    })
                    .filter(|t| roles.is_empty() || roles.contains(&t.role.to_ascii_lowercase()))
                    .filter(|t| target_matches_query(t, &query))
                    .take(limit)
                    .collect();
                Ok(json!({
                    "mode": "find",
                    "stateCursor": cursor,
                    "stateVersion": state.state_version,
                    "targetRevision": state.target_revision,
                    "url": state.url,
                    "title": state.title,
                    "targets": matches,
                }))
            }
            "inspect" => {
                ensure!(
                    !params.element_ids.is_empty() && params.element_ids.len() <= 50,
                    "INSPECT_ELEMENT_COUNT_INVALID"
                );
                let targets: Vec<_> = state
                    .targets
                    .iter()
                    .filter(|t| {
                        params
                            .element_ids
                            .iter()
                            .any(|id| id == &t.element_id || id == &t.target_ref)
                    })
                    .collect();
                Ok(json!({
                    "mode": "inspect",
                    "stateCursor": cursor,
                    "stateVersion": state.state_version,
                    "targetRevision": state.target_revision,
                    "targets": targets,
                }))
            }
            "diff" => {
                let since = params
                    .since_state_version
                    .ok_or_else(|| anyhow::anyhow!("SINCE_STATE_VERSION_REQUIRED"))?;
                let previous = {
                    let live = self.live.lock().await;
                    live.get(session_id)
                        .and_then(|s| s.last_states.iter().find(|st| st.state_version == since))
                        .cloned()
                };
                match previous {
                    Some(prev) => match diff_states(&prev, &state, 262_144, 128)? {
                        DiffOutcome::Diff(diff) => Ok(json!({
                            "mode": "diff",
                            "fullRequired": false,
                            "stateCursor": cursor,
                            "stateVersion": state.state_version,
                            "targetRevision": state.target_revision,
                            "diff": diff,
                        })),
                        DiffOutcome::Truncated(trunc) => Ok(json!({
                            "mode": "diff",
                            "fullRequired": true,
                            "reason": trunc.reason,
                            "stateCursor": cursor,
                            "stateVersion": state.state_version,
                            "targetRevision": state.target_revision,
                            "summary": compact_tree_payload(&state),
                        })),
                    },
                    None => Ok(json!({
                        "mode": "diff",
                        "fullRequired": true,
                        "reason": "BASELINE_EXPIRED",
                        "stateCursor": cursor,
                        "stateVersion": state.state_version,
                        "targetRevision": state.target_revision,
                        "summary": compact_tree_payload(&state),
                    })),
                }
            }
            _ => Ok(json!({
                "mode": "tree",
                "stateCursor": cursor,
                "stateVersion": state.state_version,
                "targetRevision": state.target_revision,
                "summary": compact_tree_payload(&state),
            })),
        }
    }

    pub async fn act(
        &self,
        caller: &AgentTokenRecord,
        session_id: &str,
        step: ActionStepParams,
    ) -> Result<Value> {
        let record = self.require_owned_session(caller, session_id)?;
        let _active_op = self.begin_active_op(session_id);
        self.ensure_running(&record).await?;
        let started = Instant::now();
        let state = self.execute_single_step(caller, session_id, &step).await?;
        self.store
            .touch_session_activity(session_id, Some(&state.url))?;
        self.store.record_audit(
            &caller.agent_id,
            Some(session_id),
            &format!("ACT:{}", step.action),
            "OK",
            None,
        );
        self.events.publish(
            "session.acted",
            &caller.agent_id,
            Some(session_id),
            None,
            json!({ "action": step.action, "stateVersion": state.state_version }),
        );
        Ok(json!({
            "sessionId": session_id,
            "action": step.action,
            "status": "SUCCEEDED",
            "durationMs": started.elapsed().as_millis() as u64,
            "url": state.url,
            "title": state.title,
            "stateCursor": state_cursor(&state),
            "stateVersion": state.state_version,
            "targetRevision": state.target_revision,
        }))
    }

    pub async fn act_batch(
        &self,
        caller: &AgentTokenRecord,
        session_id: &str,
        params: ActBatchParams,
    ) -> Result<Value> {
        ensure!(
            !params.steps.is_empty() && params.steps.len() <= 20,
            "BATCH_STEP_COUNT_INVALID"
        );
        let record = self.require_owned_session(caller, session_id)?;
        let _active_op = self.begin_active_op(session_id);
        self.ensure_running(&record).await?;
        let max_duration =
            Duration::from_millis(params.max_duration_ms.unwrap_or(60_000).min(180_000));
        let batch_start = Instant::now();

        let mut outcomes = Vec::with_capacity(params.steps.len());
        let mut latest_state: Option<CurrentState> = None;
        let mut all_succeeded = true;

        for (index, step) in params.steps.iter().enumerate() {
            let _ = self.store.touch_session_activity(session_id, None);
            if batch_start.elapsed() >= max_duration {
                all_succeeded = false;
                outcomes.push(BatchStepOutcome {
                    index,
                    action: step.action.clone(),
                    status: "TIMED_OUT".to_owned(),
                    duration_ms: 0,
                    error: Some("BATCH_MAX_DURATION_EXCEEDED".to_owned()),
                    state_version: latest_state.as_ref().map(|s| s.state_version).unwrap_or(0),
                    target_revision: latest_state
                        .as_ref()
                        .map(|s| s.target_revision)
                        .unwrap_or(0),
                });
                break;
            }
            let step_start = Instant::now();
            match self.execute_single_step(caller, session_id, step).await {
                Ok(state) => {
                    outcomes.push(BatchStepOutcome {
                        index,
                        action: step.action.clone(),
                        status: "SUCCEEDED".to_owned(),
                        duration_ms: step_start.elapsed().as_millis() as u64,
                        error: None,
                        state_version: state.state_version,
                        target_revision: state.target_revision,
                    });
                    latest_state = Some(state);
                }
                Err(error) => {
                    all_succeeded = false;
                    outcomes.push(BatchStepOutcome {
                        index,
                        action: step.action.clone(),
                        status: "FAILED".to_owned(),
                        duration_ms: step_start.elapsed().as_millis() as u64,
                        error: Some(error.to_string()),
                        state_version: latest_state.as_ref().map(|s| s.state_version).unwrap_or(0),
                        target_revision: latest_state
                            .as_ref()
                            .map(|s| s.target_revision)
                            .unwrap_or(0),
                    });
                    if params.stop_on_error {
                        break;
                    }
                }
            }
        }

        let final_state = match latest_state {
            Some(s) => s,
            None => self.refresh_state(session_id).await?,
        };
        self.store
            .touch_session_activity(session_id, Some(&final_state.url))?;
        self.store.record_audit(
            &caller.agent_id,
            Some(session_id),
            "ACT_BATCH",
            if all_succeeded {
                "OK"
            } else {
                "PARTIAL_OR_FAILED"
            },
            None,
        );

        Ok(json!({
            "sessionId": session_id,
            "succeeded": all_succeeded,
            "durationMs": batch_start.elapsed().as_millis() as u64,
            "steps": outcomes,
            "url": final_state.url,
            "title": final_state.title,
            "stateCursor": state_cursor(&final_state),
            "stateVersion": final_state.state_version,
            "targetRevision": final_state.target_revision,
        }))
    }

    pub async fn wait(
        &self,
        caller: &AgentTokenRecord,
        session_id: &str,
        params: WaitParams,
    ) -> Result<Value> {
        let record = self.require_owned_session(caller, session_id)?;
        let _active_op = self.begin_active_op(session_id);
        self.ensure_running(&record).await?;
        let budget = Duration::from_millis(params.timeout_ms.unwrap_or(5_000).clamp(100, 120_000));
        let deadline = Instant::now() + budget;
        let mut last_activity_touch = Instant::now();
        let mut previous_hash: Option<String> = None;
        let mut previous_visual_hash: Option<String> = None;
        let mut stable_visual_count: u32 = 0;
        let required_visual_frames = params.stable_frames.unwrap_or(2).clamp(2, 5);

        loop {
            if last_activity_touch.elapsed() >= Duration::from_secs(5) {
                let _ = self.store.touch_session_activity(session_id, None);
                last_activity_touch = Instant::now();
            }
            let state = self.refresh_state(session_id).await?;
            let ready = match params.mode.as_str() {
                "element_ready" => {
                    let target_ref = params
                        .target
                        .as_deref()
                        .ok_or_else(|| anyhow::anyhow!("WAIT_TARGET_REQUIRED"))?;
                    state.targets.iter().any(|t| {
                        (t.element_id == target_ref || t.target_ref == target_ref)
                            && t.enabled
                            && !t.occluded
                            && (t.visible
                                || t.visibility_reason.as_deref() == Some("OUTSIDE_VIEWPORT"))
                    })
                }
                "dom_quiet" => {
                    previous_hash.as_deref() == Some(state.content_hash.as_str())
                        || (state.page_stability.evidence_fresh
                            && state.page_stability.dom_quiet_millis >= 300)
                }
                "visual_stable" => {
                    let ws_url = self.collector.active_page_websocket(session_id).await?;
                    let b64_png =
                        capture_cdp_screenshot(&ws_url, json!({ "format": "png" })).await?;
                    let mut hasher = Sha256::new();
                    hasher.update(b64_png.as_bytes());
                    let frame_hash = hex_encode(&hasher.finalize());
                    if previous_visual_hash.as_deref() == Some(frame_hash.as_str()) {
                        stable_visual_count = stable_visual_count.saturating_add(1);
                    } else {
                        previous_visual_hash = Some(frame_hash);
                        stable_visual_count = 1;
                    }
                    stable_visual_count >= required_visual_frames
                }
                "app_condition" => {
                    let has_any = params.url_contains.is_some()
                        || params.title_contains.is_some()
                        || params.text.is_some();
                    ensure!(has_any, "WAIT_CONDITION_REQUIRED");
                    let url_ok = params
                        .url_contains
                        .as_deref()
                        .map(|needle| state.url.contains(needle))
                        .unwrap_or(true);
                    let title_ok = params
                        .title_contains
                        .as_deref()
                        .map(|needle| state.title.contains(needle))
                        .unwrap_or(true);
                    let text_ok = params
                        .text
                        .as_deref()
                        .map(|needle| {
                            let needle_lc = needle.to_ascii_lowercase();
                            state.title.to_ascii_lowercase().contains(&needle_lc)
                                || state.targets.iter().any(|t| {
                                    t.visible
                                        && !t.sensitive
                                        && (t.name.as_deref().is_some_and(|n| {
                                            n.to_ascii_lowercase().contains(&needle_lc)
                                        }) || t.value.as_deref().is_some_and(|v| {
                                            v.to_ascii_lowercase().contains(&needle_lc)
                                        }))
                                })
                        })
                        .unwrap_or(true);
                    url_ok && title_ok && text_ok
                }
                _ => {
                    state.document_ready_state == "complete"
                        && (previous_hash.as_deref() == Some(state.content_hash.as_str())
                            || state.network_quiet_millis >= 250)
                }
            };
            if ready {
                return Ok(json!({
                    "satisfied": true,
                    "mode": params.mode,
                    "stateCursor": state_cursor(&state),
                    "stateVersion": state.state_version,
                    "targetRevision": state.target_revision,
                    "url": state.url,
                }));
            }
            if Instant::now() >= deadline {
                return Ok(json!({
                    "satisfied": false,
                    "mode": params.mode,
                    "reason": "WAIT_TIMEOUT",
                    "stateCursor": state_cursor(&state),
                    "stateVersion": state.state_version,
                    "targetRevision": state.target_revision,
                    "url": state.url,
                }));
            }
            previous_hash = Some(state.content_hash);
            tokio::time::sleep(Duration::from_millis(200)).await;
        }
    }

    pub async fn screenshot(
        &self,
        caller: &AgentTokenRecord,
        session_id: &str,
        params: ScreenshotParams,
    ) -> Result<(String, Value)> {
        let record = self.require_owned_session(caller, session_id)?;
        let _active_op = self.begin_active_op(session_id);
        self.ensure_running(&record).await?;
        let state = self.refresh_state(session_id).await?;

        let clip = if let Some(target_ref) = params.target.as_deref() {
            let resolved = self
                .collector
                .resolve_target_for_action(session_id, target_ref, state.target_revision)
                .await?;
            Some(json!({
                "x": resolved.bounds.x.max(0.0),
                "y": resolved.bounds.y.max(0.0),
                "width": resolved.bounds.width.max(1.0),
                "height": resolved.bounds.height.max(1.0),
                "scale": 1.0,
            }))
        } else {
            None
        };

        let ws_url = self.collector.active_page_websocket(session_id).await?;
        let mut cdp_params = json!({ "format": "png" });
        if let Some(clip) = clip {
            cdp_params["clip"] = clip;
        } else if params.full_page {
            cdp_params["captureBeyondViewport"] = json!(true);
        }
        let b64_png = capture_cdp_screenshot(&ws_url, cdp_params).await?;
        self.store
            .touch_session_activity(session_id, Some(&state.url))?;
        let meta = json!({
            "sessionId": session_id,
            "mimeType": "image/png",
            "url": state.url,
            "stateCursor": state_cursor(&state),
            "bytesBase64": b64_png.len(),
        });
        Ok((b64_png, meta))
    }

    async fn execute_single_step(
        &self,
        caller: &AgentTokenRecord,
        session_id: &str,
        step: &ActionStepParams,
    ) -> Result<CurrentState> {
        let current = self.refresh_state(session_id).await?;
        let revision = step.target_revision.unwrap_or(current.target_revision);
        let input = self.input_for(session_id).await?;
        let action = step.action.trim().to_ascii_lowercase();

        match action.as_str() {
            "scroll" => {
                let dy = step.delta_y.unwrap_or(480).clamp(-2000, 2000);
                self.collector.scroll(session_id, dy).await?;
            }
            "open_tab" => {
                let raw_url = step
                    .tab_url
                    .as_deref()
                    .or(step.value.as_deref())
                    .ok_or_else(|| anyhow::anyhow!("TAB_URL_REQUIRED"))?;
                let allowed_private = self
                    .store
                    .get_session(session_id)?
                    .map(|rec| self.effective_private_hosts(&rec))
                    .unwrap_or_default();
                let validated = validate_navigation_url_with_policy(
                    raw_url,
                    self.config.allow_private_network,
                    &allowed_private,
                )?;
                self.collector.open_tab(session_id, &validated).await?;
            }
            "switch_tab" => {
                let tab_id = step
                    .tab_id
                    .as_deref()
                    .ok_or_else(|| anyhow::anyhow!("TAB_ID_REQUIRED"))?;
                self.collector.activate_tab(session_id, tab_id).await?;
            }
            "close_tab" => {
                let tab_id = step
                    .tab_id
                    .as_deref()
                    .ok_or_else(|| anyhow::anyhow!("TAB_ID_REQUIRED"))?;
                self.collector.close_tab(session_id, tab_id).await?;
            }
            "accept_dialog" | "dismiss_dialog" => {
                let dialog_id = step
                    .dialog_id
                    .as_deref()
                    .ok_or_else(|| anyhow::anyhow!("DIALOG_ID_REQUIRED"))?;
                self.collector
                    .handle_native_dialog(
                        session_id,
                        dialog_id,
                        action == "accept_dialog",
                        step.value.as_deref(),
                    )
                    .await?;
            }
            "select" | "select_option" => {
                let target_ref = step
                    .target
                    .as_deref()
                    .ok_or_else(|| anyhow::anyhow!("TARGET_REQUIRED"))?;
                let value = step
                    .value
                    .as_deref()
                    .filter(|v| !v.is_empty())
                    .ok_or_else(|| anyhow::anyhow!("SELECT_VALUE_REQUIRED"))?;
                let target = self
                    .collector
                    .resolve_target_for_action(session_id, target_ref, revision)
                    .await?;
                let cx = (target.bounds.x + target.bounds.width / 2.0).round() as i32;
                let cy = (target.bounds.y + target.bounds.height / 2.0).round() as i32;
                match self
                    .collector
                    .select_option_probe(session_id, &target.path, value)
                    .await?
                {
                    DropdownSelectionProbe::NativeSelected => input.keep_alive().await,
                    DropdownSelectionProbe::NativeOptionNotFound => {
                        bail!("DROPDOWN_OPTION_NOT_FOUND")
                    }
                    DropdownSelectionProbe::CustomDropdown { is_text_input } => {
                        let mut seq = input.ledger_snapshot().await.last_sequence;
                        let mut next = || {
                            seq = seq.saturating_add(1);
                            seq
                        };
                        let res: Result<()> = async {
                            input.mouse_move(cx, cy, next()).await?;
                            input.mouse_click(0, 1, next()).await?;
                            tokio::time::sleep(Duration::from_millis(180)).await;
                            input.keep_alive().await;
                            if let Some((ox, oy)) = self
                                .collector
                                .find_visible_dropdown_option(session_id, value)
                                .await?
                            {
                                input.mouse_move(ox, oy, next()).await?;
                                input.mouse_click(0, 1, next()).await?;
                                return Ok(());
                            }
                            if is_text_input {
                                select_all_and_type(&input, value, &mut next).await?;
                                tokio::time::sleep(Duration::from_millis(180)).await;
                                if let Some((ox, oy)) = self
                                    .collector
                                    .find_visible_dropdown_option(session_id, value)
                                    .await?
                                {
                                    input.mouse_move(ox, oy, next()).await?;
                                    input.mouse_click(0, 1, next()).await?;
                                } else {
                                    input.key_down(InputKey::Enter, next()).await?;
                                    input.key_up(InputKey::Enter, next()).await?;
                                }
                                return Ok(());
                            }
                            tokio::time::sleep(Duration::from_millis(220)).await;
                            if let Some((ox, oy)) = self
                                .collector
                                .find_visible_dropdown_option(session_id, value)
                                .await?
                            {
                                input.mouse_move(ox, oy, next()).await?;
                                input.mouse_click(0, 1, next()).await?;
                                return Ok(());
                            }
                            bail!("DROPDOWN_OPTION_NOT_FOUND")
                        }
                        .await;
                        if let Err(err) = res {
                            let _ = input.release_all().await;
                            return Err(err);
                        }
                    }
                    DropdownSelectionProbe::FallbackKeyboard => {
                        let mut seq = input.ledger_snapshot().await.last_sequence;
                        let mut next = || {
                            seq = seq.saturating_add(1);
                            seq
                        };
                        input.mouse_move(cx, cy, next()).await?;
                        input.mouse_click(0, 1, next()).await?;
                        select_all_and_type(&input, value, &mut next).await?;
                        input.key_down(InputKey::Enter, next()).await?;
                        input.key_up(InputKey::Enter, next()).await?;
                    }
                }
            }
            "click" | "click_target" | "double_click" | "right_click" | "hover" | "check"
            | "uncheck" | "clear" | "type" | "type_text" | "fill" | "press_key" | "drag"
            | "drag_target" | "swipe" | "mouse_down" | "mouse_up" | "drop" => {
                let target_ref = step
                    .target
                    .as_deref()
                    .ok_or_else(|| anyhow::anyhow!("TARGET_REQUIRED"))?;
                let target = self
                    .collector
                    .resolve_target_for_action(session_id, target_ref, revision)
                    .await?;
                let cx = (target.bounds.x + target.bounds.width / 2.0).round() as i32;
                let cy = (target.bounds.y + target.bounds.height / 2.0).round() as i32;
                let mut seq = input.ledger_snapshot().await.last_sequence;
                let mut next = || {
                    seq = seq.saturating_add(1);
                    seq
                };

                let exec: Result<()> = async {
                    match action.as_str() {
                        "hover" => input.mouse_move(cx, cy, next()).await?,
                        "click" | "click_target" => {
                            input.mouse_move(cx, cy, next()).await?;
                            input.mouse_click(0, 1, next()).await?;
                        }
                        "double_click" => {
                            input.mouse_move(cx, cy, next()).await?;
                            input.mouse_click(0, 2, next()).await?;
                        }
                        "right_click" => {
                            input.mouse_move(cx, cy, next()).await?;
                            input.mouse_click(2, 1, next()).await?;
                        }
                        "check" => {
                            if target.checked != Some(true) {
                                input.mouse_move(cx, cy, next()).await?;
                                input.mouse_click(0, 1, next()).await?;
                            }
                        }
                        "uncheck" => {
                            if target.checked != Some(false) {
                                input.mouse_move(cx, cy, next()).await?;
                                input.mouse_click(0, 1, next()).await?;
                            }
                        }
                        "clear" => {
                            input.mouse_move(cx, cy, next()).await?;
                            input.mouse_click(0, 1, next()).await?;
                            input.key_down(InputKey::Control, next()).await?;
                            input
                                .key_down(InputKey::Character("a".to_owned()), next())
                                .await?;
                            input
                                .key_up(InputKey::Character("a".to_owned()), next())
                                .await?;
                            input.key_up(InputKey::Control, next()).await?;
                            input.key_down(InputKey::Backspace, next()).await?;
                            input.key_up(InputKey::Backspace, next()).await?;
                        }
                        "type" | "type_text" => {
                            let resolved_secret;
                            let text: &str = if let Some(cred_ref) = step
                                .value_ref
                                .as_deref()
                                .map(str::trim)
                                .filter(|s| !s.is_empty())
                            {
                                resolved_secret = self.resolve_credential_for_url(
                                    caller,
                                    cred_ref,
                                    &current.url,
                                )?;
                                &resolved_secret
                            } else {
                                step.value
                                    .as_deref()
                                    .filter(|v| !v.is_empty())
                                    .ok_or_else(|| anyhow::anyhow!("TEXT_VALUE_REQUIRED"))?
                            };
                            input.mouse_move(cx, cy, next()).await?;
                            input.mouse_click(0, 1, next()).await?;
                            input.insert_text(text, next()).await?;
                        }
                        "fill" => {
                            let resolved_secret;
                            let text: &str = if let Some(cred_ref) = step
                                .value_ref
                                .as_deref()
                                .map(str::trim)
                                .filter(|s| !s.is_empty())
                            {
                                resolved_secret = self.resolve_credential_for_url(
                                    caller,
                                    cred_ref,
                                    &current.url,
                                )?;
                                &resolved_secret
                            } else {
                                step.value
                                    .as_deref()
                                    .filter(|v| !v.is_empty())
                                    .ok_or_else(|| anyhow::anyhow!("TEXT_VALUE_REQUIRED"))?
                            };
                            input.mouse_move(cx, cy, next()).await?;
                            input.mouse_click(0, 1, next()).await?;
                            select_all_and_type(&input, text, &mut next).await?;
                        }
                        "press_key" => {
                            let key = parse_input_key(
                                step.key
                                    .as_deref()
                                    .or(step.value.as_deref())
                                    .ok_or_else(|| anyhow::anyhow!("KEY_REQUIRED"))?,
                            )?;
                            input.mouse_move(cx, cy, next()).await?;
                            input.mouse_click(0, 1, next()).await?;
                            input.key_down(key.clone(), next()).await?;
                            input.key_up(key, next()).await?;
                        }
                        "mouse_down" => {
                            let btn = step.button.unwrap_or(0);
                            input.mouse_move(cx, cy, next()).await?;
                            input.mouse_down(btn, next()).await?;
                        }
                        "mouse_up" | "drop" => {
                            let btn = step.button.unwrap_or(0);
                            input.mouse_move(cx, cy, next()).await?;
                            input.mouse_up(btn, next()).await?;
                        }
                        "drag" | "drag_target" => {
                            let (end_x, end_y) = if let Some(end_ref) = step.end_target.as_deref() {
                                let dest = self
                                    .collector
                                    .resolve_target_for_action(session_id, end_ref, revision)
                                    .await?;
                                (
                                    (dest.bounds.x + dest.bounds.width / 2.0).round() as i32,
                                    (dest.bounds.y + dest.bounds.height / 2.0).round() as i32,
                                )
                            } else {
                                let dx = step.delta_x.unwrap_or(0).clamp(-4000, 4000);
                                let dy = step.delta_y.unwrap_or(0).clamp(-4000, 4000);
                                ((cx + dx).max(0), (cy + dy).max(0))
                            };
                            let duration = step.duration_ms.unwrap_or(240).clamp(80, 5000);
                            input.mouse_move(cx, cy, next()).await?;
                            input.mouse_down(0, next()).await?;
                            if (cx, cy) == (end_x, end_y) {
                                let mut rem = u64::from(duration);
                                while rem > 0 {
                                    let slice = rem.min(250);
                                    tokio::time::sleep(Duration::from_millis(slice)).await;
                                    rem -= slice;
                                    input.keep_alive().await;
                                }
                            } else {
                                let step_ms = u64::from(duration / 8);
                                for i in 1..=8_i32 {
                                    let mx = cx + (end_x - cx) * i / 8;
                                    let my = cy + (end_y - cy) * i / 8;
                                    input.mouse_move(mx, my, next()).await?;
                                    if step_ms > 0 {
                                        tokio::time::sleep(Duration::from_millis(step_ms)).await;
                                    }
                                    input.keep_alive().await;
                                }
                            }
                            input.mouse_up(0, next()).await?;
                        }
                        "swipe" => {
                            let dx = step.delta_x.unwrap_or(0).clamp(-4000, 4000);
                            let dy = step.delta_y.unwrap_or(0).clamp(-4000, 4000);
                            let end_x = (cx + dx).max(0);
                            let end_y = (cy + dy).max(0);
                            let duration = step.duration_ms.unwrap_or(240).clamp(80, 5000);
                            let step_ms = u64::from(duration / 8);
                            input.touch_start(cx, cy, next()).await?;
                            for i in 1..=8_i32 {
                                let mx = cx + (end_x - cx) * i / 8;
                                let my = cy + (end_y - cy) * i / 8;
                                input.touch_move(mx, my, next()).await?;
                                if step_ms > 0 {
                                    tokio::time::sleep(Duration::from_millis(step_ms)).await;
                                }
                                input.keep_alive().await;
                            }
                            input.touch_end(next()).await?;
                        }
                        _ => bail!("ACTION_UNSUPPORTED"),
                    }
                    Ok(())
                }
                .await;
                if let Err(error) = exec {
                    let _ = input.release_all().await;
                    return Err(error);
                }
            }
            _ => bail!("ACTION_UNSUPPORTED"),
        }

        self.refresh_state(session_id).await
    }
}

async fn select_all_and_type<F: FnMut() -> u64>(
    input: &CdpDesktopInput,
    text: &str,
    next: &mut F,
) -> Result<()> {
    input.key_down(InputKey::Control, next()).await?;
    input
        .key_down(InputKey::Character("a".to_owned()), next())
        .await?;
    input
        .key_up(InputKey::Character("a".to_owned()), next())
        .await?;
    input.key_up(InputKey::Control, next()).await?;
    input.insert_text(text, next()).await?;
    Ok(())
}

fn parse_input_key(raw: &str) -> Result<InputKey> {
    match raw {
        "Enter" => Ok(InputKey::Enter),
        "Tab" => Ok(InputKey::Tab),
        "Escape" => Ok(InputKey::Escape),
        "Backspace" => Ok(InputKey::Backspace),
        "Delete" => Ok(InputKey::Delete),
        "ArrowUp" => Ok(InputKey::ArrowUp),
        "ArrowDown" => Ok(InputKey::ArrowDown),
        "ArrowLeft" => Ok(InputKey::ArrowLeft),
        "ArrowRight" => Ok(InputKey::ArrowRight),
        "Shift" => Ok(InputKey::Shift),
        "Control" => Ok(InputKey::Control),
        "Alt" => Ok(InputKey::Alt),
        "Meta" => Ok(InputKey::Meta),
        single if single.chars().count() == 1 => Ok(InputKey::Character(single.to_owned())),
        _ => bail!("KEY_UNSUPPORTED"),
    }
}

fn state_cursor(state: &CurrentState) -> String {
    format!(
        "{}:{}:{}",
        state.state_version, state.target_revision, state.content_hash
    )
}

fn target_matches_query(target: &state_collector::InteractiveTarget, lower_query: &str) -> bool {
    target.element_id.to_ascii_lowercase().contains(lower_query)
        || target.role.to_ascii_lowercase().contains(lower_query)
        || target
            .name
            .as_deref()
            .is_some_and(|n| n.to_ascii_lowercase().contains(lower_query))
        || (!target.sensitive
            && target
                .value
                .as_deref()
                .is_some_and(|v| v.to_ascii_lowercase().contains(lower_query)))
        || target
            .control_type
            .as_deref()
            .is_some_and(|c| c.to_ascii_lowercase().contains(lower_query))
}

fn compact_tree_payload(state: &CurrentState) -> Value {
    let mut text_parts = Vec::new();
    if !state.title.trim().is_empty() {
        text_parts.push(state.title.trim().to_owned());
    }
    for target in &state.targets {
        if target.visible && !target.sensitive {
            if let Some(name) = target
                .name
                .as_deref()
                .map(str::trim)
                .filter(|s| !s.is_empty())
            {
                text_parts.push(name.to_owned());
            }
            if let Some(val) = target
                .value
                .as_deref()
                .map(str::trim)
                .filter(|s| !s.is_empty())
            {
                text_parts.push(val.to_owned());
            }
        }
    }
    let mut visible_text = text_parts.join(" · ");
    if visible_text.len() > 4_000 {
        visible_text.truncate(4_000);
    }
    json!({
        "url": state.url,
        "title": state.title,
        "documentReadyState": state.document_ready_state,
        "visibleTextSummary": visible_text,
        "activeTabId": state.active_tab_id,
        "tabs": state.tabs,
        "nativeDialogs": state.native_dialogs,
        "targets": state.targets,
    })
}

async fn capture_cdp_screenshot(ws_url: &str, params: Value) -> Result<String> {
    let (mut socket, _) = timeout(
        Duration::from_secs(3),
        tokio_tungstenite::connect_async(ws_url),
    )
    .await
    .map_err(|_| anyhow::anyhow!("CDP_SCREENSHOT_CONNECT_TIMEOUT"))??;
    let payload = json!({
        "id": 901,
        "method": "Page.captureScreenshot",
        "params": params,
    });
    socket.send(Message::Text(payload.to_string())).await?;
    let deadline = tokio::time::Instant::now() + Duration::from_secs(5);
    while let Some(msg) = timeout(Duration::from_secs(5), socket.next())
        .await
        .map_err(|_| anyhow::anyhow!("CDP_SCREENSHOT_TIMEOUT"))?
    {
        if tokio::time::Instant::now() >= deadline {
            bail!("CDP_SCREENSHOT_TIMEOUT");
        }
        let Message::Text(text) = msg? else {
            continue;
        };
        let value: Value = serde_json::from_str(&text)?;
        if value.get("id").and_then(Value::as_i64) != Some(901) {
            continue;
        }
        if let Some(err) = value.get("error") {
            bail!("CDP_SCREENSHOT_FAILED: {err}");
        }
        let data = value
            .pointer("/result/data")
            .and_then(Value::as_str)
            .filter(|s| !s.is_empty())
            .ok_or_else(|| anyhow::anyhow!("CDP_SCREENSHOT_EMPTY"))?;
        return Ok(data.to_owned());
    }
    bail!("CDP_SCREENSHOT_CLOSED")
}
