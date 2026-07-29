#!/usr/bin/env bash
set -euo pipefail

minio_image="${MINIO_IMAGE:-minio/minio:RELEASE.2025-04-22T22-12-26Z}"
mc_image="${MINIO_MC_IMAGE:-minio/mc:RELEASE.2025-04-16T18-13-26Z}"
container_name="browsercloud-minio-$RANDOM-$$"
network_name="${container_name}-network"
access_key="browsercloud-test"
secret_key="browsercloud-test-secret"
bucket="profile-checkpoints"
port="$(python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1",0)); print(s.getsockname()[1]); s.close()')"

cleanup() {
  docker unpause "$container_name" >/dev/null 2>&1 || true
  docker rm -f "$container_name" >/dev/null 2>&1 || true
  docker network rm "$network_name" >/dev/null 2>&1 || true
}
trap cleanup EXIT

docker network create "$network_name" >/dev/null
docker run -d --name "$container_name" \
  --network "$network_name" \
  -p "127.0.0.1:${port}:9000" \
  -e "MINIO_ROOT_USER=${access_key}" \
  -e "MINIO_ROOT_PASSWORD=${secret_key}" \
  "$minio_image" server /data >/dev/null

ready=false
for _ in $(seq 1 60); do
  if curl -fsS "http://127.0.0.1:${port}/minio/health/ready" >/dev/null 2>&1; then
    ready=true
    break
  fi
  sleep 0.5
done
test "$ready" = "true"

docker run --rm --network "$network_name" --entrypoint /bin/sh "$mc_image" \
  -c "mc alias set acceptance http://${container_name}:9000 '${access_key}' '${secret_key}' >/dev/null && mc mb acceptance/${bucket} >/dev/null"

TEST_OBJECT_STORAGE_ENDPOINT="http://127.0.0.1:${port}" \
TEST_OBJECT_STORAGE_BUCKET="$bucket" \
TEST_OBJECT_STORAGE_ACCESS_KEY_ID="$access_key" \
TEST_OBJECT_STORAGE_SECRET_ACCESS_KEY="$secret_key" \
TEST_OBJECT_STORAGE_TIMEOUT_MS=1000 \
  cargo test --locked --manifest-path apps/browser-node/Cargo.toml \
  -p storage-helper object_archive::tests::archives_checkpoint_or_fails_within_bound \
  -- --ignored --exact

docker pause "$container_name" >/dev/null
TEST_OBJECT_STORAGE_ENDPOINT="http://127.0.0.1:${port}" \
TEST_OBJECT_STORAGE_BUCKET="$bucket" \
TEST_OBJECT_STORAGE_ACCESS_KEY_ID="$access_key" \
TEST_OBJECT_STORAGE_SECRET_ACCESS_KEY="$secret_key" \
TEST_OBJECT_STORAGE_TIMEOUT_MS=500 \
TEST_OBJECT_STORAGE_EXPECT_FAILURE=true \
  cargo test --locked --manifest-path apps/browser-node/Cargo.toml \
  -p storage-helper object_archive::tests::archives_checkpoint_or_fails_within_bound \
  -- --ignored --exact
docker unpause "$container_name" >/dev/null

printf 'OBJECT_STORAGE_GAMEDAY_OK commit_marker_last=true timeout_ms=500 local_checkpoint_retryable=true\n'
