import importlib.util
import pathlib
import unittest


MODULE_DIR = pathlib.Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location("vision_worker", MODULE_DIR / "vision_worker.py")
vision_worker = importlib.util.module_from_spec(SPEC)
assert SPEC.loader
SPEC.loader.exec_module(vision_worker)


class VisionWorkerTest(unittest.TestCase):
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
