FROM debian:bookworm-slim
RUN apt-get update \
    && apt-get install --no-install-recommends -y \
        opencv-data python3 python3-numpy python3-opencv tesseract-ocr tesseract-ocr-eng tesseract-ocr-chi-sim \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /workspace
COPY apps/browser-node/scripts/recording_privacy_scanner.py apps/browser-node/scripts/recording_privacy_scanner.py
COPY tests/recording/test_recording_privacy_scanner.py tests/recording/test_recording_privacy_scanner.py
ENV PYTHONDONTWRITEBYTECODE=1
USER 65532:65532
RUN --network=none python3 -m unittest discover -s tests/recording -p 'test_*.py' -v
