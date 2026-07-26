#!/usr/bin/env bash

set -euo pipefail

report_failure() {
  exit_code=$?
  printf 'POSTGRES_OUTAGE_FAILED line=%s command=%q exit=%s\n' \
    "${BASH_LINENO[0]}" "$BASH_COMMAND" "$exit_code" >&2
  return "$exit_code"
}
trap report_failure ERR

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
  echo "Java 21 is required for the PostgreSQL outage exercise." >&2
  exit 1
fi

./gradlew -p apps/control-plane bootJar

run_id="$(date +%s)-$$"
postgres_name="agentbrowser-postgres-outage-${run_id}"
redis_name="agentbrowser-redis-outage-${run_id}"
temp_dir="$(mktemp -d)"
control_pid=""

cleanup() {
  exit_code=$?
  if [[ -n "$control_pid" ]]; then kill "$control_pid" 2>/dev/null || true; fi
  docker unpause "$postgres_name" >/dev/null 2>&1 || true
  docker rm -f "$postgres_name" "$redis_name" >/dev/null 2>&1 || true
  if [[ "$exit_code" -ne 0 ]]; then
    tail -n 160 "$temp_dir/control-plane.log" 2>/dev/null || true
    find "$temp_dir" -maxdepth 1 -type f -name '*.json' -print -exec sed -n '1,80p' {} \;
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
control_port="$(python3 -c 'import socket; s=socket.socket(); s.bind(("", 0)); print(s.getsockname()[1]); s.close()')"

for _ in $(seq 1 40); do
  docker exec "$postgres_name" pg_isready -U browsercloud -d browsercloud >/dev/null 2>&1 && break
  sleep 0.5
done
docker exec "$postgres_name" pg_isready -U browsercloud -d browsercloud >/dev/null
for _ in $(seq 1 40); do
  docker exec "$redis_name" redis-cli ping 2>/dev/null | grep -q PONG && break
  sleep 0.25
done
docker exec "$redis_name" redis-cli ping | grep -q PONG

DATABASE_URL="jdbc:postgresql://localhost:${postgres_port}/browsercloud" \
DATABASE_USER=browsercloud \
DATABASE_PASSWORD=browsercloud \
DATABASE_CONNECTION_TIMEOUT_MS=2000 \
DATABASE_VALIDATION_TIMEOUT_MS=1000 \
DATABASE_CONNECT_TIMEOUT_SECONDS=2 \
DATABASE_SOCKET_TIMEOUT_SECONDS=3 \
REDIS_HOST=localhost \
REDIS_PORT="$redis_port" \
SERVER_PORT="$control_port" \
  "$java_bin" -jar apps/control-plane/build/libs/agent-browser-cloud-0.1.0.jar \
  >"$temp_dir/control-plane.log" 2>&1 &
control_pid=$!

ready_status=""
for _ in $(seq 1 90); do
  ready_status="$(curl -sS -o /dev/null -w '%{http_code}' \
    "http://127.0.0.1:${control_port}/api/v1/sessions?limit=1" \
    -H 'X-Tenant-Id: tenant-outage' 2>/dev/null || true)"
  if [[ "$ready_status" = "200" ]]; then break; fi
  if ! kill -0 "$control_pid" 2>/dev/null; then exit 1; fi
  sleep 0.5
done
test "$ready_status" = "200"

baseline_body='{"tenantId":"tenant-outage","profileId":"profile-baseline","region":"local","resourceClass":"L1","metadata":{"displayName":"Committed before outage"}}'
curl -fsS -X POST "http://127.0.0.1:${control_port}/api/v1/sessions" \
  -H 'Content-Type: application/json' \
  -H 'X-Tenant-Id: tenant-outage' \
  -H 'Idempotency-Key: outage-baseline-001' \
  -d "$baseline_body" >"$temp_dir/baseline.json"
baseline_session_id="$(python3 -c 'import json,sys; print(json.load(sys.stdin)["sessionId"])' <"$temp_dir/baseline.json")"

docker pause "$postgres_name" >/dev/null
outage_started_at="$(date +%s)"
outage_status="$(curl --max-time 8 -sS -o "$temp_dir/outage.json" -w '%{http_code}' \
  "http://127.0.0.1:${control_port}/api/v1/sessions?limit=20" \
  -H 'X-Tenant-Id: tenant-outage')"
outage_elapsed="$(( $(date +%s) - outage_started_at ))"
printf 'postgres_outage_read_status=%s elapsed=%s\n' "$outage_status" "$outage_elapsed"
test "$outage_status" = "503"
test "$outage_elapsed" -le 8
python3 - "$temp_dir/outage.json" <<'PY'
import json
import sys

body = json.load(open(sys.argv[1], encoding="utf-8"))
assert body["code"] == "DATABASE_UNAVAILABLE", body
assert body["message"] == "The authoritative database is temporarily unavailable", body
assert body["details"] == {}, body
assert body["requestId"], body
PY
kill -0 "$control_pid"

outage_body='{"tenantId":"tenant-outage","profileId":"profile-after-outage","region":"local","resourceClass":"L1","metadata":{"displayName":"Committed after recovery"}}'
write_status="$(curl --max-time 8 -sS -o "$temp_dir/write-outage.json" -w '%{http_code}' \
  -X POST "http://127.0.0.1:${control_port}/api/v1/sessions" \
  -H 'Content-Type: application/json' \
  -H 'X-Tenant-Id: tenant-outage' \
  -H 'Idempotency-Key: outage-retry-001' \
  -d "$outage_body")"
printf 'postgres_outage_write_status=%s\n' "$write_status"
test "$write_status" = "503"

docker unpause "$postgres_name" >/dev/null
ready_status=""
for _ in $(seq 1 90); do
  ready_status="$(curl -sS -o /dev/null -w '%{http_code}' \
    "http://127.0.0.1:${control_port}/api/v1/sessions?limit=1" \
    -H 'X-Tenant-Id: tenant-outage' 2>/dev/null || true)"
  if [[ "$ready_status" = "200" ]]; then break; fi
  sleep 0.5
done
test "$ready_status" = "200"

curl -fsS -X POST "http://127.0.0.1:${control_port}/api/v1/sessions" \
  -H 'Content-Type: application/json' \
  -H 'X-Tenant-Id: tenant-outage' \
  -H 'Idempotency-Key: outage-retry-001' \
  -d "$outage_body" >"$temp_dir/recovered-write.json"

sessions="$(curl -fsS \
  "http://127.0.0.1:${control_port}/api/v1/sessions?limit=20" \
  -H 'X-Tenant-Id: tenant-outage')"
printf '%s' "$sessions" | python3 -c \
  'import json,sys; result=json.load(sys.stdin); names=[item["displayName"] for item in result["items"]]; assert result["total"] == 2, result; assert sorted(names) == ["Committed after recovery", "Committed before outage"], names'
recovered_session_id="$(python3 -c 'import json,sys; print(json.load(sys.stdin)["sessionId"])' <"$temp_dir/recovered-write.json")"

printf 'POSTGRES_OUTAGE_GAMEDAY_OK baseline=%s recovered=%s outage_seconds=%s\n' \
  "$baseline_session_id" "$recovered_session_id" "$outage_elapsed"
