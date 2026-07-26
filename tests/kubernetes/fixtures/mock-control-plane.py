#!/usr/bin/env python3
"""Small deterministic Control Plane fixture for Kubernetes operator E2E tests."""

import json
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

LOCK = threading.Lock()
SESSIONS = {}
CREATE_CALLS = 0
TERMINATE_CALLS = 0


class Handler(BaseHTTPRequestHandler):
    def send_json(self, status, body):
        encoded = json.dumps(body).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(encoded)))
        self.end_headers()
        self.wfile.write(encoded)

    def do_GET(self):
        if self.path == "/health":
            self.send_json(200, {"status": "UP"})
            return
        if self.path == "/stats":
            with LOCK:
                self.send_json(
                    200,
                    {
                        "createCalls": CREATE_CALLS,
                        "terminateCalls": TERMINATE_CALLS,
                        "sessions": SESSIONS,
                    },
                )
            return
        self.send_json(404, {"code": "NOT_FOUND"})

    def do_POST(self):
        global CREATE_CALLS, TERMINATE_CALLS
        length = int(self.headers.get("Content-Length", "0"))
        body = json.loads(self.rfile.read(length) or b"{}")
        if self.path == "/api/v1/sessions":
            key = self.headers.get("Idempotency-Key", "")
            with LOCK:
                session_id = SESSIONS.get(key)
                if not session_id:
                    CREATE_CALLS += 1
                    session_id = f"kind-session-{CREATE_CALLS}"
                    SESSIONS[key] = session_id
            self.send_json(201, {"sessionId": session_id, "request": body})
            return
        if self.path.startswith("/api/v1/sessions/") and self.path.endswith(
            ":terminate"
        ):
            with LOCK:
                TERMINATE_CALLS += 1
            self.send_json(204, {})
            return
        self.send_json(404, {"code": "NOT_FOUND"})

    def log_message(self, message, *args):
        print(f"mock-control-plane: {message % args}", flush=True)


if __name__ == "__main__":
    ThreadingHTTPServer(("0.0.0.0", 8080), Handler).serve_forever()
