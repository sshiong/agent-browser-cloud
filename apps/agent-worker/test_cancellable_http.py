#!/usr/bin/env python3

import json
import pathlib
import ssl
import sys
import threading
import time
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from unittest.mock import Mock


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


class CancellableHttpClientTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), BlockingHandler)
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
