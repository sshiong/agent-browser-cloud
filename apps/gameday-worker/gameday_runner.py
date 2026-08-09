#!/usr/bin/env python3
"""Execute one fixed-catalog Recovery GameDay and always attempt recovery."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import pathlib
import signal
import ssl
import stat
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request


MAX_CONFIG_BYTES = 1024 * 1024
MAX_RESPONSE_BYTES = 64 * 1024
CANCELLED = threading.Event()


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise urllib.error.HTTPError(newurl, code, "redirects are disabled", headers, fp)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--catalog-file", required=True)
    parser.add_argument("--controller-token-file", required=True)
    parser.add_argument("--ca-file")
    parser.add_argument("--game-day-id", required=True)
    parser.add_argument("--scenario-code", required=True)
    parser.add_argument("--environment", required=True)
    parser.add_argument("--source-region", required=True)
    parser.add_argument("--target-region", required=True)
    parser.add_argument("--maximum-duration-seconds", required=True, type=int)
    parser.add_argument("--recovery-only", action="store_true")
    return parser.parse_args()


def bounded_file(path: str, maximum: int) -> bytes:
    file_path = pathlib.Path(path)
    if not file_path.is_absolute() or file_path.is_symlink():
        raise ValueError("configured file must be an absolute non-symlink path")
    with file_path.open("rb") as handle:
        data = handle.read(maximum + 1)
    if len(data) > maximum:
        raise ValueError("configured file exceeds the size limit")
    return data


def read_catalog(path: str) -> dict:
    payload = json.loads(bounded_file(path, MAX_CONFIG_BYTES))
    if payload.get("version") != 1 or not isinstance(payload.get("scenarios"), dict):
        raise ValueError("GameDay catalog must use version 1 and contain scenarios")
    return payload


def read_token(path: str) -> str:
    file_path = pathlib.Path(path)
    if not file_path.is_absolute() or file_path.is_symlink() or not file_path.is_file():
        raise ValueError("controller token must be an absolute regular non-symlink file")
    info = file_path.stat()
    mode = stat.S_IMODE(info.st_mode)
    private_owner = mode == 0o600 and info.st_uid == os.geteuid()
    private_group = mode == 0o440 and info.st_gid == os.getegid()
    if not (private_owner or private_group):
        raise ValueError("controller token must be owner 0600 or process-group 0440")
    token = bounded_file(path, 8192).decode("utf-8").strip()
    if not token or "\n" in token or "\r" in token:
        raise ValueError("controller token must be a non-empty single line")
    return token


def validate_path(value: object, name: str) -> str:
    if not isinstance(value, str) or not value.startswith("/") or value.startswith("//"):
        raise ValueError(f"{name} must be an absolute controller path")
    parsed = urllib.parse.urlsplit(value)
    if parsed.scheme or parsed.netloc or parsed.query or parsed.fragment:
        raise ValueError(f"{name} cannot change the configured controller origin")
    return value


def validate_entry(entry: object, environment: str) -> dict:
    if not isinstance(entry, dict) or entry.get("environment") != environment:
        raise ValueError("scenario is not authorized for the claimed environment")
    base_url = entry.get("controllerBaseUrl")
    if not isinstance(base_url, str):
        raise ValueError("scenario controllerBaseUrl is required")
    parsed = urllib.parse.urlsplit(base_url)
    if (
        parsed.scheme not in {"http", "https"}
        or not parsed.hostname
        or parsed.username
        or parsed.password
        or parsed.query
        or parsed.fragment
        or parsed.path not in {"", "/"}
    ):
        raise ValueError("scenario controllerBaseUrl must be a fixed HTTP(S) origin")
    if environment != "TEST" and parsed.scheme != "https":
        raise ValueError("non-test GameDay controllers require HTTPS")
    if environment == "TEST" and parsed.scheme == "http" and parsed.hostname not in {
        "127.0.0.1",
        "localhost",
        "::1",
    }:
        raise ValueError("test HTTP GameDay controllers must be loopback-only")
    return {
        "controllerBaseUrl": base_url.rstrip("/"),
        "injectPath": validate_path(entry.get("injectPath"), "injectPath"),
        "recoverPath": validate_path(entry.get("recoverPath"), "recoverPath"),
        "healthPath": validate_path(entry.get("healthPath"), "healthPath"),
        "evidencePath": validate_path(entry.get("evidencePath"), "evidencePath"),
        "healthyStatus": bounded_int(entry.get("healthyStatus", 200), 100, 599, "healthyStatus"),
        "faultStatus": bounded_int(entry.get("faultStatus", 503), 100, 599, "faultStatus"),
        "observationSeconds": bounded_int(
            entry.get("observationSeconds", 1), 0, 30, "observationSeconds"
        ),
    }


def bounded_int(value: object, minimum: int, maximum: int, name: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or not minimum <= value <= maximum:
        raise ValueError(f"{name} is outside the allowed range")
    return value


def ssl_context(ca_file: str | None) -> ssl.SSLContext:
    return ssl.create_default_context(cafile=ca_file) if ca_file else ssl.create_default_context()


def opener(context: ssl.SSLContext) -> urllib.request.OpenerDirector:
    return urllib.request.build_opener(
        urllib.request.ProxyHandler({}), NoRedirect(), urllib.request.HTTPSHandler(context=context)
    )


def request_json(
    http: urllib.request.OpenerDirector,
    method: str,
    url: str,
    token: str,
    body: dict | None,
    timeout: float,
) -> tuple[int, dict | None]:
    data = None if body is None else json.dumps(body, separators=(",", ":")).encode("utf-8")
    headers = {
        "Authorization": f"Bearer {token}",
        "Accept": "application/json",
        "User-Agent": "agent-browser-cloud-gameday-runner/1",
    }
    if data is not None:
        headers["Content-Type"] = "application/json"
    call = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        response = http.open(call, timeout=timeout)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        payload = response.read(MAX_RESPONSE_BYTES + 1)
        if len(payload) > MAX_RESPONSE_BYTES:
            raise RuntimeError("controller response exceeded the size limit")
        content_type = response.headers.get_content_type()
        if payload and content_type != "application/json":
            raise RuntimeError("controller response must be application/json")
        parsed = json.loads(payload) if payload else None
        if parsed is not None and not isinstance(parsed, dict):
            raise RuntimeError("controller response must be a JSON object")
        return response.status, parsed


def emit_stage(stage: str) -> None:
    print(json.dumps({"type": "stage", "stage": stage}, separators=(",", ":")), flush=True)


def emit_result(result: dict) -> None:
    print(json.dumps({"type": "result", "result": result}, separators=(",", ":")), flush=True)


def require_status(actual: int, expected: int, operation: str) -> None:
    if actual != expected:
        raise RuntimeError(f"{operation} returned HTTP_{actual}")


def poll_health(
    http: urllib.request.OpenerDirector,
    entry: dict,
    token: str,
    expected_status: int,
    deadline: float,
) -> float:
    started = time.monotonic()
    last_status = None
    while time.monotonic() < deadline:
        status, _ = request_json(
            http,
            "GET",
            entry["controllerBaseUrl"] + entry["healthPath"],
            token,
            None,
            5,
        )
        last_status = status
        if status == expected_status:
            return time.monotonic() - started
        if CANCELLED.is_set() and expected_status == entry["faultStatus"]:
            raise InterruptedError("GameDay abort requested")
        time.sleep(0.25)
    raise TimeoutError(f"health transition timed out at HTTP_{last_status}")


def evidence_metrics(payload: dict | None) -> dict:
    if not isinstance(payload, dict):
        raise RuntimeError("controller evidence is missing")
    result = {}
    for key in (
        "dataLossRecords",
        "staleOperationCount",
        "userImpactCount",
        "manualSteps",
        "runbookAccuracyPercent",
    ):
        value = payload.get(key)
        upper = 100 if key == "runbookAccuracyPercent" else 2_147_483_647
        result[key] = bounded_int(value, 0, upper, key)
    return result


def recovery(
    http: urllib.request.OpenerDirector,
    entry: dict,
    token: str,
    game_day_id: str,
    deadline: float,
) -> tuple[bool, float | None, dict | None, str | None]:
    try:
        # Cleanup is a safety action, not normal workload. Give it a bounded grace
        # period even when the exercise deadline or termination signal has fired.
        recovery_deadline = max(deadline, time.monotonic() + 30)
        emit_stage("RECOVERING")
        status, _ = request_json(
            http,
            "POST",
            entry["controllerBaseUrl"] + entry["recoverPath"],
            token,
            {"gameDayId": game_day_id, "idempotencyKey": f"{game_day_id}:recover"},
            10,
        )
        require_status(status, 200, "recover")
        recovery_seconds = poll_health(
            http, entry, token, entry["healthyStatus"], recovery_deadline
        )
        emit_stage("VALIDATING")
        evidence_status, evidence = request_json(
            http,
            "GET",
            entry["controllerBaseUrl"] + entry["evidencePath"],
            token,
            None,
            10,
        )
        require_status(evidence_status, 200, "evidence")
        return True, recovery_seconds, evidence_metrics(evidence), None
    except Exception as error:  # recovery result must be reported, not hidden by cleanup
        return False, None, None, type(error).__name__.upper()


def run(args: argparse.Namespace) -> dict:
    if not 30 <= args.maximum_duration_seconds <= 7200:
        raise ValueError("maximum duration is outside the platform bound")
    catalog = read_catalog(args.catalog_file)
    entry = validate_entry(catalog["scenarios"].get(args.scenario_code), args.environment)
    token = read_token(args.controller_token_file)
    http = opener(ssl_context(args.ca_file))
    deadline = time.monotonic() + args.maximum_duration_seconds
    injected = False
    detection_seconds = None
    failure_code = None
    aborted = False
    started = time.monotonic()

    if args.recovery_only:
        confirmed, recovery_seconds, evidence, recovery_error = recovery(
            http, entry, token, args.game_day_id, deadline
        )
        return result_payload(
            args,
            started,
            detection_seconds,
            recovery_seconds,
            evidence,
            confirmed,
            True,
            "GAMEDAY_RECOVERY_ONLY_COMPLETED" if confirmed else recovery_error,
            True,
        )

    recovery_seconds = None
    evidence = None
    recovery_confirmed = False
    try:
        emit_stage("INJECTING")
        status, _ = request_json(
            http,
            "POST",
            entry["controllerBaseUrl"] + entry["injectPath"],
            token,
            {
                "gameDayId": args.game_day_id,
                "scenarioCode": args.scenario_code,
                "sourceRegion": args.source_region,
                "targetRegion": args.target_region,
                "idempotencyKey": f"{args.game_day_id}:inject",
            },
            10,
        )
        require_status(status, 200, "inject")
        injected = True
        emit_stage("FAULT_INJECTED")
        detection_seconds = poll_health(
            http, entry, token, entry["faultStatus"], deadline
        )
        emit_stage("OBSERVING")
        observation_deadline = time.monotonic() + entry["observationSeconds"]
        while time.monotonic() < observation_deadline:
            if CANCELLED.wait(min(0.25, observation_deadline - time.monotonic())):
                raise InterruptedError("GameDay abort requested")
    except InterruptedError:
        aborted = True
        failure_code = "GAMEDAY_ABORT_REQUESTED"
    except Exception as error:
        failure_code = f"GAMEDAY_{type(error).__name__.upper()}"
    finally:
        if injected or failure_code is not None or CANCELLED.is_set():
            recovery_confirmed, recovery_seconds, evidence, recovery_error = recovery(
                http, entry, token, args.game_day_id, deadline
            )
            if not recovery_confirmed:
                failure_code = recovery_error or "GAMEDAY_RECOVERY_UNCONFIRMED"

    return result_payload(
        args,
        started,
        detection_seconds,
        recovery_seconds,
        evidence,
        recovery_confirmed,
        aborted,
        failure_code,
        injected,
    )


def result_payload(
    args: argparse.Namespace,
    started: float,
    detection_seconds: float | None,
    recovery_seconds: float | None,
    evidence: dict | None,
    recovery_confirmed: bool,
    aborted: bool,
    failure_code: str | None,
    fault_injected: bool,
) -> dict:
    metrics = evidence or {
        "dataLossRecords": 0,
        "staleOperationCount": 0,
        "userImpactCount": 0,
        "manualSteps": 0,
        "runbookAccuracyPercent": 0,
    }
    observed_rto = max(0, math.ceil(time.monotonic() - started))
    proof = {
        "gameDayId": args.game_day_id,
        "scenarioCode": args.scenario_code,
        "environment": args.environment,
        "detectionTimeSeconds": None if detection_seconds is None else math.ceil(detection_seconds),
        "recoveryTimeSeconds": None if recovery_seconds is None else math.ceil(recovery_seconds),
        "observedRtoSeconds": observed_rto,
        "metrics": metrics,
        "recoveryConfirmed": recovery_confirmed,
        "faultInjected": fault_injected,
        "aborted": aborted,
        "failureCode": failure_code,
    }
    digest = hashlib.sha256(
        json.dumps(proof, sort_keys=True, separators=(",", ":")).encode("utf-8")
    ).hexdigest()
    return {
        **proof,
        "observedRpoSeconds": 0,
        "runnerEvidenceHash": f"sha256:{digest}",
    }


def main() -> int:
    args = parse_args()
    signal.signal(signal.SIGTERM, lambda *_: CANCELLED.set())
    signal.signal(signal.SIGINT, lambda *_: CANCELLED.set())
    try:
        emit_result(run(args))
        return 0
    except Exception as error:
        emit_result(
            {
                "gameDayId": args.game_day_id,
                "scenarioCode": args.scenario_code,
                "environment": args.environment,
                "recoveryConfirmed": False,
                "aborted": CANCELLED.is_set(),
                "failureCode": f"GAMEDAY_RUNNER_{type(error).__name__.upper()}",
            }
        )
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
