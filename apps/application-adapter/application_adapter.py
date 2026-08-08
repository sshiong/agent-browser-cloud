#!/usr/bin/env python3
"""Least-privilege Application Adapter runtime for real Provider attestations.

The runtime reads short-lived bearer tokens from mounted files, calls an allow-listed
Provider HTTPS endpoint, hashes the selected JSON value locally, and submits only the
hash and a bounded Provider reference to the Control Plane. It also exposes the
owner-bound business-activity Lease operations used by application integrations.
"""

from __future__ import annotations

import argparse
import dataclasses
import datetime as dt
import hashlib
import json
import os
import pathlib
import re
import ssl
import stat
import sys
import urllib.error
import urllib.parse
import urllib.request
from typing import Any


MAX_SECRET_BYTES = 16 * 1024
MAX_RESPONSE_BYTES = 1024 * 1024
SHA256_PATTERN = re.compile(r"^[0-9a-f]{64}$")
SESSION_PATTERN = re.compile(r"^ses_[A-Za-z0-9]{16,}$")
REFERENCE_HEADERS = {"x-request-id", "request-id", "x-correlation-id", "traceparent"}


class AdapterError(RuntimeError):
    """Stable, content-free failure safe to expose in logs and process output."""

    def __init__(self, code: str):
        super().__init__(code)
        self.code = code


class NoRedirectHandler(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *_args: Any, **_kwargs: Any) -> None:
        return None


def read_secret_file(path: str | pathlib.Path) -> str:
    secret_path = pathlib.Path(path)
    try:
        metadata = secret_path.stat()
    except OSError as error:
        raise AdapterError("SECRET_FILE_UNAVAILABLE") from error
    if not stat.S_ISREG(metadata.st_mode):
        raise AdapterError("SECRET_FILE_NOT_REGULAR")
    if metadata.st_size <= 0 or metadata.st_size > MAX_SECRET_BYTES:
        raise AdapterError("SECRET_FILE_SIZE_INVALID")
    if os.name != "nt" and stat.S_IMODE(metadata.st_mode) & 0o077:
        raise AdapterError("SECRET_FILE_PERMISSIONS_TOO_BROAD")
    try:
        value = secret_path.read_text(encoding="utf-8").strip()
    except (OSError, UnicodeError) as error:
        raise AdapterError("SECRET_FILE_UNREADABLE") from error
    if not value or "\x00" in value or "\n" in value or "\r" in value:
        raise AdapterError("SECRET_VALUE_INVALID")
    return value


def canonical_value(value: Any) -> bytes:
    if isinstance(value, str):
        return value.encode("utf-8")
    return json.dumps(
        value, ensure_ascii=False, allow_nan=False, sort_keys=True, separators=(",", ":")
    ).encode("utf-8")


def sha256_value(value: Any) -> str:
    return hashlib.sha256(canonical_value(value)).hexdigest()


def extract_json_pointer(document: Any, pointer: str) -> Any:
    if pointer == "":
        return document
    if not pointer.startswith("/"):
        raise AdapterError("PROVIDER_JSON_POINTER_INVALID")
    value = document
    for encoded_token in pointer[1:].split("/"):
        token = encoded_token.replace("~1", "/").replace("~0", "~")
        try:
            if isinstance(value, list):
                if not token.isdigit() or (len(token) > 1 and token.startswith("0")):
                    raise AdapterError("PROVIDER_JSON_POINTER_INVALID")
                value = value[int(token)]
            elif isinstance(value, dict):
                value = value[token]
            else:
                raise AdapterError("PROVIDER_VALUE_MISSING")
        except (KeyError, IndexError) as error:
            raise AdapterError("PROVIDER_VALUE_MISSING") from error
    return value


