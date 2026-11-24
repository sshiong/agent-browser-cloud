"""Optional Agent Browser Cloud MCP Gateway; authority remains in Control Plane."""
import contextvars
import asyncio
import json
import os
from urllib.parse import urlparse
from typing import Any

import httpx
from mcp.server.fastmcp import FastMCP
from mcp.server.transport_security import TransportSecuritySettings

BASE = os.environ.get("CONTROL_PLANE_URL", "http://127.0.0.1:8080").rstrip("/")
parsed = urlparse(BASE)
if parsed.scheme not in ("http", "https") or parsed.username or parsed.password or parsed.query or parsed.fragment:
    raise RuntimeError("MCP_CONTROL_PLANE_URL_INVALID")
if parsed.scheme == "http" and parsed.hostname not in ("127.0.0.1", "localhost") and os.environ.get("MCP_ALLOW_INTERNAL_HTTP") != "true":
    raise RuntimeError("MCP_CONTROL_PLANE_HTTPS_REQUIRED")
credential = contextvars.ContextVar("mcp_credential", default=None)
HOST = os.environ.get("MCP_HOST", "127.0.0.1")
PORT = int(os.environ.get("MCP_PORT", "8130"))
origins = os.environ.get("MCP_ALLOWED_ORIGINS", "http://127.0.0.1:* http://localhost:*").split()
hosts = os.environ.get("MCP_ALLOWED_HOSTS", "127.0.0.1:* localhost:*").split()
server = FastMCP("agent_browser_cloud", host=HOST, port=PORT, stateless_http=True,
    json_response=True, transport_security=TransportSecuritySettings(
        enable_dns_rebinding_protection=True, allowed_hosts=hosts, allowed_origins=origins))


async def api(path, method="GET", body=None, key=None, token=None):
    token = token or credential.get()
    if not token:
        raise ValueError("MCP_AUTHENTICATION_REQUIRED")
    headers = {"Authorization": "Bearer " + token}
    if key is not None:
        if not key.strip() or len(key) > 128:
            raise ValueError("IDEMPOTENCY_KEY_INVALID")
        headers["Idempotency-Key"] = key
    async with httpx.AsyncClient(trust_env=False, timeout=30) as client:
        response = await client.request(method, BASE + "/api/v1/agent-access" + path,
            headers=headers, json=body)
    if response.status_code >= 400:
        code = "CONTROL_PLANE_REQUEST_REJECTED"
        reason = None
        try:
            payload = response.json()
            candidate = payload.get("code", "")
            if candidate and len(candidate) < 100 and all(c.isupper() or c.isdigit() or c == "_" for c in candidate):
                code = candidate
            details = payload.get("details")
            if isinstance(details, dict):
                detail_reason = details.get("reason", "")
                if detail_reason and len(detail_reason) < 100 and all(c.isupper() or c.isdigit() or c == "_" for c in detail_reason):
                    reason = detail_reason
        except (ValueError, AttributeError):
            pass
        detail = f"{code} ({reason})" if reason and reason != code else code
        raise ValueError(f"{detail}: HTTP {response.status_code}")
    return response.json()


def identifier(value, prefix):
    if not value.startswith(prefix) or len(value) > 128 or not all(c.isascii() and (c.isalnum() or c in "_-") for c in value):
        raise ValueError("RESOURCE_IDENTIFIER_INVALID")
    return value


@server.tool(annotations={"readOnlyHint": True})
async def browser_get_quota() -> dict:
    """Get this connection's total and running environment quotas and usage."""
    return await api("/quota")


@server.tool(annotations={"readOnlyHint": True})
async def browser_list_environments(limit: int = 20, offset: int = 0) -> dict:
    """List only environments owned by this connection, with bounded pagination."""
    if not 1 <= limit <= 100 or offset < 0:
        raise ValueError("PAGINATION_INVALID")
    return await api(f"/environments?limit={limit}&offset={offset}")


