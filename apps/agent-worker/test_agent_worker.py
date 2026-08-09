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


MODULE_PATH = pathlib.Path(__file__).with_name("agent_worker.py")
SPEC = importlib.util.spec_from_file_location("agent_worker", MODULE_PATH)
worker = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = worker
SPEC.loader.exec_module(worker)


class FixtureHandler(BaseHTTPRequestHandler):
    requests = []
    claim_available = True

    def log_message(self, *_args):
        return

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", "0"))))
        self.__class__.requests.append(
            {
                "path": self.path,
                "body": body,
                "tenant": self.headers.get("X-Tenant-Id"),
                "actor": self.headers.get("X-Actor-Id"),
                "roles": self.headers.get("X-Roles"),
            }
        )
        if self.path.endswith(":claim"):
            if not self.__class__.claim_available:
                self.send_response(204)
                self.end_headers()
                return
            document = {
                "claimToken": "a" * 43,
                "claimEpoch": 1,
                "leaseExpiresAt": "2026-08-09T00:01:00Z",
                "job": {
                    "jobId": "ajob_1234567890abcdefghij",
                    "taskId": "agt_1234567890abcdef",
                    "protocolVersion": "agent-worker/v1",
                    "state": "CLAIMED",
                },
            }
        else:
            state = "EXECUTING"
            if self.path.endswith(":drive"):
                state = "COMMITTED"
            document = {"jobId": "ajob_1234567890abcdefghij", "state": state}
        raw = json.dumps(document).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)


class AgentWorkerTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), FixtureHandler)
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()
        cls.origin = f"http://127.0.0.1:{cls.server.server_port}"

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.thread.join(timeout=2)

    def setUp(self):
        FixtureHandler.requests.clear()
        FixtureHandler.claim_available = True
        self.client = worker.ControlPlaneClient(
            worker.control_plane_origin(self.origin, "test"),
            "token",
            None,
            "test",
            "agent-worker-test",
        )

    def test_secret_permissions_are_fail_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            path = pathlib.Path(directory, "token")
            path.write_text("secret\n", encoding="utf-8")
            path.chmod(0o600)
            self.assertEqual(worker.read_secret(str(path.resolve())), "secret")
            path.chmod(0o644)
            with self.assertRaisesRegex(ValueError, "0600"):
                worker.read_secret(str(path.resolve()))

    def test_claim_uses_only_fixed_protocol_capability_and_worker_role(self):
        claim = self.client.claim()
        self.assertEqual(claim["job"]["taskId"], "agt_1234567890abcdef")
        request = FixtureHandler.requests[-1]
        self.assertEqual(
            request["body"],
            {"protocolVersion": "agent-worker/v1", "capabilities": {"task-drive-v1": True}},
        )
        self.assertEqual(request["tenant"], "platform-control")
        self.assertEqual(request["actor"], "agent-worker-test")
        self.assertEqual(request["roles"], "AGENT_WORKER")

    def test_loop_only_starts_and_drives_opaque_job(self):
        loop = worker.WorkerLoop(self.client, poll_seconds=0.1, heartbeat_seconds=5)
        self.assertTrue(loop.run_once())
        self.assertEqual(
            [entry["path"].rsplit(":", 1)[-1] for entry in FixtureHandler.requests],
            ["claim", "start", "drive"],
        )
        for entry in FixtureHandler.requests:
            self.assertNotIn("plan", entry["body"])
            self.assertNotIn("prompt", entry["body"])
            self.assertNotIn("command", entry["body"])

    def test_no_job_is_normal_idle_state(self):
        FixtureHandler.claim_available = False
        self.assertFalse(worker.WorkerLoop(self.client, 0.1, 5).run_once())

    def test_production_rejects_plain_http(self):
        with self.assertRaisesRegex(ValueError, "requires HTTPS"):
            worker.control_plane_origin("http://127.0.0.1:8080", "production")


if __name__ == "__main__":
    unittest.main()
