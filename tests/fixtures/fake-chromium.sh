#!/usr/bin/env sh

cdp_port=""
for argument in "$@"; do
  case "$argument" in
    --remote-debugging-port=*)
      cdp_port="${argument#*=}"
      ;;
  esac
done

if [ -z "$cdp_port" ]; then
  echo "fake Chromium requires --remote-debugging-port" >&2
  exit 2
fi

exec python3 - "$cdp_port" <<'PY'
import json
import signal
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

port = int(sys.argv[1])

class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
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
        else:
            self.send_error(404)
            return
        encoded = json.dumps(payload).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(encoded)))
        self.end_headers()
        self.wfile.write(encoded)

    def log_message(self, _format, *_args):
        return

server = ThreadingHTTPServer(("127.0.0.1", port), Handler)
signal.signal(signal.SIGTERM, lambda *_: sys.exit(0))
signal.signal(signal.SIGINT, lambda *_: sys.exit(0))
server.serve_forever()
PY
