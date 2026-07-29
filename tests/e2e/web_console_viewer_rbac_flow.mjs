import { createRequire } from "node:module";

const require = createRequire(
  new URL("../../apps/web-console/package.json", import.meta.url),
);
const { chromium, expect } = require("@playwright/test");

const baseUrl = process.env.WEB_CONSOLE_BASE_URL;
if (!baseUrl) throw new Error("WEB_CONSOLE_BASE_URL is required");

const browser = await chromium.launch({ headless: true });
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
const consoleErrors = [];
let sessionRequestHeaders;

page.on("console", (message) => {
  if (message.type() === "error") consoleErrors.push(message.text());
});
page.on("request", (request) => {
  if (
    request.method() === "GET" &&
    request.url().includes("/api/v1/sessions?")
  ) {
    sessionRequestHeaders = request.headers();
  }
});

try {
  await page.goto(`${baseUrl}/environments`);
  await page.waitForLoadState("networkidle");
  await expect(page.getByRole("heading", { name: "环境管理" })).toBeVisible();
  await expect(page.getByRole("button", { name: "新建环境" })).toHaveCount(0);
  await expect(page.getByRole("button", { name: "导入环境" })).toHaveCount(0);
  await expect(
    page.getByRole("button", { name: "新建浏览器环境" }),
  ).toHaveCount(0);
  await expect(page.getByRole("link", { name: "Agent 任务" })).toHaveCount(0);
  await expect(page.getByRole("link", { name: "远程桌面" })).toHaveCount(0);
  await expect(page.getByRole("link", { name: "安全中心" })).toHaveCount(0);
  await expect(page.getByRole("link", { name: "设置" })).toHaveCount(0);
  await expect(page.getByRole("link", { name: "分组与标签" })).toBeVisible();
  await expect(page.getByRole("link", { name: "Browser Node" })).toHaveCount(0);
  await expect(page.getByRole("link", { name: "扩展与应用" })).toHaveCount(0);
  await expect(page.getByRole("link", { name: "企业运营" })).toHaveCount(0);
  await page.getByRole("button", { name: /E2E Workspace View/ }).click();
  const savedViewsPanel = page.getByRole("region", {
    name: "环境 Saved Views",
  });
  await expect(savedViewsPanel).toBeVisible();
  await expect(
    savedViewsPanel.getByText(/E2E Workspace View/, { exact: false }),
  ).toBeVisible();
  await expect(
    savedViewsPanel.getByPlaceholder("视图名称，例如：新加坡运行环境"),
  ).toHaveCount(0);
  await expect(
    savedViewsPanel.getByRole("button", { name: /删除 E2E Workspace View/ }),
  ).toHaveCount(0);
  await savedViewsPanel
    .getByRole("button", { name: /E2E Workspace View/ })
    .click();

  if (sessionRequestHeaders?.["x-roles"] !== "TENANT_VIEWER") {
    throw new Error(
      `viewer API identity was not propagated: ${JSON.stringify(sessionRequestHeaders)}`,
    );
  }

  await page.goto(`${baseUrl}/automation/tasks`);
  await page.waitForURL("**/unauthorized");
  await expect(page.getByRole("heading", { name: "权限不足" })).toBeVisible();

  await page.goto(`${baseUrl}/enterprise`);
  await page.waitForURL("**/unauthorized");
  await expect(page.getByRole("heading", { name: "权限不足" })).toBeVisible();

  await page.goto(`${baseUrl}/groups`);
  await expect(page.getByRole("heading", { name: "分组与标签" })).toBeVisible();
  await expect(page.getByRole("button", { name: "新建分组" })).toHaveCount(0);
  await expect(
    page.getByRole("button", { name: "创建第一个分组" }),
  ).toHaveCount(0);

  await page.goto(`${baseUrl}/profiles`);
  await expect(
    page.getByRole("heading", { name: "Profile 存储" }),
  ).toBeVisible();
  await expect(page.getByRole("button", { name: "新建 Profile" })).toHaveCount(
    0,
  );
  await expect(
    page.getByRole("button", { name: "导入 Checkpoint" }),
  ).toHaveCount(0);

  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto(`${baseUrl}/environments`);
  await expect(page.getByRole("heading", { name: "环境管理" })).toBeVisible();
  const sidebarWidth = await page
    .getByRole("complementary", { name: "主导航" })
    .evaluate((element) => element.getBoundingClientRect().width);
  if (sidebarWidth > 69) {
    throw new Error(`mobile sidebar is too wide: ${sidebarWidth}px`);
  }
  await expect(page.getByRole("link", { name: "环境管理" })).toBeVisible();

  const screenshotPath = process.env.WEB_CONSOLE_VIEWER_SCREENSHOT;
  if (screenshotPath) {
    await page.screenshot({ path: screenshotPath, fullPage: true });
  }
} finally {
  await browser.close();
}

if (consoleErrors.length > 0) {
  throw new Error(`Browser console errors: ${consoleErrors.join("\n")}`);
}

console.log("WEB_CONSOLE_VIEWER_RBAC_OK");
