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


MODULE_PATH = pathlib.Path(__file__).with_name("validation_worker.py")
SPEC = importlib.util.spec_from_file_location("validation_worker", MODULE_PATH)
worker = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = worker
SPEC.loader.exec_module(worker)

RUNNER_MODULE_PATH = pathlib.Path(__file__).with_name("runtime_validation_runner.py")
RUNNER_SPEC = importlib.util.spec_from_file_location(
    "runtime_validation_runner", RUNNER_MODULE_PATH
)
runtime_runner = importlib.util.module_from_spec(RUNNER_SPEC)
sys.modules[RUNNER_SPEC.name] = runtime_runner
RUNNER_SPEC.loader.exec_module(runtime_runner)


class FixtureHandler(BaseHTTPRequestHandler):
    requests = []
    claim_available = True

    def log_message(self, *_args):
        return

    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0"))
        body = json.loads(self.rfile.read(length))
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
        if self.path.endswith("runtime-validation-jobs:claim"):
            if not self.__class__.claim_available:
                self.send_response(204)
                self.end_headers()
                return
            document = {
                "claimToken": "a" * 43,
                "claimEpoch": 2,
                "leaseExpiresAt": "2026-08-09T00:01:00Z",
                "validation": {
                    "validationId": "val_1234567890abcdefghij",
                    "buildId": "runtime_test",
                    "suiteVersion": "suite-v1",
                    "environmentDigest": "sha256:" + "a" * 64,
                    "replayDatasetId": "dataset-v1",
                    "persona": "default",
                    "job": {"state": "CLAIMED"},
                },
            }
        elif self.path.endswith(":complete"):
            document = {"validationId": "val_1234567890abcdefghij", "state": "PASSED"}
        elif self.path.endswith(":fail"):
            document = {"validationId": "val_1234567890abcdefghij", "state": "RUNNING"}
        else:
            document = {"validationId": "val_1234567890abcdefghij", "state": "EXECUTING"}
        raw = json.dumps(document).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)


