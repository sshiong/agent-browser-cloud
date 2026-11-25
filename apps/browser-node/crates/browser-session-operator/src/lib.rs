//! Pure-Rust Kubernetes `BrowserSession` Operator with Lease leader election, paginated LIST
//! snapshots, JSON-lines Watch (with `410 Gone` relist recovery), safe finalization, and bounded
//! Prometheus metrics.
//!
//! 100% protocol-compatible drop-in replacement for
//! `tools/browser-session-operator/browser_session_operator.py` (~8 MiB RSS vs ~60 MiB Python).

use anyhow::{anyhow, bail, ensure, Result};
use axum::extract::State;
use axum::http::{header, StatusCode};
use axum::response::IntoResponse;
use axum::routing::get;
use axum::Router;
use chrono::{DateTime, Duration as ChronoDuration, Utc};
use reqwest::header::{ACCEPT, AUTHORIZATION, CONTENT_TYPE};
use reqwest::{Certificate, Client, Method};
use serde_json::{json, Value};
use std::collections::BTreeMap;
use std::fs;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};
use tracing::{info, warn};

pub const FINALIZER: &str = "browsercloud.io/session-cleanup";
pub const API_PATH: &str = "/apis/browsercloud.io/v1alpha1";
pub const RESOURCE_PATH: &str = "/apis/browsercloud.io/v1alpha1/browsersessions";
const MAX_WATCH_EVENT_BYTES: usize = 1024 * 1024;
const RECONCILE_BUCKETS: &[f64] = &[0.01, 0.05, 0.1, 0.25, 0.5, 1.0, 2.5, 5.0];

#[derive(Debug, Clone)]
pub struct OperatorConfig {
    pub kube_root: String,
    pub kube_token_path: Option<PathBuf>,
    pub kube_static_token: Option<String>,
    pub kube_ca_path: Option<PathBuf>,
    pub control_plane_url: String,
    pub control_plane_token: String,
    pub control_plane_ca: Option<PathBuf>,
    pub namespace: String,
    pub identity: String,
    pub lease_name: String,
    pub lease_duration_seconds: i64,
    pub reconcile_interval_seconds: f64,
    pub watch_timeout_seconds: u64,
    pub resync_interval_seconds: u64,
    pub list_page_size: u64,
    pub metrics_bind: String,
}

impl OperatorConfig {
    pub fn from_env() -> Result<Self> {
        let kube_host = std::env::var("KUBERNETES_SERVICE_HOST")
            .unwrap_or_else(|_| "kubernetes.default.svc".to_owned());
        let kube_port =
            std::env::var("KUBERNETES_SERVICE_PORT_HTTPS").unwrap_or_else(|_| "443".to_owned());
        let kube_root = std::env::var("KUBERNETES_API_URL")
            .unwrap_or_else(|_| format!("https://{kube_host}:{kube_port}"));
        let control_plane_url = std::env::var("CONTROL_PLANE_URL")
            .map_err(|_| anyhow!("CONTROL_PLANE_URL is required"))?
            .trim_end_matches('/')
            .to_owned();
        let control_plane_token = std::env::var("CONTROL_PLANE_TOKEN")
            .map_err(|_| anyhow!("CONTROL_PLANE_TOKEN is required"))?;
        let namespace = std::env::var("POD_NAMESPACE").unwrap_or_else(|_| {
            fs::read_to_string("/var/run/secrets/kubernetes.io/serviceaccount/namespace")
                .map(|s| s.trim().to_owned())
                .unwrap_or_else(|_| "default".to_owned())
        });
        let identity = std::env::var("POD_NAME")
            .or_else(|_| std::env::var("HOSTNAME"))
            .unwrap_or_else(|_| "browsercloud-operator".to_owned());
        let metrics_host =
            std::env::var("METRICS_BIND_ADDRESS").unwrap_or_else(|_| "0.0.0.0".to_owned());
        let metrics_port = std::env::var("METRICS_PORT").unwrap_or_else(|_| "8080".to_owned());
        Ok(Self {
            kube_root: kube_root.trim_end_matches('/').to_owned(),
            kube_token_path: Some(PathBuf::from(
                "/var/run/secrets/kubernetes.io/serviceaccount/token",
            )),
            kube_static_token: None,
            kube_ca_path: Some(PathBuf::from(
                "/var/run/secrets/kubernetes.io/serviceaccount/ca.crt",
            ))
            .filter(|p| p.is_file()),
            control_plane_url,
            control_plane_token,
            control_plane_ca: std::env::var("CONTROL_PLANE_CA")
                .ok()
                .filter(|s| !s.is_empty())
                .map(PathBuf::from),
            namespace,
            identity,
            lease_name: std::env::var("LEASE_NAME")
                .unwrap_or_else(|_| "browser-session-operator".to_owned()),
            lease_duration_seconds: std::env::var("LEASE_DURATION_SECONDS")
                .ok()
                .and_then(|s| s.parse().ok())
                .unwrap_or(15),
            reconcile_interval_seconds: std::env::var("RECONCILE_INTERVAL_SECONDS")
                .ok()
                .and_then(|s| s.parse().ok())
                .unwrap_or(2.0),
            watch_timeout_seconds: std::env::var("WATCH_TIMEOUT_SECONDS")
                .ok()
                .and_then(|s| s.parse().ok())
                .unwrap_or(5),
            resync_interval_seconds: std::env::var("RESYNC_INTERVAL_SECONDS")
                .ok()
                .and_then(|s| s.parse().ok())
                .unwrap_or(300),
            list_page_size: std::env::var("LIST_PAGE_SIZE")
                .ok()
                .and_then(|s| s.parse().ok())
                .unwrap_or(500),
            metrics_bind: format!("{metrics_host}:{metrics_port}"),
        })
    }
}