class HttpTransport:
    def __init__(
        self,
        allowed_hosts: set[str],
        *,
        timeout_seconds: float = 10.0,
        max_response_bytes: int = MAX_RESPONSE_BYTES,
        ca_file: str | None = None,
        allow_http: bool = False,
    ):
        self.allowed_hosts = {host.lower().rstrip(".") for host in allowed_hosts}
        self.timeout_seconds = min(max(timeout_seconds, 0.25), 30.0)
        self.max_response_bytes = min(max(max_response_bytes, 1024), MAX_RESPONSE_BYTES)
        self.allow_http = allow_http
        context = ssl.create_default_context(cafile=ca_file)
        self.opener = urllib.request.build_opener(
            urllib.request.ProxyHandler({}),
            NoRedirectHandler(),
            urllib.request.HTTPSHandler(context=context),
        )

    def request_json(
        self,
        url: str,
        *,
        method: str,
        headers: dict[str, str],
        payload: dict[str, Any] | None,
        error_prefix: str,
    ) -> tuple[dict[str, Any] | list[Any], Any, str]:
        parsed = urllib.parse.urlsplit(url)
        scheme_allowed = parsed.scheme == "https" or (self.allow_http and parsed.scheme == "http")
        host = (parsed.hostname or "").lower().rstrip(".")
        if (
            not scheme_allowed
            or not host
            or host not in self.allowed_hosts
            or parsed.username is not None
            or parsed.password is not None
            or parsed.fragment
        ):
            raise AdapterError(f"{error_prefix}_URL_REJECTED")
        body = None
        request_headers = {"Accept": "application/json", **headers}
        if payload is not None:
            body = json.dumps(payload, separators=(",", ":")).encode("utf-8")
            request_headers["Content-Type"] = "application/json"
        request = urllib.request.Request(
            url, data=body, headers=request_headers, method=method.upper()
        )
        try:
            with self.opener.open(request, timeout=self.timeout_seconds) as response:
                raw = response.read(self.max_response_bytes + 1)
                if len(raw) > self.max_response_bytes:
                    raise AdapterError(f"{error_prefix}_RESPONSE_TOO_LARGE")
                response_headers = response.headers
        except urllib.error.HTTPError as error:
            raise AdapterError(f"{error_prefix}_HTTP_{error.code}") from error
        except (urllib.error.URLError, TimeoutError, OSError) as error:
            raise AdapterError(f"{error_prefix}_UNAVAILABLE") from error
        content_type = response_headers.get_content_type().lower()
        if content_type != "application/json" and not content_type.endswith("+json"):
            raise AdapterError(f"{error_prefix}_CONTENT_TYPE_INVALID")
        try:
            document = json.loads(raw)
        except (UnicodeError, json.JSONDecodeError) as error:
            raise AdapterError(f"{error_prefix}_JSON_INVALID") from error
        if not isinstance(document, (dict, list)):
            raise AdapterError(f"{error_prefix}_JSON_ROOT_INVALID")
        return document, response_headers, hashlib.sha256(raw).hexdigest()


@dataclasses.dataclass(frozen=True)
class ProviderAttestationSpec:
    session_id: str
    context_epoch: int
    state_version: int
    evidence_type: str
    key: str
    provider_id: str
    expected_value_hash: str
    provider_url: str
    value_pointer: str
    reference_header: str = "x-request-id"

    def validate(self) -> None:
        if not SESSION_PATTERN.fullmatch(self.session_id):
            raise AdapterError("SESSION_ID_INVALID")
        if self.context_epoch < 1 or self.state_version < 1:
            raise AdapterError("SESSION_VERSION_INVALID")
        if self.evidence_type not in {
            "ACCOUNT",
            "TENANT_WORKSPACE",
            "PERMISSION",
            "BUSINESS_ENTITY",
        }:
            raise AdapterError("EVIDENCE_TYPE_INVALID")
        if not SHA256_PATTERN.fullmatch(self.expected_value_hash):
            raise AdapterError("EXPECTED_VALUE_HASH_INVALID")
        identifier = re.compile(r"^[A-Za-z][A-Za-z0-9_.-]{0,127}$")
        if not identifier.fullmatch(self.key) or not identifier.fullmatch(self.provider_id):
            raise AdapterError("EVIDENCE_IDENTIFIER_INVALID")
        if self.reference_header.lower() not in REFERENCE_HEADERS:
            raise AdapterError("PROVIDER_REFERENCE_HEADER_REJECTED")


@dataclasses.dataclass(frozen=True)
class ProviderObservation:
    value_hash: str
    outcome: str
    reference: str
    observed_at: str


@dataclasses.dataclass(frozen=True)
class AttestationReceipt:
    evidence_id: str
    outcome: str
    request_id: str
    expires_at: str


class ProviderClient:
    def __init__(self, transport: HttpTransport, bearer_token: str | None):
        self.transport = transport
        self.bearer_token = bearer_token

    def observe(self, spec: ProviderAttestationSpec) -> ProviderObservation:
        headers: dict[str, str] = {}
        if self.bearer_token is not None:
            headers["Authorization"] = f"Bearer {self.bearer_token}"
        document, response_headers, response_hash = self.transport.request_json(
            spec.provider_url,
            method="GET",
            headers=headers,
            payload=None,
            error_prefix="PROVIDER",
        )
        observed_hash = sha256_value(extract_json_pointer(document, spec.value_pointer))
        raw_reference = response_headers.get(spec.reference_header, "").strip()
        reference = raw_reference[:512] if raw_reference else f"response-sha256:{response_hash}"
        now = dt.datetime.now(dt.timezone.utc).isoformat().replace("+00:00", "Z")
        return ProviderObservation(
            value_hash=observed_hash,
            outcome="MATCH" if observed_hash == spec.expected_value_hash else "MISMATCH",
            reference=reference,
            observed_at=now,
        )


