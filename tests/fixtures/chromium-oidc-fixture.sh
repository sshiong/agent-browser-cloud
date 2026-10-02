#!/usr/bin/env bash
set -euo pipefail
# Trust only the ephemeral owned callback certificate's public key. Public IdP TLS stays normal.
exec "${REAL_URL_CHROMIUM_BINARY:?}" \
  "--ignore-certificate-errors-spki-list=${REAL_URL_OIDC_FIXTURE_SPKI:?}" "$@"
