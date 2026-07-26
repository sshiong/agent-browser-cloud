#!/usr/bin/env python3
"""Minimal BrowserSession reconciler with observedGeneration and safe finalization."""

import json
import os
import ssl
import time
import urllib.error
import urllib.request

FINALIZER = "browsercloud.io/session-cleanup"
KUBE_HOST = os.environ.get("KUBERNETES_SERVICE_HOST", "kubernetes.default.svc")
KUBE_PORT = os.environ.get("KUBERNETES_SERVICE_PORT_HTTPS", "443")
KUBE_ROOT = f"https://{KUBE_HOST}:{KUBE_PORT}"
RESOURCE_PATH = "/apis/browsercloud.io/v1alpha1/browsersessions"
TOKEN_PATH = "/var/run/secrets/kubernetes.io/serviceaccount/token"
CA_PATH = "/var/run/secrets/kubernetes.io/serviceaccount/ca.crt"
CONTROL_PLANE = os.environ["CONTROL_PLANE_URL"].rstrip("/")
CONTROL_TOKEN = os.environ["CONTROL_PLANE_TOKEN"]
CONTROL_CA = os.environ.get("CONTROL_PLANE_CA")


def request(url, method="GET", body=None, token=None, context=None, headers=None):
    payload = None if body is None else json.dumps(body).encode()
    request_headers = {"Accept": "application/json", **(headers or {})}
    if payload is not None:
        request_headers["Content-Type"] = "application/merge-patch+json"
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
    path = f"{RESOURCE_PATH}/namespaces/{namespace}/{name}{suffix}"
    return kube_request(path, "PATCH", patch)


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
    while True:
        try:
            response = kube_request(RESOURCE_PATH)
            for item in response.get("items", []):
                try:
                    reconcile(item)
                except Exception as error:  # The next reconcile retries from authoritative state.
                    print(f"reconcile failed for {item['metadata'].get('name')}: {error}", flush=True)
        except Exception as error:
            print(f"operator list failed: {error}", flush=True)
        time.sleep(2)


if __name__ == "__main__":
    main()
