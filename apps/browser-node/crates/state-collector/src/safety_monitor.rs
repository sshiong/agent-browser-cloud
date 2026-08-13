use futures_util::{SinkExt, StreamExt};
use serde::Deserialize;
use std::collections::{HashMap, HashSet};
use std::sync::Arc;
use std::time::Instant;
use tokio::sync::RwLock;
use tokio::task::JoinHandle;
use tokio::time::{timeout, Duration};
use tokio_tungstenite::tungstenite::Message;

const MAX_NETWORK_QUIET_MILLIS: u64 = 300_000;
const TRANSACTION_SETTLE_WINDOW: Duration = Duration::from_secs(10);
const MAX_POLICY_RULES: usize = 32;
const MAX_POLICY_VALUE_BYTES: usize = 512;

#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct BrowserTransactionPolicy {
    pub version: u64,
    pub expected_origins: Vec<String>,
    pub payment_security_route_prefixes: Vec<String>,
    pub critical_transaction_route_prefixes: Vec<String>,
    pub policy_hash: String,
}

impl BrowserTransactionPolicy {
    pub fn validate(&self) -> anyhow::Result<()> {
        let empty = self.version == 0
            && self.expected_origins.is_empty()
            && self.payment_security_route_prefixes.is_empty()
            && self.critical_transaction_route_prefixes.is_empty()
            && self.policy_hash.is_empty();
        if empty {
            return Ok(());
        }
        anyhow::ensure!(
            self.version > 0,
            "transaction policy version must be positive"
        );
        anyhow::ensure!(
            !self.expected_origins.is_empty()
                && self.expected_origins.len() <= 16
                && self.payment_security_route_prefixes.len() <= MAX_POLICY_RULES
                && self.critical_transaction_route_prefixes.len() <= MAX_POLICY_RULES,
            "transaction policy rule count is invalid"
        );
        anyhow::ensure!(
            sorted_unique(&self.expected_origins)
                && sorted_unique(&self.payment_security_route_prefixes)
                && sorted_unique(&self.critical_transaction_route_prefixes),
            "transaction policy rules must be canonical"
        );
        anyhow::ensure!(
            self.expected_origins.iter().all(|value| {
                value.len() <= MAX_POLICY_VALUE_BYTES
                    && normalized_origin(value).as_deref() == Some(value)
            }),
            "transaction policy origin is invalid"
        );
        anyhow::ensure!(
            self.payment_security_route_prefixes
                .iter()
                .chain(self.critical_transaction_route_prefixes.iter())
                .all(|value| valid_route_prefix(value)),
            "transaction policy route prefix is invalid"
        );
        anyhow::ensure!(
            self.policy_hash.len() == 64
                && self
                    .policy_hash
                    .bytes()
                    .all(|value| value.is_ascii_hexdigit())
                && self.policy_hash == self.canonical_hash(),
            "transaction policy hash is invalid"
        );
        Ok(())
    }

    fn canonical_hash(&self) -> String {
        use sha2::{Digest, Sha256};
        let mut value = format!("browser-transaction-policy-v1\n{}\n", self.version);
        for origin in &self.expected_origins {
            value.push_str("O:");
            value.push_str(origin);
            value.push('\n');
        }
        for route in &self.payment_security_route_prefixes {
            value.push_str("P:");
            value.push_str(route);
            value.push('\n');
        }
        for route in &self.critical_transaction_route_prefixes {
            value.push_str("C:");
            value.push_str(route);
            value.push('\n');
        }
        format!("{:x}", Sha256::digest(value.as_bytes()))
    }
}

/// Browser Node 从持续 CDP 事件流读取的安全点活动快照。
///
/// `fresh` 只在 Browser 下载事件和至少一个 Page Network Domain 都成功启用后为 true。
/// 连接丢失后不会在同一 Runtime 代内重新变为 true，因为重连无法重建已经在途的请求。
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct BrowserSafetyObservation {
    pub fresh: bool,
    pub active_upload_count: u32,
    pub active_download_count: u32,
    pub active_form_submission_count: u32,
    pub active_spa_mutation_count: u32,
    pub active_payment_or_security_count: u32,
    pub active_critical_transaction_count: u32,
    pub active_network_request_count: u32,
    pub last_network_activity: Option<Instant>,
}

