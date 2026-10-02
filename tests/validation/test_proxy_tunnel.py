import importlib.util
import http.client
import pathlib
import socket
import threading
import unittest
from http.server import ThreadingHTTPServer
from unittest.mock import patch


SPEC = importlib.util.spec_from_file_location(
    "replay_forward_proxy",
    pathlib.Path(__file__).parents[1] / "fixtures" / "allowlist-forward-proxy.py",
)
PROXY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PROXY)


class ProxyTunnelTest(unittest.TestCase):
    def read_exact(self, connection, expected):
        data = b""
        while len(data) < len(expected):
            chunk = connection.recv(len(expected) - len(data))
            self.assertTrue(chunk, "tunnel closed before all bytes arrived")
            data += chunk
        self.assertEqual(data, expected)

    def check_tunnel_closes(self, upstream_eof):
        upstream, peer = socket.socketpair()
        server = ThreadingHTTPServer(("127.0.0.1", 0), PROXY.ProxyHandler)
        worker = threading.Thread(target=server.serve_forever, kwargs={"poll_interval": 0.01})
        relay = PROXY.relay
        with (
            patch.object(PROXY, "ALLOWED_HOSTS", {"public.example"}),
            patch.object(PROXY, "connect_public", return_value=upstream),
            patch.object(PROXY, "relay", side_effect=lambda left, right: relay(
                left, right, 1 if upstream_eof else 0.1
            )),
        ):
            worker.start()
            try:
                with socket.create_connection(server.server_address, timeout=2) as client:
                    client.sendall(b"CONNECT public.example:443 HTTP/1.1\r\nHost: public.example\r\n\r\n")
                    response = b""
                    while b"\r\n\r\n" not in response:
                        chunk = client.recv(4096)
                        self.assertTrue(chunk, "proxy closed before CONNECT response")
                        response += chunk
                        self.assertLess(len(response), 8192)
                    self.assertIn(b"200 Connection Established", response)
                    if upstream_eof:
                        peer.settimeout(2)
                        client.sendall(b"tunnel-request")
                        self.read_exact(peer, b"tunnel-request")
                        peer.sendall(b"tunnel-response")
                        self.read_exact(client, b"tunnel-response")
                        peer.shutdown(socket.SHUT_WR)
                    self.assertEqual(client.recv(4096), b"", "finished tunnel must close browser TCP")
            finally:
                peer.close()
                server.shutdown()
                server.server_close()
                worker.join(timeout=2)
                upstream.close()
                self.assertFalse(worker.is_alive())

    def test_upstream_eof_closes_browser_tunnel(self):
        self.check_tunnel_closes(upstream_eof=True)

    def test_idle_timeout_closes_browser_tunnel(self):
        self.check_tunnel_closes(upstream_eof=False)

    def test_plaintext_oidc_callback_is_rejected_before_code_exchange(self):
        server = ThreadingHTTPServer(("127.0.0.1", 0), PROXY.ProxyHandler)
        worker = threading.Thread(target=server.serve_forever, kwargs={"poll_interval": 0.01})
        with patch.object(PROXY, "ALLOWED_HOSTS", {"agent-controls.invalid"}), \
             patch.object(PROXY, "OIDC_CLIENT") as client:
            worker.start()
            connection = http.client.HTTPConnection(*server.server_address, timeout=2)
            try:
                connection.request("POST", "http://agent-controls.invalid/oidc-callback",
                                   "code=test-code&state=test-state",
                                   {"Content-Type": "application/x-www-form-urlencoded"})
                response = connection.getresponse()
                self.assertEqual(response.status, 403)
                response.read()
                client.complete.assert_not_called()
            finally:
                connection.close()
                server.shutdown()
                server.server_close()
                worker.join(timeout=2)
