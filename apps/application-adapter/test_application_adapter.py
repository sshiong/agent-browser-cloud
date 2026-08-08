#!/usr/bin/env python3

import importlib.util
import json
import os
import pathlib
import stat
import sys
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


MODULE_PATH = pathlib.Path(__file__).with_name("application_adapter.py")
SPEC = importlib.util.spec_from_file_location("application_adapter", MODULE_PATH)
adapter = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = adapter
SPEC.loader.exec_module(adapter)


class FixtureHandler(BaseHTTPRequestHandler):
    requests = []

    def log_message(self, *_args):
        return

    def _record(self, body):
        self.__class__.requests.append(
            {
                "method": self.command,
                "path": self.path,
                "authorization": self.headers.get("Authorization"),
                "idempotency": self.headers.get("Idempotency-Key"),
                "tenant": self.headers.get("X-Tenant-Id"),
                "actor": self.headers.get("X-Actor-Id"),
                "roles": self.headers.get("X-Roles"),
                "body": body,
            }
        )

    def do_GET(self):
        self._record(None)
        if self.path == "/provider":
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("X-Request-ID", "provider-request-7")
            self.end_headers()
            self.wfile.write(json.dumps({"account": {"id": "acct-7"}}).encode())
            return
        if self.path == "/redirect":
            self.send_response(302)
            self.send_header("Location", "/provider")
            self.end_headers()
            return
        if self.path == "/wrong-content":
            payload = json.dumps({"account": {"id": "acct-7"}}).encode()
            self.send_response(200)
            self.send_header("Content-Type", "text/plain")
            self.end_headers()
            self.wfile.write(payload)
            return
        if self.path == "/oversized":
            self.send_response(200)
            self.end_headers()
            self.wfile.write(json.dumps({"value": "x" * 4096}).encode())
            return
        self.send_response(404)
        self.end_headers()

    def _mutation(self):
        length = int(self.headers.get("Content-Length", "0"))
        body = json.loads(self.rfile.read(length)) if length else None
        self._record(body)
        if self.path.endswith("/business-recovery/provider-evidence"):
            self.send_response(201)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(
                json.dumps(
                    {
                        "evidenceId": "bre_1234567890abcdef",
                        "outcome": body["outcome"],
                        "requestId": "req-control-plane",
                        "expiresAt": "2026-08-08T12:05:00Z",
                    }
                ).encode()
            )
            return
        if "/safety-leases" in self.path:
            self.send_response(201 if self.command == "POST" and self.path.endswith("safety-leases") else 200)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(
                json.dumps(
                    {
                        "leaseId": "sfl_1234567890abcdef",
                        "state": "RELEASED" if self.path.endswith(":release") else "ACTIVE",
                    }
                ).encode()
            )
            return
        self.send_response(404)
        self.end_headers()

    do_POST = _mutation
    do_PUT = _mutation


class AdapterRuntimeTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), FixtureHandler)
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()
        cls.base_url = f"http://127.0.0.1:{cls.server.server_port}"

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.thread.join(timeout=2)

    def setUp(self):
        FixtureHandler.requests.clear()
        self.transport = adapter.HttpTransport(
            {"127.0.0.1"}, allow_http=True, timeout_seconds=2
        )

    def test_secret_file_requires_private_permissions_and_single_line_value(self):
        with tempfile.TemporaryDirectory() as directory:
            path = pathlib.Path(directory, "token")
            path.write_text("short-lived-token\n", encoding="utf-8")
            path.chmod(stat.S_IRUSR | stat.S_IWUSR)
            self.assertEqual(adapter.read_secret_file(path), "short-lived-token")
            path.chmod(stat.S_IRUSR | stat.S_IWUSR | stat.S_IRGRP)
            with self.assertRaisesRegex(adapter.AdapterError, "SECRET_FILE_PERMISSIONS_TOO_BROAD"):
                adapter.read_secret_file(path)

    def test_json_pointer_and_canonical_hash_are_deterministic(self):
        document = {"items": [{"account/id": {"~key": "acct-7"}}]}
        self.assertEqual(
            adapter.extract_json_pointer(document, "/items/0/account~1id/~0key"), "acct-7"
        )
        self.assertEqual(
            adapter.sha256_value({"b": 2, "a": 1}),
            adapter.sha256_value({"a": 1, "b": 2}),
        )

    def test_provider_redirect_is_not_followed(self):
        client = adapter.ProviderClient(self.transport, "provider-token")
        spec = self.spec(provider_url=f"{self.base_url}/redirect")
        with self.assertRaisesRegex(adapter.AdapterError, "PROVIDER_HTTP_302"):
            client.observe(spec)
        self.assertEqual([item["path"] for item in FixtureHandler.requests], ["/redirect"])

    def test_provider_response_is_bounded(self):
        transport = adapter.HttpTransport(
            {"127.0.0.1"}, allow_http=True, max_response_bytes=1024
        )
        client = adapter.ProviderClient(transport, None)
        with self.assertRaisesRegex(adapter.AdapterError, "PROVIDER_RESPONSE_TOO_LARGE"):
            client.observe(self.spec(provider_url=f"{self.base_url}/oversized"))

    def test_provider_requires_json_content_type(self):
        client = adapter.ProviderClient(self.transport, None)
        with self.assertRaisesRegex(adapter.AdapterError, "PROVIDER_CONTENT_TYPE_INVALID"):
            client.observe(self.spec(provider_url=f"{self.base_url}/wrong-content"))

    def test_non_allowlisted_provider_url_is_rejected_before_network(self):
        client = adapter.ProviderClient(self.transport, None)
        with self.assertRaisesRegex(adapter.AdapterError, "PROVIDER_URL_REJECTED"):
            client.observe(self.spec(provider_url="http://localhost/provider"))
        self.assertEqual(FixtureHandler.requests, [])

    def test_real_provider_read_submits_only_hash_and_reference(self):
        provider = adapter.ProviderClient(self.transport, "provider-secret")
        control_plane = adapter.BrowserCloudAdapterClient(
            self.base_url, self.transport, "control-secret"
        )
        receipt = adapter.AttestationRunner(provider, control_plane).run(self.spec())

        self.assertEqual(receipt.evidence_id, "bre_1234567890abcdef")
        self.assertEqual(receipt.outcome, "MATCH")
        self.assertEqual(len(FixtureHandler.requests), 2)
        provider_request, control_request = FixtureHandler.requests
        self.assertEqual(provider_request["authorization"], "Bearer provider-secret")
        self.assertEqual(control_request["authorization"], "Bearer control-secret")
        self.assertEqual(control_request["body"]["observedValueHash"], adapter.sha256_value("acct-7"))
        self.assertEqual(control_request["body"]["providerReference"], "provider-request-7")
        self.assertNotIn("acct-7", json.dumps(control_request["body"]))
        self.assertRegex(control_request["idempotency"], r"^att-[0-9a-f]{64}$")

    def test_mismatch_is_submitted_fail_closed(self):
        provider = adapter.ProviderClient(self.transport, None)
        control_plane = adapter.BrowserCloudAdapterClient(self.base_url, self.transport, "token")
        spec = self.spec(expected_hash="0" * 64)
        receipt = adapter.AttestationRunner(provider, control_plane).run(spec)
        self.assertEqual(receipt.outcome, "MISMATCH")
        self.assertEqual(FixtureHandler.requests[-1]["body"]["outcome"], "MISMATCH")

    def test_same_observation_retry_reuses_exact_idempotency_key_and_body(self):
        provider = adapter.ProviderClient(self.transport, None)
        control_plane = adapter.BrowserCloudAdapterClient(self.base_url, self.transport, "token")
        spec = self.spec()
        observation = provider.observe(spec)
        first = control_plane.submit_evidence(spec, observation)
        second = control_plane.submit_evidence(spec, observation)
        self.assertEqual(first.evidence_id, second.evidence_id)
        first_request, second_request = FixtureHandler.requests[-2:]
        self.assertEqual(first_request["idempotency"], second_request["idempotency"])
        self.assertEqual(first_request["body"], second_request["body"])

    def test_lease_sdk_uses_owner_bound_control_plane_endpoints(self):
        client = adapter.BrowserCloudAdapterClient(self.base_url, self.transport, "control-secret")
        acquired = client.acquire_lease(
            "ses_1234567890abcdef",
            "PAYMENT_OR_SECURITY",
            "CHECKOUT_COMMIT",
            30,
            "lease-acquire-1",
        )
        renewed = client.renew_lease(
            "ses_1234567890abcdef", acquired["leaseId"], 30, "lease-renew-1"
        )
        released = client.release_lease(
            "ses_1234567890abcdef", acquired["leaseId"], "lease-release-1"
        )
        self.assertEqual(renewed["state"], "ACTIVE")
        self.assertEqual(released["state"], "RELEASED")
        self.assertEqual([item["method"] for item in FixtureHandler.requests], ["POST", "PUT", "POST"])
        self.assertTrue(FixtureHandler.requests[-1]["path"].endswith(":release"))

    def test_local_identity_is_fixed_to_application_adapter(self):
        client = adapter.BrowserCloudAdapterClient(
            self.base_url,
            self.transport,
            "control-secret",
            ("tenant-test", "crm-adapter"),
        )
        client.acquire_lease(
            "ses_1234567890abcdef",
            "CRITICAL_TRANSACTION",
            "CRM_WRITE",
            30,
            "lease-local-1",
        )
        request = FixtureHandler.requests[-1]
        self.assertEqual(request["tenant"], "tenant-test")
        self.assertEqual(request["actor"], "crm-adapter")
        self.assertEqual(request["roles"], "APPLICATION_ADAPTER")

    def spec(self, provider_url=None, expected_hash=None):
        return adapter.ProviderAttestationSpec(
            session_id="ses_1234567890abcdef",
            context_epoch=7,
            state_version=42,
            evidence_type="ACCOUNT",
            key="current-account",
            provider_id="crm-provider",
            expected_value_hash=expected_hash or adapter.sha256_value("acct-7"),
            provider_url=provider_url or f"{self.base_url}/provider",
            value_pointer="/account/id",
        )


if __name__ == "__main__":
    unittest.main()
