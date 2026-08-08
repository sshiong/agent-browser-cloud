#!/usr/bin/env python3
"""BrowserSession reconciler with leader election and safe finalization."""

import datetime
import json
import os
import socket
import ssl
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, HTTPServer

FINALIZER = "browsercloud.io/session-cleanup"
KUBE_HOST = os.environ.get("KUBERNETES_SERVICE_HOST", "kubernetes.default.svc")
KUBE_PORT = os.environ.get("KUBERNETES_SERVICE_PORT_HTTPS", "443")
KUBE_ROOT = f"https://{KUBE_HOST}:{KUBE_PORT}"
API_PATH = "/apis/browsercloud.io/v1alpha1"
RESOURCE_PATH = f"{API_PATH}/browsersessions"
TOKEN_PATH = "/var/run/secrets/kubernetes.io/serviceaccount/token"
CA_PATH = "/var/run/secrets/kubernetes.io/serviceaccount/ca.crt"
CONTROL_PLANE = os.environ["CONTROL_PLANE_URL"].rstrip("/")
CONTROL_TOKEN = os.environ["CONTROL_PLANE_TOKEN"]
CONTROL_CA = os.environ.get("CONTROL_PLANE_CA")
LEASE_NAME = os.environ.get("LEASE_NAME", "browser-session-operator")
LEASE_DURATION_SECONDS = int(os.environ.get("LEASE_DURATION_SECONDS", "15"))
RECONCILE_INTERVAL_SECONDS = float(os.environ.get("RECONCILE_INTERVAL_SECONDS", "2"))
WATCH_TIMEOUT_SECONDS = int(os.environ.get("WATCH_TIMEOUT_SECONDS", "5"))
RESYNC_INTERVAL_SECONDS = int(os.environ.get("RESYNC_INTERVAL_SECONDS", "300"))
LIST_PAGE_SIZE = int(os.environ.get("LIST_PAGE_SIZE", "500"))
MAX_WATCH_EVENT_BYTES = 1024 * 1024
METRICS_BIND_ADDRESS = os.environ.get("METRICS_BIND_ADDRESS", "0.0.0.0")
METRICS_PORT = int(os.environ.get("METRICS_PORT", "8080"))
NAMESPACE_PATH = "/var/run/secrets/kubernetes.io/serviceaccount/namespace"


METRIC_DEFINITIONS = {
    "browsercloud_operator_build_info": (
        "BrowserSession Operator build information.",
        "gauge",
    ),
    "browsercloud_operator_leader": (
        "Whether this Operator replica currently holds the Kubernetes Lease.",
        "gauge",
    ),
    "browsercloud_operator_lease_attempts_total": (
        "Kubernetes Lease acquisition and renewal attempts.",
        "counter",
    ),
    "browsercloud_operator_last_successful_lease_unixtime": (
        "Unix time of the most recent successful Lease acquisition or renewal.",
        "gauge",
    ),
    "browsercloud_operator_list_requests_total": (
        "BrowserSession LIST page requests.",
        "counter",
    ),
    "browsercloud_operator_list_snapshots_total": (
        "Completed BrowserSession LIST snapshots.",
        "counter",
    ),
    "browsercloud_operator_list_snapshot_resources": (
        "BrowserSession resources in the most recent consistent LIST snapshot.",
        "gauge",
    ),
    "browsercloud_operator_last_successful_snapshot_unixtime": (
        "Unix time of the most recent successful BrowserSession LIST snapshot.",
        "gauge",
    ),
    "browsercloud_operator_watch_events_total": (
        "BrowserSession Watch events consumed by type.",
        "counter",
    ),
    "browsercloud_operator_watch_restarts_total": (
        "BrowserSession Watch restarts by reason.",
        "counter",
    ),
    "browsercloud_operator_last_successful_watch_unixtime": (
        "Unix time of the most recent clean Watch completion.",
        "gauge",
    ),
    "browsercloud_operator_resource_version_expired_total": (
        "BrowserSession Watch resourceVersion expiration events.",
        "counter",
    ),
    "browsercloud_operator_reconcile_total": (
        "BrowserSession reconciliations by source and result.",
        "counter",
    ),
    "browsercloud_operator_reconcile_duration_seconds": (
        "BrowserSession reconciliation duration in seconds.",
        "histogram",
    ),
    "browsercloud_operator_loop_errors_total": (
        "Operator control loop failures by bounded category.",
        "counter",
    ),
    "browsercloud_operator_backoff_seconds": (
        "Current retry backoff after an Operator loop failure.",
        "gauge",
    ),
}
RECONCILE_DURATION_BUCKETS = (0.01, 0.05, 0.1, 0.25, 0.5, 1.0, 2.5, 5.0)