impl BrowserSafetyObservation {
    /// Returns bounded monotonic evidence suitable for a recovery Ready Gate.
    /// Any observer gap or in-flight request fails closed to zero.
    pub fn network_quiet_millis(&self) -> u64 {
        if !self.fresh
            || self.active_network_request_count > 0
            || self.last_network_activity.is_none()
        {
            return 0;
        }
        self.last_network_activity
            .expect("checked above")
            .elapsed()
            .as_millis()
            .try_into()
            .unwrap_or(u64::MAX)
            .min(MAX_NETWORK_QUIET_MILLIS)
    }
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct CdpVersion {
    web_socket_debugger_url: String,
}

#[derive(Debug, Default, Clone, Copy)]
struct RequestActivity {
    upload: bool,
    download: bool,
    form_submission: bool,
    spa_mutation: bool,
    payment_or_security: bool,
    critical_transaction: bool,
}

#[derive(Debug, Default)]
struct ActivityTracker {
    requests: HashMap<(String, String), RequestActivity>,
    browser_downloads: HashSet<String>,
    download_events_enabled: bool,
    network_sessions: HashSet<String>,
    fresh_allowed: bool,
    was_fresh: bool,
    last_network_activity: Option<Instant>,
    spa_mutation_settle_until: Option<Instant>,
    payment_or_security_settle_until: Option<Instant>,
    critical_transaction_settle_until: Option<Instant>,
}

impl ActivityTracker {
    fn observation(&self) -> BrowserSafetyObservation {
        // The same browser download is normally visible both as a Network response and as a
        // Browser download GUID. Use the larger live set as a conservative de-duplicated count
        // instead of summing both protocol surfaces.
        let network_download_count = self
            .requests
            .values()
            .filter(|activity| activity.download)
            .count();
        BrowserSafetyObservation {
            fresh: self.fresh_allowed
                && self.download_events_enabled
                && !self.network_sessions.is_empty(),
            active_upload_count: self
                .requests
                .values()
                .filter(|activity| activity.upload)
                .count()
                .try_into()
                .unwrap_or(u32::MAX),
            active_download_count: network_download_count
                .max(self.browser_downloads.len())
                .try_into()
                .unwrap_or(u32::MAX),
            active_form_submission_count: self
                .requests
                .values()
                .filter(|activity| activity.form_submission)
                .count()
                .try_into()
                .unwrap_or(u32::MAX),
            active_spa_mutation_count: active_or_settling_count(
                self.requests
                    .values()
                    .filter(|activity| activity.spa_mutation)
                    .count(),
                self.spa_mutation_settle_until,
            ),
            active_payment_or_security_count: active_or_settling_count(
                self.requests
                    .values()
                    .filter(|activity| activity.payment_or_security)
                    .count(),
                self.payment_or_security_settle_until,
            ),
            active_critical_transaction_count: active_or_settling_count(
                self.requests
                    .values()
                    .filter(|activity| activity.critical_transaction)
                    .count(),
                self.critical_transaction_settle_until,
            ),
            active_network_request_count: self.requests.len().try_into().unwrap_or(u32::MAX),
            last_network_activity: self.last_network_activity,
        }
    }

    fn mark_network_activity(&mut self) {
        self.last_network_activity = Some(Instant::now());
    }

    fn remove_session(&mut self, session_id: &str) {
        self.network_sessions.remove(session_id);
        let request_ids = self
            .requests
            .keys()
            .filter(|(request_session, _)| request_session == session_id)
            .cloned()
            .collect::<Vec<_>>();
        let completed = request_ids
            .into_iter()
            .filter_map(|request_id| self.requests.remove(&request_id))
            .collect::<Vec<_>>();
        self.hold_completed_transactions(completed);
    }

    fn next_observation(&mut self) -> BrowserSafetyObservation {
        let observation = self.observation();
        self.was_fresh |= observation.fresh;
        observation
    }

    fn complete_request(&mut self, session_id: &str, request_id: &str) {
        if let Some(activity) = self
            .requests
            .remove(&(session_id.to_owned(), request_id.to_owned()))
        {
            self.hold_completed_transactions([activity]);
        }
    }

