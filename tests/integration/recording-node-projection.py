#!/usr/bin/env python3
"""Owned runner only: real Recorder/Helper/mTLS/PG; CDP/scanner protocol fixtures."""

import base64
import hashlib
import json
from pathlib import Path
import re
import sqlite3
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request


port, postgres, runtime, binding = sys.argv[1:]
assert port.isdecimal() and re.fullmatch(r"agentbrowser-postgres-it-[0-9-]+", postgres)
runtime = Path(runtime).resolve(strict=True)
assert runtime.name == "runtime" and (runtime / "node-journal.sqlite3").is_file()
base = f"http://127.0.0.1:{port}/api/v1"
http = urllib.request.build_opener(urllib.request.ProxyHandler({}))
headers = {
    "X-Tenant-Id": "tenant-integration",
    "X-Actor-Id": "recording-projection-admin",
    "X-Roles": "TENANT_ADMIN",
    "Content-Type": "application/json",
}


def request(path, method="GET", body=None, tenant=None):
    selected = dict(headers)
    if tenant:
        selected["X-Tenant-Id"] = tenant
    if method == "POST":
        selected["Idempotency-Key"] = "recording-projection-" + hashlib.sha256(
            path.encode()
        ).hexdigest()[:32]
    data = None if body is None else json.dumps(body).encode()
    with http.open(
        urllib.request.Request(base + path, data=data, headers=selected, method=method),
        timeout=10,
    ) as response:
        return json.load(response)


def wait_for(read, accepts, code):
    for _ in range(120):
        value = read()
        if accepts(value):
            return value
        time.sleep(0.25)
    raise AssertionError(code)


def sql(query):
    return subprocess.check_output(
        ["docker", "exec", postgres, "psql", "-v", "ON_ERROR_STOP=1", "-U",
         "browsercloud", "-d", "browsercloud", "-Atc", query],
        text=True, timeout=10,
    ).strip()


created = request("/sessions", "POST", {
    "tenantId": "tenant-integration", "profileId": "profile-recording-projection",
    "runtimeBuildId": "runtime_local_chromium", "region": "local",
    "proxyBindingProfileId": binding, "resourcePolicy": {"mode": "AUTO"},
    "videoRecording": True,
})
session = created["sessionId"]
assert re.fullmatch(r"ses_[A-Za-z0-9_-]+", session)
path = "/sessions/" + session
request(path + ":start", "POST")
wait_for(lambda: request(path), lambda x: x["state"] == "RUNNING", "RECORDING_START_TIMEOUT")
allocation = request(path + "/resources")["allocation"]
assert allocation["videoRecordingRequested"] and allocation["videoRecordingEnabled"]


def captured_recording():
    # No raw pixels leave the owned private runtime. Other setup Sessions do not request recording.
    for segment in runtime.rglob("segment-*.ndjson"):
        try:
            with segment.open("rb") as stream:
                lines = [stream.readline(), stream.readline()]
            if all(line.endswith(b"\n") for line in lines):
                frames = [json.loads(line) for line in lines]
                assert all(f["redactionPolicyVersion"] == 2 for f in frames)
                return segment.parent.name
        except (FileNotFoundError, json.JSONDecodeError):
            pass
    return None


recording = wait_for(captured_recording, bool, "RECORDING_SAFE_FRAMES_TIMEOUT")
assert re.fullmatch(r"rec_[a-f0-9]{32}", recording)
request(path + ":stop", "POST")
wait_for(lambda: request(path), lambda x: x["state"] == "HIBERNATED", "RECORDING_STOP_TIMEOUT")
listed = wait_for(
    lambda: request(path + "/recordings"),
    lambda x: any(i["recordingId"] == recording for i in x["items"]),
    "RECORDING_PROJECTION_TIMEOUT",
)
item = next(i for i in listed["items"] if i["recordingId"] == recording)
assert item["redactionPolicyVersion"] == 2 and item["frameCount"] >= 2
assert item["segmentCount"] >= 1 and item["manifestBytes"] > 0
assert "manifestObjectKey" not in item and "downloadUrl" not in item
# This branch never inserts recording metadata. The Inbox and projection must share the Node event.
event = sql(f"SELECT r.event_id FROM session_recordings r JOIN inbox_events i USING(event_id) "
            f"WHERE r.session_id='{session}' AND r.recording_id='{recording}' "
            "AND r.tenant_id='tenant-integration' AND r.redaction_policy_version=2")
assert re.fullmatch(r"evt_[A-Za-z0-9_-]+", event)
assert sql(f"SELECT count(*) FROM session_recordings WHERE recording_id='{recording}'") == "1"


def delivered():
    with sqlite3.connect(runtime / "node-journal.sqlite3", timeout=5) as db:
        return db.execute(
            "SELECT event_delivered FROM command_results WHERE event_id=?", (event,)
        ).fetchone()


wait_for(delivered, lambda x: x == (1,), "RECORDING_NODE_ACK_TIMEOUT")
try:
    request(path + "/recordings", tenant="tenant-other")
    raise AssertionError("RECORDING_CROSS_TENANT_ACCEPTED")
except urllib.error.HTTPError as error:
    assert error.code == 404
grant = request(path + f"/recordings/{recording}/playback-grants", "POST",
                {"purpose": "SECURITY_INVESTIGATION"})
access = request(path + f"/recording-playback-grants/{grant['grantId']}:redeem", "POST")
assert access["recordingId"] == recording and access["manifestSha256"] == item["manifestSha256"]
assert access["nextSegmentOffset"] is None and len(access["segments"]) == item["segmentCount"]
frames = 0
for segment in access["segments"]:
    url = urllib.parse.urlparse(segment["downloadUrl"])
    assert url.scheme == "http" and url.hostname == "127.0.0.1"
    with http.open(segment["downloadUrl"], timeout=10) as response:
        data = response.read()
    assert len(data) == segment["contentBytes"]
    assert hashlib.sha256(data).hexdigest() == segment["contentSha256"]
    lines = data.splitlines()
    assert len(lines) == segment["frameCount"]
    for line in lines:
        frame = json.loads(line)
        assert frame["redactionPolicyVersion"] == 2 and frame["privacyScanState"] == "VERIFIED"
        assert hashlib.sha256(base64.b64decode(frame["data"], validate=True)).hexdigest() == frame["sanitizedImageSha256"]
    frames += len(lines)
assert frames == item["frameCount"]
print("recording_node_v2_mtls_inbox_projection=true")
print("recording_node_v2_purpose_bound_playback=true")
print(f"recording_node_v2_frames={frames} segments={item['segmentCount']}")
