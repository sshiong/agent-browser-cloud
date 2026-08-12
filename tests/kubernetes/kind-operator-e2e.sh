#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
KIND_BIN="${KIND_BIN:-kind}"
KUBECTL_BIN="${KUBECTL_BIN:-kubectl}"
CLUSTER_NAME="${KIND_CLUSTER_NAME:-agent-browser-operator}"
KUBE_CONTEXT="kind-${CLUSTER_NAME}"
NAMESPACE="browsercloud-system"
OPERATOR_IMAGE="agent-browser-cloud/operator:kind"
N_MINUS_ONE_OPERATOR_IMAGE="agent-browser-cloud/operator:kind-n-minus-one"
N_MINUS_ONE_COMMIT="${N_MINUS_ONE_COMMIT:-$(git -C "${ROOT_DIR}" rev-parse HEAD^)}"
MOCK_IMAGE="agent-browser-cloud/mock-control-plane:kind"
PYTHON_BASE_IMAGE="${PYTHON_BASE_IMAGE:-cgr.dev/chainguard/python@sha256:a0365f7b90bf7b78a5e35f2709efb7c9263acf9c7b1905e0ec4c3e943c88e64d}"
PORT_FORWARD_PID=""
CLUSTER_CREATED=false
N_MINUS_ONE_CONTEXT="$(mktemp -d)"

cleanup() {
  if [[ -n "${PORT_FORWARD_PID}" ]]; then
    kill "${PORT_FORWARD_PID}" >/dev/null 2>&1 || true
  fi
  "${KIND_BIN}" delete cluster --name "${CLUSTER_NAME}" >/dev/null 2>&1 || true
  rm -rf "${N_MINUS_ONE_CONTEXT}"
}

failure_context() {
  local exit_code=$?
  printf 'Kubernetes E2E failed at line %s: %s\n' "${BASH_LINENO[0]}" "${BASH_COMMAND}" >&2
  if [[ "${CLUSTER_CREATED}" == true ]]; then
    "${KUBECTL_BIN}" --context "${KUBE_CONTEXT}" -n "${NAMESPACE}" \
      get deployments,pods -l app.kubernetes.io/name=browser-session-operator >&2 || true
    "${KUBECTL_BIN}" --context "${KUBE_CONTEXT}" -n "${NAMESPACE}" \
      get deployments,pods -l app.kubernetes.io/name=mock-control-plane >&2 || true
    "${KUBECTL_BIN}" --context "${KUBE_CONTEXT}" -n "${NAMESPACE}" \
      logs -l app.kubernetes.io/name=browser-session-operator \
      --all-containers=true --max-log-requests=10 --tail=100 --prefix=true >&2 || true
  fi
  exit "${exit_code}"
}

trap failure_context ERR
trap cleanup EXIT

wait_for_jsonpath() {
  local resource=$1
  local jsonpath=$2
  local expected=$3
  local timeout_seconds=${4:-90}
  local deadline=$((SECONDS + timeout_seconds))
  while ((SECONDS < deadline)); do
    local actual
    actual="$("${KUBECTL_BIN}" --context "${KUBE_CONTEXT}" -n "${NAMESPACE}" \
      get "${resource}" -o "jsonpath=${jsonpath}" 2>/dev/null || true)"
    if [[ "${actual}" == "${expected}" ]]; then
      return 0
    fi
    sleep 2
  done
  printf 'Timed out waiting for %s %s=%s\n' "${resource}" "${jsonpath}" "${expected}" >&2
  return 1
}

docker_build_with_retry() {
  local image=$1
  local context=$2
  local attempt
  for attempt in 1 2 3 4 5; do
    if docker build --build-arg "PYTHON_BASE_IMAGE=${PYTHON_BASE_IMAGE}" \
      -t "${image}" "${context}"; then
      return 0
    fi
    if ((attempt == 5)); then
      printf 'Docker build failed after %s attempts: %s\n' "${attempt}" "${image}" >&2
      return 1
    fi
    printf 'Docker build attempt %s failed; retrying pinned base image in %ss\n' \
      "${attempt}" "$((attempt * 3))" >&2
    sleep "$((attempt * 3))"
  done
}

