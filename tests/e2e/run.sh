#!/usr/bin/env bash

set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

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
  echo "Java 21 is required for the real Web Console E2E test." >&2
  exit 1
fi

./gradlew -p apps/control-plane bootJar
cargo build --locked --manifest-path apps/browser-node/Cargo.toml \
  --bin network-helper --bin storage-helper --bin node-agent

run_id="$(date +%s)-$$"
postgres_name="agentbrowser-postgres-e2e-${run_id}"
redis_name="agentbrowser-redis-e2e-${run_id}"
temp_dir="$(mktemp -d)"
control_pid=""
node_pid=""
network_helper_pid=""
storage_helper_pid=""
web_pid=""
proxy_pid=""

cleanup() {
  exit_code=$?
  if [[ -n "$web_pid" ]]; then kill "$web_pid" 2>/dev/null || true; fi
  if [[ -n "$control_pid" ]]; then kill "$control_pid" 2>/dev/null || true; fi
  if [[ -n "$node_pid" ]]; then kill "$node_pid" 2>/dev/null || true; fi
  if [[ -n "$network_helper_pid" ]]; then kill "$network_helper_pid" 2>/dev/null || true; fi
  if [[ -n "$storage_helper_pid" ]]; then kill "$storage_helper_pid" 2>/dev/null || true; fi
  if [[ -n "$proxy_pid" ]]; then kill "$proxy_pid" 2>/dev/null || true; fi
  docker rm -f "$postgres_name" "$redis_name" >/dev/null 2>&1 || true
  if [[ "$exit_code" -ne 0 ]]; then
    tail -n 120 "$temp_dir/control-plane.log" 2>/dev/null || true
    tail -n 80 "$temp_dir/browser-node.log" 2>/dev/null || true
    tail -n 80 "$temp_dir/network-helper.log" 2>/dev/null || true
    tail -n 80 "$temp_dir/storage-helper.log" 2>/dev/null || true
    tail -n 80 "$temp_dir/web-console.log" 2>/dev/null || true
    tail -n 120 "$temp_dir/vnc-events.jsonl" 2>/dev/null || true
  fi
  rm -rf "$temp_dir"
}
trap cleanup EXIT INT TERM

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
node_port="$(python3 -c 'import socket; s=socket.socket(); s.bind(("", 0)); print(s.getsockname()[1]); s.close()')"
control_port="$(python3 -c 'import socket; s=socket.socket(); s.bind(("", 0)); print(s.getsockname()[1]); s.close()')"
event_port="$(python3 -c 'import socket; s=socket.socket(); s.bind(("", 0)); print(s.getsockname()[1]); s.close()')"
web_port="$(python3 -c 'import socket; s=socket.socket(); s.bind(("", 0)); print(s.getsockname()[1]); s.close()')"
desktop_port="$(python3 -c 'import socket; s=socket.socket(); s.bind(("", 0)); print(s.getsockname()[1]); s.close()')"
proxy_port="$(python3 -c 'import socket; s=socket.socket(); s.bind(("", 0)); print(s.getsockname()[1]); s.close()')"
screenshot_path="${WEB_CONSOLE_SCREENSHOT:-/tmp/agent-browser-cloud-session-flow.png}"
ticket_secret="browsercloud-e2e-remote-desktop-ticket-secret-v1"

python3 "$repo_root/tests/fixtures/fake-http-proxy.py" \
  "$proxy_port" "$temp_dir/proxy-events.jsonl" \
  >"$temp_dir/proxy.log" 2>&1 &
proxy_pid=$!

NETWORK_HELPER_SOCKET="$temp_dir/network-helper.sock" \
NODE_AGENT_UID="$(id -u)" \
STATIC_PROXY_ENDPOINT="http://127.0.0.1:${proxy_port}" \
STATIC_PROXY_EXPECTED_EXIT_IP="203.0.113.10" \
PROXY_EXIT_CHECK_URL="http://browsercloud.invalid/exit" \
  apps/browser-node/target/debug/network-helper >"$temp_dir/network-helper.log" 2>&1 &
network_helper_pid=$!
for _ in $(seq 1 40); do
  if [[ -S "$temp_dir/network-helper.sock" ]]; then break; fi
  if ! kill -0 "$network_helper_pid" 2>/dev/null; then exit 1; fi
  sleep 0.1
done
test -S "$temp_dir/network-helper.sock"

STORAGE_HELPER_SOCKET="$temp_dir/storage-helper.sock" \
PROFILE_STORAGE_ROOT="$temp_dir/runtime/profile-storage" \
NODE_AGENT_UID="$(id -u)" \
  apps/browser-node/target/debug/storage-helper >"$temp_dir/storage-helper.log" 2>&1 &
