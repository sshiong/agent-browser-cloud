#!/usr/bin/env python3
"""Data-minimized Agent Worker for the fixed agent-worker/v1 drive protocol.

The worker never receives prompts, plans, page state, capability tokens, customer credentials, or
runner commands. It can only lease an opaque job and ask the Control Plane safety kernel to drive
the associated task. It deliberately has no subprocess or dynamic-code execution surface.
"""

from __future__ import annotations

import argparse
import json
import math
import os
import pathlib
import random
import re
import ssl
import stat
import threading
import time
import urllib.error
import urllib.parse
import urllib.request


MAX_RESPONSE_BYTES = 1024 * 1024
JOB_ID = re.compile(r"^ajob_[A-Za-z0-9]{20}$")
TASK_ID = re.compile(r"^agt_[A-Za-z0-9]{16,}$")
WORKER_ID = re.compile(r"^[A-Za-z0-9_-]{1,128}$")


class PollBackoff:
    """Bound empty/error claim traffic without delaying heartbeats or busy queues."""

    def __init__(self, base_seconds: float):
        if not math.isfinite(base_seconds):
            raise ValueError("poll seconds must be finite")
        self.base = min(max(base_seconds, 0.1), 60)
        self.ceiling = max(self.base, 30)
        self.window = self.base

    def reset(self) -> None:
        self.window = self.base

    def next_delay(self) -> float:
        # Equal jitter avoids synchronized claims and zero-delay busy loops.
        delay = random.uniform(self.window / 2, self.window)
        self.window = min(self.ceiling, self.window * 2)
        return delay


def run_poll_loop(run_once, once: bool, poll_seconds: float) -> None:
    backoff = PollBackoff(poll_seconds)
    while True:
        try:
            worked = run_once()
        except WorkerError:
            worked = False
        if once:
            return
        if worked:
            backoff.reset()
        else:
            time.sleep(backoff.next_delay())


class WorkerError(RuntimeError):
    def __init__(self, code: str, *, retryable: bool = True):
        super().__init__(code)
        self.code = code if re.fullmatch(r"[A-Z][A-Z0-9_]{2,127}", code) else "AGENT_WORKER_FAILED"
        self.retryable = retryable


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise urllib.error.HTTPError(newurl, code, "redirects are disabled", headers, fp)


def read_secret(path_value: str) -> str:
    path = pathlib.Path(path_value)
    if not path.is_absolute() or path.is_symlink() or not path.is_file():
        raise ValueError("secret path must be an absolute regular non-symlink file")
    info = path.stat()
    mode = stat.S_IMODE(info.st_mode)
    private_owner = mode == 0o600 and info.st_uid == os.geteuid()
    private_group = mode == 0o440 and info.st_gid == os.getegid()
    if not (private_owner or private_group) or mode & stat.S_IRWXO:
        raise ValueError("secret file must be owner 0600 or dedicated process-group 0440")
    with path.open("rb") as handle:
        payload = handle.read(8193)
    if not payload or len(payload) > 8192:
        raise ValueError("secret size is invalid")
    value = payload.decode("utf-8").strip()
    if not value or "\n" in value or "\r" in value or "\0" in value:
        raise ValueError("secret must be a non-empty single line")
    return value


def control_plane_origin(value: str, environment: str) -> str:
    parsed = urllib.parse.urlsplit(value)
    if (
        parsed.scheme not in {"http", "https"}
        or not parsed.hostname
        or parsed.username
        or parsed.password
        or parsed.query
        or parsed.fragment
        or parsed.path not in {"", "/"}
    ):
        raise ValueError("Control Plane URL must be a fixed HTTP(S) origin")
    local = environment in {"local", "test"}
    if not local and parsed.scheme != "https":
        raise ValueError("non-local Agent Worker requires HTTPS")
    if parsed.scheme == "http" and parsed.hostname not in {"127.0.0.1", "localhost", "::1"}:
        raise ValueError("HTTP Control Plane must be loopback-only")
    return value.rstrip("/")


