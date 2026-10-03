"""Exercise the real integration fixture's document identity over its CDP socket."""

import base64
import contextlib
import json
import os
from pathlib import Path
import socket
import struct
import subprocess
import tempfile
import time
import unittest
import urllib.request


ROOT = Path(__file__).resolve().parents[2]


class CdpSocket:
    def __init__(self, port, target):
        self.socket = socket.create_connection(("127.0.0.1", port), timeout=3)
        self.stream = self.socket.makefile("rb")
        key = base64.b64encode(os.urandom(16)).decode()
        self.socket.sendall((
            f"GET /devtools/page/{target} HTTP/1.1\r\nHost: 127.0.0.1:{port}\r\n"
            f"Upgrade: websocket\r\nConnection: Upgrade\r\n"
            f"Sec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n\r\n"
        ).encode())
        if not self.stream.readline().startswith(b"HTTP/1.1 101 "):
            raise AssertionError("fixture did not upgrade the CDP socket")
        for _ in range(100):
            line = self.stream.readline()
            if line == b"\r\n":
                break
            if not line:
                raise AssertionError("fixture CDP upgrade headers were incomplete")
        else:
            raise AssertionError("fixture CDP upgrade headers exceeded the budget")
        self.sequence = 0

    def close(self):
        self.stream.close()
        self.socket.close()

    def command(self, method, params=None):
        self.sequence += 1
        payload = json.dumps({"id": self.sequence, "method": method,
                              "params": params or {}}).encode()
        mask = os.urandom(4)
        size = len(payload)
        header = bytes([0x81, 0x80 | size]) if size < 126 else (
            bytes([0x81, 0xFE]) + struct.pack("!H", size)
        )
        self.socket.sendall(header + mask + bytes(
            value ^ mask[index % 4] for index, value in enumerate(payload)
        ))
        for _ in range(20):
            header = self.stream.read(2)
            if len(header) != 2 or header[0] != 0x81 or header[1] & 0x80:
                raise AssertionError("invalid fixture CDP frame")
            size = header[1] & 0x7F
            if size == 126:
                size = struct.unpack("!H", self.stream.read(2))[0]
            elif size == 127:
                size = struct.unpack("!Q", self.stream.read(8))[0]
            if size > 65536:
                raise AssertionError("oversized fixture CDP frame")
            response = json.loads(self.stream.read(size))
            if response.get("id") == self.sequence:
                return response
        raise AssertionError("fixture CDP response missing")


class FakeChromiumDocumentTests(unittest.TestCase):
    def test_snapshot_pair_reload_and_tab_have_authoritative_identities(self):
        with tempfile.TemporaryDirectory(prefix="ab-fixture-doc-") as profile:
            with socket.socket() as reservation:
                reservation.bind(("127.0.0.1", 0))
                port = reservation.getsockname()[1]
            child = subprocess.Popen(
                [str(ROOT / "tests/fixtures/fake-chromium.sh"),
                 f"--remote-debugging-port={port}", f"--user-data-dir={profile}"],
                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
            )
            self.addCleanup(self.stop_child, child)
            deadline = time.monotonic() + 5
            while True:
                try:
                    with urllib.request.urlopen(
                        f"http://127.0.0.1:{port}/json/list", timeout=1
                    ) as response:
                        target = json.load(response)[0]["id"]
                    break
                except OSError:
                    if child.poll() is not None or time.monotonic() >= deadline:
                        self.fail("fixture CDP startup failed")
                    time.sleep(0.02)
            with contextlib.closing(CdpSocket(port, target)) as cdp:
                before = cdp.command("Page.getFrameTree")["result"]["frameTree"]["frame"]
                cdp.command("Runtime.evaluate", {"expression": "document.title"})
                after = cdp.command("Page.getFrameTree")["result"]["frameTree"]["frame"]
                self.assertTrue(before["id"])
                self.assertTrue(before["loaderId"])
                self.assertNotIn("parentId", before)
                self.assertEqual(before, after)
                cdp.command("Page.reload")
                reloaded = cdp.command("Page.getFrameTree")["result"]["frameTree"]["frame"]
                self.assertEqual(before["url"], reloaded["url"])
                self.assertEqual(before["id"], reloaded["id"])
                self.assertNotEqual(before["loaderId"], reloaded["loaderId"])
                second = cdp.command("Target.createTarget", {"url": before["url"]})["result"]["targetId"]
                with contextlib.closing(CdpSocket(port, second)) as other:
                    frame = other.command("Page.getFrameTree")["result"]["frameTree"]["frame"]
                    self.assertNotEqual(frame["id"], reloaded["id"])
                    self.assertNotEqual(frame["loaderId"], reloaded["loaderId"])
                    cdp.command("Target.closeTarget", {"targetId": second})
                    self.assertIn("error", other.command("Page.getFrameTree"))

    @staticmethod
    def stop_child(child):
        if child.poll() is None:
            child.terminate()
        child.wait(timeout=5)


if __name__ == "__main__":
    unittest.main()