command -v docker >/dev/null
command -v "${KUBECTL_BIN}" >/dev/null
command -v "${KIND_BIN}" >/dev/null

docker_build_with_retry "${OPERATOR_IMAGE}" "${ROOT_DIR}/tools/browser-session-operator"
git -C "${ROOT_DIR}" archive "${N_MINUS_ONE_COMMIT}" tools/browser-session-operator |
  tar -x -C "${N_MINUS_ONE_CONTEXT}"
docker_build_with_retry "${N_MINUS_ONE_OPERATOR_IMAGE}" \
  "${N_MINUS_ONE_CONTEXT}/tools/browser-session-operator"
docker_build_with_retry "${MOCK_IMAGE}" "${ROOT_DIR}/tests/kubernetes/fixtures"

"${KIND_BIN}" create cluster --name "${CLUSTER_NAME}" --wait 90s
CLUSTER_CREATED=true
"${KIND_BIN}" load docker-image --name "${CLUSTER_NAME}" \
  "${OPERATOR_IMAGE}" "${N_MINUS_ONE_OPERATOR_IMAGE}" "${MOCK_IMAGE}"

"${KUBECTL_BIN}" --context "${KUBE_CONTEXT}" apply \
  -f "${ROOT_DIR}/deploy/kubernetes/base/namespace.yaml" \
  -f "${ROOT_DIR}/deploy/kubernetes/base/browser-session-crd.yaml" \
  -f "${ROOT_DIR}/deploy/kubernetes/base/operator-rbac.yaml" \
  -f "${ROOT_DIR}/deploy/kubernetes/base/operator-observability.yaml"
"${KUBECTL_BIN}" --context "${KUBE_CONTEXT}" -n "${NAMESPACE}" create secret generic \
  browser-session-operator-credentials --from-literal=token=kind-test-token

"${KUBECTL_BIN}" --context "${KUBE_CONTEXT}" apply --server-side --dry-run=server \
  -f "${ROOT_DIR}/deploy/kubernetes/base/operator-deployment.yaml" -o json |
  python3 -c '
import json
import sys

deployment = json.load(sys.stdin)
security = deployment["spec"]["template"]["spec"]["containers"][0]["securityContext"]
assert security["appArmorProfile"]["type"] == "RuntimeDefault"
container = deployment["spec"]["template"]["spec"]["containers"][0]
assert container["ports"] == [
    {"name": "metrics", "containerPort": 8080, "protocol": "TCP"}
]
'
"${KUBECTL_BIN}" --context "${KUBE_CONTEXT}" apply --server-side --dry-run=server \
  -f "${ROOT_DIR}/tests/kubernetes/mock-control-plane.yaml" >/dev/null

"${KUBECTL_BIN}" --context "${KUBE_CONTEXT}" create --dry-run=client \
  -f "${ROOT_DIR}/deploy/kubernetes/base/operator-deployment.yaml" -o json |
  python3 -c '
import json
import sys

deployment = json.load(sys.stdin)
pod_spec = deployment["spec"]["template"]["spec"]
pod_spec.pop("nodeSelector", None)
container = pod_spec["containers"][0]
container["image"] = sys.argv[1]
container["imagePullPolicy"] = "Never"
container["securityContext"].pop("appArmorProfile", None)
container["env"] = [
    entry for entry in container["env"] if entry["name"] != "CONTROL_PLANE_CA"
]
for entry in container["env"]:
    if entry["name"] == "CONTROL_PLANE_URL":
        entry.pop("valueFrom", None)
        entry["value"] = "http://mock-control-plane.browsercloud-system.svc:8080"
json.dump(deployment, sys.stdout)
' "${N_MINUS_ONE_OPERATOR_IMAGE}" |
  "${KUBECTL_BIN}" --context "${KUBE_CONTEXT}" apply -f -

"${KUBECTL_BIN}" --context "${KUBE_CONTEXT}" create --dry-run=client \
  -f "${ROOT_DIR}/tests/kubernetes/mock-control-plane.yaml" -o json |
  python3 -c '
import json
import sys

raw = sys.stdin.read()
decoder = json.JSONDecoder()
items = []
while raw.strip():
    raw = raw.lstrip()
    item, offset = decoder.raw_decode(raw)
    items.append(item)
    raw = raw[offset:]
