#!/usr/bin/env bash

set -euo pipefail

report_failure() {
  exit_code=$?
  printf 'LOGIN_AGENT_MATRIX_FAILED line=%s command=%q exit=%s\n' \
    "${BASH_LINENO[0]}" "$BASH_COMMAND" "$exit_code" >&2
  return "$exit_code"
}
trap report_failure ERR

fail() {
  printf 'real login Agent matrix: %s\n' "$1" >&2
  exit 1
}

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

if command -v orbctl >/dev/null 2>&1; then
  [[ "$(orbctl status)" == "Running" ]]
  [[ "$(docker context show)" == "orbstack" ]]
  [[ "$(docker info --format '{{.OperatingSystem}}')" == "OrbStack" ]]
fi

provider_mode="${LOGIN_AGENT_PROVIDER_MODE:-fixture}"
case "$provider_mode" in
  fixture) ;;
  external)
    [[ -n "${LOCAL_AGENT_MODEL_API_KEY_FILE:-}" ]] || fail "external Provider requires LOCAL_AGENT_MODEL_API_KEY_FILE"
    [[ -n "${LOCAL_AGENT_MODEL_ENDPOINT:-}" ]] || fail "external Provider requires LOCAL_AGENT_MODEL_ENDPOINT"
    [[ -n "${LOCAL_AGENT_MODEL_NAME:-}" ]] || fail "external Provider requires LOCAL_AGENT_MODEL_NAME"
    [[ -n "${LOCAL_AGENT_MODEL_REVISION:-}" ]] || fail "external Provider requires LOCAL_AGENT_MODEL_REVISION"
    [[ "$LOCAL_AGENT_MODEL_API_KEY_FILE" = /* ]] || fail "model API key path must be absolute"
    [[ -f "$LOCAL_AGENT_MODEL_API_KEY_FILE" ]] || fail "model API key must be a regular file"
    [[ ! -L "$LOCAL_AGENT_MODEL_API_KEY_FILE" ]] || fail "model API key must not be a symbolic link"
    [[ -s "$LOCAL_AGENT_MODEL_API_KEY_FILE" ]] || fail "model API key must not be empty"
    if key_mode="$(stat -c '%a' "$LOCAL_AGENT_MODEL_API_KEY_FILE" 2>/dev/null)"; then
      : # GNU stat (Linux)
    else
      key_mode="$(stat -f '%Lp' "$LOCAL_AGENT_MODEL_API_KEY_FILE")" # BSD stat (macOS)
    fi
    [[ "$key_mode" == "600" || "$key_mode" == "400" ]] || fail "model API key mode must be 0600 or 0400"
    [[ "$LOCAL_AGENT_MODEL_ENDPOINT" == https://*/v1/responses ]] || fail "model endpoint must be HTTPS and end in /v1/responses"
    ;;
  *)
    echo "LOGIN_AGENT_PROVIDER_MODE must be fixture or external" >&2
    exit 1
    ;;
esac

java_bin=""
if [[ -n "${JAVA_HOME:-}" ]] && [[ -x "${JAVA_HOME}/bin/java" ]] \
  && "${JAVA_HOME}/bin/java" -version 2>&1 | grep -q '"21'; then
  java_bin="${JAVA_HOME}/bin/java"
elif [[ -x /usr/libexec/java_home ]] \
  && java_home_21="$(/usr/libexec/java_home -v 21 2>/dev/null)" \
  && [[ -x "${java_home_21}/bin/java" ]]; then
  java_bin="${java_home_21}/bin/java"
elif command -v java >/dev/null && java -version 2>&1 | grep -q '"21'; then
  java_bin="$(command -v java)"
else
  echo "Java 21 is required for the real-URL Agent matrix." >&2
  exit 1
fi

chromium_path="${REAL_CHROMIUM_PATH:-}"
if [[ -z "$chromium_path" ]] \
  && [[ -x "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" ]]; then
  chromium_path="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
elif [[ -z "$chromium_path" ]] && command -v chromium >/dev/null; then
  chromium_path="$(command -v chromium)"
elif [[ -z "$chromium_path" ]] && command -v google-chrome >/dev/null; then
  chromium_path="$(command -v google-chrome)"
fi
if [[ -z "$chromium_path" ]] || [[ ! -x "$chromium_path" ]]; then
  echo "Set REAL_CHROMIUM_PATH to an executable Chrome/Chromium binary." >&2
  exit 1
fi

./gradlew -p apps/control-plane bootJar
cargo build --locked --manifest-path apps/browser-node/Cargo.toml \
  --bin network-helper --bin storage-helper --bin node-agent

