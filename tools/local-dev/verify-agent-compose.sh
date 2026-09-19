#!/bin/sh
set -eu

for service in control-plane agent-worker reviewer-worker outcome-verifier-worker vision-worker; do
  container_id="$(docker compose ps -q "$service")"
  [ -n "$container_id" ] || {
    printf '%s\n' "default Compose verification: $service is not running" >&2
    exit 1
  }
  health="$(docker inspect --format '{{.State.Health.Status}}' "$container_id")"
  [ "$health" = healthy ] || {
    printf '%s\n' "default Compose verification: $service is $health" >&2
    exit 1
  }
done

for service in agent-worker reviewer-worker outcome-verifier-worker vision-worker; do
  container_id="$(docker compose ps -q "$service")"
  if docker inspect --format '{{range .Config.Env}}{{println .}}{{end}}' "$container_id" \
    | grep -q '^LOCAL_AGENT_MODEL_API_KEY='; then
    printf '%s\n' "default Compose verification: model credential leaked into $service environment" >&2
    exit 1
  fi
done

printf '%s\n' "default Compose Agent/Reviewer/Outcome/Vision chain: PASS"