def _escape_metric_label(value):
    return str(value).replace("\\", "\\\\").replace("\n", "\\n").replace('"', '\\"')


def _format_metric_labels(labels, extra=None):
    values = dict(labels)
    if extra:
        values.update(extra)
    if not values:
        return ""
    rendered = ",".join(
        f'{key}="{_escape_metric_label(value)}"' for key, value in sorted(values.items())
    )
    return "{" + rendered + "}"


def _format_metric_value(value):
    return str(int(value)) if float(value).is_integer() else format(value, ".12g")


class MetricsRegistry:
    """Small bounded Prometheus registry without runtime package downloads."""

    def __init__(self):
        self._lock = threading.Lock()
        self._counters = {}
        self._gauges = {}
        self._histograms = {}

    @staticmethod
    def _key(name, labels, expected_type):
        if name not in METRIC_DEFINITIONS:
            raise KeyError(f"unknown metric {name}")
        if METRIC_DEFINITIONS[name][1] != expected_type:
            raise TypeError(f"metric {name} is not a {expected_type}")
        return name, tuple(sorted((labels or {}).items()))

    def increment(self, name, labels=None, amount=1.0):
        key = self._key(name, labels, "counter")
        with self._lock:
            self._counters[key] = self._counters.get(key, 0.0) + amount

    def set(self, name, value, labels=None):
        key = self._key(name, labels, "gauge")
        with self._lock:
            self._gauges[key] = float(value)

    def observe(self, name, value, labels=None):
        key = self._key(name, labels, "histogram")
        value = float(value)
        with self._lock:
            histogram = self._histograms.setdefault(
                key,
                {
                    "buckets": [0] * len(RECONCILE_DURATION_BUCKETS),
                    "count": 0,
                    "sum": 0.0,
                },
            )
            for index, bucket in enumerate(RECONCILE_DURATION_BUCKETS):
                if value <= bucket:
                    histogram["buckets"][index] += 1
            histogram["count"] += 1
            histogram["sum"] += value

    def render(self):
        with self._lock:
            counters = dict(self._counters)
            gauges = dict(self._gauges)
            histograms = {
                key: {
                    "buckets": list(value["buckets"]),
                    "count": value["count"],
                    "sum": value["sum"],
                }
                for key, value in self._histograms.items()
            }
        lines = []
        for name, (help_text, metric_type) in METRIC_DEFINITIONS.items():
            lines.extend((f"# HELP {name} {help_text}", f"# TYPE {name} {metric_type}"))
            if metric_type == "counter":
                samples = counters
            elif metric_type == "gauge":
                samples = gauges
            else:
                samples = histograms
            for (sample_name, raw_labels), value in sorted(samples.items()):
                if sample_name != name:
                    continue
                labels = dict(raw_labels)
                if metric_type != "histogram":
                    lines.append(
                        f"{name}{_format_metric_labels(labels)} "
                        f"{_format_metric_value(value)}"
                    )
                    continue
                for index, bucket in enumerate(RECONCILE_DURATION_BUCKETS):
                    lines.append(
                        f"{name}_bucket"
                        f"{_format_metric_labels(labels, {'le': bucket})} "
                        f"{value['buckets'][index]}"
                    )
                lines.append(
                    f"{name}_bucket"
                    f"{_format_metric_labels(labels, {'le': '+Inf'})} {value['count']}"
                )
                lines.append(
                    f"{name}_sum{_format_metric_labels(labels)} "
                    f"{_format_metric_value(value['sum'])}"
                )
                lines.append(
                    f"{name}_count{_format_metric_labels(labels)} {value['count']}"
                )
        return "\n".join(lines) + "\n"


