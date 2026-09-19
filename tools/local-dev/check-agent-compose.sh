#!/bin/sh
set -eu

fail() {
  printf '%s\n' "default Compose preflight: $1" >&2
  exit 1
}

[ -n "${LOCAL_AGENT_MODEL_API_KEY_FILE:-}" ] || fail "LOCAL_AGENT_MODEL_API_KEY_FILE is required"
[ -n "${LOCAL_AGENT_MODEL_ENDPOINT:-}" ] || fail "LOCAL_AGENT_MODEL_ENDPOINT is required"
[ -n "${LOCAL_AGENT_MODEL_NAME:-}" ] || fail "LOCAL_AGENT_MODEL_NAME is required"
[ -n "${LOCAL_AGENT_MODEL_REVISION:-}" ] || fail "LOCAL_AGENT_MODEL_REVISION is required"

case "$LOCAL_AGENT_MODEL_API_KEY_FILE" in
  /*) ;;
  *) fail "LOCAL_AGENT_MODEL_API_KEY_FILE must be an absolute path" ;;
esac
[ -f "$LOCAL_AGENT_MODEL_API_KEY_FILE" ] || fail "model API key must be a regular file"
[ ! -L "$LOCAL_AGENT_MODEL_API_KEY_FILE" ] || fail "model API key must not be a symbolic link"
[ -s "$LOCAL_AGENT_MODEL_API_KEY_FILE" ] || fail "model API key must not be empty"

if mode="$(stat -c '%a' "$LOCAL_AGENT_MODEL_API_KEY_FILE" 2>/dev/null)"; then
  : # GNU stat (Linux)
else
  mode="$(stat -f '%Lp' "$LOCAL_AGENT_MODEL_API_KEY_FILE")" # BSD stat (macOS)
fi
[ "$mode" = 600 ] || [ "$mode" = 400 ] || fail "model API key mode must be 0600 or 0400"

case "$LOCAL_AGENT_MODEL_ENDPOINT" in
  https://*/v1/responses) ;;
  *) fail "LOCAL_AGENT_MODEL_ENDPOINT must be an HTTPS /v1/responses endpoint" ;;
esac

[ "$LOCAL_AGENT_MODEL_NAME" != configure-real-model ] || fail "configure a real model name"

docker compose config --quiet
