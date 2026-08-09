#!/usr/bin/env python3
"""Least-privilege leased worker for fixed-catalog Recovery GameDays."""

from __future__ import annotations

import argparse
import json
import os
import pathlib
import queue
import re
import signal
import ssl
import stat
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request


MAX_RESPONSE_BYTES = 1024 * 1024
MAX_RUNNER_OUTPUT_BYTES = 1024 * 1024
IDENTIFIER = re.compile(r"^[A-Za-z0-9_-]{1,128}$")


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise urllib.error.HTTPError(newurl, code, "redirects are disabled", headers, fp)


class ApiError(RuntimeError):
    def __init__(self, status: int, payload: object):
        super().__init__(f"Control Plane returned HTTP_{status}")
        self.status = status
        self.payload = payload


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--control-plane-url", required=True)
    parser.add_argument("--control-plane-token-file", required=True)
    parser.add_argument("--control-plane-ca-file")
    parser.add_argument("--controller-token-file", required=True)
    parser.add_argument("--controller-ca-file")
    parser.add_argument("--catalog-file", required=True)
    parser.add_argument("--runner", required=True)
    parser.add_argument("--worker-id", required=True)
    parser.add_argument("--poll-seconds", type=float, default=5)
    parser.add_argument("--heartbeat-seconds", type=float, default=15)
    parser.add_argument("--environment", default="production")
    parser.add_argument("--once", action="store_true")
    return parser.parse_args()


def private_secret(path_value: str) -> pathlib.Path:
    path = pathlib.Path(path_value)
    if not path.is_absolute() or path.is_symlink() or not path.is_file():
        raise ValueError("secret path must be an absolute regular non-symlink file")
    info = path.stat()
    mode = stat.S_IMODE(info.st_mode)
    private_owner = mode == 0o600 and info.st_uid == os.geteuid()
    private_group = (
        mode == 0o440
        and info.st_gid == os.getegid()
        and not mode & (stat.S_IWGRP | stat.S_IRWXO)
    )
    if not (private_owner or private_group):
        raise ValueError("secret file must be owner 0600 or dedicated process-group 0440")
    return path


def read_secret(path_value: str) -> str:
    path = private_secret(path_value)
    with path.open("rb") as handle:
        payload = handle.read(8193)
    if len(payload) > 8192:
        raise ValueError("secret exceeds 8192 bytes")
    value = payload.decode("utf-8").strip()
    if not value or "\n" in value or "\r" in value:
        raise ValueError("secret must be a non-empty single line")
    return value


def read_catalog(path_value: str) -> tuple[list[str], list[str]]:
    path = pathlib.Path(path_value)
    if not path.is_absolute() or path.is_symlink() or not path.is_file():
        raise ValueError("catalog must be an absolute regular non-symlink file")
    with path.open("rb") as handle:
        payload = handle.read(1024 * 1024 + 1)
    if len(payload) > 1024 * 1024:
        raise ValueError("catalog exceeds the size limit")
    catalog = json.loads(payload)
    scenarios = catalog.get("scenarios") if catalog.get("version") == 1 else None
    if not isinstance(scenarios, dict):
        raise ValueError("catalog must use version 1 and contain scenarios")
    codes = sorted(scenarios)
    environments = sorted(
        {
            entry.get("environment")
            for entry in scenarios.values()
            if isinstance(entry, dict)
            and entry.get("environment") in {"TEST", "STAGING", "PRODUCTION"}
        }
    )
    if any(not re.fullmatch(r"[A-Z][A-Z0-9_]{2,63}", code) for code in codes):
        raise ValueError("catalog contains an invalid scenario code")
    return codes, environments


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
        raise ValueError("non-local GameDay Worker requires HTTPS")
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
    ):
        context = ssl.create_default_context(cafile=ca_file) if ca_file else ssl.create_default_context()
        self.http = urllib.request.build_opener(
            urllib.request.ProxyHandler({}),
            NoRedirect(),
            urllib.request.HTTPSHandler(context=context),
        )
        self.origin = origin
        self.token = token
        self.environment = environment
        self.worker_id = worker_id

    def request(self, method: str, path: str, body: dict | None = None) -> tuple[int, object]:
        data = None if body is None else json.dumps(body, separators=(",", ":")).encode("utf-8")
        headers = {
            "Accept": "application/json",
            "Content-Type": "application/json",
            "Authorization": f"Bearer {self.token}",
            "User-Agent": "agent-browser-cloud-gameday-worker/1",
        }
        if self.environment in {"local", "test"}:
            headers.update(
                {
                    "X-Tenant-Id": "platform-control",
                    "X-Actor-Id": self.worker_id,
                    "X-Roles": "GAMEDAY_WORKER",
                }
            )
        call = urllib.request.Request(
            self.origin + path, data=data, headers=headers, method=method
        )
        try:
            response = self.http.open(call, timeout=20)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            payload = response.read(MAX_RESPONSE_BYTES + 1)
            if len(payload) > MAX_RESPONSE_BYTES:
                raise RuntimeError("Control Plane response exceeded the size limit")
            parsed = json.loads(payload) if payload else None
            if response.status >= 400:
                raise ApiError(response.status, parsed)
            return response.status, parsed


