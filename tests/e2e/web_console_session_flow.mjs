import { createRequire } from "node:module";

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
page.on("console", (message) => {
  if (message.type() === "error") consoleErrors.push(message.text());
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
  await expect(
    page.getByText("接管输入屏障已建立", { exact: true }),
  ).toBeVisible();
  await page.getByRole("button", { name: "结束接管" }).click();
  await expect(page.getByText("NO CONTROL", { exact: true })).toBeVisible({
    timeout: 15_000,
  });

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
} finally {
  await browser.close();
}

if (consoleErrors.length > 0) {
  throw new Error(`Browser console errors: ${consoleErrors.join("\n")}`);
}

console.log(`WEB_CONSOLE_E2E_OK screenshot=${screenshotPath}`);