for item in items:
    if item["kind"] == "Deployment":
        security = item["spec"]["template"]["spec"]["containers"][0]["securityContext"]
        security.pop("appArmorProfile", None)
json.dump({"apiVersion": "v1", "kind": "List", "items": items}, sys.stdout)
' |
  "${KUBECTL_BIN}" --context "${KUBE_CONTEXT}" apply -f -

"${KUBECTL_BIN}" --context "${KUBE_CONTEXT}" -n "${NAMESPACE}" \
  rollout status deployment/mock-control-plane --timeout=90s
"${KUBECTL_BIN}" --context "${KUBE_CONTEXT}" -n "${NAMESPACE}" \
  rollout status deployment/browser-session-operator --timeout=90s

"${KUBECTL_BIN}" --context "${KUBE_CONTEXT}" auth can-i get leases.coordination.k8s.io \
  --as "system:serviceaccount:${NAMESPACE}:browser-session-operator" \
  --namespace "${NAMESPACE}" | grep -qx yes
secret_permission="$("${KUBECTL_BIN}" --context "${KUBE_CONTEXT}" auth can-i create secrets \
  --as "system:serviceaccount:${NAMESPACE}:browser-session-operator" \
  --namespace "${NAMESPACE}" || true)"
test "${secret_permission}" = "no"

"${KUBECTL_BIN}" --context "${KUBE_CONTEXT}" -n "${NAMESPACE}" \
  get deployment browser-session-operator -o json |
  python3 -c '
import json
import sys

deployment = json.load(sys.stdin)
container = deployment["spec"]["template"]["spec"]["containers"][0]
security = container["securityContext"]
assert security["allowPrivilegeEscalation"] is False
assert security["readOnlyRootFilesystem"] is True
assert security["capabilities"]["drop"] == ["ALL"]
assert security["seccompProfile"]["type"] == "RuntimeDefault"
'

cat <<'YAML' | "${KUBECTL_BIN}" --context "${KUBE_CONTEXT}" apply -f -
apiVersion: browsercloud.io/v1alpha1
kind: BrowserSession
metadata:
  name: kind-primary
  namespace: browsercloud-system
spec:
  tenantId: tenant-kind
  profileId: profile-kind
  region: local
  executionEnvironment: SYSTEM_MANAGED
  resourcePolicy:
    mode: AUTO
    onMaximumReached: PAUSE_AGENT
YAML

wait_for_jsonpath "browsersession/kind-primary" "{.status.phase}" "Ready"
wait_for_jsonpath "browsersession/kind-primary" "{.status.observedGeneration}" "1"
wait_for_jsonpath "browsersession/kind-primary" "{.metadata.finalizers[0]}" \
  "browsercloud.io/session-cleanup"

leader="$("${KUBECTL_BIN}" --context "${KUBE_CONTEXT}" -n "${NAMESPACE}" \
  get lease browser-session-operator -o jsonpath='{.spec.holderIdentity}')"
test -n "${leader}"
"${KUBECTL_BIN}" --context "${KUBE_CONTEXT}" -n "${NAMESPACE}" get pod "${leader}" >/dev/null
"${KUBECTL_BIN}" --context "${KUBE_CONTEXT}" -n "${NAMESPACE}" delete pod "${leader}" \
  --wait=false

deadline=$((SECONDS + 90))
new_leader=""
while ((SECONDS < deadline)); do
  new_leader="$("${KUBECTL_BIN}" --context "${KUBE_CONTEXT}" -n "${NAMESPACE}" \
    get lease browser-session-operator -o jsonpath='{.spec.holderIdentity}' 2>/dev/null || true)"
  if [[ -n "${new_leader}" && "${new_leader}" != "${leader}" ]]; then
    break
  fi
  sleep 2
done
test -n "${new_leader}"
test "${new_leader}" != "${leader}"

cat <<'YAML' | "${KUBECTL_BIN}" --context "${KUBE_CONTEXT}" apply -f -
apiVersion: browsercloud.io/v1alpha1
kind: BrowserSession
metadata:
  name: kind-after-failover
  namespace: browsercloud-system