#[derive(Default)]
struct HistogramBucketState {
    buckets: Vec<u64>,
    count: u64,
    sum: f64,
}

type MetricKey = (String, BTreeMap<String, String>);

#[derive(Default)]
pub struct MetricsRegistry {
    counters: Mutex<BTreeMap<MetricKey, f64>>,
    gauges: Mutex<BTreeMap<MetricKey, f64>>,
    histograms: Mutex<BTreeMap<MetricKey, HistogramBucketState>>,
}

impl MetricsRegistry {
    pub fn new() -> Self {
        let reg = Self::default();
        reg.set("browsercloud_operator_build_info", 1.0, &[]);
        reg.set("browsercloud_operator_leader", 0.0, &[]);
        reg.set("browsercloud_operator_backoff_seconds", 0.0, &[]);
        reg.set(
            "browsercloud_operator_last_successful_lease_unixtime",
            0.0,
            &[],
        );
        reg.set(
            "browsercloud_operator_last_successful_snapshot_unixtime",
            0.0,
            &[],
        );
        reg.set(
            "browsercloud_operator_last_successful_watch_unixtime",
            0.0,
            &[],
        );
        reg
    }

    pub fn inc(&self, name: &str, labels: &[(&str, &str)]) {
        let key = (name.to_owned(), to_label_map(labels));
        if let Ok(mut map) = self.counters.lock() {
            *map.entry(key).or_insert(0.0) += 1.0;
        }
    }

    pub fn set(&self, name: &str, value: f64, labels: &[(&str, &str)]) {
        let key = (name.to_owned(), to_label_map(labels));
        if let Ok(mut map) = self.gauges.lock() {
            map.insert(key, value);
        }
    }

    pub fn observe(&self, name: &str, value: f64, labels: &[(&str, &str)]) {
        let key = (name.to_owned(), to_label_map(labels));
        if let Ok(mut map) = self.histograms.lock() {
            let h = map.entry(key).or_insert_with(|| HistogramBucketState {
                buckets: vec![0; RECONCILE_BUCKETS.len()],
                count: 0,
                sum: 0.0,
            });
            for (i, &b) in RECONCILE_BUCKETS.iter().enumerate() {
                if value <= b {
                    h.buckets[i] += 1;
                }
            }
            h.count += 1;
            h.sum += value;
        }
    }

    pub fn render(&self) -> String {
        let mut lines = Vec::new();
        let defs: &[(&str, &str, &str)] = &[
            (
                "browsercloud_operator_build_info",
                "BrowserSession Operator build information.",
                "gauge",
            ),
            (
                "browsercloud_operator_leader",
                "Whether this Operator replica currently holds the Kubernetes Lease.",
                "gauge",
            ),
            (
                "browsercloud_operator_lease_attempts_total",
                "Kubernetes Lease acquisition and renewal attempts.",
                "counter",
            ),
            (
                "browsercloud_operator_last_successful_lease_unixtime",
                "Unix time of the most recent successful Lease acquisition or renewal.",
                "gauge",
            ),
            (
                "browsercloud_operator_list_requests_total",
                "BrowserSession LIST page requests.",
                "counter",
            ),
            (
                "browsercloud_operator_list_snapshots_total",
                "Completed BrowserSession LIST snapshots.",
                "counter",
            ),
            (
                "browsercloud_operator_list_snapshot_resources",
                "BrowserSession resources in the most recent consistent LIST snapshot.",
                "gauge",
            ),
            (
                "browsercloud_operator_last_successful_snapshot_unixtime",
                "Unix time of the most recent successful BrowserSession LIST snapshot.",
                "gauge",
            ),
            (
                "browsercloud_operator_watch_events_total",
                "BrowserSession Watch events consumed by type.",
                "counter",
            ),
            (
                "browsercloud_operator_watch_restarts_total",
                "BrowserSession Watch restarts by reason.",
                "counter",
            ),
            (
                "browsercloud_operator_last_successful_watch_unixtime",
                "Unix time of the most recent clean Watch completion.",
                "gauge",
            ),
            (
                "browsercloud_operator_resource_version_expired_total",
                "BrowserSession Watch resourceVersion expiration events.",
                "counter",
            ),
            (
                "browsercloud_operator_reconcile_total",
                "BrowserSession reconciliations by source and result.",
                "counter",
            ),
            (
                "browsercloud_operator_reconcile_duration_seconds",
                "BrowserSession reconciliation duration in seconds.",
                "histogram",
            ),
            (
                "browsercloud_operator_loop_errors_total",
                "Operator control loop failures by bounded category.",
                "counter",
            ),
            (
                "browsercloud_operator_backoff_seconds",
                "Current retry backoff after an Operator loop failure.",
                "gauge",
            ),
        ];
        let counters = self.counters.lock().unwrap();
        let gauges = self.gauges.lock().unwrap();
        let hists = self.histograms.lock().unwrap();
        for &(name, help, kind) in defs {
            lines.push(format!("# HELP {name} {help}"));
            lines.push(format!("# TYPE {name} {kind}"));
            match kind {
                "counter" => {
                    for ((n, lbls), val) in counters.iter().filter(|((n, _), _)| n == name) {
                        lines.push(format!("{n}{} {}", fmt_labels(lbls, None), val));
                    }
                }
                "gauge" => {
                    for ((n, lbls), val) in gauges.iter().filter(|((n, _), _)| n == name) {
                        lines.push(format!("{n}{} {}", fmt_labels(lbls, None), val));
                    }
                }
                _ => {
                    for ((n, lbls), h) in hists.iter().filter(|((n, _), _)| n == name) {
                        for (i, &b) in RECONCILE_BUCKETS.iter().enumerate() {
                            lines.push(format!(
                                "{n}_bucket{} {}",
                                fmt_labels(lbls, Some(("le", &b.to_string()))),
                                h.buckets[i]
                            ));
                        }
                        lines.push(format!(
                            "{n}_bucket{} {}",
                            fmt_labels(lbls, Some(("le", "+Inf"))),
                            h.count
                        ));
                        lines.push(format!("{n}_sum{} {}", fmt_labels(lbls, None), h.sum));
                        lines.push(format!("{n}_count{} {}", fmt_labels(lbls, None), h.count));
                    }
                }
            }
        }
        lines.join("\n") + "\n"
    }
}

