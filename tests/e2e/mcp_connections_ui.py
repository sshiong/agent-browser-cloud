"""Owned real Chrome UI test; API responses are explicit fixtures, no cloud claims."""
import json
import os
from pathlib import Path
import socket
import subprocess
import tempfile
import time
import urllib.request
from playwright.sync_api import sync_playwright, expect

with tempfile.TemporaryDirectory(prefix='agentbrowser-mcp-ui-') as tmp:
    with socket.socket() as sock:
        sock.bind(('127.0.0.1',0)); port=sock.getsockname()[1]
    env={**os.environ,'VITE_AUTH_MODE':'local','VITE_LOCAL_ROLES':'TENANT_ADMIN','VITE_ENABLE_FIXTURES':'false'}
    log=open(Path(tmp)/'vite.log','w')
    process=subprocess.Popen(['pnpm','--dir','apps/web-console','exec','vite','--host','127.0.0.1','--port',str(port),'--strictPort'],env=env,stdout=log,stderr=subprocess.STDOUT)
    state={'connections':[]}; credential='abc_mcp_UI_FIXTURE'
    def api(route):
        request=route.request;path=request.url.split('/api/v1',1)[1].split('?',1)[0]
        if path=='/agent-connections':
            if request.method=='POST':
                body=request.post_data_json
                assert body['maximumEnvironments']==20 and body['maximumRunning']==3 and len(body['allowedTools'])==8
                connection={**body,'connectionId':'acon_ui','subject':'owned-ui','enabled':True}
                state['connections']=[connection]
                route.fulfill(json={'connection':connection,'accessToken':credential})
            else:route.fulfill(json=state['connections'])
        elif path=='/agent-connections/acon_ui' and request.method=='PATCH':
            state['connections'][0].update(request.post_data_json);route.fulfill(json={'updated':True})
        elif path=='/agent-connections/acon_ui:revoke':
            state['connections'][0]['enabled']=False;route.fulfill(json={'revoked':True})
        elif path=='/workspace-settings':
            route.fulfill(json={'workspaceName':'Owned UI','defaultRuntimeBuildId':'runtime_local_chromium','defaultRegion':'local','defaultHumanTakeoverEnabled':True,'remoteDesktopControlBitrateLimitKbps':8000,'remoteDesktopControlFrameRateLimitFps':30,'remoteDesktopViewerBitrateLimitKbps':4000,'remoteDesktopViewerFrameRateLimitFps':15,'source':'WORKSPACE_OVERRIDE','version':1,'updatedAt':'2026-11-10T00:00:00Z','updatedBy':'owned-ui'})
        else:route.fulfill(json={'items':[],'total':0,'unreadCount':0})
    try:
        for _ in range(120):
            assert process.poll() is None,'OWNED_VITE_EXITED'
            try:
                urllib.request.urlopen(f'http://127.0.0.1:{port}',timeout=1);break
            except Exception:time.sleep(.25)
        with sync_playwright() as runtime:
            browser=runtime.chromium.launch(headless=True,executable_path=os.environ.get('CHROME_BIN','/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'))
            context=browser.new_context(viewport={'width':1440,'height':1000},service_workers='block')
            page=context.new_page();errors=[];page.on('pageerror',lambda e:errors.append(e))
            page.route('**/*',lambda r:r.continue_() if r.request.url.startswith(f'http://127.0.0.1:{port}/') else r.abort())
            page.route(f'http://127.0.0.1:{port}/api/v1/**',api)
            page.goto(f'http://127.0.0.1:{port}/settings')
            section=page.locator('#agent-connections');section.scroll_into_view_if_needed()
            expect(section.get_by_text('暂无 Agent 连接。')).to_be_visible()
            section.get_by_role('button',name='创建 Agent 连接',exact=True).click()
            expect(section.get_by_text(credential,exact=True)).to_be_visible()
            section.get_by_role('button',name='已保存，隐藏凭据').click()
            expect(section.get_by_text(credential,exact=True)).to_have_count(0)
            section.get_by_label('我的 Agent 环境总数',exact=True).fill('5')
            section.get_by_label('我的 Agent 运行上限',exact=True).fill('1')
            section.get_by_role('button',name='保存额度').click()
            expect(section.get_by_text('额度已保存。')).to_be_visible()
            assert state['connections'][0]['maximumRunning']==1
            section.get_by_role('button',name='撤销连接').click()
            expect(section.get_by_text('已撤销或过期',exact=True)).to_be_visible()
            page.reload();expect(page.get_by_text(credential,exact=True)).to_have_count(0)
            assert credential not in page.evaluate('JSON.stringify(localStorage)')
            assert not errors,'OWNED_UI_RUNTIME_ERROR'
            context.close();browser.close()
        print('mcp_connections_real_chrome_ui=true create=1 quota_update=1 revoke=1 credential_not_persisted=true')
    finally:
        process.terminate()
        try:process.wait(timeout=10)
        except subprocess.TimeoutExpired:process.kill();process.wait(timeout=5)
        log.close()
