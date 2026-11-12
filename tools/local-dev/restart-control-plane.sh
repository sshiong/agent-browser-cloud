#!/bin/sh
# Pass Compose options, e.g. -p project -f docker-compose.yml -f override.json.
# Workers share the Control Plane network namespace and must rejoin after restart.
set -eu
if [ "$(uname -s)" = Darwin ]; then
  orbctl status | grep -q Running
  [ "$(docker context show)" = orbstack ]
  docker info --format 'Name={{.Name}} OS={{.OperatingSystem}} Server={{.ServerVersion}}' | grep 'OS=OrbStack'
fi
wait_healthy() {
  service=$1
  shift
  count=0
  while [ "$count" -lt 90 ]; do
    id=$(docker compose "$@" ps -q "$service")
    if [ -n "$id" ] && [ "$(docker inspect --format '{{.State.Health.Status}}' "$id")" = healthy ]; then
      return 0
    fi
    count=$((count + 1))
    sleep 2
  done
  printf '%s\n' "restart verification failed: $service did not become healthy" >&2
  return 1
}
workers=''
for worker in agent-worker reviewer-worker outcome-verifier-worker vision-worker; do
  if [ -n "$(docker compose "$@" ps -q "$worker")" ]; then
    workers="$workers $worker"
  fi
done
[ -n "$(docker compose "$@" ps -q control-plane)" ] || {
  printf '%s\n' 'control-plane must already be running' >&2
  exit 1
}
docker compose "$@" restart -t 30 control-plane
wait_healthy control-plane "$@"
if [ -n "$workers" ]; then
  # Intentional splitting of a fixed list of service names, never caller-supplied text.
  # shellcheck disable=SC2086
  docker compose "$@" up -d --no-deps --force-recreate $workers
  for worker in $workers; do
    wait_healthy "$worker" "$@"
  done
fi
printf '%s\n' 'Control Plane restart and active Worker namespace/health verification: PASS'