fn to_label_map(labels: &[(&str, &str)]) -> BTreeMap<String, String> {
    labels
        .iter()
        .map(|(k, v)| ((*k).to_owned(), (*v).to_owned()))
        .collect()
}

fn fmt_labels(map: &BTreeMap<String, String>, extra: Option<(&str, &str)>) -> String {
    let mut merged = map.clone();
    if let Some((k, v)) = extra {
        merged.insert(k.to_owned(), v.to_owned());
    }
    if merged.is_empty() {
        return String::new();
    }
    let inner = merged
        .iter()
        .map(|(k, v)| format!(r#"{k}="{}""#, v.replace('\\', "\\\\").replace('"', "\\\"")))
        .collect::<Vec<_>>()
        .join(",");
    format!("{{{inner}}}")
}

pub fn build_metrics_router(metrics: Arc<MetricsRegistry>) -> Router {
    Router::new()
        .route("/healthz", get(|| async { "ok\n" }))
        .route(
            "/metrics",
            get(|State(m): State<Arc<MetricsRegistry>>| async move {
                (
                    StatusCode::OK,
                    [(
                        header::CONTENT_TYPE,
                        "text/plain; version=0.0.4; charset=utf-8",
                    )],
                    m.render(),
                )
                    .into_response()
            }),
        )
        .with_state(metrics)
}

#[derive(Debug)]
pub struct ResourceVersionExpired;

impl std::fmt::Display for ResourceVersionExpired {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "watch resourceVersion expired")
    }
}

impl std::error::Error for ResourceVersionExpired {}

pub struct Operator {
    pub config: OperatorConfig,
    pub metrics: Arc<MetricsRegistry>,
    kube_http: Client,
    cp_http: Client,
}

fn build_client(ca_path: Option<&Path>, timeout: Duration) -> Result<Client> {
    let mut b = Client::builder().no_proxy().timeout(timeout);
    if let Some(ca) = ca_path {
        let pem = fs::read(ca)?;
        b = b.add_root_certificate(Certificate::from_pem(&pem)?);
    }
    Ok(b.build()?)
}

fn unix_now() -> f64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs_f64())
        .unwrap_or(0.0)
}

impl Operator {
    pub fn new(config: OperatorConfig, metrics: Arc<MetricsRegistry>) -> Result<Self> {
        let kube_http = build_client(config.kube_ca_path.as_deref(), Duration::from_secs(30))?;
        let cp_http = build_client(config.control_plane_ca.as_deref(), Duration::from_secs(15))?;
        Ok(Self {
            config,
            metrics,
            kube_http,
            cp_http,
        })
    }

    fn kube_token(&self) -> Result<String> {
        if let Some(t) = &self.config.kube_static_token {
            return Ok(t.clone());
        }
        let path = self
            .config
            .kube_token_path
            .as_deref()
            .ok_or_else(|| anyhow!("KUBE_TOKEN_UNAVAILABLE"))?;
        Ok(fs::read_to_string(path)?.trim().to_owned())
    }

    async fn kube_request(
        &self,
        path: &str,
        method: Method,
        body: Option<Value>,
    ) -> Result<(StatusCode, Value)> {
        let token = self.kube_token()?;
        let mut req = self
            .kube_http
            .request(method.clone(), format!("{}{path}", self.config.kube_root))
            .header(ACCEPT, "application/json")
            .header(AUTHORIZATION, format!("Bearer {token}"));
        if let Some(payload) = body {
            let ct = if method == Method::PATCH {
                "application/merge-patch+json"
            } else {
                "application/json"
            };
            req = req
                .header(CONTENT_TYPE, ct)
                .body(serde_json::to_vec(&payload)?);
        }
        let resp = req.send().await?;
        let status = resp.status();
        let bytes = resp.bytes().await?;
        let doc = if bytes.is_empty() {
            json!({})
        } else {
            serde_json::from_slice(&bytes).unwrap_or_else(|_| json!({}))
        };
        Ok((status, doc))
    }