class BrowserCloudAdapterClient:
    def __init__(
        self,
        base_url: str,
        transport: HttpTransport,
        bearer_token: str,
        local_identity: tuple[str, str] | None = None,
    ):
        self.base_url = base_url.rstrip("/")
        self.transport = transport
        self.bearer_token = bearer_token
        self.local_identity = local_identity

    def _request(
        self,
        path: str,
        *,
        method: str,
        idempotency_key: str,
        payload: dict[str, Any] | None,
    ) -> dict[str, Any]:
        request_headers = {
            "Authorization": f"Bearer {self.bearer_token}",
            "Idempotency-Key": idempotency_key,
        }
        if self.local_identity is not None:
            request_headers.update(
                {
                    "X-Tenant-Id": self.local_identity[0],
                    "X-Actor-Id": self.local_identity[1],
                    "X-Roles": "APPLICATION_ADAPTER",
                }
            )
        document, _headers, _hash = self.transport.request_json(
            f"{self.base_url}{path}",
            method=method,
            headers=request_headers,
            payload=payload,
            error_prefix="CONTROL_PLANE",
        )
        if not isinstance(document, dict):
            raise AdapterError("CONTROL_PLANE_JSON_ROOT_INVALID")
        return document

    def submit_evidence(
        self, spec: ProviderAttestationSpec, observation: ProviderObservation
    ) -> AttestationReceipt:
        idempotency_material = {
            "sessionId": spec.session_id,
            "contextEpoch": spec.context_epoch,
            "stateVersion": spec.state_version,
            "type": spec.evidence_type,
            "key": spec.key,
            "providerId": spec.provider_id,
            "observedValueHash": observation.value_hash,
            "outcome": observation.outcome,
            "observedAt": observation.observed_at,
            "providerReferenceHash": hashlib.sha256(
                observation.reference.encode("utf-8")
            ).hexdigest(),
        }
        idempotency_key = "att-" + hashlib.sha256(canonical_value(idempotency_material)).hexdigest()
        result = self._request(
            f"/api/v1/sessions/{spec.session_id}/business-recovery/provider-evidence",
            method="POST",
            idempotency_key=idempotency_key,
            payload={
                "contextEpoch": spec.context_epoch,
                "stateVersion": spec.state_version,
                "type": spec.evidence_type,
                "key": spec.key,
                "providerId": spec.provider_id,
                "observedValueHash": observation.value_hash,
                "outcome": observation.outcome,
                "providerReference": observation.reference,
                "observedAt": observation.observed_at,
            },
        )
        return AttestationReceipt(
            evidence_id=str(result.get("evidenceId", "")),
            outcome=str(result.get("outcome", "")),
            request_id=str(result.get("requestId", "")),
            expires_at=str(result.get("expiresAt", "")),
        )

    def acquire_lease(
        self,
        session_id: str,
        signal_type: str,
        reason_code: str,
        ttl_seconds: int,
        idempotency_key: str,
    ) -> dict[str, Any]:
        return self._request(
            f"/api/v1/sessions/{session_id}/safety-leases",
            method="POST",
            idempotency_key=idempotency_key,
            payload={
                "signalType": signal_type,
                "reasonCode": reason_code,
                "ttlSeconds": ttl_seconds,
            },
        )

    def renew_lease(
        self, session_id: str, lease_id: str, ttl_seconds: int, idempotency_key: str
    ) -> dict[str, Any]:
        return self._request(
            f"/api/v1/sessions/{session_id}/safety-leases/{lease_id}",
            method="PUT",
            idempotency_key=idempotency_key,
            payload={"ttlSeconds": ttl_seconds},
        )

    def release_lease(
        self, session_id: str, lease_id: str, idempotency_key: str
    ) -> dict[str, Any]:
        return self._request(
            f"/api/v1/sessions/{session_id}/safety-leases/{lease_id}:release",
            method="POST",
            idempotency_key=idempotency_key,
            payload=None,
        )


class AttestationRunner:
    def __init__(self, provider: ProviderClient, control_plane: BrowserCloudAdapterClient):
        self.provider = provider
        self.control_plane = control_plane

    def run(self, spec: ProviderAttestationSpec) -> AttestationReceipt:
        spec.validate()
        return self.control_plane.submit_evidence(spec, self.provider.observe(spec))


