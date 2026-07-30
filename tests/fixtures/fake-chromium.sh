#!/usr/bin/env sh

cdp_port=""
user_data_dir=""
proxy_server=""
proxy_bypass_list=""
load_extension=""
for argument in "$@"; do
  case "$argument" in
    --remote-debugging-port=*)
      cdp_port="${argument#*=}"
      ;;
    --user-data-dir=*)
      user_data_dir="${argument#*=}"
      ;;
    --proxy-server=*)
      proxy_server="${argument#*=}"
      ;;
    --proxy-bypass-list=*)
      proxy_bypass_list="${argument#*=}"
      ;;
    --load-extension=*)
      load_extension="${argument#*=}"
      ;;
  esac
done

if [ -z "$cdp_port" ]; then
  echo "fake Chromium requires --remote-debugging-port" >&2
  exit 2
fi
if [ -z "$user_data_dir" ]; then
  echo "fake Chromium requires --user-data-dir" >&2
  exit 2
fi
if [ "${FAKE_CHROMIUM_REQUIRE_PROXY:-false}" = "true" ]; then
  if [ -z "$proxy_server" ] || [ "$proxy_bypass_list" != "<-loopback>" ]; then
    echo "fake Chromium requires an enforced proxy with no implicit loopback bypass" >&2
    exit 2
  fi
fi
if [ -n "${FAKE_CHROMIUM_ARGUMENT_LOG:-}" ]; then
  printf '%s\n' "$*" >>"$FAKE_CHROMIUM_ARGUMENT_LOG"
fi

exec python3 - "$cdp_port" "$user_data_dir" "$load_extension" <<'PY'
import json
import base64
import hashlib
import os
import signal
import struct
import sys
import time
from pathlib import Path
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

port = int(sys.argv[1])
profile_root = Path(sys.argv[2])
extension_ids = [
    Path(item).name
    for item in sys.argv[3].split(",")
    if item and Path(item).name
]
marker = profile_root / "Default" / "BrowserCloudProfileState.json"
marker.parent.mkdir(parents=True, exist_ok=True)
try:
    starts = json.loads(marker.read_text()).get("starts", 0)
except (FileNotFoundError, json.JSONDecodeError):
    starts = 0
marker.write_text(json.dumps({"starts": starts + 1, "durable": True}))
delay_profile_fragment = os.environ.get("FAKE_CHROMIUM_DELAY_PROFILE_FRAGMENT", "")
delay_start_number = int(os.environ.get("FAKE_CHROMIUM_DELAY_START_NUMBER", "0"))
delay_seconds = float(os.environ.get("FAKE_CHROMIUM_STARTUP_DELAY_SECONDS", "0"))
if (
    delay_profile_fragment
    and delay_profile_fragment in str(profile_root)
    and starts + 1 == delay_start_number
    and 0 < delay_seconds <= 60
):
    time.sleep(delay_seconds)
mutate_after = int(os.environ.get("FAKE_CHROMIUM_MUTATE_STATE_AFTER", "0"))
evaluation_count = 0
business_recovery_completed = False