class ControlPlaneClient:
    def __init__(
        self,
        origin: str,
        token: str,
        ca_file: str | None,
        environment: str,
        worker_id: str,
        timeout_seconds: float = 300,
    ):
        if not WORKER_ID.fullmatch(worker_id):
            raise ValueError("worker id is invalid")
        context = ssl.create_default_context(cafile=ca_file) if ca_file else ssl.create_default_context()
        self.http = urllib.request.build_opener(
            urllib.request.ProxyHandler({}), NoRedirect(), urllib.request.HTTPSHandler(context=context)
        )
        self.origin = origin
        self.token = token
        self.environment = environment
        self.worker_id = worker_id
        self.timeout_seconds = min(max(timeout_seconds, 1), 600)

    def request(self, path: str, body: dict) -> dict | None:
        payload = json.dumps(
            body, ensure_ascii=False, allow_nan=False, sort_keys=True, separators=(",", ":")
        ).encode("utf-8")
        headers = {
            "Accept": "application/json",
            "Content-Type": "application/json",
            "Authorization": f"Bearer {self.token}",
            "User-Agent": "agent-browser-cloud-agent-worker/1",
        }
        if self.environment in {"local", "test"}:
            headers.update(
                {
                    "X-Tenant-Id": "platform-control",
                    "X-Actor-Id": self.worker_id,
                    "X-Roles": "AGENT_WORKER",
                }
            )
        call = urllib.request.Request(self.origin + path, data=payload, headers=headers, method="POST")
        try:
            response = self.http.open(call, timeout=self.timeout_seconds)
        except urllib.error.HTTPError as error:
            response = error
        except (urllib.error.URLError, TimeoutError, OSError) as error:
            raise WorkerError("CONTROL_PLANE_UNAVAILABLE") from error
        with response:
            raw = response.read(MAX_RESPONSE_BYTES + 1)
            if len(raw) > MAX_RESPONSE_BYTES:
                raise WorkerError("CONTROL_PLANE_RESPONSE_TOO_LARGE", retryable=False)
            try:
                document = json.loads(raw) if raw else None
            except (UnicodeError, json.JSONDecodeError) as error:
                raise WorkerError("CONTROL_PLANE_RESPONSE_INVALID") from error
            if response.status >= 400:
                reason = None
                if isinstance(document, dict):
                    details = document.get("details")
                    reason = details.get("reason") if isinstance(details, dict) else document.get("code")
                raise WorkerError(str(reason or f"CONTROL_PLANE_HTTP_{response.status}"), retryable=response.status >= 500)
            if response.status == 204:
                return None
            if response.status != 200 or not isinstance(document, dict):
                raise WorkerError("CONTROL_PLANE_RESPONSE_INVALID")
            return document

    def claim(self) -> dict | None:
        claim = self.request(
            "/api/v1/agent-worker-jobs:claim",
            {"protocolVersion": "agent-worker/v1", "capabilities": {"task-drive-v1": True}},
        )
        if claim is None:
            return None
        job = claim.get("job")
        token = claim.get("claimToken")
        if (
            not isinstance(job, dict)
            or not JOB_ID.fullmatch(str(job.get("jobId", "")))
            or not TASK_ID.fullmatch(str(job.get("taskId", "")))
            or job.get("protocolVersion") != "agent-worker/v1"
            or job.get("state") != "CLAIMED"
            or not isinstance(token, str)
            or len(token) != 43
        ):
            raise WorkerError("AGENT_EXECUTION_CLAIM_INVALID", retryable=False)
        return claim

    def transition(self, claim: dict, action: str, extra: dict | None = None) -> dict:
        job_id = claim["job"]["jobId"]
        body = {"claimToken": claim["claimToken"], **(extra or {})}
        response = self.request(f"/api/v1/agent-worker-jobs/{job_id}:{action}", body)
        if not isinstance(response, dict) or response.get("jobId") != job_id:
            raise WorkerError("AGENT_EXECUTION_TRANSITION_INVALID")
        return response


class WorkerLoop:
    def __init__(self, client: ControlPlaneClient, poll_seconds: float, heartbeat_seconds: float):
        self.client = client
        self.poll_seconds = min(max(poll_seconds, 0.1), 60)
        self.heartbeat_seconds = min(max(heartbeat_seconds, 1), 25)

    def run_once(self) -> bool:
        claim = self.client.claim()
        if claim is None:
            return False
        started = False
        stop = threading.Event()
        lease_lost = threading.Event()
        thread = None
        try:
            self.client.transition(claim, "start")
            started = True

            def heartbeat() -> None:
                while not stop.wait(self.heartbeat_seconds):
                    try:
                        self.client.transition(claim, "heartbeat")
                    except WorkerError:
                        lease_lost.set()
                        return

            thread = threading.Thread(target=heartbeat, daemon=True)
            thread.start()
            result = self.client.transition(claim, "drive")
            stop.set()
            thread.join(timeout=self.heartbeat_seconds + 1)
            if lease_lost.is_set():
                raise WorkerError("AGENT_EXECUTION_LEASE_LOST")
            if result.get("state") not in {"WAITING", "COMMITTED", "FAILED"}:
                raise WorkerError("AGENT_EXECUTION_DRIVE_STATE_INVALID", retryable=False)
            return True
        except WorkerError as error:
            stop.set()
            if started and error.code not in {
                "AGENT_EXECUTION_JOB_CLAIM_TOKEN_INVALID",
                "AGENT_EXECUTION_JOB_LEASE_EXPIRED",
                "AGENT_EXECUTION_LEASE_LOST",
            }:
                try:
                    self.client.transition(
                        claim,
                        "fail",
                        {"failureCode": error.code, "retryable": error.retryable},
                    )
                except WorkerError:
                    pass
            raise
        finally:
            stop.set()
            if thread is not None:
                thread.join(timeout=self.heartbeat_seconds + 1)

    def run(self, once: bool) -> None:
        run_poll_loop(self.run_once, once, self.poll_seconds)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--control-plane-url", required=True)
    parser.add_argument("--control-plane-token-file", required=True)
    parser.add_argument("--control-plane-ca-file")
    parser.add_argument("--worker-id", required=True)
    parser.add_argument("--poll-seconds", type=float, default=2)
    parser.add_argument("--heartbeat-seconds", type=float, default=15)
    parser.add_argument("--environment", choices=("production", "local", "test"), default="production")
    parser.add_argument("--once", action="store_true")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    origin = control_plane_origin(args.control_plane_url, args.environment)
    token = read_secret(args.control_plane_token_file)
    client = ControlPlaneClient(
        origin,
        token,
        args.control_plane_ca_file,
        args.environment,
        args.worker_id,
    )
    WorkerLoop(client, args.poll_seconds, args.heartbeat_seconds).run(args.once)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
