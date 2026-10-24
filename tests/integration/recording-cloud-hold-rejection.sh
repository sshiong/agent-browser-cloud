#!/usr/bin/env bash
# Owned fixture only: a validly shaped ledger hash disagrees with actual S3 bytes.
# The real Helper must reject it; neither SQL nor a mock creates a cloud receipt.
[[ "${BASH_SOURCE[0]}" != "$0" ]] || { echo 'RECORDING_NATIVE_HOLD_OWNED_RUNNER_REQUIRED' >&2; exit 1; }
[[ "$postgres_name" == agentbrowser-postgres-it-* ]]
native_recording_id=rec_33333333333333333333333333333333
RECORDING_FIXTURE_CURRENT_TIME=true RECORDING_FIXTURE_HISTORY=true \
  cargo run --quiet --locked --manifest-path apps/browser-node/Cargo.toml -p storage-helper \
  --example create_recording_fixture -- "http://127.0.0.1:${minio_port}" "$minio_bucket" \
  "$minio_access_key" "$minio_secret_key" "$temp_dir/profile-archive-keyring.json" \
  "$native_recording_tenant" "$native_recording_profile" "$native_recording_session" "$native_recording_id" \
  > "$temp_dir/native-recording-rejection-fixture.json"
native_recording_key="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["manifestObjectKey"])' "$temp_dir/native-recording-rejection-fixture.json")"
native_recording_bytes="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["manifestBytes"])' "$temp_dir/native-recording-rejection-fixture.json")"
native_recording_start="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["startedAtMs"])' "$temp_dir/native-recording-rejection-fixture.json")"
native_recording_end="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["endedAtMs"])' "$temp_dir/native-recording-rejection-fixture.json")"
native_recording_bad_hash="$(python3 -c 'print("0"*64)')"
native_recording_rejections_before="$(grep -c 'RECORDING_HOLD_MANIFEST_REJECTED' "$temp_dir/storage-helper.log" || true)"
docker exec "$postgres_name" psql -v ON_ERROR_STOP=1 -U browsercloud -d browsercloud -c \
  "insert into session_recordings(recording_id,event_id,tenant_id,session_id,node_id,segment_count,frame_count,
   dropped_frames,redacted_frame_count,redacted_region_count,redaction_policy_version,manifest_object_key,
   manifest_sha256,manifest_bytes,started_at,ended_at,retention_until)
   values('$native_recording_id','evt_native_recording_rejection_fixture','$native_recording_tenant',
   '$native_recording_session','node_integration',25,25,0,25,25,1,'$native_recording_key',
   '$native_recording_bad_hash',$native_recording_bytes,to_timestamp($native_recording_start/1000.0),
   to_timestamp($native_recording_end/1000.0),now()+interval '31 days')" >/dev/null
native_recording_policy_write true true "$temp_dir/native-recording-rejection-on.json"
native_recording_unknown=""
for _ in $(seq 1 120); do
  curl --noproxy '*' -fsS --max-time 10 "http://127.0.0.1:${control_port}/api/v1/enterprise/retention-policies" \
    -H "X-Tenant-Id: ${native_recording_tenant}" -H 'X-Roles: TENANT_ADMIN' \
    > "$temp_dir/native-recording-rejection-policies.json"
  native_recording_unknown="$(python3 - "$temp_dir/native-recording-rejection-policies.json" <<'PY'
import json,sys
item=next(value for value in json.load(open(sys.argv[1])) if value['dataClass']=='REMOTE_DESKTOP_RECORDING')
view=item['recordingCloudHold']
assert view['desiredMode']=='ON' and item['legalHold'] is True and item['cloudHoldRequired'] is True
assert view['status'] in ('PENDING','UNKNOWN')
assert view['verifiedOnRecordings']==0 and view['verifiedOffRecordings']==0
if view['status']=='UNKNOWN': assert view['unknownRecordings']==1
print(view['status'])
PY
)"
  [[ "$native_recording_unknown" == UNKNOWN ]] && break
  sleep 0.5