METRICS = MetricsRegistry()
METRICS.set("browsercloud_operator_build_info", 1)
METRICS.set("browsercloud_operator_leader", 0)
METRICS.set("browsercloud_operator_backoff_seconds", 0)
METRICS.set("browsercloud_operator_last_successful_lease_unixtime", 0)
METRICS.set("browsercloud_operator_last_successful_snapshot_unixtime", 0)
METRICS.set("browsercloud_operator_last_successful_watch_unixtime", 0)


class MetricsHandler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path == "/healthz":
            payload = b"ok\n"
            content_type = "text/plain; charset=utf-8"
        elif self.path == "/metrics":
            payload = METRICS.render().encode()
            content_type = "text/plain; version=0.0.4; charset=utf-8"
        else:
            self.send_error(404)
            return
        self.send_response(200)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def log_message(self, _format, *_args):
        return


def start_metrics_server():
    server = HTTPServer((METRICS_BIND_ADDRESS, METRICS_PORT), MetricsHandler)
    thread = threading.Thread(
        target=server.serve_forever,
        name="operator-metrics",
        daemon=True,
    )
    thread.start()
    return server


class ResourceVersionExpired(RuntimeError):
    """The API server can no longer serve the requested watch history."""


class BoundedBackoff:
    def __init__(self, minimum=1.0, maximum=30.0):
        self.minimum = minimum
        self.maximum = maximum
        self.current = minimum

    def success(self):
        self.current = self.minimum

    def failure(self):
        delay = self.current
        self.current = min(self.maximum, self.current * 2)
        return delay


def bounded_error_category(error):
    if isinstance(error, urllib.error.HTTPError):
        if 400 <= error.code < 500:
            return "http_4xx"
        if 500 <= error.code < 600:
            return "http_5xx"
        return "http_other"
    if isinstance(error, (TimeoutError, socket.timeout)):
        return "timeout"
    if isinstance(error, urllib.error.URLError):
        return "network"
    return "internal"


def request(
    url,
    method="GET",
    body=None,
    token=None,
    context=None,
    headers=None,
    content_type=None,
):
    payload = None if body is None else json.dumps(body).encode()
    request_headers = {"Accept": "application/json", **(headers or {})}
    if payload is not None:
        request_headers["Content-Type"] = content_type or "application/json"
    if token:
        request_headers["Authorization"] = f"Bearer {token}"
    req = urllib.request.Request(url, data=payload, method=method, headers=request_headers)
    with urllib.request.urlopen(req, context=context, timeout=10) as response:
        content = response.read()
        return json.loads(content) if content else {}


def kube_request(path, method="GET", body=None):
    with open(TOKEN_PATH, encoding="utf-8") as token_file:
        token = token_file.read().strip()
    return request(
        KUBE_ROOT + path,
        method,
        body,
        token,
        ssl.create_default_context(cafile=CA_PATH),
        content_type="application/merge-patch+json" if method == "PATCH" else None,
    )


def open_kube_watch(path, timeout_seconds):
    with open(TOKEN_PATH, encoding="utf-8") as token_file:
        token = token_file.read().strip()
    req = urllib.request.Request(
        KUBE_ROOT + path,
        method="GET",
        headers={
            "Accept": "application/json",
            "Authorization": f"Bearer {token}",
        },
    )
    try:
        return urllib.request.urlopen(
            req,
            context=ssl.create_default_context(cafile=CA_PATH),
            timeout=timeout_seconds + 5,
        )
    except urllib.error.HTTPError as error:
        if error.code == 410:
            raise ResourceVersionExpired("watch resourceVersion expired") from error
        raise


def control_request(path, method="GET", body=None, headers=None):
    context = ssl.create_default_context(cafile=CONTROL_CA) if CONTROL_CA else None
    payload = None if body is None else json.dumps(body).encode()
    request_headers = {
        "Accept": "application/json",
        "Authorization": f"Bearer {CONTROL_TOKEN}",
        **(headers or {}),
    }
    if payload is not None:
        request_headers["Content-Type"] = "application/json"
    req = urllib.request.Request(
        CONTROL_PLANE + path, data=payload, method=method, headers=request_headers
    )
    with urllib.request.urlopen(req, context=context, timeout=15) as response:
        content = response.read()
        return json.loads(content) if content else {}