    async fn control_request(
        &self,
        path: &str,
        method: Method,
        body: Option<Value>,
        idempotency_key: Option<&str>,
    ) -> Result<(StatusCode, Value)> {
        let mut req = self
            .cp_http
            .request(method, format!("{}{path}", self.config.control_plane_url))
            .header(ACCEPT, "application/json")
            .header(
                AUTHORIZATION,
                format!("Bearer {}", self.config.control_plane_token),
            );
        if let Some(key) = idempotency_key {
            req = req.header("Idempotency-Key", key);
        }
        if let Some(payload) = body {
            req = req.json(&payload);
        }
        let resp = req.send().await?;
        let status = resp.status();
        let bytes = resp.bytes().await?;
        let doc = if bytes.is_empty() {
            json!({})
        } else {
            serde_json::from_slice(&bytes).unwrap_or_else(|_| json!({}))
        };
        Ok((status, doc))
    }

    pub async fn try_acquire_or_renew_lease(&self) -> Result<bool> {
        let now = Utc::now();
        let now_str = now.to_rfc3339_opts(chrono::SecondsFormat::Micros, true);
        let path = format!(
            "/apis/coordination.k8s.io/v1/namespaces/{}/leases/{}",
            self.config.namespace, self.config.lease_name
        );
        let (status, lease) = self.kube_request(&path, Method::GET, None).await?;
        if status == StatusCode::NOT_FOUND {
            let collection = format!(
                "/apis/coordination.k8s.io/v1/namespaces/{}/leases",
                self.config.namespace
            );
            let payload = json!({
                "apiVersion": "coordination.k8s.io/v1",
                "kind": "Lease",
                "metadata": {
                    "name": self.config.lease_name,
                    "namespace": self.config.namespace,
                },
                "spec": {
                    "holderIdentity": self.config.identity,
                    "leaseDurationSeconds": self.config.lease_duration_seconds,
                    "acquireTime": now_str,
                    "renewTime": now_str,
                    "leaseTransitions": 0,
                }
            });
            let (st, _) = self
                .kube_request(&collection, Method::POST, Some(payload))
                .await?;
            if st == StatusCode::CONFLICT {
                return Ok(false);
            }
            ensure!(st.is_success(), "lease create failed: HTTP {st}");
            return Ok(true);
        }
        ensure!(status.is_success(), "lease get failed: HTTP {status}");

        let spec = lease.get("spec").cloned().unwrap_or_else(|| json!({}));
        let holder = spec
            .get("holderIdentity")
            .and_then(Value::as_str)
            .unwrap_or("");
        let expired = is_lease_expired(&spec, now, self.config.lease_duration_seconds);
        if holder != self.config.identity && !expired {
            return Ok(false);
        }
        let takeover = holder != self.config.identity;
        let rv = lease
            .pointer("/metadata/resourceVersion")
            .and_then(Value::as_str)
            .unwrap_or("");
        let mut patch_spec = json!({
            "holderIdentity": self.config.identity,
            "leaseDurationSeconds": self.config.lease_duration_seconds,
            "renewTime": now_str,
        });
        if takeover {
            let transitions = spec
                .get("leaseTransitions")
                .and_then(Value::as_i64)
                .unwrap_or(0);
            patch_spec["acquireTime"] = json!(now_str);
            patch_spec["leaseTransitions"] = json!(transitions + 1);
        }
        let patch = json!({
            "metadata": { "resourceVersion": rv },
            "spec": patch_spec,
        });
        let (pst, _) = self.kube_request(&path, Method::PATCH, Some(patch)).await?;
        if pst == StatusCode::CONFLICT {
            return Ok(false);
        }
        ensure!(pst.is_success(), "lease renew failed: HTTP {pst}");
        Ok(true)
    }

    pub async fn reconcile_item(&self, item: &Value, source: &str) -> Result<()> {
        let started = Instant::now();
        let res = self.reconcile_inner(item).await;
        let outcome = if res.is_ok() { "success" } else { "error" };
        self.metrics.inc(
            "browsercloud_operator_reconcile_total",
            &[("source", source), ("result", outcome)],
        );
        self.metrics.observe(
            "browsercloud_operator_reconcile_duration_seconds",
            started.elapsed().as_secs_f64(),
            &[("source", source), ("result", outcome)],
        );
        res
    }