run_id="$(date +%s)-$$"
postgres_name="agentbrowser-postgres-real-login-${run_id}"
redis_name="agentbrowser-redis-real-login-${run_id}"
temp_dir="$(mktemp -d)"
control_pid=""
node_pid=""
network_helper_pid=""
storage_helper_pid=""
proxy_pid=""
model_pid=""
outcome_worker_pid=""

cleanup() {
  exit_code=$?
  for pid in "$control_pid" "$node_pid" "$network_helper_pid" "$storage_helper_pid" "$proxy_pid" "$model_pid" "$outcome_worker_pid"; do
    if [[ -n "$pid" ]]; then kill "$pid" 2>/dev/null || true; fi
  done
  docker rm -f "$postgres_name" "$redis_name" >/dev/null 2>&1 || true
  if [[ "$exit_code" -ne 0 ]]; then
    tail -n 160 "$temp_dir/control-plane.log" 2>/dev/null || true
    tail -n 160 "$temp_dir/browser-node.log" 2>/dev/null || true
    tail -n 80 "$temp_dir/network-helper.log" 2>/dev/null || true
    tail -n 80 "$temp_dir/storage-helper.log" 2>/dev/null || true
    tail -n 80 "$temp_dir/proxy.log" 2>/dev/null || true
    tail -n 80 "$temp_dir/proxy-events.jsonl" 2>/dev/null || true
    tail -n 80 "$temp_dir/model-events.jsonl" 2>/dev/null || true
    tail -n 120 "$temp_dir/outcome-worker.log" 2>/dev/null || true
  fi
  rm -rf "$temp_dir"
}
trap cleanup EXIT INT TERM

openssl req -x509 -newkey rsa:2048 -nodes -days 2 \
  -subj '/CN=BrowserCloud Real URL CA' \
  -keyout "$temp_dir/ca.key" -out "$temp_dir/ca.crt" >/dev/null 2>&1
openssl req -new -newkey rsa:2048 -nodes \
  -subj '/CN=browser-node.internal' \
  -addext 'subjectAltName=DNS:browser-node.internal' \
  -keyout "$temp_dir/node.key" -out "$temp_dir/node.csr" >/dev/null 2>&1
openssl x509 -req -days 2 -in "$temp_dir/node.csr" \
  -CA "$temp_dir/ca.crt" -CAkey "$temp_dir/ca.key" -CAcreateserial \
  -copy_extensions copy -out "$temp_dir/node.crt" >/dev/null 2>&1
openssl req -new -newkey rsa:2048 -nodes \
  -subj '/CN=control-plane.internal' \
  -addext 'subjectAltName=DNS:control-plane.internal' \
  -keyout "$temp_dir/control-plane.key" -out "$temp_dir/control-plane.csr" >/dev/null 2>&1
openssl x509 -req -days 2 -in "$temp_dir/control-plane.csr" \
  -CA "$temp_dir/ca.crt" -CAkey "$temp_dir/ca.key" -CAcreateserial \
  -copy_extensions copy -out "$temp_dir/control-plane.crt" >/dev/null 2>&1

docker run -d --name "$postgres_name" \
  -e POSTGRES_DB=browsercloud \
  -e POSTGRES_USER=browsercloud \
  -e POSTGRES_PASSWORD=browsercloud \
  -p 127.0.0.1::5432 \
  postgres:17-alpine >/dev/null
docker run -d --name "$redis_name" \
  -p 127.0.0.1::6379 \
  redis:7-alpine >/dev/null

postgres_port="$(docker port "$postgres_name" 5432/tcp | sed -E 's/.*:([0-9]+)$/\1/')"
redis_port="$(docker port "$redis_name" 6379/tcp | sed -E 's/.*:([0-9]+)$/\1/')"
free_port() {
  python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1]); s.close()'
}
node_port="$(free_port)"
control_port="$(free_port)"
event_port="$(free_port)"
desktop_port="$(free_port)"
proxy_port="$(free_port)"
model_port="$(free_port)"

PROXY_ALLOWED_HOSTS="agent-controls.invalid" \
PROXY_EVENT_LOG="$temp_dir/proxy-events.jsonl" \
  python3 "$repo_root/tests/fixtures/allowlist-forward-proxy.py" "$proxy_port" \
  >"$temp_dir/proxy.log" 2>&1 &
proxy_pid=$!