@server.tool(annotations={"readOnlyHint": True})
async def browser_get_environment(environment_id: str) -> dict:
    """Read an environment owned by this connection."""
    return await api("/environments/" + identifier(environment_id, "ses_"))


@server.tool(annotations={"idempotentHint": True, "destructiveHint": False})
async def browser_create_environment(profile_id: str, idempotency_key: str,
    region: str = "local", runtime_build_id: str | None = None,
    proxy_binding_profile_id: str | None = None,
    display_name: str | None = None,
    agent_policy: str | None = None) -> dict:
    """Create a stopped environment with AUTO resource policy. Reuse the same idempotency key after an uncertain result; start separately. Optionally set display_name or agent_policy (BALANCED or INTERACTIVE for larger action batches)."""
    identity = await api("/identity")
    body: dict[str, Any] = {"tenantId": identity["tenantId"], "profileId": profile_id,
        "region": region, "resourcePolicy": {"mode": "AUTO"}}
    if runtime_build_id is not None:
        body["runtimeBuildId"] = runtime_build_id
    if proxy_binding_profile_id is not None:
        body["proxyBindingProfileId"] = proxy_binding_profile_id
    if display_name is not None:
        cleaned = display_name.strip()
        if not cleaned or len(cleaned) > 128:
            raise ValueError("DISPLAY_NAME_INVALID")
        body["metadata"] = {"displayName": cleaned}
    if agent_policy is not None:
        if agent_policy not in ("BALANCED", "INTERACTIVE"):
            raise ValueError("AGENT_POLICY_INVALID")
        body["agentPolicy"] = agent_policy
    return await api("/environments", "POST", body, idempotency_key)


@server.tool(annotations={"idempotentHint": True, "destructiveHint": False})
async def browser_start_environment(environment_id: str, idempotency_key: str) -> dict:
    """Start an owned environment within running quota; returns an asynchronous operation."""
    return await api("/environments/" + identifier(environment_id, "ses_") + ":start", "POST", key=idempotency_key)


@server.tool(annotations={"idempotentHint": True, "destructiveHint": False})
async def browser_stop_environment(environment_id: str, idempotency_key: str) -> dict:
    """Gracefully stop and checkpoint an owned environment; quota releases after authoritative completion."""
    return await api("/environments/" + identifier(environment_id, "ses_") + ":stop", "POST", key=idempotency_key)


@server.tool(annotations={"idempotentHint": True, "destructiveHint": True})
async def browser_delete_environment(environment_id: str, idempotency_key: str) -> dict:
    """Delete an owned stopped environment. Does not delete its Profile. Stop first if running."""
    return await api("/environments/" + identifier(environment_id, "ses_"), "DELETE", key=idempotency_key)


@server.tool(annotations={"readOnlyHint": True})
async def browser_get_operation(operation_id: str) -> dict:
    """Read an operation only when its environment belongs to this connection."""
    return await api("/operations/" + identifier(operation_id, "op_"))


@server.tool(annotations={"readOnlyHint": True, "destructiveHint": False})
async def browser_wait_operation(operation_id: str, timeout_seconds: int = 10) -> dict[str, Any]:
    """Wait up to 25 seconds for COMMITTED, ABORTED or TIMED_OUT. A wait timeout does not cancel the operation or release quota. Continue querying the same operation ID; do not repeat the write with a new key. Requires operation permission."""
    path = "/operations/" + identifier(operation_id, "op_")
    if not 1 <= timeout_seconds <= 25:
        raise ValueError("WAIT_TIMEOUT_INVALID")
    last = None
    # Bound the entire read, including an upstream HTTP call and sleeps.
    try:
        async with asyncio.timeout(timeout_seconds):
            while True:
                last = await api(path)
                state = last.get("state")
                if state in ("COMMITTED", "ABORTED", "TIMED_OUT"):
                    return {"completed": True, "waitTimedOut": False,
                        "succeeded": state == "COMMITTED", "operation": last}
                await asyncio.sleep(.5)
    except TimeoutError:
        return {"completed": False, "waitTimedOut": True,
            "succeeded": False, "operation": last}


