#!/usr/bin/env bash
set -euo pipefail
umask 077
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

if [[ "$(uname -s)" == Darwin ]]; then
  runtime_status="$(orbctl status)"
  context="$(docker context show)"
  runtime_info="$(docker info --format 'Name={{.Name}} OS={{.OperatingSystem}} Server={{.ServerVersion}}')"
  [[ "$runtime_status" == *Running* ]]
  [[ "$context" == orbstack ]]
  [[ "$runtime_info" == *OS=OrbStack* ]]
fi

temp_dir="$(mktemp -d)"
container_name="recording-retention-test-$(openssl rand -hex 10)"
cleanup() {
  docker rm -f "$container_name" >/dev/null 2>&1 || true
  rm -rf "$temp_dir"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
password="$(openssl rand -hex 24)"
printf 'POSTGRES_USER=recording_retention_test\nPOSTGRES_DB=recording_retention_test\nPOSTGRES_PASSWORD=%s\n' \
  "$password" > "$temp_dir/postgres.env"
docker run --rm -d --name "$container_name" --env-file "$temp_dir/postgres.env" \
  -p 127.0.0.1::5432 postgres:17 >/dev/null
for _ in $(seq 1 120); do
  if docker exec "$container_name" pg_isready -h 127.0.0.1 -U recording_retention_test >/dev/null 2>&1; then break; fi
  sleep 0.25
done
docker exec "$container_name" pg_isready -h 127.0.0.1 -U recording_retention_test >/dev/null
port="$(docker port "$container_name" 5432/tcp | sed -n 's/^127\.0\.0\.1://p')"
[[ "$port" =~ ^[0-9]+$ ]]
arguments=(-p apps/control-plane test --tests io.browsercloud.application.RecordingRetentionOrderPostgresTest --tests io.browsercloud.application.RecordingLegalHoldPostgresTest --rerun)
if [[ "${RECORDING_RETENTION_TEST_SKIP_PROTO:-false}" == true ]]; then
  test -d apps/control-plane/build/generated/source/proto/main/java
  arguments+=(-x generateProto)
fi
BROWSERCLOUD_RECORDING_RETENTION_TEST_URL="jdbc:postgresql://127.0.0.1:${port}/recording_retention_test" \
BROWSERCLOUD_RECORDING_RETENTION_TEST_PASSWORD="$password" \
  ./gradlew "${arguments[@]}"
python3 - <<'VERIFY'
import xml.etree.ElementTree as ET
for name, minimum in [("RecordingRetentionOrderPostgresTest", 14), ("RecordingLegalHoldPostgresTest", 10)]:
    result = ET.parse("apps/control-plane/build/test-results/test/TEST-io.browsercloud.application." + name + ".xml").getroot()
    assert int(result.get("tests", "0")) >= minimum
    assert all(int(result.get(key, "-1")) == 0 for key in ("failures", "errors", "skipped"))
    print("recording_postgres_suite=" + name + " tests=" + result.get("tests") + " failures=0 errors=0 skipped=0")
print("recording_retention_and_hold_postgres_order=true")
VERIFY
