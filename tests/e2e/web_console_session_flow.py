"""Real Control Plane browser flow for the Web Console.

Run with PostgreSQL, Redis, Browser Node, Control Plane, and Vite already
listening. The base URL can be overridden through WEB_CONSOLE_BASE_URL.
"""

import os
import time
from pathlib import Path

from playwright.sync_api import expect, sync_playwright


base_url = os.environ.get("WEB_CONSOLE_BASE_URL", "http://127.0.0.1:3000")
screenshot_path = Path(
    os.environ.get("WEB_CONSOLE_SCREENSHOT", "/tmp/agent-browser-cloud-session-flow.png")
)
run_suffix = str(int(time.time()))

with sync_playwright() as playwright:
    browser = playwright.chromium.launch(headless=True)
    page = browser.new_page(viewport={"width": 1440, "height": 900})
    console_errors: list[str] = []
    page.on(
        "console",
        lambda message: console_errors.append(message.text)
        if message.type == "error"
        else None,
    )

    page.goto(f"{base_url}/environments")
    page.wait_for_load_state("networkidle")
    expect(page.get_by_role("heading", name="环境管理")).to_be_visible()

    page.get_by_role("button", name="新建环境").first.click()
    expect(page.get_by_role("heading", name="新建浏览器环境")).to_be_visible()
    name_input = page.get_by_label("环境名称")
    name_input.fill(f"E2E Start {run_suffix}")
    page.get_by_label("Profile ID").fill("profile-e2e-start")
    page.get_by_label("部署区域").fill("local")
    expect(name_input).to_have_value(f"E2E Start {run_suffix}")
    page.get_by_role("button", name="下一步").click()
    expect(page.get_by_role("button", name="确认创建")).to_be_visible()
    page.get_by_role("button", name="确认创建").click()
    page.wait_for_url("**/environments/ses_*")
    expect(page.get_by_role("heading", name="Session 详情")).to_be_visible()
    expect(page.locator("main").get_by_text("已创建", exact=True).last).to_be_visible()

    page.get_by_role("button", name="启动 Session").click()
    expect(page.get_by_text("后端 Operation 正在执行，详情会每 2 秒同步一次。")).to_be_visible(
        timeout=10_000
    )

    page.goto(f"{base_url}/environments?create=1")
    page.wait_for_load_state("networkidle")
    expect(page.get_by_role("heading", name="新建浏览器环境")).to_be_visible()
    name_input = page.get_by_label("环境名称")
    name_input.fill(f"E2E Terminate {run_suffix}")
    page.get_by_label("Profile ID").fill("profile-e2e-terminate")
    page.get_by_label("部署区域").fill("local")
    expect(name_input).to_have_value(f"E2E Terminate {run_suffix}")
    page.get_by_role("button", name="下一步").click()
    expect(page.get_by_role("button", name="确认创建")).to_be_visible()
    page.get_by_role("button", name="确认创建").click()
    page.wait_for_url("**/environments/ses_*")
    expect(page.locator("main").get_by_text("已创建", exact=True).last).to_be_visible()

    page.get_by_role("button", name="终止", exact=True).click()
    expect(page.get_by_role("heading", name="终止 Session？")).to_be_visible()
    page.get_by_role("button", name="确认终止").click()
    expect(page.get_by_text("后端 Operation 正在执行，详情会每 2 秒同步一次。")).to_be_visible(
        timeout=10_000
    )

    page.screenshot(path=str(screenshot_path), full_page=True)
    browser.close()

if console_errors:
    raise AssertionError(f"Browser console errors: {console_errors}")

print(f"WEB_CONSOLE_E2E_OK screenshot={screenshot_path}")
