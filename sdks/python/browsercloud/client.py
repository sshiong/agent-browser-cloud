"""Small, typed-at-the-boundary client for the stable Agent Browser Cloud API."""

from __future__ import annotations

import json
import urllib.error
import urllib.request
import uuid
from dataclasses import dataclass
from typing import Any, Callable, Mapping


@dataclass(frozen=True)
class BrowserCloudError(Exception):
    status: int
    code: str
    message: str
    request_id: str | None = None

    def __str__(self) -> str:
        request = f" request_id={self.request_id}" if self.request_id else ""
        return f"{self.status} {self.code}: {self.message}{request}"


Transport = Callable[
    [str, str, Mapping[str, str], bytes | None], tuple[int, Mapping[str, str], bytes]
]


class BrowserCloudClient:
    def __init__(
        self,
        base_url: str,
        *,
        tenant_id: str,
        access_token: str | None = None,
        actor_id: str | None = None,
        timeout_seconds: float = 30.0,
        transport: Transport | None = None,
    ) -> None:
        if not base_url.startswith(("http://", "https://")):
            raise ValueError("base_url must be an absolute HTTP(S) URL")
        if not tenant_id:
            raise ValueError("tenant_id is required")
        self._base_url = base_url.rstrip("/")
        self._tenant_id = tenant_id
        self._access_token = access_token
        self._actor_id = actor_id
        self._timeout_seconds = timeout_seconds
        self._transport = transport or self._urllib_transport

    def list_sessions(self, *, limit: int = 50, offset: int = 0) -> dict[str, Any]:
        return self._request("GET", f"/sessions?limit={limit}&offset={offset}")

    def create_session(
        self,
        *,
        profile_id: str,
        region: str,
        resource_class: str = "L2",
        requested_tabs: int = 1,
        agent_actions_per_minute: int = 0,
        extension_ids: list[str] | None = None,
        remote_desktop: bool = False,
        web3_workload: bool = False,
        media_workload: bool = False,
        requested_media_streams: int = 0,
        media_bitrate_kbps: int = 0,
        video_recording: bool = False,
        metadata: Mapping[str, str] | None = None,
        idempotency_key: str | None = None,
    ) -> dict[str, Any]:
        return self._request(
            "POST",
            "/sessions",
            {
                "tenantId": self._tenant_id,
                "profileId": profile_id,
                "region": region,
                "resourceClass": resource_class,
                "requestedTabs": requested_tabs,
                "agentActionsPerMinute": agent_actions_per_minute,
                "extensionIds": extension_ids or [],
                "remoteDesktop": remote_desktop,
                "web3Workload": web3_workload,
                "mediaWorkload": media_workload,
                "requestedMediaStreams": requested_media_streams,
                "mediaBitrateKbps": media_bitrate_kbps,
                "videoRecording": video_recording,
                "metadata": dict(metadata or {}),
            },
            {"Idempotency-Key": idempotency_key or str(uuid.uuid4())},
        )

    def start_session(self, session_id: str) -> dict[str, Any]:
        return self._request("POST", f"/sessions/{session_id}:start")

    def terminate_session(self, session_id: str) -> dict[str, Any]:
        return self._request("POST", f"/sessions/{session_id}:terminate")

    def create_agent_task(
        self,
        session_id: str,
        *,
        goal: str,
        allowed_domains: list[str],
        max_replans: int = 2,
    ) -> dict[str, Any]:
        return self._request(
            "POST",
            f"/sessions/{session_id}/agent-tasks",
            {
                "goal": goal,
                "allowedDomains": allowed_domains,
                "maxReplans": max_replans,
            },
        )

    def get_enterprise_overview(self) -> dict[str, Any]:
        return self._request("GET", "/enterprise/overview")

    def explain_session_cost(self, session_id: str) -> dict[str, Any]:
        return self._request(
            "GET", f"/enterprise/sessions/{session_id}/cost-explanation"
        )

    def _request(
        self,
        method: str,
        path: str,
        body: Mapping[str, Any] | None = None,
        extra_headers: Mapping[str, str] | None = None,
    ) -> dict[str, Any]:
        headers = {
            "Accept": "application/json",
            "Content-Type": "application/json",
            "X-Tenant-Id": self._tenant_id,
        }
        if self._access_token:
            headers["Authorization"] = f"Bearer {self._access_token}"
            headers.pop("X-Tenant-Id", None)
        if self._actor_id and not self._access_token:
            headers["X-Actor-Id"] = self._actor_id
        headers.update(extra_headers or {})
        encoded = json.dumps(body, separators=(",", ":")).encode() if body else None
        status, _, payload = self._transport(
            method, self._base_url + "/api/v1" + path, headers, encoded
        )
        parsed = json.loads(payload.decode()) if payload else {}
        if status < 200 or status >= 300:
            raise BrowserCloudError(
                status,
                parsed.get("code", "UNKNOWN_ERROR"),
                parsed.get("message", f"HTTP {status}"),
                parsed.get("requestId"),
            )
        return parsed

    def _urllib_transport(
        self, method: str, url: str, headers: Mapping[str, str], body: bytes | None
    ) -> tuple[int, Mapping[str, str], bytes]:
        request = urllib.request.Request(url, data=body, headers=dict(headers), method=method)
        try:
            with urllib.request.urlopen(
                request, timeout=self._timeout_seconds
            ) as response:
                return response.status, dict(response.headers), response.read()
        except urllib.error.HTTPError as error:
            return error.code, dict(error.headers), error.read()
