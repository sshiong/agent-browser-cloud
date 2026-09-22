#!/usr/bin/env python3
"""Fail-closed, local full-frame privacy scanner for Recording frames.

The process accepts bounded newline-delimited JSON on stdin and returns one JSON document per
request on stdout. It never writes image or OCR data to disk. OCR text is reduced to counts, while
the returned JPEG has every detected OCR-PII, face, and QR-code region painted black. A second
scan must find no residual sensitive signal before the frame may be persisted.
"""

from __future__ import annotations

import base64
import csv
import hashlib
import io
import json
import os
import re
import subprocess
import sys
from typing import Iterable

import cv2
import numpy as np


MAX_INPUT_BYTES = 8 * 1024 * 1024
MAX_OUTPUT_BYTES = 8 * 1024 * 1024
MAX_OCR_BYTES = 256 * 1024
MAX_REGIONS = 1_000
SCAN_VERSION = "tesseract-opencv-pii-face-qr-v2"
TESSERACT = "/usr/bin/tesseract"
LANGUAGES = os.environ.get("RECORDING_OCR_LANGUAGES", "eng+chi_sim")
FACE_CASCADE = "/usr/share/opencv4/haarcascades/haarcascade_frontalface_default.xml"

PII_PATTERNS = (
    re.compile(r"(?i)\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}\b"),
    re.compile(r"(?<!\d)(?:\+?\d[\s().-]*){10,15}(?!\d)"),
    re.compile(r"(?<!\d)\d{3}-\d{2}-\d{4}(?!\d)"),
    re.compile(
        r"(?i)\b(?:bearer\s+)?(?:api[_ -]?key|secret|token)\s*[:=]\s*[A-Z0-9._/-]{8,}\b"
    ),
    re.compile(r"(?i)\b(?:otp|one[ -]?time|verification)[^\n\d]{0,24}\d{4,10}\b"),
    re.compile(r"\beyJ[A-Za-z0-9_-]{12,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\b"),
)


def _luhn_valid(value: str) -> bool:
    digits = [int(character) for character in value if character.isdigit()]
    if not 13 <= len(digits) <= 19 or len(set(digits)) == 1:
        return False
    total = 0
    parity = len(digits) % 2
    for index, digit in enumerate(digits):
        if index % 2 == parity:
            digit *= 2
            if digit > 9:
                digit -= 9
        total += digit
    return total % 10 == 0


def _sensitive_signal_count(text: str) -> int:
    signals = sum(1 for pattern in PII_PATTERNS if pattern.search(text))
    cards = re.findall(r"(?<!\d)(?:\d[ -]?){13,19}(?!\d)", text)
    return signals + int(any(_luhn_valid(candidate) for candidate in cards))


def _ocr_rows(image: bytes) -> tuple[str, list[dict[str, str]]]:
    try:
        completed = subprocess.run(
            [TESSERACT, "stdin", "stdout", "-l", LANGUAGES, "--psm", "11", "tsv"],
            input=image,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            timeout=20,
            check=False,
            close_fds=True,
            env={"PATH": "/usr/bin:/bin", "LANG": "C.UTF-8"},
        )
    except (OSError, subprocess.SubprocessError) as error:
        raise RuntimeError("OCR_EXECUTION_FAILED") from error
    if completed.returncode != 0 or len(completed.stdout) > MAX_OCR_BYTES:
        raise RuntimeError("OCR_EXECUTION_FAILED")
    try:
        reader = csv.DictReader(io.StringIO(completed.stdout.decode("utf-8")), delimiter="\t")
        required = {
            "page_num",
            "block_num",
            "par_num",
            "line_num",
            "left",
            "top",
            "width",
            "height",
            "text",
        }
        if reader.fieldnames is None or not required.issubset(reader.fieldnames):
            raise ValueError("OCR_TSV_INVALID")
        rows = list(reader)
    except (UnicodeError, csv.Error, ValueError) as error:
        raise RuntimeError("OCR_TSV_INVALID") from error
    words = [row.get("text", "").strip() for row in rows if row.get("text", "").strip()]
    return " ".join(" ".join(words).split()), rows


