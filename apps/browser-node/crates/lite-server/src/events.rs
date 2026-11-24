//! In-process resumable event bus (SSE `Last-Event-ID`) + Prometheus metrics counters for L0 Lite.

use chrono::Utc;
use serde::Serialize;
use serde_json::Value;
use std::collections::VecDeque;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Mutex;
use tokio::sync::broadcast;

const MAX_RETAINED_EVENTS: usize = 512;

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct LiteEvent {
    pub id: u64,
    pub event_type: String,
    pub owner_agent_id: String,
    pub session_id: Option<String>,
    pub trace_id: Option<String>,
    pub timestamp: String,
    pub payload: Value,
}

pub struct EventBus {
    next_id: AtomicU64,
    history: Mutex<VecDeque<LiteEvent>>,
    tx: broadcast::Sender<LiteEvent>,
}

impl EventBus {
    pub fn new() -> Self {
        let (tx, _) = broadcast::channel(256);
        Self {
            next_id: AtomicU64::new(1),
            history: Mutex::new(VecDeque::with_capacity(MAX_RETAINED_EVENTS)),
            tx,
        }
    }

    pub fn publish(
        &self,
        event_type: &str,
        owner_agent_id: &str,
        session_id: Option<&str>,
        trace_id: Option<&str>,
        payload: Value,
    ) -> LiteEvent {
        let id = self.next_id.fetch_add(1, Ordering::SeqCst);
        let ev = LiteEvent {
            id,
            event_type: event_type.to_owned(),
            owner_agent_id: owner_agent_id.to_owned(),
            session_id: session_id.map(ToOwned::to_owned),
            trace_id: trace_id.map(ToOwned::to_owned),
            timestamp: Utc::now().to_rfc3339(),
            payload,
        };
        if let Ok(mut hist) = self.history.lock() {
            if hist.len() >= MAX_RETAINED_EVENTS {
                hist.pop_front();
            }
            hist.push_back(ev.clone());
        }
        let _ = self.tx.send(ev.clone());
        ev
    }

    /// Replays all retained events with `id > last_event_id` visible to `agent_id` (or all if admin).
    pub fn replay_since(
        &self,
        last_event_id: u64,
        agent_id: &str,
        is_admin: bool,
    ) -> Vec<LiteEvent> {
        let Ok(hist) = self.history.lock() else {
            return Vec::new();
        };
        hist.iter()
            .filter(|e| e.id > last_event_id && (is_admin || e.owner_agent_id == agent_id))
            .cloned()
            .collect()
    }

    pub fn subscribe(&self) -> broadcast::Receiver<LiteEvent> {
        self.tx.subscribe()
    }

    pub fn total_published(&self) -> u64 {
        self.next_id.load(Ordering::SeqCst).saturating_sub(1)
    }
}

impl Default for EventBus {
    fn default() -> Self {
        Self::new()
    }
}

#[derive(Default)]
pub struct LiteMetrics {
    pub requests_total: AtomicU64,
    pub mcp_calls_total: AtomicU64,
    pub cdp_proxy_active: AtomicU64,
    pub cdp_proxy_total: AtomicU64,
}

impl LiteMetrics {
    pub fn render_prometheus(
        &self,
        running_sessions: u64,
        warm_pool_ready: u64,
        max_sessions: u64,
        backpressure_level: u64,
        events_published: u64,
    ) -> String {
        format!(
            "# HELP browsercloud_lite_requests_total Total HTTP requests served by browsercloud-lite\n\
             # TYPE browsercloud_lite_requests_total counter\n\
             browsercloud_lite_requests_total {}\n\
             # HELP browsercloud_lite_mcp_calls_total Total MCP JSON-RPC calls served\n\
             # TYPE browsercloud_lite_mcp_calls_total counter\n\
             browsercloud_lite_mcp_calls_total {}\n\
             # HELP browsercloud_lite_sessions_running Currently running Chromium sessions\n\
             # TYPE browsercloud_lite_sessions_running gauge\n\
             browsercloud_lite_sessions_running {}\n\
             # HELP browsercloud_lite_warm_pool_ready Clean warm Chromium instances ready\n\
             # TYPE browsercloud_lite_warm_pool_ready gauge\n\
             browsercloud_lite_warm_pool_ready {}\n\
             # HELP browsercloud_lite_max_sessions Configured session concurrency limit\n\
             # TYPE browsercloud_lite_max_sessions gauge\n\
             browsercloud_lite_max_sessions {}\n\
             # HELP browsercloud_lite_backpressure_level Graded backpressure state (0=NORMAL..5=CLOSED)\n\
             # TYPE browsercloud_lite_backpressure_level gauge\n\
             browsercloud_lite_backpressure_level {}\n\
             # HELP browsercloud_lite_events_published_total Total SSE events published\n\
             # TYPE browsercloud_lite_events_published_total counter\n\
             browsercloud_lite_events_published_total {}\n\
             # HELP browsercloud_lite_cdp_proxy_active Active authenticated CDP proxy streams\n\
             # TYPE browsercloud_lite_cdp_proxy_active gauge\n\
             browsercloud_lite_cdp_proxy_active {}\n\
             # HELP browsercloud_lite_cdp_proxy_total Total authenticated CDP proxy connections\n\
             # TYPE browsercloud_lite_cdp_proxy_total counter\n\
             browsercloud_lite_cdp_proxy_total {}\n",
            self.requests_total.load(Ordering::Relaxed),
            self.mcp_calls_total.load(Ordering::Relaxed),
            running_sessions,
            warm_pool_ready,
            max_sessions,
            backpressure_level,
            events_published,
            self.cdp_proxy_active.load(Ordering::Relaxed),
            self.cdp_proxy_total.load(Ordering::Relaxed),
        )
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn replays_missed_events_with_per_agent_isolation() {
        let bus = EventBus::new();
        let e1 = bus.publish("session.created", "agent_a", Some("ses_1"), None, json!({}));
        let e2 = bus.publish("session.created", "agent_b", Some("ses_2"), None, json!({}));
        let e3 = bus.publish(
            "session.navigated",
            "agent_a",
            Some("ses_1"),
            None,
            json!({}),
        );
        assert_eq!(e1.id, 1);
        assert_eq!(e2.id, 2);
        assert_eq!(e3.id, 3);

        let a_replay = bus.replay_since(1, "agent_a", false);
        assert_eq!(a_replay.len(), 1);
        assert_eq!(a_replay[0].id, 3);

        let b_replay = bus.replay_since(0, "agent_b", false);
        assert_eq!(b_replay.len(), 1);
        assert_eq!(b_replay[0].id, 2);

        let admin_replay = bus.replay_since(1, "admin", true);
        assert_eq!(admin_replay.len(), 2);
    }
}
