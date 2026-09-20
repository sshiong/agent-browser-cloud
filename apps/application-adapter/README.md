# Application Adapter Runtime

This component connects a tenant-owned CRM, payment or IAM API to the Browser Cloud
Business Recovery trust boundary. It performs real HTTPS reads, hashes the selected value
inside the Adapter container, and submits only the hash to the Control Plane.

Production invariants:

- Provider and Control Plane bearer tokens are read from `0600` mounted files, suitable for
  a Secret Manager CSI driver or short-lived workload identity token projection.
- Provider hosts are explicitly allow-listed; redirects, URL userinfo, fragments, oversized
  responses and non-JSON payloads are rejected. Environment HTTP proxy variables are ignored.
- HTTP is forbidden unless `APP_ENVIRONMENT=local|test` and `--allow-insecure-http` are both set.
- Local identity headers are emitted only when `APP_ENVIRONMENT=local|test`; their role is fixed to
  `APPLICATION_ADAPTER` and cannot be selected from the command line.
- Provider response bodies and bearer tokens are never printed or persisted.
- The Control Plane identity must have only the `APPLICATION_ADAPTER` role.

## DOM entity identity

When multiple visible controls have the same role, name and surrounding text, a tenant-owned UI
Adapter can attach a stable, non-secret entity fence to the control itself or its nearest container.
Generate the attributes without putting the raw CRM/payment/IAM identifier in the DOM:

```bash
python application_adapter.py entity-identity \
  --entity-value-file /run/secrets/current-entity-id \
  --identity-key-file /run/secrets/entity-identity-key \
  --namespace crm.production \
  --entity-type customer
```

The command returns only `data-agent-entity-hash`, `data-agent-entity-scope` and
`data-agent-entity-type`. Apply all three attributes to the interactive element or a composed-tree
ancestor. Browser Node uses the nearest valid tuple only to bind Element ID and reject stale target
reuse; it hashes the tuple again before Browser State publication and never treats it as authority,
permission or proof of business outcome. The identity key must be at least 32 bytes, remain outside
the page, and be scoped per tenant/application to prevent cross-context correlation.

Example attestation:

```bash
python application_adapter.py attest \
  --control-plane-url https://control-plane.example.com \
  --control-plane-token-file /run/secrets/control-plane-token \
  --provider-url https://crm.example.com/api/v1/me \
  --provider-host crm.example.com \
  --provider-token-file /run/secrets/provider-token \
  --session-id ses_1234567890abcdef \
  --context-epoch 7 \
  --state-version 42 \
  --evidence-type ACCOUNT \
  --key current-account \
  --provider-id crm-provider \
  --value-pointer /account/id \
  --expected-value-hash be781897291dcfd5c58baa17cd0602b427af04efd3a67d7f4f9bb13b68fd01ce
```

Application code must acquire a Lease before a payment, form submission, file transfer or
critical transaction, renew it while the work remains active, and release it only after the
business result is known. The `lease-acquire`, `lease-renew` and `lease-release` commands expose
that owner-bound protocol without granting the Adapter general Session operation privileges.
