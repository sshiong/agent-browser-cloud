#!/usr/bin/env python3
"""Fixed-suite Chromium runner for the isolated Runtime Validation Worker."""

from __future__ import annotations

import argparse
import json
import os
import pathlib
import re
import signal
import subprocess
import sys
import tempfile
import threading
import time
import urllib.parse
from typing import Any


MAX_CATALOG_BYTES = 1024 * 1024
MAX_DOM_BYTES = 4 * 1024 * 1024
MAX_BROWSER_STDERR_BYTES = 1024 * 1024
MAX_REPLAY_CASES = 1000


class RunnerError(RuntimeError):
    pass


def load_catalog(path: pathlib.Path) -> dict[str, Any]:
    if not path.is_file() or path.stat().st_size <= 0 or path.stat().st_size > MAX_CATALOG_BYTES:
        raise RunnerError("SUITE_CATALOG_INVALID")
    document = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(document, dict) or not isinstance(document.get("datasets"), dict):
        raise RunnerError("SUITE_CATALOG_INVALID")
    return document


def run_bounded(
    command: list[str], timeout_seconds: int, max_stdout_bytes: int
) -> tuple[int, bytes]:
    try:
        process = subprocess.Popen(
            command,
            stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            start_new_session=sys.platform != "win32",
        )
    except OSError as error:
        raise RunnerError("BROWSER_START_FAILED") from error
    stdout_buffer = bytearray()
    stderr_buffer = bytearray()
    output_too_large = threading.Event()

    def drain(stream: Any, output: bytearray, maximum: int) -> None:
        while True:
            chunk = stream.read(64 * 1024)
            if not chunk:
                return
            remaining = maximum + 1 - len(output)
            if remaining > 0:
                output.extend(chunk[:remaining])
            if len(chunk) > remaining or len(output) > maximum:
                output_too_large.set()
                kill_process(process)

    readers = (
        threading.Thread(
            target=drain, args=(process.stdout, stdout_buffer, max_stdout_bytes), daemon=True
        ),
        threading.Thread(
            target=drain,
            args=(process.stderr, stderr_buffer, MAX_BROWSER_STDERR_BYTES),
            daemon=True,
        ),
    )
    for reader in readers:
        reader.start()
    deadline = time.monotonic() + timeout_seconds
    timed_out = False
    while process.poll() is None:
        if output_too_large.is_set():
            kill_process(process)
            break
        if time.monotonic() >= deadline:
            timed_out = True
            kill_process(process)
            break
        time.sleep(0.05)
    try:
        process.wait(timeout=5)
    except subprocess.TimeoutExpired:
        kill_process(process)
        process.wait(timeout=5)
    for reader in readers:
        reader.join(timeout=5)
    process.stdout.close()
    process.stderr.close()
    if timed_out:
        raise RunnerError("BROWSER_TIMEOUT")
    if output_too_large.is_set() or any(reader.is_alive() for reader in readers):
        raise RunnerError("BROWSER_OUTPUT_TOO_LARGE")
    return process.returncode, bytes(stdout_buffer)


def kill_process(process: subprocess.Popen[bytes]) -> None:
    if process.poll() is not None:
        return
    try:
        if sys.platform != "win32":
            os.killpg(process.pid, signal.SIGKILL)
        else:
            process.kill()
    except (OSError, ProcessLookupError):
        return


def browser_version(browser: str) -> str:
    returncode, stdout = run_bounded([browser, "--version"], 10, 64 * 1024)
    match = re.search(rb"\b(\d+(?:\.\d+){1,3})\b", stdout)
    if returncode != 0 or match is None:
        raise RunnerError("BROWSER_VERSION_UNAVAILABLE")
    return match.group(1).decode("ascii")


def run_case(
    browser: str,
    browser_args: list[str],
    case: dict[str, Any],
    *,
    allow_http: bool,
    timeout_seconds: int,
) -> bool:
    url = str(case.get("url", ""))
    parsed = urllib.parse.urlsplit(url)
    if (
        parsed.scheme not in ({"https", "http"} if allow_http else {"https"})
        or not parsed.hostname
        or parsed.username is not None
        or parsed.password is not None
        or parsed.fragment
    ):
        raise RunnerError("SUITE_CASE_URL_REJECTED")
    with tempfile.TemporaryDirectory(prefix="browsercloud-validation-") as profile:
        command = [
            browser,
            "--headless=new",
            "--disable-gpu",
            "--disable-dev-shm-usage",
            f"--user-data-dir={profile}",
            *browser_args,
            "--dump-dom",
            url,
        ]
        returncode, stdout = run_bounded(command, timeout_seconds, MAX_DOM_BYTES)
    if returncode != 0:
        return False
    try:
        dom = stdout.decode("utf-8")
    except UnicodeError:
        return False
    expected = case.get("expectedText")
    forbidden = case.get("forbiddenText")
    return (expected is None or str(expected) in dom) and (
        forbidden is None or str(forbidden) not in dom
    )