    fn hold_completed_transactions(
        &mut self,
        completed: impl IntoIterator<Item = RequestActivity>,
    ) {
        let until = Instant::now() + TRANSACTION_SETTLE_WINDOW;
        for activity in completed {
            if activity.spa_mutation {
                extend_deadline(&mut self.spa_mutation_settle_until, until);
            }
            if activity.payment_or_security {
                extend_deadline(&mut self.payment_or_security_settle_until, until);
            }
            if activity.critical_transaction {
                extend_deadline(&mut self.critical_transaction_settle_until, until);
            }
        }
    }
}

fn active_or_settling_count(active: usize, settle_until: Option<Instant>) -> u32 {
    let settling = usize::from(settle_until.is_some_and(|until| until > Instant::now()));
    active
        .saturating_add(settling)
        .try_into()
        .unwrap_or(u32::MAX)
}

fn extend_deadline(deadline: &mut Option<Instant>, next: Instant) {
    *deadline = Some(deadline.map_or(next, |current| current.max(next)));
}

pub(crate) fn spawn(
    session_id: String,
    endpoint: String,
    transaction_policy: BrowserTransactionPolicy,
    observations: Arc<RwLock<HashMap<String, BrowserSafetyObservation>>>,
) -> JoinHandle<()> {
    tokio::spawn(async move {
        let mut established_once = false;
        loop {
            let websocket_url = match browser_websocket(&endpoint).await {
                Ok(websocket_url) => websocket_url,
                Err(error) => {
                    tracing::warn!(
                        session_id,
                        error = %error,
                        "Browser safety observer could not resolve CDP Browser endpoint"
                    );
                    tokio::time::sleep(Duration::from_secs(1)).await;
                    continue;
                }
            };
            let mut tracker = ActivityTracker {
                fresh_allowed: !established_once,
                last_network_activity: Some(Instant::now()),
                ..ActivityTracker::default()
            };
            let result = observe_browser(
                &websocket_url,
                &session_id,
                &observations,
                &mut tracker,
                &transaction_policy,
            )
            .await;
            established_once |= tracker.was_fresh;
            publish(
                &observations,
                &session_id,
                BrowserSafetyObservation::default(),
            )
            .await;
            match result {
                Ok(()) => {
                    tracing::warn!(session_id, "Browser safety observer CDP connection closed");
                }
                Err(error) => {
                    tracing::warn!(
                        session_id,
                        error = %error,
                        "Browser safety observer CDP connection failed"
                    );
                }
            }
            tokio::time::sleep(Duration::from_secs(1)).await;
        }
    })
}

async fn browser_websocket(endpoint: &str) -> anyhow::Result<String> {
    let client = reqwest::Client::builder()
        .connect_timeout(Duration::from_secs(1))
        .timeout(Duration::from_secs(2))
        .no_proxy()
        .build()?;
    let version: CdpVersion = client
        .get(format!("{endpoint}/json/version"))
        .send()
        .await?
        .error_for_status()?
        .json()
        .await?;
    Ok(version.web_socket_debugger_url)
}

async fn observe_browser(
    websocket_url: &str,
    session_id: &str,
    observations: &Arc<RwLock<HashMap<String, BrowserSafetyObservation>>>,
    tracker: &mut ActivityTracker,
    transaction_policy: &BrowserTransactionPolicy,
) -> anyhow::Result<()> {
    let (mut socket, _) = timeout(
        Duration::from_secs(3),
        tokio_tungstenite::connect_async(websocket_url),
    )
    .await
    .map_err(|_| anyhow::anyhow!("CDP browser safety connection timed out"))??;

    send_command(
        &mut socket,
        8_001,
        "Target.setDiscoverTargets",
        serde_json::json!({"discover": true}),
        None,
    )
    .await?;
    send_command(
        &mut socket,
        8_002,
        "Target.setAutoAttach",
        serde_json::json!({
            "autoAttach": true,
            "waitForDebuggerOnStart": false,
            "flatten": true
        }),
        None,
    )
    .await?;
    send_command(
        &mut socket,
        8_003,
        "Browser.setDownloadBehavior",
        serde_json::json!({"behavior": "default", "eventsEnabled": true}),
        None,
    )
    .await?;

    let mut next_command_id = 8_100_i64;
    let mut network_enable_commands = HashMap::<i64, String>::new();
    loop {
        let message = match timeout(Duration::from_secs(1), socket.next()).await {
            Ok(Some(message)) => message,
            Ok(None) => break,
            Err(_) => {
                // Recompute local settle deadlines even when the page is otherwise silent. This
                // does not generate Control Plane traffic; the normal 5-second reporter reads the
                // refreshed bounded snapshot.
                let observation = tracker.next_observation();
                publish(observations, session_id, observation).await;
                continue;
            }
        };
        let Message::Text(text) = message? else {
            continue;
        };
        let event: serde_json::Value = serde_json::from_str(&text)?;
        if let Some(id) = event.get("id").and_then(serde_json::Value::as_i64) {
            if let Some(network_session) = network_enable_commands.remove(&id) {
                if event.get("error").is_none() {
                    tracker.network_sessions.insert(network_session);
                }
            } else if id == 8_003 && event.get("error").is_none() {
                tracker.download_events_enabled = true;
            } else if event.get("error").is_some() && matches!(id, 8_001..=8_003) {
                anyhow::bail!("required CDP safety domain command {id} failed");
            }
            let observation = tracker.next_observation();
            publish(observations, session_id, observation).await;
            continue;
        }

        let method = event
            .get("method")
            .and_then(serde_json::Value::as_str)
            .unwrap_or_default();
        let cdp_session = event
            .get("sessionId")
            .and_then(serde_json::Value::as_str)
            .unwrap_or_default();
        if method.starts_with("Network.") || method.starts_with("Browser.download") {
            tracker.mark_network_activity();
        }
        match method {
            "Target.attachedToTarget"
                if event
                    .pointer("/params/targetInfo/type")
                    .and_then(serde_json::Value::as_str)
                    == Some("page") =>
            {
                tracker.mark_network_activity();
                let attached_session = event
                    .pointer("/params/sessionId")
                    .and_then(serde_json::Value::as_str)
                    .ok_or_else(|| anyhow::anyhow!("attached Page Target has no sessionId"))?
                    .to_owned();
                let command_id = next_command_id;
                next_command_id = next_command_id.saturating_add(1);
                send_command(
                    &mut socket,
                    command_id,
                    "Network.enable",
                    serde_json::json!({
                        "maxTotalBufferSize": 65_536,
                        "maxResourceBufferSize": 16_384,
                        "maxPostDataSize": 0
                    }),
                    Some(&attached_session),
                )
                .await?;
                network_enable_commands.insert(command_id, attached_session);
            }
            "Target.detachedFromTarget" => {
                tracker.mark_network_activity();
                if let Some(detached_session) = event
                    .pointer("/params/sessionId")
                    .and_then(serde_json::Value::as_str)
                {
                    tracker.remove_session(detached_session);
                }
            }
            "Network.requestWillBeSent" => {
                let request_id = event
                    .pointer("/params/requestId")
                    .and_then(serde_json::Value::as_str)
                    .unwrap_or_default();
                if !request_id.is_empty() && !cdp_session.is_empty() {
                    let request = event
                        .pointer("/params/request")
                        .cloned()
                        .unwrap_or_default();
                    let method = request
                        .get("method")
                        .and_then(serde_json::Value::as_str)
                        .unwrap_or_default()
                        .to_ascii_uppercase();
                    let resource_type = event
                        .pointer("/params/type")
                        .and_then(serde_json::Value::as_str)
                        .unwrap_or_default();
                    let content_type = header(&request["headers"], "content-type");
                    let content_disposition = header(&request["headers"], "content-disposition");
                    let upload = content_type.contains("multipart/form-data")
                        || content_type.contains("application/octet-stream")
                        || content_disposition.contains("form-data")
                        || content_disposition.contains("attachment");
                    let form_submission = resource_type == "Document"
                        && !matches!(method.as_str(), "GET" | "HEAD" | "OPTIONS");
                    let mutation = !matches!(method.as_str(), "GET" | "HEAD" | "OPTIONS");
                    let spa_mutation = mutation && matches!(resource_type, "Fetch" | "XHR");
                    // Inspect only the route while the CDP event is in memory. Neither the URL,
                    // query string, headers nor body cross the Node boundary.
                    let request_url = request
                        .get("url")
                        .and_then(serde_json::Value::as_str)
                        .unwrap_or_default();
                    let route = request_route(request_url)
                        .chars()
                        .take(2_048)
                        .collect::<String>()
                        .to_ascii_lowercase();
                    let payment_or_security = mutation
                        && (route_matches(
                            &route,
                            &[
                                "checkout", "payment", "billing", "purchase", "transfer", "wallet",
                                "account", "password", "security", "mfa", "2fa", "otp", "webauthn",
                                "login", "signin",
                            ],
                        ) || policy_route_matches(
                            request_url,
                            &route,
                            &transaction_policy.expected_origins,
                            &transaction_policy.payment_security_route_prefixes,
                        ));
                    let critical_transaction = mutation
                        && (route_matches(
                            &route,
                            &[
                                "commit",
                                "confirm",
                                "submit",
                                "order",
                                "booking",
                                "reservation",
                                "transfer",
                                "purchase",
                                "checkout",
                                "payment",
                                "execute",
                            ],
                        ) || policy_route_matches(
                            request_url,
                            &route,
                            &transaction_policy.expected_origins,
                            &transaction_policy.critical_transaction_route_prefixes,
                        ));
                    tracker.requests.insert(
                        (cdp_session.to_owned(), request_id.to_owned()),
                        RequestActivity {
                            upload,
                            form_submission,
                            spa_mutation,
                            payment_or_security,
                            critical_transaction,
                            ..RequestActivity::default()
                        },
                    );
                }
            }
            "Network.responseReceived" => {
                let request_id = event
                    .pointer("/params/requestId")
                    .and_then(serde_json::Value::as_str)
                    .unwrap_or_default();
                let disposition = header(
                    event
                        .pointer("/params/response/headers")
                        .unwrap_or(&serde_json::Value::Null),
                    "content-disposition",
                );
                if !request_id.is_empty()
                    && !cdp_session.is_empty()
                    && disposition.contains("attachment")
                {
                    tracker
                        .requests
                        .entry((cdp_session.to_owned(), request_id.to_owned()))
                        .or_default()
                        .download = true;
                }
            }
            "Network.loadingFinished" | "Network.loadingFailed" => {
                if let Some(request_id) = event
                    .pointer("/params/requestId")
                    .and_then(serde_json::Value::as_str)
                {
                    tracker.complete_request(cdp_session, request_id);
                }
            }
            "Browser.downloadWillBegin" => {
                if let Some(guid) = event
                    .pointer("/params/guid")
                    .and_then(serde_json::Value::as_str)
                {
                    tracker.browser_downloads.insert(guid.to_owned());
                }
            }
            "Browser.downloadProgress" => {
                if matches!(
                    event
                        .pointer("/params/state")
                        .and_then(serde_json::Value::as_str),
                    Some("completed" | "canceled")
                ) {
                    if let Some(guid) = event
                        .pointer("/params/guid")
                        .and_then(serde_json::Value::as_str)
                    {
                        tracker.browser_downloads.remove(guid);
                    }
                }
            }
            _ => {}
        }
        let observation = tracker.next_observation();
        publish(observations, session_id, observation).await;
    }
    Ok(())
}

async fn send_command<S>(
    socket: &mut tokio_tungstenite::WebSocketStream<S>,
    id: i64,
    method: &str,
    params: serde_json::Value,
    session_id: Option<&str>,
) -> anyhow::Result<()>
where
    S: tokio::io::AsyncRead + tokio::io::AsyncWrite + Unpin,
{
    let mut command = serde_json::json!({"id": id, "method": method, "params": params});
    if let Some(session_id) = session_id {
        command["sessionId"] = serde_json::Value::String(session_id.to_owned());
    }
    socket.send(Message::Text(command.to_string())).await?;
    Ok(())
}

fn header(headers: &serde_json::Value, expected: &str) -> String {
    headers
        .as_object()
        .and_then(|headers| {
            headers.iter().find_map(|(name, value)| {
                name.eq_ignore_ascii_case(expected)
                    .then(|| value.as_str())
                    .flatten()
            })
        })
        .unwrap_or_default()
        .to_ascii_lowercase()
}

fn request_route(url: &str) -> &str {
    let without_origin = if let Some(scheme) = url.find("://") {
        let authority_start = scheme + 3;
        url[authority_start..]
            .find('/')
            .map(|path| &url[authority_start + path..])
            .unwrap_or_default()
    } else {
        url
    };
    without_origin.split(['?', '#']).next().unwrap_or_default()
}

fn route_matches(route: &str, terms: &[&str]) -> bool {
    route
        .split(|character: char| !character.is_ascii_alphanumeric())
        .any(|segment| !segment.is_empty() && terms.contains(&segment))
}

fn prefix_matches(route: &str, prefixes: &[String]) -> bool {
    prefixes.iter().any(|prefix| {
        route == prefix
            || (route.starts_with(prefix)
                && (prefix.ends_with('/')
                    || route.as_bytes().get(prefix.len()).copied() == Some(b'/')))
    })
}

fn policy_route_matches(
    request_url: &str,
    route: &str,
    expected_origins: &[String],
    prefixes: &[String],
) -> bool {
    normalized_origin(request_url).is_some_and(|origin| expected_origins.contains(&origin))
        && prefix_matches(route, prefixes)
}

fn valid_route_prefix(value: &str) -> bool {
    value.len() <= MAX_POLICY_VALUE_BYTES
        && value.starts_with('/')
        && !value.contains("..")
        && !value.contains(['?', '#'])
        && value == value.to_ascii_lowercase()
}

fn sorted_unique(values: &[String]) -> bool {
    values.windows(2).all(|pair| pair[0] < pair[1])
}

fn normalized_origin(url: &str) -> Option<String> {
    let parsed = reqwest::Url::parse(url).ok()?;
    if !matches!(parsed.scheme(), "http" | "https")
        || !parsed.username().is_empty()
        || parsed.password().is_some()
    {
        return None;
    }
    let host = parsed.host_str()?.to_ascii_lowercase();
    let port = parsed.port();
    let default_port = matches!(
        (parsed.scheme(), port),
        ("http", Some(80)) | ("https", Some(443))
    );
    Some(match port.filter(|_| !default_port) {
        Some(port) => format!("{}://{}:{}", parsed.scheme(), host, port),
        None => format!("{}://{}", parsed.scheme(), host),
    })
}

async fn publish(
    observations: &Arc<RwLock<HashMap<String, BrowserSafetyObservation>>>,
    session_id: &str,
    observation: BrowserSafetyObservation,
) {
    observations
        .write()
        .await
        .insert(session_id.to_owned(), observation);
}

#[cfg(test)]
mod tests {
    use super::*;
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    use tokio::net::TcpListener;

