"""Owned loopback UI contract test; API/SSE responses are synthetic, never a cloud proof.

Install the optional recording-hold-ui-requirements.txt in a private venv. Start Vite with
VITE_AUTH_MODE=local VITE_LOCAL_ROLES=TENANT_ADMIN VITE_ENABLE_FIXTURES=false and an owned
loopback port, then run this script with that port. CHROME_BIN may select an existing browser;
otherwise Playwright's installed Chromium is used. RECORDING_HOLD_UI_SCREENSHOT is optional.
"""
import copy
import json
import os
import sys
from playwright.sync_api import sync_playwright, expect

port = int(sys.argv[1])
assert 1024 <= port <= 65535
policy = dict(tenantId='tenant-local',dataClass='REMOTE_DESKTOP_RECORDING',retentionDays=31,
    legalHold=True,residencyRegion='primary',updatedBy='owned-test',updatedAt='2026-10-23T00:00:00Z',
    cloudHoldRequired=True,recordingCloudHold=dict(status='PENDING',desiredMode='ON',pendingRecordings=1,
      unknownRecordings=0,verifiedOnRecordings=0,verifiedOffRecordings=0,reconciliationExhausted=False))
state = dict(policy=policy,cursor=1,puts=[])
base = dict(validations=[],costRates=[],mediaQuota=None,errorBudget=None,releaseFreeze=None,slaExclusions=[],
    licenseInventory=[],regions=[],recoveryGameDays=[],recoveryGameDayTrends=[],recoveryGameDayRemediations=[],
    latestCompliance=None,generatedAt='2026-10-23T00:00:00Z')

def api(route):
    request = route.request
    path = request.url.split('/api/v1',1)[1].split('?',1)[0]
    if path == '/enterprise/overview':
        route.fulfill(json={**base,'retentionPolicies':[copy.deepcopy(state['policy'])]})
    elif path == '/enterprise/overview/event-stream':
        cursor=state['cursor']
        control=dict(cursor=cursor,resetRequired=False,connectedAt='2026-10-23T00:00:00Z')
        route.fulfill(content_type='text/event-stream',body=f'id: {cursor}\nevent: enterprise-overview-stream-ready\ndata: {json.dumps(control)}\n\n')
    elif path == '/enterprise/retention-policies' and request.method == 'PUT':
        data=request.post_data_json
        assert data['retentionDays']==31 and data['residencyRegion']=='primary'
        assert 'tenantId' not in data
        state['puts'].append(data)
        state['policy'].update(data)
        state['policy']['updatedAt']='2026-10-23T00:00:0'+str(len(state['puts']))+'Z'
        state['policy']['recordingCloudHold']['desiredMode']='ON' if data['legalHold'] else 'OFF'
        if not data['cloudHoldRequired']:
            state['policy']['recordingCloudHold']['status']='LOGICAL_ONLY'
        route.fulfill(json=copy.deepcopy(state['policy']))
    else:
        route.fulfill(json=dict(items=[],total=0,unreadCount=0))

with sync_playwright() as runtime:
    browser=runtime.chromium.launch(headless=True,executable_path=os.environ.get('CHROME_BIN'))
    context=browser.new_context(viewport={'width':1440,'height':1000}, service_workers='block')
    page=context.new_page()
    errors=[]
    page.on('pageerror',lambda error: errors.append(error))
    page.route('**/*', lambda route: route.continue_() if route.request.url.startswith(f'http://127.0.0.1:{port}/') else route.abort())
    page.route(f'http://127.0.0.1:{port}/api/v1/**',api)
    page.goto(f'http://127.0.0.1:{port}/enterprise')
    page.wait_for_load_state('networkidle')
    assert '编辑期望策略' in page.locator('summary').all_text_contents()
    title=page.get_by_text('录制保留策略',exact=True)
    card=title.locator('../../..')
    expect(page.get_by_text('等待云端回执',exact=True)).to_be_visible()
    page.get_by_text('编辑期望策略',exact=True).click()
    cloud=page.get_by_label('云端对象版本保全',exact=True)
    expect(cloud).to_be_disabled()
    page.get_by_label('期望保全（解除后仍需等待云端证明）',exact=True).uncheck()
    page.get_by_role('button',name='保存期望策略',exact=True).click()
    expect(card).to_contain_text('期望保全：解除')
    assert state['puts'][0]['legalHold'] is False and state['puts'][0]['cloudHoldRequired'] is True
    expect(page.get_by_text('云端保全已验证（托管录制）',exact=True)).to_have_count(0)
    state['policy']['recordingCloudHold'].update(status='UNKNOWN',pendingRecordings=0,unknownRecordings=1,reconciliationExhausted=True)
    state['cursor']=2
    expect(page.get_by_text('云端结果未知',exact=True)).to_be_visible(timeout=15000)
    expect(card).to_contain_text('保全仍保留')
    if os.environ.get('RECORDING_HOLD_UI_SCREENSHOT'):
        card.screenshot(path=os.environ['RECORDING_HOLD_UI_SCREENSHOT'])
    state['policy']['recordingCloudHold'].update(status='VERIFIED',unknownRecordings=0,verifiedOffRecordings=1,reconciliationExhausted=False)
    state['cursor']=3
    expect(page.get_by_text('已验证解除（托管录制）',exact=True)).to_be_visible(timeout=15000)
    if card.locator('details').get_attribute('open') is None:
        page.get_by_text('编辑期望策略',exact=True).click()
    expect(cloud).to_be_enabled()
    cloud.uncheck()
    page.get_by_role('button',name='保存期望策略',exact=True).click()
    expect(page.get_by_text('仅逻辑保全',exact=True)).to_be_visible()
    assert len(state['puts'])==2 and state['puts'][1]['cloudHoldRequired'] is False
    assert not errors
    browser.close()
print('recording_hold_browser_owned_fixture=true')
