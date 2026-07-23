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
  echo "Java 21 is required for the integration smoke test." >&2
  exit 1
fi

./gradlew -p apps/control-plane bootJar
cargo build --locked --manifest-path apps/browser-node/Cargo.toml --bin node-agent

run_id="$(date +%s)-$$"
postgres_name="agentbrowser-postgres-it-${run_id}"
redis_name="agentbrowser-redis-it-${run_id}"
temp_dir="$(mktemp -d)"
control_pid=""
node_pid=""

cleanup() {
  exit_code=$?
  if [[ -n "$control_pid" ]]; then kill "$control_pid" 2>/dev/null || true; fi
  if [[ -n "$node_pid" ]]; then kill "$node_pid" 2>/dev/null || true; fi
  docker rm -f "$postgres_name" "$redis_name" >/dev/null 2>&1 || true
  if [[ "$exit_code" -ne 0 ]]; then
    tail -n 120 "$temp_dir/control-plane.log" 2>/dev/null || true
    tail -n 80 "$temp_dir/browser-node.log" 2>/dev/null || true
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
NODE_ID=node-integration \
CONTROL_PLANE_EVENT_TARGET="127.0.0.1:${event_port}" \
RUNTIME_ROOT="$temp_dir/runtime" \
  apps/browser-node/target/debug/node-agent >"$temp_dir/browser-node.log" 2>&1 &
node_pid=$!

DATABASE_URL="jdbc:postgresql://localhost:${postgres_port}/browsercloud" \
DATABASE_USER=browsercloud \
DATABASE_PASSWORD=browsercloud \
REDIS_HOST=localhost \
REDIS_PORT="$redis_port" \
BROWSER_NODE_GRPC_TARGET="localhost:${node_port}" \
CONTROL_PLANE_NODE_EVENT_PORT="$event_port" \
SERVER_PORT="$control_port" \
  "$java_bin" -jar apps/control-plane/build/libs/agent-browser-cloud-0.1.0.jar \
  >"$temp_dir/control-plane.log" 2>&1 &
control_pid=$!

health=""
for _ in $(seq 1 90); do
  health="$(curl -fsS "http://localhost:${control_port}/actuator/health" 2>/dev/null || true)"
  if printf '%s' "$health" | grep -q '"status":"UP"'; then break; fi
  if ! kill -0 "$control_pid" 2>/dev/null; then exit 1; fi
  sleep 0.5
done
printf '%s' "$health" | grep -q '"status":"UP"'
curl -fsS -D "$temp_dir/health-headers.txt" -o /dev/null \
  "http://localhost:${control_port}/actuator/health"
grep -qi '^x-content-type-options: nosniff' "$temp_dir/health-headers.txt"
grep -qi '^cache-control: no-store' "$temp_dir/health-headers.txt"

unknown_field_status="$(curl -sS -o "$temp_dir/unknown-field.json" -w '%{http_code}' \
  -X POST "http://localhost:${control_port}/api/v1/sessions" \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: smoke-invalid-001' \
  -d '{"tenantId":"tenant-integration","profileId":"profile-integration","unexpected":true}')"
test "$unknown_field_status" = "400"

request_body='{"tenantId":"tenant-integration","profileId":"profile-integration","region":"local","resourceClass":"L1","metadata":{"displayName":"Integration browser"}}'
curl -fsS -X POST "http://localhost:${control_port}/api/v1/sessions" \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: smoke-idempotency-001' \
  -d "$request_body" >"$temp_dir/created-one.json" &
create_one_pid=$!
curl -fsS -X POST "http://localhost:${control_port}/api/v1/sessions" \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: smoke-idempotency-001' \
  -d "$request_body" >"$temp_dir/created-two.json" &
create_two_pid=$!
wait "$create_one_pid"
wait "$create_two_pid"
created_one="$(<"$temp_dir/created-one.json")"
created_two="$(<"$temp_dir/created-two.json")"
session_one="$(printf '%s' "$created_one" | python3 -c 'import json,sys; print(json.load(sys.stdin)["sessionId"])')"
session_two="$(printf '%s' "$created_two" | python3 -c 'import json,sys; print(json.load(sys.stdin)["sessionId"])')"
test "$session_one" = "$session_two"

conflict_status="$(curl -sS -o "$temp_dir/conflict.json" -w '%{http_code}' \
  -X POST "http://localhost:${control_port}/api/v1/sessions" \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: smoke-idempotency-001' \
  -d '{"tenantId":"tenant-integration","profileId":"different-profile"}')"
test "$conflict_status" = "409"

list_result="$(curl -fsS "http://localhost:${control_port}/api/v1/sessions" \
  -H 'X-Tenant-Id: tenant-integration')"
