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

The mounted `suites.json` catalog must carry authorization metadata for every dataset. The
runner rejects the entire dataset before opening any case URL if the declaration is missing,
contains production or personal data or credentials, has no approved hosts, or includes a case
outside those exact hosts. Every case capability must also appear in `declaredCapabilities`; the
runner checks all references before opening the first case. Prepare the Secret with these fields
before rolling out the new Worker:

```json
{
  "datasets": {
    "synthetic-v1": {
      "suiteVersion": "v1",
      "persona": "default",
      "authorization": {
        "basis": "Repository-owned synthetic fixture reviewed for validation",
        "containsProductionData": false,
        "personalData": false,
        "credentials": false,
        "allowedHosts": ["fixture.example.test"]
      },
      "declaredCapabilities": {"navigate": true},
      "cases": [{"id": "NAVIGATE_FIXTURE", "required": true,
                 "url": "https://fixture.example.test/", "capability": "navigate"}]
    }
  }
}
```

The declaration is an admission check, not proof that a customer authorized a dataset or that
redirects remain on those hosts. Keep approval evidence outside the catalog, mount it only from a
trusted deployment, and enforce destination egress with the sandbox network policy. The runner
does not ingest raw customer data or Provider credentials.

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
