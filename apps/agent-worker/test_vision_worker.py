import importlib.util
import http.server
import json
import pathlib
import subprocess
import threading
import unittest
from unittest.mock import Mock, patch


MODULE_DIR = pathlib.Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location("vision_worker", MODULE_DIR / "vision_worker.py")
vision_worker = importlib.util.module_from_spec(SPEC)
assert SPEC.loader
SPEC.loader.exec_module(vision_worker)


class VisionWorkerTest(unittest.TestCase):
    @staticmethod
    def jpeg():
        return b"\xff\xd8\xfffixture"

    @staticmethod
    def tsv(text):
        header = "level\tpage_num\tblock_num\tpar_num\tline_num\tword_num\tleft\ttop\twidth\theight\tconf\ttext\n"
        rows = "".join(
            f"5\t1\t1\t1\t1\t{index}\t{10 + index * 30}\t20\t28\t15\t95\t{word}\n"
            for index, word in enumerate(text.split(), 1)
        )
        return (header + rows).encode()

    def test_local_ocr_returns_only_hash_attestation_for_safe_pixels(self):
        with patch.object(
            vision_worker.subprocess,
            "run",
            side_effect=(
                subprocess.CompletedProcess([], 0, self.tsv("Select every traffic light"), b""),
                subprocess.CompletedProcess([], 0, b"\xff\xd8\xffsanitized", b""),
                subprocess.CompletedProcess([], 0, self.tsv("Select every traffic light"), b""),
            ),
        ) as run:
            result = vision_worker.LocalScreenshotPrivacyScanner("/fixture/tesseract").scan(
                self.jpeg()
            )
        attestation = result.attestation
        self.assertEqual(attestation["privacyScanVersion"], "tesseract-pii-v1")
        self.assertEqual(attestation["detectedSensitivePatternCount"], 0)
        self.assertEqual(attestation["piiRedactedRegionCount"], 0)
        self.assertEqual(attestation["remainingSensitivePatternCount"], 0)
        self.assertEqual(len(attestation["ocrTextHash"]), 64)
        self.assertNotIn("text", attestation)
        self.assertEqual(run.call_count, 3)
        self.assertEqual(run.call_args_list[0].kwargs["input"], self.jpeg())
        self.assertEqual(result.screenshot, b"\xff\xd8\xffsanitized")
        self.assertIn("-strip", run.call_args_list[1].args[0])

    def test_local_ocr_redacts_pii_and_rechecks_before_external_model(self):
        samples = (
            "Contact alice@example.test",
            "Card 4111 1111 1111 1111",
            "Verification code 123456",
            "api_key=abcdefghijklmnop",
        )
        scanner = vision_worker.LocalScreenshotPrivacyScanner("/fixture/tesseract")
        for sample in samples:
            with self.subTest(sample=sample), patch.object(
                vision_worker.subprocess,
                "run",
                side_effect=(
                    subprocess.CompletedProcess([], 0, self.tsv(sample), b""),
                    subprocess.CompletedProcess([], 0, b"\xff\xd8\xffsanitized", b""),
                    subprocess.CompletedProcess([], 0, self.tsv("redacted"), b""),
                ),
            ):
                result = scanner.scan(self.jpeg())
                self.assertNotEqual(result.screenshot, self.jpeg())
                self.assertGreater(result.attestation["detectedSensitivePatternCount"], 0)
                self.assertEqual(result.attestation["piiRedactedRegionCount"], 1)
                self.assertEqual(result.attestation["remainingSensitivePatternCount"], 0)

    def test_local_ocr_failure_is_fail_closed(self):
        scanner = vision_worker.LocalScreenshotPrivacyScanner("/fixture/tesseract")
        with patch.object(
            vision_worker.subprocess,
            "run",
            return_value=subprocess.CompletedProcess([], 1, b"", b"decoder failure"),
        ):
            with self.assertRaisesRegex(
                vision_worker.WorkerError, "SCREENSHOT_PRIVACY_SCAN_FAILED"
            ):
                scanner.scan(b"\xff\xd8\xfffixture")

    def test_local_redaction_must_pass_a_second_zero_pii_scan(self):
        scanner = vision_worker.LocalScreenshotPrivacyScanner(
            "/fixture/tesseract", "/fixture/convert"
        )
        pii = self.tsv("Contact alice@example.test")
        with patch.object(
            vision_worker.subprocess,
            "run",
            side_effect=(
                subprocess.CompletedProcess([], 0, pii, b""),
                subprocess.CompletedProcess([], 0, b"\xff\xd8\xffsanitized", b""),
                subprocess.CompletedProcess([], 0, pii, b""),
            ),
        ):
            with self.assertRaisesRegex(
                vision_worker.WorkerError, "SCREENSHOT_PII_REDACTION_INCOMPLETE"
            ):
                scanner.scan(self.jpeg())

    def test_vision_loop_scans_before_calling_model_and_attests_completion(self):
        calls = []
        client = Mock(deployment_id="vision-test")
        client.claim.return_value = {
            "screenshotUrl": "http://127.0.0.1/evidence",
            "job": {"jobId": "cvj_12345678901234567890"},
        }
        client.transition.return_value = {}
        provider = Mock()
        provider.download.return_value = b"\xff\xd8\xfffixture"
        provider.analyze.side_effect = lambda *_: calls.append("model") or {
            "decision": "ESCALATE"
        }
        scanner = Mock()
        scanner.scan.side_effect = lambda screenshot: calls.append("scan") or vision_worker.PrivacyScanResult(
            b"\xff\xd8\xffsanitized",
            {
                "privacyScanVersion": "tesseract-pii-v1",
                "ocrTextHash": "a" * 64,
                "detectedSensitivePatternCount": 0,
                "piiRedactedRegionCount": 0,
                "remainingSensitivePatternCount": 0,
            },
        )
        loop = vision_worker.VisionLoop(client, provider, scanner, "test", [], 2, 15)
        self.assertTrue(loop.run_once())
        self.assertEqual(calls, ["scan", "model"])
        self.assertEqual(provider.analyze.call_args.args[1], b"\xff\xd8\xffsanitized")
        completion = next(
            call.args[2] for call in client.transition.call_args_list if call.args[1] == "complete"
        )
        self.assertEqual(completion["privacyScanVersion"], "tesseract-pii-v1")
        self.assertEqual(completion["ocrTextHash"], "a" * 64)
        self.assertEqual(completion["detectedSensitivePatternCount"], 0)
        self.assertEqual(completion["piiRedactedRegionCount"], 0)
        self.assertEqual(completion["remainingSensitivePatternCount"], 0)

    def test_privacy_rejection_never_calls_external_model(self):
        client = Mock(deployment_id="vision-test")
        client.claim.return_value = {
            "screenshotUrl": "http://127.0.0.1/evidence",
            "job": {"jobId": "cvj_12345678901234567890"},
        }
        client.transition.return_value = {}
        provider = Mock()
        provider.download.return_value = b"\xff\xd8\xfffixture"
        scanner = Mock()
        scanner.scan.side_effect = vision_worker.WorkerError(
            "SCREENSHOT_PII_DETECTED", retryable=False
        )
        loop = vision_worker.VisionLoop(client, provider, scanner, "test", [], 2, 15)
        with self.assertRaisesRegex(vision_worker.WorkerError, "SCREENSHOT_PII_DETECTED"):
            loop.run_once()
        provider.analyze.assert_not_called()
        failure = next(
            call.args[2] for call in client.transition.call_args_list if call.args[1] == "fail"
        )
        self.assertEqual(failure, {"failureCode": "SCREENSHOT_PII_DETECTED", "retryable": False})

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
