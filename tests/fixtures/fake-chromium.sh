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
import threading
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
public_note_value = "coordinator failover note"
checkbox_checked = False
pointer_x = 0.0
pointer_y = 0.0
focused_control = None
control_pressed = False
select_all = False
pages_lock = threading.Lock()
pages = {
    "page-1": {
        "title": "Browser Cloud Test Page",
        "url": "https://example.test/runtime",
    }
}
active_page_id = "page-1"
native_dialog = None
native_dialog_sequence = 0

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
            with pages_lock:
                payload = [{
                    "id": page_id,
                    "type": "page",
                    "title": page["title"],
                    "url": page["url"],
                    "webSocketDebuggerUrl": (
                        f"ws://127.0.0.1:{port}/devtools/page/{page_id}"
                    ),
                } for page_id, page in pages.items()]
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
        global public_note_value, checkbox_checked, pointer_x, pointer_y
        global focused_control, control_pressed, select_all
        global active_page_id
        global native_dialog, native_dialog_sequence
        reported_native_dialog_sequence = 0
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
                    expression = command.get("params", {}).get("expression", "")
                    if expression == "void 0" and command.get("sessionId"):
                        with pages_lock:
                            dialog = None if native_dialog is None else native_dialog.copy()
                        if dialog is not None:
                            if reported_native_dialog_sequence != dialog["sequence"]:
                                self.write_websocket_text(json.dumps({
                                    "method": "Page.javascriptDialogOpening",
                                    "sessionId": command["sessionId"],
                                    "params": {
                                        "url": dialog["url"],
                                        "message": dialog["message"],
                                        "type": dialog["type"],
                                        "hasBrowserHandler": False,
                                        "defaultPrompt": dialog.get("defaultPrompt", ""),
                                    },
                                }))
                                reported_native_dialog_sequence = dialog["sequence"]
                            # Runtime is blocked while a real JavaScript Dialog is open.
                            continue
                        response = {
                            "id": command["id"],
                            "sessionId": command["sessionId"],
                            "result": {"result": {"type": "undefined"}},
                        }
                        self.write_websocket_text(json.dumps(response))
                        continue
                    if expression == "document.visibilityState":
                        page_id = self.path.rsplit("/", 1)[-1]
                        response = {
                            "id": command["id"],
                            "result": {
                                "result": {
                                    "type": "string",
                                    "value": (
                                        "visible" if page_id == active_page_id else "hidden"
                                    ),
                                }
                            },
                        }
                        self.write_websocket_text(json.dumps(response))
                        continue
                    if "redactedRegionCount: count" in expression:
                        response = {
                            "id": command["id"],
                            "result": {
                                "result": {
                                    "type": "object",
                                    "value": {
                                        "version": 1,
                                        "redactedRegionCount": 1,
                                    },
                                }
                            },
                        }
                        self.write_websocket_text(json.dumps(response))
                        continue
                    if "return {valid: false, redactedRegionCount: 0}" in expression:
                        response = {
                            "id": command["id"],
                            "result": {
                                "result": {
                                    "type": "object",
                                    "value": {
                                        "valid": True,
                                        "redactedRegionCount": 1,
                                    },
                                }
                            },
                        }
                        self.write_websocket_text(json.dumps(response))
                        continue
                    if "__agent_browser_sensitive_redaction_v1" in expression:
                        response = {
                            "id": command["id"],
                            "result": {
                                "result": {"type": "boolean", "value": True}
                            },
                        }
                        self.write_websocket_text(json.dumps(response))
                        continue
                    evaluation_count += 1
                    requested_root = None
                    root_marker = "const requestedRoot = "
                    if root_marker in expression:
                        encoded_root = expression.split(root_marker, 1)[1].split(";", 1)[0]
                        requested_root = json.loads(encoded_root)
                    target_name = (
                        "Continue integration"
                        if mutate_after > 0 and evaluation_count >= mutate_after
                        else "Run integration"
                    )
                    page_id = self.path.rsplit("/", 1)[-1]
                    with pages_lock:
                        page = pages.get(page_id, pages["page-1"]).copy()
                    result = {
                        "url": page["url"],
                        "title": page["title"],
                        "documentReadyState": "complete",
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
                            "value": public_note_value,
                            "controlType": "text",
                            "bounds": {"x": 20.0, "y": 84.0, "width": 240.0, "height": 36.0},
                            "enabled": True,
                            "visible": True,
                            "sensitive": False,
                            "focused": focused_control == "public-note",
                        }, {
                            "path": "html:nth-of-type(1)>body:nth-of-type(1)>input:nth-of-type(2)",
                            "role": "textbox",
                            "name": None,
                            "bounds": {"x": 20.0, "y": 138.0, "width": 240.0, "height": 36.0},
                            "enabled": True,
                            "visible": True,
                            "sensitive": True,
                        }, {
                            "path": "html:nth-of-type(1)>body:nth-of-type(1)>input:nth-of-type(3)",
                            "role": "checkbox",
                            "name": "Remember integration",
                            "controlType": "checkbox",
                            "bounds": {"x": 20.0, "y": 240.0, "width": 24.0, "height": 24.0},
                            "enabled": True,
                            "visible": True,
                            "sensitive": False,
                            "checked": checkbox_checked,
                            "focused": focused_control == "checkbox",
                        }, {
                            "path": "html:nth-of-type(1)>body:nth-of-type(1)>button:nth-of-type(2)",
                            "role": "button",
                            "name": "Open native alert",
                            "controlType": "button",
                            "bounds": {"x": 20.0, "y": 300.0, "width": 180.0, "height": 36.0},
                            "enabled": True,
                            "visible": True,
                            "sensitive": False,
                        }, {
                            "path": "html:nth-of-type(1)>body:nth-of-type(1)>button:nth-of-type(3)",
                            "role": "button",
                            "name": "Open native confirm",
                            "controlType": "button",
                            "bounds": {"x": 20.0, "y": 346.0, "width": 180.0, "height": 36.0},
                            "enabled": True,
                            "visible": True,
                            "sensitive": False,
                        }, {
                            "path": "html:nth-of-type(1)>body:nth-of-type(1)>button:nth-of-type(4)",
                            "role": "button",
                            "name": "Open native prompt",
                            "controlType": "button",
                            "bounds": {"x": 20.0, "y": 392.0, "width": 180.0, "height": 36.0},
                            "enabled": True,
                            "visible": True,
                            "sensitive": False,
                        }, {
                            "path": "html:nth-of-type(1)>body:nth-of-type(1)>button:nth-of-type(5)",
                            "role": "button",
                            "name": "Open native beforeunload",
                            "controlType": "button",
                            "bounds": {"x": 20.0, "y": 438.0, "width": 210.0, "height": 36.0},
                            "enabled": True,
                            "visible": True,
                            "sensitive": False,
                        }],
                    }
                    if requested_root is not None:
                        if requested_root not in ("body", "document"):
                            result = {
                                "url": "https://example.test/runtime",
                                "title": "Browser Cloud Test Page",
                                "documentReadyState": "complete",
                                "error": "REGION_ROOT_NOT_FOUND",
                            }
                        else:
                            result["rootPath"] = (
                                "html:nth-of-type(1)>body:nth-of-type(1)"
                                if requested_root == "body"
                                else "html:nth-of-type(1)"
                            )
                    if business_recovery_completed and "targets" in result:
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
            elif method == "Target.createTarget":
                with pages_lock:
                    page_id = f"page-{len(pages) + 1}"
                    pages[page_id] = {
                        "title": "Agent opened tab",
                        "url": command.get("params", {}).get("url", "about:blank"),
                    }
                    active_page_id = page_id
                response = {"id": command["id"], "result": {"targetId": page_id}}
            elif method == "Target.activateTarget":
                page_id = command.get("params", {}).get("targetId")
                with pages_lock:
                    exists = page_id in pages
                    if exists:
                        active_page_id = page_id
                response = (
                    {"id": command["id"], "result": {}}
                    if exists
                    else {
                        "id": command["id"],
                        "error": {"code": -32602, "message": "unknown target"},
                    }
                )
            elif method == "Target.closeTarget":
                page_id = command.get("params", {}).get("targetId")
                with pages_lock:
                    success = page_id in pages and len(pages) > 1
                    if success:
                        del pages[page_id]
                        if active_page_id == page_id:
                            active_page_id = next(iter(pages))
                response = {"id": command["id"], "result": {"success": success}}
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
            elif method == "DOM.getDocument":
                response = {
                    "id": command["id"],
                    "result": {"root": {"nodeId": 100}},
                }
            elif method == "DOM.querySelector":
                if command.get("params", {}).get("selector") != (
                    "#__agent_browser_sensitive_redaction_v1"
                ):
                    response = {
                        "id": command["id"],
                        "error": {"code": -32602, "message": "unexpected selector"},
                    }
                else:
                    response = {
                        "id": command["id"],
                        "result": {"nodeId": 101},
                    }
            elif method == "DOM.querySelectorAll":
                if command.get("params", {}).get("selector") != (
                    "[data-agent-browser-redaction-region]"
                ):
                    response = {
                        "id": command["id"],
                        "error": {"code": -32602, "message": "unexpected selector"},
                    }
                else:
                    response = {
                        "id": command["id"],
                        "result": {"nodeIds": [102]},
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
            elif method == "Page.handleJavaScriptDialog":
                page_id = self.path.rsplit("/", 1)[-1]
                params = command.get("params", {})
                with pages_lock:
                    dialog = None if native_dialog is None else native_dialog.copy()
                    valid = dialog is not None and dialog["tabId"] == page_id
                    if valid:
                        if (
                            params.get("accept")
                            and dialog["type"] == "prompt"
                            and "promptText" in params
                        ):
                            public_note_value = params["promptText"]
                        native_dialog = None
                response = (
                    {"id": command["id"], "result": {}}
                    if valid
                    else {
                        "id": command["id"],
                        "error": {"code": -32000, "message": "No dialog is showing"},
                    }
                )
            elif method == "Input.dispatchMouseEvent":
                params = command.get("params", {})
                event_type = params.get("type")
                pointer_x = float(params.get("x", pointer_x))
                pointer_y = float(params.get("y", pointer_y))
                if event_type == "mouseReleased" and params.get("button") == "left":
                    if 20 <= pointer_x <= 260 and 84 <= pointer_y <= 120:
                        focused_control = "public-note"
                        select_all = False
                    elif 20 <= pointer_x <= 44 and 240 <= pointer_y <= 264:
                        focused_control = "checkbox"
                        checkbox_checked = not checkbox_checked
                    elif 20 <= pointer_x <= 200 and 300 <= pointer_y <= 336:
                        native_dialog_sequence += 1
                        native_dialog = {
                            "sequence": native_dialog_sequence,
                            "tabId": self.path.rsplit("/", 1)[-1],
                            "url": "https://example.test/runtime",
                            "type": "alert",
                            "message": "Integration native alert",
                        }
                    elif 20 <= pointer_x <= 200 and 346 <= pointer_y <= 382:
                        native_dialog_sequence += 1
                        native_dialog = {
                            "sequence": native_dialog_sequence,
                            "tabId": self.path.rsplit("/", 1)[-1],
                            "url": "https://example.test/runtime",
                            "type": "confirm",
                            "message": "Confirm integration action",
                        }
                    elif 20 <= pointer_x <= 200 and 392 <= pointer_y <= 428:
                        native_dialog_sequence += 1
                        native_dialog = {
                            "sequence": native_dialog_sequence,
                            "tabId": self.path.rsplit("/", 1)[-1],
                            "url": "https://example.test/runtime",
                            "type": "prompt",
                            "message": "Enter integration value",
                            "defaultPrompt": "",
                        }
                    elif 20 <= pointer_x <= 230 and 438 <= pointer_y <= 474:
                        native_dialog_sequence += 1
                        native_dialog = {
                            "sequence": native_dialog_sequence,
                            "tabId": self.path.rsplit("/", 1)[-1],
                            "url": "https://example.test/runtime",
                            "type": "beforeunload",
                            "message": "Leave integration page?",
                        }
                    else:
                        focused_control = None
                        select_all = False
                response = {"id": command["id"], "result": {}}
            elif method == "Input.dispatchKeyEvent":
                params = command.get("params", {})
                event_type = params.get("type")
                key_name = params.get("key")
                if key_name == "Control":
                    control_pressed = event_type == "keyDown"
                elif event_type == "keyDown" and key_name == "a" and control_pressed:
                    select_all = focused_control == "public-note"
                elif event_type == "keyDown" and key_name in ("Backspace", "Delete"):
                    if focused_control == "public-note" and select_all:
                        public_note_value = ""
                        select_all = False
                response = {"id": command["id"], "result": {}}
            elif method == "Input.insertText":
                if focused_control == "public-note":
                    text = command.get("params", {}).get("text", "")
                    public_note_value = text if select_all else public_note_value + text
                    select_all = False
                response = {"id": command["id"], "result": {}}
            else:
                response = {"id": command.get("id", 1), "result": {}}
            self.write_websocket_text(json.dumps(response))
            if method == "Target.setAutoAttach":
                self.write_websocket_text(json.dumps({
                    "method": "Target.attachedToTarget",
                    "params": {
                        "sessionId": "fake-page-session",
                        "targetInfo": {"targetId": active_page_id, "type": "page"},
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
