#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TEST_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/browsercloud-release-bundle.XXXXXX")"
trap 'rm -rf -- "$TEST_ROOT"' EXIT

digest() {
  printf 'sha256:%064d' "$1"
}

SOURCE_COMMIT="0123456789abcdef0123456789abcdef01234567"
BUNDLE="$TEST_ROOT/bundle"
EVIDENCE_ARGS=()
for component in control-plane browser-node web-console operator application-adapter; do
  evidence_path="$TEST_ROOT/$component.spdx.json"
  printf '{"spdxVersion":"SPDX-2.3","name":"%s"}\n' "$component" >"$evidence_path"
  EVIDENCE_ARGS+=(--evidence "$component=$evidence_path")
done
python3 "$REPO_ROOT/tools/supply-chain/release_bundle.py" render \
  --base-dir "$REPO_ROOT/deploy/kubernetes/base" \
  --source-commit "$SOURCE_COMMIT" \
  --output "$BUNDLE" \
  --image "control-plane=ghcr.io/sshiong/agent-browser-cloud-control-plane@$(digest 1)" \
  --image "browser-node=ghcr.io/sshiong/agent-browser-cloud-browser-node@$(digest 2)" \
  --image "web-console=ghcr.io/sshiong/agent-browser-cloud-web-console@$(digest 3)" \
  --image "operator=ghcr.io/sshiong/agent-browser-cloud-operator@$(digest 4)" \
  --image "application-adapter=ghcr.io/sshiong/agent-browser-cloud-application-adapter@$(digest 5)" \
  "${EVIDENCE_ARGS[@]}"

python3 "$REPO_ROOT/tools/supply-chain/release_bundle.py" verify --bundle "$BUNDLE"
kubectl kustomize "$BUNDLE/production" >"$TEST_ROOT/rendered.yaml"

image_count="$(
  awk '/^[[:space:]]+(- )?image: / { count++ } END { print count + 0 }' \
    "$TEST_ROOT/rendered.yaml"
)"
if [[ "$image_count" -ne 6 ]]; then
  echo "expected six digest-locked workload image references, got $image_count" >&2
  exit 1
fi
if awk '/^[[:space:]]+(- )?image: / && $0 !~ /@sha256:[a-f0-9]{64}$/ { exit 1 }' \
  "$TEST_ROOT/rendered.yaml"; then
  :
else
  echo "rendered deployment contains a tag or malformed digest" >&2
  exit 1
fi

if python3 "$REPO_ROOT/tools/supply-chain/release_bundle.py" render \
  --base-dir "$REPO_ROOT/deploy/kubernetes/base" \
  --source-commit "$SOURCE_COMMIT" \
  --output "$TEST_ROOT/tagged" \
  --image "control-plane=ghcr.io/sshiong/agent-browser-cloud-control-plane:latest" \
  --image "browser-node=ghcr.io/sshiong/agent-browser-cloud-browser-node@$(digest 2)" \
  --image "web-console=ghcr.io/sshiong/agent-browser-cloud-web-console@$(digest 3)" \
  --image "operator=ghcr.io/sshiong/agent-browser-cloud-operator@$(digest 4)" \
  --image "application-adapter=ghcr.io/sshiong/agent-browser-cloud-application-adapter@$(digest 5)" \
  "${EVIDENCE_ARGS[@]}"; then
  echo "tagged production image was accepted" >&2
  exit 1
fi

cp "$BUNDLE/release-manifest.json" "$TEST_ROOT/release-manifest.json"
python3 - "$BUNDLE/release-manifest.json" <<'PY'
import json
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
manifest = json.loads(path.read_text())
manifest["images"]["operator"]["digest"] = "sha256:" + ("f" * 64)
path.write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n")
PY
if python3 "$REPO_ROOT/tools/supply-chain/release_bundle.py" verify --bundle "$BUNDLE"; then
  echo "tampered release manifest was accepted" >&2
  exit 1
fi
cp "$TEST_ROOT/release-manifest.json" "$BUNDLE/release-manifest.json"

printf '{"spdxVersion":"SPDX-2.3","name":"tampered"}\n' \
  >"$BUNDLE/evidence/control-plane.spdx.json"
if python3 "$REPO_ROOT/tools/supply-chain/release_bundle.py" verify --bundle "$BUNDLE"; then
  echo "tampered SBOM evidence was accepted" >&2
  exit 1
fi

echo "RELEASE_BUNDLE_TEST_OK"
