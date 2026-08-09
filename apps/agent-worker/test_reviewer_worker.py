#!/usr/bin/env python3

import importlib.util
import json
import pathlib
import sys
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


MODULE_DIR = pathlib.Path(__file__).parent
sys.path.insert(0, str(MODULE_DIR))
MODULE_PATH = MODULE_DIR / "reviewer_worker.py"
SPEC = importlib.util.spec_from_file_location("reviewer_worker", MODULE_PATH)
reviewer = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = reviewer
SPEC.loader.exec_module(reviewer)


class ControlPlaneFixture(BaseHTTPRequestHandler):
    requests = []

    def log_message(self, *_args):
        return

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", "0"))))
        self.__class__.requests.append(
            {
                "path": self.path,
                "body": body,
                "roles": self.headers.get("X-Roles"),
            }
        )
        if self.path.endswith(":claim"):
            document = {
                "claimToken": "a" * 43,
                "claimEpoch": 1,
                "leaseExpiresAt": "2026-08-09T00:01:00Z",
                "job": {
                    "jobId": "rjob_1234567890abcdefghij",
                    "reviewId": "rev_1234567890abcdefghij",
                    "taskId": "agt_1234567890abcdef",
                    "protocolVersion": "reviewer-worker/v1",
                    "state": "CLAIMED",
                    "deployment": {
                        "deploymentId": "reviewer-test-v1",
                        "providerType": "OPENAI_RESPONSES",
                        "modelName": "reviewer-model",
                        "modelRevision": "model-revision-v1",
                        "dataPolicy": "REDACTED_TASK_PLAN",
                        "maximumOutputTokens": 512,
                    },
                },
                "reviewPayload": {
                    "taskId": "agt_1234567890abcdef",
                    "goal": "read the public dashboard",
                    "riskClass": "R0_READ_ONLY",
                    "allowedDomains": ["example.com"],
                    "maximumActions": 5,
                    "replanBudget": 0,
                    "steps": [
                        {
                            "stepId": "step_123",
                            "toolId": "GET_CURRENT_STATE",
                            "riskClass": "R0_READ_ONLY",
                            "targetOrigin": None,
                            "targetRefHash": None,
                            "dataClass": None,
                            "payloadLength": None,
                            "requiredConfirmation": False,
                            "strategy": "SEMANTIC_DOM",
                            "requiredStateQuality": "COMPLETE",
                            "verification": "STATE_VERSION_BOUND",
                        }
                    ],
                    "planHash": "b" * 64,
                    "dataPolicy": "REDACTED_TASK_PLAN",
                },
            }
        else:
            state = "EXECUTING"
            if self.path.endswith(":complete"):
                state = "APPROVED"
            document = {"jobId": "rjob_1234567890abcdefghij", "state": state}
        raw = json.dumps(document).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)


class ModelFixture(BaseHTTPRequestHandler):
    requests = []
    response_model = "reviewer-model"

    def log_message(self, *_args):
        return

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", "0"))))
        self.__class__.requests.append(
            {
                "path": self.path,
                "body": body,
                "authorization": self.headers.get("Authorization"),
            }
        )
        document = {
            "id": "resp_reviewer_123",
            "model": self.__class__.response_model,
            "output": [
                {
                    "type": "message",
                    "content": [
                        {
                            "type": "output_text",
                            "text": json.dumps(
                                {
                                    "decision": "APPROVE",
                                    "reasonCodes": ["SAFE"],
                                    "confidence": 0.97,
                                }
                            ),
                        }
                    ],
                }
            ],
            "usage": {"input_tokens": 120, "output_tokens": 18},
        }
        raw = json.dumps(document).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("x-request-id", "req_reviewer_123")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)


class ReviewerWorkerTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.control_plane = ThreadingHTTPServer(("127.0.0.1", 0), ControlPlaneFixture)
        cls.model = ThreadingHTTPServer(("127.0.0.1", 0), ModelFixture)
        cls.threads = [
            threading.Thread(target=cls.control_plane.serve_forever, daemon=True),
            threading.Thread(target=cls.model.serve_forever, daemon=True),
        ]
        for thread in cls.threads:
            thread.start()
        cls.control_plane_origin = f"http://127.0.0.1:{cls.control_plane.server_port}"
        cls.model_endpoint = f"http://127.0.0.1:{cls.model.server_port}/v1/responses"

    @classmethod
    def tearDownClass(cls):
        cls.control_plane.shutdown()
        cls.model.shutdown()
        cls.control_plane.server_close()
        cls.model.server_close()
        for thread in cls.threads:
            thread.join(timeout=2)

    def setUp(self):
        ControlPlaneFixture.requests.clear()
        ModelFixture.requests.clear()
        ModelFixture.response_model = "reviewer-model"
        self.client = reviewer.ReviewerControlPlaneClient(
            reviewer.control_plane_origin(self.control_plane_origin, "test"),
            "control-plane-token",
            None,
            "test",
            "reviewer-worker-test",
            "reviewer-test-v1",
            "model-revision-v1",
        )
        self.provider = reviewer.OpenAIResponsesReviewer(
            reviewer.fixed_model_endpoint(self.model_endpoint, "test", []),
            "provider-secret",
            None,
            "reviewer-model",
            "model-revision-v1",
            512,
        )

    def test_claim_uses_dedicated_role_and_pinned_deployment(self):
        claim = self.client.claim()
        self.assertEqual(claim["job"]["taskId"], "agt_1234567890abcdef")
        request = ControlPlaneFixture.requests[-1]
        self.assertEqual(request["roles"], "REVIEWER_WORKER")
        self.assertEqual(
            request["body"],
            {
                "protocolVersion": "reviewer-worker/v1",
                "capabilities": {"openai-responses-v1": True},
                "deploymentId": "reviewer-test-v1",
                "modelRevision": "model-revision-v1",
            },
        )

    def test_real_provider_call_and_complete_protocol_are_data_minimized(self):
        loop = reviewer.ReviewerLoop(self.client, self.provider, 0.1, 5)
        self.assertTrue(loop.run_once())
        self.assertEqual(
            [entry["path"].rsplit(":", 1)[-1] for entry in ControlPlaneFixture.requests],
            ["claim", "start", "complete"],
        )
        model_request = ModelFixture.requests[-1]
        self.assertEqual(model_request["path"], "/v1/responses")
        self.assertEqual(model_request["authorization"], "Bearer provider-secret")
        serialized = json.dumps(model_request["body"])
        self.assertNotIn("capabilityToken", serialized)
        self.assertNotIn("sealedPayload", serialized)
        self.assertNotIn("control-plane-token", serialized)
        completion = ControlPlaneFixture.requests[-1]["body"]
        self.assertEqual(completion["decision"], "APPROVE")
        self.assertEqual(completion["reasonCodes"], ["SAFE"])
        self.assertEqual(completion["inputTokens"], 120)
        self.assertEqual(completion["outputTokens"], 18)
        self.assertNotIn("provider-secret", json.dumps(completion))

    def test_claim_rejects_capability_or_sealed_payload_leak(self):
        self.assertTrue(reviewer.contains_forbidden_key({"nested": {"sealedPayload": "x"}}))
        self.assertTrue(reviewer.contains_forbidden_key({"capabilityToken": "x"}))

    def test_provider_response_must_match_the_pinned_model(self):
        ModelFixture.response_model = "unexpected-model"
        with self.assertRaisesRegex(reviewer.WorkerError, "MODEL_PROVIDER_MODEL_MISMATCH"):
            self.provider.review({"taskId": "agt_1234567890abcdef"})

    def test_production_requires_https_and_explicit_host_allowlist(self):
        with self.assertRaisesRegex(ValueError, "HTTPS"):
            reviewer.fixed_model_endpoint(self.model_endpoint, "production", ["127.0.0.1"])
        with self.assertRaisesRegex(ValueError, "explicitly allowed"):
            reviewer.fixed_model_endpoint(
                "https://models.example.com/v1/responses", "production", ["other.example.com"]
            )
        self.assertEqual(
            reviewer.fixed_model_endpoint(
                "https://models.example.com/v1/responses", "production", ["models.example.com"]
            ),
            "https://models.example.com/v1/responses",
        )


if __name__ == "__main__":
    unittest.main()
