use super::{hex_sha256, Bounds, CdpStateCollector, EvaluatedPageState, PageStability};
use crate::BrowserSafetyObservation;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::time::{Duration, Instant};

const MAX_REGIONS: usize = 40;
const REGION_QUIET_MILLIS: u64 = 2_000;
const MIN_REGION_SAMPLES: u32 = 3;
const MAX_STABILITY_WAIT: Duration = Duration::from_secs(15);
const COMPONENT_READY_MILLIS: u64 = 250;

/// Node evidence only: this is neither an action grant nor a claim that the whole page is stable.
#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq)]
pub struct RegionalStabilityObservation {
    pub evidence_fresh: bool,
    pub max_wait_reached: bool,
    pub changing_millis: u64,
    pub transaction_free: bool,
    pub stable_regions: Vec<StableTargetRegion>,
    pub unstable_regions: Vec<UnstableTargetRegion>,
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{BoundRegionalSnapshot, CollectorCursor, CurrentState};

    fn page() -> EvaluatedPageState {
        let mut page: EvaluatedPageState = serde_json::from_value(serde_json::json!({
            "url":"https://example.test/regions", "title":"Region fixture",
            "documentReadyState":"complete", "targets":[{
                "path":"html>body>button", "role":"button", "name":"Save",
                "frameId":"main", "enabled":true, "visible":true,
                "interactive":true,
                "bounds":{"x":10,"y":10,"width":100,"height":30}
            }]
        }))
        .unwrap();
        page.document_identity = "document-a".to_owned();
        page.targets[0]
            .document_identity
            .clone_from(&page.document_identity);
        page
    }

    fn changing() -> PageStability {
        PageStability {
            evidence_fresh: true,
            route_quiet_millis: 2_000,
            focus_quiet_millis: 2_000,
            ..PageStability::default()
        }
    }

    fn sample(
        tracker: &mut RegionStabilityTracker,
        page: &EvaluatedPageState,
        network: &BrowserSafetyObservation,
        at: Instant,
    ) -> RegionalStabilityObservation {
        tracker.observe(
            page,
            "tab-a",
            network,
            &changing(),
            Duration::from_secs(10),
            at,
        )
    }

    fn matured(
        page: &EvaluatedPageState,
        network: &BrowserSafetyObservation,
        start: Instant,
    ) -> (RegionStabilityTracker, RegionalStabilityObservation) {
        let mut tracker = RegionStabilityTracker::default();
        let mut result = RegionalStabilityObservation::default();
        for second in 0..=15 {
            result = sample(
                &mut tracker,
                page,
                network,
                start + Duration::from_secs(second),
            );
        }
        (tracker, result)
    }

    #[test]
    fn requires_continuous_target_samples_and_maximum_wait() {
        let page = page();
        let network = BrowserSafetyObservation::test_fresh_for_tab("tab-a");
        let start = Instant::now();
        let id = CdpStateCollector::scoped_element_id(&page.targets[0], &page.url, "tab-a");
        let mut tracker = RegionStabilityTracker::default();
        for second in 0..15 {
            let result = sample(
                &mut tracker,
                &page,
                &network,
                start + Duration::from_secs(second),
            );
            assert!(!result.ready_for(&id));
            if second >= 2 {
                assert_eq!(result.stable_regions.len(), 1);
            }
        }
        let result = sample(
            &mut tracker,
            &page,
            &network,
            start + Duration::from_secs(15),
        );
        assert!(result.ready_for(&id));
        assert!(!result.ready_for("unproven-element"));
        assert_eq!(result.stable_regions[0].consecutive_samples, 16);
        assert_eq!(result.stable_regions[0].quiet_millis, 15_000);
        assert_eq!(
            result.unstable_regions[0].reason,
            "OUTSIDE_PROVEN_TARGET_REGIONS"
        );
    }

    #[test]
    fn sampling_gaps_and_future_clock_do_not_credit_unobserved_time() {
        let page = page();
        let network = BrowserSafetyObservation::test_fresh_for_tab("tab-a");
        let start = Instant::now();
        let (tracker, _) = matured(&page, &network, start);
        for at in [
            start + Duration::from_secs(46),
            start - Duration::from_secs(1),
        ] {
            let mut tracker = tracker.clone();
            let result = sample(&mut tracker, &page, &network, at);
            assert!(!result.max_wait_reached);
            assert_eq!(result.changing_millis, 0);
            assert!(result.stable_regions.is_empty());
            assert_eq!(tracker.windows.values().next().unwrap().samples, 1);
        }
    }