def patch_resource(item, patch, status=False):
    metadata = item["metadata"]
    namespace = metadata["namespace"]
    name = metadata["name"]
    suffix = "/status" if status else ""
    path = f"{API_PATH}/namespaces/{namespace}/browsersessions/{name}{suffix}"
    return kube_request(path, "PATCH", patch)


def operator_namespace():
    namespace = os.environ.get("POD_NAMESPACE")
    if namespace:
        return namespace
    with open(NAMESPACE_PATH, encoding="utf-8") as namespace_file:
        return namespace_file.read().strip()


def operator_identity():
    return os.environ.get("POD_NAME") or socket.gethostname()


def utc_now():
    return datetime.datetime.now(datetime.timezone.utc)


def format_kube_time(value):
    return value.astimezone(datetime.timezone.utc).isoformat(
        timespec="microseconds"
    ).replace("+00:00", "Z")


def parse_kube_time(value):
    if not value:
        return None
    return datetime.datetime.fromisoformat(value.replace("Z", "+00:00"))


def lease_path(namespace):
    return (
        f"/apis/coordination.k8s.io/v1/namespaces/{namespace}/leases/{LEASE_NAME}"
    )


def lease_expired(spec, now):
    renewed_at = parse_kube_time(spec.get("renewTime") or spec.get("acquireTime"))
    if renewed_at is None:
        return True
    duration = int(spec.get("leaseDurationSeconds") or LEASE_DURATION_SECONDS)
    return now >= renewed_at + datetime.timedelta(seconds=duration)


def create_lease(namespace, identity, now):
    return {
        "apiVersion": "coordination.k8s.io/v1",
        "kind": "Lease",
        "metadata": {
            "name": LEASE_NAME,
            "namespace": namespace,
        },
        "spec": {
            "holderIdentity": identity,
            "leaseDurationSeconds": LEASE_DURATION_SECONDS,
            "acquireTime": format_kube_time(now),
            "renewTime": format_kube_time(now),
            "leaseTransitions": 0,
        },
    }


def renew_lease(lease, identity, now, takeover=False):
    spec = lease.get("spec", {})
    patch = {
        "metadata": {"resourceVersion": lease["metadata"]["resourceVersion"]},
        "spec": {
            "holderIdentity": identity,
            "leaseDurationSeconds": LEASE_DURATION_SECONDS,
            "renewTime": format_kube_time(now),
        },
    }
    if takeover:
        patch["spec"]["acquireTime"] = format_kube_time(now)
        patch["spec"]["leaseTransitions"] = int(spec.get("leaseTransitions") or 0) + 1
    return patch


def try_acquire_or_renew(namespace, identity, now=None):
    now = now or utc_now()
    path = lease_path(namespace)
    try:
        lease = kube_request(path)
    except urllib.error.HTTPError as error:
        if error.code != 404:
            raise
        try:
            kube_request(
                path.rsplit("/", 1)[0],
                "POST",
                create_lease(namespace, identity, now),
            )
            return True
        except urllib.error.HTTPError as create_error:
            if create_error.code == 409:
                return False
            raise

    spec = lease.get("spec", {})
    holder = spec.get("holderIdentity")
    if holder != identity and not lease_expired(spec, now):
        return False
    try:
        kube_request(
            path,
            "PATCH",
            renew_lease(lease, identity, now, takeover=holder != identity),
        )
        return True
    except urllib.error.HTTPError as error:
        if error.code == 409:
            return False
        raise


def resource_pages():
    """Yield one consistent snapshot page at a time without retaining every CR."""
    continuation = None
    resource_version = None
    while True:
        query = {"limit": LIST_PAGE_SIZE}
        if continuation:
            query["continue"] = continuation
        else:
            query["resourceVersion"] = "0"
            query["resourceVersionMatch"] = "NotOlderThan"
        try:
            response = kube_request(f"{RESOURCE_PATH}?{urllib.parse.urlencode(query)}")
        except Exception:
            METRICS.increment(
                "browsercloud_operator_list_requests_total", {"result": "error"}
            )
            raise
        METRICS.increment(
            "browsercloud_operator_list_requests_total", {"result": "success"}
        )
        metadata = response.get("metadata", {})
        page_version = metadata.get("resourceVersion")
        if page_version:
            if resource_version and page_version != resource_version:
                raise RuntimeError("BrowserSession list snapshot version changed")
            resource_version = page_version
        if not resource_version:
            raise RuntimeError("BrowserSession list omitted resourceVersion")
        yield response.get("items", []), resource_version
        continuation = metadata.get("continue")
        if not continuation:
            return


