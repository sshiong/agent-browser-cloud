ARG PYTHON_BASE_IMAGE=python:3.13-slim-bookworm
FROM ${PYTHON_BASE_IMAGE}
RUN apt-get update \
    && apt-get install --no-install-recommends -y ca-certificates chromium tini \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY validation_worker.py /app/validation_worker.py
COPY runtime_validation_runner.py /usr/local/bin/runtime-validation-runner
RUN chmod 0555 /usr/local/bin/runtime-validation-runner
USER 65532:65532
ENTRYPOINT ["/usr/bin/tini", "--", "python", "/app/validation_worker.py"]