if [[ "$provider_mode" == "fixture" ]]; then
  model_endpoint="http://127.0.0.1:${model_port}/v1/responses"
  model_name="outcome-login-fixture-model"
  model_revision="outcome-login-fixture-v1"
  model_maximum_output_tokens=512
  model_key_file="$temp_dir/model-api-key"
  printf '%s\n' 'login-fixture-model-token' >"$model_key_file"
  chmod 600 "$model_key_file"
  python3 "$repo_root/tests/fixtures/fake-reviewer-model.py" "$model_port" \
    'login-fixture-model-token' "$temp_dir/model-events.jsonl" \
    >"$temp_dir/model.log" 2>&1 &
  model_pid=$!
else
  model_endpoint="$LOCAL_AGENT_MODEL_ENDPOINT"
  model_name="$LOCAL_AGENT_MODEL_NAME"
  model_revision="$LOCAL_AGENT_MODEL_REVISION"
  model_maximum_output_tokens="${LOCAL_AGENT_MODEL_MAXIMUM_OUTPUT_TOKENS:-512}"
  model_key_file="$LOCAL_AGENT_MODEL_API_KEY_FILE"
fi

for _ in $(seq 1 40); do
  if curl -fsS --proxy "http://127.0.0.1:${proxy_port}" \
    "http://browsercloud.invalid/exit" | grep -q '203.0.113.10'; then break; fi
  if ! kill -0 "$proxy_pid" 2>/dev/null; then exit 1; fi
  sleep 0.1
done

NETWORK_HELPER_SOCKET="$temp_dir/network-helper.sock" \
NODE_AGENT_UID="$(id -u)" \
STATIC_PROXY_ENDPOINT="http://127.0.0.1:${proxy_port}" \
STATIC_PROXY_EXPECTED_EXIT_IP="203.0.113.10" \
PROXY_EXIT_CHECK_URL="http://browsercloud.invalid/exit" \
  apps/browser-node/target/debug/network-helper >"$temp_dir/network-helper.log" 2>&1 &
network_helper_pid=$!

STORAGE_HELPER_SOCKET="$temp_dir/storage-helper.sock" \
PROFILE_STORAGE_ROOT="$temp_dir/runtime/profile-storage" \
NODE_AGENT_UID="$(id -u)" \
  apps/browser-node/target/debug/storage-helper >"$temp_dir/storage-helper.log" 2>&1 &
storage_helper_pid=$!

for socket_path in "$temp_dir/network-helper.sock" "$temp_dir/storage-helper.sock"; do
  for _ in $(seq 1 60); do
    if [[ -S "$socket_path" ]]; then break; fi
    sleep 0.1
  done
  [[ -S "$socket_path" ]]
done

CHROMIUM_PATH="$chromium_path" \
NODE_AGENT_PORT="$node_port" \
NODE_ID=node_real_url \
CONTROL_PLANE_EVENT_TARGET="127.0.0.1:${event_port}" \
GRPC_TLS_ENABLED=true \
GRPC_TLS_CA_CERT="$temp_dir/ca.crt" \
GRPC_TLS_CERT="$temp_dir/node.crt" \
GRPC_TLS_KEY="$temp_dir/node.key" \
CONTROL_PLANE_TLS_SERVER_NAME=control-plane.internal \
REMOTE_DESKTOP_GATEWAY_PORT="$desktop_port" \
RUNTIME_ROOT="$temp_dir/runtime" \
PROFILE_STORAGE_ROOT="$temp_dir/runtime/profile-storage" \
STORAGE_HELPER_SOCKET="$temp_dir/storage-helper.sock" \
NETWORK_HELPER_SOCKET="$temp_dir/network-helper.sock" \
  apps/browser-node/target/debug/node-agent >"$temp_dir/browser-node.log" 2>&1 &
node_pid=$!

for _ in $(seq 1 60); do
  docker exec "$postgres_name" pg_isready -U browsercloud -d browsercloud >/dev/null 2>&1 && break
  sleep 0.25
done
for _ in $(seq 1 60); do
  docker exec "$redis_name" redis-cli ping 2>/dev/null | grep -q PONG && break
  sleep 0.1
done