def reconcile_snapshot():
    resource_count = 0
    resource_version = None
    first_error = None
    result = "error"
    try:
        for items, resource_version in resource_pages():
            resource_count += len(items)
            for item in items:
                try:
                    reconcile_observed(item, "snapshot")
                except Exception as error:
                    first_error = first_error or error
                    print(
                        f"snapshot reconcile failed for "
                        f"{item['metadata'].get('name')}: {error}",
                        flush=True,
                    )
        if first_error:
            raise first_error
        if resource_version is None:
            raise RuntimeError("BrowserSession list returned no pages")
        METRICS.set("browsercloud_operator_list_snapshot_resources", resource_count)
        METRICS.set(
            "browsercloud_operator_last_successful_snapshot_unixtime", time.time()
        )
        result = "success"
        print(
            f"list-watch cache synchronized resources={resource_count} "
            f"resourceVersion={resource_version}",
            flush=True,
        )
        return resource_version
    finally:
        METRICS.increment(
            "browsercloud_operator_list_snapshots_total", {"result": result}
        )


def consume_watch(response, resource_version):
    """Consume one Kubernetes JSON-lines watch without advancing past failed work."""
    current_version = resource_version
    while True:
        raw = response.readline(MAX_WATCH_EVENT_BYTES + 1)
        if not raw:
            return current_version
        if len(raw) > MAX_WATCH_EVENT_BYTES:
            raise RuntimeError("BrowserSession watch event exceeded size limit")
        try:
            event = json.loads(raw)
        except json.JSONDecodeError as error:
            raise RuntimeError("BrowserSession watch returned invalid JSON") from error
        event_type = event.get("type")
        metric_event_type = (
            event_type
            if event_type in ("ADDED", "MODIFIED", "DELETED", "BOOKMARK", "ERROR")
            else "UNKNOWN"
        )
        METRICS.increment(
            "browsercloud_operator_watch_events_total", {"type": metric_event_type}
        )
        item = event.get("object") or {}
        if event_type == "ERROR":
            if int(item.get("code") or 0) == 410:
                raise ResourceVersionExpired("watch resourceVersion expired")
            raise RuntimeError(
                f"BrowserSession watch error code={item.get('code', 'unknown')}"
            )
        next_version = item.get("metadata", {}).get("resourceVersion")
        if event_type == "BOOKMARK":
            if next_version:
                current_version = next_version
            continue
        if event_type in ("ADDED", "MODIFIED"):
            reconcile_observed(item, "watch")
        elif event_type != "DELETED":
            raise RuntimeError(f"unsupported BrowserSession watch event {event_type}")
        if next_version:
            current_version = next_version


def watch_resources(resource_version, timeout_seconds=None):
    timeout_seconds = timeout_seconds or WATCH_TIMEOUT_SECONDS
    query = urllib.parse.urlencode(
        {
            "watch": "1",
            "allowWatchBookmarks": "true",
            "resourceVersion": resource_version,
            "timeoutSeconds": timeout_seconds,
        }
    )
    try:
        with open_kube_watch(f"{RESOURCE_PATH}?{query}", timeout_seconds) as response:
            return consume_watch(response, resource_version)
    except ResourceVersionExpired:
        raise
    except Exception:
        METRICS.increment(
            "browsercloud_operator_watch_restarts_total", {"reason": "error"}
        )
        raise