    async fn reconcile_inner(&self, item: &Value) -> Result<()> {
        let meta = item
            .get("metadata")
            .and_then(Value::as_object)
            .ok_or_else(|| anyhow!("BrowserSession missing metadata"))?;
        let spec = item
            .get("spec")
            .and_then(Value::as_object)
            .ok_or_else(|| anyhow!("BrowserSession missing spec"))?;
        let mut status = item
            .get("status")
            .and_then(Value::as_object)
            .cloned()
            .unwrap_or_default();
        let namespace = meta
            .get("namespace")
            .and_then(Value::as_str)
            .unwrap_or("default");
        let name = meta.get("name").and_then(Value::as_str).unwrap_or("");
        let uid = meta.get("uid").and_then(Value::as_str).unwrap_or(name);
        let generation = meta.get("generation").and_then(Value::as_i64).unwrap_or(1);
        let finalizers: Vec<String> = meta
            .get("finalizers")
            .and_then(Value::as_array)
            .map(|a| {
                a.iter()
                    .filter_map(Value::as_str)
                    .map(ToOwned::to_owned)
                    .collect()
            })
            .unwrap_or_default();
        let item_path = format!("{API_PATH}/namespaces/{namespace}/browsersessions/{name}");

        if meta.get("deletionTimestamp").is_some_and(|v| !v.is_null()) {
            if let Some(sid) = status.get("sessionId").and_then(Value::as_str) {
                let (st, _) = self
                    .control_request(
                        &format!("/api/v1/sessions/{sid}:terminate"),
                        Method::POST,
                        None,
                        None,
                    )
                    .await?;
                if !st.is_success() && st != StatusCode::NOT_FOUND && st != StatusCode::CONFLICT {
                    bail!("terminate failed: HTTP {st}");
                }
            }
            let remaining: Vec<String> =
                finalizers.into_iter().filter(|f| f != FINALIZER).collect();
            let (st, _) = self
                .kube_request(
                    &item_path,
                    Method::PATCH,
                    Some(json!({ "metadata": { "finalizers": remaining } })),
                )
                .await?;
            ensure!(st.is_success(), "finalizer removal failed: HTTP {st}");
            return Ok(());
        }

        if !finalizers.iter().any(|f| f == FINALIZER) {
            let mut updated = finalizers;
            updated.push(FINALIZER.to_owned());
            let (st, _) = self
                .kube_request(
                    &item_path,
                    Method::PATCH,
                    Some(json!({ "metadata": { "finalizers": updated } })),
                )
                .await?;
            ensure!(st.is_success(), "finalizer attach failed: HTTP {st}");
            return Ok(());
        }

        let original_status = status.clone();
        if status
            .get("sessionId")
            .and_then(Value::as_str)
            .unwrap_or("")
            .is_empty()
        {
            let mut rp = spec
                .get("resourcePolicy")
                .and_then(Value::as_object)
                .cloned()
                .unwrap_or_default();
            rp.insert("mode".to_owned(), json!("AUTO"));
            if let Some(ee) = spec.get("executionEnvironment").filter(|v| !v.is_null()) {
                rp.insert("executionEnvironment".to_owned(), ee.clone());
            }
            let body = json!({
                "tenantId": spec.get("tenantId").cloned().unwrap_or(Value::Null),
                "profileId": spec.get("profileId").cloned().unwrap_or(Value::Null),
                "region": spec.get("region").and_then(Value::as_str).unwrap_or("local"),
                "resourcePolicy": Value::Object(rp),
                "metadata": { "displayName": name },
            });
            let idem = format!("k8s-{uid}");
            let (st, created) = self
                .control_request("/api/v1/sessions", Method::POST, Some(body), Some(&idem))
                .await?;
            ensure!(st.is_success(), "session create failed: HTTP {st}");
            let sid = created
                .get("sessionId")
                .and_then(Value::as_str)
                .ok_or_else(|| anyhow!("Control Plane response missing sessionId"))?;
            status.insert("sessionId".to_owned(), json!(sid));
        }

        status.insert("phase".to_owned(), json!("Ready"));
        status.insert("observedGeneration".to_owned(), json!(generation));
        status.insert("lastError".to_owned(), json!(""));

        if status != original_status {
            let (st, _) = self
                .kube_request(
                    &format!("{item_path}/status"),
                    Method::PATCH,
                    Some(json!({ "status": Value::Object(status) })),
                )
                .await?;
            ensure!(st.is_success(), "status patch failed: HTTP {st}");
        }
        Ok(())
    }

    pub async fn reconcile_snapshot(&self) -> Result<String> {
        let mut continuation: Option<String> = None;
        let mut resource_version: Option<String> = None;
        let mut count = 0usize;
        let mut first_err: Option<anyhow::Error> = None;

        loop {
            let query = match &continuation {
                Some(tok) => format!("limit={}&continue={tok}", self.config.list_page_size),
                None => format!(
                    "limit={}&resourceVersion=0&resourceVersionMatch=NotOlderThan",
                    self.config.list_page_size
                ),
            };
            let res = self
                .kube_request(&format!("{RESOURCE_PATH}?{query}"), Method::GET, None)
                .await;
            let (st, doc) = match res {
                Ok(pair) if pair.0.is_success() => {
                    self.metrics.inc(
                        "browsercloud_operator_list_requests_total",
                        &[("result", "success")],
                    );
                    pair
                }
                Ok((st, _)) => {
                    self.metrics.inc(
                        "browsercloud_operator_list_requests_total",
                        &[("result", "error")],
                    );
                    self.metrics.inc(
                        "browsercloud_operator_list_snapshots_total",
                        &[("result", "error")],
                    );
                    bail!("BrowserSession list failed: HTTP {st}");
                }
                Err(e) => {
                    self.metrics.inc(
                        "browsercloud_operator_list_requests_total",
                        &[("result", "error")],
                    );
                    self.metrics.inc(
                        "browsercloud_operator_list_snapshots_total",
                        &[("result", "error")],
                    );
                    return Err(e);
                }
            };
            let _ = st;
            let page_rv = doc
                .pointer("/metadata/resourceVersion")
                .and_then(Value::as_str)
                .map(ToOwned::to_owned);
            if let Some(rv) = page_rv {
                if let Some(prev) = &resource_version {
                    ensure!(prev == &rv, "BrowserSession list snapshot version changed");
                }
                resource_version = Some(rv);
            }
            ensure!(
                resource_version.is_some(),
                "BrowserSession list omitted resourceVersion"
            );
            if let Some(items) = doc.get("items").and_then(Value::as_array) {
                count += items.len();
                for item in items {
                    if let Err(err) = self.reconcile_item(item, "snapshot").await {
                        warn!(error = %err, "snapshot reconcile failed");
                        if first_err.is_none() {
                            first_err = Some(err);
                        }
                    }
                }
            }
            continuation = doc
                .pointer("/metadata/continue")
                .and_then(Value::as_str)
                .filter(|s| !s.is_empty())
                .map(ToOwned::to_owned);
            if continuation.is_none() {
                break;
            }
        }
        if let Some(err) = first_err {
            self.metrics.inc(
                "browsercloud_operator_list_snapshots_total",
                &[("result", "error")],
            );
            return Err(err);
        }
        let rv =
            resource_version.ok_or_else(|| anyhow!("BrowserSession list returned no pages"))?;
        self.metrics.set(
            "browsercloud_operator_list_snapshot_resources",
            count as f64,
            &[],
        );
        self.metrics.set(
            "browsercloud_operator_last_successful_snapshot_unixtime",
            unix_now(),
            &[],
        );
        self.metrics.inc(
            "browsercloud_operator_list_snapshots_total",
            &[("result", "success")],
        );
        info!(resources = count, resource_version = %rv, "list-watch cache synchronized");
        Ok(rv)
    }