def _ocr_sensitive_regions(rows: list[dict[str, str]]) -> tuple[int, list[tuple[int, int, int, int]]]:
    lines: dict[tuple[str, str, str, str], list[dict[str, str]]] = {}
    for row in rows:
        if not row.get("text", "").strip():
            continue
        key = tuple(row.get(name, "") for name in ("page_num", "block_num", "par_num", "line_num"))
        lines.setdefault(key, []).append(row)
    detected = 0
    regions: list[tuple[int, int, int, int]] = []
    for words in lines.values():
        signals = _sensitive_signal_count(" ".join(row.get("text", "").strip() for row in words))
        if not signals:
            continue
        detected += signals
        try:
            left = min(int(row["left"]) for row in words)
            top = min(int(row["top"]) for row in words)
            right = max(int(row["left"]) + int(row["width"]) for row in words)
            bottom = max(int(row["top"]) + int(row["height"]) for row in words)
        except (KeyError, TypeError, ValueError) as error:
            raise RuntimeError("OCR_REGION_INVALID") from error
        regions.append((left, top, right, bottom))
    return detected, regions


def _bounded_region(region: Iterable[int], width: int, height: int) -> tuple[int, int, int, int]:
    left, top, right, bottom = region
    left = max(0, int(left) - 4)
    top = max(0, int(top) - 4)
    right = min(width, int(right) + 4)
    bottom = min(height, int(bottom) + 4)
    if left >= right or top >= bottom:
        raise RuntimeError("VISUAL_REGION_INVALID")
    return left, top, right, bottom


def _visual_sensitive_regions(image: np.ndarray) -> tuple[int, list[tuple[int, int, int, int]]]:
    height, width = image.shape[:2]
    gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
    face_classifier = cv2.CascadeClassifier(FACE_CASCADE)
    if face_classifier.empty():
        raise RuntimeError("FACE_CLASSIFIER_UNAVAILABLE")
    faces = face_classifier.detectMultiScale(
        gray, scaleFactor=1.1, minNeighbors=5, minSize=(24, 24), flags=cv2.CASCADE_SCALE_IMAGE
    )
    regions = [
        _bounded_region((x, y, x + w, y + h), width, height)
        for x, y, w, h in faces
    ]

    detector = cv2.QRCodeDetector()
    try:
        detected, _, points, _ = detector.detectAndDecodeMulti(image)
    except cv2.error as error:
        raise RuntimeError("QR_CLASSIFIER_FAILED") from error
    if detected and points is not None:
        for polygon in points:
            xs = [float(point[0]) for point in polygon]
            ys = [float(point[1]) for point in polygon]
            regions.append(_bounded_region((min(xs), min(ys), max(xs), max(ys)), width, height))
    else:
        try:
            _, points, _ = detector.detectAndDecode(image)
        except cv2.error as error:
            raise RuntimeError("QR_CLASSIFIER_FAILED") from error
        if points is not None and len(points):
            xs = [float(point[0]) for point in points]
            ys = [float(point[1]) for point in points]
            regions.append(_bounded_region((min(xs), min(ys), max(xs), max(ys)), width, height))
    return len(regions), regions


def _decode_jpeg(encoded: str) -> tuple[bytes, np.ndarray]:
    try:
        raw = base64.b64decode(encoded, validate=True)
    except (ValueError, TypeError) as error:
        raise RuntimeError("IMAGE_BASE64_INVALID") from error
    if not raw.startswith(b"\xff\xd8\xff") or not 0 < len(raw) <= MAX_INPUT_BYTES:
        raise RuntimeError("IMAGE_JPEG_INVALID")
    image = cv2.imdecode(np.frombuffer(raw, dtype=np.uint8), cv2.IMREAD_COLOR)
    if image is None or image.ndim != 3:
        raise RuntimeError("IMAGE_DECODE_FAILED")
    height, width = image.shape[:2]
    if width <= 0 or height <= 0 or width > 1920 or height > 1080:
        raise RuntimeError("IMAGE_DIMENSIONS_INVALID")
    return raw, image