    #[test]
    fn network_quiet_evidence_fails_closed_for_observer_gaps_and_inflight_requests() {
        let unavailable = BrowserSafetyObservation {
            fresh: false,
            last_network_activity: Some(Instant::now() - Duration::from_secs(5)),
            ..BrowserSafetyObservation::default()
        };
        assert_eq!(unavailable.network_quiet_millis(), 0);

        let inflight = BrowserSafetyObservation {
            fresh: true,
            active_network_request_count: 1,
            last_network_activity: Some(Instant::now() - Duration::from_secs(5)),
            ..BrowserSafetyObservation::default()
        };
        assert_eq!(inflight.network_quiet_millis(), 0);
    }

    #[test]
    fn network_quiet_evidence_reports_bounded_elapsed_time() {
        let observation = BrowserSafetyObservation {
            fresh: true,
            last_network_activity: Some(Instant::now() - Duration::from_secs(2)),
            ..BrowserSafetyObservation::default()
        };
        assert!((2_000..=2_100).contains(&observation.network_quiet_millis()));

        let bounded = BrowserSafetyObservation {
            fresh: true,
            last_network_activity: Some(Instant::now() - Duration::from_secs(600)),
            ..BrowserSafetyObservation::default()
        };
        assert_eq!(bounded.network_quiet_millis(), MAX_NETWORK_QUIET_MILLIS);
    }

