ARG PYTHON_BASE_IMAGE=python:3.13-slim-bookworm
FROM ${PYTHON_BASE_IMAGE}
RUN apt-get update \
    && apt-get install --no-install-recommends -y ca-certificates tini \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY gameday_worker.py /app/gameday_worker.py
COPY gameday_runner.py /usr/local/bin/gameday-runner
RUN mkdir -p \
      /var/run/browsercloud/identity \
      /var/run/browsercloud/control-plane-ca \
      /var/run/browsercloud/controller \
      /var/run/browsercloud/controller-ca \
      /var/run/browsercloud/gameday \
    && chmod 0555 /usr/local/bin/gameday-runner /var/run/browsercloud/*
USER 65532:65532
ENTRYPOINT ["/usr/bin/tini", "--", "python", "/app/gameday_worker.py"]