storage_helper_pid=$!
for _ in $(seq 1 40); do
  if [[ -S "$temp_dir/storage-helper.sock" ]]; then break; fi
  if ! kill -0 "$storage_helper_pid" 2>/dev/null; then exit 1; fi
  sleep 0.1
done
test -S "$temp_dir/storage-helper.sock"

for _ in $(seq 1 40); do
  docker exec "$postgres_name" pg_isready -U browsercloud -d browsercloud >/dev/null 2>&1 && break
  sleep 0.5
done
for _ in $(seq 1 40); do
  docker exec "$redis_name" redis-cli ping 2>/dev/null | grep -q PONG && break
  sleep 0.25
done

CHROMIUM_PATH="$repo_root/tests/fixtures/fake-chromium.sh" \
NODE_AGENT_PORT="$node_port" \
NODE_ID=node-e2e \
CONTROL_PLANE_EVENT_TARGET="127.0.0.1:${event_port}" \
RUNTIME_ROOT="$temp_dir/runtime" \
PROFILE_STORAGE_ROOT="$temp_dir/runtime/profile-storage" \
STORAGE_HELPER_SOCKET="$temp_dir/storage-helper.sock" \
REMOTE_DESKTOP_GATEWAY_PORT="$desktop_port" \
REMOTE_DESKTOP_TICKET_SECRET="$ticket_secret" \
REMOTE_DESKTOP_ALLOWED_ORIGINS="http://127.0.0.1:${web_port}" \
XVFB_PATH="$repo_root/tests/fixtures/fake-xvfb.sh" \
X11VNC_PATH="$repo_root/tests/fixtures/fake-x11vnc.py" \
FAKE_VNC_EVENT_LOG="$temp_dir/vnc-events.jsonl" \
NETWORK_HELPER_SOCKET="$temp_dir/network-helper.sock" \
FAKE_CHROMIUM_REQUIRE_PROXY=true \
RUST_LOG="node_agent=debug,remote_desktop_gateway=debug" \
  apps/browser-node/target/debug/node-agent >"$temp_dir/browser-node.log" 2>&1 &
node_pid=$!

DATABASE_URL="jdbc:postgresql://localhost:${postgres_port}/browsercloud" \
DATABASE_USER=browsercloud \
DATABASE_PASSWORD=browsercloud \
REDIS_HOST=localhost \
REDIS_PORT="$redis_port" \
BROWSER_NODE_GRPC_TARGET="localhost:${node_port}" \
CONTROL_PLANE_NODE_EVENT_PORT="$event_port" \
REMOTE_DESKTOP_TICKET_SECRET="$ticket_secret" \
STATIC_PROXY_ENDPOINT="http://127.0.0.1:${proxy_port}" \
STATIC_PROXY_EXPECTED_EXIT_IP="203.0.113.10" \
SERVER_PORT="$control_port" \
  "$java_bin" -jar apps/control-plane/build/libs/agent-browser-cloud-0.1.0.jar \
  >"$temp_dir/control-plane.log" 2>&1 &
control_pid=$!

health=""
for _ in $(seq 1 90); do
  health="$(curl -fsS "http://127.0.0.1:${control_port}/actuator/health" 2>/dev/null || true)"
  if printf '%s' "$health" | grep -q '"status":"UP"'; then break; fi
  if ! kill -0 "$control_pid" 2>/dev/null; then exit 1; fi
  sleep 0.5
done
printf '%s' "$health" | grep -q '"status":"UP"'

VITE_DEV_PROXY_TARGET="http://127.0.0.1:${control_port}" \
VITE_DESKTOP_PROXY_TARGET="http://127.0.0.1:${desktop_port}" \
  pnpm --dir apps/web-console exec vite \
  --host 127.0.0.1 --port "$web_port" --strictPort >"$temp_dir/web-console.log" 2>&1 &
web_pid=$!

for _ in $(seq 1 60); do
  if curl -fsS "http://127.0.0.1:${web_port}/environments" >/dev/null 2>&1; then break; fi
  if ! kill -0 "$web_pid" 2>/dev/null; then exit 1; fi
  sleep 0.25
done
curl -fsS "http://127.0.0.1:${web_port}/environments" >/dev/null

WEB_CONSOLE_BASE_URL="http://127.0.0.1:${web_port}" \
WEB_CONSOLE_SCREENSHOT="$screenshot_path" \
VNC_EVENT_LOG="$temp_dir/vnc-events.jsonl" \
  pnpm --dir apps/web-console exec node ../../tests/e2e/web_console_session_flow.mjs

printf 'real_web_console_e2e=true\nhealth=%s\n' "$health"
