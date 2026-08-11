# Agent Browser Cloud Terraform Provider

The provider manages tenant workspace objects through the authoritative Agent Browser Cloud
Control Plane API. It does not connect to PostgreSQL, Browser Nodes, cgroups, or cloud credentials.

Supported in `0.1.0`:

- `browsercloud_group`: full create/read/update/delete plus exact Session membership convergence;
- `browsercloud_tag`: full create/read/update/delete plus exact Session assignment convergence;
- `browsercloud_workspace_settings`: read effective PostgreSQL-backed Workspace defaults.

Production configuration requires an HTTPS endpoint and an OIDC bearer token. The token is marked
sensitive and is never stored by a resource. Redirects are rejected so credentials cannot cross an
origin boundary. Local Header authentication is available only when `local_development = true` and
the endpoint resolves syntactically to localhost or a loopback IP.

```hcl
terraform {
  required_providers {
    browsercloud = {
      source  = "sshiong/browsercloud"
      version = "~> 0.1"
    }
  }
}

provider "browsercloud" {
  endpoint = "https://browsercloud.example.com"
  token    = var.browsercloud_token
}
```

Environment alternatives are `BROWSERCLOUD_ENDPOINT` and `BROWSERCLOUD_TOKEN`. Local development
additionally supports `BROWSERCLOUD_TENANT_ID` and `BROWSERCLOUD_ACTOR_ID`.

## Development verification

```bash
go test ./...
go vet ./...
go build -trimpath -o /tmp/terraform-provider-browsercloud .
```

Release tags use `terraform-provider-vX.Y.Z`. The dedicated release workflow verifies the tag
against `VERSION`, cross-builds deterministic archives, creates SHA-256 checksums, signs the
checksum with the configured Terraform Registry GPG identity, attaches GitHub OIDC provenance, and
publishes an immutable GitHub release. Registry namespace ownership, public GPG key registration,
and repository secrets remain organization release gates and are never emulated in source.
