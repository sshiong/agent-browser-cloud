//! Private mutation/interaction evidence. This ledger contains no semantic names or input values.

use crate::{Bounds, CdpStateCollector, EvaluatedPageState};
use futures_util::{SinkExt, StreamExt};
use serde::Deserialize;
use std::collections::HashSet;
use std::sync::{
    atomic::{AtomicU64, Ordering},
    Arc,
};
use tokio::sync::Mutex;
use tokio::time::{timeout_at, Duration, Instant};
use tokio_tungstenite::tungstenite::Message;

const MAX_SEQUENCE: u64 = 9_007_199_254_740_991;
static NEXT_STYLESHEET_EPOCH: AtomicU64 = AtomicU64::new(1);

fn next_stylesheet_epoch() -> anyhow::Result<u64> {
    let mut current = NEXT_STYLESHEET_EPOCH.load(Ordering::Relaxed);
    loop {
        let next = current
            .checked_add(1)
            .ok_or_else(|| anyhow::anyhow!("Regional stylesheet epoch exhausted"))?;
        match NEXT_STYLESHEET_EPOCH.compare_exchange_weak(
            current,
            next,
            Ordering::Relaxed,
            Ordering::Relaxed,
        ) {
            Ok(_) => return Ok(current),
            Err(observed) => current = observed,
        }
    }
}

type ObserverSocket =
    tokio_tungstenite::WebSocketStream<tokio_tungstenite::MaybeTlsStream<tokio::net::TcpStream>>;
pub(super) type SharedObserver = Arc<Mutex<Option<ObserverConnection>>>;