    #[test]
    fn hash_changes_at_readiness_boundaries_but_not_for_growing_counters() {
        let page = page();
        let network = BrowserSafetyObservation::test_fresh_for_tab("tab-a");
        let start = Instant::now();
        let mut tracker = RegionStabilityTracker::default();
        let mut settling = None;
        let mut ready = None;
        for second in 0..=16 {
            let result = sample(
                &mut tracker,
                &page,
                &network,
                start + Duration::from_secs(second),
            );
            if second == 3 {
                settling = Some(result.hash_bucket());
            } else if second == 14 {
                assert_eq!(settling.as_ref().unwrap(), &result.hash_bucket());
            } else if second == 15 {
                assert_ne!(settling.as_ref().unwrap(), &result.hash_bucket());
                ready = Some(result.hash_bucket());
            } else if second == 16 {
                assert_eq!(ready.as_ref().unwrap(), &result.hash_bucket());
            }
        }
    }

    #[test]
    fn missing_or_changed_target_loses_its_region_window() {
        let page = page();
        let network = BrowserSafetyObservation::test_fresh_for_tab("tab-a");
        let start = Instant::now();
        let (tracker, _) = matured(&page, &network, start);
        for kind in ["bounds", "value", "focus", "checked", "entity"] {
            let mut changed = page.clone();
            let target = &mut changed.targets[0];
            match kind {
                "bounds" => target.bounds.as_mut().unwrap().x += 1.0,
                "value" => target.value = Some("different visible value".to_owned()),
                "focus" => target.focused = true,
                "checked" => target.checked = Some(true),
                "entity" => target.semantic_context_hash = Some("another-entity".to_owned()),
                _ => unreachable!(),
            }
            let result = sample(
                &mut tracker.clone(),
                &changed,
                &network,
                start + Duration::from_secs(16),
            );
            assert!(result.stable_regions.is_empty(), "{kind}");
        }
        let mut tracker = tracker;
        let mut absent = page.clone();
        absent.targets.clear();
        sample(
            &mut tracker,
            &absent,
            &network,
            start + Duration::from_secs(16),
        );
        let returned = sample(
            &mut tracker,
            &page,
            &network,
            start + Duration::from_secs(17),
        );
        assert!(returned.stable_regions.is_empty());
    }

    #[test]
    fn every_unknown_write_or_global_transaction_blocks_readiness() {
        let page = page();
        let network = BrowserSafetyObservation::test_fresh_for_tab("tab-a");
        let start = Instant::now();
        let (tracker, _) = matured(&page, &network, start);
        let id = CdpStateCollector::scoped_element_id(&page.targets[0], &page.url, "tab-a");
        for kind in ["form", "spa", "payment", "critical", "upload", "download"] {
            let mut network = network.clone();
            match kind {
                "form" => network.active_form_submission_count = 1,
                "spa" => network.active_spa_mutation_count = 1,
                "payment" => network.active_payment_or_security_count = 1,
                "critical" => network.active_critical_transaction_count = 1,
                "upload" => network.active_upload_count = 1,
                "download" => network.active_download_count = 1,
                _ => unreachable!(),
            }
            let result = sample(
                &mut tracker.clone(),
                &page,
                &network,
                start + Duration::from_secs(16),
            );
            assert_eq!(result.stable_regions.len(), 1);
            assert!(!result.transaction_free);
            assert!(!result.ready_for(&id), "{kind}");
        }
    }

    #[test]
    fn document_route_and_tab_changes_restart_maximum_wait() {
        let page = page();
        let network = BrowserSafetyObservation::test_fresh_for_tab("tab-a");
        let start = Instant::now();
        let (tracker, _) = matured(&page, &network, start);
        for kind in ["document", "route", "tab"] {
            let mut changed = page.clone();
            let tab = if kind == "tab" { "tab-b" } else { "tab-a" };
            if kind == "document" {
                changed.document_identity = "document-b".to_owned();
                changed.targets[0]
                    .document_identity
                    .clone_from(&changed.document_identity);
            } else if kind == "route" {
                changed.url.push_str("#changed");
            }
            let result = tracker.clone().observe(
                &changed,
                tab,
                &BrowserSafetyObservation::test_fresh_for_tab(tab),
                &changing(),
                Duration::from_secs(10),
                start + Duration::from_secs(16),
            );
            assert!(!result.max_wait_reached);
            assert!(result.stable_regions.is_empty());
        }
    }