total="$(printf '%s' "$list_result" | python3 -c 'import json,sys; print(json.load(sys.stdin)["total"])')"
test "$total" = "1"
printf '%s' "$list_result" | python3 -c \
  'import json,sys; item=json.load(sys.stdin)["items"][0]; assert item["displayName"] == "Integration browser"; assert item["profileId"] == "profile-integration"; assert item["region"] == "local"; assert item["resourceClass"] == "L1"'

forbidden_status="$(curl -sS -o "$temp_dir/forbidden.json" -w '%{http_code}' \
  "http://localhost:${control_port}/api/v1/sessions/${session_one}" \
  -H 'X-Tenant-Id: different-tenant')"
test "$forbidden_status" = "403"

start_result="$(curl -fsS -X POST \
  "http://localhost:${control_port}/api/v1/sessions/${session_one}:start" \
  -H 'X-Tenant-Id: tenant-integration')"
operation_id="$(printf '%s' "$start_result" | python3 -c 'import json,sys; print(json.load(sys.stdin)["operationId"])')"
session_after_start=""
state=""
for _ in $(seq 1 40); do
  session_after_start="$(curl -fsS \
    "http://localhost:${control_port}/api/v1/sessions/${session_one}" \
    -H 'X-Tenant-Id: tenant-integration')"
  state="$(printf '%s' "$session_after_start" | python3 -c 'import json,sys; print(json.load(sys.stdin)["state"])')"
  if [[ "$state" = "RUNNING" ]]; then break; fi
  sleep 0.25
done
test "$state" = "RUNNING"
printf '%s' "$session_after_start" | python3 -c \
  'import json,sys; item=json.load(sys.stdin); assert item["displayName"] == "Integration browser"; assert item["profileId"] == "profile-integration"; assert item["region"] == "local"; assert item["resourceClass"] == "L1"'
printf '%s' "$session_after_start" | python3 -c \
  'import json,sys; item=json.load(sys.stdin); assert item["currentOperation"] is None; assert item["nodeId"] == "node-integration"; assert item["contextEpoch"] == 1'

published="0"
for _ in $(seq 1 30); do
  published="$(docker exec "$postgres_name" psql -U browsercloud -d browsercloud -Atc \
    "select count(*) from outbox_events where event_type='node.command.requested' and published_at is not null")"
  if [[ "$published" = "1" ]]; then break; fi
  sleep 0.5
done
test "$published" = "1"

terminate_result="$(curl -fsS -X POST \
  "http://localhost:${control_port}/api/v1/sessions/${session_one}:terminate" \
  -H 'X-Tenant-Id: tenant-integration')"
terminate_operation_id="$(printf '%s' "$terminate_result" | python3 -c 'import json,sys; print(json.load(sys.stdin)["operationId"])')"
session_after_terminate=""
for _ in $(seq 1 40); do
  session_after_terminate="$(curl -fsS \
    "http://localhost:${control_port}/api/v1/sessions/${session_one}" \
    -H 'X-Tenant-Id: tenant-integration')"
  state="$(printf '%s' "$session_after_terminate" | python3 -c 'import json,sys; print(json.load(sys.stdin)["state"])')"
  if [[ "$state" = "TERMINATED" ]]; then break; fi
  sleep 0.25
done
test "$state" = "TERMINATED"
printf '%s' "$session_after_terminate" | python3 -c \
  'import json,sys; item=json.load(sys.stdin); assert item["currentOperation"] is None'

committed_operations="$(docker exec "$postgres_name" psql -U browsercloud -d browsercloud -Atc \
  "select count(*) from exclusive_operations where session_id='${session_one}' and state='COMMITTED'")"
test "$committed_operations" = "2"
inbox_events="$(docker exec "$postgres_name" psql -U browsercloud -d browsercloud -Atc \
  "select count(*) from inbox_events where consumer_id='session-coordinator-v1'")"
test "$inbox_events" = "2"
published_commands="0"
for _ in $(seq 1 20); do
  published_commands="$(docker exec "$postgres_name" psql -U browsercloud -d browsercloud -Atc \
    "select count(*) from outbox_events where event_type='node.command.requested' and published_at is not null")"
  if [[ "$published_commands" = "2" ]]; then break; fi
  sleep 0.25
done
test "$published_commands" = "2"

public_tables="$(docker exec "$postgres_name" psql -U browsercloud -d browsercloud -Atc \
  "select count(*) from information_schema.tables where table_schema='public'")"
printf 'health=%s\nsecurity_headers=true\nunknown_field_rejected=%s\nsession_id=%s\nidempotent_replay=true\nidempotency_conflict=%s\ntenant_list_total=%s\nsession_descriptor_visible=true\ncross_tenant_access=%s\nstart_operation_committed=%s\nterminate_operation_committed=%s\nnode_events_inbox=%s\nnode_command_published=%s\npublic_tables=%s\n' \
  "$health" "$unknown_field_status" "$session_one" "$conflict_status" "$total" "$forbidden_status" \
  "$operation_id" "$terminate_operation_id" "$inbox_events" "$published_commands" "$public_tables"
