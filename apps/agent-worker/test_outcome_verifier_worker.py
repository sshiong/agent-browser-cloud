#!/usr/bin/env python3

import json
import pathlib
import sys
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


MODULE_DIR = pathlib.Path(__file__).parent
sys.path.insert(0, str(MODULE_DIR))
import outcome_verifier_worker as outcome


class ControlPlaneFixture(BaseHTTPRequestHandler):
    requests = []

    def log_message(self, *_args):
        return

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", "0"))))
        self.__class__.requests.append(
            {"path": self.path, "body": body, "roles": self.headers.get("X-Roles")}
        )
        if self.path.endswith(":claim"):
            document = {
                "claimToken": "a" * 43,
                "claimEpoch": 1,
                "leaseExpiresAt": "2026-09-09T00:01:00Z",
                "job": {
                    "jobId": "ojob_1234567890abcdefghij",
                    "verificationId": "out_1234567890abcdefghij",
                    "taskId": "agt_1234567890abcdef",
                    "protocolVersion": "outcome-verifier-worker/v1",
                    "state": "CLAIMED",
                    "deployment": {
                        "deploymentId": "outcome-test-v1",
                        "providerType": "OPENAI_RESPONSES",
                        "modelName": "outcome-model",
                        "modelRevision": "model-revision-v1",
                        "dataPolicy": "REDACTED_FINAL_STATE",
                        "maximumOutputTokens": 512,
                    },
                },
                "outcomePayload": {
                    "taskId": "agt_1234567890abcdef",
                    "goal": "save settings",
                    "riskClass": "R2_DATA_CHANGE",
                    "allowedDomains": ["example.com"],
                    "expectedOutcomes": [{
                        "outcomeId": "saved-status",
                        "type": "TARGET_PRESENT",
                        "role": "status",
                        "expectedValueHash": "e" * 64,
                    }],
                    "expectedOutcomeEvaluations": [{
                        "outcomeId": "saved-status",
                        "type": "TARGET_PRESENT",
                        "status": "SATISFIED",
                        "reasonCode": "EXPECTED_TARGET_PRESENT",
                        "observedValueHash": None,
                    }],
                    "executionEvidence": [{
                        "stepOrdinal": 0,
                        "stepId": "step_123",
                        "toolId": "CLICK_TARGET",
                        "status": "VERIFIED",
                        "resultHash": "b" * 64,
                        "verification": "STATE_ADVANCED",
                    }],
                    "finalState": {
                        "stateVersion": 8,
                        "targetRevision": 9,
                        "stateHash": "c" * 64,
                        "url": "https://example.com/settings",
                        "title": "Settings saved",
                        "stateQuality": "COMPLETE",
                        "documentReadyState": "complete",
                        "networkQuietMillis": 1000,
                        "networkEvidenceFresh": True,
                        "observedAt": "2026-09-09T00:00:00Z",
                        "targets": [],
                    },
                    "evidenceHash": "d" * 64,
                    "dataPolicy": "REDACTED_FINAL_STATE",
                },
            }
        else:
            document = {
                "jobId": "ojob_1234567890abcdefghij",
                "state": "VERIFIED" if self.path.endswith(":complete") else "EXECUTING",
            }
        raw = json.dumps(document).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)


class ModelFixture(BaseHTTPRequestHandler):
    requests = []

    def log_message(self, *_args):
        return

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", "0"))))
        self.__class__.requests.append(body)
        document = {
            "id": "resp_outcome_123",
            "model": "outcome-model",
            "output_text": json.dumps({
                "decision": "VERIFIED",
                "reasonCodes": ["GOAL_SATISFIED"],
                "confidence": 0.97,
            }),
            "usage": {"input_tokens": 150, "output_tokens": 20},
        }
        raw = json.dumps(document).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)


class OutcomeVerifierWorkerTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.control = ThreadingHTTPServer(("127.0.0.1", 0), ControlPlaneFixture)
        cls.model = ThreadingHTTPServer(("127.0.0.1", 0), ModelFixture)
        cls.threads = [
            threading.Thread(target=cls.control.serve_forever, daemon=True),
            threading.Thread(target=cls.model.serve_forever, daemon=True),
        ]
        for thread in cls.threads:
            thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.control.shutdown()
        cls.model.shutdown()
        cls.control.server_close()
        cls.model.server_close()

    def setUp(self):
        ControlPlaneFixture.requests.clear()
        ModelFixture.requests.clear()
        self.client = outcome.OutcomeControlPlaneClient(
            f"http://127.0.0.1:{self.control.server_port}",
            "control-token", None, "test", "outcome-worker-test",
            "outcome-test-v1", "model-revision-v1",
        )
        self.provider = outcome.OpenAIResponsesOutcomeVerifier(
            f"http://127.0.0.1:{self.model.server_port}/v1/responses",
            "provider-secret", None, "outcome-model", "model-revision-v1", 512,
        )

    def test_end_to_end_protocol_is_independent_and_minimized(self):
        self.assertTrue(outcome.OutcomeVerifierLoop(self.client, self.provider, 0.1, 5).run_once())
        self.assertEqual(ControlPlaneFixture.requests[0]["roles"], "OUTCOME_VERIFIER_WORKER")
        self.assertEqual(
            [entry["path"].rsplit(":", 1)[-1] for entry in ControlPlaneFixture.requests],
            ["claim", "start", "complete"],
        )
        request = ModelFixture.requests[-1]
        self.assertEqual(request["text"]["format"]["name"], "agent_outcome_verification")
        serialized = json.dumps(request)
        for forbidden in (
            "capabilityToken", "sealedPayload", "elementId", "matchValue", "provider-secret"
        ):
            self.assertNotIn(forbidden, serialized)
        completion = ControlPlaneFixture.requests[-1]["body"]
        self.assertEqual(completion["decision"], "VERIFIED")
        self.assertEqual(completion["reasonCodes"], ["GOAL_SATISFIED"])

    def test_sensitive_fields_are_rejected_before_model_call(self):
        self.assertTrue(outcome.contains_forbidden_outcome_key({"targets": [{"value": "secret"}]}))
        self.assertTrue(outcome.contains_forbidden_outcome_key({"elementId": "stable"}))


if __name__ == "__main__":
    unittest.main()
