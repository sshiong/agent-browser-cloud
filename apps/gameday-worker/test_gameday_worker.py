#!/usr/bin/env python3

import contextlib
import importlib.util
import io
import json
import pathlib
import stat
import sys
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


ROOT = pathlib.Path(__file__).parent


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, ROOT / filename)
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


worker = load("gameday_worker", "gameday_worker.py")
runner = load("gameday_runner", "gameday_runner.py")


class ControllerHandler(BaseHTTPRequestHandler):
    faulted = False
    requests = []

    def log_message(self, *_args):
        return

    def respond(self, status, payload):
        raw = json.dumps(payload).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def do_GET(self):
        self.__class__.requests.append(("GET", self.path, self.headers.get("Authorization")))
        if self.path == "/health":
            self.respond(503 if self.__class__.faulted else 200, {"faulted": self.__class__.faulted})
        elif self.path == "/evidence":
            self.respond(
                200,
                {
                    "dataLossRecords": 0,
                    "staleOperationCount": 0,
                    "userImpactCount": 1,
                    "manualSteps": 0,
                    "runbookAccuracyPercent": 100,
                },
            )
        else:
            self.respond(404, {"code": "NOT_FOUND"})

    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0"))
        body = json.loads(self.rfile.read(length))
        self.__class__.requests.append(
            ("POST", self.path, self.headers.get("Authorization"), body)
        )
        if self.path == "/inject":
            self.__class__.faulted = True
            self.respond(200, {"state": "FAULTED"})
        elif self.path == "/recover":
            self.__class__.faulted = False
            self.respond(200, {"state": "RECOVERING"})
        else:
            self.respond(404, {"code": "NOT_FOUND"})


class ControlPlaneHandler(BaseHTTPRequestHandler):
    requests = []
    claim_available = True

    def log_message(self, *_args):
        return

    def respond(self, status, payload=None):
        raw = b"" if payload is None else json.dumps(payload).encode()
        self.send_response(status)
        if raw:
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        if raw:
            self.wfile.write(raw)

    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0"))
        body = json.loads(self.rfile.read(length)) if length else None
        self.__class__.requests.append(
            {
                "path": self.path,
                "body": body,
                "tenant": self.headers.get("X-Tenant-Id"),
                "actor": self.headers.get("X-Actor-Id"),
                "roles": self.headers.get("X-Roles"),
                "authorization": self.headers.get("Authorization"),
            }
        )
        if self.path.endswith("recovery-gameday-jobs:claim"):
            if not self.__class__.claim_available:
                self.respond(204)
                return
            self.__class__.claim_available = False
            self.respond(
                200,
                {
                    "claimToken": "a" * 43,
                    "claimEpoch": 1,
                    "leaseExpiresAt": "2026-08-09T00:01:00Z",
                    "recoveryOnly": False,
                    "gameDay": {
                        "gameDayId": "gameday_1234567890abcdefghij",
                        "scenario": "OBJECT_STORAGE_UNAVAILABLE",
                        "sourceRegion": "source",
                        "targetRegion": "target",
                        "environment": "TEST",
                        "maximumDurationSeconds": 30,
                        "job": {
                            "scenarioCode": "OBJECT_STORAGE_UNAVAILABLE",
                            "state": "CLAIMED",
                        },
                    },
                },
            )
        elif self.path.endswith(":heartbeat"):
            self.respond(200, {"state": "EXECUTING", "abortRequested": False})
        elif self.path.endswith(":complete"):
            self.respond(200, {"state": "PASSED"})
        elif self.path.endswith(":fail"):
            self.respond(200, {"state": "FAILED"})
        else:
            self.respond(200, {"state": "EXECUTING"})


class GameDayWorkerTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.controller = ThreadingHTTPServer(("127.0.0.1", 0), ControllerHandler)
        cls.control_plane = ThreadingHTTPServer(("127.0.0.1", 0), ControlPlaneHandler)
        cls.threads = [
            threading.Thread(target=cls.controller.serve_forever, daemon=True),
            threading.Thread(target=cls.control_plane.serve_forever, daemon=True),
        ]
        for thread in cls.threads:
            thread.start()
        cls.controller_url = f"http://127.0.0.1:{cls.controller.server_port}"
        cls.control_plane_url = f"http://127.0.0.1:{cls.control_plane.server_port}"

    @classmethod
    def tearDownClass(cls):
        cls.controller.shutdown()
        cls.control_plane.shutdown()
        cls.controller.server_close()
        cls.control_plane.server_close()
        for thread in cls.threads:
            thread.join(timeout=2)

    def setUp(self):
        ControllerHandler.faulted = False
        ControllerHandler.requests.clear()
        ControlPlaneHandler.requests.clear()
        ControlPlaneHandler.claim_available = True
        runner.CANCELLED.clear()

    def files(self, root):
        catalog = root / "catalog.json"
        catalog.write_text(
            json.dumps(
                {
                    "version": 1,
                    "scenarios": {
                        "OBJECT_STORAGE_UNAVAILABLE": {
                            "environment": "TEST",
                            "controllerBaseUrl": self.controller_url,
                            "injectPath": "/inject",
                            "recoverPath": "/recover",
                            "healthPath": "/health",
                            "evidencePath": "/evidence",
                            "healthyStatus": 200,
                            "faultStatus": 503,
                            "observationSeconds": 0,
                        }
                    },
                }
            ),
            encoding="utf-8",
        )
        control_token = root / "control-token"
        control_token.write_text("control-secret\n", encoding="utf-8")
        control_token.chmod(0o600)
        controller_token = root / "controller-token"
        controller_token.write_text("controller-secret\n", encoding="utf-8")
        controller_token.chmod(0o600)
        return catalog, control_token, controller_token

    def test_secret_permissions_fail_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            path = pathlib.Path(directory, "token")
            path.write_text("secret\n", encoding="utf-8")
            path.chmod(stat.S_IRUSR | stat.S_IWUSR)
            self.assertEqual(worker.read_secret(str(path)), "secret")
            path.chmod(stat.S_IRUSR | stat.S_IWUSR | stat.S_IROTH)
            with self.assertRaisesRegex(ValueError, "0600"):
                worker.read_secret(str(path))
            with self.assertRaisesRegex(ValueError, "0600"):
                runner.read_token(str(path))

    def test_runner_injects_observes_recovers_and_measures_real_fixture(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            catalog, _, controller_token = self.files(root)
            namespace = type(
                "Args",
                (),
                {
                    "catalog_file": str(catalog),
                    "controller_token_file": str(controller_token),
                    "ca_file": None,
                    "game_day_id": "gameday_1234567890abcdefghij",
                    "scenario_code": "OBJECT_STORAGE_UNAVAILABLE",
                    "environment": "TEST",
                    "source_region": "source",
                    "target_region": "target",
                    "maximum_duration_seconds": 30,
                    "recovery_only": False,
                },
            )()
            output = io.StringIO()
            with contextlib.redirect_stdout(output):
                result = runner.run(namespace)
        stages = [json.loads(line)["stage"] for line in output.getvalue().splitlines()]
        self.assertEqual(
            stages,
            ["INJECTING", "FAULT_INJECTED", "OBSERVING", "RECOVERING", "VALIDATING"],
        )
        self.assertTrue(result["recoveryConfirmed"])
        self.assertEqual(result["metrics"]["dataLossRecords"], 0)
        self.assertRegex(result["runnerEvidenceHash"], r"^sha256:[a-f0-9]{64}$")
        self.assertFalse(ControllerHandler.faulted)
        self.assertTrue(
            all(request[2] == "Bearer controller-secret" for request in ControllerHandler.requests)
        )

    def test_worker_claims_fixed_catalog_and_submits_result_without_parent_secrets(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            catalog, control_token, controller_token = self.files(root)
            old_argv = sys.argv
            sys.argv = [
                "gameday_worker.py",
                "--control-plane-url",
                self.control_plane_url,
                "--control-plane-token-file",
                str(control_token),
                "--controller-token-file",
                str(controller_token),
                "--catalog-file",
                str(catalog),
                "--runner",
                str(ROOT / "gameday_runner.py"),
                "--worker-id",
                "gameday-worker-test",
                "--environment",
                "test",
                "--heartbeat-seconds",
                "5",
                "--once",
            ]
            try:
                self.assertEqual(worker.main(), 0)
            finally:
                sys.argv = old_argv
        claim = ControlPlaneHandler.requests[0]
        self.assertEqual(claim["tenant"], "platform-control")
        self.assertEqual(claim["actor"], "gameday-worker-test")
        self.assertEqual(claim["roles"], "GAMEDAY_WORKER")
        self.assertEqual(claim["authorization"], "Bearer control-secret")
        self.assertEqual(claim["body"]["scenarioCodes"], ["OBJECT_STORAGE_UNAVAILABLE"])
        actions = [request["path"].rsplit(":", 1)[-1] for request in ControlPlaneHandler.requests]
        self.assertEqual(actions[:2], ["claim", "start"])
        self.assertIn("heartbeat", actions)
        self.assertEqual(actions[-1], "complete")
        stages = [
            request["body"]["stage"]
            for request in ControlPlaneHandler.requests
            if request["path"].endswith(":stage")
        ]
        self.assertEqual(
            stages,
            ["INJECTING", "FAULT_INJECTED", "OBSERVING", "RECOVERING", "VALIDATING"],
        )
        complete = ControlPlaneHandler.requests[-1]["body"]["result"]
        self.assertTrue(complete["recoveryConfirmed"])
        self.assertEqual(complete["dataLossRecords"], 0)

    def test_empty_catalog_does_not_claim(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            catalog = root / "catalog.json"
            catalog.write_text('{"version":1,"scenarios":{}}', encoding="utf-8")
            control_token = root / "control-token"
            control_token.write_text("control-secret", encoding="utf-8")
            control_token.chmod(0o600)
            controller_token = root / "controller-token"
            controller_token.write_text("controller-secret", encoding="utf-8")
            controller_token.chmod(0o600)
            old_argv = sys.argv
            sys.argv = [
                "gameday_worker.py",
                "--control-plane-url",
                self.control_plane_url,
                "--control-plane-token-file",
                str(control_token),
                "--controller-token-file",
                str(controller_token),
                "--catalog-file",
                str(catalog),
                "--runner",
                str(ROOT / "gameday_runner.py"),
                "--worker-id",
                "gameday-worker-test",
                "--environment",
                "test",
                "--once",
            ]
            try:
                self.assertEqual(worker.main(), 0)
            finally:
                sys.argv = old_argv
        self.assertEqual(ControlPlaneHandler.requests, [])


if __name__ == "__main__":
    unittest.main()
