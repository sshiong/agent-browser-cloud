#!/usr/bin/env python3
"""Allowlisted forward proxy used only by authorized real-URL compatibility tests."""

import http.client
import ipaddress
import json
import os
import select
import socket
import sys
import threading
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ALLOWED_HOSTS = {
    host.strip().lower().rstrip(".")
    for host in os.environ.get("PROXY_ALLOWED_HOSTS", "").split(",")
    if host.strip()
}
EXIT_CHECK_HOST = "browsercloud.invalid"
CONTROL_FIXTURE_HOST = "agent-controls.invalid"
EXIT_IP = os.environ.get("PROXY_TEST_EXIT_IP", "203.0.113.10")
LOG_PATH = os.environ.get("PROXY_EVENT_LOG", "")
LOG_LOCK = threading.Lock()


def log_event(event, **details):
    if not LOG_PATH:
        return
    record = json.dumps({"event": event, **details}, sort_keys=True)
    with LOG_LOCK, open(LOG_PATH, "a", encoding="utf-8") as log_file:
        log_file.write(record + "\n")


def normalized_host(value):
    return value.strip().lower().rstrip(".")


def require_allowed_host(host):
    host = normalized_host(host)
    if host not in ALLOWED_HOSTS:
        raise PermissionError(f"host is not allowlisted: {host}")
    return host


def public_addresses(host, port):
    addresses = []
    for family, socktype, proto, _, sockaddr in socket.getaddrinfo(
        host, port, type=socket.SOCK_STREAM
    ):
        address = ipaddress.ip_address(sockaddr[0])
        if (
            address.is_private
            or address.is_loopback
            or address.is_link_local
            or address.is_multicast
            or address.is_reserved
            or address.is_unspecified
        ):
            continue
        addresses.append((family, socktype, proto, sockaddr))
    if not addresses:
        raise PermissionError(f"host has no public address: {host}")
    return addresses


def connect_public(host, port):
    last_error = None
    for family, socktype, proto, sockaddr in public_addresses(host, port):
        upstream = socket.socket(family, socktype, proto)
        upstream.settimeout(15)
        try:
            upstream.connect(sockaddr)
            upstream.settimeout(None)
            return upstream
        except OSError as error:
            last_error = error
            upstream.close()
    raise ConnectionError(f"cannot connect to {host}:{port}") from last_error


def relay(left, right):
    sockets = [left, right]
    while True:
        readable, _, exceptional = select.select(sockets, [], sockets, 30)
        if exceptional or not readable:
            return
        for source in readable:
            target = right if source is left else left
            data = source.recv(64 * 1024)
            if not data:
                return
            target.sendall(data)


class ProxyHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def do_CONNECT(self):
        try:
            host, port_text = self.path.rsplit(":", 1)
            host = require_allowed_host(host)
            port = int(port_text)
            if port != 443:
                raise PermissionError("CONNECT is restricted to port 443")
            upstream = connect_public(host, port)
        except (ValueError, OSError, PermissionError) as error:
            log_event("connect_denied", target=self.path, reason=str(error))
            self.send_error(403, "CONNECT target denied")
            return
        log_event("connect_allowed", host=host, port=port)
        self.send_response(200, "Connection Established")
        self.end_headers()
        try:
            relay(self.connection, upstream)
        finally:
            upstream.close()

    def do_GET(self):
        parsed = urllib.parse.urlsplit(self.path)
        host = normalized_host(parsed.hostname or self.headers.get("Host", "").split(":")[0])
        if host == EXIT_CHECK_HOST and parsed.path == "/exit":
            payload = json.dumps(
                {"exitIp": EXIT_IP, "country": "TEST", "asn": "AS64500"}
            ).encode()
            log_event("exit_check", host=host)
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)
            return
        if host == CONTROL_FIXTURE_HOST:
            require_allowed_host(host)
            body = b"""<!doctype html>
<html lang="en"><head><meta charset="utf-8"><title>Agent Control Fixture</title>
<style>body{font-family:sans-serif;min-height:2400px;padding:32px}label,input{display:block}
input{width:360px;height:36px;margin:8px 0 24px}</style></head>
<body><main><h1>Authorized Agent controls</h1>
<label for="public-marker">Public test marker</label>
<input id="public-marker" name="public-marker" aria-label="Public test marker">
<button type="button">Safe local action</button>
<p>Deterministic authorized fixture for real Chrome controls.</p></main></body></html>"""
            log_event("control_fixture", host=host, path=parsed.path)
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        try:
            host = require_allowed_host(host)
            port = parsed.port or 80
            if port != 80:
                raise PermissionError("plain HTTP is restricted to port 80")
            # Resolve and reject private destinations before using the HTTP client.
            public_addresses(host, port)
            path = urllib.parse.urlunsplit(("", "", parsed.path or "/", parsed.query, ""))
            connection = http.client.HTTPConnection(host, port, timeout=15)
            headers = {
                key: value
                for key, value in self.headers.items()
                if key.lower() not in {"connection", "proxy-connection", "host"}
            }
            connection.request("GET", path, headers=headers)
            response = connection.getresponse()
            body = response.read(2 * 1024 * 1024 + 1)
            if len(body) > 2 * 1024 * 1024:
                raise ValueError("upstream response exceeds 2 MiB")
        except (OSError, PermissionError, ValueError) as error:
            log_event("http_denied", target=self.path, reason=str(error))
            self.send_error(403, "HTTP target denied")
            return
        log_event("http_allowed", host=host, port=port, path=path)
        self.send_response(response.status, response.reason)
        for key, value in response.getheaders():
            if key.lower() not in {
                "connection",
                "content-length",
                "keep-alive",
                "proxy-authenticate",
                "transfer-encoding",
                "upgrade",
            }:
                self.send_header(key, value)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)
        connection.close()

    def log_message(self, message, *args):
        log_event("proxy_log", message=message % args)


if __name__ == "__main__":
    if len(sys.argv) != 2 or not ALLOWED_HOSTS:
        raise SystemExit(
            "usage: allowlist-forward-proxy.py <port>; set PROXY_ALLOWED_HOSTS"
        )
    ThreadingHTTPServer(("127.0.0.1", int(sys.argv[1])), ProxyHandler).serve_forever()
