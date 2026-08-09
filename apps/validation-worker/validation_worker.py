#!/usr/bin/env python3
"""Least-privilege Runtime Validation Worker with leased, fenced result delivery.

The Control Plane selects jobs by immutable browser/OS/capability requirements. The worker
executes only the operator-configured runner command, passes the job as JSON on stdin, heartbeats
the lease while it runs, and submits a bounded JSON result. Jobs can never provide shell text.
"""

from __future__ import annotations

import argparse
import dataclasses
import json
import os
import pathlib
import platform
import re
import signal
import ssl
import stat
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from typing import Any


MAX_SECRET_BYTES = 16 * 1024
MAX_RESPONSE_BYTES = 1024 * 1024
MAX_RUNNER_OUTPUT_BYTES = 1024 * 1024
VALIDATION_ID = re.compile(r"^val_[A-Za-z0-9]{20}$")


class WorkerError(RuntimeError):
    def __init__(self, code: str, *, retryable: bool = True):
        super().__init__(code)
        self.code = code
        self.retryable = retryable


class NoRedirectHandler(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *_args: Any, **_kwargs: Any) -> None:
        return None


def read_secret_file(path: str | pathlib.Path) -> str:
    secret_path = pathlib.Path(path)
    try:
        metadata = secret_path.stat()
    except OSError as error:
        raise WorkerError("SECRET_FILE_UNAVAILABLE", retryable=False) from error
    if not stat.S_ISREG(metadata.st_mode):
        raise WorkerError("SECRET_FILE_NOT_REGULAR", retryable=False)
    if metadata.st_size <= 0 or metadata.st_size > MAX_SECRET_BYTES:
        raise WorkerError("SECRET_FILE_SIZE_INVALID", retryable=False)
    if os.name != "nt":
        permissions = stat.S_IMODE(metadata.st_mode)
        group_readable_by_worker = (
            permissions & 0o070 == 0o040
            and metadata.st_gid in {os.getgid(), *os.getgroups()}
        )
        if permissions & 0o007 or permissions & 0o030 or (
            permissions & 0o040 and not group_readable_by_worker
        ):
            raise WorkerError("SECRET_FILE_PERMISSIONS_TOO_BROAD", retryable=False)
    try:
        value = secret_path.read_text(encoding="utf-8").strip()
    except (OSError, UnicodeError) as error:
        raise WorkerError("SECRET_FILE_UNREADABLE", retryable=False) from error
    if not value or "\x00" in value or "\n" in value or "\r" in value:
        raise WorkerError("SECRET_VALUE_INVALID", retryable=False)
    return value


def canonical_json(value: Any) -> bytes:
    return json.dumps(
        value, ensure_ascii=False, allow_nan=False, sort_keys=True, separators=(",", ":")
    ).encode("utf-8")


