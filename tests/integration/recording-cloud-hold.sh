#!/usr/bin/env bash
# Sourced only by the owned integration runner. Every native Hold/receipt is real;
# recording bytes and the initial Node manifest projection are explicit fixtures.
[[ "${BASH_SOURCE[0]}" != "$0" ]] || { echo 'RECORDING_NATIVE_HOLD_OWNED_RUNNER_REQUIRED' >&2; exit 1; }
[[ "$postgres_name" == agentbrowser-postgres-it-* ]]
native_recording_tenant=tenant-recording-native
native_recording_id=rec_22222222222222222222222222222222
curl --noproxy '*' -fsS --max-time 10 -X POST "http://127.0.0.1:${control_port}/api/v1/sessions" \
  -H 'Content-Type: application/json' -H "X-Tenant-Id: ${native_recording_tenant}" \
  -H 'X-Roles: TENANT_ADMIN' -H 'X-Actor-Id: native-recording-admin' \
  -H 'Idempotency-Key: native-recording-session-v1' \
  -d '{"tenantId":"tenant-recording-native","profileId":"profile-recording-native","runtimeBuildId":"runtime_local_chromium","region":"local","resourcePolicy":{"mode":"AUTO"}}' \
  > "$temp_dir/native-recording-session.json"
native_recording_session="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["sessionId"])' "$temp_dir/native-recording-session.json")"
curl --noproxy '*' -fsS --max-time 10 "http://127.0.0.1:${control_port}/api/v1/sessions/${native_recording_session}" \
  -H "X-Tenant-Id: ${native_recording_tenant}" -H 'X-Roles: TENANT_ADMIN' > "$temp_dir/native-recording-detail.json"
native_recording_profile="$(python3 -c 'import json,sys; value=json.load(open(sys.argv[1])); assert value["tenantId"]=="tenant-recording-native"; print(value["profileId"])' "$temp_dir/native-recording-detail.json")"
[[ "$native_recording_session" =~ ^ses_[a-zA-Z0-9_-]+$ ]]
[[ "$native_recording_profile" =~ ^[a-zA-Z0-9_-]+$ ]]
RECORDING_FIXTURE_CURRENT_TIME=true RECORDING_FIXTURE_HISTORY=true \
  cargo run --quiet --locked --manifest-path apps/browser-node/Cargo.toml -p storage-helper \
  --example create_recording_fixture -- "http://127.0.0.1:${minio_port}" "$minio_bucket" \
  "$minio_access_key" "$minio_secret_key" "$temp_dir/profile-archive-keyring.json" \
  "$native_recording_tenant" "$native_recording_profile" "$native_recording_session" "$native_recording_id" \
  > "$temp_dir/native-recording-fixture.json"
native_recording_key="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["manifestObjectKey"])' "$temp_dir/native-recording-fixture.json")"
native_recording_sha="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["manifestSha256"])' "$temp_dir/native-recording-fixture.json")"
native_recording_bytes="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["manifestBytes"])' "$temp_dir/native-recording-fixture.json")"
native_recording_start="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["startedAtMs"])' "$temp_dir/native-recording-fixture.json")"
native_recording_end="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["endedAtMs"])' "$temp_dir/native-recording-fixture.json")"
docker exec "$postgres_name" psql -v ON_ERROR_STOP=1 -U browsercloud -d browsercloud -c \
  "insert into session_recordings(recording_id,event_id,tenant_id,session_id,node_id,segment_count,frame_count,
   dropped_frames,redacted_frame_count,redacted_region_count,redaction_policy_version,manifest_object_key,
   manifest_sha256,manifest_bytes,started_at,ended_at,retention_until)
   values('$native_recording_id','evt_native_recording_fixture','$native_recording_tenant',
   '$native_recording_session','node_integration',25,25,0,25,25,1,'$native_recording_key',
   '$native_recording_sha',$native_recording_bytes,to_timestamp($native_recording_start/1000.0),
   to_timestamp($native_recording_end/1000.0),now()+interval '31 days')" >/dev/null