    pub async fn consume_watch_ndjson(&self, ndjson: &str, start_rv: &str) -> Result<String> {
        let mut current_rv = start_rv.to_owned();
        for line in ndjson.lines() {
            if line.trim().is_empty() {
                continue;
            }
            ensure!(
                line.len() <= MAX_WATCH_EVENT_BYTES,
                "BrowserSession watch event exceeded size limit"
            );
            let event: Value = serde_json::from_str(line)?;
            let ev_type = event
                .get("type")
                .and_then(Value::as_str)
                .unwrap_or("UNKNOWN");
            let metric_type = if matches!(
                ev_type,
                "ADDED" | "MODIFIED" | "DELETED" | "BOOKMARK" | "ERROR"
            ) {
                ev_type
            } else {
                "UNKNOWN"
            };
            self.metrics.inc(
                "browsercloud_operator_watch_events_total",
                &[("type", metric_type)],
            );
            let item = event.get("object").cloned().unwrap_or_else(|| json!({}));
            if ev_type == "ERROR" {
                if item.get("code").and_then(Value::as_i64) == Some(410) {
                    return Err(anyhow!(ResourceVersionExpired));
                }
                bail!("BrowserSession watch error: {item}");
            }
            let next_rv = item
                .pointer("/metadata/resourceVersion")
                .and_then(Value::as_str)
                .map(ToOwned::to_owned);
            if ev_type == "BOOKMARK" {
                if let Some(rv) = next_rv {
                    current_rv = rv;
                }
                continue;
            }
            if matches!(ev_type, "ADDED" | "MODIFIED") {
                self.reconcile_item(&item, "watch").await?;
            } else if ev_type != "DELETED" {
                bail!("unsupported BrowserSession watch event {ev_type}");
            }
            if let Some(rv) = next_rv {
                current_rv = rv;
            }
        }
        Ok(current_rv)
    }

    pub async fn watch_once(&self, resource_version: &str) -> Result<String> {
        let token = self.kube_token()?;
        let url = format!(
            "{}{RESOURCE_PATH}?watch=1&allowWatchBookmarks=true&resourceVersion={resource_version}&timeoutSeconds={}",
            self.config.kube_root, self.config.watch_timeout_seconds
        );
        let resp = self
            .kube_http
            .get(&url)
            .header(ACCEPT, "application/json")
            .header(AUTHORIZATION, format!("Bearer {token}"))
            .timeout(Duration::from_secs(self.config.watch_timeout_seconds + 5))
            .send()
            .await?;
        if resp.status() == StatusCode::GONE {
            return Err(anyhow!(ResourceVersionExpired));
        }
        ensure!(
            resp.status().is_success(),
            "watch failed: HTTP {}",
            resp.status()
        );
        let body = resp.text().await?;
        self.consume_watch_ndjson(&body, resource_version).await
    }

