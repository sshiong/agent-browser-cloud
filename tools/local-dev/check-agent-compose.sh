#!/bin/sh
set -eu

fail() {
  printf '%s\n' "default Compose preflight: $1" >&2
  exit 1
}

[ -n "${LOCAL_AGENT_MODEL_ENDPOINT:-}" ] || fail "LOCAL_AGENT_MODEL_ENDPOINT is required"
[ -n "${LOCAL_AGENT_MODEL_NAME:-}" ] || fail "LOCAL_AGENT_MODEL_NAME is required"

if [ -n "${LOCAL_AGENT_MODEL_API_KEY_FILE:-}" ]; then
  model_key_file="$LOCAL_AGENT_MODEL_API_KEY_FILE"
elif [ -n "${LOCAL_AGENT_MODEL_API_KEY:-}" ]; then
  model_key_file="$(pwd)/.local/agent-model-api-key"
  mkdir -p "$(dirname "$model_key_file")"
  umask 077
  printf '%s\n' "$LOCAL_AGENT_MODEL_API_KEY" >"$model_key_file"
else
  fail "LOCAL_AGENT_MODEL_API_KEY or LOCAL_AGENT_MODEL_API_KEY_FILE is required"
fi

case "$model_key_file" in
  /*) ;;
  *) fail "LOCAL_AGENT_MODEL_API_KEY_FILE must be an absolute path" ;;
esac
[ -f "$model_key_file" ] || fail "model API key must be a regular file"
[ ! -L "$model_key_file" ] || fail "model API key must not be a symbolic link"
[ -s "$model_key_file" ] || fail "model API key must not be empty"

if mode="$(stat -c '%a' "$model_key_file" 2>/dev/null)"; then
  : # GNU stat (Linux)
else
  mode="$(stat -f '%Lp' "$model_key_file")" # BSD stat (macOS)
fi
[ "$mode" = 600 ] || [ "$mode" = 400 ] || fail "model API key mode must be 0600 or 0400"

case "$LOCAL_AGENT_MODEL_ENDPOINT" in
  http://*/v1|http://*/v1/|http://*/v1/responses|http://*/v1/responses/|https://*/v1|https://*/v1/|https://*/v1/responses|https://*/v1/responses/) ;;
  *) fail "LOCAL_AGENT_MODEL_ENDPOINT must be an HTTP(S) /v1 base URL" ;;
esac

[ "$LOCAL_AGENT_MODEL_NAME" != configure-real-model ] || fail "configure a real model name"

case "${LOCAL_AGENT_MODEL_MAXIMUM_OUTPUT_TOKENS:-512}" in
  *[!0-9]*|'') fail "LOCAL_AGENT_MODEL_MAXIMUM_OUTPUT_TOKENS must be an integer from 64 to 4096" ;;
esac
[ "${LOCAL_AGENT_MODEL_MAXIMUM_OUTPUT_TOKENS:-512}" -ge 64 ] \
  && [ "${LOCAL_AGENT_MODEL_MAXIMUM_OUTPUT_TOKENS:-512}" -le 4096 ] \
  || fail "LOCAL_AGENT_MODEL_MAXIMUM_OUTPUT_TOKENS must be an integer from 64 to 4096"

docker compose config --quiet