@server.tool(annotations={"readOnlyHint": True})
async def browser_snapshot(environment_id: str) -> dict:
    """Read the current page summary, actionable targets, regional stability, and state cursor of an owned running environment. Requires direct permission."""
    return await api("/environments/" + identifier(environment_id, "ses_") + "/browser/snapshot")


@server.tool(annotations={"readOnlyHint": True})
async def browser_find(environment_id: str, query: str,
                       roles: list[str] | None = None,
                       include_hidden: bool = False,
                       limit: int = 20) -> dict:
    """Search actionable elements on the current page by semantic name, text value, role, control type, or stable elementId without transferring the full snapshot. Returns matching targets and the current stateCursor. Requires direct permission."""
    cleaned = query.strip()
    if not cleaned or len(cleaned) > 256:
        raise ValueError("FIND_QUERY_INVALID")
    if not 1 <= limit <= 100:
        raise ValueError("PAGINATION_INVALID")
    body: dict[str, Any] = {"query": cleaned, "includeHidden": include_hidden, "limit": limit}
    if roles:
        if len(roles) > 16:
            raise ValueError("FIND_ROLES_INVALID")
        body["roles"] = roles
    return await api("/environments/" + identifier(environment_id, "ses_") + "/browser/find",
        "POST", body)


@server.tool(annotations={"readOnlyHint": True})
async def browser_inspect(environment_id: str, state_cursor: str,
                          element_ids: list[str]) -> dict:
    """Inspect 1–50 exact elementIds from a stateCursor without a Browser Node round trip. Fails closed with STATE_CURSOR_STALE if the page changed. Requires direct permission."""
    if not 1 <= len(element_ids) <= 50:
        raise ValueError("INSPECT_ELEMENT_COUNT_INVALID")
    return await api("/environments/" + identifier(environment_id, "ses_") + "/browser/inspect",
        "POST", {"stateCursor": state_cursor, "elementIds": element_ids})


@server.tool(annotations={"idempotentHint": True, "destructiveHint": False})
async def browser_act(environment_id: str, goal: str, state_cursor: str,
                      actions: list[dict[str, Any]], idempotency_key: str) -> dict:
    """Run 1–20 direct browser actions. Use elementId and targetRevision from browser_snapshot or browser_find.
    Supported primitives: CLICK_TARGET, DOUBLE_CLICK_TARGET, RIGHT_CLICK_TARGET, HOVER_TARGET,
    CHECK_TARGET, UNCHECK_TARGET, TYPE_TEXT/FILL/SELECT_OPTION (non-sensitive PUBLIC textbox/combobox
    targets only; credentials/OTP/PII rejected), CLEAR_TARGET (non-sensitive textbox/combobox),
    PRESS_KEY (key), DRAG_TARGET (either endTargetRef for element-to-element drag / same-element
    long-press hold, OR deltaX/deltaY pixel offset for atomic mouse drags such as sliders, durationMs
    <= 5000), SWIPE_TARGET (touch swipe deltaX/deltaY, durationMs), MOUSE_DOWN/MOUSE_MOVE/DROP_TARGET/
    MOUSE_UP (30s think-safe drag window), MOUSE_WHEEL (deltaX, deltaY), SCROLL (scrollDeltaY),
    WAIT_FOR (waitCondition, timeoutMs), OPEN_TAB (tabUrl), SWITCH_TAB (tabId), DISMISS_DIALOG (dialogId).
    Prefer atomic DRAG_TARGET over split MOUSE_DOWN + DROP_TARGET so thinking never drops a drag.
    Wait for the returned task via browser_wait_action_task and refresh a stale cursor before retrying.
    Requires direct permission.
    """
    if not 1 <= len(actions) <= 20:
        raise ValueError("ACTION_BATCH_SIZE_INVALID")
    return await api("/environments/" + identifier(environment_id, "ses_") + "/browser/act",
        "POST", {"goal": goal, "expectedStateCursor": state_cursor, "actions": actions,
                 "stopOnError": True}, idempotency_key)