def validate_replay_dataset(dataset: dict[str, Any], *, allow_http: bool) -> list[dict[str, Any]]:
    """Reject an unapproved or malformed catalog before any case starts a browser."""
    authorization = dataset.get("authorization")
    if not isinstance(authorization, dict) or (
        authorization.get("containsProductionData") is not False
        or authorization.get("personalData") is not False
        or authorization.get("credentials") is not False
        or not isinstance(authorization.get("basis"), str)
        or not authorization["basis"].strip()
    ):
        raise RunnerError("REPLAY_AUTHORIZATION_INVALID")
    hosts = authorization.get("allowedHosts")
    if not isinstance(hosts, list) or not hosts or len(hosts) > 100:
        raise RunnerError("REPLAY_AUTHORIZED_HOSTS_INVALID")
    if any(not isinstance(host, str) or not valid_host(host) for host in hosts):
        raise RunnerError("REPLAY_AUTHORIZED_HOSTS_INVALID")
    authorized_hosts = set(hosts)
    if len(authorized_hosts) != len(hosts):
        raise RunnerError("REPLAY_AUTHORIZED_HOSTS_INVALID")
    cases = dataset.get("cases")
    if not isinstance(cases, list) or not 1 <= len(cases) <= MAX_REPLAY_CASES:
        raise RunnerError("REPLAY_DATASET_INVALID")
    seen_ids: set[str] = set()
    required_count = 0
    for case in cases:
        if not isinstance(case, dict) or not re.fullmatch(
            r"[A-Z][A-Z0-9_]{1,127}", str(case.get("id", ""))
        ):
            raise RunnerError("REPLAY_CASE_INVALID")
        if case["id"] in seen_ids or type(case.get("required", True)) is not bool:
            raise RunnerError("REPLAY_CASE_INVALID")
        seen_ids.add(case["id"])
        required_count += case.get("required", True)
        url = case.get("url")
        if not isinstance(url, str):
            raise RunnerError("REPLAY_CASE_URL_REJECTED")
        try:
            parsed = urllib.parse.urlsplit(url)
            host = parsed.hostname
            port = parsed.port
        except ValueError as error:
            raise RunnerError("REPLAY_CASE_URL_REJECTED") from error
        if (
            parsed.scheme not in ({"https", "http"} if allow_http else {"https"})
            or not host
            or not valid_host(host)
            or host not in authorized_hosts
            or parsed.username is not None
            or parsed.password is not None
            or parsed.fragment
            or (port is not None and not 1 <= port <= 65535)
        ):
            raise RunnerError("REPLAY_CASE_URL_REJECTED")
    if required_count < 1:
        raise RunnerError("REPLAY_REQUIRED_CASE_MISSING")
    return cases


def valid_host(host: str) -> bool:
    return (
        host == host.lower()
        and len(host) <= 253
        and all(
            1 <= len(label) <= 63
            and re.fullmatch(r"[a-z0-9](?:[a-z0-9-]*[a-z0-9])?", label) is not None
            for label in host.split(".")
        )
    )


def execute(args: argparse.Namespace, validation: dict[str, Any]) -> dict[str, Any]:
    job = validation.get("job")
    if not isinstance(job, dict):
        raise RunnerError("VALIDATION_JOB_MISSING")
    actual_version = browser_version(args.browser)
    expected_version = str(job.get("browserVersion", ""))
    if expected_version != "stable" and actual_version != expected_version:
        raise RunnerError("BROWSER_VERSION_MISMATCH")
    catalog = load_catalog(pathlib.Path(args.suite_catalog))
    dataset = catalog["datasets"].get(validation.get("replayDatasetId"))
    if not isinstance(dataset, dict):
        raise RunnerError("REPLAY_DATASET_NOT_FOUND")
    if dataset.get("suiteVersion") != validation.get("suiteVersion"):
        raise RunnerError("SUITE_VERSION_MISMATCH")
    if dataset.get("persona") != validation.get("persona"):
        raise RunnerError("PERSONA_MISMATCH")
    declared = dataset.get("declaredCapabilities")
    if not isinstance(declared, dict):
        raise RunnerError("REPLAY_DATASET_INVALID")
    if any(not isinstance(key, str) or not isinstance(value, bool) for key, value in declared.items()):
        raise RunnerError("REPLAY_DATASET_INVALID")
    cases = validate_replay_dataset(dataset, allow_http=args.allow_http)
    required_tests = required_failures = optional_tests = optional_failures = 0
    optional_codes: list[str] = []
    observed = {key: False for key in declared}
    for case in cases:
        required = case.get("required", True)
        passed = run_case(
            args.browser,
            args.browser_arg,
            case,
            allow_http=args.allow_http,
            timeout_seconds=args.case_timeout_seconds,
        )
        capability = case.get("capability")
        if capability is not None:
            if capability not in observed:
                raise RunnerError("REPLAY_CASE_CAPABILITY_INVALID")
            observed[capability] = observed[capability] or passed
        if required:
            required_tests += 1
            required_failures += 0 if passed else 1
        else:
            optional_tests += 1
            optional_failures += 0 if passed else 1
            if not passed:
                optional_codes.append(str(case["id"]))
    if required_tests < 1:
        raise RunnerError("REPLAY_REQUIRED_CASE_MISSING")
    return {
        "requiredTests": required_tests,
        "requiredFailures": required_failures,
        "optionalTests": optional_tests,
        "optionalFailures": optional_failures,
        "declaredCapabilities": declared,
        "observedCapabilities": observed,
        "optionalFailureCodes": optional_codes,
        "personaConsistent": True,
    }


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--browser", required=True)
    parser.add_argument("--browser-arg", action="append", default=[])
    parser.add_argument("--suite-catalog", required=True)
    parser.add_argument("--case-timeout-seconds", type=int, default=30)
    parser.add_argument("--allow-http", action="store_true")
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        validation = json.load(sys.stdin)
        if not isinstance(validation, dict):
            raise RunnerError("VALIDATION_INPUT_INVALID")
        print(json.dumps(execute(args, validation), separators=(",", ":")))
        return 0
    except (RunnerError, json.JSONDecodeError, OSError, subprocess.SubprocessError) as error:
        code = str(error) if isinstance(error, RunnerError) else "RUNNER_INTERNAL_ERROR"
        print(json.dumps({"error": code}), file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