done
test "$native_recording_unknown" = UNKNOWN
native_recording_rejections_after="$(grep -c 'RECORDING_HOLD_MANIFEST_REJECTED' "$temp_dir/storage-helper.log" || true)"
test "$native_recording_rejections_after" -gt "$native_recording_rejections_before"
native_recording_original_request="$(docker exec "$postgres_name" psql -U browsercloud -d browsercloud -Atc \
  "select job.operation_id||':'||job.authority_epoch||':'||job.mode||':'||job.manifest_sha256
   from recording_cloud_hold_jobs job join recording_cloud_hold_authorities auth
   on auth.active_operation_id=job.operation_id join session_recordings rec on rec.recording_id=job.recording_id
   where job.recording_id='$native_recording_id' and job.state='UNKNOWN' and job.attempt between 1 and 10
   and job.failure_code='RECORDING_HOLD_REMOTE_RESULT_UNKNOWN' and job.proof_hash is null
   and auth.status='UNKNOWN' and auth.applied_epoch is null and auth.proof_hash is null
   and rec.legal_hold and rec.deleted_at is null")"
[[ "$native_recording_original_request" == rhj_*":1:ON:$native_recording_bad_hash" ]]
native_recording_unknown_audits="$(docker exec "$postgres_name" psql -U browsercloud -d browsercloud -Atc \
  "select count(*) from audit_events where tenant_id='$native_recording_tenant' and resource_id='$native_recording_id'
   and event_type='RECORDING_CLOUD_HOLD' and action='RECONCILE' and result='UNKNOWN'")"
test "$native_recording_unknown_audits" -ge 1
native_recording_verify_versions OFF 51 52
# Changing desired policy cannot replace the dispatched immutable ON or create an OFF without proof.
native_recording_policy_write false true "$temp_dir/native-recording-rejection-off.json"
python3 - "$temp_dir/native-recording-rejection-off.json" <<'PY'
import json,sys
item=json.load(open(sys.argv[1])); view=item['recordingCloudHold']
assert item['legalHold'] is False and item['cloudHoldRequired'] is True
assert view['status']=='UNKNOWN' and view['desiredMode']=='OFF' and view['unknownRecordings']==1
assert view['verifiedOnRecordings']==0 and view['verifiedOffRecordings']==0
PY
native_recording_retained_request="$(docker exec "$postgres_name" psql -U browsercloud -d browsercloud -Atc \
  "select job.operation_id||':'||job.authority_epoch||':'||job.mode||':'||job.manifest_sha256
   from recording_cloud_hold_jobs job join recording_cloud_hold_authorities auth
   on auth.active_operation_id=job.operation_id where job.recording_id='$native_recording_id'
   and auth.desired_epoch=2 and auth.desired_mode='OFF' and auth.status='UNKNOWN'
   and job.state='UNKNOWN' and job.previous_proof_hash is null and job.proof_hash is null")"
test "$native_recording_retained_request" = "$native_recording_original_request"
native_recording_rejection_disable="$(curl --noproxy '*' -sS --max-time 10 \
  -o "$temp_dir/native-recording-rejection-disable.json" -w '%{http_code}' \
  -X PUT "http://127.0.0.1:${control_port}/api/v1/enterprise/retention-policies" \
  -H 'Content-Type: application/json' -H "X-Tenant-Id: ${native_recording_tenant}" -H 'X-Roles: TENANT_ADMIN' \
  -d '{"dataClass":"REMOTE_DESKTOP_RECORDING","retentionDays":31,"legalHold":false,"cloudHoldRequired":false,"residencyRegion":"local"}')"
test "$native_recording_rejection_disable" = 409
grep -q 'RECORDING_CLOUD_HOLD_NOT_RELEASED' "$temp_dir/native-recording-rejection-disable.json"
# Advance owned fixture time only. Real deletion discovery must still exclude UNKNOWN.
docker exec "$postgres_name" psql -v ON_ERROR_STOP=1 -U browsercloud -d browsercloud \
  -c "update session_recordings set retention_until=ended_at where recording_id='$native_recording_id'" >/dev/null
sleep 2
native_recording_no_deletion="$(docker exec "$postgres_name" psql -U browsercloud -d browsercloud -Atc \
  "select rec.legal_hold and rec.deleted_at is null and rec.deletion_proof_hash is null
   and not exists(select 1 from recording_retention_deletion_jobs job where job.recording_id=rec.recording_id)
   and (select count(*) from recording_cloud_hold_jobs job where job.recording_id=rec.recording_id)=1
   from session_recordings rec where rec.recording_id='$native_recording_id'")"
test "$native_recording_no_deletion" = t
native_recording_verify_versions OFF 51 52
echo 'recording_cloud_hold_native_rejection_preserves_unknown=true'
