FROM rust:1.98-bookworm AS build
WORKDIR /workspace
COPY apps/browser-node apps/browser-node
COPY packages/contracts/proto packages/contracts/proto
RUN cargo build \
    --release \
    --locked \
    --manifest-path apps/browser-node/Cargo.toml \
    --bin network-helper \
    --bin storage-helper \
    --bin node-agent

FROM debian:bookworm-slim
RUN apt-get update \
    && apt-get install --no-install-recommends -y ca-certificates chromium chromium-sandbox tini xvfb x11vnc \
        opencv-data python3 python3-numpy python3-opencv tesseract-ocr tesseract-ocr-eng tesseract-ocr-chi-sim \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 65532 browsercloud \
    && useradd --uid 65532 --gid browsercloud --no-log-init --home-dir /var/lib/browsercloud browsercloud \
    && mkdir -p /var/lib/browsercloud/runtime \
    && chown -R browsercloud:browsercloud /var/lib/browsercloud
COPY --from=build /workspace/apps/browser-node/target/release/node-agent /usr/local/bin/node-agent
COPY --from=build /workspace/apps/browser-node/target/release/network-helper /usr/local/bin/network-helper
COPY --from=build /workspace/apps/browser-node/target/release/storage-helper /usr/local/bin/storage-helper
COPY apps/browser-node/scripts/recording_privacy_scanner.py /usr/local/libexec/browsercloud/recording-privacy-scanner
RUN chmod 0555 /usr/local/libexec/browsercloud/recording-privacy-scanner \
    && /usr/local/libexec/browsercloud/recording-privacy-scanner --self-test
USER browsercloud
ENV CHROMIUM_PATH=/usr/bin/chromium \
    RUNTIME_ROOT=/var/lib/browsercloud/runtime \
    NODE_JOURNAL_PATH=/var/lib/browsercloud/runtime/node-journal.sqlite3 \
    PROFILE_STORAGE_ROOT=/var/lib/browsercloud/profiles \
    ALLOW_DIRECT_NETWORK=false \
    NODE_AGENT_PORT=9090 \
    APP_ENVIRONMENT=local \
    REMOTE_DESKTOP_GATEWAY_PORT=6080 \
    XVFB_PATH=/usr/bin/Xvfb \
    X11VNC_PATH=/usr/bin/x11vnc \
    RECORDING_PRIVACY_SCANNER_PATH=/usr/local/libexec/browsercloud/recording-privacy-scanner
EXPOSE 9090 6080
ENTRYPOINT ["/usr/bin/tini", "--", "/usr/local/bin/node-agent"]
