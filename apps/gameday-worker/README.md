# Recovery GameDay Worker

This worker is the isolated execution side of the PostgreSQL-authoritative Recovery GameDay
queue. It claims only an approved scenario/environment pair from an immutable local catalog,
maintains a fenced lease and executes a fixed runner. A job can never provide a URL, shell command
or executable path.

The runner performs an explicit lifecycle: inject, verify the fault, observe, recover, verify
health and collect evidence. Recovery runs in `finally` and receives a bounded cleanup grace period
after the exercise deadline or `SIGTERM`. The Control Plane commits a result only after recovery is
confirmed. Lease loss after injection moves the job to `RECOVERY_REQUIRED`, where only a recovery
claim can proceed.

Production requirements:

- use a short-lived `GAMEDAY_WORKER` JWT and a separate controller credential, projected as `0600`
  owner files or `0440` files owned by the Pod's dedicated `fsGroup`;
- run with a read-only root filesystem, no privilege escalation, all capabilities dropped, a
  dedicated sandbox RuntimeClass and no Kubernetes service-account token;
- deliver the catalog through an immutable, reviewed ConfigMap or Secret. The base catalog is
  intentionally empty and therefore claims no jobs;
- expose only fixed HTTPS controller origins. Plain HTTP is accepted only for a loopback TEST
  fixture;
- allow egress only to the Control Plane, approved GameDay controller Pods and DNS;
- set the Pod termination grace longer than the runner's 30-second emergency recovery grace.

Example local one-shot invocation:

```bash
python gameday_worker.py \
  --control-plane-url=http://127.0.0.1:8080 \
  --control-plane-token-file=/tmp/gameday-worker.token \
  --controller-token-file=/tmp/gameday-controller.token \
  --catalog-file=/etc/browsercloud/gameday/catalog.json \
  --runner=/absolute/path/gameday_runner.py \
  --worker-id=gameday-worker-local --environment=test --once
```
