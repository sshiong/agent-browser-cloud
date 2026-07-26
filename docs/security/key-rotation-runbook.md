# Key and Certificate Rotation Runbook

## Scope

- OIDC signing keys are owned by the IdP and consumed through issuer metadata/JWKS.
- Control Plane and Browser Node use CA-issued mutual TLS certificates.
- Remote desktop tickets, Agent capability tokens and encrypted action payloads use service keys
  supplied from the secret manager.

## Rotation procedure

1. Add a new trust key/certificate while retaining the previous verifier.
2. Issue new workloads with a unique key ID and short validity.
3. Verify CP→Node Ping and Node→CP Event publish under the new identity.
4. Drain old workloads and wait longer than the maximum token/ticket lifetime.
5. Remove the previous verifier and record `KEY_ROTATION` in the audit chain.
6. For emergency revocation, stop admission first, revoke the identity, then reconcile active
   Runtimes and Sessions.

The integration smoke test rotates the Browser Node certificate during Node restart and proves that
the same CA trust policy admits the new identity without accepting plaintext.

## Control Plane governance

All production rotations must be registered through:

- `POST /api/v1/key-rotation-requests`;
- `POST /api/v1/key-rotation-requests/{rotationId}:approve` by a different Platform Admin;
- `POST /api/v1/key-rotation-requests/{rotationId}:complete` with workload verification evidence;
- `POST /api/v1/key-rotation-requests/{rotationId}:revoke` for containment or rollback.

Normal completion is blocked until the verifier overlap window has elapsed and the new-key write,
old-key read and plaintext-rejection probes pass. A suspected-compromise rotation removes the
overlap window immediately and does not require an old-key read probe. Every decision is appended
as `KEY_ROTATION` with separate approval and completion evidence hashes.