DATABASE_URL="jdbc:postgresql://localhost:${postgres_port}/browsercloud" \
DATABASE_USER=browsercloud \
DATABASE_PASSWORD=browsercloud \
REDIS_HOST=localhost \
REDIS_PORT="$redis_port" \
BROWSER_NODE_GRPC_TARGET="localhost:${node_port}" \
BROWSER_DENSITY_BOOTSTRAP_LOCAL_NODE_ENABLED=false \
CONTROL_PLANE_NODE_EVENT_PORT="$event_port" \
GRPC_TLS_ENABLED=true \
GRPC_TLS_CA_CERT="$temp_dir/ca.crt" \
GRPC_TLS_CERT="$temp_dir/control-plane.crt" \
GRPC_TLS_KEY="$temp_dir/control-plane.key" \
BROWSER_NODE_TLS_SERVER_NAME=browser-node.internal \
STATIC_PROXY_ENDPOINT="http://127.0.0.1:${proxy_port}" \
STATIC_PROXY_EXPECTED_EXIT_IP="203.0.113.10" \
SERVER_PORT="$control_port" \
AGENT_OUTCOME_VERIFIER_EXTERNAL_ENABLED=true \
AGENT_OUTCOME_VERIFIER_DEPLOYMENT_ID=outcome-login-v1 \
AGENT_OUTCOME_VERIFIER_MODEL_NAME="$model_name" \
AGENT_OUTCOME_VERIFIER_MODEL_REVISION="$model_revision" \
AGENT_OUTCOME_VERIFIER_MAXIMUM_OUTPUT_TOKENS="$model_maximum_output_tokens" \
  "$java_bin" -jar apps/control-plane/build/libs/agent-browser-cloud-0.1.0.jar \
  >"$temp_dir/control-plane.log" 2>&1 &
control_pid=$!

health=""
for _ in $(seq 1 120); do
  health="$(curl -fsS "http://localhost:${control_port}/actuator/health" 2>/dev/null || true)"
  if printf '%s' "$health" | grep -q '"status":"UP"'; then break; fi
  if ! kill -0 "$control_pid" 2>/dev/null; then exit 1; fi
  sleep 0.5
done
printf '%s' "$health" | grep -q '"status":"UP"'

browser_nodes=""
for _ in $(seq 1 60); do
  browser_nodes="$(curl -fsS \
    "http://localhost:${control_port}/api/v1/browser-nodes" \
    -H 'X-Tenant-Id: tenant-real-url' \
    -H 'X-Roles: TENANT_ADMIN' 2>/dev/null || true)"
  if printf '%s' "$browser_nodes" | python3 -c \
    'import json,sys; items=json.load(sys.stdin)["items"]; node=next(item for item in items if item["nodeId"] == "node_real_url"); assert node["admissionState"] == "OPEN"; assert node["pressureState"] == "NORMAL"; assert node["labels"]["proxyProviderDescriptor"] == "v1"' \
    2>/dev/null; then
    break
  fi
  if ! kill -0 "$node_pid" 2>/dev/null; then exit 1; fi
  sleep 0.25
done
printf '%s' "$browser_nodes" | python3 -c \
  'import json,sys; items=json.load(sys.stdin)["items"]; node=next(item for item in items if item["nodeId"] == "node_real_url"); assert node["admissionState"] == "OPEN"; assert node["labels"]["proxyProviderDescriptor"] == "v1"'

printf '%s\n' 'outcome-worker-control-token-unused' >"$temp_dir/outcome-worker-token"
chmod 600 "$temp_dir/outcome-worker-token"
python3 "$repo_root/apps/agent-worker/outcome_verifier_worker.py" \
  --control-plane-url="http://127.0.0.1:${control_port}" \
  --control-plane-token-file="$temp_dir/outcome-worker-token" \
  --worker-id=outcome-login-worker \
  --deployment-id=outcome-login-v1 \
  --model-endpoint="$model_endpoint" \
  --model-api-key-file="$model_key_file" \
  --model-name="$model_name" \
  --expected-response-model="${LOCAL_AGENT_MODEL_RESPONSE_NAME:-}" \
  --model-revision="$model_revision" \
  --maximum-output-tokens="$model_maximum_output_tokens" \
  --model-timeout-seconds="${LOCAL_AGENT_MODEL_TIMEOUT_SECONDS:-120}" \
  --environment=test --poll-seconds=0.25 --heartbeat-seconds=5 \
  >"$temp_dir/outcome-worker.log" 2>&1 &
outcome_worker_pid=$!
sleep 0.5
kill -0 "$outcome_worker_pid"

BROWSER_VERSION="$("$chromium_path" --version 2>/dev/null || echo unknown)" \
LOGIN_AGENT_TASK_TIMEOUT_SECONDS="${LOGIN_AGENT_TASK_TIMEOUT_SECONDS:-120}" \
python3 "$repo_root/tests/compatibility/real_login_agent_matrix.py" \
  "http://localhost:${control_port}" \
  "$temp_dir/model-events.jsonl" \
  "$provider_mode" \
  "$model_name" \
  "$model_revision"

grep -q '"event": "login_attempt".*"success": true' "$temp_dir/proxy-events.jsonl"
grep -q '"event": "login_attempt".*"success": false' "$temp_dir/proxy-events.jsonl"

echo "Controlled login Agent matrix passed with real Chrome, sensitive-input references, Outcome verification, and Provider mode ${provider_mode}."
