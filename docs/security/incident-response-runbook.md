# Incident Response Runbook

## Severity and ownership

| Severity | Examples | Incident commander | Page |
| --- | --- | --- | --- |
| SEV-0 | Cross-tenant leak, signing/CA compromise | Security Owner | Security, Platform, SRE |
| SEV-1 | Region-wide control/runtime outage | SRE | Platform, Runtime |
| SEV-2 | Tenant-scoped degraded workflow/provider | Service Owner | On-call |

Primary roles are Security Owner, Platform Owner, Browser Runtime Owner and SRE; every production
service must also have a named secondary in the deployment inventory.

## First 15 minutes

1. Open an incident record; capture UTC start, detector, affected tenants/regions/builds.
2. Freeze releases for suspected supply-chain, schema or cross-tenant events.
3. Preserve audit head hashes, relevant signed manifests, Outbox/Inbox ranges and Runtime Build IDs.
4. Revoke affected OIDC sessions, mTLS identities, capability/ticket keys or Runtime Builds.
5. Prefer admission stop and workload isolation over deleting evidence.

## Containment playbooks

- Cross-tenant: disable affected API operation, rotate signing material, run the cross-tenant suite,
  compare per-tenant audit chains.
- Node identity: revoke/rotate CA identity, drain Node, terminate its Runtimes and reconcile leases.
- Runtime artifact: mark build non-Stable, freeze rollout, verify digest/SBOM and replay validation.
- Prompt injection: disable affected Tool/Provider, retain redacted Security Events, do not persist
  raw hostile content.
- Profile corruption: quarantine checkpoint, refuse Ready, restore only from a verified Commit Marker.

## Coordinator failover

1. Confirm `browsercloud_coordinator_reconcile_cleanup_failures_total`, stale-operation abort rate
   and reconcile P99 across all Control Plane instances; do not use a single Pod as authority.
2. Check PostgreSQL availability, Coordinator Ownership lease age and the active Operation term
   before restarting or draining any instance.
3. For cleanup failures, stop new Session admission for the affected Shard, retain the Outbox/Inbox
   and Operation rows, and verify whether the new-term `StopRuntime` reached the Browser Node.
4. Never replay an old-term lifecycle or Agent write command. Allow the current owner to create a
   new-term termination cleanup; Node fencing must reject late callbacks.
5. Before reopening admission, verify no stale Active Operation remains, Node Journal pending events
   are draining, cleanup failure rate returns to zero, and the Coordinator failover integration
   matrix passes.

## Operator List/Watch

1. Check `browsercloud_operator_leader` across every Operator Pod and compare it with the
   `browser-session-operator` Lease holder; never infer leadership from one Service endpoint.
2. Inspect Lease renewal age, loop error categories, Watch restart reasons, resourceVersion expiry
   rate and the last successful consistent snapshot before restarting a replica.
3. Repeated 410 events indicate API Server compaction outrunning the consumer. Preserve logs and
   API Server/etcd health evidence, then confirm a fresh paginated LIST completes before intervention.
4. During API Server or network failure, keep the existing Browser Sessions running. Do not delete
   CRs or finalizers and do not manually replay create/terminate requests without their idempotency key.
5. Drain or restart only the non-Leader first. Before restoring normal operations, verify exactly one
   active Leader, recent Lease/snapshot timestamps, zero sustained reconcile errors and a successful
   BrowserSession create/delete finalizer exercise.

## Recovery and closure

- Recovery requires technical health plus business validation where Profiles are involved.
- Record measured RTO/RPO, data scope, residual risk owner, control failure and follow-up deadline.
- A SEV-0/1 closes only after audit-chain verification, credential rotation, regression tests and a
  blameless review with tracked actions.
