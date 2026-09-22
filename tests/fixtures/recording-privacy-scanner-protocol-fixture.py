#!/usr/bin/env python3
"""Protocol-only fixture for the host Integration gate.

The production image runs the real Tesseract/OpenCV scanner. This fixture isolates the Node ↔
scanner streaming contract while the Integration suite uses its fake Chromium runtime.
"""

import base64
import hashlib
import json
import sys


VERSION = "tesseract-opencv-pii-face-qr-v2"


if sys.argv[1:] == ["--self-test"]:
    print(json.dumps({"ready": True, "scanVersion": VERSION}))
    raise SystemExit(0)
if sys.argv[1:]:
    raise SystemExit(2)

for line in sys.stdin:
    request = json.loads(line)
    if set(request) != {"imageBase64"}:
        raise SystemExit(2)
    image = base64.b64decode(request["imageBase64"], validate=True)
    if not image.startswith(b"\xff\xd8\xff"):
        raise SystemExit(2)
    digest = hashlib.sha256(image).hexdigest()
    print(
        json.dumps(
            {
                "scanVersion": VERSION,
                "sanitizedImageBase64": request["imageBase64"],
                "sanitizedImageSha256": digest,
                "ocrDetectedSignalCount": 0,
                "ocrMaskedRegionCount": 0,
                "ocrResidualSignalCount": 0,
                "visualDetectedSignalCount": 0,
                "visualMaskedRegionCount": 0,
                "visualResidualSignalCount": 0,
            },
            separators=(",", ":"),
        ),
        flush=True,
    )
