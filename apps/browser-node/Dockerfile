FROM rust:1.98-bookworm AS build
WORKDIR /workspace
COPY apps/browser-node apps/browser-node
COPY packages/contracts/proto packages/contracts/proto
RUN cargo build \
    --release \
    --locked \
    --manifest-path apps/browser-node/Cargo.toml \
    --bin browsercloud-lite \
    --bin browsercloud-worker-supervisor \
    --bin browsercloud-mcp-gateway \
    --bin network-helper \
    --bin storage-helper \
    --bin node-agent

# ---------------------------------------------------------------------------
# Target: mcp-gateway (Pure-Rust drop-in replacement for Python mcp-gateway)
# ---------------------------------------------------------------------------
FROM debian:bookworm-slim AS mcp-gateway
RUN set -eux; \
    apt-get update; \
    apt-get install --no-install-recommends -y ca-certificates tini; \
    rm -rf /var/lib/apt/lists/*; \
    groupadd --gid 65532 browsercloud; \
    useradd --uid 65532 --gid browsercloud --no-log-init --home-dir /tmp browsercloud
COPY --from=build /workspace/apps/browser-node/target/release/browsercloud-mcp-gateway /usr/local/bin/browsercloud-mcp-gateway
USER 65532:65532
ENV MCP_HOST=0.0.0.0 \
    MCP_PORT=8130 \
    MCP_BACKEND=control-plane \
    RUST_LOG=info
EXPOSE 8130
ENTRYPOINT ["/usr/bin/tini", "--", "/usr/local/bin/browsercloud-mcp-gateway"]

# ---------------------------------------------------------------------------
# Target: worker-supervisor (Pure-Rust unified Agent/Reviewer/Outcome/Vision
# worker replacing 4 Python worker containers for L1/L2)
# ---------------------------------------------------------------------------
FROM debian:bookworm-slim AS worker-supervisor
RUN set -eux; \
    apt-get update; \
    apt-get install --no-install-recommends -y ca-certificates tini; \
    rm -rf /var/lib/apt/lists/*; \
    groupadd --gid 65532 browsercloud; \
    useradd --uid 65532 --gid browsercloud --no-log-init --home-dir /tmp browsercloud
COPY --from=build /workspace/apps/browser-node/target/release/browsercloud-worker-supervisor /usr/local/bin/browsercloud-worker-supervisor
USER browsercloud
ENV WORKER_ROLES=agent,reviewer,outcome,vision \
    WORKER_ENVIRONMENT=production \
    WORKER_READY_FILE=/tmp/worker-ready \
    RUST_LOG=info
ENTRYPOINT ["/usr/bin/tini", "--", "/usr/local/bin/browsercloud-worker-supervisor"]

# ---------------------------------------------------------------------------
# Target: lite (L0 Standalone — single pure-Rust container + Headless Chromium)
# Zero Java, PostgreSQL, Redis, Python, Xvfb/VNC, or OpenCV/Tesseract.
# ---------------------------------------------------------------------------
FROM debian:bookworm-slim AS lite
RUN set -eux; \
    apt-get update; \
    apt-get install --no-install-recommends -y ca-certificates chromium chromium-sandbox tini; \
    rm -rf /var/lib/apt/lists/*; \
    groupadd --gid 65532 browsercloud; \
    useradd --uid 65532 --gid browsercloud --no-log-init --home-dir /data browsercloud; \
    mkdir -p /data; \
    chown -R browsercloud:browsercloud /data
COPY --from=build /workspace/apps/browser-node/target/release/browsercloud-lite /usr/local/bin/browsercloud-lite
USER browsercloud
ENV CHROMIUM_PATH=/usr/bin/chromium \
    LITE_DATA_DIR=/data \
    LITE_BIND_ADDR=0.0.0.0:8130 \
    LITE_MAX_SESSIONS=4 \
    BROWSER_LOW_MEMORY_MODE=true \
    RUST_LOG=info
EXPOSE 8130
ENTRYPOINT ["/usr/bin/tini", "--", "/usr/local/bin/browsercloud-lite"]

# ---------------------------------------------------------------------------
# Target: core (L1/L2 Headless Browser Node — Rust + Chromium + CDP only)
# ---------------------------------------------------------------------------
FROM debian:bookworm-slim AS core
RUN set -eux; \
    apt-get update; \
    apt-get install --no-install-recommends -y ca-certificates chromium chromium-sandbox tini; \
    rm -rf /var/lib/apt/lists/*; \
    groupadd --gid 65532 browsercloud; \
    useradd --uid 65532 --gid browsercloud --no-log-init --home-dir /var/lib/browsercloud browsercloud; \
    mkdir -p /var/lib/browsercloud/runtime; \
    chown -R browsercloud:browsercloud /var/lib/browsercloud
COPY --from=build /workspace/apps/browser-node/target/release/node-agent /usr/local/bin/node-agent
COPY --from=build /workspace/apps/browser-node/target/release/network-helper /usr/local/bin/network-helper
COPY --from=build /workspace/apps/browser-node/target/release/storage-helper /usr/local/bin/storage-helper
USER browsercloud
ENV CHROMIUM_PATH=/usr/bin/chromium \
    RUNTIME_ROOT=/var/lib/browsercloud/runtime \
    NODE_JOURNAL_PATH=/var/lib/browsercloud/runtime/node-journal.sqlite3 \
    PROFILE_STORAGE_ROOT=/var/lib/browsercloud/profiles \
    ALLOW_DIRECT_NETWORK=false \
    NODE_AGENT_PORT=9090 \
    APP_ENVIRONMENT=local \
    REMOTE_DESKTOP_GATEWAY_PORT=6080 \
    XVFB_PATH="" \
    X11VNC_PATH=""
EXPOSE 9090 6080
ENTRYPOINT ["/usr/bin/tini", "--", "/usr/local/bin/node-agent"]

# ---------------------------------------------------------------------------
# Target: desktop (core + on-demand Xvfb / x11vnc for human collaboration)
# ---------------------------------------------------------------------------
FROM core AS desktop
USER root
RUN set -eux; \
    apt-get update; \
    apt-get install --no-install-recommends -y xvfb x11vnc; \
    rm -rf /var/lib/apt/lists/*
USER browsercloud
ENV XVFB_PATH=/usr/bin/Xvfb \
    X11VNC_PATH=/usr/bin/x11vnc

# ---------------------------------------------------------------------------
# Target: vision (default final stage — preserves full compatibility with
# existing docker-compose.yml, CI gates, and ENABLE_* build-arg pruning)
# ---------------------------------------------------------------------------
FROM core AS vision
ARG ENABLE_REMOTE_DESKTOP=true
ARG ENABLE_RECORDING_SCANNER=true
USER root
RUN set -eux; \
    packages=""; \
    if [ "${ENABLE_REMOTE_DESKTOP}" = "true" ]; then \
        packages="${packages} xvfb x11vnc"; \
    fi; \
    if [ "${ENABLE_RECORDING_SCANNER}" = "true" ]; then \
        packages="${packages} opencv-data python3 python3-numpy python3-opencv tesseract-ocr tesseract-ocr-eng tesseract-ocr-chi-sim"; \
    fi; \
    if [ -n "${packages}" ]; then \
        apt-get update; \
        apt-get install --no-install-recommends -y ${packages}; \
        rm -rf /var/lib/apt/lists/*; \
    fi
COPY apps/browser-node/scripts/recording_privacy_scanner.py /usr/local/libexec/browsercloud/recording-privacy-scanner
COPY apps/browser-node/assets/recording-privacy /usr/local/share/browsercloud/recording-privacy
RUN set -eux; \
    if [ "${ENABLE_RECORDING_SCANNER}" = "true" ]; then \
        chmod 0555 /usr/local/libexec/browsercloud/recording-privacy-scanner; \
        chmod 0444 /usr/local/share/browsercloud/recording-privacy/*; \
        /usr/local/libexec/browsercloud/recording-privacy-scanner --self-test; \
    else \
        rm -rf /usr/local/libexec/browsercloud/recording-privacy-scanner /usr/local/share/browsercloud/recording-privacy; \
    fi
USER browsercloud
ENV XVFB_PATH=/usr/bin/Xvfb \
    X11VNC_PATH=/usr/bin/x11vnc \
    RECORDING_PRIVACY_SCANNER_PATH=/usr/local/libexec/browsercloud/recording-privacy-scanner
