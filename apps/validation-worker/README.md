# Runtime Validation Worker

This is the isolated execution side of the PostgreSQL-authoritative Runtime Validation queue.
It claims only matrix cells matching its browser version, OS, architecture and declared
capabilities, maintains a fenced lease, and submits a bounded result to the Control Plane.

The worker never accepts a command from a job. `--runner` and every `--runner-arg` are fixed by the
deployment. The runner receives the public `RuntimeValidationView` as JSON on stdin and must emit
one `CompleteRuntimeValidationRequest` JSON document on stdout. Provider credentials and Control
Plane tokens are not copied into the runner environment.

Production requirements:

- use a short-lived `VALIDATION_WORKER` JWT projected as a `0600` file, or `0440` when the
  read-only group is the Worker's dedicated Pod `fsGroup`; group write and all other access fail
  closed;
- run with a read-only root filesystem, no privilege escalation, all capabilities dropped and a
  dedicated sandbox RuntimeClass;
- mount one immutable browser/runtime build per worker pool and advertise only that exact version;
- allow network only to the Control Plane and the approved validation fixture/provider endpoints;
- keep `--heartbeat-seconds` below one third of the server lease.

Example local invocation:

```bash
APP_ENVIRONMENT=local python validation_worker.py run-once \
  --control-plane-url http://127.0.0.1:8080 \
  --allow-insecure-http --local-tenant-id platform-control \
  --control-plane-token-file /tmp/validation-worker.token \
  --worker-id validation-worker-local \
  --browser-version 128.0.6613.84 \
  --capability cdp=true --capability replay=true \
  --runner /opt/browsercloud/bin/runtime-validation-runner
```
