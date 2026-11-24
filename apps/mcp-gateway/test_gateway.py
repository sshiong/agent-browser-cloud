"""Actual MCP HTTP client/server protocol; the upstream API is an explicit fixture."""
import asyncio
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import importlib.util
import json
import os
from pathlib import Path
import socket
import subprocess
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
        self.operation_states = ['COMMITTED']
        self.task_states = ['COMPLETED']
        self.error_response = None
        test = self
        class Handler(BaseHTTPRequestHandler):
            def handle_api(self):
                length = int(self.headers.get('Content-Length', '0'))
                body = json.loads(self.rfile.read(length)) if length else None
                test.calls.append((self.command, self.path, body))
                authenticated = self.headers.get('Authorization') == 'Bearer fixture' and not test.revoked
                status = 200 if authenticated else 401
                data = {'tenantId': 'owned'} if self.path.endswith('/identity') else {'accepted': True}
                if authenticated and test.error_response and not self.path.endswith('/identity'):
                    status, data = test.error_response
                elif '/operations/' in self.path:
                    state = test.operation_states[0]
                    if len(test.operation_states) > 1:
                        test.operation_states.pop(0)
                    data = {'operationId': 'op_owned', 'state': state}
                elif '/browser/tasks/' in self.path:
                    state = test.task_states[0]
                    if len(test.task_states) > 1:
                        test.task_states.pop(0)
                    data = {'taskId': 'agt_owned', 'state': state}
                elif self.command == 'POST' and self.path.endswith('/environments'):
                    data = {'sessionId': 'ses_owned', 'tenantId': body['tenantId']}
                encoded = json.dumps(data).encode()
                self.send_response(status)
                self.send_header('Content-Type', 'application/json')
                self.send_header('Content-Length', str(len(encoded)))
                self.end_headers(); self.wfile.write(encoded)
            do_GET = handle_api
            do_POST = handle_api
            do_DELETE = handle_api
            def log_message(self, *_): pass
        image = os.environ.get('MCP_TEST_CONTAINER_IMAGE')
        self.upstream = ThreadingHTTPServer(('0.0.0.0' if image else '127.0.0.1', 0), Handler)
        self.thread = threading.Thread(target=self.upstream.serve_forever, daemon=True); self.thread.start()
        if image:
            self.addCleanup(self.thread.join, 3)
            self.addCleanup(self.upstream.server_close)
            self.addCleanup(self.upstream.shutdown)
            # Exercise the shipped image with the same non-root/read-only constraints as Compose.
            container = subprocess.check_output([
                'docker', 'run', '-d', '--read-only', '--cap-drop=ALL',
                '--security-opt=no-new-privileges:true', '--tmpfs', '/tmp:rw,noexec,nosuid,size=16m',
                '--add-host=host.docker.internal:host-gateway', '-p', '127.0.0.1::8130',
                '-e', f'CONTROL_PLANE_URL=http://host.docker.internal:{self.upstream.server_port}',
                '-e', 'MCP_ALLOW_INTERNAL_HTTP=true', image,
            ], text=True).strip()
            self.addCleanup(subprocess.run, ['docker', 'rm', '-fv', container],
                stdout=subprocess.DEVNULL, check=True)
            details = json.loads(subprocess.check_output(['docker', 'inspect', container], text=True))[0]
            self.assertEqual(details['Config']['User'], '65532:65532')
            self.assertTrue(details['HostConfig']['ReadonlyRootfs'])
            port = details['NetworkSettings']['Ports']['8130/tcp'][0]['HostPort']
            self.url = f'http://127.0.0.1:{port}/mcp'
            async with httpx.AsyncClient(trust_env=False) as client:
                for _ in range(200):
                    try:
                        response = await client.get(self.url.replace('/mcp', '/healthz'))
                        if response.status_code == 200:
                            return
                    except httpx.TransportError:
                        pass
                    await asyncio.sleep(.05)
            self.fail('MCP_CONTAINER_NOT_READY')
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
        if os.environ.get('MCP_TEST_CONTAINER_IMAGE'):
            return
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
                self.assertEqual(len(listed.tools), 16)
                actions = {
                    'browser_navigate': {'environment_id': 'ses_owned', 'goal': 'Open the test page',
                        'state_cursor': '1:1:' + 'a' * 64, 'idempotency_key': 'direct-nav',
                        'url': 'https://example.com'},
                    'browser_snapshot': {'environment_id': 'ses_owned'},
                    'browser_find': {'environment_id': 'ses_owned', 'query': 'Continue', 'roles': ['button']},
                    'browser_inspect': {'environment_id': 'ses_owned', 'state_cursor': '1:1:' + 'a' * 64, 'element_ids': ['e1']},
                    'browser_act': {'environment_id': 'ses_owned', 'goal': 'Hover a test button',
                        'state_cursor': '1:1:' + 'a' * 64, 'idempotency_key': 'direct-act',
                        'actions': [{'toolId': 'HOVER_TARGET', 'targetRef': 'e1', 'targetRevision': 1}]},
                    'browser_get_action_task': {'environment_id': 'ses_owned', 'task_id': 'agt_owned'},
                    'browser_wait_action_task': {'environment_id': 'ses_owned', 'task_id': 'agt_owned'},
                    'browser_get_quota': {}, 'browser_list_environments': {},
                    'browser_get_environment': {'environment_id': 'ses_owned'},
                    'browser_create_environment': {'profile_id': 'profile-owned', 'idempotency_key': 'owned-create',
                        'display_name': 'Agent Runner', 'agent_policy': 'INTERACTIVE'},
                    'browser_start_environment': {'environment_id': 'ses_owned', 'idempotency_key': 'owned-start'},
                    'browser_stop_environment': {'environment_id': 'ses_owned', 'idempotency_key': 'owned-stop'},
                    'browser_delete_environment': {'environment_id': 'ses_owned', 'idempotency_key': 'owned-delete'},
                    'browser_get_operation': {'operation_id': 'op_owned'},
                    'browser_wait_operation': {'operation_id': 'op_owned'},
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

    async def test_wait_terminal_failure_timeout_and_validation(self):
        async with streamablehttp_client(self.url, headers={'Authorization': 'Bearer fixture'}) as (read, write, _):
            async with ClientSession(read, write) as session:
                await session.initialize()
                async def wait(**arguments):
                    return await session.call_tool('browser_wait_operation',
                        {'operation_id': 'op_owned', **arguments})
                for terminal in ('COMMITTED', 'ABORTED', 'TIMED_OUT'):
                    self.operation_states = ['ACTIVE', terminal]
                    result = await wait()
                    self.assertFalse(result.isError)
                    data = result.structuredContent
                    self.assertTrue(data['completed'])
                    self.assertFalse(data['waitTimedOut'])
                    self.assertEqual(data['succeeded'], terminal == 'COMMITTED')
                self.operation_states = ['UNKNOWN']
                result = await wait(timeout_seconds=1)
                self.assertFalse(result.isError)
                self.assertFalse(result.structuredContent['completed'])
                self.assertTrue(result.structuredContent['waitTimedOut'])
                for seconds in (0, 26):
                    self.assertTrue((await wait(timeout_seconds=seconds)).isError)
                self.assertTrue(all(method == 'GET' for method, _, _ in self.calls))

    async def test_wait_action_task_and_reason_propagation(self):
        async with streamablehttp_client(self.url, headers={'Authorization': 'Bearer fixture'}) as (read, write, _):
            async with ClientSession(read, write) as session:
                await session.initialize()
                async def wait(**arguments):
                    return await session.call_tool('browser_wait_action_task',
                        {'environment_id': 'ses_owned', 'task_id': 'agt_owned', **arguments})
                for settled in ('COMPLETED', 'FAILED', 'BLOCKED', 'WAITING_FOR_HUMAN'):
                    self.task_states = ['RUNNING', 'VERIFYING_OUTCOME', settled]
                    result = await wait()
                    self.assertFalse(result.isError)
                    data = result.structuredContent
                    self.assertTrue(data['completed'])
                    self.assertFalse(data['waitTimedOut'])
                    self.assertEqual(data['succeeded'], settled == 'COMPLETED')
                    self.assertEqual(data['state'], settled)
                self.task_states = ['RUNNING']
                result = await wait(timeout_seconds=1)
                self.assertFalse(result.isError)
                self.assertFalse(result.structuredContent['completed'])
                self.assertTrue(result.structuredContent['waitTimedOut'])
                self.assertEqual(result.structuredContent['state'], 'RUNNING')
                for seconds in (0, 26):
                    self.assertTrue((await wait(timeout_seconds=seconds)).isError)
                self.error_response = (422, {
                    'code': 'AGENT_BROWSER_PERCEPTION_REJECTED',
                    'details': {'reason': 'BROWSER_STATE_STALE'},
                })
                rejected = await session.call_tool('browser_snapshot', {'environment_id': 'ses_owned'})
                self.assertTrue(rejected.isError)
                self.assertIn('BROWSER_STATE_STALE', rejected.content[0].text)
                self.error_response = None
                self.assertTrue(all(method == 'GET' for method, _, _ in self.calls))