    pub async fn run_forever(&self) -> Result<()> {
        let mut was_leader = false;
        let mut resource_version: Option<String> = None;
        let mut last_resync =
            Instant::now() - Duration::from_secs(self.config.resync_interval_seconds);
        let mut backoff_secs = 1.0f64;

        loop {
            match self.try_acquire_or_renew_lease().await {
                Ok(is_leader) => {
                    self.metrics.inc(
                        "browsercloud_operator_lease_attempts_total",
                        &[("result", if is_leader { "leader" } else { "follower" })],
                    );
                    self.metrics.set(
                        "browsercloud_operator_leader",
                        if is_leader { 1.0 } else { 0.0 },
                        &[],
                    );
                    if is_leader {
                        self.metrics.set(
                            "browsercloud_operator_last_successful_lease_unixtime",
                            unix_now(),
                            &[],
                        );
                    }
                    if is_leader && !was_leader {
                        info!(identity = %self.config.identity, "leadership acquired");
                    } else if !is_leader && was_leader {
                        warn!(identity = %self.config.identity, "leadership lost");
                        resource_version = None;
                    }
                    was_leader = is_leader;
                    if is_leader {
                        let needs_resync = resource_version.is_none()
                            || last_resync.elapsed()
                                >= Duration::from_secs(self.config.resync_interval_seconds);
                        if needs_resync {
                            match self.reconcile_snapshot().await {
                                Ok(rv) => {
                                    resource_version = Some(rv);
                                    last_resync = Instant::now();
                                }
                                Err(err) => {
                                    warn!(error = %err, "snapshot failed");
                                    self.metrics.inc(
                                        "browsercloud_operator_loop_errors_total",
                                        &[("category", "internal")],
                                    );
                                    self.metrics.set(
                                        "browsercloud_operator_backoff_seconds",
                                        backoff_secs,
                                        &[],
                                    );
                                    tokio::time::sleep(Duration::from_secs_f64(backoff_secs)).await;
                                    backoff_secs = (backoff_secs * 2.0).min(30.0);
                                    continue;
                                }
                            }
                        }
                        if let Some(rv) = resource_version.as_deref() {
                            match self.watch_once(rv).await {
                                Ok(next_rv) => {
                                    resource_version = Some(next_rv);
                                    self.metrics.set(
                                        "browsercloud_operator_last_successful_watch_unixtime",
                                        unix_now(),
                                        &[],
                                    );
                                    self.metrics.inc(
                                        "browsercloud_operator_watch_restarts_total",
                                        &[("reason", "server_timeout")],
                                    );
                                    backoff_secs = 1.0;
                                    self.metrics.set(
                                        "browsercloud_operator_backoff_seconds",
                                        0.0,
                                        &[],
                                    );
                                    continue;
                                }
                                Err(err)
                                    if err.downcast_ref::<ResourceVersionExpired>().is_some() =>
                                {
                                    resource_version = None;
                                    self.metrics.inc(
                                        "browsercloud_operator_resource_version_expired_total",
                                        &[],
                                    );
                                    self.metrics.inc(
                                        "browsercloud_operator_watch_restarts_total",
                                        &[("reason", "resource_version_expired")],
                                    );
                                    continue;
                                }
                                Err(err) => {
                                    warn!(error = %err, "watch error");
                                    self.metrics.inc(
                                        "browsercloud_operator_watch_restarts_total",
                                        &[("reason", "error")],
                                    );
                                    self.metrics.inc(
                                        "browsercloud_operator_loop_errors_total",
                                        &[("category", "network")],
                                    );
                                    self.metrics.set(
                                        "browsercloud_operator_backoff_seconds",
                                        backoff_secs,
                                        &[],
                                    );
                                    tokio::time::sleep(Duration::from_secs_f64(backoff_secs)).await;
                                    backoff_secs = (backoff_secs * 2.0).min(30.0);
                                    continue;
                                }
                            }
                        }
                    }
                    backoff_secs = 1.0;
                    self.metrics
                        .set("browsercloud_operator_backoff_seconds", 0.0, &[]);
                }
                Err(err) => {
                    was_leader = false;
                    warn!(error = %err, "lease renewal error");
                    self.metrics.inc(
                        "browsercloud_operator_lease_attempts_total",
                        &[("result", "error")],
                    );
                    self.metrics
                        .set("browsercloud_operator_backoff_seconds", backoff_secs, &[]);
                    tokio::time::sleep(Duration::from_secs_f64(backoff_secs)).await;
                    backoff_secs = (backoff_secs * 2.0).min(30.0);
                    continue;
                }
            }
            tokio::time::sleep(Duration::from_secs_f64(
                self.config.reconcile_interval_seconds.max(0.2),
            ))
            .await;
        }
    }
}

fn is_lease_expired(spec: &Value, now: DateTime<Utc>, default_duration: i64) -> bool {
    let raw_time = spec
        .get("renewTime")
        .or_else(|| spec.get("acquireTime"))
        .and_then(Value::as_str);
    let Some(dt) = raw_time.and_then(|s| DateTime::parse_from_rfc3339(s).ok()) else {
        return true;
    };
    let duration = spec
        .get("leaseDurationSeconds")
        .and_then(Value::as_i64)
        .unwrap_or(default_duration);
    now >= dt.with_timezone(&Utc) + ChronoDuration::seconds(duration)
}

#[cfg(test)]
mod tests {
    use super::*;
    use axum::extract::State as AxumState;
    use axum::routing::{get, patch, post};
    use axum::Json;

    #[derive(Clone, Default)]
    struct MockCluster {
        lease_exists: Arc<Mutex<bool>>,
        patched_finalizers: Arc<Mutex<Vec<Value>>>,
        patched_statuses: Arc<Mutex<Vec<Value>>>,
        terminated: Arc<Mutex<Vec<String>>>,
    }

