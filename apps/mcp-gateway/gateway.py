"""Optional Agent Browser Cloud MCP Gateway; authority remains in Control Plane."""
import contextvars
import json
import os
from urllib.parse import urlparse

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
        try:
            candidate = response.json().get("code", "")
            if candidate and len(candidate) < 100 and all(c.isupper() or c == "_" for c in candidate):
                code = candidate
        except (ValueError, AttributeError):
            pass
        raise ValueError(f"{code}: HTTP {response.status_code}")
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
    proxy_binding_profile_id: str | None = None) -> dict:
    """Create a stopped environment. Reuse the same idempotency key after an uncertain result; start separately."""
    identity = await api("/identity")
    body = {"tenantId": identity["tenantId"], "profileId": profile_id,
        "region": region, "resourcePolicy": {"mode": "AUTO"}}
    if runtime_build_id is not None:
        body["runtimeBuildId"] = runtime_build_id
    if proxy_binding_profile_id is not None:
        body["proxyBindingProfileId"] = proxy_binding_profile_id
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
