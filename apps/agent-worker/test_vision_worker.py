import importlib.util
import http.server
import json
import pathlib
import threading
import unittest


MODULE_DIR = pathlib.Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location("vision_worker", MODULE_DIR / "vision_worker.py")
vision_worker = importlib.util.module_from_spec(SPEC)
assert SPEC.loader
SPEC.loader.exec_module(vision_worker)


class VisionWorkerTest(unittest.TestCase):
    def test_control_plane_errors_preserve_failure_code_and_retry_classification(self):
        class Handler(http.server.BaseHTTPRequestHandler):
            def do_POST(self):
                self.rfile.read(int(self.headers["Content-Length"]))
                status = int(self.path.rsplit("/", 1)[-1])
                payload = json.dumps({"code": "VISION_LEASE_REJECTED"}).encode()
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(payload)))
                self.end_headers()
                self.wfile.write(payload)

            def log_message(self, *args):
                pass

        server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            client = vision_worker.VisionControlPlaneClient(
                f"http://127.0.0.1:{server.server_port}", "fixture-token", None,
                "test", "vision-fixture", "vision-v1", "model-v1",
            )
            for status in (401, 403, 409, 500, 503):
                with self.subTest(status=status):
                    with self.assertRaises(vision_worker.WorkerError) as raised:
                        client.request(f"/fixture/{status}", {})
                    self.assertEqual(raised.exception.code, "VISION_LEASE_REJECTED")
                    self.assertEqual(raised.exception.retryable, status >= 500)
        finally:
            server.shutdown()
            thread.join(timeout=2)
            server.server_close()

    def test_production_screenshot_url_is_https_and_allowlisted(self):
        url = "https://evidence.internal/object?signature=redacted"
        self.assertEqual(
            url,
            vision_worker.validate_screenshot_url(
                url, "production", ["evidence.internal"]
            ),
        )

    def test_rejects_untrusted_or_plaintext_screenshot_url(self):
        for value in (
            "https://evil.invalid/object",
            "http://evidence.internal/object",
            "https://user:secret@evidence.internal/object",
        ):
            with self.subTest(value=value):
                with self.assertRaises(vision_worker.WorkerError):
                    vision_worker.validate_screenshot_url(
                        value, "production", ["evidence.internal"]
                    )

    def test_local_screenshot_url_is_loopback_only(self):
        self.assertEqual(
            "http://127.0.0.1:9000/test.jpg",
            vision_worker.validate_screenshot_url(
                "http://127.0.0.1:9000/test.jpg", "test", []
            ),
        )
        with self.assertRaises(vision_worker.WorkerError):
            vision_worker.validate_screenshot_url(
                "http://minio.test/test.jpg", "test", []
            )


if __name__ == "__main__":
    unittest.main()
