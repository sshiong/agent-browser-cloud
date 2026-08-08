#!/usr/bin/env python3
"""Deterministic CRM-like Provider used only by the integration smoke test."""

import json
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


if len(sys.argv) != 4:
    raise SystemExit("usage: fake-business-provider.py PORT TOKEN EVENTS_FILE")

port = int(sys.argv[1])
expected_token = sys.argv[2]
events_file = sys.argv[3]


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_args):
        return

    def do_GET(self):
        event = {
            "path": self.path,
            "authorized": self.headers.get("Authorization") == f"Bearer {expected_token}",
        }
        with open(events_file, "a", encoding="utf-8") as events:
            events.write(json.dumps(event, separators=(",", ":")) + "\n")
        if self.path != "/api/v1/me":
            self.send_response(404)
            self.end_headers()
            return
        if not event["authorized"]:
            self.send_response(401)
            self.end_headers()
            return
        payload = json.dumps({"account": {"id": "account-42"}}, separators=(",", ":")).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.send_header("X-Request-ID", "crm-provider-integration-42")
        self.end_headers()
        self.wfile.write(payload)


ThreadingHTTPServer(("127.0.0.1", port), Handler).serve_forever()
