#!/usr/bin/env python3

import json
import os
import secrets
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

port = int(sys.argv[1])
event_log = sys.argv[2]
expected_authorization = os.environ.get("PROXY_TEST_AUTHORIZATION", "")


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def do_GET(self):
        authorization = self.headers.get("Proxy-Authorization", "")
        authorized = not expected_authorization or secrets.compare_digest(
            authorization, expected_authorization
        )
        with open(event_log, "a", encoding="utf-8") as log:
            log.write(
                json.dumps(
                    {
                        "method": "GET",
                        "target": self.path,
                        "authenticated": authorized,
                    }
                )
                + "\n"
            )
        if not authorized:
            self.send_response(407)
            self.send_header("Proxy-Authenticate", 'Basic realm="integration"')
            self.send_header("Content-Length", "0")
            self.send_header("Connection", "close")
            self.end_headers()
            return
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
