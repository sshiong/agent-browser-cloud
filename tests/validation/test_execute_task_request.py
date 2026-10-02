import ast
import contextlib
import importlib.util
import io
import json
import pathlib
import threading
import urllib.error
import urllib.request
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from types import SimpleNamespace


SOURCE = pathlib.Path(__file__).resolve().parents[1] / "integration/execute_task_request.py"
spec = importlib.util.spec_from_file_location("execute_task_request", SOURCE)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class HttpFixture:
    def fixture(self, responses):
        requests = []

        class Handler(BaseHTTPRequestHandler):
            def do_POST(self):
                requests.append((self.path, self.headers.get("X-Tenant-Id"),
                                 self.headers.get("Idempotency-Key"),
                                 self.rfile.read(int(self.headers.get("Content-Length", "0")))))
                status, body = responses[min(len(requests) - 1, len(responses) - 1)]
                if status is None:
                    self.connection.close()
                    return
                payload = body if isinstance(body, bytes) else json.dumps(body).encode()
                self.send_response(status)
                self.send_header("Content-Length", str(len(payload)))
                if status == 302:
                    self.send_header("Location", "https://outside.invalid/private")
                self.end_headers()
                self.wfile.write(payload)

            def log_message(self, *args):
                pass

        server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()

        def cleanup():
            server.shutdown()
            server.server_close()
            thread.join(timeout=2)

        self.addCleanup(cleanup)
        return f"http://127.0.0.1:{server.server_port}/api/v1/agent-tasks/agt_0123456789abcdef:execute", requests


class ExecutionRequestTest(HttpFixture, unittest.TestCase):
    def call(self, url, **kwargs):
        return module.execute_task_request(url, "tenant-fixture", "fixture-execute-idempotency", **kwargs)

    def test_transaction_abort_reuses_exact_request_and_stops_after_success(self):
        conflict = (503, {"code": "DATABASE_TRANSACTION_RETRY", "details": {"retryable": True}})
        url, requests = self.fixture([conflict, conflict, (200, {"state": "AWAITING_REVIEW"})])
        delays = []
        diagnostics = io.StringIO()
        with contextlib.redirect_stderr(diagnostics):
            result = self.call(url, sleep=delays.append)
        self.assertEqual(json.loads(result), {"state": "AWAITING_REVIEW"})
        self.assertEqual(len(requests), 3)
        self.assertTrue(all(item == requests[0] for item in requests))
        self.assertEqual(requests[0][1:], ("tenant-fixture", "fixture-execute-idempotency", b""))
        self.assertEqual(delays, [0.1, 0.2])
        self.assertEqual(diagnostics.getvalue().splitlines(), [
            "EXECUTE_TRANSACTION_RETRY attempt=1 max_attempts=3",
            "EXECUTE_TRANSACTION_RETRY attempt=2 max_attempts=3",
        ])

    def test_other_http_errors_and_unproven_conflicts_are_not_replayed_or_logged(self):
        marker = "fixture-private-error-body"
        for status, body in [
            (503, {"code": "DATABASE_UNAVAILABLE", "message": marker}),
            (503, {"code": "DATABASE_TRANSACTION_RETRY", "details": {"retryable": False}}),
            (503, {"code": "DATABASE_TRANSACTION_RETRY"}),
            (409, {"code": "DATABASE_TRANSACTION_RETRY", "details": {"retryable": True}}),
            (403, {"message": marker}), (503, marker.encode()), (302, {"message": marker}),
        ]:
            with self.subTest(status=status, body=body):
                url, requests = self.fixture([(status, body)])
                with self.assertRaises(module.ExecutionRequestError) as raised:
                    self.call(url, sleep=lambda _: self.fail("unexpected retry"))
                self.assertEqual(len(requests), 1)
                self.assertNotIn(marker, str(raised.exception))
                self.assertNotIn("https://", str(raised.exception))

    def test_exhausted_transaction_retry_is_bounded(self):
        url, requests = self.fixture([(503, {"code": "DATABASE_TRANSACTION_RETRY",
                                            "details": {"retryable": True}})])
        with self.assertRaisesRegex(module.ExecutionRequestError, "attempts=3"):
            self.call(url, sleep=lambda _: None)
        self.assertEqual(len(requests), 3)

    def test_transport_disconnect_is_not_replayed(self):
        url, requests = self.fixture([(None, b"")])
        with self.assertRaises(module.ExecutionRequestError):
            self.call(url, sleep=lambda _: self.fail("unexpected retry"))
        self.assertEqual(len(requests), 1)

    def test_scope_validation_precedes_network(self):
        for url, key in [("https://outside.invalid/api/v1/agent-tasks/agt_0123456789abcdef:execute", "key"),
                         ("http://127.0.0.1:9/api/v1/agent-tasks/agt_0123456789abcdef:execute?token=private", "key"),
                         ("http://127.0.0.1:9/api/v1/sessions/session:start", "key"),
                         ("http://127.0.0.1:private/api/v1/agent-tasks/agt_0123456789abcdef:execute", "key"),
                         ("http://[invalid/api/v1/agent-tasks/agt_0123456789abcdef:execute", "key"),
                         ("http://127.0.0.1:9/api/v1/agent-tasks/agt_0123456789abcdef:execute", "")]:
            with self.subTest(url=url, key=key), self.assertRaisesRegex(
                    module.ExecutionRequestError, "SCOPE_INVALID"):
                module.execute_task_request(url, "tenant-fixture", key)