spec:
  tenantId: tenant-kind
  profileId: profile-kind
YAML
wait_for_jsonpath "browsersession/kind-after-failover" "{.status.phase}" "Ready"

"${KUBECTL_BIN}" --context "${KUBE_CONTEXT}" -n "${NAMESPACE}" \
  set image deployment/browser-session-operator "operator=${OPERATOR_IMAGE}"
"${KUBECTL_BIN}" --context "${KUBE_CONTEXT}" -n "${NAMESPACE}" \
  rollout status deployment/browser-session-operator --timeout=90s
deadline=$((SECONDS + 90))
while ((SECONDS < deadline)); do
  operator_logs="$("${KUBECTL_BIN}" --context "${KUBE_CONTEXT}" -n "${NAMESPACE}" \
    logs -l app.kubernetes.io/name=browser-session-operator \
    --all-containers=true --max-log-requests=10 --prefix=true 2>/dev/null || true)"
  if grep -q "list-watch cache synchronized" <<<"${operator_logs}"; then
    break
  fi
  sleep 2
done
operator_logs="$("${KUBECTL_BIN}" --context "${KUBE_CONTEXT}" -n "${NAMESPACE}" \
  logs -l app.kubernetes.io/name=browser-session-operator \
  --all-containers=true --max-log-requests=10 --prefix=true)"
grep -q "list-watch cache synchronized" <<<"${operator_logs}"
metrics_leader=""
deadline=$((SECONDS + 90))
while ((SECONDS < deadline)); do
  candidate="$("${KUBECTL_BIN}" --context "${KUBE_CONTEXT}" -n "${NAMESPACE}" \
    get lease browser-session-operator -o jsonpath='{.spec.holderIdentity}' 2>/dev/null || true)"
  phase="$("${KUBECTL_BIN}" --context "${KUBE_CONTEXT}" -n "${NAMESPACE}" \
    get pod "${candidate}" -o jsonpath='{.status.phase}' 2>/dev/null || true)"
  ready="$("${KUBECTL_BIN}" --context "${KUBE_CONTEXT}" -n "${NAMESPACE}" \
    get pod "${candidate}" -o jsonpath='{.status.containerStatuses[0].ready}' \
    2>/dev/null || true)"
  image="$("${KUBECTL_BIN}" --context "${KUBE_CONTEXT}" -n "${NAMESPACE}" \
    get pod "${candidate}" -o jsonpath='{.spec.containers[0].image}' \
    2>/dev/null || true)"
  if [[ -n "${candidate}" && "${phase}" == "Running" && "${ready}" == "true" \
    && "${image}" == "${OPERATOR_IMAGE}" ]]; then
    metrics_leader="${candidate}"
    break
  fi
  sleep 2
done
test -n "${metrics_leader}"
"${KUBECTL_BIN}" --context "${KUBE_CONTEXT}" -n "${NAMESPACE}" \
  port-forward "pod/${metrics_leader}" 18081:8080 \
  >/tmp/agentbrowser-kind-operator-metrics-port-forward.log 2>&1 &
PORT_FORWARD_PID=$!
metrics_health_ready=false
for _ in {1..30}; do
  if curl --fail --silent http://127.0.0.1:18081/healthz >/dev/null; then
    metrics_health_ready=true
    break
  fi
  if ! kill -0 "${PORT_FORWARD_PID}" >/dev/null 2>&1; then
    cat /tmp/agentbrowser-kind-operator-metrics-port-forward.log >&2
    break
  fi
  sleep 1
done
test "${metrics_health_ready}" = true
curl --fail --silent http://127.0.0.1:18081/healthz | grep -qx ok
metrics=""
for _ in {1..30}; do
  metrics="$(curl --fail --silent http://127.0.0.1:18081/metrics)"
  if grep -q "browsercloud_operator_watch_restarts_total" <<<"${metrics}"; then
    break
  fi
  sleep 1
done
METRICS="${metrics}" python3 -c '
import os
import re