def scan(encoded: str) -> dict[str, object]:
    raw, image = _decode_jpeg(encoded)
    text, rows = _ocr_rows(raw)
    ocr_signals, ocr_regions = _ocr_sensitive_regions(rows)
    if _sensitive_signal_count(text) != ocr_signals:
        raise RuntimeError("OCR_REGION_MAPPING_INCOMPLETE")
    visual_signals, visual_regions = _visual_sensitive_regions(image)
    all_regions = ocr_regions + visual_regions
    if len(all_regions) > MAX_REGIONS:
        raise RuntimeError("PRIVACY_REGION_LIMIT_EXCEEDED")
    height, width = image.shape[:2]
    for region in all_regions:
        left, top, right, bottom = _bounded_region(region, width, height)
        image[top:bottom, left:right] = (5, 8, 13)
    success, sanitized = cv2.imencode(
        ".jpg", image, [int(cv2.IMWRITE_JPEG_QUALITY), 60, int(cv2.IMWRITE_JPEG_OPTIMIZE), 1]
    )
    if not success:
        raise RuntimeError("IMAGE_ENCODE_FAILED")
    sanitized_bytes = sanitized.tobytes()
    if not sanitized_bytes.startswith(b"\xff\xd8\xff") or len(sanitized_bytes) > MAX_OUTPUT_BYTES:
        raise RuntimeError("IMAGE_ENCODE_FAILED")
    residual_text, _ = _ocr_rows(sanitized_bytes)
    residual_ocr = _sensitive_signal_count(residual_text)
    residual_visual, _ = _visual_sensitive_regions(image)
    if residual_ocr or residual_visual:
        raise RuntimeError("PRIVACY_REDACTION_INCOMPLETE")
    return {
        "scanVersion": SCAN_VERSION,
        "sanitizedImageBase64": base64.b64encode(sanitized_bytes).decode("ascii"),
        "sanitizedImageSha256": hashlib.sha256(sanitized_bytes).hexdigest(),
        "ocrDetectedSignalCount": ocr_signals,
        "ocrMaskedRegionCount": len(ocr_regions),
        "ocrResidualSignalCount": 0,
        "visualDetectedSignalCount": visual_signals,
        "visualMaskedRegionCount": len(visual_regions),
        "visualResidualSignalCount": 0,
    }


def self_test() -> None:
    if not re.fullmatch(r"[a-z0-9_]+(?:\+[a-z0-9_]+){0,3}", LANGUAGES):
        raise RuntimeError("OCR_LANGUAGES_INVALID")
    if not os.path.isfile(TESSERACT) or not os.access(TESSERACT, os.X_OK):
        raise RuntimeError("TESSERACT_UNAVAILABLE")
    cascade = cv2.CascadeClassifier(FACE_CASCADE)
    if cascade.empty():
        raise RuntimeError("FACE_CLASSIFIER_UNAVAILABLE")
    if not hasattr(cv2, "QRCodeDetector"):
        raise RuntimeError("QR_CLASSIFIER_UNAVAILABLE")
    if not hasattr(cv2, "QRCodeEncoder_create"):
        raise RuntimeError("QR_SELF_TEST_ENCODER_UNAVAILABLE")
    image = np.full((360, 800, 3), 255, np.uint8)
    cv2.putText(
        image,
        "user@example.com",
        (20, 80),
        cv2.FONT_HERSHEY_SIMPLEX,
        1.4,
        (0, 0, 0),
        3,
        cv2.LINE_AA,
    )
    qr = cv2.QRCodeEncoder_create().encode("self-test-secret-token")
    qr = cv2.resize(qr, (220, 220), interpolation=cv2.INTER_NEAREST)
    image[110:330, 540:760] = cv2.cvtColor(qr, cv2.COLOR_GRAY2BGR)
    encoded, jpeg = cv2.imencode(".jpg", image, [int(cv2.IMWRITE_JPEG_QUALITY), 95])
    if not encoded:
        raise RuntimeError("SELF_TEST_IMAGE_ENCODE_FAILED")
    result = scan(base64.b64encode(jpeg.tobytes()).decode("ascii"))
    if (
        int(result["ocrDetectedSignalCount"]) < 1
        or int(result["ocrMaskedRegionCount"]) < 1
        or int(result["visualDetectedSignalCount"]) < 1
        or int(result["visualMaskedRegionCount"]) < 1
        or int(result["ocrResidualSignalCount"]) != 0
        or int(result["visualResidualSignalCount"]) != 0
    ):
        raise RuntimeError("FUNCTIONAL_PRIVACY_SELF_TEST_FAILED")


def main() -> int:
    try:
        if len(sys.argv) == 2 and sys.argv[1] == "--self-test":
            self_test()
            print(json.dumps({"ready": True, "scanVersion": SCAN_VERSION}, separators=(",", ":")))
            return 0
        if len(sys.argv) != 1:
            raise RuntimeError("ARGUMENT_INVALID")
        for document in sys.stdin.buffer:
            if len(document) >= MAX_INPUT_BYTES * 2:
                raise RuntimeError("REQUEST_TOO_LARGE")
            request = json.loads(document)
            if set(request) != {"imageBase64"} or not isinstance(request["imageBase64"], str):
                raise RuntimeError("REQUEST_INVALID")
            print(json.dumps(scan(request["imageBase64"]), separators=(",", ":")), flush=True)
        return 0
    except (RuntimeError, json.JSONDecodeError) as error:
        print(json.dumps({"error": str(error)}, separators=(",", ":")), file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
