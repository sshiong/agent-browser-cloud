#!/usr/bin/env python3

import json
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

port = int(sys.argv[1])
event_log = sys.argv[2]


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def do_GET(self):
        with open(event_log, "a", encoding="utf-8") as log:
            log.write(json.dumps({"method": "GET", "target": self.path}) + "\n")
        body = json.dumps(
            {"exitIp": "203.0.113.10", "country": "TEST", "asn": "AS64500"}
        ).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, _format, *_args):
        return


ThreadingHTTPServer(("127.0.0.1", port), Handler).serve_forever()
