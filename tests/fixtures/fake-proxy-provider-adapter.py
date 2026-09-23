#!/usr/bin/env python3
"""Deterministic REMOTE_HTTP_V1 proxy adapter used by the integration gate."""

import json
import sys
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


if len(sys.argv) != 6:
    raise SystemExit(
        "usage: fake-proxy-provider-adapter.py PORT TOKEN PROXY_PORT CREDENTIAL_REF EVENTS_FILE"
    )

port = int(sys.argv[1])
expected_token = sys.argv[2]
proxy_port = int(sys.argv[3])
credential_ref = sys.argv[4]
events_file = sys.argv[5]


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_args):
        return

    def _json_body(self):
        length = int(self.headers.get("Content-Length", "0"))
        if length > 64 * 1024:
            raise ValueError("request too large")
        return json.loads(self.rfile.read(length)) if length else {}

    def _send(self, status, payload=None):
        body = b"" if payload is None else json.dumps(payload, separators=(",", ":")).encode()
        self.send_response(status)
        if body:
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        if body:
            self.wfile.write(body)

    def _authorize(self):
        authorized = self.headers.get("Authorization") == f"Bearer {expected_token}"
        if not authorized:
            self._send(401, {"code": "AUTH_FAILED", "retryable": False})
        return authorized

    def _record(self, body):
        event = {
            "method": self.command,
            "path": self.path,
            "idempotencyKey": self.headers.get("Idempotency-Key"),
            "credentialReferenceOnly": credential_ref in json.dumps(body)
            and expected_token not in json.dumps(body),
        }
        with open(events_file, "a", encoding="utf-8") as events:
            events.write(json.dumps(event, separators=(",", ":")) + "\n")

    def do_GET(self):
        if not self._authorize():
            return
        self._record({})
        if self.path.endswith("/capabilities"):
            self._send(
                200,
                {
                    "protocols": ["HTTP", "HTTPS_CONNECT"],
                    "productTypes": ["DATACENTER"],
                    "stickySession": True,
                    "countrySelection": False,
                    "citySelection": False,
                    "asnSelection": False,
                    "ipFamilies": ["IPV4"],
                    "rotation": True,
                    "bandwidthMetering": True,
                    "providerWebhook": False,
                },
            )
        elif self.path.endswith("/health"):
            self._send(
                200,
                {
                    "state": "HEALTHY",
                    "checkedAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
                    "reason": "integration fixture",
                },
            )
        elif self.path.endswith("/usage"):
            self._send(
                200,
                {
                    "metered": True,
                    "ingressBytes": 1024,
                    "egressBytes": 2048,
                    "measuredAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
                },
            )
        else:
            self._send(404, {"code": "PROVIDER_OUTAGE", "retryable": False})

    def do_POST(self):
        if not self._authorize():
            return
        try:
            body = self._json_body()
        except (ValueError, json.JSONDecodeError):
            self._send(400, {"code": "PROVIDER_OUTAGE", "retryable": False})
            return
        self._record(body)
        if self.path.endswith("/allocate"):
            endpoint_id = body.get("endpointId")
            if (
                not endpoint_id
                or body.get("credentialRef") != credential_ref
                or self.headers.get("Idempotency-Key") != endpoint_id
            ):
                self._send(400, {"code": "AUTH_FAILED", "retryable": False})
                return
            self._send(
                201,
                {
                    "endpointId": endpoint_id,
                    "providerId": "static-local",
                    "endpoint": f"http://127.0.0.1:{proxy_port}",
                    "expectedExitIp": "203.0.113.10",
                    "credentialRef": credential_ref,
                    "protocol": "HTTP",
                    "productType": "DATACENTER",
                },
            )
        elif self.path.endswith("/rotate"):
            previous = self.headers.get("Idempotency-Key")
            self._send(
                200,
                {
                    "previousEndpointId": previous,
                    "endpoint": {
                        "endpointId": f"{previous}-rotated",
                        "providerId": "static-local",
                        "endpoint": f"http://127.0.0.1:{proxy_port}",
                        "expectedExitIp": "203.0.113.10",
                        "credentialRef": credential_ref,
                        "protocol": "HTTP",
                        "productType": "DATACENTER",
                    },
                },
            )
        else:
            self._send(404, {"code": "PROVIDER_OUTAGE", "retryable": False})

    def do_DELETE(self):
        if not self._authorize():
            return
        self._record({})
        if not self.headers.get("Idempotency-Key"):
            self._send(400, {"code": "RELEASE_FAILED", "retryable": False})
            return
        self._send(204)


ThreadingHTTPServer(("127.0.0.1", port), Handler).serve_forever()
