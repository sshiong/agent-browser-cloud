#!/usr/bin/env bash

set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
artifact_dir="$repo_root/output/playwright/turnstile-interactive"
mkdir -p "$artifact_dir"

command -v npx >/dev/null 2>&1 || {
  echo "npx is required for the Turnstile interactive matrix" >&2
  exit 1
}

free_port() {
  python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1]); s.close()'
}

port="$(free_port)"
server_pid=""
session="turnstile-interactive-$$"

pwcli() {
  npx --yes --package=@playwright/cli playwright-cli --session "$session" "$@"
}

cleanup() {
  exit_code=$?
  pwcli close >/dev/null 2>&1 || true
  if [[ -n "$server_pid" ]]; then
    kill "$server_pid" >/dev/null 2>&1 || true
  fi
  exit "$exit_code"
}
trap cleanup EXIT INT TERM

python3 -m http.server "$port" \
  --bind 127.0.0.1 \
  --directory "$repo_root/tests/fixtures" \
  >"$artifact_dir/http-server.log" 2>&1 &
server_pid=$!

for _ in $(seq 1 40); do
  if curl -fsS "http://127.0.0.1:${port}/turnstile-interactive.html" >/dev/null 2>&1; then
    break
  fi
  kill -0 "$server_pid"
  sleep 0.1
done

cd "$artifact_dir"
pwcli open "http://127.0.0.1:${port}/turnstile-interactive.html" \
  --browser chrome --headed >open.log

checkbox_ref=""
for attempt in $(seq 1 8); do
  pwcli snapshot --filename="snapshot-${attempt}.md" >/dev/null
  checkbox_ref="$(sed -nE 's/.*checkbox[^[]*\[ref=([^]]+)\].*/\1/p' "snapshot-${attempt}.md" | head -1 || true)"
  if [[ -n "$checkbox_ref" ]]; then
    break
  fi
  pwcli run-code 'async page => { await page.waitForTimeout(250); }' >/dev/null
done

initial_status="$(pwcli eval '() => document.querySelector("#turnstile-status")?.dataset.verified || "false"')"
printf '%s' "$initial_status" | grep -q 'false'

if [[ -n "$checkbox_ref" ]]; then
  pwcli screenshot --filename=turnstile-interactive-before-click.png >/dev/null
  printf 'interactionMethod=snapshot-ref\ncheckboxRef=%s\n' "$checkbox_ref" >interaction-evidence.txt
  pwcli click "$checkbox_ref" >click.log
else
  # Turnstile is a cross-origin opaque frame, so Chromium may render a visible checkbox without
  # exposing an accessibility ref to the parent snapshot. Use only the parent-owned widget bounds
  # and a real mouse down/up; never evaluate or mutate the cross-origin frame.
  pwcli eval \
    '(element) => { const r = element.getBoundingClientRect(); return {x:r.x,y:r.y,width:r.width,height:r.height}; }' \
    '.cf-turnstile' --filename=widget-bounds.json >/dev/null
  pwcli screenshot --filename=turnstile-interactive-before-click.png >/dev/null
  read -r click_x click_y < <(python3 - <<'PY'
import json
from pathlib import Path

bounds = json.loads(Path("widget-bounds.json").read_text())
if bounds["width"] < 280 or bounds["height"] < 60:
    raise SystemExit("Turnstile widget bounds are not safely clickable")
print(round(bounds["x"] + 22), round(bounds["y"] + bounds["height"] / 2))
PY
  )
  printf 'interactionMethod=opaque-frame-coordinate\nclickX=%s\nclickY=%s\n' \
    "$click_x" "$click_y" >interaction-evidence.txt
  pwcli mousemove "$click_x" "$click_y" >click.log
  pwcli mousedown >>click.log
  pwcli mouseup >>click.log
fi

verified="false"
for _ in $(seq 1 60); do
  status="$(pwcli eval '() => document.querySelector("#turnstile-status")?.dataset.verified || "false"')"
  if printf '%s' "$status" | grep -q 'true'; then
    verified="true"
    break
  fi
  pwcli run-code 'async page => { await page.waitForTimeout(250); }' >/dev/null
done

pwcli snapshot --filename=verified-snapshot.md >/dev/null
pwcli screenshot --filename=turnstile-interactive-verified.png >/dev/null

if [[ "$verified" != "true" ]]; then
  echo "Cloudflare interactive checkbox click did not produce a verification token" >&2
  exit 1
fi

grep -q 'Interactive Turnstile verified' verified-snapshot.md
printf 'turnstile_interactive_checkbox_clicked=true\n' | tee -a interaction-evidence.txt
