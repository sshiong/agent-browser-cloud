#!/usr/bin/env python3
"""Bounded OpenAI Responses fixture for the Reviewer Worker integration certificate."""

import hashlib
import json
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


PORT = int(sys.argv[1])
TOKEN = sys.argv[2]
EVENT_LOG = sys.argv[3]
FORBIDDEN_KEYS = {
    "capabilityToken", "sealedPayload", "customerCredentials", "pageState",
    "elementId", "targetRef", "value", "screenshot", "pageBody",
}


def contains_forbidden_key(value):
    if isinstance(value, dict):
        return any(
            key in FORBIDDEN_KEYS or contains_forbidden_key(item)
            for key, item in value.items()
        )
    if isinstance(value, list):
        return any(contains_forbidden_key(item) for item in value)
    return False


def user_payloads(request):
    for message in request.get("input", []):
        if not isinstance(message, dict) or message.get("role") != "user":
            continue
        for part in message.get("content", []):
            if not isinstance(part, dict) or part.get("type") != "input_text":
                continue
            try:
                yield json.loads(part.get("text", ""))
            except (TypeError, json.JSONDecodeError):
                yield {"pageBody": "invalid unstructured user payload"}


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_args):
        return

    def do_POST(self):
        if self.path != "/v1/responses" or self.headers.get("Authorization") != f"Bearer {TOKEN}":
            self.send_error(403)
            return
        raw = self.rfile.read(int(self.headers.get("Content-Length", "0")))
        try:
            request = json.loads(raw)
        except json.JSONDecodeError:
            self.send_error(400)
            return
        if any(contains_forbidden_key(payload) for payload in user_payloads(request)):
            self.send_error(422)
            return
        event = {
            "requestHash": hashlib.sha256(raw).hexdigest(),
            "model": request.get("model"),
            "hasJsonSchema": request.get("text", {}).get("format", {}).get("type") == "json_schema",
            "authorizationPresent": True,
            "forbiddenFieldsAbsent": True,
            "schemaName": request.get("text", {}).get("format", {}).get("name"),
        }
        with open(EVENT_LOG, "a", encoding="utf-8") as handle:
            handle.write(json.dumps(event, sort_keys=True) + "\n")
        schema_name = request.get("text", {}).get("format", {}).get("name")
        verdict_document = (
            {"decision": "VERIFIED", "reasonCodes": ["GOAL_SATISFIED"], "confidence": 0.97}
            if schema_name == "agent_outcome_verification"
            else {"decision": "APPROVE", "reasonCodes": ["SAFE"], "confidence": 0.97}
        )
        verdict = json.dumps(verdict_document, separators=(",", ":"))
        response = {
            "id": "resp_reviewer_integration",
            "model": request.get("model"),
            "output": [
                {
                    "type": "message",
                    "content": [{"type": "output_text", "text": verdict}],
                }
            ],
            "usage": {"input_tokens": 144, "output_tokens": 19},
        }
        payload = json.dumps(response, separators=(",", ":")).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("X-Request-Id", "req_reviewer_integration")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)


ThreadingHTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