def require_absolute_executable(path_value: str) -> pathlib.Path:
    path = pathlib.Path(path_value)
    if not path.is_absolute() or path.is_symlink() or not path.is_file() or not os.access(path, os.X_OK):
        raise ValueError("runner must be a fixed absolute executable file")
    return path


def terminate_group(process: subprocess.Popen, grace_seconds: float = 10) -> None:
    if process.poll() is not None:
        return
    os.killpg(process.pid, signal.SIGTERM)
    try:
        process.wait(timeout=grace_seconds)
    except subprocess.TimeoutExpired:
        os.killpg(process.pid, signal.SIGKILL)
        process.wait(timeout=5)


def stage_request(client: ControlPlaneClient, game_day_id: str, token: str, stage: str) -> None:
    client.request(
        "POST",
        f"/api/v1/enterprise/recovery-gameday-jobs/{game_day_id}:stage",
        {"claimToken": token, "stage": stage},
    )


def execute_claim(
    client: ControlPlaneClient,
    claim: dict,
    args: argparse.Namespace,
    runner: pathlib.Path,
) -> None:
    game_day = claim["gameDay"]
    job = game_day["job"]
    game_day_id = game_day["gameDayId"]
    claim_token = claim["claimToken"]
    recovery_only = bool(claim.get("recoveryOnly"))
    client.request(
        "POST",
        f"/api/v1/enterprise/recovery-gameday-jobs/{game_day_id}:start",
        {"claimToken": claim_token},
    )
    command = [
        str(runner),
        "--catalog-file",
        str(pathlib.Path(args.catalog_file).resolve()),
        "--controller-token-file",
        str(pathlib.Path(args.controller_token_file).resolve()),
        "--game-day-id",
        game_day_id,
        "--scenario-code",
        job["scenarioCode"],
        "--environment",
        game_day["environment"],
        "--source-region",
        game_day["sourceRegion"],
        "--target-region",
        game_day["targetRegion"],
        "--maximum-duration-seconds",
        str(game_day["maximumDurationSeconds"]),
    ]
    if args.controller_ca_file:
        command.extend(["--ca-file", str(pathlib.Path(args.controller_ca_file).resolve())])
    if recovery_only:
        command.append("--recovery-only")
    child_environment = {
        "PATH": "/usr/local/bin:/usr/bin:/bin",
        "LANG": "C.UTF-8",
        "LC_ALL": "C.UTF-8",
        "PYTHONUNBUFFERED": "1",
    }
    process = subprocess.Popen(
        command,
        stdin=subprocess.DEVNULL,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        encoding="utf-8",
        errors="replace",
        env=child_environment,
        start_new_session=True,
        bufsize=1,
    )
    failures: queue.Queue[BaseException] = queue.Queue()
    result_box: list[dict] = []

    def stdout_reader() -> None:
        total = 0
        try:
            assert process.stdout is not None
            for line in process.stdout:
                total += len(line.encode("utf-8", errors="replace"))
                if total > MAX_RUNNER_OUTPUT_BYTES:
                    raise RuntimeError("runner stdout exceeded the size limit")
                event = json.loads(line)
                if event.get("type") == "stage":
                    stage_request(client, game_day_id, claim_token, event["stage"])
                elif event.get("type") == "result" and isinstance(event.get("result"), dict):
                    if result_box:
                        raise RuntimeError("runner emitted more than one result")
                    result_box.append(event["result"])
                else:
                    raise RuntimeError("runner emitted an unauthorized event")
        except BaseException as error:
            failures.put(error)

    def stderr_reader() -> None:
        total = 0
        try:
            assert process.stderr is not None
            while chunk := process.stderr.read(8192):
                total += len(chunk.encode("utf-8", errors="replace"))
                if total > MAX_RUNNER_OUTPUT_BYTES:
                    raise RuntimeError("runner stderr exceeded the size limit")
        except BaseException as error:
            failures.put(error)

    stdout_thread = threading.Thread(target=stdout_reader, daemon=True)
    stderr_thread = threading.Thread(target=stderr_reader, daemon=True)
    stdout_thread.start()
    stderr_thread.start()
    next_heartbeat = time.monotonic()
    hard_deadline = time.monotonic() + game_day["maximumDurationSeconds"] + 60
    abort_signalled = False
    try:
        while process.poll() is None:
            if not failures.empty():
                raise failures.get_nowait()
            now = time.monotonic()
            if now >= hard_deadline:
                raise TimeoutError("runner exceeded the execution and recovery deadline")
            if now >= next_heartbeat:
                _, heartbeat = client.request(
                    "POST",
                    f"/api/v1/enterprise/recovery-gameday-jobs/{game_day_id}:heartbeat",
                    {"claimToken": claim_token},
                )
                next_heartbeat = now + args.heartbeat_seconds
                if heartbeat.get("abortRequested") and not abort_signalled:
                    os.killpg(process.pid, signal.SIGTERM)
                    abort_signalled = True
            time.sleep(0.1)
        stdout_thread.join(timeout=5)
        stderr_thread.join(timeout=5)
        if stdout_thread.is_alive() or stderr_thread.is_alive():
            raise RuntimeError("runner output drain did not terminate")
        if not failures.empty():
            raise failures.get_nowait()
        if process.returncode != 0 or len(result_box) != 1:
            raise RuntimeError(f"runner failed with exit code {process.returncode}")
        result = result_box[0]
        recovery_confirmed = bool(result.get("recoveryConfirmed"))
        failure_code = result.get("failureCode")
        if recovery_only or failure_code or result.get("aborted"):
            client.request(
                "POST",
                f"/api/v1/enterprise/recovery-gameday-jobs/{game_day_id}:fail",
                {
                    "claimToken": claim_token,
                    "failureCode": failure_code or "GAMEDAY_RECOVERY_ONLY_COMPLETED",
                    "retryable": bool(
                        not recovery_only
                        and not result.get("faultInjected")
                        and not result.get("aborted")
                    ),
                    "recoveryConfirmed": recovery_confirmed,
                },
            )
            return
        metrics = result["metrics"]
        client.request(
            "POST",
            f"/api/v1/enterprise/recovery-gameday-jobs/{game_day_id}:complete",
            {
                "claimToken": claim_token,
                "result": {
                    "observedRtoSeconds": result["observedRtoSeconds"],
                    "observedRpoSeconds": result["observedRpoSeconds"],
                    "dataLossRecords": metrics["dataLossRecords"],
                    "detectionTimeSeconds": result["detectionTimeSeconds"],
                    "failoverTimeSeconds": result["observedRtoSeconds"],
                    "staleOperationCount": metrics["staleOperationCount"],
                    "userImpactCount": metrics["userImpactCount"],
                    "manualSteps": metrics["manualSteps"],
                    "runbookAccuracyPercent": metrics["runbookAccuracyPercent"],
                    "runnerEvidenceHash": result["runnerEvidenceHash"],
                    "recoveryConfirmed": recovery_confirmed,
                },
            },
        )
    except BaseException:
        terminate_group(process)
        try:
            client.request(
                "POST",
                f"/api/v1/enterprise/recovery-gameday-jobs/{game_day_id}:fail",
                {
                    "claimToken": claim_token,
                    "failureCode": "GAMEDAY_WORKER_EXECUTION_FAILED",
                    "retryable": True,
                    "recoveryConfirmed": False,
                },
            )
        except Exception:
            pass
        raise
    finally:
        if process.stdout:
            process.stdout.close()
        if process.stderr:
            process.stderr.close()