def _common_control_plane(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--control-plane-url", required=True)
    parser.add_argument("--control-plane-token-file", required=True)
    parser.add_argument("--control-plane-ca-file")
    parser.add_argument("--timeout-seconds", type=float, default=10.0)
    parser.add_argument("--allow-insecure-http", action="store_true")
    parser.add_argument("--local-tenant-id")
    parser.add_argument("--local-actor-id")


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    attest = commands.add_parser("attest")
    _common_control_plane(attest)
    attest.add_argument("--provider-url", required=True)
    attest.add_argument("--provider-host", action="append", required=True)
    attest.add_argument("--provider-token-file")
    attest.add_argument("--provider-ca-file")
    attest.add_argument("--provider-reference-header", default="x-request-id")
    attest.add_argument("--value-pointer", required=True)
    attest.add_argument("--session-id", required=True)
    attest.add_argument("--context-epoch", type=int, required=True)
    attest.add_argument("--state-version", type=int, required=True)
    attest.add_argument(
        "--evidence-type",
        choices=["ACCOUNT", "TENANT_WORKSPACE", "PERMISSION", "BUSINESS_ENTITY"],
        required=True,
    )
    attest.add_argument("--key", required=True)
    attest.add_argument("--provider-id", required=True)
    attest.add_argument("--expected-value-hash", required=True)
    for command in ("lease-acquire", "lease-renew", "lease-release"):
        lease = commands.add_parser(command)
        _common_control_plane(lease)
        lease.add_argument("--session-id", required=True)
        lease.add_argument("--idempotency-key", required=True)
        if command != "lease-acquire":
            lease.add_argument("--lease-id", required=True)
        if command != "lease-release":
            lease.add_argument("--ttl-seconds", type=int, required=True)
        if command == "lease-acquire":
            lease.add_argument(
                "--signal-type",
                choices=[
                    "FILE_TRANSFER",
                    "FORM_SUBMISSION",
                    "PAYMENT_OR_SECURITY",
                    "CRITICAL_TRANSACTION",
                    "BUSINESS_RECOVERY_UNKNOWN",
                ],
                required=True,
            )
            lease.add_argument("--reason-code", required=True)
    return parser


def _allow_http(args: argparse.Namespace) -> bool:
    if not args.allow_insecure_http:
        return False
    if os.environ.get("APP_ENVIRONMENT", "").lower() not in {"local", "test"}:
        raise AdapterError("INSECURE_HTTP_FORBIDDEN")
    return True


def _control_plane_client(args: argparse.Namespace) -> BrowserCloudAdapterClient:
    parsed = urllib.parse.urlsplit(args.control_plane_url)
    host = parsed.hostname or ""
    transport = HttpTransport(
        {host},
        timeout_seconds=args.timeout_seconds,
        ca_file=args.control_plane_ca_file,
        allow_http=_allow_http(args),
    )
    local_identity = None
    if args.local_tenant_id or args.local_actor_id:
        if not args.local_tenant_id or not args.local_actor_id:
            raise AdapterError("LOCAL_IDENTITY_INCOMPLETE")
        if os.environ.get("APP_ENVIRONMENT", "").lower() not in {"local", "test"}:
            raise AdapterError("LOCAL_IDENTITY_FORBIDDEN")
        local_identity = (args.local_tenant_id, args.local_actor_id)
    return BrowserCloudAdapterClient(
        args.control_plane_url,
        transport,
        read_secret_file(args.control_plane_token_file),
        local_identity,
    )


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        control_plane = _control_plane_client(args)
        if args.command == "attest":
            provider_token = (
                read_secret_file(args.provider_token_file) if args.provider_token_file else None
            )
            provider = ProviderClient(
                HttpTransport(
                    set(args.provider_host),
                    timeout_seconds=args.timeout_seconds,
                    ca_file=args.provider_ca_file,
                    allow_http=_allow_http(args),
                ),
                provider_token,
            )
            result: Any = AttestationRunner(provider, control_plane).run(
                ProviderAttestationSpec(
                    session_id=args.session_id,
                    context_epoch=args.context_epoch,
                    state_version=args.state_version,
                    evidence_type=args.evidence_type,
                    key=args.key,
                    provider_id=args.provider_id,
                    expected_value_hash=args.expected_value_hash,
                    provider_url=args.provider_url,
                    value_pointer=args.value_pointer,
                    reference_header=args.provider_reference_header,
                )
            )
        elif args.command == "lease-acquire":
            result = control_plane.acquire_lease(
                args.session_id,
                args.signal_type,
                args.reason_code,
                args.ttl_seconds,
                args.idempotency_key,
            )
        elif args.command == "lease-renew":
            result = control_plane.renew_lease(
                args.session_id, args.lease_id, args.ttl_seconds, args.idempotency_key
            )
        else:
            result = control_plane.release_lease(
                args.session_id, args.lease_id, args.idempotency_key
            )
        print(json.dumps(dataclasses.asdict(result) if dataclasses.is_dataclass(result) else result))
        return 0
    except AdapterError as error:
        print(json.dumps({"error": error.code}), file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