class ReplayRequestContractTest(HttpFixture, unittest.TestCase):
    def replay_request(self, url, delays):
        source = SOURCE.parent.parent / "compatibility/real_url_agent_matrix.py"
        tree = ast.parse(source.read_text())
        function = next(node for node in tree.body if isinstance(node, ast.FunctionDef)
                        and node.name == "request")
        scope = {"json": json, "urllib": urllib, "time": SimpleNamespace(sleep=delays.append),
                 "BASE_URL": url.split("/api/v1/", 1)[0], "TENANT": "tenant-fixture"}
        exec(compile(ast.Module(body=[function], type_ignores=[]), str(source), "exec"), scope)
        return scope["request"]

    def test_actual_matrix_request_requires_boolean_transaction_abort_proof(self):
        for details in [None, {}, {"retryable": False}, {"retryable": "true"}, {"retryable": 1}]:
            with self.subTest(details=details):
                body = {"code": "DATABASE_TRANSACTION_RETRY", "details": details}
                url, requests = self.fixture([(503, body)])
                delays = []
                call = self.replay_request(url, delays)
                status, payload = call("POST", "/api/v1" + url.split("/api/v1", 1)[1],
                                       body={"fixture": True}, idempotency_key="fixture-stable-key")
                self.assertEqual((status, payload), (503, body))
                self.assertEqual(len(requests), 1)
                self.assertEqual(delays, [])

    def test_actual_matrix_request_preserves_body_and_key_for_proven_abort(self):
        conflict = (503, {"code": "DATABASE_TRANSACTION_RETRY", "details": {"retryable": True}})
        url, requests = self.fixture([conflict, conflict, (200, {"state": "AWAITING_REVIEW"})])
        delays = []
        call = self.replay_request(url, delays)
        status, payload = call("POST", "/api/v1" + url.split("/api/v1", 1)[1],
                               body={"fixture": True}, idempotency_key="fixture-stable-key")
        self.assertEqual((status, payload), (200, {"state": "AWAITING_REVIEW"}))
        self.assertEqual(len(requests), 3)
        self.assertTrue(all(request == requests[0] for request in requests))
        self.assertEqual(requests[0][1:3], ("tenant-fixture", "fixture-stable-key"))
        self.assertEqual(json.loads(requests[0][3]), {"fixture": True})
        self.assertEqual(delays, [0.1, 0.2])


if __name__ == "__main__":
    unittest.main()
