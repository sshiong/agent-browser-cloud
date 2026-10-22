#!/usr/bin/python3
import base64, hashlib, json, sys
for line in sys.stdin:
  request = json.loads(line)
  image = base64.b64decode(request["imageBase64"], validate=True)
  digest = hashlib.sha256(image).hexdigest()
  print(json.dumps({
    "scanVersion": "tesseract-opencv-pii-face-qr-v2",
    "sanitizedImageBase64": request["imageBase64"],
    "sanitizedImageSha256": digest,
    "ocrDetectedSignalCount": 0,
    "ocrMaskedRegionCount": 0,
    "ocrResidualSignalCount": 0,
    "visualDetectedSignalCount": 0,
    "visualMaskedRegionCount": 0,
    "visualResidualSignalCount": 0
  }), flush=True)
