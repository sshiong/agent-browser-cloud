#!/usr/bin/env python3

import json
import pathlib
import ssl
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from unittest.mock import Mock, patch


sys.path.insert(0, str(pathlib.Path(__file__).parent))

from cancellable_http import CancellableHttpClient, RequestCancelled
from agent_worker import WorkerError
from outcome_verifier_worker import OpenAIResponsesOutcomeVerifier, OutcomeVerifierLoop
from reviewer_worker import OpenAIResponsesReviewer, ReviewerLoop
from vision_worker import ScreenshotVisionProvider


class BlockingHandler(BaseHTTPRequestHandler):
    request_received = threading.Event()
    release_response = threading.Event()

    def log_message(self, *_args):
        return

    def do_POST(self):
        self.rfile.read(int(self.headers.get("Content-Length", "0")))
        self.__class__.request_received.set()
        self.__class__.release_response.wait(10)
        raw = json.dumps({"ok": True}).encode()
        try:
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(raw)))
            self.end_headers()
            self.wfile.write(raw)
        except (BrokenPipeError, ConnectionResetError):
            pass


class CancellationTestServer(ThreadingHTTPServer):
    def handle_error(self, request, client_address):
        if isinstance(sys.exc_info()[1], (BrokenPipeError, ConnectionResetError)):
            return  # Expected when cancellation closes the owned TCP/TLS connection.
        super().handle_error(request, client_address)


class CancellableHttpClientTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = CancellationTestServer(("127.0.0.1", 0), BlockingHandler)
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()
        cls.url = f"http://127.0.0.1:{cls.server.server_port}/v1/responses"

    @classmethod
    def tearDownClass(cls):
        BlockingHandler.release_response.set()
        cls.server.shutdown()
        cls.server.server_close()
        cls.thread.join(timeout=2)

    def setUp(self):
        BlockingHandler.request_received.clear()
        BlockingHandler.release_response.clear()
        self.client = CancellableHttpClient(ssl.create_default_context())

    def tearDown(self):
        BlockingHandler.release_response.set()

    def test_in_flight_request_is_aborted_when_lease_signal_is_set(self):
        cancel = threading.Event()
        result = []

        def request():
            try:
                self.client.post(
                    self.url,
                    b"{}",
                    {"Content-Type": "application/json"},
                    30,
                    1024,
                    cancel,
                )
            except Exception as error:  # captured for assertion on the caller thread
                result.append(error)

        request_thread = threading.Thread(target=request)
        request_thread.start()
        self.assertTrue(BlockingHandler.request_received.wait(2), "request never reached provider")
        started = time.monotonic()
        cancel.set()
        request_thread.join(timeout=2)
        elapsed = time.monotonic() - started
        self.assertFalse(request_thread.is_alive(), "cancelled request remained blocked")
        self.assertLess(elapsed, 1)
        self.assertEqual(len(result), 1)
        self.assertIsInstance(result[0], RequestCancelled)

    def test_non_cancelled_request_preserves_status_headers_and_body(self):
        BlockingHandler.release_response.set()
        response = self.client.post(
            self.url,
            b"{}",
            {"Content-Type": "application/json"},
            2,
            1024,
        )
        self.assertEqual(response.status, 200)
        self.assertEqual(response.headers.get_content_type(), "application/json")
        self.assertEqual(json.loads(response.body), {"ok": True})

    def test_cancelled_during_connect_never_sends_the_provider_request(self):
        self.assert_cancel_during_connect(self.url, self.client)

    def test_cancelled_during_https_connect_never_sends_the_provider_request(self):
        with tempfile.TemporaryDirectory(prefix="browsercloud-cancel-tls-") as directory:
            certificate = pathlib.Path(directory) / "certificate.pem"
            key = pathlib.Path(directory) / "key.pem"
            subprocess.run(
                ["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "1",
                 "-subj", "/CN=localhost", "-addext", "subjectAltName=IP:127.0.0.1",
                 "-keyout", str(key), "-out", str(certificate)],
                check=True, timeout=10, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
            )
            key.chmod(0o600)
            server_context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
            server_context.load_cert_chain(certificate, key)
            server = CancellationTestServer(("127.0.0.1", 0), BlockingHandler)
            server.socket = server_context.wrap_socket(server.socket, server_side=True)
            server_thread = threading.Thread(target=server.serve_forever, daemon=True)
            server_thread.start()
            try:
                client = CancellableHttpClient(ssl.create_default_context(cafile=str(certificate)))
                self.assert_cancel_during_connect(
                    f"https://127.0.0.1:{server.server_port}/v1/responses", client
                )
            finally:
                server.shutdown()
                server.server_close()
                server_thread.join(timeout=2)

    def assert_cancel_during_connect(self, url, client):
        cancel = threading.Event()
        connecting = threading.Event()
        release_connect = threading.Event()
        result = []
        BlockingHandler.release_response.set()
        import socket

        create_connection = socket.create_connection
        thread_type = threading.Thread

        def delayed_connect(*args, **kwargs):
            connecting.set()
            if not release_connect.wait(2):
                raise TimeoutError("owned connection barrier expired")
            return create_connection(*args, **kwargs)

        def scheduled_thread(*args, **kwargs):
            # A cancellation watcher can be descheduled while connect returns. The caller must
            # enforce cancellation itself before sending headers/body, without relying on it.
            if kwargs.get("name") == "model-http-cancellation":
                return Mock()
            return thread_type(*args, **kwargs)

        def request():
            try:
                client.post(url, b"{}", {"Content-Type": "application/json"}, 2, 1024, cancel)
            except Exception as error:
                result.append(error)

        request_thread = thread_type(target=request)
        with patch("cancellable_http.socket.create_connection", side_effect=delayed_connect), patch(
            "cancellable_http.threading.Thread", side_effect=scheduled_thread
        ):
            request_thread.start()
            try:
                self.assertTrue(connecting.wait(2), "owned request never started connecting")
                cancel.set()
            finally:
                release_connect.set()
                request_thread.join(timeout=3)
        self.assertFalse(request_thread.is_alive(), "owned request did not finish")
        self.assertEqual(len(result), 1)
        self.assertIsInstance(result[0], RequestCancelled)
        self.assertFalse(BlockingHandler.request_received.is_set(), "cancelled request reached provider")

    def assert_provider_cancels(self, invoke):
        cancel = threading.Event()
        result = []

        def request():
            try:
                invoke(cancel)
            except Exception as error:  # captured for assertion on the caller thread
                result.append(error)

        request_thread = threading.Thread(target=request)
        request_thread.start()
        self.assertTrue(BlockingHandler.request_received.wait(2), "request never reached provider")
        cancel.set()
        request_thread.join(timeout=2)
        self.assertFalse(request_thread.is_alive(), "provider request ignored cancellation")
        self.assertEqual(len(result), 1)
        self.assertIsInstance(result[0], WorkerError)
        self.assertEqual(result[0].code, "MODEL_PROVIDER_REQUEST_CANCELLED")

    def test_all_external_model_workers_abort_their_provider_call(self):
        providers = (
            (
                OpenAIResponsesReviewer(
                    self.url, "secret", None, "model", "revision", 128, timeout_seconds=30
                ),
                lambda provider, cancel: provider.review({"taskId": "agt_test"}, cancel),
            ),
            (
                OpenAIResponsesOutcomeVerifier(
                    self.url, "secret", None, "model", "revision", 128, timeout_seconds=30
                ),
                lambda provider, cancel: provider.review({"taskId": "agt_test"}, cancel),
            ),
            (
                ScreenshotVisionProvider(
                    self.url, "secret", None, "model", "revision", 128, timeout_seconds=30
                ),
                lambda provider, cancel: provider.analyze(
                    {"challengeType": "SINGLE_CLICK"}, b"\xff\xd8\xfffixture", cancel
                ),
            ),
        )
        for provider, invoke in providers:
            with self.subTest(provider=type(provider).__name__):
                BlockingHandler.request_received.clear()
                BlockingHandler.release_response.clear()
                self.assert_provider_cancels(lambda cancel: invoke(provider, cancel))
                BlockingHandler.release_response.set()

    def test_reviewer_and_outcome_lease_loss_abort_real_provider_socket(self):
        cases = (
            (
                ReviewerLoop,
                OpenAIResponsesReviewer(
                    self.url, "secret", None, "model", "revision", 128, timeout_seconds=30
                ),
                "reviewPayload",
                "AGENT_REVIEW_LEASE_LOST",
            ),
            (
                OutcomeVerifierLoop,
                OpenAIResponsesOutcomeVerifier(
                    self.url, "secret", None, "model", "revision", 128, timeout_seconds=30
                ),
                "outcomePayload",
                "AGENT_OUTCOME_LEASE_LOST",
            ),
        )
        for loop_type, provider, payload_name, expected_error in cases:
            with self.subTest(loop=loop_type.__name__):
                BlockingHandler.request_received.clear()
                BlockingHandler.release_response.clear()
                client = Mock(deployment_id="deployment")
                client.claim.return_value = {
                    "claimToken": "x" * 43,
                    "job": {
                        "deployment": {
                            "deploymentId": "deployment",
                            "providerType": "OPENAI_RESPONSES",
                            "modelName": "model",
                            "modelRevision": "revision",
                            "maximumOutputTokens": 128,
                        }
                    },
                    payload_name: {"taskId": "agt_test"},
                }

                def transition(_claim, action, *_args):
                    if action == "heartbeat":
                        self.assertTrue(
                            BlockingHandler.request_received.wait(2),
                            "heartbeat ran before provider request",
                        )
                        raise WorkerError("OWNER_INACTIVE", retryable=False)
                    return {}

                client.transition.side_effect = transition
                loop = loop_type(client, provider, 0.1, 1)
                loop.heartbeat_seconds = 0.01
                started = time.monotonic()
                with self.assertRaisesRegex(WorkerError, expected_error):
                    loop.run_once()
                self.assertLess(time.monotonic() - started, 1)
                self.assertEqual(
                    [call.args[1] for call in client.transition.call_args_list],
                    ["start", "heartbeat"],
                )
                BlockingHandler.release_response.set()


if __name__ == "__main__":
    unittest.main()