class HttpTransport:
    def __init__(
        self,
        base_url: str,
        *,
        timeout_seconds: float = 10.0,
        ca_file: str | None = None,
        allow_http: bool = False,
    ):
        parsed = urllib.parse.urlsplit(base_url)
        scheme_allowed = parsed.scheme == "https" or (allow_http and parsed.scheme == "http")
        if (
            not scheme_allowed
            or not parsed.hostname
            or parsed.username is not None
            or parsed.password is not None
            or parsed.query
            or parsed.fragment
        ):
            raise WorkerError("CONTROL_PLANE_URL_REJECTED", retryable=False)
        self.base_url = base_url.rstrip("/")
        self.host = parsed.hostname.lower().rstrip(".")
        self.timeout_seconds = min(max(timeout_seconds, 0.25), 30.0)
        context = ssl.create_default_context(cafile=ca_file)
        self.opener = urllib.request.build_opener(
            urllib.request.ProxyHandler({}),
            NoRedirectHandler(),
            urllib.request.HTTPSHandler(context=context),
        )

    def request(
        self,
        path: str,
        *,
        method: str,
        headers: dict[str, str],
        payload: dict[str, Any],
    ) -> dict[str, Any] | None:
        url = f"{self.base_url}{path}"
        parsed = urllib.parse.urlsplit(url)
        if (parsed.hostname or "").lower().rstrip(".") != self.host:
            raise WorkerError("CONTROL_PLANE_URL_REJECTED", retryable=False)
        body = canonical_json(payload)
        request = urllib.request.Request(
            url,
            data=body,
            headers={"Accept": "application/json", "Content-Type": "application/json", **headers},
            method=method,
        )
        try:
            with self.opener.open(request, timeout=self.timeout_seconds) as response:
                if response.status == 204:
                    return None
                raw = response.read(MAX_RESPONSE_BYTES + 1)
                content_type = response.headers.get_content_type().lower()
                status = response.status
        except urllib.error.HTTPError as error:
            raw = error.read(MAX_RESPONSE_BYTES + 1)
            reason = f"CONTROL_PLANE_HTTP_{error.code}"
            try:
                document = json.loads(raw)
                reason = str(document.get("details", {}).get("reason") or document.get("code") or reason)
            except (UnicodeError, json.JSONDecodeError, AttributeError):
                pass
            raise WorkerError(reason, retryable=error.code >= 500) from error
        except (urllib.error.URLError, TimeoutError, OSError) as error:
            raise WorkerError("CONTROL_PLANE_UNAVAILABLE") from error
        if status != 200 or len(raw) > MAX_RESPONSE_BYTES:
            raise WorkerError("CONTROL_PLANE_RESPONSE_INVALID")
        if content_type != "application/json" and not content_type.endswith("+json"):
            raise WorkerError("CONTROL_PLANE_CONTENT_TYPE_INVALID")
        try:
            document = json.loads(raw)
        except (UnicodeError, json.JSONDecodeError) as error:
            raise WorkerError("CONTROL_PLANE_JSON_INVALID") from error
        if not isinstance(document, dict):
            raise WorkerError("CONTROL_PLANE_JSON_ROOT_INVALID")
        return document


@dataclasses.dataclass(frozen=True)
class WorkerCapabilities:
    browser_engine: str
    browser_versions: tuple[str, ...]
    operating_system: str
    architecture: str
    capabilities: dict[str, bool]

    def payload(self) -> dict[str, Any]:
        return {
            "browserEngine": self.browser_engine,
            "browserVersions": list(self.browser_versions),
            "operatingSystem": self.operating_system,
            "architecture": self.architecture,
            "capabilities": self.capabilities,
        }


@dataclasses.dataclass(frozen=True)
class ClaimedJob:
    validation_id: str
    claim_token: str
    claim_epoch: int
    validation: dict[str, Any]


class ControlPlaneClient:
    def __init__(
        self,
        transport: HttpTransport,
        bearer_token: str,
        capabilities: WorkerCapabilities,
        local_identity: tuple[str, str] | None = None,
    ):
        self.transport = transport
        self.bearer_token = bearer_token
        self.capabilities = capabilities
        self.local_identity = local_identity

    def _headers(self) -> dict[str, str]:
        headers = {"Authorization": f"Bearer {self.bearer_token}"}
        if self.local_identity is not None:
            headers.update(
                {
                    "X-Tenant-Id": self.local_identity[0],
                    "X-Actor-Id": self.local_identity[1],
                    "X-Roles": "VALIDATION_WORKER",
                }
            )
        return headers

    def _post(self, path: str, payload: dict[str, Any]) -> dict[str, Any] | None:
        return self.transport.request(path, method="POST", headers=self._headers(), payload=payload)

    def claim(self) -> ClaimedJob | None:
        document = self._post(
            "/api/v1/enterprise/runtime-validation-jobs:claim", self.capabilities.payload()
        )
        if document is None:
            return None
        validation = document.get("validation")
        validation_id = str(validation.get("validationId", "")) if isinstance(validation, dict) else ""
        claim_token = str(document.get("claimToken", ""))
        claim_epoch = document.get("claimEpoch")
        if (
            not VALIDATION_ID.fullmatch(validation_id)
            or len(claim_token) != 43
            or not isinstance(claim_epoch, int)
        ):
            raise WorkerError("VALIDATION_CLAIM_INVALID", retryable=False)
        return ClaimedJob(validation_id, claim_token, claim_epoch, validation)

    def start(self, job: ClaimedJob) -> None:
        self._post(
            f"/api/v1/enterprise/runtime-validation-jobs/{job.validation_id}:start",
            {"claimToken": job.claim_token},
        )

    def heartbeat(self, job: ClaimedJob) -> None:
        self._post(
            f"/api/v1/enterprise/runtime-validation-jobs/{job.validation_id}:heartbeat",
            {"claimToken": job.claim_token},
        )

    def complete(self, job: ClaimedJob, result: dict[str, Any]) -> dict[str, Any]:
        document = self._post(
            f"/api/v1/enterprise/runtime-validation-jobs/{job.validation_id}:complete",
            {"claimToken": job.claim_token, "result": result},
        )
        if document is None:
            raise WorkerError("VALIDATION_COMPLETE_EMPTY")
        return document

    def fail(self, job: ClaimedJob, error: WorkerError) -> dict[str, Any]:
        document = self._post(
            f"/api/v1/enterprise/runtime-validation-jobs/{job.validation_id}:fail",
            {
                "claimToken": job.claim_token,
                "failureCode": error.code,
                "retryable": error.retryable,
            },
        )
        if document is None:
            raise WorkerError("VALIDATION_FAILURE_ACK_EMPTY")
        return document