class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def do_GET(self):
        if self.headers.get("Upgrade", "").lower() == "websocket":
            self.handle_websocket()
            return
        if self.path == "/json/version":
            payload = {
                "Browser": "FakeChromium/1.0",
                "Protocol-Version": "1.3",
                "webSocketDebuggerUrl": f"ws://127.0.0.1:{port}/devtools/browser/fake",
            }
        elif self.path in ("/json", "/json/list"):
            payload = [{
                "id": "page-1",
                "type": "page",
                "title": "Browser Cloud Test Page",
                "url": "about:blank",
                "webSocketDebuggerUrl": f"ws://127.0.0.1:{port}/devtools/page/page-1",
            }]
            payload.extend({
                "id": f"extension-{extension_id}",
                "type": "service_worker",
                "title": "Agent Browser Integration Extension",
                "url": f"chrome-extension://{extension_id}/background.js",
                "webSocketDebuggerUrl": (
                    f"ws://127.0.0.1:{port}/devtools/page/extension-{extension_id}"
                ),
            } for extension_id in extension_ids)
        else:
            self.send_error(404)
            return
        encoded = json.dumps(payload).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(encoded)))
        self.end_headers()
        self.wfile.write(encoded)

    def handle_websocket(self):
        global evaluation_count, business_recovery_completed
        key = self.headers.get("Sec-WebSocket-Key", "")
        accept = base64.b64encode(
            hashlib.sha1((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").encode()).digest()
        ).decode()
        self.send_response(101, "Switching Protocols")
        self.send_header("Upgrade", "websocket")
        self.send_header("Connection", "Upgrade")
        self.send_header("Sec-WebSocket-Accept", accept)
        self.end_headers()
        while True:
            request = self.read_websocket_text()
            if request is None:
                return
            command = json.loads(request)
            method = command.get("method")
            if method == "Runtime.evaluate":
                if self.path.startswith("/devtools/page/extension-"):
                    if command.get("params", {}).get("expression") != (
                        "setTimeout(() => chrome.runtime.reload(), 0); true"
                    ):
                        response = {
                            "id": command["id"],
                            "error": {"code": -32602, "message": "untrusted extension expression"},
                        }
                    else:
                        business_recovery_completed = True
                        response = {
                            "id": command["id"],
                            "result": {
                                "result": {"type": "boolean", "value": True}
                            },
                        }
                else:
                    evaluation_count += 1
                    target_name = (
                        "Continue integration"
                        if mutate_after > 0 and evaluation_count >= mutate_after
                        else "Run integration"
                    )
                    result = {
                        "url": "https://example.test/runtime",
                        "title": "Browser Cloud Test Page",
                        "targets": [{
                            "path": "html:nth-of-type(1)>body:nth-of-type(1)>button:nth-of-type(1)",
                            "role": "button",
                            "name": target_name,
                            "bounds": {"x": 20.0, "y": 30.0, "width": 120.0, "height": 36.0},
                            "enabled": True,
                            "visible": True,
                            "sensitive": False,
                        }, {
                            "path": "html:nth-of-type(1)>body:nth-of-type(1)>input:nth-of-type(1)",
                            "role": "textbox",
                            "name": "Public note",
                            "bounds": {"x": 20.0, "y": 84.0, "width": 240.0, "height": 36.0},
                            "enabled": True,
                            "visible": True,
                            "sensitive": False,
                        }, {
                            "path": "html:nth-of-type(1)>body:nth-of-type(1)>input:nth-of-type(2)",
                            "role": "textbox",
                            "name": None,
                            "bounds": {"x": 20.0, "y": 138.0, "width": 240.0, "height": 36.0},
                            "enabled": True,
                            "visible": True,
                            "sensitive": True,
                        }],
                    }
                    if business_recovery_completed:
                        result["targets"].append({
                            "path": "html:nth-of-type(1)>body:nth-of-type(1)>div:nth-of-type(1)",
                            "role": "status",
                            "name": "Recovered workspace",
                            "bounds": {"x": 20.0, "y": 192.0, "width": 180.0, "height": 28.0},
                            "enabled": True,
                            "visible": True,
                            "sensitive": False,
                        })
                    response = {
                        "id": command["id"],
                        "result": {"result": {"type": "object", "value": result}},
                    }
            elif method == "SystemInfo.getProcessInfo":
                response = {
                    "id": command["id"],
                    "result": {
                        "processInfo": [
                            {"type": "browser", "id": 1},
                            {"type": "renderer", "id": 2},
                        ]
                    },
                }
            elif method == "Performance.getMetrics":
                response = {
                    "id": command["id"],
                    "result": {
                        "metrics": [
                            {"name": "Timestamp", "value": time.monotonic()},
                            {"name": "TaskDuration", "value": 0.125},
                        ]
                    },
                }
            elif method == "Page.captureScreenshot":
                if command.get("params", {}).get("format") != "jpeg":
                    response = {
                        "id": command["id"],
                        "error": {"code": -32602, "message": "only JPEG is supported"},
                    }
                else:
                    response = {
                        "id": command["id"],
                        "result": {
                            "data": base64.b64encode(
                                bytes([0xFF, 0xD8, 0xFF, 0xD9])
                            ).decode()
                        },
                    }
            elif method == "Page.reload":
                business_recovery_completed = True
                response = {"id": command["id"], "result": {}}
            else:
                response = {"id": command.get("id", 1), "result": {}}
            self.write_websocket_text(json.dumps(response))
            if method == "Target.setAutoAttach":
                self.write_websocket_text(json.dumps({
                    "method": "Target.attachedToTarget",
                    "params": {
                        "sessionId": "fake-page-session",
                        "targetInfo": {"targetId": "page-1", "type": "page"},
                        "waitingForDebugger": False,
                    },
                }))

    def read_exact(self, length):
        result = b""
        while len(result) < length:
            chunk = self.rfile.read(length - len(result))
            if not chunk:
                return None
            result += chunk
        return result

    def read_websocket_text(self):
        header = self.read_exact(2)
        if header is None:
            return None
        length = header[1] & 0x7F
        masked = bool(header[1] & 0x80)
        if length == 126:
            length = struct.unpack("!H", self.read_exact(2))[0]
        elif length == 127:
            length = struct.unpack("!Q", self.read_exact(8))[0]
        mask = self.read_exact(4) if masked else None
        payload = self.read_exact(length)
        if payload is None:
            return None
        if mask:
            payload = bytes(value ^ mask[index % 4] for index, value in enumerate(payload))
        return payload.decode()

    def write_websocket_text(self, text):
        payload = text.encode()
        if len(payload) < 126:
            header = bytes([0x81, len(payload)])
        elif len(payload) < 65536:
            header = bytes([0x81, 126]) + struct.pack("!H", len(payload))
        else:
            header = bytes([0x81, 127]) + struct.pack("!Q", len(payload))
        self.wfile.write(header + payload)
        self.wfile.flush()

    def log_message(self, _format, *_args):
        return

server = ThreadingHTTPServer(("127.0.0.1", port), Handler)
signal.signal(signal.SIGTERM, lambda *_: sys.exit(0))
signal.signal(signal.SIGINT, lambda *_: sys.exit(0))
server.serve_forever()
PY
