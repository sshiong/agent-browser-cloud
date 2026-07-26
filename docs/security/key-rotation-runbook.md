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