def reconcile(item):
    metadata = item["metadata"]
    spec = item["spec"]
    status = item.get("status", {})
    finalizers = metadata.get("finalizers", [])
    if metadata.get("deletionTimestamp"):
        session_id = status.get("sessionId")
        if session_id:
            try:
                control_request(f"/api/v1/sessions/{session_id}:terminate", "POST")
            except urllib.error.HTTPError as error:
                if error.code not in (404, 409):
                    raise
        patch_resource(item, {"metadata": {"finalizers": [f for f in finalizers if f != FINALIZER]}})
        return
    if FINALIZER not in finalizers:
        patch_resource(item, {"metadata": {"finalizers": [*finalizers, FINALIZER]}})
        return
    if not status.get("sessionId"):
        resource_policy = {**(spec.get("resourcePolicy") or {}), "mode": "AUTO"}
        if spec.get("executionEnvironment"):
            resource_policy["executionEnvironment"] = spec["executionEnvironment"]
        created = control_request(
            "/api/v1/sessions",
            "POST",
            {
                "tenantId": spec["tenantId"],
                "profileId": spec["profileId"],
                "region": spec.get("region", "local"),
                "resourcePolicy": resource_policy,
                "metadata": {"displayName": metadata["name"]},
            },
            {"Idempotency-Key": f"k8s-{metadata['uid']}"},
        )
        status["sessionId"] = created["sessionId"]
    desired_status = {
        **status,
        "phase": "Ready",
        "observedGeneration": metadata.get("generation", 1),
        "lastError": "",
    }
    if status != desired_status:
        patch_resource(item, {"status": desired_status}, status=True)


def reconcile_observed(item, source):
    started_at = time.perf_counter()
    result = "error"
    try:
        reconcile(item)
        result = "success"
    finally:
        labels = {"source": source, "result": result}
        METRICS.increment("browsercloud_operator_reconcile_total", labels)
        METRICS.observe(
            "browsercloud_operator_reconcile_duration_seconds",
            time.perf_counter() - started_at,
            labels,
        )


def main():
    start_metrics_server()
    print(
        f"metrics listening on {METRICS_BIND_ADDRESS}:{METRICS_PORT}",
        flush=True,
    )
    namespace = operator_namespace()
    identity = operator_identity()
    was_leader = False
    resource_version = None
    last_resync = 0.0
    backoff = BoundedBackoff()
    while True:
        try:
            try:
                is_leader = try_acquire_or_renew(namespace, identity)
            except Exception:
                METRICS.increment(
                    "browsercloud_operator_lease_attempts_total", {"result": "error"}
                )
                raise
            lease_result = "leader" if is_leader else "follower"
            METRICS.increment(
                "browsercloud_operator_lease_attempts_total", {"result": lease_result}
            )
            METRICS.set("browsercloud_operator_leader", 1 if is_leader else 0)
            if is_leader:
                METRICS.set(
                    "browsercloud_operator_last_successful_lease_unixtime", time.time()
                )
            if is_leader and not was_leader:
                print(f"leadership acquired by {identity}", flush=True)
            if not is_leader and was_leader:
                print(f"leadership lost by {identity}", flush=True)
                resource_version = None
            was_leader = is_leader
            if is_leader:
                now = time.monotonic()
                if (
                    resource_version is None
                    or now - last_resync >= RESYNC_INTERVAL_SECONDS
                ):
                    resource_version = reconcile_snapshot()
                    last_resync = now
                resource_version = watch_resources(resource_version)
                METRICS.set(
                    "browsercloud_operator_last_successful_watch_unixtime", time.time()
                )
                METRICS.increment(
                    "browsercloud_operator_watch_restarts_total",
                    {"reason": "server_timeout"},
                )
                backoff.success()
                METRICS.set("browsercloud_operator_backoff_seconds", 0)
                continue
            backoff.success()
            METRICS.set("browsercloud_operator_backoff_seconds", 0)
        except ResourceVersionExpired:
            resource_version = None
            METRICS.increment("browsercloud_operator_resource_version_expired_total")
            METRICS.increment(
                "browsercloud_operator_watch_restarts_total",
                {"reason": "resource_version_expired"},
            )
            print("watch resourceVersion expired; relisting", flush=True)
        except Exception as error:
            was_leader = False
            category = bounded_error_category(error)
            METRICS.increment(
                "browsercloud_operator_loop_errors_total", {"category": category}
            )
            print(f"operator loop failed: {error}", flush=True)
            delay = backoff.failure()
            METRICS.set("browsercloud_operator_backoff_seconds", delay)
            time.sleep(delay)
            continue
        time.sleep(RECONCILE_INTERVAL_SECONDS)


if __name__ == "__main__":
    main()