def main() -> int:
    args = parse_args()
    if not IDENTIFIER.fullmatch(args.worker_id):
        raise ValueError("worker id is invalid")
    if not 1 <= args.poll_seconds <= 60 or not 5 <= args.heartbeat_seconds <= 30:
        raise ValueError("poll or heartbeat interval is outside the allowed range")
    origin = control_plane_origin(args.control_plane_url, args.environment)
    token = read_secret(args.control_plane_token_file)
    private_secret(args.controller_token_file)
    runner = require_absolute_executable(args.runner)
    scenario_codes, environments = read_catalog(args.catalog_file)
    client = ControlPlaneClient(
        origin, token, args.control_plane_ca_file, args.environment, args.worker_id
    )
    if not scenario_codes or not environments:
        if args.once:
            return 0
        while True:
            time.sleep(args.poll_seconds)
    while True:
        status, claim = client.request(
            "POST",
            "/api/v1/enterprise/recovery-gameday-jobs:claim",
            {
                "environments": environments,
                "scenarioCodes": scenario_codes,
                "capabilities": {
                    "faultInjection": True,
                    "recovery": True,
                    "measurement": True,
                },
            },
        )
        if status == 204:
            if args.once:
                return 0
            time.sleep(args.poll_seconds)
            continue
        execute_claim(client, claim, args, runner)
        if args.once:
            return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except KeyboardInterrupt:
        raise SystemExit(130)
