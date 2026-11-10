"""Actual MCP HTTP client/server protocol; the upstream API is an explicit fixture."""
import asyncio
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import importlib.util
import json
import os
from pathlib import Path
import socket
import threading
import unittest

import httpx
from mcp import ClientSession
from mcp.client.streamable_http import streamablehttp_client
import uvicorn


class GatewayTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.revoked = False
        self.calls = []
        test = self
        class Handler(BaseHTTPRequestHandler):
            def handle_api(self):
                length = int(self.headers.get('Content-Length', '0'))
                body = json.loads(self.rfile.read(length)) if length else None
                test.calls.append((self.command, self.path, body))
                authenticated = self.headers.get('Authorization') == 'Bearer fixture' and not test.revoked
                data = {'tenantId': 'owned'} if self.path.endswith('/identity') else {'accepted': True}
                if self.command == 'POST' and self.path.endswith('/environments'):
                    data = {'sessionId': 'ses_owned', 'tenantId': body['tenantId']}
                encoded = json.dumps(data).encode()
                self.send_response(200 if authenticated else 401)
                self.send_header('Content-Type', 'application/json')
                self.send_header('Content-Length', str(len(encoded)))
                self.end_headers(); self.wfile.write(encoded)
            do_GET = handle_api
            do_POST = handle_api
            do_DELETE = handle_api
            def log_message(self, *_): pass
        self.upstream = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        self.thread = threading.Thread(target=self.upstream.serve_forever, daemon=True); self.thread.start()
        os.environ['CONTROL_PLANE_URL'] = f'http://127.0.0.1:{self.upstream.server_port}'
        spec = importlib.util.spec_from_file_location('owned_gateway', Path(__file__).with_name('gateway.py'))
        self.module = importlib.util.module_from_spec(spec); spec.loader.exec_module(self.module)
        self.sock = socket.socket(); self.sock.bind(('127.0.0.1', 0))
        self.url = f'http://127.0.0.1:{self.sock.getsockname()[1]}/mcp'
        self.server = uvicorn.Server(uvicorn.Config(self.module.app, log_level='critical', access_log=False))
        self.task = asyncio.create_task(self.server.serve(sockets=[self.sock]))
        for _ in range(100):
            if self.server.started: break
            await asyncio.sleep(.05)
        self.assertTrue(self.server.started)

    async def asyncTearDown(self):
        self.server.should_exit = True
        await asyncio.wait_for(self.task, 10)
        self.sock.close(); self.upstream.shutdown(); self.upstream.server_close(); self.thread.join(3)

    async def test_tools_lifecycle_authentication_origin_and_revocation(self):
        async with httpx.AsyncClient(trust_env=False) as client:
            response = await client.post(self.url, json={})
            self.assertEqual(response.status_code, 401)
            response = await client.post(self.url, json={}, headers={'Authorization': 'Bearer fixture', 'Origin': 'https://untrusted.invalid'})
            self.assertEqual(response.status_code, 403)
        async with streamablehttp_client(self.url, headers={'Authorization': 'Bearer fixture'}) as (read, write, _):
            async with ClientSession(read, write) as session:
                await session.initialize()
                listed = await session.list_tools()
                self.assertEqual(len(listed.tools), 8)
                actions = {
                    'browser_get_quota': {}, 'browser_list_environments': {},
                    'browser_get_environment': {'environment_id': 'ses_owned'},
                    'browser_create_environment': {'profile_id': 'profile-owned', 'idempotency_key': 'owned-create'},
                    'browser_start_environment': {'environment_id': 'ses_owned', 'idempotency_key': 'owned-start'},
                    'browser_stop_environment': {'environment_id': 'ses_owned', 'idempotency_key': 'owned-stop'},
                    'browser_delete_environment': {'environment_id': 'ses_owned', 'idempotency_key': 'owned-delete'},
                    'browser_get_operation': {'operation_id': 'op_owned'},
                }
                for name, arguments in actions.items():
                    result = await session.call_tool(name, arguments)
                    self.assertFalse(result.isError, name)
                created = next(body for method, path, body in self.calls if method == 'POST' and path.endswith('/environments'))
                self.assertEqual(created['tenantId'], 'owned')
                before = len(self.calls)
                result = await session.call_tool('browser_get_environment', {'environment_id': 'ses_../../escape'})
                self.assertTrue(result.isError)
                # HTTP authentication may introspect; no unsafe resource request reaches the API.
                self.assertTrue(all(path.endswith('/identity') for _, path, _ in self.calls[before:]))
        self.revoked = True
        async with httpx.AsyncClient(trust_env=False) as client:
            response = await client.post(self.url, json={}, headers={'Authorization': 'Bearer fixture'})
            self.assertEqual(response.status_code, 401)
