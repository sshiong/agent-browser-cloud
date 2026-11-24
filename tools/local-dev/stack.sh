#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
MODULAR_COMPOSE="${ROOT_DIR}/deploy/compose/modular.yml"
LOW_MEM_COMPOSE="${ROOT_DIR}/deploy/compose/low-memory.yml"

# Defaults match the direct-lite preset (small-memory friendly, MCP + Web + VNC, no managed workers/recording).
PRESET=""
ENABLE_MCP="true"
ENABLE_WEB="true"
ENABLE_WORKERS="false"
ENABLE_VNC="true"
ENABLE_RECORDING="false"
LOW_MEMORY="true"
ALLOW_DIRECT_NETWORK="true"
MAX_SESSIONS="2"
NODE_MEM_MB="1536"
BIND_HOST="127.0.0.1"
INTERACTIVE="false"
ACTION=""
EXTRA_ARGS=()

usage() {
  cat <<'EOF'
Usage: ./tools/local-dev/stack.sh [options] <config|build|up|down|ps> [-- extra docker compose args]

Presets:
  --preset minimal-mcp     Core + MCP + low-memory (no Web, no VNC, no recording, no workers)
  --preset direct-lite     Core + MCP + Web + VNC + low-memory (no recording, no workers) [default]
  --preset full            Core + MCP + Web + VNC + recording + 4 managed workers (standard memory)

Module & Build Toggles (override preset):
  --mcp / --no-mcp                 Build and expose optional MCP Gateway (127.0.0.1:8130)
  --web / --no-web                 Build and expose optional Web Console (127.0.0.1:3000)
  --workers / --no-workers         Build and run 4 managed model workers (requires model API key)
  --vnc / --no-vnc                 Include Xvfb + x11vnc remote desktop in Browser Node
  --recording / --no-recording     Include OpenCV + Tesseract recording privacy scanner in Browser Node
  --low-memory / --no-low-memory   Apply low-memory JVM/PG/Redis/Chromium tuning overlay
  --direct-network / --no-direct-network  Allow direct browser egress (default: enabled)
  --max-sessions <N>               Max concurrent browser sessions on node (default: 2 in low-memory)
  --node-mem-mb <MB>               Browser Node memory cap in MiB (default: 1536 in low-memory)
  --bind-host <IP>                 Host bind address for exposed ports (default: 127.0.0.1)
  --interactive                    Prompt interactively for each module toggle
  -h, --help                       Show this help
EOF
}

apply_preset() {
  case "$1" in
    minimal-mcp)
      ENABLE_MCP="true"
      ENABLE_WEB="false"
      ENABLE_WORKERS="false"
      ENABLE_VNC="false"
      ENABLE_RECORDING="false"
      LOW_MEMORY="true"
      ;;
    direct-lite)
      ENABLE_MCP="true"
      ENABLE_WEB="true"
      ENABLE_WORKERS="false"
      ENABLE_VNC="true"
      ENABLE_RECORDING="false"
      LOW_MEMORY="true"
      ;;
    full)
      ENABLE_MCP="true"
      ENABLE_WEB="true"
      ENABLE_WORKERS="true"
      ENABLE_VNC="true"
      ENABLE_RECORDING="true"
      LOW_MEMORY="false"
      ;;
    *)
      echo "Unknown preset: $1 (expected minimal-mcp, direct-lite, or full)" >&2
      exit 2
      ;;
  esac
}

prompt_yn() {
  local label="$1"
  local current="$2"
  local hint="y/N"
  if [ "$current" = "true" ]; then
    hint="Y/n"
  fi
  printf "%s [%s]: " "$label" "$hint" >&2
  local answer=""
  read -r answer || true
  case "$(printf '%s' "$answer" | tr '[:upper:]' '[:lower:]')" in
    "") printf '%s' "$current" ;;
    y|yes|1|true) printf 'true' ;;
    n|no|0|false) printf 'false' ;;
    *)
      echo "Invalid choice '$answer', keeping $current" >&2
      printf '%s' "$current"
      ;;
  esac
}

while [ $# -gt 0 ]; do
  case "$1" in
    --preset)
      PRESET="${2:-}"
      apply_preset "$PRESET"
      shift 2
      ;;
    --mcp) ENABLE_MCP="true"; shift ;;
    --no-mcp) ENABLE_MCP="false"; shift ;;
    --web) ENABLE_WEB="true"; shift ;;
    --no-web) ENABLE_WEB="false"; shift ;;
    --workers) ENABLE_WORKERS="true"; shift ;;
    --no-workers) ENABLE_WORKERS="false"; shift ;;
    --vnc) ENABLE_VNC="true"; shift ;;
    --no-vnc) ENABLE_VNC="false"; shift ;;
    --recording) ENABLE_RECORDING="true"; shift ;;
    --no-recording) ENABLE_RECORDING="false"; shift ;;
    --low-memory) LOW_MEMORY="true"; shift ;;
    --no-low-memory) LOW_MEMORY="false"; shift ;;
    --direct-network) ALLOW_DIRECT_NETWORK="true"; shift ;;
    --no-direct-network) ALLOW_DIRECT_NETWORK="false"; shift ;;
    --max-sessions) MAX_SESSIONS="${2:-}"; shift 2 ;;
    --node-mem-mb) NODE_MEM_MB="${2:-}"; shift 2 ;;
    --bind-host) BIND_HOST="${2:-}"; shift 2 ;;
    --interactive) INTERACTIVE="true"; shift ;;
    -h|--help) usage; exit 0 ;;
    --)
      shift
      EXTRA_ARGS+=("$@")
      break
      ;;
    config|build|up|down|ps)
      if [ -n "$ACTION" ]; then
        EXTRA_ARGS+=("$1")
      else
        ACTION="$1"
      fi
      shift
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

