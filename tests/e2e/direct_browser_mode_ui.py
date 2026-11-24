"""Playwright regression for the regular console. The API is an explicit fixture."""
import os
import re
import sys
from urllib.parse import urlsplit

from playwright.sync_api import expect, sync_playwright

base = sys.argv[1].rstrip("/")
session_id = "ses_1234567890abcdef"
session_path = f"/api/v1/sessions/{session_id}"
cursor = f"10:4:{'a' * 64}"
target = {
    "targetRef": "target:4:continue",
    "elementId": "e_continue",
    "name": "Continue",
    "role": "button",
    "visible": True,
    "enabled": True,
    "sensitive": False,
    "interactive": True,
    "inViewport": True,
    "occluded": False,
    "bounds": {"x": 15, "y": 20, "width": 80, "height": 30},
}
destination = {**target, "targetRef": "target:4:drop", "elementId": "e_drop",
               "name": "Drop area", "bounds": {"x": 150, "y": 120, "width": 80, "height": 30}}
state = {
    "sessionId": session_id, "url": "https://example.test",
    "title": "Fixture", "stateVersion": 10, "targetRevision": 4,
    "contextEpoch": 1, "stateHash": "a" * 64, "stateQuality": "COMPLETE",
    "freshness": "FRESH", "targets": [target, destination],
}
task = {
    "taskId": "agt_direct_fixture", "sessionId": session_id,
    "goal": "Operate the fixture", "state": "COMPLETED",
    "riskClass": "R1_LOW_RISK_CHANGE", "intentDecision": "ALLOWED",
    "confirmation": {}, "humanHandoff": {}, "stepExecution": {},
    "currentStep": 4, "totalSteps": 4, "replanCount": 0,
    "allowedDomains": ["example.test"], "executionResults": [], "securityEvents": [],
    "createdAt": "2026-11-24T00:00:00Z", "updatedAt": "2026-11-24T00:00:00Z",
    "plan": {"steps": [], "replanBudget": 0, "maxActions": 4},
}
writes = []
snapshots = []


def api(route):
    request = route.request
    path = urlsplit(request.url).path
    if path == "/api/v1/sessions":
        route.fulfill(json={"items": [{
            "sessionId": session_id, "displayName": "Fixture browser",
            "agentPolicy": "BALANCED", "state": "RUNNING",
        }], "total": 1})
    elif path == session_path + "/state":
        route.fulfill(json=state)
    elif path == session_path + "/agent-browser/snapshot":
        snapshots.append(path)
        route.fulfill(json={"state": state, "stateCursor": cursor})
    elif path == session_path + "/agent-browser/act":
        writes.append((request.post_data_json, request.headers["idempotency-key"]))
        if len(writes) == 2:
            route.fulfill(status=409, json={
                "code": "AGENT_BROWSER_ACTION_REJECTED",
                "message": "State changed", "details": {"reason": "STATE_CURSOR_STALE"},
            })
        else:
            route.fulfill(json=task)
    elif path.endswith("/" + task["taskId"]):
        route.fulfill(json=task)
    else:
        route.fulfill(json={"items": [], "total": 0, "unreadCount": 0})


with sync_playwright() as runtime:
    browser = runtime.chromium.launch(headless=True, executable_path=os.environ.get("CHROME_BIN"))
    page = browser.new_page(service_workers="block")
    errors = []
    page.on("pageerror", lambda error: errors.append(error))
    page.route(base + "/api/v1/**", api)
    page.goto(base + "/automation/tasks", wait_until="domcontentloaded")
    mode = page.get_by_label("执行模式")
    expect(mode).to_have_value("MANAGED")
    expect(page.get_by_label("授权域名")).to_be_visible()
    mode.select_option("DIRECT")
    expect(page.get_by_label("授权域名")).to_have_count(0)
    page.get_by_label("用户目标").fill(task["goal"])
    page.get_by_label("打开网址").fill("https://example.test/start")
    run = page.get_by_role("button", name="直接执行", exact=True)
    run.click()
    expect(page.get_by_label("打开网址")).to_have_value("")
    expect(page.get_by_text("COMPLETED", exact=True)).to_be_visible()
    assert writes[0][0] == {
        "goal": task["goal"], "expectedStateCursor": cursor,
        "executionMode": "DIRECT", "navigateUrl": "https://example.test/start", "actions": [],
    }

    page.get_by_role("button", name="添加结构化动作").click()
    action_type = page.get_by_label("动作 1 类型")
    expect(action_type.locator("option")).to_have_count(10)
    page.get_by_label("动作 1 目标").select_option(target["targetRef"])
    run.click()
    expect(page.get_by_role("alert").filter(has_text=re.compile("页面状态已变化"))).to_be_visible()
    expect(page.get_by_label("动作 1 目标")).to_have_value(target["targetRef"])
    assert len(writes) == 2
    run.click()
    expect(action_type).to_have_count(0)
    assert len(snapshots) == 3
    assert len({key for _, key in writes}) == 3
    assert writes[-1][0]["actions"] == [{
        "toolId": "CLICK_TARGET", "targetRef": target["elementId"],
        "targetRevision": state["targetRevision"],
    }]
    add = page.get_by_role("button", name="添加结构化动作")
    for index, kind in enumerate(("HOLD_TARGET", "DRAG_TARGET", "SWIPE_TARGET"), start=1):
        add.click()
        page.get_by_label(f"动作 {index} 类型").select_option(kind)
        page.get_by_label(f"动作 {index} 目标").select_option(target["targetRef"])
    page.get_by_label("动作 2 拖动终点").select_option(destination["targetRef"])
    page.get_by_label("动作 3 横向位移（像素）").fill("120")
    run.click()
    expect(action_type).to_have_count(0)
    binding = {"targetRef": target["elementId"], "targetRevision": state["targetRevision"]}
    assert writes[-1][0]["actions"] == [
        {"toolId": "DRAG_TARGET", **binding, "endTargetRef": target["elementId"], "durationMs": 600},
        {"toolId": "DRAG_TARGET", **binding, "endTargetRef": destination["elementId"], "durationMs": 300},
        {"toolId": "SWIPE_TARGET", **binding, "deltaX": 120, "deltaY": -120, "durationMs": 300},
    ]
    add.click()
    page.get_by_label("动作 1 类型").select_option("PRESS_KEY")
    page.get_by_label("动作 1 目标").select_option(target["targetRef"])
    page.get_by_label("动作 1 按键").select_option("Tab")
    add.click()
    page.get_by_label("动作 2 类型").select_option("WAIT_FOR")
    run.click()
    expect(action_type).to_have_count(0)
    assert writes[-1][0]["actions"] == [
        {"toolId": "PRESS_KEY", **binding, "key": "Tab"},
        {"toolId": "WAIT_FOR", "waitCondition": "STATE_STABLE", "timeoutMs": 5000},
    ]
    mode.select_option("MANAGED")
    expect(page.get_by_label("授权域名")).to_be_visible()
    expect(page.get_by_role("button", name="运行安全校验并生成计划")).to_be_visible()
    page.goto(f"{base}/automation/tasks?session={session_id}&mode=direct")
    expect(mode).to_have_value("DIRECT")
    expect(page.get_by_label("运行中的 Session")).to_have_value(session_id)
    assert not errors, errors
    browser.close()
print("console_direct_mode=true navigation=true click=true stale_recovery=true gestures=true")