metrics = os.environ["METRICS"]
required = (
    "browsercloud_operator_build_info 1",
    "browsercloud_operator_leader 1",
    "browsercloud_operator_last_successful_lease_unixtime",
    "browsercloud_operator_last_successful_snapshot_unixtime",
    "browsercloud_operator_watch_restarts_total",
    "browsercloud_operator_reconcile_duration_seconds_bucket",
)
for sample in required:
    assert sample in metrics, sample
match = re.search(
    r"browsercloud_operator_list_snapshots_total\{result=\"success\"\} ([0-9.]+)",
    metrics,
)
assert match and float(match.group(1)) >= 1, metrics
'
kill "${PORT_FORWARD_PID}" >/dev/null 2>&1 || true
wait "${PORT_FORWARD_PID}" >/dev/null 2>&1 || true
PORT_FORWARD_PID=""
wait_for_jsonpath "browsersession/kind-primary" "{.status.phase}" "Ready"
wait_for_jsonpath "browsersession/kind-after-failover" "{.status.phase}" "Ready"

cat <<'YAML' | "${KUBECTL_BIN}" --context "${KUBE_CONTEXT}" apply -f -
apiVersion: browsercloud.io/v1alpha1
kind: BrowserSession
metadata:
  name: kind-after-upgrade
  namespace: browsercloud-system
spec:
  tenantId: tenant-kind
  profileId: profile-kind
  region: local
  executionEnvironment: CONTAINER
  resourcePolicy:
    mode: AUTO
YAML
wait_for_jsonpath "browsersession/kind-after-upgrade" "{.status.phase}" "Ready"

"${KUBECTL_BIN}" --context "${KUBE_CONTEXT}" -n "${NAMESPACE}" \
  set image deployment/browser-session-operator "operator=${N_MINUS_ONE_OPERATOR_IMAGE}"
"${KUBECTL_BIN}" --context "${KUBE_CONTEXT}" -n "${NAMESPACE}" \
  rollout status deployment/browser-session-operator --timeout=90s
wait_for_jsonpath "browsersession/kind-after-upgrade" "{.status.phase}" "Ready"

cat <<'YAML' | "${KUBECTL_BIN}" --context "${KUBE_CONTEXT}" apply -f -
apiVersion: browsercloud.io/v1alpha1
kind: BrowserSession
metadata:
  name: kind-after-rollback
  namespace: browsercloud-system
spec:
  tenantId: tenant-kind
  profileId: profile-kind
YAML
wait_for_jsonpath "browsersession/kind-after-rollback" "{.status.phase}" "Ready"

if cat <<'YAML' | "${KUBECTL_BIN}" --context "${KUBE_CONTEXT}" apply -f - >/dev/null 2>&1
apiVersion: browsercloud.io/v1alpha1
kind: BrowserSession
metadata:
  name: kind-invalid
  namespace: browsercloud-system
spec:
  tenantId: "invalid tenant"
  profileId: profile-kind
YAML
then
  printf 'CRD admission accepted an invalid tenantId\n' >&2
  exit 1
fi

"${KUBECTL_BIN}" --context "${KUBE_CONTEXT}" -n "${NAMESPACE}" \
  delete browsersession kind-primary
"${KUBECTL_BIN}" --context "${KUBE_CONTEXT}" -n "${NAMESPACE}" \
  wait --for=delete browsersession/kind-primary --timeout=90s

"${KUBECTL_BIN}" --context "${KUBE_CONTEXT}" -n "${NAMESPACE}" \
  port-forward service/mock-control-plane 18080:8080 >/tmp/agentbrowser-kind-port-forward.log 2>&1 &
PORT_FORWARD_PID=$!
for _ in {1..30}; do
  if curl --fail --silent http://127.0.0.1:18080/health >/dev/null; then
    break
  fi
  sleep 1
done
stats="$(curl --fail --silent http://127.0.0.1:18080/stats)"
STATS="${stats}" python3 -c '
import json
import os

stats = json.loads(os.environ["STATS"])
assert stats["createCalls"] == 4, stats
assert stats["terminateCalls"] == 1, stats
'

printf 'Kubernetes operator N/N-1 E2E passed: baseline=%s leader=%s failoverLeader=%s stats=%s\n' \
  "${N_MINUS_ONE_COMMIT}" "${leader}" "${new_leader}" "${stats}"
