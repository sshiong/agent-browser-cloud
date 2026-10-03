#!/usr/bin/env python3
"""Allowlisted forward proxy used only by authorized real-URL compatibility tests."""

import http.client
import html
import ipaddress
import json
import os
import select
import socket
import ssl
import sys
import threading
import urllib.parse
import importlib.util
import pathlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ALLOWED_HOSTS = {
    host.strip().lower().rstrip(".")
    for host in os.environ.get("PROXY_ALLOWED_HOSTS", "").split(",")
    if host.strip()
}
EXIT_CHECK_HOST = "browsercloud.invalid"
CONTROL_FIXTURE_HOST = "agent-controls.invalid"
OPAQUE_CHALLENGE_HOST = "opaque-challenge.invalid"
EXIT_IP = os.environ.get("PROXY_TEST_EXIT_IP", "203.0.113.10")
LOG_PATH = os.environ.get("PROXY_EVENT_LOG", "")
LOG_LOCK = threading.Lock()

OIDC_SPEC = importlib.util.spec_from_file_location(
    "public_oidc_client", pathlib.Path(__file__).with_name("public-oidc-client.py")
)
OIDC_MODULE = importlib.util.module_from_spec(OIDC_SPEC)
OIDC_SPEC.loader.exec_module(OIDC_MODULE)
OIDC_CLIENT = OIDC_MODULE.PublicOidcClient()
OIDC_TLS_ADDRESS = None


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


def relay(left, right, idle_timeout=30):
    sockets = [left, right]
    # Bound each complete write as well as select's idle wait. A peer that stops reading
    # must not keep a CONNECT handler blocked indefinitely in sendall.
    for connection in sockets:
        connection.settimeout(idle_timeout)
    while True:
        readable, _, exceptional = select.select(sockets, [], sockets, idle_timeout)
        if exceptional or not readable:
            return
        for source in readable:
            target = right if source is left else left
            try:
                data = source.recv(64 * 1024)
                if not data:
                    return
                target.sendall(data)
            except OSError:
                # Partial writes are terminal: close the tunnel without retrying encrypted
                # bytes or emitting transport errors that might contain endpoint details.
                return


class ProxyHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    @staticmethod
    def login_html(title, status_text):
        return f"""<!doctype html>
<html lang="en"><head><meta charset="utf-8"><title>{title}</title>
<style>body{{font-family:sans-serif;padding:32px}}label,input{{display:block}}
input{{width:360px;height:36px;margin:8px 0 24px}}button{{height:40px;width:180px}}
</style></head><body><main><h1>Controlled login fixture</h1>
<p role="status">{status_text}</p>
<form method="post" action="/login">
<label for="username">Username</label><input id="username" name="username" autocomplete="username">
<label for="password">Password</label><input id="password" name="password" type="password" autocomplete="current-password">
<button id="submit-login" type="submit">Sign in</button>
</form></main></body></html>""".encode()

    @staticmethod
    def otp_html(title="Fixture OTP Verification", status_text="Enter the one-time code"):
        return f"""<!doctype html>
<html lang="en"><head><meta charset="utf-8"><title>{title}</title>
<style>body{{font-family:sans-serif;padding:32px}}label,input{{display:block}}
input{{width:360px;height:36px;margin:8px 0 24px}}button{{height:40px;width:180px}}
</style></head><body><main><h1>Controlled OTP verification</h1>
<p role="status">{status_text}</p>
<form method="post" action="/otp" id="otp-form">
<label for="otp">One-time code</label>
<input id="otp" name="otp" inputmode="numeric" autocomplete="one-time-code" maxlength="6">
<button id="submit-otp" type="submit">Verify code</button>
</form>
<script>document.querySelector('#otp').addEventListener('input',event=>{{if(event.target.value.length===6)document.querySelector('#otp-form').requestSubmit();}});</script>
</main></body></html>""".encode()

    def send_fixture(self, body, status=200, headers=None):
        headers = headers or {}
        self.send_response(status)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        for key, value in headers.items():
            self.send_header(key, value)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):
        parsed = urllib.parse.urlsplit(self.path)
        host = normalized_host(
            parsed.hostname or self.headers.get("Host", "").split(":")[0]
        )
        if host == CONTROL_FIXTURE_HOST and parsed.path == "/oidc-callback":
            require_allowed_host(host)
            if not isinstance(self.connection, ssl.SSLSocket):
                self.close_connection = True
                self.send_fixture(b"OIDC callback requires TLS", status=403)
                return
            try:
                length = int(self.headers.get("Content-Length", "0"))
                if not 0 < length <= 8192 or self.headers.get_content_type() != "application/x-www-form-urlencoded":
                    raise ValueError("OIDC_CALLBACK_REJECTED")
                location = OIDC_CLIENT.complete(self.rfile.read(length))
            except Exception as error:
                log_event("oidc_rejected", host=host, **OIDC_MODULE.failure_metadata(error))
                self.close_connection = True
                self.send_fixture(b"OIDC verification failed", status=403)
                return
            log_event("oidc_verified", host=host, **OIDC_CLIENT.proof)
            self.send_fixture(b"", status=303, headers={"Location": location, "Cache-Control": "no-store"})
            return
        if host != CONTROL_FIXTURE_HOST or parsed.path not in {"/login", "/otp"}:
            self.send_error(403, "POST target denied")
            return
        length = int(self.headers.get("Content-Length", "0"))
        fields = urllib.parse.parse_qs(
            self.rfile.read(length).decode("utf-8", "replace")
        )
        if parsed.path == "/otp":
            # This is a repository-owned one-time test code. Only the boolean outcome is logged.
            ok = fields.get("otp", [""])[0] == "246810"
            log_event("otp_attempt", host=host, success=ok)
            if ok:
                body = b"""<!doctype html><html lang="en"><head><meta charset="utf-8"><title>Fixture OTP Dashboard</title></head>
<body><main><h1>Fixture OTP Dashboard</h1><p role="status">OTP verified</p><a href="/otp-dashboard">Account home</a></main></body></html>"""
                self.send_fixture(
                    body,
                    status=303,
                    headers={
                        "Location": "/otp-dashboard",
                        "Set-Cookie": "fixture_otp_session=ok; Path=/; HttpOnly; SameSite=Lax",
                    },
                )
            else:
                self.send_fixture(
                    self.otp_html("Fixture OTP Failed", "Invalid one-time code"),
                    status=401,
                )
            return
        # These are repository-owned fixture credentials; values are never logged.
        ok = (
            fields.get("username", [""])[0] == "fixture-user"
            and fields.get("password", [""])[0] == "fixture-pass"
        )
        log_event("login_attempt", host=host, success=ok)
        if ok:
            body = b"""<!doctype html><html lang="en"><head><meta charset="utf-8"><title>Fixture Dashboard</title></head>
<body><main><h1>Fixture Dashboard</h1><p role="status">Login successful</p><a href="/dashboard">Account home</a></main></body></html>"""
            self.send_fixture(
                body,
                status=303,
                headers={
                    "Location": "/dashboard",
                    "Set-Cookie": "fixture_session=ok; Path=/",
                },
            )
        else:
            self.send_fixture(
                self.login_html("Fixture Login Failed", "Invalid username or password"),
                status=401,
            )

    def do_CONNECT(self):
        try:
            host, port_text = self.path.rsplit(":", 1)
            host = require_allowed_host(host)
            port = int(port_text)
            if port != 443:
                raise PermissionError("CONNECT is restricted to port 443")
            upstream = (socket.create_connection(OIDC_TLS_ADDRESS, timeout=15)
                        if host == CONTROL_FIXTURE_HOST and OIDC_TLS_ADDRESS is not None
                        else connect_public(host, port))
            upstream.settimeout(None)
        except (ValueError, OSError, PermissionError) as error:
            log_event("connect_denied", target=self.path, reason=str(error))
            self.send_error(403, "CONNECT target denied")
            return
        log_event("connect_allowed", host=host, port=port)
        # CONNECT takes over this TCP connection. When the relay finishes, close the browser
        # side as well; treating encrypted tunnel bytes as a subsequent HTTP request can leave
        # Chromium waiting indefinitely after the upstream has already gone away.
        self.close_connection = True
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
            if parsed.path == "/oidc-start":
                try:
                    location = OIDC_CLIENT.begin()
                except Exception as error:
                    log_event("oidc_rejected", host=host, **OIDC_MODULE.failure_metadata(error))
                    self.send_fixture(b"OIDC discovery failed", status=403)
                    return
                body = ('<!doctype html><html><head><title>Public OIDC client</title></head>'
                        '<body><h1>Public OIDC client</h1><a href="' + html.escape(location, quote=True)
                        + '">Authorize public OIDC client</a></body></html>').encode()
                self.send_fixture(body, headers={"Cache-Control": "no-store"})
                return
            if parsed.path == "/oidc-result":
                if not isinstance(self.connection, ssl.SSLSocket) or OIDC_CLIENT.proof is None:
                    self.send_fixture(b"OIDC proof unavailable", status=403)
                    return
                proof = json.dumps(OIDC_CLIENT.proof).encode()
                self.send_fixture(b"<!doctype html><html><head><title>OIDC verified</title></head>"
                                  b'<body><h1>OIDC verified</h1><p role="status">' + proof + b"</p></body></html>",
                                  headers={"Cache-Control": "no-store"})
                return
            if parsed.path == "/challenge":
                body = b"""<!doctype html>
<html lang="en"><head><meta charset="utf-8"><title>Authorized Simple Challenge</title>
<style>body{font-family:sans-serif;padding:32px}button{width:240px;height:56px}</style></head>
<body><main><h1>Simple challenge fixture</h1>
<button id="verify" type="button" onclick="document.title='Challenge passed';this.remove();document.querySelector('main').insertAdjacentHTML('beforeend','<p role=status>Challenge passed</p>')">Verify you are human</button>
</main></body></html>"""
            elif parsed.path == "/opaque-challenge":
                body = b"""<!doctype html>
<html lang="en"><head><meta charset="utf-8"><title>Verify you are human</title>
<style>body{font-family:sans-serif;padding:32px}iframe{border:1px solid #777;width:320px;height:180px}</style></head>
<body><main><h1>Verify you are human</h1>
<iframe id="hosted-verification" title="Hosted verification"></iframe>
<script>
addEventListener('message',event=>{if(event.origin==='http://opaque-challenge.invalid'&&event.data==='verified'){document.title='Opaque challenge passed';document.querySelector('h1').textContent='Opaque challenge passed';}});
setTimeout(()=>{document.querySelector('#hosted-verification').src='http://opaque-challenge.invalid/widget'},3000);
</script>
</main></body></html>"""
            elif parsed.path == "/login":
                body = self.login_html(
                    "Fixture Login", "Use the controlled fixture account"
                )
            elif parsed.path == "/otp":
                body = self.otp_html()
            elif parsed.path == "/otp-dashboard":
                body = b"""<!doctype html><html lang="en"><head><meta charset="utf-8"><title>Fixture OTP Dashboard</title></head>
<body><main><h1>Fixture OTP Dashboard</h1><p role="status">OTP verified</p><a href="/otp-dashboard">Account home</a></main></body></html>"""
            elif parsed.path == "/dashboard":
                body = b"""<!doctype html><html lang="en"><head><meta charset="utf-8"><title>Fixture Dashboard</title></head>
<body><main><h1>Fixture Dashboard</h1><p role="status">Login successful</p><a href="/dashboard">Account home</a></main></body></html>"""
            else:
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
        if host == OPAQUE_CHALLENGE_HOST and parsed.path == "/widget":
            require_allowed_host(host)
            body = b"""<!doctype html><html lang="en"><head><meta charset="utf-8"><title>Hosted verification</title>
<style>html,body{margin:0;width:100%;height:100%}button{width:100%;height:100%;font:18px sans-serif}</style></head>
<body><button type="button" onclick="parent.postMessage('verified','http://agent-controls.invalid')">Continue verification</button></body></html>"""
            log_event("opaque_challenge_fixture", host=host, path=parsed.path)
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
    certificate = os.environ.get("OIDC_FIXTURE_CERT_FILE")
    private_key = os.environ.get("OIDC_FIXTURE_KEY_FILE")
    if certificate and private_key:
        tls_server = ThreadingHTTPServer(("127.0.0.1", 0), ProxyHandler)
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.minimum_version = ssl.TLSVersion.TLSv1_2
        context.load_cert_chain(certificate, private_key)
        tls_server.socket = context.wrap_socket(tls_server.socket, server_side=True)
        OIDC_TLS_ADDRESS = tls_server.server_address
        threading.Thread(target=tls_server.serve_forever, daemon=True).start()
    ThreadingHTTPServer(("127.0.0.1", int(sys.argv[1])), ProxyHandler).serve_forever()