    #[test]
    fn remembers_freshness_after_the_last_page_detaches() {
        let mut tracker = ActivityTracker {
            download_events_enabled: true,
            fresh_allowed: true,
            ..ActivityTracker::default()
        };
        tracker.network_sessions.insert("page-1".to_owned());
        assert!(tracker.next_observation().fresh);

        tracker.remove_session("page-1");
        assert!(!tracker.next_observation().fresh);
        assert!(tracker.was_fresh);
    }

    #[test]
    fn classifies_only_route_segments_without_query_values() {
        assert_eq!(
            request_route("https://example.test/api/checkout?password=secret"),
            "/api/checkout"
        );
        assert!(route_matches("/api/checkout", &["checkout"]));
        assert!(!route_matches("/api/checkoutline", &["checkout"]));
        assert!(!route_matches(
            request_route("https://example.test/search?q=payment"),
            &["payment"]
        ));
        assert_eq!(request_route("https://payment.example.test"), "");
    }

    #[test]
    fn validates_origin_scoped_transaction_policy_and_rejects_tampering() {
        let mut policy = BrowserTransactionPolicy {
            version: 7,
            expected_origins: vec!["https://crm.example.test".to_owned()],
            payment_security_route_prefixes: vec!["/api/authorize".to_owned()],
            critical_transaction_route_prefixes: vec!["/cases/finalize".to_owned()],
            policy_hash: String::new(),
        };
        policy.policy_hash = policy.canonical_hash();
        policy.validate().unwrap();

        assert!(prefix_matches(
            "/api/authorize/confirm",
            &policy.payment_security_route_prefixes
        ));
        assert!(!prefix_matches(
            "/api/authorize-token",
            &policy.payment_security_route_prefixes
        ));
        assert_eq!(
            normalized_origin("https://CRM.EXAMPLE.TEST:443/api/authorize?secret=x"),
            Some("https://crm.example.test".to_owned())
        );
        assert_ne!(
            normalized_origin("https://other.example.test/api/authorize"),
            policy.expected_origins.first().cloned()
        );
        assert!(policy_route_matches(
            "https://crm.example.test/api/authorize/confirm?secret=x",
            "/api/authorize/confirm",
            &policy.expected_origins,
            &policy.payment_security_route_prefixes,
        ));
        assert!(!policy_route_matches(
            "https://other.example.test/api/authorize/confirm",
            "/api/authorize/confirm",
            &policy.expected_origins,
            &policy.payment_security_route_prefixes,
        ));

        policy.critical_transaction_route_prefixes = vec!["/tampered".to_owned()];
        assert!(policy.validate().is_err());
    }

