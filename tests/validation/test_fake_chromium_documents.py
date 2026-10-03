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
    def start_fixture(self, profile, port=0):
        # stderr stays private; failures expose only a fixed category and exit code.
        error_log = tempfile.TemporaryFile()
        self.addCleanup(error_log.close)
        child = subprocess.Popen(
            [str(ROOT / "tests/fixtures/fake-chromium.sh"),
             f"--remote-debugging-port={port}", f"--user-data-dir={profile}"],
            stdout=subprocess.DEVNULL, stderr=error_log,
        )
        self.addCleanup(self.stop_child, child)
        active_port = Path(profile) / "DevToolsActivePort"
        deadline = time.monotonic() + 5
        while True:
            code = child.poll()
            if code is not None or time.monotonic() >= deadline:
                error_log.seek(0)
                error = error_log.read(4096)
                kind = "UNKNOWN"
                if b"Address already in use" in error:
                    kind = "PORT_IN_USE"
                elif b"fake Chromium requires" in error:
                    kind = "ARGUMENT_REJECTED"
                status = "FIXTURE_EXIT" if code is not None else "STARTUP_DEADLINE"
                self.fail(f"{status} code={code} kind={kind}")
            try:
                assigned = int(active_port.read_text().splitlines()[0])
                self.assertTrue(0 < assigned < 65536)
                if port:
                    self.assertEqual(assigned, port)
                with urllib.request.urlopen(
                    f"http://127.0.0.1:{assigned}/json/list", timeout=1
                ) as response:
                    target = json.load(response)[0]["id"]
                return child, assigned, target
            except (OSError, ValueError, IndexError):
                time.sleep(0.02)

    def test_snapshot_pair_reload_and_tab_have_authoritative_identities(self):
        with tempfile.TemporaryDirectory(prefix="ab-fixture-doc-") as profile:
            _, port, target = self.start_fixture(profile)
            with contextlib.closing(CdpSocket(port, target)) as cdp:
                before = cdp.command("Page.getFrameTree")["result"]["frameTree"]["frame"]
                snapshot = cdp.command("Runtime.evaluate", {"expression": "document.title"})[
                    "result"]["result"]["value"]
                self.assertTrue(snapshot["targets"])
                self.assertTrue(all(target.get("interactive") is True
                                    for target in snapshot["targets"]),
                                "fixture controls must explicitly declare interactivity")
                self.assertTrue(any(target.get("controlType") == "file"
                                    and target["interactive"] and not target["visible"]
                                    for target in snapshot["targets"]))
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

    def test_child_owned_ports_are_distinct_and_published_in_cdp(self):
        with tempfile.TemporaryDirectory(prefix="ab-fixture-port-a-") as first, \
                tempfile.TemporaryDirectory(prefix="ab-fixture-port-b-") as second:
            _, first_port, first_target = self.start_fixture(first)
            _, second_port, second_target = self.start_fixture(second)
            self.assertNotEqual(first_port, second_port)
            for port, target in [(first_port, first_target), (second_port, second_target)]:
                with urllib.request.urlopen(f"http://127.0.0.1:{port}/json/list") as response:
                    self.assertEqual(json.load(response)[0]["webSocketDebuggerUrl"],
                                     f"ws://127.0.0.1:{port}/devtools/page/{target}")

    def test_occupied_fixed_port_exposes_only_bounded_startup_diagnosis(self):
        with tempfile.TemporaryDirectory(prefix="ab-fixture-occupied-") as profile, \
                socket.socket() as occupied:
            occupied.bind(("127.0.0.1", 0))
            occupied.listen()
            with self.assertRaisesRegex(AssertionError, "^FIXTURE_EXIT code=1 kind=PORT_IN_USE$"):
                self.start_fixture(profile, occupied.getsockname()[1])

    @staticmethod
    def stop_child(child):
        if child.poll() is None:
            child.terminate()
        child.wait(timeout=5)


if __name__ == "__main__":
    unittest.main()