/// Keeping the Page connection open preserves stylesheet events between complete samples.
/// A dropped probe owns and drops its connection; no partial subscription is reused.
pub(super) struct ObserverConnection {
    socket: ObserverSocket,
    endpoint: String,
    document_identity: String,
    command_sequence: u64,
    stylesheet_epoch: u64,
    stylesheet_sequence: u64,
    received_messages: u32,
    subscribed: bool,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(super) struct RegionEventProof {
    pub path: String,
    pub identity: u64,
    pub last_event_sequence: u64,
    pub animation_active: bool,
    pub bounds: Bounds,
    #[serde(skip)]
    pub observer_nonce: String,
    #[serde(skip)]
    pub stylesheet_epoch: u64,
    #[serde(skip)]
    pub stylesheet_sequence: u64,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct ObserverSnapshot {
    nonce: String,
    current_sequence: u64,
    fresh: bool,
    regions: Vec<RegionEventProof>,
}

impl ObserverSnapshot {
    fn validate(mut self, paths: &[String]) -> anyhow::Result<Vec<RegionEventProof>> {
        anyhow::ensure!(
            self.fresh
                && self.nonce.len() == 32
                && self.nonce.bytes().all(|value| value.is_ascii_hexdigit())
                && (1..=MAX_SEQUENCE).contains(&self.current_sequence)
                && self.regions.len() <= 40,
            "Regional event observation is unavailable"
        );
        let mut seen = HashSet::new();
        let mut identities = HashSet::new();
        for region in &mut self.regions {
            anyhow::ensure!(
                paths.contains(&region.path)
                    && seen.insert(region.path.clone())
                    && identities.insert(region.identity)
                    && (1..=MAX_SEQUENCE).contains(&region.identity)
                    && (1..=self.current_sequence).contains(&region.last_event_sequence)
                    && [
                        region.bounds.x,
                        region.bounds.y,
                        region.bounds.width,
                        region.bounds.height
                    ]
                    .into_iter()
                    .all(f64::is_finite),
                "Regional event observation is invalid"
            );
            region.observer_nonce.clone_from(&self.nonce);
        }
        Ok(self.regions)
    }
}

impl CdpStateCollector {
    pub(super) async fn observe_region_events(
        &self,
        session_id: &str,
        websocket_url: &str,
        page: &mut EvaluatedPageState,
    ) -> anyhow::Result<()> {
        page.region_event_evidence_fresh = false;
        for target in &mut page.targets {
            target.region_event_proof = None;
        }
        let paths = page
            .targets
            .iter()
            .take(40)
            .filter(|target| {
                target.frame_id == "main" && target.interactive && !target.path.contains(">>")
            })
            .map(|target| target.path.clone())
            .collect::<Vec<_>>();
        if paths.is_empty() {
            return Ok(());
        }
        anyhow::ensure!(
            paths.iter().all(|path| path.len() <= 4096),
            "Regional event path budget exceeded"
        );
        let deadline = Instant::now() + Duration::from_secs(3);
        let shared = self
            .region_observers
            .lock()
            .await
            .entry(session_id.to_owned())
            .or_default()
            .clone();
        let mut stored = timeout_at(deadline, shared.lock_owned())
            .await
            .map_err(|_| anyhow::anyhow!("Regional event lock timed out"))?;
        let mut connection = match stored.take().filter(|connection| {
            connection.endpoint == websocket_url
                && connection.document_identity == page.document_identity
        }) {
            Some(connection) => connection,
            None => {
                let stylesheet_epoch = next_stylesheet_epoch()?;
                let (socket, _) =
                    timeout_at(deadline, tokio_tungstenite::connect_async(websocket_url))
                        .await
                        .map_err(|_| anyhow::anyhow!("Regional event connection timed out"))??;
                ObserverConnection {
                    socket,
                    endpoint: websocket_url.to_owned(),
                    document_identity: page.document_identity.clone(),
                    command_sequence: 0,
                    stylesheet_epoch,
                    stylesheet_sequence: 1,
                    received_messages: 0,
                    subscribed: false,
                }
            }
        };
        connection.received_messages = 0;
        let (identity, frame_id) = connection.document(deadline).await?;
        anyhow::ensure!(
            identity == page.document_identity,
            "Regional event document changed"
        );
        if !connection.subscribed {
            connection
                .command("DOM.enable", serde_json::json!({}), deadline)
                .await?;
            connection
                .command("CSS.enable", serde_json::json!({}), deadline)
                .await?;
            connection.subscribed = true;
        }
        // No cross-origin privileges: only the exact root Frame and its isolated JavaScript world.
        let world = connection
            .command(
                "Page.createIsolatedWorld",
                serde_json::json!({"frameId":frame_id,"worldName":"agentbrowser-region-observer-v1",
                "grantUniveralAccess":false}),
                deadline,
            )
            .await?;
        let context_id = world["executionContextId"]
            .as_i64()
            .filter(|value| *value > 0)
            .ok_or_else(|| anyhow::anyhow!("Regional event context is unavailable"))?;
        let expression = include_str!("region_observer.js")
            .replace("__REGION_PATHS__", &serde_json::to_string(&paths)?);
        let stylesheet_before = connection.stylesheet_sequence;
        let observed = connection
            .command(
                "Runtime.evaluate",
                serde_json::json!({"expression":expression,"contextId":context_id,
                "returnByValue":true}),
                deadline,
            )
            .await?;
        anyhow::ensure!(
            observed.get("exceptionDetails").is_none(),
            "Regional event probe failed"
        );
        let snapshot: ObserverSnapshot = serde_json::from_value(
            observed
                .pointer("/result/value")
                .cloned()
                .ok_or_else(|| anyhow::anyhow!("Regional event result is unavailable"))?,
        )?;
        let mut regions = snapshot.validate(&paths)?;
        let current = connection.document(deadline).await?;
        anyhow::ensure!(
            current == (identity, frame_id),
            "Regional event document changed"
        );
        // The isolated-world evaluate is a renderer response barrier on this same subscription.
        // A stylesheet change overlapping the DOM observation cannot supply coherent evidence.
        anyhow::ensure!(
            stylesheet_before == connection.stylesheet_sequence,
            "Regional stylesheet changed during observation"
        );
        for region in &mut regions {
            region.stylesheet_epoch = connection.stylesheet_epoch;
            region.stylesheet_sequence = connection.stylesheet_sequence;
        }
        for region in regions {
            if let Some(target) = page.targets.iter_mut().find(|target| {
                target.frame_id == "main"
                    && target.path == region.path
                    && target.bounds.as_ref() == Some(&region.bounds)
            }) {
                target.region_event_proof = Some(region);
            }
        }
        page.region_event_evidence_fresh = true;
        *stored = Some(connection);
        Ok(())
    }
}

impl ObserverConnection {
    async fn document(&mut self, deadline: Instant) -> anyhow::Result<(String, String)> {
        let value = self
            .command("Page.getFrameTree", serde_json::json!({}), deadline)
            .await?;
        let frame = &value["frameTree"]["frame"];
        let identifier = |name: &str| {
            frame[name].as_str().filter(|value| {
                !value.is_empty() && value.len() <= 128 && !value.chars().any(char::is_control)
            })
        };
        let (Some(frame_id), Some(loader_id)) = (identifier("id"), identifier("loaderId")) else {
            anyhow::bail!("Regional document identity unavailable");
        };
        anyhow::ensure!(
            frame.get("parentId").is_none(),
            "Regional main document identity invalid"
        );
        Ok((
            crate::hex_sha256(serde_json::to_string(&(frame_id, loader_id))?.as_bytes()),
            frame_id.to_owned(),
        ))
    }

    async fn command(
        &mut self,
        method: &str,
        params: serde_json::Value,
        deadline: Instant,
    ) -> anyhow::Result<serde_json::Value> {
        self.command_sequence = self
            .command_sequence
            .checked_add(1)
            .filter(|value| *value <= MAX_SEQUENCE)
            .ok_or_else(|| anyhow::anyhow!("Regional command sequence exhausted"))?;
        let id = self.command_sequence;
        timeout_at(
            deadline,
            self.socket.send(Message::Text(
                serde_json::json!({"id":id,"method":method,"params":params}).to_string(),
            )),
        )
        .await
        .map_err(|_| anyhow::anyhow!("Regional event command timed out"))??;
        while let Some(message) = timeout_at(deadline, self.socket.next())
            .await
            .map_err(|_| anyhow::anyhow!("Regional event command timed out"))?
        {
            anyhow::ensure!(
                Instant::now() < deadline,
                "Regional event command timed out"
            );
            self.received_messages = self.received_messages.saturating_add(1);
            anyhow::ensure!(
                self.received_messages <= 4096,
                "Regional event message budget exceeded"
            );
            let Message::Text(text) = message? else {
                continue;
            };
            anyhow::ensure!(
                text.len() <= 131_072,
                "Regional event response budget exceeded"
            );
            let response: serde_json::Value = serde_json::from_str(&text)?;
            if response["method"]
                .as_str()
                .is_some_and(|method| method.starts_with("CSS."))
            {
                self.stylesheet_sequence = self
                    .stylesheet_sequence
                    .checked_add(1)
                    .ok_or_else(|| anyhow::anyhow!("Regional stylesheet sequence exhausted"))?;
            }
            if response["id"].as_u64() != Some(id) {
                continue;
            }
            anyhow::ensure!(
                response.get("error").is_none(),
                "Regional event command rejected"
            );
            return response
                .get("result")
                .cloned()
                .ok_or_else(|| anyhow::anyhow!("Regional event result is unavailable"));
        }
        anyhow::bail!("Regional event connection closed")
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn observed_page() -> EvaluatedPageState {
        let mut page: EvaluatedPageState = serde_json::from_value(serde_json::json!({
            "url":"https://example.test", "title":"Observer fixture", "targets":[{
                "path":"button:nth-of-type(1)","frameId":"main","role":"button",
                "enabled":true,"visible":true,"interactive":true,
                "bounds":{"x":10,"y":20,"width":100,"height":30}}]}))
        .unwrap();
        page.document_identity = crate::hex_sha256(
            serde_json::to_string(&("root", "loader"))
                .unwrap()
                .as_bytes(),
        );
        page
    }

    async fn reply(
        socket: &mut tokio_tungstenite::WebSocketStream<tokio::net::TcpStream>,
        request: &serde_json::Value,
    ) {
        let result = match request["method"].as_str().unwrap() {
            "Page.getFrameTree" => {
                serde_json::json!({"frameTree":{"frame":{"id":"root", "loaderId":"loader"}}})
            }
            "Page.createIsolatedWorld" => serde_json::json!({"executionContextId":71}),
            "Runtime.evaluate" => serde_json::json!({"result":{"value":snapshot()}}),
            "DOM.enable" | "CSS.enable" => serde_json::json!({}),
            _ => panic!("unexpected observer command"),
        };
        socket
            .send(Message::Text(
                serde_json::json!({"id":request["id"],"result":result}).to_string(),
            ))
            .await
            .unwrap();
    }

    #[tokio::test]
    async fn stylesheet_subscription_survives_samples_but_overlap_and_reconnect_reset_proof() {
        use tokio::net::TcpListener;
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let address = listener.local_addr().unwrap();
        let server = tokio::spawn(async move {
            for connection in 0..2 {
                let (stream, _) = listener.accept().await.unwrap();
                let mut socket = tokio_tungstenite::accept_async(stream).await.unwrap();
                let last = if connection == 0 { 14 } else { 6 };
                for id in 1..=last {
                    let Message::Text(text) = socket.next().await.unwrap().unwrap() else {
                        panic!()
                    };
                    let request: serde_json::Value = serde_json::from_str(&text).unwrap();
                    assert_eq!(request["id"], id);
                    if connection == 0 && matches!(id, 7 | 13) {
                        socket.send(Message::Text(serde_json::json!({"method":"CSS.styleSheetChanged", "params":{"styleSheetId":"private-sheet"}}).to_string())).await.unwrap();
                    }
                    reply(&mut socket, &request).await;
                }
            }
        });
        let collector = CdpStateCollector::new();
        let mut page = observed_page();
        let endpoint = format!("ws://{address}");
        collector
            .observe_region_events("session", &endpoint, &mut page)
            .await
            .unwrap();
        let first = page.targets[0].region_event_proof.clone().unwrap();
        collector
            .observe_region_events("session", &endpoint, &mut page)
            .await
            .unwrap();
        let second = page.targets[0].region_event_proof.clone().unwrap();
        assert_eq!(first.observer_nonce, second.observer_nonce);
        assert_eq!(first.stylesheet_epoch, second.stylesheet_epoch);
        assert_eq!(second.stylesheet_sequence, first.stylesheet_sequence + 1);
        assert!(collector
            .observe_region_events("session", &endpoint, &mut page)
            .await
            .is_err());
        assert!(!page.region_event_evidence_fresh);
        assert!(page.targets[0].region_event_proof.is_none());
        collector
            .observe_region_events("session", &endpoint, &mut page)
            .await
            .unwrap();
        let reconnected = page.targets[0].region_event_proof.as_ref().unwrap();
        assert_eq!(first.observer_nonce, reconnected.observer_nonce);
        assert_ne!(first.stylesheet_epoch, reconnected.stylesheet_epoch);
        assert_eq!(reconnected.stylesheet_sequence, first.stylesheet_sequence);
        server.await.unwrap();
    }

    #[tokio::test]
    async fn rejected_stylesheet_subscription_never_reaches_page_probe_or_reuses_connection() {
        use tokio::net::TcpListener;
        for rejected in ["DOM.enable", "CSS.enable"] {
            let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
            let address = listener.local_addr().unwrap();
            let server = tokio::spawn(async move {
                let (stream, _) = listener.accept().await.unwrap();
                let mut socket = tokio_tungstenite::accept_async(stream).await.unwrap();
                loop {
                    let Message::Text(text) = socket.next().await.unwrap().unwrap() else {
                        panic!()
                    };
                    let request: serde_json::Value = serde_json::from_str(&text).unwrap();
                    if request["method"] == rejected {
                        socket.send(Message::Text(serde_json::json!({"id":request["id"],"error":{"code":-32601,"message":"private-error-marker"}}).to_string())).await.unwrap();
                        break;
                    }
                    assert!(matches!(
                        request["method"].as_str(),
                        Some("Page.getFrameTree" | "DOM.enable")
                    ));
                    reply(&mut socket, &request).await;
                }
            });
            let collector = CdpStateCollector::new();
            let mut page = observed_page();
            let error = collector
                .observe_region_events("session", &format!("ws://{address}"), &mut page)
                .await
                .unwrap_err();
            assert_eq!(error.to_string(), "Regional event command rejected");
            assert!(!page.region_event_evidence_fresh);
            let shared = collector.region_observers.lock().await["session"].clone();
            assert!(shared.lock().await.is_none());
            server.await.unwrap();
        }
    }

    #[tokio::test]
    async fn cancelling_region_probe_discards_the_inflight_subscription() {
        use tokio::net::TcpListener;
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let address = listener.local_addr().unwrap();
        let (sent, received) = tokio::sync::oneshot::channel();
        let server = tokio::spawn(async move {
            let (stream, _) = listener.accept().await.unwrap();
            let mut socket = tokio_tungstenite::accept_async(stream).await.unwrap();
            let request = socket.next().await.unwrap().unwrap();
            assert!(matches!(request, Message::Text(_)));
            sent.send(()).unwrap();
            assert!(
                timeout_at(Instant::now() + Duration::from_secs(1), socket.next())
                    .await
                    .is_ok()
            );
        });
        let collector = CdpStateCollector::new();
        let task_collector = collector.clone();
        let task = tokio::spawn(async move {
            task_collector
                .observe_region_events("session", &format!("ws://{address}"), &mut observed_page())
                .await
        });
        received.await.unwrap();
        task.abort();
        assert!(task.await.unwrap_err().is_cancelled());
        let shared = collector.region_observers.lock().await["session"].clone();
        assert!(shared.lock().await.is_none());
        server.await.unwrap();
    }

    fn snapshot() -> serde_json::Value {
        serde_json::json!({"nonce":"a".repeat(32),"currentSequence":7,"fresh":true,
            "regions":[{"path":"button:nth-of-type(1)","identity":1,
                "lastEventSequence":4,"animationActive":false,
                "bounds":{"x":10,"y":20,"width":100,"height":30}}]})
    }

    #[test]
    fn page_results_cannot_supply_or_export_private_event_evidence() {
        let page: EvaluatedPageState = serde_json::from_value(serde_json::json!({
            "url":"https://example.test", "title":"Observer fixture",
            "region_event_evidence_fresh":true, "targets":[{
                "path":"button:nth-of-type(1)","role":"button","enabled":true,"visible":true,
                "region_event_proof":{"observer_nonce":"page-marker","identity":1}}]}))
        .unwrap();
        assert!(!page.region_event_evidence_fresh);
        assert!(page.targets[0].region_event_proof.is_none());
        assert!(serde_json::to_value(&page.targets[0])
            .unwrap()
            .get("region_event_proof")
            .is_none());
    }

    #[test]
    fn accepts_only_bounded_isolated_observer_identity_and_sequences() {
        let paths = vec!["button:nth-of-type(1)".to_owned()];
        let proof = serde_json::from_value::<ObserverSnapshot>(snapshot())
            .unwrap()
            .validate(&paths)
            .unwrap();
        assert_eq!(proof[0].observer_nonce, "a".repeat(32));
        assert_eq!(proof[0].last_event_sequence, 4);
        for case in [
            "nonce",
            "fresh",
            "zero",
            "overflow",
            "event",
            "identity",
            "path",
            "duplicate",
            "budget",
        ] {
            let mut value = snapshot();
            match case {
                "nonce" => value["nonce"] = serde_json::json!("page-marker"),
                "fresh" => value["fresh"] = serde_json::json!(false),
                "zero" => value["currentSequence"] = serde_json::json!(0),
                "overflow" => value["currentSequence"] = serde_json::json!(MAX_SEQUENCE + 1),
                "event" => value["regions"][0]["lastEventSequence"] = serde_json::json!(8),
                "identity" => value["regions"][0]["identity"] = serde_json::json!(0),
                "path" => value["regions"][0]["path"] = serde_json::json!("another-target"),
                "duplicate" => {
                    value["regions"] = serde_json::json!([value["regions"][0], value["regions"][0]])
                }
                "budget" => {
                    value["regions"] = serde_json::json!(vec![value["regions"][0].clone(); 41])
                }
                _ => unreachable!(),
            }
            assert!(
                serde_json::from_value::<ObserverSnapshot>(value)
                    .unwrap()
                    .validate(&paths)
                    .is_err(),
                "{case}"
            );
        }
    }

    #[tokio::test]
    async fn binds_probe_to_exact_root_document_and_isolated_context_without_extra_privileges() {
        use tokio::net::TcpListener;
        for changed in [false, true] {
            let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
            let address = listener.local_addr().unwrap();
            let server = tokio::spawn(async move {
                let (stream, _) = listener.accept().await.unwrap();
                let mut socket = tokio_tungstenite::accept_async(stream).await.unwrap();
                for command in 1..=6 {
                    let Message::Text(text) = socket.next().await.unwrap().unwrap() else {
                        panic!()
                    };
                    let request: serde_json::Value = serde_json::from_str(&text).unwrap();
                    assert_eq!(request["id"], command);
                    let result = match command {
                        1 | 6 => {
                            assert_eq!(request["method"], "Page.getFrameTree");
                            serde_json::json!({"frameTree":{"frame":{"id":"root", "loaderId":
                                if changed && command == 6 { "new-loader" } else { "loader" }}}})
                        }
                        2 | 3 => {
                            assert_eq!(
                                request["method"],
                                if command == 2 {
                                    "DOM.enable"
                                } else {
                                    "CSS.enable"
                                }
                            );
                            serde_json::json!({})
                        }
                        4 => {
                            assert_eq!(request["method"], "Page.createIsolatedWorld");
                            assert_eq!(request["params"]["frameId"], "root");
                            assert_eq!(request["params"]["grantUniveralAccess"], false);
                            serde_json::json!({"executionContextId":71})
                        }
                        5 => {
                            assert_eq!(request["method"], "Runtime.evaluate");
                            assert_eq!(request["params"]["contextId"], 71);
                            assert_eq!(request["params"]["returnByValue"], true);
                            serde_json::json!({"result":{"value":snapshot()}})
                        }
                        _ => unreachable!(),
                    };
                    socket
                        .send(Message::Text(
                            serde_json::json!({"id":command,"result":result}).to_string(),
                        ))
                        .await
                        .unwrap();
                }
            });
            let mut page: EvaluatedPageState = serde_json::from_value(serde_json::json!({
                "url":"https://example.test", "title":"Observer fixture", "targets":[{
                    "path":"button:nth-of-type(1)","frameId":"main","role":"button",
                    "enabled":true,"visible":true,"interactive":true,
                    "bounds":{"x":10,"y":20,"width":100,"height":30}}]}))
            .unwrap();
            page.document_identity = crate::hex_sha256(
                serde_json::to_string(&("root", "loader"))
                    .unwrap()
                    .as_bytes(),
            );
            let result = CdpStateCollector::new()
                .observe_region_events("observer-fixture", &format!("ws://{address}"), &mut page)
                .await;
            assert_eq!(result.is_ok(), !changed);
            assert_eq!(page.region_event_evidence_fresh, !changed);
            assert_eq!(page.targets[0].region_event_proof.is_some(), !changed);
            server.await.unwrap();
        }
    }
}
