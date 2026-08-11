#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
WORKFLOW="$REPO_ROOT/.github/workflows/terraform-provider-release.yml"
CONFIG="$REPO_ROOT/deploy/terraform/provider/.goreleaser.yml"
VERSION_FILE="$REPO_ROOT/deploy/terraform/provider/VERSION"

require_fixed() {
  local file="$1"
  local text="$2"
  if ! grep -Fq -- "$text" "$file"; then
    echo "missing Terraform Provider release invariant '$text' in ${file#"$REPO_ROOT/"}" >&2
    exit 1
  fi
}

if ! grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+$' "$VERSION_FILE"; then
  echo "Terraform Provider VERSION must be strict SemVer without a mutable suffix" >&2
  exit 1
fi

require_fixed "$WORKFLOW" 'tags:'
require_fixed "$WORKFLOW" '"terraform-provider-v*"'
require_fixed "$WORKFLOW" 'TERRAFORM_PROVIDER_GPG_PRIVATE_KEY'
require_fixed "$WORKFLOW" 'TERRAFORM_PROVIDER_GPG_PASSPHRASE'
require_fixed "$WORKFLOW" 'actions/attest-build-provenance@8beda2b7ed98355c0e97c0a63bec38ae472e66c4'
require_fixed "$WORKFLOW" 'goreleaser/goreleaser-action@f06c13b6b1a9625abc9e6e439d9c05a8f2190e94'
require_fixed "$WORKFLOW" 'args: release --clean --skip=validate'
require_fixed "$WORKFLOW" 'PROVIDER_VERSION: ${{ env.PROVIDER_VERSION }}'
require_fixed "$WORKFLOW" 'gh release create "${GITHUB_REF_NAME}"'
require_fixed "$WORKFLOW" '--verify-tag'

require_fixed "$CONFIG" 'version: 2'
require_fixed "$CONFIG" 'goos: [darwin, linux, windows]'
require_fixed "$CONFIG" 'goarch: [amd64, arm64]'
require_fixed "$CONFIG" 'CGO_ENABLED=0'
require_fixed "$CONFIG" '{{ .Env.PROVIDER_VERSION }}'
require_fixed "$CONFIG" 'artifacts: checksum'
require_fixed "$CONFIG" 'cmd: gpg'
require_fixed "$CONFIG" 'release:'
require_fixed "$CONFIG" 'disable: true'

if grep -Eq 'uses: [^[:space:]@]+@(main|master|v[0-9]+)([[:space:]]|$)' "$WORKFLOW"; then
  echo "Terraform Provider release workflow contains a mutable action reference" >&2
  exit 1
fi

echo "TERRAFORM_PROVIDER_RELEASE_TEST_OK"
