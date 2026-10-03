//! Private mutation/interaction evidence. This ledger contains no semantic names or input values.

use crate::{Bounds, CdpStateCollector, EvaluatedPageState};
use futures_util::{SinkExt, StreamExt};
use serde::Deserialize;
use std::collections::HashSet;
use tokio::time::{timeout_at, Duration, Instant};
use tokio_tungstenite::tungstenite::Message;

const MAX_SEQUENCE: u64 = 9_007_199_254_740_991;

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
        websocket_url: &str,
        page: &mut EvaluatedPageState,
    ) -> anyhow::Result<()> {
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
        let (mut socket, _) = timeout_at(deadline, tokio_tungstenite::connect_async(websocket_url))
            .await
            .map_err(|_| anyhow::anyhow!("Regional event connection timed out"))??;
        let (identity, frame_id) = Self::query_document_frame(&mut socket, 10, deadline).await?;
        anyhow::ensure!(
            identity == page.document_identity,
            "Regional event document changed"
        );
        // No cross-origin privileges: only the exact root Frame and its isolated JavaScript world.
        let world = observer_command(
            &mut socket,
            11,
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
        let observed = observer_command(
            &mut socket,
            12,
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
        let regions = snapshot.validate(&paths)?;
        let current = Self::query_document_frame(&mut socket, 13, deadline).await?;
        anyhow::ensure!(
            current == (identity, frame_id),
            "Regional event document changed"
        );
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
        Ok(())
    }
}

async fn observer_command<S>(
    socket: &mut tokio_tungstenite::WebSocketStream<S>,
    id: i64,
    method: &str,
    params: serde_json::Value,
    deadline: Instant,
) -> anyhow::Result<serde_json::Value>
where
    S: tokio::io::AsyncRead + tokio::io::AsyncWrite + Unpin,
{
    timeout_at(
        deadline,
        socket.send(Message::Text(
            serde_json::json!({"id":id,"method":method,"params":params}).to_string(),
        )),
    )
    .await
    .map_err(|_| anyhow::anyhow!("Regional event command timed out"))??;
    while let Some(message) = timeout_at(deadline, socket.next())
        .await
        .map_err(|_| anyhow::anyhow!("Regional event command timed out"))?
    {
        anyhow::ensure!(
            Instant::now() < deadline,
            "Regional event command timed out"
        );
        let Message::Text(text) = message? else {
            continue;
        };
        anyhow::ensure!(
            text.len() <= 131_072,
            "Regional event response budget exceeded"
        );
        let response: serde_json::Value = serde_json::from_str(&text)?;
        if response["id"].as_i64() != Some(id) {
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

#[cfg(test)]
mod tests {
    use super::*;

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
                for command in [10, 11, 12, 13] {
                    let Message::Text(text) = socket.next().await.unwrap().unwrap() else {
                        panic!()
                    };
                    let request: serde_json::Value = serde_json::from_str(&text).unwrap();
                    assert_eq!(request["id"], command);
                    let result = match command {
                        10 | 13 => {
                            assert_eq!(request["method"], "Page.getFrameTree");
                            serde_json::json!({"frameTree":{"frame":{"id":"root", "loaderId":
                                if changed && command == 13 { "new-loader" } else { "loader" }}}})
                        }
                        11 => {
                            assert_eq!(request["method"], "Page.createIsolatedWorld");
                            assert_eq!(request["params"]["frameId"], "root");
                            assert_eq!(request["params"]["grantUniveralAccess"], false);
                            serde_json::json!({"executionContextId":71})
                        }
                        12 => {
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
                .observe_region_events(&format!("ws://{address}"), &mut page)
                .await;
            assert_eq!(result.is_ok(), !changed);
            assert_eq!(page.region_event_evidence_fresh, !changed);
            assert_eq!(page.targets[0].region_event_proof.is_some(), !changed);
            server.await.unwrap();
        }
    }
}
