#!/usr/bin/env python3
"""Official MCP client through Gateway, real Java/PG/Node/Helper; runtime CDP is a fixture."""
import asyncio
from contextlib import asynccontextmanager
from datetime import datetime, timedelta, timezone
import importlib.util
import json
import os
from pathlib import Path
import socket
import sys

import httpx
from mcp import ClientSession
from mcp.client.streamable_http import streamablehttp_client
import uvicorn

port, binding = sys.argv[1:]
assert port.isdecimal()
base = f'http://127.0.0.1:{port}/api/v1'
os.environ['CONTROL_PLANE_URL'] = base.removesuffix('/api/v1')

@asynccontextmanager
async def gateway():
    spec = importlib.util.spec_from_file_location('owned_mcp_gateway', Path('apps/mcp-gateway/gateway.py'))
    module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
    sock = socket.socket(); sock.bind(('127.0.0.1',0))
    server = uvicorn.Server(uvicorn.Config(module.app,log_level='critical',access_log=False))
    task = asyncio.create_task(server.serve(sockets=[sock]))
    try:
        for _ in range(100):
            if server.started: break
            await asyncio.sleep(.05)
        assert server.started, 'MCP_GATEWAY_START_TIMEOUT'
        yield f'http://127.0.0.1:{sock.getsockname()[1]}/mcp'
    finally:
        server.should_exit=True
        await asyncio.wait_for(task,10)
        sock.close()

def unpack(result):
    assert not result.isError, 'MCP_TOOL_REJECTED'
    return result.structuredContent or json.loads(result.content[0].text)

async def main():
    admin={'X-Tenant-Id':'tenant-integration','X-Actor-Id':'mcp-admin','X-Roles':'TENANT_ADMIN'}
    async with httpx.AsyncClient(base_url=base,headers=admin,trust_env=False,timeout=30) as http:
        async def management(path,body=None):
            r=await http.post(path,json=body);assert r.status_code==200, 'MCP_MANAGEMENT_FAILED';return r.json()
        config={'name':'Owned MCP Agent','maximumEnvironments':2,'maximumRunning':1,'expiresAt':(datetime.now(timezone.utc)+timedelta(hours=1)).isoformat()}
        issued=await management('/agent-connections',config)
        token=issued['accessToken'];connection=issued['connection']['connectionId']
        async def wait_state(session, wanted):
            for _ in range(120):
                r=await http.get('/sessions/'+session);assert r.status_code==200
                if r.json()['state']==wanted:return
                await asyncio.sleep(.25)
            raise AssertionError('MCP_SESSION_STATE_TIMEOUT_'+wanted)
        async with gateway() as url:
            async with streamablehttp_client(url,headers={'Authorization':'Bearer '+token}) as (read,write,_):
                async with ClientSession(read,write) as client:
                    await client.initialize()
                    assert len((await client.list_tools()).tools)==8
                    async def call(name,args=None):return unpack(await client.call_tool(name,args or {}))
                    sessions=[]
                    for n in range(2):
                        arguments={'profile_id':f'profile-mcp-owned-{n}','idempotency_key':f'create-{n}','proxy_binding_profile_id':binding}
                        created=await call('browser_create_environment',arguments)
                        sessions.append(created['sessionId'])
                        assert (await call('browser_create_environment',arguments))['sessionId']==sessions[-1]
                    assert len((await call('browser_list_environments'))['items'])==2
                    overflow=await client.call_tool('browser_create_environment',{'profile_id':'profile-overflow','idempotency_key':'overflow','proxy_binding_profile_id':binding})
                    assert overflow.isError
                    starts=await asyncio.gather(*(client.call_tool('browser_start_environment',{'environment_id':s,'idempotency_key':'start-'+str(n)}) for n,s in enumerate(sessions)))
                    assert sum(not r.isError for r in starts)==1, 'MCP_CONCURRENT_START_QUOTA_BYPASS'
                    index=next(n for n,r in enumerate(starts) if not r.isError)
                    running=sessions[index];stopped=sessions[1-index]
                    await wait_state(running,'RUNNING')
                    assert (await call('browser_get_quota'))['runningUsed']==1
                    operation=unpack(starts[index])['operationId']
                    assert (await call('browser_get_operation',{'operation_id':operation}))['operationId']==operation
                    await call('browser_stop_environment',{'environment_id':running,'idempotency_key':'stop'})
                    await wait_state(running,'HIBERNATED')
                    assert (await call('browser_get_quota'))['runningUsed']==0
                    deleted=await call('browser_delete_environment',{'environment_id':running,'idempotency_key':'delete'})
                    assert deleted['deletedCount']==1
                    assert (await call('browser_delete_environment',{'environment_id':running,'idempotency_key':'delete'}))['deletionId']==deleted['deletionId']
                    assert (await call('browser_get_quota'))['environmentsUsed']==1
                    other=await management('/agent-connections',{**config,'name':'Other connection'})
                    async with httpx.AsyncClient(base_url=base,headers={'Authorization':'Bearer '+other['accessToken']},trust_env=False) as outsider:
                        assert (await outsider.get('/agent-access/environments/'+stopped)).status_code==403
                        assert (await outsider.get('/agent-access/environments')).json()['items']==[]
        # Gateway shutdown preserves the stopped environment. Restart with the same credential.
        assert (await http.get('/sessions/'+stopped)).status_code==200
        async with gateway() as url:
            async with streamablehttp_client(url,headers={'Authorization':'Bearer '+token}) as (read,write,_):
                async with ClientSession(read,write) as client:
                    await client.initialize()
                    result=unpack(await client.call_tool('browser_list_environments',{}))
                    assert [e['sessionId'] for e in result['items']]==[stopped]
            await management('/agent-connections/'+connection+':revoke')
            async with httpx.AsyncClient(trust_env=False) as raw:
                assert (await raw.post(url,headers={'Authorization':'Bearer '+token},json={})).status_code==401
        listed=(await http.get('/agent-connections')).json()
        assert token not in json.dumps(listed)
    print('mcp_real_control_plane_lifecycle=true')
    print('mcp_parallel_running_quota_and_idempotency=true')
    print('mcp_connection_isolation_revoke_and_gateway_restart=true')

if __name__=='__main__':asyncio.run(main())