class FixedCommandRunner:
    def __init__(self, command: tuple[str, ...], timeout_seconds: int):
        if not command or not pathlib.Path(command[0]).is_absolute():
            raise WorkerError("RUNNER_ABSOLUTE_PATH_REQUIRED", retryable=False)
        self.command = command
        self.timeout_seconds = min(max(timeout_seconds, 1), 86400)

    def run(
        self,
        job: ClaimedJob,
        heartbeat_failed: threading.Event,
    ) -> dict[str, Any]:
        environment = {
            "PATH": "/usr/local/bin:/usr/bin:/bin",
            "LANG": "C.UTF-8",
            "VALIDATION_ID": job.validation_id,
            "VALIDATION_CLAIM_EPOCH": str(job.claim_epoch),
        }
        with tempfile.TemporaryFile() as input_file:
            input_file.write(canonical_json(job.validation))
            input_file.seek(0)
            try:
                process = subprocess.Popen(
                    self.command,
                    stdin=input_file,
                    stdout=subprocess.PIPE,
                    stderr=subprocess.PIPE,
                    env=environment,
                    start_new_session=os.name == "posix",
                )
            except OSError as error:
                raise WorkerError("RUNNER_START_FAILED") from error
            stdout_buffer = bytearray()
            stderr_buffer = bytearray()
            output_too_large = threading.Event()

            def drain(stream: Any, output: bytearray) -> None:
                while True:
                    chunk = stream.read(64 * 1024)
                    if not chunk:
                        return
                    remaining = MAX_RUNNER_OUTPUT_BYTES + 1 - len(output)
                    if remaining > 0:
                        output.extend(chunk[:remaining])
                    if len(chunk) > remaining or len(output) > MAX_RUNNER_OUTPUT_BYTES:
                        output_too_large.set()
                        _kill_process(process)

            readers = (
                threading.Thread(target=drain, args=(process.stdout, stdout_buffer), daemon=True),
                threading.Thread(target=drain, args=(process.stderr, stderr_buffer), daemon=True),
            )
            for reader in readers:
                reader.start()
            deadline = time.monotonic() + self.timeout_seconds
            failure: WorkerError | None = None
            while process.poll() is None:
                if heartbeat_failed.is_set():
                    failure = WorkerError("VALIDATION_LEASE_LOST", retryable=True)
                    _kill_process(process)
                    break
                if output_too_large.is_set():
                    failure = WorkerError("RUNNER_OUTPUT_TOO_LARGE", retryable=False)
                    _kill_process(process)
                    break
                if time.monotonic() >= deadline:
                    failure = WorkerError("RUNNER_TIMEOUT")
                    _kill_process(process)
                    break
                time.sleep(0.05)
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                _kill_process(process)
                process.wait(timeout=5)
            for reader in readers:
                reader.join(timeout=5)
            process.stdout.close()
            process.stderr.close()
            if any(reader.is_alive() for reader in readers):
                raise WorkerError("RUNNER_OUTPUT_READ_FAILED")
            stdout = bytes(stdout_buffer)
            stderr = bytes(stderr_buffer)
        if failure is not None:
            raise failure
        if output_too_large.is_set():
            raise WorkerError("RUNNER_OUTPUT_TOO_LARGE", retryable=False)
        if process.returncode != 0:
            raise WorkerError("RUNNER_EXIT_NONZERO")
        try:
            result = json.loads(stdout)
        except (UnicodeError, json.JSONDecodeError) as error:
            raise WorkerError("RUNNER_RESULT_INVALID", retryable=False) from error
        validate_result(result)
        return result


