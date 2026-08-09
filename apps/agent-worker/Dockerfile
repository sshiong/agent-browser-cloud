ARG PYTHON_BASE_IMAGE=python:3.13-slim-bookworm
FROM ${PYTHON_BASE_IMAGE}
RUN apt-get update \
    && apt-get install --no-install-recommends -y ca-certificates tini \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY agent_worker.py /app/agent_worker.py
USER 65532:65532
ENTRYPOINT ["/usr/bin/tini", "--", "python", "/app/agent_worker.py"]
