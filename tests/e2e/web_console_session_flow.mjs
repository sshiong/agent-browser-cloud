import { createRequire } from "node:module";
import { existsSync, readFileSync } from "node:fs";

const require = createRequire(
  new URL("../../apps/web-console/package.json", import.meta.url),
);
const { chromium, expect } = require("@playwright/test");

const baseUrl = process.env.WEB_CONSOLE_BASE_URL ?? "http://127.0.0.1:3000";
const screenshotPath =
  process.env.WEB_CONSOLE_SCREENSHOT ??
  "/tmp/agent-browser-cloud-session-flow.png";
const runSuffix = String(Date.now());

const browser = await chromium.launch({ headless: true });
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
const consoleErrors = [];
const redactTicket = (value) =>
  value.replace(/ticket=[^'"\s]+/g, "ticket=[REDACTED]");
page.on("console", (message) => {
  if (message.type() === "error") {
    consoleErrors.push(redactTicket(message.text()));
  }
});

try {
  await page.goto(`${baseUrl}/environments`);
  await page.waitForLoadState("networkidle");
  await expect(page.getByRole("heading", { name: "环境管理" })).toBeVisible();

  await page.getByRole("button", { name: "新建环境" }).first().click();
  await expect(
    page.getByRole("heading", { name: "新建浏览器环境" }),
  ).toBeVisible();
  const startName = `E2E Start ${runSuffix}`;
  const nameInput = page.getByLabel("环境名称");
  await nameInput.fill(startName);
  await page.getByLabel("Profile ID").fill("profile-e2e-start");
  await page.getByLabel("部署区域").fill("local");
  await expect(nameInput).toHaveValue(startName);
  await page.getByRole("button", { name: "下一步" }).click();
  await expect(page.getByRole("button", { name: "确认创建" })).toBeVisible();
  await page.getByRole("button", { name: "确认创建" }).click();
  await page.waitForURL("**/environments/ses_*");
  const startSessionId = new URL(page.url()).pathname.split("/").at(-1);
  if (!startSessionId?.startsWith("ses_")) {
    throw new Error("created Session ID is missing from the detail URL");
  }
  await expect(
    page.getByRole("heading", { name: "Session 详情" }),
  ).toBeVisible();
  await expect(
    page.locator("main").getByRole("heading", { name: startName, exact: true }),
  ).toBeVisible();
  await expect(
    page.locator("main").getByText("已创建", { exact: true }).last(),
  ).toBeVisible();

  await page.getByRole("button", { name: "启动 Session" }).click();
  await expect(
    page.locator("main").getByText("运行中", { exact: true }).last(),
  ).toBeVisible({ timeout: 15_000 });
  await expect(
    page.getByRole("heading", { name: "Browser State", exact: true }),
  ).toBeVisible();
  await expect(
    page.getByText("Browser Cloud Test Page", { exact: true }),
  ).toBeVisible({ timeout: 15_000 });
  await expect(page.getByText("Run integration", { exact: true })).toBeVisible();
  await expect(page.getByText("COMPLETE", { exact: true })).toBeVisible();

  await page.getByRole("button", { name: "人工接管" }).click();
  await page.waitForURL("**/remote-desktop?session=ses_*");
  await expect(page.getByText("CONTROL ACQUIRED", { exact: true })).toBeVisible({
    timeout: 15_000,
  });
  await expect(page.getByText("RFB LIVE", { exact: true })).toBeVisible({
    timeout: 15_000,
  });
  const remoteCanvas = page.getByLabel("实时远程桌面画面").locator("canvas");
  await expect(remoteCanvas).toBeVisible();
  await remoteCanvas.click({ position: { x: 96, y: 72 } });
  await page.keyboard.press("A");
  const vncEventLog = process.env.VNC_EVENT_LOG;
  if (!vncEventLog) throw new Error("VNC_EVENT_LOG is required");
  let inputLoopClosed = false;
  for (let attempt = 0; attempt < 40; attempt += 1) {
    if (existsSync(vncEventLog)) {
      const events = readFileSync(vncEventLog, "utf8");
      inputLoopClosed =
        events.includes('"type":"frame"') &&
        events.includes('"type":"pointer"') &&
        events.includes('"type":"key"');
      if (inputLoopClosed) break;
    }
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
  if (!inputLoopClosed) {
    throw new Error("noVNC pixel/input loop did not reach the fake RFB server");
  }
  await page.getByRole("button", { name: "结束接管" }).click();
  await expect(page.getByText("NO CONTROL", { exact: true })).toBeVisible({
    timeout: 15_000,
  });

  await page.getByRole("link", { name: "Session", exact: true }).click();
  await expect(page.getByRole("button", { name: "人工接管" })).toBeEnabled({
    timeout: 15_000,
  });
  await page.getByRole("button", { name: "人工接管" }).click();
  await page.waitForURL("**/remote-desktop?session=ses_*");
  await expect(page.getByText("RFB LIVE", { exact: true })).toBeVisible({
    timeout: 15_000,
  });
  const disconnectCanvas = page
    .getByLabel("实时远程桌面画面")
    .locator("canvas");
  await disconnectCanvas.click({ position: { x: 64, y: 48 } });
  await page.keyboard.down("Shift");
  let lostKeyDownObserved = false;
  for (let attempt = 0; attempt < 40; attempt += 1) {
    if (existsSync(vncEventLog)) {
      const events = readFileSync(vncEventLog, "utf8");
      lostKeyDownObserved =
        events.includes('"type":"key","down":true,"keysym":65505') ||
        events.includes('"type":"key","down":true,"keysym":65506');
      if (lostKeyDownObserved) break;
    }
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
  if (!lostKeyDownObserved) {
    throw new Error("noVNC did not forward the intentionally unpaired Shift KeyDown");
  }
  const releaseCountBeforeDisconnect = (
    readFileSync(vncEventLog, "utf8").match(/"type":"release"/g) ?? []
  ).length;
  await page.goto(`${baseUrl}/environments/${startSessionId}`);
  await expect(page.getByRole("button", { name: "人工接管" })).toBeEnabled({
    timeout: 15_000,
  });
  let disconnectReleaseObserved = false;
  for (let attempt = 0; attempt < 60; attempt += 1) {
    const events = readFileSync(vncEventLog, "utf8");
    const releaseCount = (events.match(/"type":"release"/g) ?? []).length;
    if (releaseCount > releaseCountBeforeDisconnect) {
      disconnectReleaseObserved = true;
      break;
    }
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
  await page.keyboard.up("Shift");
  if (!disconnectReleaseObserved) {
    throw new Error("gateway disconnect did not execute the x11 all-keys-up barrier");
  }

  await page.goto(`${baseUrl}/environments?create=1`);
  await page.waitForLoadState("networkidle");
  await expect(
    page.getByRole("heading", { name: "新建浏览器环境" }),
  ).toBeVisible();
  const terminateName = `E2E Terminate ${runSuffix}`;
  const terminateNameInput = page.getByLabel("环境名称");
  await terminateNameInput.fill(terminateName);
  await page.getByLabel("Profile ID").fill("profile-e2e-terminate");
  await page.getByLabel("部署区域").fill("local");
  await expect(terminateNameInput).toHaveValue(terminateName);
  await page.getByRole("button", { name: "下一步" }).click();
  await expect(page.getByRole("button", { name: "确认创建" })).toBeVisible();
  await page.getByRole("button", { name: "确认创建" }).click();
  await page.waitForURL("**/environments/ses_*");
  await expect(
    page
      .locator("main")
      .getByRole("heading", { name: terminateName, exact: true }),
  ).toBeVisible();
  await expect(
    page.locator("main").getByText("已创建", { exact: true }).last(),
  ).toBeVisible();

  await page.getByRole("button", { name: "终止", exact: true }).click();
  await expect(
    page.getByRole("heading", { name: "终止 Session？" }),
  ).toBeVisible();
  await page.getByRole("button", { name: "确认终止" }).click();
  await expect(
    page.locator("main").getByText("已终止", { exact: true }).last(),
  ).toBeVisible({ timeout: 15_000 });

  await page.screenshot({ path: screenshotPath, fullPage: true });
} catch (error) {
  console.error(
    `E2E_FAILURE url=${page.url()} buttons=${JSON.stringify(
      await page.getByRole("button").allTextContents().catch(() => []),
    )} consoleErrors=${JSON.stringify(consoleErrors)}`,
  );
  await page
    .screenshot({ path: `${screenshotPath}.failure.png`, fullPage: true })
    .catch(() => undefined);
  throw error;
} finally {
  await browser.close();
}

if (consoleErrors.length > 0) {
  throw new Error(`Browser console errors: ${consoleErrors.join("\n")}`);
}

console.log(`WEB_CONSOLE_E2E_OK screenshot=${screenshotPath}`);