def _kill_process(process: subprocess.Popen[bytes]) -> None:
    if process.poll() is not None:
        return
    try:
        if os.name == "posix":
            os.killpg(process.pid, signal.SIGKILL)
        else:
            process.kill()
    except (OSError, ProcessLookupError):
        return


def validate_result(result: Any) -> None:
    if not isinstance(result, dict):
        raise WorkerError("RUNNER_RESULT_INVALID", retryable=False)
    integers = ("requiredTests", "requiredFailures", "optionalTests", "optionalFailures")
    if any(not isinstance(result.get(key), int) or result[key] < 0 for key in integers):
        raise WorkerError("RUNNER_RESULT_COUNTS_INVALID", retryable=False)
    if result["requiredTests"] < 1:
        raise WorkerError("RUNNER_RESULT_COUNTS_INVALID", retryable=False)
    if result["requiredFailures"] > result["requiredTests"]:
        raise WorkerError("RUNNER_RESULT_COUNTS_INVALID", retryable=False)
    if result["optionalFailures"] > result["optionalTests"]:
        raise WorkerError("RUNNER_RESULT_COUNTS_INVALID", retryable=False)
    for key in ("declaredCapabilities", "observedCapabilities"):
        value = result.get(key)
        if not isinstance(value, dict) or any(
            not isinstance(name, str) or not isinstance(enabled, bool)
            for name, enabled in value.items()
        ):
            raise WorkerError("RUNNER_RESULT_CAPABILITIES_INVALID", retryable=False)
    failures = result.get("optionalFailureCodes")
    if not isinstance(failures, list) or len(failures) > 256 or any(
        not isinstance(code, str) or len(code) > 128 for code in failures
    ):
        raise WorkerError("RUNNER_RESULT_FAILURE_CODES_INVALID", retryable=False)
    if not isinstance(result.get("personaConsistent"), bool):
        raise WorkerError("RUNNER_RESULT_PERSONA_INVALID", retryable=False)


class WorkerLoop:
    def __init__(
        self,
        client: ControlPlaneClient,
        runner: FixedCommandRunner,
        heartbeat_seconds: float,
        idle_seconds: float,
    ):
        self.client = client
        self.runner = runner
        self.heartbeat_seconds = min(max(heartbeat_seconds, 1.0), 60.0)
        self.idle_seconds = min(max(idle_seconds, 0.1), 60.0)

    def run_once(self) -> bool:
        job = self.client.claim()
        if job is None:
            return False
        self.client.start(job)
        stop = threading.Event()
        heartbeat_failed = threading.Event()

        def heartbeat() -> None:
            while not stop.wait(self.heartbeat_seconds):
                try:
                    self.client.heartbeat(job)
                except WorkerError:
                    heartbeat_failed.set()
                    return

        thread = threading.Thread(target=heartbeat, name="validation-heartbeat", daemon=True)
        thread.start()
        try:
            result = self.runner.run(job, heartbeat_failed)
            if heartbeat_failed.is_set():
                raise WorkerError("VALIDATION_LEASE_LOST")
            self.client.complete(job, result)
        except WorkerError as error:
            if not heartbeat_failed.is_set():
                self.client.fail(job, error)
            else:
                raise
        finally:
            stop.set()
            thread.join(timeout=self.heartbeat_seconds + 1)
        return True

    def serve(self) -> None:
        while True:
            try:
                if not self.run_once():
                    time.sleep(self.idle_seconds)
            except WorkerError as error:
                print(json.dumps({"event": "validation-worker-error", "code": error.code}), file=sys.stderr)
                time.sleep(self.idle_seconds)


