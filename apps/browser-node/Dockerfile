FROM rust:1.85-bookworm AS build
WORKDIR /workspace
COPY apps/browser-node apps/browser-node
COPY packages/contracts/proto packages/contracts/proto
RUN cargo build \
    --release \
    --locked \
    --manifest-path apps/browser-node/Cargo.toml \
    --bin node-agent

FROM debian:bookworm-slim
RUN apt-get update \
    && apt-get install --no-install-recommends -y ca-certificates chromium tini \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system browsercloud \
    && useradd --system --gid browsercloud --home-dir /var/lib/browsercloud browsercloud \
    && mkdir -p /var/lib/browsercloud/runtime \
    && chown -R browsercloud:browsercloud /var/lib/browsercloud
COPY --from=build /workspace/apps/browser-node/target/release/node-agent /usr/local/bin/node-agent
USER browsercloud
ENV CHROMIUM_PATH=/usr/bin/chromium \
    RUNTIME_ROOT=/var/lib/browsercloud/runtime \
    NODE_JOURNAL_PATH=/var/lib/browsercloud/runtime/node-journal.sqlite3 \
    NODE_AGENT_PORT=9090
EXPOSE 9090
ENTRYPOINT ["/usr/bin/tini", "--", "/usr/local/bin/node-agent"]