if [ -z "$ACTION" ]; then
  usage >&2
  exit 2
fi

if ! printf '%s' "$MAX_SESSIONS" | grep -Eq '^[1-9][0-9]*$'; then
  echo "--max-sessions must be a positive integer" >&2
  exit 2
fi
if ! printf '%s' "$NODE_MEM_MB" | grep -Eq '^[1-9][0-9]*$' || [ "$NODE_MEM_MB" -lt 512 ]; then
  echo "--node-mem-mb must be an integer >= 512" >&2
  exit 2
fi

if [ "$INTERACTIVE" = "true" ]; then
  echo "=== Agent Browser Cloud 模块化构建/运行配置 ===" >&2
  ENABLE_MCP="$(prompt_yn "开放 MCP Gateway (外部 AI 直连, 端口 8130)?" "$ENABLE_MCP")"
  ENABLE_WEB="$(prompt_yn "构建并开放 Web 管理控制台 (端口 3000)?" "$ENABLE_WEB")"
  ENABLE_VNC="$(prompt_yn "在 Browser Node 中包含远程桌面 VNC (Xvfb + x11vnc)?" "$ENABLE_VNC")"
  ENABLE_RECORDING="$(prompt_yn "在 Browser Node 中包含录制脱敏扫描器 (OpenCV + Tesseract, ~700MB)?" "$ENABLE_RECORDING")"
  ENABLE_WORKERS="$(prompt_yn "构建并启用 4 个托管模型 Worker (需配置外部模型 API Key)?" "$ENABLE_WORKERS")"
  LOW_MEMORY="$(prompt_yn "启用小内存运行优化 (适合 2–4 GB RAM 机器)?" "$LOW_MEMORY")"
fi

COMPOSE_FILES=(-f "$MODULAR_COMPOSE")
if [ "$LOW_MEMORY" = "true" ]; then
  COMPOSE_FILES+=(-f "$LOW_MEM_COMPOSE")
fi

PROFILES=()
if [ "$ENABLE_MCP" = "true" ]; then
  PROFILES+=(--profile mcp)
fi
if [ "$ENABLE_WEB" = "true" ]; then
  PROFILES+=(--profile web)
fi
if [ "$ENABLE_WORKERS" = "true" ]; then
  PROFILES+=(--profile workers)
fi

export ENABLE_REMOTE_DESKTOP="$ENABLE_VNC"
export ENABLE_RECORDING_SCANNER="$ENABLE_RECORDING"
export BROWSER_LOW_MEMORY_MODE="$LOW_MEMORY"
export ALLOW_DIRECT_NETWORK="$ALLOW_DIRECT_NETWORK"
export PROXY_ALLOW_DIRECT="$ALLOW_DIRECT_NETWORK"
export BIND_HOST="$BIND_HOST"
export NODE_MAX_SESSIONS="$MAX_SESSIONS"
export NODE_CERTIFIED_MEMORY_MIB="$NODE_MEM_MB"
export BROWSER_NODE_MEM_LIMIT="${NODE_MEM_MB}m"
export AGENT_EXTERNAL_WORKER_ENABLED="$ENABLE_WORKERS"
export AGENT_REVIEWER_EXTERNAL_ENABLED="$ENABLE_WORKERS"
export AGENT_OUTCOME_VERIFIER_EXTERNAL_ENABLED="$ENABLE_WORKERS"

if [ "$ENABLE_VNC" = "true" ]; then
  export BROWSER_XVFB_PATH="/usr/bin/Xvfb"
  export BROWSER_X11VNC_PATH="/usr/bin/x11vnc"
else
  export BROWSER_XVFB_PATH=""
  export BROWSER_X11VNC_PATH=""
fi

if [ "$ENABLE_WORKERS" = "true" ] && { [ "$ACTION" = "up" ] || [ "$ACTION" = "build" ]; }; then
  "${ROOT_DIR}/tools/local-dev/check-agent-compose.sh"
fi

echo "[stack] action=${ACTION} mcp=${ENABLE_MCP} web=${ENABLE_WEB} workers=${ENABLE_WORKERS} vnc=${ENABLE_VNC} recording=${ENABLE_RECORDING} low_memory=${LOW_MEMORY} max_sessions=${MAX_SESSIONS} node_mem=${NODE_MEM_MB}MiB bind=${BIND_HOST}" >&2

case "$ACTION" in
  config)
    exec docker compose "${COMPOSE_FILES[@]}" "${PROFILES[@]}" config "${EXTRA_ARGS[@]}"
    ;;
  build)
    exec docker compose "${COMPOSE_FILES[@]}" "${PROFILES[@]}" build "${EXTRA_ARGS[@]}"
    ;;
  up)
    exec docker compose "${COMPOSE_FILES[@]}" "${PROFILES[@]}" up -d --build "${EXTRA_ARGS[@]}"
    ;;
  down)
    exec docker compose "${COMPOSE_FILES[@]}" --profile mcp --profile web --profile workers down "${EXTRA_ARGS[@]}"
    ;;
  ps)
    exec docker compose "${COMPOSE_FILES[@]}" --profile mcp --profile web --profile workers ps "${EXTRA_ARGS[@]}"
    ;;
esac