def _platform_os() -> str:
    value = platform.system().lower()
    return {"darwin": "macos", "windows": "windows"}.get(value, value)


def _architecture() -> str:
    value = platform.machine().lower()
    return {"x86_64": "amd64", "aarch64": "arm64"}.get(value, value)


def _capabilities(values: list[str]) -> dict[str, bool]:
    result: dict[str, bool] = {}
    for value in values:
        name, separator, enabled = value.partition("=")
        if not separator or not re.fullmatch(r"[A-Za-z][A-Za-z0-9_.-]{0,127}", name):
            raise WorkerError("WORKER_CAPABILITY_INVALID", retryable=False)
        if enabled.lower() not in {"true", "false"}:
            raise WorkerError("WORKER_CAPABILITY_INVALID", retryable=False)
        result[name] = enabled.lower() == "true"
    return result


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("run-once", "serve"))
    parser.add_argument("--control-plane-url", required=True)
    parser.add_argument("--control-plane-token-file", required=True)
    parser.add_argument("--control-plane-ca-file")
    parser.add_argument("--worker-id", required=True)
    parser.add_argument("--browser-engine", default="chromium")
    parser.add_argument("--browser-version", action="append", required=True)
    parser.add_argument("--operating-system", default=_platform_os())
    parser.add_argument("--architecture", default=_architecture())
    parser.add_argument("--capability", action="append", default=[])
    parser.add_argument("--runner", required=True)
    parser.add_argument("--runner-arg", action="append", default=[])
    parser.add_argument("--runner-timeout-seconds", type=int, default=1800)
    parser.add_argument("--heartbeat-seconds", type=float, default=20)
    parser.add_argument("--idle-seconds", type=float, default=5)
    parser.add_argument("--http-timeout-seconds", type=float, default=10)
    parser.add_argument("--allow-insecure-http", action="store_true")
    parser.add_argument("--local-tenant-id")
    return parser


def _allow_http(args: argparse.Namespace) -> bool:
    if not args.allow_insecure_http:
        return False
    if os.environ.get("APP_ENVIRONMENT", "").lower() not in {"local", "test"}:
        raise WorkerError("INSECURE_HTTP_FORBIDDEN", retryable=False)
    return True


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        if not re.fullmatch(r"[A-Za-z0-9_-]{1,128}", args.worker_id):
            raise WorkerError("WORKER_ID_INVALID", retryable=False)
        local_identity = None
        if args.local_tenant_id:
            if os.environ.get("APP_ENVIRONMENT", "").lower() not in {"local", "test"}:
                raise WorkerError("LOCAL_IDENTITY_FORBIDDEN", retryable=False)
            local_identity = (args.local_tenant_id, args.worker_id)
        capabilities = WorkerCapabilities(
            args.browser_engine,
            tuple(dict.fromkeys(args.browser_version)),
            args.operating_system,
            args.architecture,
            _capabilities(args.capability),
        )
        client = ControlPlaneClient(
            HttpTransport(
                args.control_plane_url,
                timeout_seconds=args.http_timeout_seconds,
                ca_file=args.control_plane_ca_file,
                allow_http=_allow_http(args),
            ),
            read_secret_file(args.control_plane_token_file),
            capabilities,
            local_identity,
        )
        worker = WorkerLoop(
            client,
            FixedCommandRunner((args.runner, *args.runner_arg), args.runner_timeout_seconds),
            args.heartbeat_seconds,
            args.idle_seconds,
        )
        if args.command == "run-once":
            return 0 if worker.run_once() else 3
        worker.serve()
        return 0
    except WorkerError as error:
        print(json.dumps({"error": error.code}), file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
