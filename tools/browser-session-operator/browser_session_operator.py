#!/usr/bin/env python3
"""BrowserSession reconciler with leader election and safe finalization."""

import datetime
import json
import os
import socket
import ssl
import time
import urllib.error
import urllib.request

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
NAMESPACE_PATH = "/var/run/secrets/kubernetes.io/serviceaccount/namespace"


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
        created = control_request(
            "/api/v1/sessions",
            "POST",
            {
                "tenantId": spec["tenantId"],
                "profileId": spec["profileId"],
                "region": spec.get("region", "local"),
                "resourceClass": spec.get("resourceClass", "L2"),
                "metadata": {"displayName": metadata["name"]},
            },
            {"Idempotency-Key": f"k8s-{metadata['uid']}"},
        )
        status["sessionId"] = created["sessionId"]
    patch_resource(
        item,
        {
            "status": {
                **status,
                "phase": "Ready",
                "observedGeneration": metadata.get("generation", 1),
                "lastError": "",
            }
        },
        status=True,
    )


def main():
    namespace = operator_namespace()
    identity = operator_identity()
    was_leader = False
    while True:
        try:
            is_leader = try_acquire_or_renew(namespace, identity)
            if is_leader and not was_leader:
                print(f"leadership acquired by {identity}", flush=True)
            if not is_leader and was_leader:
                print(f"leadership lost by {identity}", flush=True)
            was_leader = is_leader
            if is_leader:
                response = kube_request(RESOURCE_PATH)
                for item in response.get("items", []):
                    try:
                        reconcile(item)
                    except Exception as error:
                        # The next reconcile retries from authoritative state.
                        print(
                            f"reconcile failed for "
                            f"{item['metadata'].get('name')}: {error}",
                            flush=True,
                        )
        except Exception as error:
            was_leader = False
            print(f"operator loop failed: {error}", flush=True)
        time.sleep(RECONCILE_INTERVAL_SECONDS)


if __name__ == "__main__":
    main()
