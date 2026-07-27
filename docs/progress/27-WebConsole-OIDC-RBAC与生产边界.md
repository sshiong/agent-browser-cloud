# Web Console：OIDC、RBAC 与生产边界

> 状态：本切片已完成  
> 日期：2026-07-26  
> 验收入口：`make test-e2e`

## 本轮关闭的缺口

### 1. 前端生产身份闭环

- 引入 `oidc-client-ts`，使用 OIDC Authorization Code 流程恢复登录会话、处理
  `/auth/callback`、自动续期和退出登录。
- Access Token 只保存在浏览器 `sessionStorage` 管理的 OIDC User Store 中；API
  请求从当前运行时身份读取 Bearer Token，不再使用构建时静态
  `VITE_ACCESS_TOKEN`。
- 从可配置 Claim 读取 Tenant 与 Role，缺少租户或受支持角色时拒绝建立身份。
- 生产构建默认使用 OIDC；缺少 `VITE_OIDC_AUTHORITY` 或
  `VITE_OIDC_CLIENT_ID` 时明确 fail-closed，不自动退回本地 Header 身份。
- 本地开发仍支持显式 `VITE_AUTH_MODE=local`，用于无外部 IdP 的可重复集成测试。

### 2. 前后端 RBAC 对齐

- 建立 `TENANT_VIEWER`、`TENANT_OPERATOR`、`TENANT_ADMIN`、
  `SECURITY_ADMIN`、`PLATFORM_ADMIN` 前端角色模型。
- Viewer 可读取 Session、Profile、Proxy 和 Runtime，但看不到创建、启动、终止、
  Resync、Agent、HumanTakeover 等写操作。
- Agent、远程桌面、安全中心和设置增加路由 Gate；侧栏同时按角色过滤入口。
- 安全中心按后端权限拆分租户安全工作区与平台密钥工作区，避免单一角色加载无权限
  API 后产生 403。
- 前端 Gate 只改善用户体验；Control Plane 的方法级 RBAC 仍是最终授权边界。

### 3. Fixture 与响应式边界

- `Groups`、`Browser Node`、`Extensions` 在生产构建中不只隐藏导航，直接访问路由
  也会进入 404，避免把 Fixture 页面误认为生产数据。
- 增加跳转到主要内容的 Skip Link、导航语义与可访问名称。
- 390px 窄屏使用 64px 图标导航，压缩顶部操作区并隐藏非关键静态按钮；真实移动端
  截图已复核。

## 自动化验收

前端静态与单元验收：

```bash
pnpm --dir apps/web-console lint
pnpm --dir apps/web-console build
pnpm --dir apps/web-console test
```

结果：6 个测试文件、24 个测试全部通过。

真实 E2E 会先用完整本地管理员身份执行既有 Session、Profile、Proxy、Agent、
HumanTakeover 和 noVNC 流程，再构建一个生产模式 Viewer 控制台，验证：

1. Session 列表请求带 `TENANT_VIEWER` 身份；
2. 创建和控制入口不可见；
3. Agent、Remote Desktop、Security、Settings 入口不可见，直接访问写路由进入
   `unauthorized`；
4. Fixture 导航不可见，直接访问 Fixture 路由进入 404；
5. Profile 创建入口不可见；
6. 390×844 视口中主导航宽度不超过 65px；
7. 页面无浏览器 Console Error。

最终输出：

```text
WEB_CONSOLE_E2E_OK
WEB_CONSOLE_VIEWER_RBAC_OK
real_web_console_e2e=true
viewer_rbac_e2e=true
health={"status":"UP"}
```

## 仍未完成

1. 尚未接入一个真实企业 IdP 完成 Issuer Metadata、Redirect URI、Claim Mapping、
   MFA/ACR 和 RP-Initiated Logout 联调；当前完成的是标准客户端和生产配置边界。
2. Resource/Migration 已完成 PostgreSQL SSE、`Last-Event-ID` 和断线重放；Session
   State 与 Audit 仍以轮询为主，缺统一事件管理器和 Full Resync。
3. Nodes、Extensions 已接正式 API；Groups 仍缺正式后端领域模型和 API。
4. API Client 仍手写，缺 OpenAPI 生成、版本协商和 N/N-1 契约兼容测试。
5. Session 创建向导已覆盖 Extension，仍未覆盖 Persona、Agent Policy 等完整生产步骤。
6. 本轮覆盖移动窄屏与基础可访问语义；完整键盘、屏幕阅读器、对比度和多分辨率视觉
   回归仍需独立验收。
7. 全局搜索、通知、主题和用户偏好仍是明确禁用的占位，不计为已完成能力。
