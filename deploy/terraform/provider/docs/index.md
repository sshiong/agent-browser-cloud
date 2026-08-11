---
page_title: "Provider: Agent Browser Cloud"
description: "Manage workspace metadata through the authoritative Agent Browser Cloud API."
---

# Agent Browser Cloud Provider

Use this provider to manage tenant Workspace Groups and Tags without bypassing Control Plane RBAC,
idempotency, audit, or PostgreSQL authority.

## Authentication

```hcl
provider "browsercloud" {
  endpoint = "https://browsercloud.example.com"
  token    = var.browsercloud_token
}
```

Production mode requires HTTPS and a bearer token. `local_development` exists only for loopback
integration tests and must not be enabled against a remote host.

## Schema

- `endpoint` (optional): Control Plane origin; alternatively `BROWSERCLOUD_ENDPOINT`.
- `token` (optional, sensitive): OIDC bearer token; alternatively `BROWSERCLOUD_TOKEN`.
- `local_development` (optional): enable loopback-only local Header identity.
- `tenant_id`, `actor_id` (optional): required only with `local_development`.