    #[test]
    fn completed_transaction_remains_visible_during_settle_window() {
        let mut tracker = ActivityTracker::default();
        tracker.requests.insert(
            ("page-1".to_owned(), "request-1".to_owned()),
            RequestActivity {
                spa_mutation: true,
                payment_or_security: true,
                critical_transaction: true,
                ..RequestActivity::default()
            },
        );

        tracker.complete_request("page-1", "request-1");

        assert!(tracker.requests.is_empty());
        let observation = tracker.observation();
        assert_eq!(observation.active_spa_mutation_count, 1);
        assert_eq!(observation.active_payment_or_security_count, 1);
        assert_eq!(observation.active_critical_transaction_count, 1);

        tracker.spa_mutation_settle_until = Some(Instant::now() - Duration::from_millis(1));
        tracker.payment_or_security_settle_until = Some(Instant::now() - Duration::from_millis(1));
        tracker.critical_transaction_settle_until = Some(Instant::now() - Duration::from_millis(1));
        let expired = tracker.observation();
        assert_eq!(expired.active_spa_mutation_count, 0);
        assert_eq!(expired.active_payment_or_security_count, 0);
        assert_eq!(expired.active_critical_transaction_count, 0);
    }

    #[tokio::test]
    async fn tracks_upload_submission_and_download_lifecycle() {
        let websocket_listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let websocket_address = websocket_listener.local_addr().unwrap();
        let websocket_task = tokio::spawn(async move {
            let (stream, _) = websocket_listener.accept().await.unwrap();
            let mut socket = tokio_tungstenite::accept_async(stream).await.unwrap();
            for expected in [
                "Target.setDiscoverTargets",
                "Target.setAutoAttach",
                "Browser.setDownloadBehavior",
            ] {
                let Message::Text(command) = socket.next().await.unwrap().unwrap() else {
                    panic!("expected CDP command")
                };
                let command: serde_json::Value = serde_json::from_str(&command).unwrap();
                assert_eq!(command["method"], expected);
                socket
                    .send(Message::Text(
                        serde_json::json!({"id": command["id"], "result": {}}).to_string(),
                    ))
                    .await
                    .unwrap();
            }
            socket
                .send(Message::Text(
                    serde_json::json!({
                        "method": "Target.attachedToTarget",
                        "params": {
                            "sessionId": "page-session-1",
                            "targetInfo": {"type": "page"}
                        }
                    })
                    .to_string(),
                ))
                .await
                .unwrap();
            let Message::Text(network_enable) = socket.next().await.unwrap().unwrap() else {
                panic!("expected Network.enable")
            };
            let network_enable: serde_json::Value = serde_json::from_str(&network_enable).unwrap();
            assert_eq!(network_enable["method"], "Network.enable");
            assert_eq!(network_enable["sessionId"], "page-session-1");
            socket
                .send(Message::Text(
                    serde_json::json!({"id": network_enable["id"], "result": {}}).to_string(),
                ))
                .await
                .unwrap();
            for (request_id, resource_type, url) in [
                (
                    "spa-confirm-1",
                    "XHR",
                    "https://example.test/api/cart/confirm",
                ),
                (
                    "account-security-1",
                    "Fetch",
                    "https://example.test/api/account/password",
                ),
            ] {
                socket
                    .send(Message::Text(
                        serde_json::json!({
                            "sessionId": "page-session-1",
                            "method": "Network.requestWillBeSent",
                            "params": {
                                "requestId": request_id,
                                "type": resource_type,
                                "request": {
                                    "method": "POST",
                                    "url": url,
                                    "headers": {"Content-Type": "application/json"}
                                }
                            }
                        })
                        .to_string(),
                    ))
                    .await
                    .unwrap();
            }
            socket
                .send(Message::Text(
                    serde_json::json!({
                        "sessionId": "page-session-1",
                        "method": "Network.requestWillBeSent",
                        "params": {
                            "requestId": "upload-1",
                            "type": "Document",
                            "request": {
                                "method": "POST",
                                "headers": {"Content-Type": "multipart/form-data; boundary=x"}
                            }
                        }
                    })
                    .to_string(),
                ))
                .await
                .unwrap();
            for request_id in ["spa-confirm-1", "account-security-1"] {
                socket
                    .send(Message::Text(
                        serde_json::json!({
                            "sessionId": "page-session-1",
                            "method": "Network.loadingFinished",
                            "params": {"requestId": request_id}
                        })
                        .to_string(),
                    ))
                    .await
                    .unwrap();
            }
            socket
                .send(Message::Text(
                    serde_json::json!({
                        "method": "Browser.downloadWillBegin",
                        "params": {"guid": "download-1"}
                    })
                    .to_string(),
                ))
                .await
                .unwrap();
            tokio::time::sleep(Duration::from_millis(100)).await;
            socket
                .send(Message::Text(
                    serde_json::json!({
                        "sessionId": "page-session-1",
                        "method": "Network.loadingFinished",
                        "params": {"requestId": "upload-1"}
                    })
                    .to_string(),
                ))
                .await
                .unwrap();
            socket
                .send(Message::Text(
                    serde_json::json!({
                        "method": "Browser.downloadProgress",
                        "params": {"guid": "download-1", "state": "completed"}
                    })
                    .to_string(),
                ))
                .await
                .unwrap();
            tokio::time::sleep(Duration::from_millis(100)).await;
        });

        let http_listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let http_address = http_listener.local_addr().unwrap();
        let http_task = tokio::spawn(async move {
            let (mut stream, _) = http_listener.accept().await.unwrap();
            let mut request = vec![0_u8; 4096];
            let count = stream.read(&mut request).await.unwrap();
            assert!(String::from_utf8_lossy(&request[..count]).starts_with("GET /json/version "));
            let body = serde_json::json!({
                "webSocketDebuggerUrl": format!("ws://{websocket_address}/devtools/browser/1")
            })
            .to_string();
            let response = format!(
                "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}",
                body.len(),
                body
            );
            stream.write_all(response.as_bytes()).await.unwrap();
        });

        let observations = Arc::new(RwLock::new(HashMap::new()));
        let monitor = spawn(
            "ses_safety".to_owned(),
            format!("http://{http_address}"),
            BrowserTransactionPolicy::default(),
            Arc::clone(&observations),
        );
        let mut active = None;
        for _ in 0..100 {
            let observation = observations
                .read()
                .await
                .get("ses_safety")
                .cloned()
                .unwrap_or_default();
            if observation.fresh
                && observation.active_upload_count == 1
                && observation.active_download_count == 1
                && observation.active_form_submission_count == 1
                && observation.active_spa_mutation_count == 1
                && observation.active_payment_or_security_count == 1
                && observation.active_critical_transaction_count == 1
            {
                active = Some(observation);
                break;
            }
            tokio::time::sleep(Duration::from_millis(10)).await;
        }
        assert!(
            active.is_some(),
            "active browser operations were not observed"
        );
        // Finish the transaction requests only after an active snapshot has been observed. They
        // remain protected by the settle window; aborting the monitor below avoids a slow test.
        let mut completed = None;
        for _ in 0..100 {
            let observation = observations
                .read()
                .await
                .get("ses_safety")
                .cloned()
                .unwrap_or_default();
            if observation.fresh
                && observation.active_upload_count == 0
                && observation.active_download_count == 0
                && observation.active_form_submission_count == 0
            {
                completed = Some(observation);
                break;
            }
            tokio::time::sleep(Duration::from_millis(10)).await;
        }
        assert!(
            completed.is_some(),
            "completed browser operations remained active"
        );

        monitor.abort();
        websocket_task.await.unwrap();
        http_task.await.unwrap();
    }
}