    #[test]
    fn stale_or_unproven_evidence_never_produces_ready_regions() {
        let page = page();
        let network = BrowserSafetyObservation::test_fresh_for_tab("tab-a");
        let start = Instant::now();
        for kind in [
            "network",
            "unknown-tab",
            "page",
            "truncated",
            "loading",
            "frame",
            "overlay",
            "hidden",
            "bounds",
        ] {
            let mut page = page.clone();
            let mut network = network.clone();
            let mut stability = changing();
            match kind {
                "network" => network.fresh = false,
                "unknown-tab" => {
                    network = BrowserSafetyObservation::default();
                    network.fresh = true;
                }
                "page" => stability.evidence_fresh = false,
                "truncated" => page.truncated = true,
                "loading" => page.document_ready_state = "interactive".to_owned(),
                "frame" => page.targets[0].frame_id = "child-frame".to_owned(),
                "overlay" => page.targets[0].occluded = true,
                "hidden" => page.targets[0].visible = false,
                "bounds" => page.targets[0].bounds.as_mut().unwrap().width = f64::NAN,
                _ => unreachable!(),
            }
            let mut tracker = RegionStabilityTracker::default();
            for second in 0..=16 {
                let result = tracker.observe(
                    &page,
                    "tab-a",
                    &network,
                    &stability,
                    Duration::from_secs(10),
                    start + Duration::from_secs(second),
                );
                assert!(result.stable_regions.is_empty(), "{kind}");
            }
        }
    }

    #[test]
    fn secret_values_and_names_do_not_enter_region_evidence_or_fingerprints() {
        let mut page = page();
        page.targets[0].sensitive = true;
        page.targets[0].name = Some("private-secret-name".to_owned());
        page.targets[0].value = Some("private-secret-value".to_owned());
        let network = BrowserSafetyObservation::test_fresh_for_tab("tab-a");
        let start = Instant::now();
        let (tracker, result) = matured(&page, &network, start);
        let encoded = serde_json::to_string(&result).unwrap();
        assert!(!encoded.contains("private-secret"));
        let previous = tracker.windows.values().next().unwrap().fingerprint.clone();
        page.targets[0].name = Some("replacement-secret-name".to_owned());
        page.targets[0].value = Some("replacement-secret-value".to_owned());
        let mut tracker = tracker;
        let result = sample(
            &mut tracker,
            &page,
            &network,
            start + Duration::from_secs(16),
        );
        assert_eq!(
            tracker.windows.values().next().unwrap().fingerprint,
            previous
        );
        assert!(!serde_json::to_string(&result).unwrap().contains("secret"));
    }