native_recording_policy_write() {
  [[ "$1" == true || "$1" == false ]] && [[ "$2" == true || "$2" == false ]]
  curl --noproxy '*' -fsS --max-time 10 -X PUT "http://127.0.0.1:${control_port}/api/v1/enterprise/retention-policies" \
    -H 'Content-Type: application/json' -H "X-Tenant-Id: ${native_recording_tenant}" \
    -H 'X-Roles: TENANT_ADMIN' -H 'X-Actor-Id: native-recording-admin' \
    -d "{\"dataClass\":\"REMOTE_DESKTOP_RECORDING\",\"retentionDays\":31,\"legalHold\":$1,\"cloudHoldRequired\":$2,\"residencyRegion\":\"local\"}" \
    > "$3"
}
native_recording_wait_mode() {
  local wanted="$1" current=""
  for _ in $(seq 1 120); do
    curl --noproxy '*' -fsS --max-time 10 "http://127.0.0.1:${control_port}/api/v1/enterprise/retention-policies" \
      -H "X-Tenant-Id: ${native_recording_tenant}" -H 'X-Roles: TENANT_ADMIN' \
      > "$temp_dir/native-recording-policies.json"
    current="$(python3 - "$temp_dir/native-recording-policies.json" <<'PY'
import json,sys
item=next(value for value in json.load(open(sys.argv[1])) if value['dataClass']=='REMOTE_DESKTOP_RECORDING')
view=item['recordingCloudHold']
assert view['status'] in ('PENDING','UNKNOWN','VERIFIED')
assert view['desiredMode'] in ('ON','OFF')
print(view['status']+':'+view['desiredMode'])
PY
)"
    [[ "$current" == "VERIFIED:$wanted" ]] && return
    [[ "$current" != UNKNOWN:* ]] || { echo 'RECORDING_NATIVE_HOLD_RESULT_UNKNOWN' >&2; return 1; }
    sleep 0.5
  done
  echo 'RECORDING_NATIVE_HOLD_PROOF_TIMEOUT' >&2
  return 1
}
native_recording_verify_versions() {
  TEST_OBJECT_STORAGE_ACCESS_KEY_ID="$minio_access_key" TEST_OBJECT_STORAGE_SECRET_ACCESS_KEY="$minio_secret_key" \
    cargo run --quiet --locked --manifest-path apps/browser-node/Cargo.toml -p storage-helper \
    --example verify_recording_native_hold -- "http://127.0.0.1:${minio_port}" "$minio_bucket" \
    "${native_recording_key%/COMMITTED}/" "$1" "$2" "$3"
}
# Real endpoint RBAC denies a weaker actor before any request is queued.
native_recording_operator="$(curl --noproxy '*' -sS --max-time 10 -o /dev/null -w '%{http_code}' \
  -X PUT "http://127.0.0.1:${control_port}/api/v1/enterprise/retention-policies" \
  -H 'Content-Type: application/json' -H "X-Tenant-Id: ${native_recording_tenant}" -H 'X-Roles: TENANT_OPERATOR' \
  -d '{"dataClass":"REMOTE_DESKTOP_RECORDING","retentionDays":31,"legalHold":true,"cloudHoldRequired":true,"residencyRegion":"local"}')"
test "$native_recording_operator" = 403
native_recording_policy_write true true "$temp_dir/native-recording-on-save.json"
python3 - "$temp_dir/native-recording-on-save.json" <<'PY'
import json,sys
item=json.load(open(sys.argv[1])); assert item['cloudHoldRequired'] is True
assert item['recordingCloudHold']['status']=='PENDING'
PY
native_recording_wait_mode ON
native_recording_verify_versions ON 51 52
native_recording_disable="$(curl --noproxy '*' -sS --max-time 10 -o "$temp_dir/native-recording-disable.json" -w '%{http_code}' \
  -X PUT "http://127.0.0.1:${control_port}/api/v1/enterprise/retention-policies" \
  -H 'Content-Type: application/json' -H "X-Tenant-Id: ${native_recording_tenant}" -H 'X-Roles: TENANT_ADMIN' \
  -d '{"dataClass":"REMOTE_DESKTOP_RECORDING","retentionDays":31,"legalHold":false,"cloudHoldRequired":false,"residencyRegion":"local"}')"
test "$native_recording_disable" = 409
grep -q 'RECORDING_CLOUD_HOLD_NOT_RELEASED' "$temp_dir/native-recording-disable.json"
native_recording_policy_write false true "$temp_dir/native-recording-off-save.json"
native_recording_wait_mode OFF
native_recording_verify_versions OFF 51 52
native_recording_proofs="$(docker exec "$postgres_name" psql -U browsercloud -d browsercloud -Atc \
  "select count(*) from recording_cloud_hold_jobs on_job join recording_cloud_hold_jobs off_job
   on off_job.recording_id=on_job.recording_id and off_job.previous_proof_hash=on_job.proof_hash
   where on_job.recording_id='$native_recording_id' and on_job.state='COMMITTED' and on_job.mode='ON'
   and off_job.state='COMMITTED' and off_job.mode='OFF' and off_job.authority_epoch>on_job.authority_epoch
   and on_job.object_version_count=52 and off_job.object_version_count=52")"
test "$native_recording_proofs" = 1
native_recording_audits="$(docker exec "$postgres_name" psql -U browsercloud -d browsercloud -Atc \
  "select count(*) from audit_events where tenant_id='$native_recording_tenant' and resource_id='$native_recording_id'
   and event_type='RECORDING_CLOUD_HOLD' and action in ('APPLY_ON','APPLY_OFF') and result='SUCCEEDED'")"
test "$native_recording_audits" = 2
native_recording_policy_write false false "$temp_dir/native-recording-management-off.json"
# Owned fixture time advancement only, after independent native OFF readback.
docker exec "$postgres_name" psql -v ON_ERROR_STOP=1 -U browsercloud -d browsercloud \
  -c "update session_recordings set retention_until=ended_at where recording_id='$native_recording_id'" >/dev/null
native_recording_deleted=""
for _ in $(seq 1 120); do
  native_recording_deleted="$(docker exec "$postgres_name" psql -U browsercloud -d browsercloud -Atc \
    "select deleted_at is not null and length(deletion_proof_hash)=64 and deleted_object_count=51
     from session_recordings where recording_id='$native_recording_id'")"
  [[ "$native_recording_deleted" == t ]] && break
  sleep 0.25
done
test "$native_recording_deleted" = t
native_recording_verify_versions EMPTY 0 0
echo 'recording_cloud_hold_native_mtls=true'
source "$repo_root/tests/integration/recording-cloud-hold-rejection.sh"
