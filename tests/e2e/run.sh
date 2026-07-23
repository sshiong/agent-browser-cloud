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
cargo build --locked --manifest-path apps/browser-node/Cargo.toml --bin node-agent

run_id="$(date +%s)-$$"
postgres_name="agentbrowser-postgres-e2e-${run_id}"
redis_name="agentbrowser-redis-e2e-${run_id}"
temp_dir="$(mktemp -d)"
control_pid=""
node_pid=""
web_pid=""

cleanup() {
  exit_code=$?
  if [[ -n "$web_pid" ]]; then kill "$web_pid" 2>/dev/null || true; fi
  if [[ -n "$control_pid" ]]; then kill "$control_pid" 2>/dev/null || true; fi
  if [[ -n "$node_pid" ]]; then kill "$node_pid" 2>/dev/null || true; fi
  docker rm -f "$postgres_name" "$redis_name" >/dev/null 2>&1 || true
  if [[ "$exit_code" -ne 0 ]]; then
    tail -n 120 "$temp_dir/control-plane.log" 2>/dev/null || true
    tail -n 80 "$temp_dir/browser-node.log" 2>/dev/null || true
    tail -n 80 "$temp_dir/web-console.log" 2>/dev/null || true
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
web_port="$(python3 -c 'import socket; s=socket.socket(); s.bind(("", 0)); print(s.getsockname()[1]); s.close()')"
screenshot_path="${WEB_CONSOLE_SCREENSHOT:-/tmp/agent-browser-cloud-session-flow.png}"

for _ in $(seq 1 40); do
  docker exec "$postgres_name" pg_isready -U browsercloud -d browsercloud >/dev/null 2>&1 && break
  sleep 0.5
done
for _ in $(seq 1 40); do
  docker exec "$redis_name" redis-cli ping 2>/dev/null | grep -q PONG && break
  sleep 0.25
done

CHROMIUM_PATH=/usr/bin/true \
NODE_AGENT_PORT="$node_port" \
NODE_ID=node-e2e \
RUNTIME_ROOT="$temp_dir/runtime" \
  apps/browser-node/target/debug/node-agent >"$temp_dir/browser-node.log" 2>&1 &
node_pid=$!

DATABASE_URL="jdbc:postgresql://localhost:${postgres_port}/browsercloud" \
DATABASE_USER=browsercloud \
DATABASE_PASSWORD=browsercloud \
REDIS_HOST=localhost \
REDIS_PORT="$redis_port" \
BROWSER_NODE_GRPC_TARGET="localhost:${node_port}" \
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
  pnpm --dir apps/web-console exec node ../../tests/e2e/web_console_session_flow.mjs

printf 'real_web_console_e2e=true\nhealth=%s\n' "$health"