    #[tokio::test]
    async fn region_evidence_is_bound_to_exact_fresh_full_snapshot_and_runtime() {
        let collector = CdpStateCollector::new();
        let state: CurrentState = serde_json::from_value(serde_json::json!({
            "session_id":"region-session", "state_version":7, "target_revision":3,
            "content_hash":"current-hash", "url":"https://example.test/regions",
            "active_tab_id":"tab-a", "title":"Region fixture", "targets":[],
            "quality":"Complete", "document_ready_state":"complete",
            "network_evidence_fresh":true, "page_stability":{
                "dom_quiet_millis":0, "layout_quiet_millis":0,
                "focus_quiet_millis":2000, "route_quiet_millis":2000,
                "evidence_fresh":true
            }
        }))
        .unwrap();
        let evidence = RegionalStabilityObservation {
            evidence_fresh: true,
            ..Default::default()
        };
        let snapshot = BoundRegionalSnapshot {
            state_version: state.state_version,
            target_revision: state.target_revision,
            content_hash: state.content_hash.clone(),
            active_tab_id: state.active_tab_id.clone(),
            url: state.url.clone(),
            captured_at: Instant::now(),
            observation: evidence.clone(),
        };
        collector.cursors.lock().await.insert(
            state.session_id.clone(),
            CollectorCursor {
                regional_snapshot: Some(snapshot.clone()),
                ..Default::default()
            },
        );
        assert_eq!(
            collector.regional_stability_for_state(&state).await,
            evidence
        );
        for kind in [
            "session",
            "version",
            "revision",
            "hash",
            "url",
            "tab",
            "quality",
            "loading",
            "network",
            "component",
        ] {
            let mut changed = state.clone();
            match kind {
                "session" => changed.session_id.push_str("-other"),
                "version" => changed.state_version += 1,
                "revision" => changed.target_revision += 1,
                "hash" => changed.content_hash.push_str("-other"),
                "url" => changed.url.push_str("#other"),
                "tab" => changed.active_tab_id.push_str("-other"),
                "quality" => changed.quality = crate::StateQuality::Resyncing,
                "loading" => changed.document_ready_state = "loading".to_owned(),
                "network" => changed.network_evidence_fresh = false,
                "component" => changed.page_stability.evidence_fresh = false,
                _ => unreachable!(),
            }
            assert_eq!(
                collector.regional_stability_for_state(&changed).await,
                RegionalStabilityObservation::default(),
                "{kind}"
            );
        }
        for at in [
            Instant::now() - Duration::from_secs(11),
            Instant::now() + Duration::from_secs(60),
        ] {
            collector
                .cursors
                .lock()
                .await
                .get_mut(&state.session_id)
                .unwrap()
                .regional_snapshot = Some(BoundRegionalSnapshot {
                captured_at: at,
                ..snapshot.clone()
            });
            assert_eq!(
                collector.regional_stability_for_state(&state).await,
                RegionalStabilityObservation::default()
            );
        }
        collector
            .cursors
            .lock()
            .await
            .get_mut(&state.session_id)
            .unwrap()
            .regional_snapshot = Some(snapshot);
        collector.unregister_runtime(&state.session_id).await;
        assert_eq!(
            collector.regional_stability_for_state(&state).await,
            RegionalStabilityObservation::default()
        );
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct StableTargetRegion {
    pub element_id: String,
    pub bounds: Bounds,
    pub quiet_millis: u64,
    pub consecutive_samples: u32,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct UnstableTargetRegion {
    pub element_id: String,
    pub bounds: Option<Bounds>,
    pub reason: String,
}

impl RegionalStabilityObservation {
    /// A caller must still enforce the trusted step risk, capability, epoch, tab, state and
    /// target fences. Unknown writes remain transactions and cannot acquire this readiness.
    pub fn ready_for(&self, element_id: &str) -> bool {
        self.evidence_fresh
            && self.max_wait_reached
            && self.transaction_free
            && self
                .stable_regions
                .iter()
                .any(|region| region.element_id == element_id)
    }

    pub(super) fn hash_bucket(&self) -> serde_json::Value {
        serde_json::json!([
            self.evidence_fresh,
            self.max_wait_reached,
            self.transaction_free,
            self.stable_regions
                .iter()
                .map(|region| (&region.element_id, &region.bounds))
                .collect::<Vec<_>>(),
            self.unstable_regions,
        ])
    }
}

#[derive(Debug, Clone)]
struct TargetWindow {
    fingerprint: String,
    quiet_millis: u64,
    samples: u32,
}

#[derive(Debug, Clone, Default)]
pub(super) struct RegionStabilityTracker {
    context: String,
    last_sample: Option<Instant>,
    changing_since: Option<Instant>,
    windows: HashMap<String, TargetWindow>,
}

impl RegionStabilityTracker {
    pub(super) fn observe(
        &mut self,
        page: &EvaluatedPageState,
        tab_id: &str,
        network: &BrowserSafetyObservation,
        stability: &PageStability,
        maximum_gap: Duration,
        now: Instant,
    ) -> RegionalStabilityObservation {
        let context = hex_sha256(
            serde_json::json!([page.document_identity, tab_id, page.url])
                .to_string()
                .as_bytes(),
        );
        let interval = self
            .last_sample
            .and_then(|previous| now.checked_duration_since(previous));
        let continuous =
            self.context == context && interval.is_some_and(|elapsed| elapsed <= maximum_gap);
        if !continuous {
            *self = Self::default();
        }
        self.context = context;
        self.last_sample = Some(now);

        if page.document_identity.is_empty()
            || tab_id.is_empty()
            || page.document_ready_state != "complete"
            || page.truncated
            || !network.fresh
            || network
                .active_network_request_count_for_tab(tab_id)
                .is_none()
            || !stability.evidence_fresh
        {
            self.windows.clear();
            self.changing_since = None;
            return RegionalStabilityObservation::default();
        }

        let full_page_ready = network.network_quiet_millis_for_tab(tab_id)
            >= COMPONENT_READY_MILLIS
            && stability.dom_quiet_millis >= COMPONENT_READY_MILLIS
            && stability.layout_quiet_millis >= COMPONENT_READY_MILLIS
            && stability.focus_quiet_millis >= COMPONENT_READY_MILLIS
            && stability.route_quiet_millis >= COMPONENT_READY_MILLIS;
        let changing = if full_page_ready {
            self.changing_since = None;
            Duration::ZERO
        } else {
            now.duration_since(*self.changing_since.get_or_insert(now))
        };
        let mut result = RegionalStabilityObservation {
            evidence_fresh: true,
            max_wait_reached: changing >= MAX_STABILITY_WAIT,
            changing_millis: changing.as_millis().min(300_000) as u64,
            transaction_free: network.active_form_submission_count == 0
                && network.active_spa_mutation_count == 0
                && network.active_payment_or_security_count == 0
                && network.active_critical_transaction_count == 0
                && network.active_upload_count == 0
                && network.active_download_count == 0,
            ..RegionalStabilityObservation::default()
        };
        // The rest of the page stays unknown even if every enumerated target is stable. This
        // includes noninteractive DOM, canvas, omitted targets and background network activity.
        result.unstable_regions.push(UnstableTargetRegion {
            element_id: String::new(),
            bounds: None,
            reason: "OUTSIDE_PROVEN_TARGET_REGIONS".to_owned(),
        });
        let mut next_windows = HashMap::new();
        for target in page.targets.iter().take(MAX_REGIONS) {
            let element_id = CdpStateCollector::scoped_element_id(target, &page.url, tab_id);
            let bounds_ready = target.bounds.as_ref().is_some_and(|bounds| {
                [bounds.x, bounds.y, bounds.width, bounds.height]
                    .into_iter()
                    .all(f64::is_finite)
                    && bounds.x >= 0.0
                    && bounds.y >= 0.0
                    && bounds.width > 0.0
                    && bounds.height > 0.0
            });
            let reason = if target.frame_id != "main"
                || target.document_identity != page.document_identity
            {
                Some("UNPROVEN_FRAME_CONTEXT")
            } else if !target.interactive
                || !target.enabled
                || !target.visible
                || !target.in_viewport
                || target.occluded
                || !bounds_ready
            {
                Some("TARGET_NOT_ACTIONABLE")
            } else {
                None
            };
            if let Some(reason) = reason {
                result.unstable_regions.push(UnstableTargetRegion {
                    element_id,
                    bounds: target.bounds.clone(),
                    reason: reason.to_owned(),
                });
                continue;
            }
            let fingerprint = hex_sha256(
                serde_json::json!([
                    element_id,
                    target.bounds,
                    target.enabled,
                    target.visible,
                    target.in_viewport,
                    target.occluded,
                    target.focused,
                    target.checked,
                    target.selected,
                    if target.sensitive {
                        None
                    } else {
                        target.value.as_ref()
                    },
                ])
                .to_string()
                .as_bytes(),
            );
            let previous = self.windows.get(&element_id);
            let window = match previous
                .filter(|previous| continuous && previous.fingerprint == fingerprint)
            {
                Some(previous) => TargetWindow {
                    fingerprint,
                    quiet_millis: previous
                        .quiet_millis
                        .saturating_add(interval.expect("continuous sample").as_millis() as u64)
                        .min(300_000),
                    samples: previous.samples.saturating_add(1),
                },
                None => TargetWindow {
                    fingerprint,
                    quiet_millis: 0,
                    samples: 1,
                },
            };
            if window.quiet_millis >= REGION_QUIET_MILLIS
                && window.samples >= MIN_REGION_SAMPLES
                && stability.route_quiet_millis >= COMPONENT_READY_MILLIS
                && stability.focus_quiet_millis >= COMPONENT_READY_MILLIS
            {
                result.stable_regions.push(StableTargetRegion {
                    element_id: element_id.clone(),
                    bounds: target.bounds.clone().expect("actionable bounds"),
                    quiet_millis: window.quiet_millis,
                    consecutive_samples: window.samples,
                });
            } else {
                result.unstable_regions.push(UnstableTargetRegion {
                    element_id: element_id.clone(),
                    bounds: target.bounds.clone(),
                    reason: "TARGET_WINDOW_INCOMPLETE".to_owned(),
                });
            }
            next_windows.insert(element_id, window);
        }
        if page.targets.len() > MAX_REGIONS {
            result.unstable_regions.push(UnstableTargetRegion {
                element_id: String::new(),
                bounds: None,
                reason: "TARGET_REGION_BUDGET".to_owned(),
            });
        }
        self.windows = next_windows;
        result
    }
}
