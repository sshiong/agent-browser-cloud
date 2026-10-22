import contextlib
import importlib.util
import io
import json
import pathlib
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

SOURCE = pathlib.Path(__file__).resolve().parents[1] / "integration/perception_readback.py"
spec = importlib.util.spec_from_file_location("perception_readback", SOURCE)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class ReadbackTest(unittest.TestCase):
    def setUp(self):
        self.requests = []
        self.snapshots = 0
        self.inspections = 0
        self.stale_count = 1
        self.rejection = (409, {"code": "AGENT_BROWSER_PERCEPTION_REJECTED",
                                "details": {"reason": "STATE_CURSOR_STALE"}})
        self.change_identity = False
        self.disconnect = False
        self.invalid_result = False
        owner = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *_):
                pass

            def send_json(self, status, value):
                body = json.dumps(value).encode()
                self.send_response(status)
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def do_GET(self):
                owner.requests.append(("GET", self.path))
                owner.snapshots += 1
                self.send_json(200, {"stateCursor": f"{owner.snapshots}:1:fixture",
                                     "state": {"activeTabId": "tab-fixture", "targets": [
                                         {"visible": True, "elementId": "other" if owner.change_identity and owner.snapshots > 1 else "element-fixture",
                                          "role": "button"}]}})

            def do_POST(self):
                owner.requests.append(("POST", self.path))
                owner.inspections += 1
                body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
                if owner.disconnect:
                    self.close_connection = True
                    return
                if owner.inspections <= owner.stale_count:
                    self.send_json(*owner.rejection)
                else:
                    self.send_json(200, {"stateCursor": body["stateCursor"], "targets": [
                        {"elementId": "element-fixture", "interactive": "false" if owner.invalid_result else True,
                         "inViewport": True, "occluded": False}]})

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever)
        self.thread.start()
        self.url = f"http://127.0.0.1:{self.server.server_port}/api/v1/sessions/session-fixture/agent-browser"

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()

    def run_read(self):
        with contextlib.redirect_stderr(io.StringIO()):
            return module.inspect_current_target(self.url, "tenant-fixture")

    def test_stale_rejection_refreshes_cursor_and_only_reads(self):
        self.assertEqual(self.run_read(), "button")
        self.assertEqual((self.snapshots, self.inspections), (2, 2))
        self.assertTrue(all(path.endswith(("/snapshot", "/inspect")) for _, path in self.requests))

    def test_other_conflict_never_retries_or_leaks_server_details(self):
        self.rejection = (409, {"code": "OTHER", "details": {"reason": "private-fixture-body"}})
        with self.assertRaisesRegex(module.PerceptionReadError, "reason=OTHER") as error:
            self.run_read()
        self.assertNotIn("private-fixture-body", str(error.exception))
        self.assertEqual(self.inspections, 1)

    def test_wrong_status_with_stale_reason_never_retries(self):
        self.rejection = (503, self.rejection[1])
        with self.assertRaisesRegex(module.PerceptionReadError, "status=503"):
            self.run_read()
        self.assertEqual(self.inspections, 1)

    def test_transport_failure_never_replays(self):
        self.disconnect = True
        with self.assertRaisesRegex(module.PerceptionReadError, "TRANSPORT_FAILED"):
            self.run_read()
        self.assertEqual(self.inspections, 1)

    def test_stale_budget_is_bounded(self):
        self.stale_count = 4
        with self.assertRaisesRegex(module.PerceptionReadError, "RETRY_EXHAUSTED"):
            self.run_read()
        self.assertEqual((self.snapshots, self.inspections), (3, 3))

    def test_changed_identity_prevents_another_inspect(self):
        self.change_identity = True
        with self.assertRaisesRegex(module.PerceptionReadError, "IDENTITY_CHANGED"):
            self.run_read()
        self.assertEqual(self.inspections, 1)

    def test_invalid_structured_result_fails_without_retry(self):
        self.stale_count = 0
        self.invalid_result = True
        with self.assertRaisesRegex(module.PerceptionReadError, "RESPONSE_INVALID"):
            self.run_read()
        self.assertEqual(self.inspections, 1)

    def test_out_of_scope_url_rejected_before_requests(self):
        for url in [self.url + "?secret=fixture", self.url.replace("127.0.0.1", "example.com"),
                    self.url.replace("/agent-browser", "/agent-browser/act")]:
            with self.assertRaisesRegex(module.PerceptionReadError, "SCOPE_INVALID"):
                module.inspect_current_target(url, "tenant-fixture")
        self.assertEqual(self.requests, [])


if __name__ == "__main__":
    unittest.main()
