"""Verify that privacy proofs cover the encoded pixels actually persisted."""

import base64
import hashlib
import importlib.util
import json
import pathlib
import subprocess
import sys
import unittest
from unittest import mock

import cv2
import numpy as np


ROOT = pathlib.Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location(
    "recording_privacy_scanner",
    ROOT / "apps/browser-node/scripts/recording_privacy_scanner.py",
)
SCANNER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(SCANNER)


class PersistedPixelsTest(unittest.TestCase):
    def setUp(self):
        # Deterministic synthetic pixels; no external photos or private browser data.
        y, x = np.indices((80, 120))
        image = np.stack(((x * 17 + y * 7) % 256, (x * 3) % 256, (y * 11) % 256), axis=2)
        ok, encoded = cv2.imencode(".jpg", image.astype(np.uint8), [cv2.IMWRITE_JPEG_QUALITY, 95])
        self.assertTrue(ok)
        self.input = base64.b64encode(encoded.tobytes()).decode("ascii")
        _, self.source_pixels = SCANNER._decode_jpeg(self.input)
        self.ocr = mock.patch.object(SCANNER, "_ocr_rows", return_value=("", []))
        self.ocr.start()
        self.addCleanup(self.ocr.stop)

    def test_visual_recheck_matches_returned_jpeg_pixels_and_hash(self):
        with mock.patch.object(SCANNER, "_visual_sensitive_regions", return_value=(0, [])) as visual:
            result = SCANNER.scan(self.input)
        persisted = base64.b64decode(result["sanitizedImageBase64"], validate=True)
        decoded = cv2.imdecode(np.frombuffer(persisted, dtype=np.uint8), cv2.IMREAD_COLOR)
        self.assertFalse(np.array_equal(decoded, self.source_pixels), "fixture must exercise lossy encoding")
        self.assertEqual(visual.call_count, 2)
        np.testing.assert_array_equal(visual.call_args_list[-1].args[0], decoded)
        self.assertEqual(result["sanitizedImageSha256"], hashlib.sha256(persisted).hexdigest())

    def test_residual_detected_only_after_encoding_rejects_frame(self):
        def classifier(pixels):
            # Model a classifier sensitive to encoding changes, without claiming a new real
            # face/QR dataset. The real JPEG codec remains in use on both sides of the boundary.
            return (0, []) if np.array_equal(pixels, self.source_pixels) else (1, [(0, 0, 10, 10)])

        with mock.patch.object(SCANNER, "_visual_sensitive_regions", side_effect=classifier):
            with self.assertRaisesRegex(RuntimeError, "PRIVACY_REDACTION_INCOMPLETE"):
                SCANNER.scan(self.input)

    def test_invalid_encoded_jpeg_cannot_receive_zero_residual_proof(self):
        truncated = np.frombuffer(b"\xff\xd8\xff\x00", dtype=np.uint8)
        with mock.patch.object(SCANNER, "_visual_sensitive_regions", return_value=(0, [])), \
                mock.patch.object(cv2, "imencode", return_value=(True, truncated)):
            with self.assertRaisesRegex(RuntimeError, "SANITIZED_IMAGE_DECODE_FAILED"):
                SCANNER.scan(self.input)

    def test_encoded_dimensions_must_match_masked_frame(self):
        with mock.patch.object(SCANNER, "_visual_sensitive_regions", return_value=(0, [])), \
                mock.patch.object(cv2, "imdecode", side_effect=[self.source_pixels, np.zeros((1, 1, 3), np.uint8)]):
            with self.assertRaisesRegex(RuntimeError, "SANITIZED_IMAGE_DECODE_FAILED"):
                SCANNER.scan(self.input)


class RealClassifiersTest(unittest.TestCase):
    def test_real_ocr_and_qr_masking_self_test(self):
        SCANNER.self_test()

    def test_real_multi_qr_frames_keep_scanner_stdout_strict_ndjson(self):
        # Own synthetic codes only. Exercise the actual Debian process and its C-library
        # stdout, which a Python-level mock or direct scan() call cannot verify.
        image = np.full((440, 900, 3), 255, np.uint8)
        for index, marker in enumerate(("owned-marker-one", "owned-marker-two")):
            qr = cv2.QRCodeEncoder_create().encode(marker)
            middle = qr.shape[0] // 2
            self.assertTrue(np.any(qr[middle - 2:middle + 2, middle - 2:middle + 2] == 0))
            qr[middle - 2:middle + 2, middle - 2:middle + 2] = 255
            qr = cv2.resize(qr, (300, 300), interpolation=cv2.INTER_NEAREST)
            left = 50 + 450 * index
            image[60:360, left:left + 300] = cv2.cvtColor(qr, cv2.COLOR_GRAY2BGR)
        success, jpeg = cv2.imencode(".jpg", image)
        self.assertTrue(success)
        encoded = base64.b64encode(jpeg.tobytes()).decode("ascii")
        request = (json.dumps({"imageBase64": encoded}) + "\n").encode("ascii")
        completed = subprocess.run(
            [sys.executable, str(ROOT / "apps/browser-node/scripts/recording_privacy_scanner.py")],
            input=request * 2, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            timeout=30, check=False,
        )
        self.assertEqual(completed.returncode, 0, "scanner process must finish successfully")
        self.assertEqual(len(completed.stdout.splitlines()), 2,
                         "scanner must emit exactly one NDJSON response per frame")
        for line in completed.stdout.splitlines():
            try:
                response = json.loads(line)
            except (ValueError, UnicodeError):
                self.fail("scanner emitted a non-JSON stdout line")
            self.assertEqual(response["visualDetectedSignalCount"], 2)
            self.assertEqual(response["visualMaskedRegionCount"], 2)
            self.assertEqual(response["visualResidualSignalCount"], 0)
            self.assertEqual(response["ocrResidualSignalCount"], 0)
            sanitized = base64.b64decode(response["sanitizedImageBase64"], validate=True)
            self.assertEqual(response["sanitizedImageSha256"], hashlib.sha256(sanitized).hexdigest())
            pixels = cv2.imdecode(np.frombuffer(sanitized, np.uint8), cv2.IMREAD_COLOR)
            detected, _ = cv2.QRCodeDetector().detectMulti(pixels)
            self.assertFalse(detected, "persisted JPEG must no longer expose either QR region")


if __name__ == "__main__":
    unittest.main()