@server.tool(annotations={"idempotentHint": True, "destructiveHint": False})
async def browser_navigate(environment_id: str, goal: str, state_cursor: str,
                           url: str, idempotency_key: str) -> dict:
    """Navigate the active tab to an explicitly authorized HTTP(S) URL. Wait with browser_wait_action_task and take a fresh snapshot after completion. Requires direct permission."""
    return await api("/environments/" + identifier(environment_id, "ses_") + "/browser/act",
        "POST", {"goal": goal, "expectedStateCursor": state_cursor, "actions": [],
                 "navigateUrl": url}, idempotency_key)


@server.tool(annotations={"readOnlyHint": True})
async def browser_get_action_task(environment_id: str, task_id: str) -> dict:
    """Read a browser action task and its results within the same owned environment. Requires direct permission."""
    return await api("/environments/" + identifier(environment_id, "ses_") +
        "/browser/tasks/" + identifier(task_id, "agt_"))


@server.tool(annotations={"readOnlyHint": True, "destructiveHint": False})
async def browser_wait_action_task(environment_id: str, task_id: str,
                                   timeout_seconds: int = 10) -> dict[str, Any]:
    """Wait up to 25 seconds for a browser action task to finish (COMPLETED, FAILED, BLOCKED, WAITING_FOR_HUMAN or AWAITING_CONFIRMATION). A wait timeout does not cancel the task; continue polling the same task ID instead of repeating the write with a new idempotency key. Requires direct permission."""
    path = ("/environments/" + identifier(environment_id, "ses_") +
            "/browser/tasks/" + identifier(task_id, "agt_"))
    if not 1 <= timeout_seconds <= 25:
        raise ValueError("WAIT_TIMEOUT_INVALID")
    last = None
    settled_states = ("COMPLETED", "FAILED", "BLOCKED", "WAITING_FOR_HUMAN", "AWAITING_CONFIRMATION")
    try:
        async with asyncio.timeout(timeout_seconds):
            while True:
                last = await api(path)
                state = last.get("state")
                if state in settled_states:
                    return {"completed": True, "waitTimedOut": False,
                        "succeeded": state == "COMPLETED", "state": state, "task": last}
                await asyncio.sleep(.25)
    except TimeoutError:
        return {"completed": False, "waitTimedOut": True,
            "succeeded": False, "state": (last or {}).get("state"), "task": last}


class AuthenticatedGateway:
    def __init__(self, app):
        self.app = app

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http":
            return await self.app(scope, receive, send)
        if scope["path"] == "/healthz" and scope["method"] == "GET":
            await send({"type": "http.response.start", "status": 200, "headers": [(b"content-type", b"application/json")]})
            return await send({"type": "http.response.body", "body": b'{"status":"UP"}'})
        headers = dict(scope["headers"])
        bearer = headers.get(b"authorization", b"").decode("latin1")
        token = bearer[7:] if bearer.startswith("Bearer ") else None
        if not token or len(token) > 128:
            return await self.reject(send, 401)
        try:
            await api("/identity", token=token)
        except Exception:
            return await self.reject(send, 401)
        value = credential.set(token)
        try:
            await self.app(scope, receive, send)
        finally:
            credential.reset(value)

    @staticmethod
    async def reject(send, status):
        await send({"type": "http.response.start", "status": status,
            "headers": [(b"content-type", b"application/json"), (b"cache-control", b"no-store")]})
        await send({"type": "http.response.body", "body": b'{"error":"MCP_AUTHENTICATION_REQUIRED"}'})


app = AuthenticatedGateway(server.streamable_http_app())
if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host=HOST, port=PORT, access_log=False)