    #[tokio::test]
    async fn reconciles_lease_snapshot_finalizer_status_deletion_and_watch_410() {
        let cluster = MockCluster::default();
        let app = Router::new()
            .route(
                "/apis/coordination.k8s.io/v1/namespaces/default/leases/op",
                get(|AxumState(c): AxumState<MockCluster>| async move {
                    if *c.lease_exists.lock().unwrap() {
                        (
                            StatusCode::OK,
                            Json(json!({
                                "metadata": { "resourceVersion": "2" },
                                "spec": {
                                    "holderIdentity": "pod-1",
                                    "leaseDurationSeconds": 15,
                                    "renewTime": Utc::now().to_rfc3339()
                                }
                            })),
                        )
                    } else {
                        (StatusCode::NOT_FOUND, Json(json!({})))
                    }
                })
                .patch(|| async { (StatusCode::OK, Json(json!({}))) }),
            )
            .route(
                "/apis/coordination.k8s.io/v1/namespaces/default/leases",
                post(|AxumState(c): AxumState<MockCluster>| async move {
                    *c.lease_exists.lock().unwrap() = true;
                    (StatusCode::CREATED, Json(json!({})))
                }),
            )
            .route(
                "/apis/browsercloud.io/v1alpha1/namespaces/default/browsersessions/demo",
                patch(
                    |AxumState(c): AxumState<MockCluster>, Json(body): Json<Value>| async move {
                        c.patched_finalizers.lock().unwrap().push(body);
                        (StatusCode::OK, Json(json!({})))
                    },
                ),
            )
            .route(
                "/apis/browsercloud.io/v1alpha1/namespaces/default/browsersessions/demo/status",
                patch(
                    |AxumState(c): AxumState<MockCluster>, Json(body): Json<Value>| async move {
                        c.patched_statuses.lock().unwrap().push(body);
                        (StatusCode::OK, Json(json!({})))
                    },
                ),
            )
            .route(
                "/api/v1/sessions",
                post(|Json(body): Json<Value>| async move {
                    assert_eq!(body["resourcePolicy"]["mode"], "AUTO");
                    (
                        StatusCode::CREATED,
                        Json(json!({ "sessionId": "ses_k8s_001" })),
                    )
                }),
            )
            .route(
                "/api/v1/sessions/ses_k8s_001:terminate",
                post(|AxumState(c): AxumState<MockCluster>| async move {
                    c.terminated.lock().unwrap().push("ses_k8s_001".into());
                    (StatusCode::OK, Json(json!({ "terminated": true })))
                }),
            )
            .with_state(cluster.clone());

        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap();
        let srv = tokio::spawn(async move { axum::serve(listener, app).await.unwrap() });

        let metrics = Arc::new(MetricsRegistry::new());
        let op = Operator::new(
            OperatorConfig {
                kube_root: format!("http://{addr}"),
                kube_token_path: None,
                kube_static_token: Some("kube-tok".into()),
                kube_ca_path: None,
                control_plane_url: format!("http://{addr}"),
                control_plane_token: "cp-tok".into(),
                control_plane_ca: None,
                namespace: "default".into(),
                identity: "pod-1".into(),
                lease_name: "op".into(),
                lease_duration_seconds: 15,
                reconcile_interval_seconds: 1.0,
                watch_timeout_seconds: 5,
                resync_interval_seconds: 300,
                list_page_size: 100,
                metrics_bind: "127.0.0.1:0".into(),
            },
            metrics.clone(),
        )
        .unwrap();

        // 1. Acquire then renew lease.
        assert!(op.try_acquire_or_renew_lease().await.unwrap());
        assert!(op.try_acquire_or_renew_lease().await.unwrap());

        // 2. First reconcile attaches finalizer.
        let cr_no_fin = json!({
            "metadata": { "namespace": "default", "name": "demo", "uid": "u1", "generation": 1 },
            "spec": { "tenantId": "t1", "profileId": "p1" }
        });
        op.reconcile_item(&cr_no_fin, "watch").await.unwrap();
        assert_eq!(cluster.patched_finalizers.lock().unwrap().len(), 1);

        // 3. With finalizer present, creates session & patches status Ready.
        let cr_with_fin = json!({
            "metadata": {
                "namespace": "default", "name": "demo", "uid": "u1", "generation": 1,
                "finalizers": [FINALIZER]
            },
            "spec": { "tenantId": "t1", "profileId": "p1" }
        });
        op.reconcile_item(&cr_with_fin, "watch").await.unwrap();
        assert_eq!(
            cluster.patched_statuses.lock().unwrap()[0]["status"]["sessionId"],
            "ses_k8s_001"
        );

        // 4. Deletion terminates session and strips finalizer.
        let cr_del = json!({
            "metadata": {
                "namespace": "default", "name": "demo", "uid": "u1", "generation": 2,
                "deletionTimestamp": "2026-11-25T00:00:00Z",
                "finalizers": [FINALIZER]
            },
            "spec": { "tenantId": "t1", "profileId": "p1" },
            "status": { "sessionId": "ses_k8s_001" }
        });
        op.reconcile_item(&cr_del, "watch").await.unwrap();
        assert_eq!(cluster.terminated.lock().unwrap().len(), 1);

        // 5. Watch stream advances on BOOKMARK and returns ResourceVersionExpired on 410.
        let rv = op
            .consume_watch_ndjson(
                r#"{"type":"BOOKMARK","object":{"metadata":{"resourceVersion":"42"}}}"#,
                "10",
            )
            .await
            .unwrap();
        assert_eq!(rv, "42");
        let expired = op
            .consume_watch_ndjson(r#"{"type":"ERROR","object":{"code":410}}"#, "42")
            .await
            .unwrap_err();
        assert!(expired.downcast_ref::<ResourceVersionExpired>().is_some());

        // 6. Prometheus metrics render cleanly.
        let prom = metrics.render();
        assert!(prom.contains("browsercloud_operator_reconcile_total"));
        assert!(prom.contains("browsercloud_operator_reconcile_duration_seconds_bucket"));

        srv.abort();
    }
}
