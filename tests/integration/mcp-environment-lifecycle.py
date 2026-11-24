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
            r=await http.post(path,json=body);assert r.status_code==200, 'MCP_MANAGEMENT_FAILED'
            if path == '/agent-connections': assert 'no-store' in r.headers.get('cache-control', '').lower().split(', ')
            return r.json()
        config={'name':'Owned MCP Agent','maximumEnvironments':2,'maximumRunning':1,'expiresAt':(datetime.now(timezone.utc)+timedelta(hours=1)).isoformat()}
        default=await management('/agent-connections',{**config,'name':'Default scope connection'})
        assert 'direct' not in default['connection']['allowedTools']
        issued=await management('/agent-connections',{**config,'allowedTools':[
            'quota','list','get','create','start','stop','delete','operation','direct']})
        token=issued['accessToken'];connection=issued['connection']['connectionId']
        assert 'direct' in issued['connection']['allowedTools']
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
                    assert len((await client.list_tools()).tools)==13
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
                    async with httpx.AsyncClient(base_url=base, headers={
                        'Authorization':'Bearer '+default['accessToken']}, trust_env=False) as limited:
                        response=await limited.get(f'/agent-access/environments/{running}/browser/snapshot')
                        assert response.status_code==403 and response.json()['code']=='AGENT_TOOL_FORBIDDEN'
                    await management('/agent-connections/'+default['connection']['connectionId']+':revoke')
                    for _ in range(80):
                        snapshot_result=await client.call_tool('browser_snapshot',{'environment_id':running})
                        if not snapshot_result.isError:
                            snapshot=unpack(snapshot_result)
                            targets=[t for t in snapshot['state']['targets']
                                     if t['role']=='button' and t['visible'] and t['interactive']
                                     and t['enabled'] and t['inViewport'] and not t['occluded']
                                     and not t['sensitive']]
                            if targets: break
                        await asyncio.sleep(.25)
                    else: raise AssertionError('MCP_DIRECT_SNAPSHOT_NOT_READY')
                    state = snapshot['state']
                    source = targets[0]['elementId']
                    destination = next(t['elementId'] for t in state['targets']
                                       if t['role'] == 'textbox' and not t['sensitive']
                                       and t['interactive'] and t['inViewport'] and not t['occluded'])
                    target_binding = {'targetRef': source, 'targetRevision': state['targetRevision']}
                    gestures = [
                        {'toolId': 'DRAG_TARGET', **target_binding, 'endTargetRef': source, 'durationMs': 180},
                        {'toolId': 'DRAG_TARGET', **target_binding, 'endTargetRef': destination, 'durationMs': 160},
                        {'toolId': 'SWIPE_TARGET', **target_binding, 'deltaX': 100, 'deltaY': 50, 'durationMs': 160},
                    ]
                    action=await call('browser_act',{'environment_id':running,
                        'goal':'Exercise the demo page pointer controls','state_cursor':snapshot['stateCursor'],
                        'idempotency_key':'direct-gestures','actions':gestures})
                    for _ in range(80):
                        action=await call('browser_get_action_task',{'environment_id':running,
                            'task_id':action['taskId']})
                        if action['state']=='COMPLETED': break
                        assert action['state']=='RUNNING', 'MCP_DIRECT_ACTION_FAILED_'+str(action.get('lastError'))
                        await asyncio.sleep(.25)
                    else: raise AssertionError('MCP_DIRECT_ACTION_TIMEOUT')
                    batch = next(r['output'] for r in action['executionResults'] if r['toolId']=='EXECUTE_ACTIONS')
                    assert batch['completedActions'] == len(gestures)
                    assert all(result['status'] == 'SUCCEEDED' for result in batch['actions'])
                    print('mcp_direct_action_and_scope=true hold=true drag=true swipe=true')
                    assert (await call('browser_get_quota'))['runningUsed']==1
                    operation=unpack(starts[index])['operationId']
                    assert (await call('browser_get_operation',{'operation_id':operation}))['operationId']==operation
                    waited=await call('browser_wait_operation',{'operation_id':operation})
                    assert waited['completed'] and waited['succeeded'] and not waited['waitTimedOut']
                    await call('browser_stop_environment',{'environment_id':running,'idempotency_key':'stop'})
                    await wait_state(running,'HIBERNATED')
                    assert (await call('browser_get_quota'))['runningUsed']==0
                    deleted=await call('browser_delete_environment',{'environment_id':running,'idempotency_key':'delete'})
                    assert deleted['deletedCount']==1
                    assert (await call('browser_delete_environment',{'environment_id':running,'idempotency_key':'delete'}))['deletionId']==deleted['deletionId']
                    assert (await call('browser_get_quota'))['environmentsUsed']==1
                    other=await management('/agent-connections',{**config,'name':'Other connection',
                        'allowedTools':['get','list','direct']})
                    async with httpx.AsyncClient(base_url=base,headers={'Authorization':'Bearer '+other['accessToken']},trust_env=False) as outsider:
                        assert (await outsider.get('/agent-access/environments/'+stopped)).status_code==403
                        assert (await outsider.get('/agent-access/environments')).json()['items']==[]
                        assert (await outsider.get(f'/agent-access/environments/{stopped}/browser/snapshot')).status_code==403
                        assert (await outsider.get(f'/agent-access/environments/{stopped}/browser/tasks/agt_other')).status_code==403
                        assert (await outsider.post(f'/agent-access/environments/{stopped}/browser/act',
                            headers={'Idempotency-Key':'unowned-act'},json={'goal':'Open test page',
                            'expectedStateCursor':'1:1:'+'a'*64,'actions':[],
                            'navigateUrl':'https://example.com'})).status_code==403
                    await management('/agent-connections/'+other['connection']['connectionId']+':revoke')
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