class ValidationWorkerTest(unittest.TestCase):
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
        FixtureHandler.claim_available = True
        capabilities = worker.WorkerCapabilities(
            "chromium", ("128.0",), "linux", "amd64", {"cdp": True, "replay": True}
        )
        self.client = worker.ControlPlaneClient(
            worker.HttpTransport(self.base_url, allow_http=True),
            "control-secret",
            capabilities,
            ("platform-control", "validation-worker-test"),
        )

    def test_secret_file_accepts_only_private_or_dedicated_group_read_permissions(self):
        with tempfile.TemporaryDirectory() as directory:
            path = pathlib.Path(directory, "token")
            path.write_text("short-lived-token\n", encoding="utf-8")
            path.chmod(stat.S_IRUSR | stat.S_IWUSR)
            self.assertEqual(worker.read_secret_file(path), "short-lived-token")
            path.chmod(stat.S_IRUSR | stat.S_IWUSR | stat.S_IRGRP)
            self.assertEqual(worker.read_secret_file(path), "short-lived-token")
            path.chmod(stat.S_IRUSR | stat.S_IWUSR | stat.S_IRGRP | stat.S_IWGRP)
            with self.assertRaisesRegex(worker.WorkerError, "SECRET_FILE_PERMISSIONS_TOO_BROAD"):
                worker.read_secret_file(path)
            path.chmod(stat.S_IRUSR | stat.S_IWUSR | stat.S_IROTH)
            with self.assertRaisesRegex(worker.WorkerError, "SECRET_FILE_PERMISSIONS_TOO_BROAD"):
                worker.read_secret_file(path)

    def test_claim_uses_fixed_least_privilege_identity_and_capability_matrix(self):
        claimed = self.client.claim()
        self.assertEqual(claimed.validation_id, "val_1234567890abcdefghij")
        request = FixtureHandler.requests[-1]
        self.assertEqual(request["tenant"], "platform-control")
        self.assertEqual(request["actor"], "validation-worker-test")
        self.assertEqual(request["roles"], "VALIDATION_WORKER")
        self.assertEqual(request["authorization"], "Bearer control-secret")
        self.assertEqual(request["body"]["browserVersions"], ["128.0"])
        self.assertEqual(request["body"]["capabilities"], {"cdp": True, "replay": True})

    def test_no_matching_job_is_not_an_error(self):
        FixtureHandler.claim_available = False
        self.assertIsNone(self.client.claim())

    def test_fixed_runner_completes_claim_without_leaking_parent_secrets(self):
        with tempfile.TemporaryDirectory() as directory:
            runner_path = pathlib.Path(directory, "runner")
            runner_path.write_text(
                "#!/usr/bin/env python3\n"
                "import json,os,sys\n"
                "job=json.load(sys.stdin)\n"
                "assert 'CONTROL_PLANE_TOKEN' not in os.environ\n"
                "print(json.dumps({'requiredTests':1,'requiredFailures':0,"
                "'optionalTests':0,'optionalFailures':0,"
                "'declaredCapabilities':{'cdp':True},"
                "'observedCapabilities':{'cdp':True},"
                "'optionalFailureCodes':[],'personaConsistent':True}))\n",
                encoding="utf-8",
            )
            runner_path.chmod(0o700)
            os.environ["CONTROL_PLANE_TOKEN"] = "must-not-leak"
            try:
                loop = worker.WorkerLoop(
                    self.client,
                    worker.FixedCommandRunner((str(runner_path),), 5),
                    heartbeat_seconds=1,
                    idle_seconds=0.1,
                )
                self.assertTrue(loop.run_once())
            finally:
                os.environ.pop("CONTROL_PLANE_TOKEN", None)
        self.assertEqual(
            [request["path"].rsplit(":", 1)[-1] for request in FixtureHandler.requests],
            ["claim", "start", "complete"],
        )
        complete = FixtureHandler.requests[-1]["body"]
        self.assertEqual(complete["claimToken"], "a" * 43)
        self.assertEqual(complete["result"]["requiredTests"], 1)

    def test_nonzero_runner_is_reported_as_retryable_failure(self):
        with tempfile.TemporaryDirectory() as directory:
            runner_path = pathlib.Path(directory, "runner")
            runner_path.write_text("#!/bin/sh\nexit 7\n", encoding="utf-8")
            runner_path.chmod(0o700)
            loop = worker.WorkerLoop(
                self.client,
                worker.FixedCommandRunner((str(runner_path),), 5),
                heartbeat_seconds=1,
                idle_seconds=0.1,
            )
            self.assertTrue(loop.run_once())
        failure = FixtureHandler.requests[-1]
        self.assertTrue(failure["path"].endswith(":fail"))
        self.assertEqual(failure["body"]["failureCode"], "RUNNER_EXIT_NONZERO")
        self.assertTrue(failure["body"]["retryable"])

    def test_runner_output_is_bounded_before_json_parsing(self):
        with tempfile.TemporaryDirectory() as directory:
            runner_path = pathlib.Path(directory, "runner")
            runner_path.write_text(
                "#!/usr/bin/env python3\nimport sys\nsys.stdout.write('x' * 8192)\n",
                encoding="utf-8",
            )
            runner_path.chmod(0o700)
            previous_limit = worker.MAX_RUNNER_OUTPUT_BYTES
            worker.MAX_RUNNER_OUTPUT_BYTES = 1024
            try:
                loop = worker.WorkerLoop(
                    self.client,
                    worker.FixedCommandRunner((str(runner_path),), 5),
                    heartbeat_seconds=1,
                    idle_seconds=0.1,
                )
                self.assertTrue(loop.run_once())
            finally:
                worker.MAX_RUNNER_OUTPUT_BYTES = previous_limit
        failure = FixtureHandler.requests[-1]
        self.assertTrue(failure["path"].endswith(":fail"))
        self.assertEqual(failure["body"]["failureCode"], "RUNNER_OUTPUT_TOO_LARGE")
        self.assertFalse(failure["body"]["retryable"])

    def test_result_validation_fails_closed(self):
        with self.assertRaisesRegex(worker.WorkerError, "RUNNER_RESULT_COUNTS_INVALID"):
            worker.validate_result({"requiredTests": 0})

    def test_bundled_runner_executes_exact_browser_matrix_cell(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            browser = root / "chromium"
            browser.write_text(
                "#!/bin/sh\n"
                "if [ \"$1\" = \"--version\" ]; then echo 'Chromium 128.0.6613.84'; exit 0; fi\n"
                "echo '<html><body>Account ready</body></html>'\n",
                encoding="utf-8",
            )
            browser.chmod(0o700)
            catalog = root / "suites.json"
            catalog.write_text(
                json.dumps(
                    {
                        "datasets": {
                            "dataset-v1": {
                                "suiteVersion": "suite-v1",
                                "persona": "default",
                                "declaredCapabilities": {"navigate": True},
                                "cases": [
                                    {
                                        "id": "NAVIGATE_ACCOUNT",
                                        "required": True,
                                        "capability": "navigate",
                                        "url": "https://example.test/account",
                                        "expectedText": "Account ready",
                                    }
                                ],
                            }
                        }
                    }
                ),
                encoding="utf-8",
            )
            args = runtime_runner.build_parser().parse_args(
                ["--browser", str(browser), "--suite-catalog", str(catalog)]
            )
            result = runtime_runner.execute(
                args,
                {
                    "replayDatasetId": "dataset-v1",
                    "suiteVersion": "suite-v1",
                    "persona": "default",
                    "job": {"browserVersion": "128.0.6613.84"},
                },
            )
        self.assertEqual(result["requiredFailures"], 0)
        self.assertEqual(result["observedCapabilities"], {"navigate": True})


if __name__ == "__main__":
    unittest.main()
