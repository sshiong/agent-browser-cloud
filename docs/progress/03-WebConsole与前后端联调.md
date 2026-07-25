# Web Console：UI、真实 API 与端到端联调

> 本文记录 2026-07-23 对用户已有 UI 代码的审查、修复和续建结果。

## 审查发现

初始 UI 已具备 App Shell、侧边栏、总览、环境表格和多个模块页面，但存在以下关键问题：

- 所有主要业务页面直接读取 `src/mocks/data.ts`，与“正式项目必须连接真实后端”的提示词冲突。
- `/environments/:id` 仍渲染环境列表，没有真实详情页。
- 新建、启动、终止和侧边栏快捷按钮没有端到端行为。
- API Base URL 写死，且最初引用了当时尚未实现的 `/sessions/{id}/state`。
- 缺少统一 Loading、Empty、Error、Offline、Request ID 与 Operation 生命周期反馈。
- 用户新增依赖时移除了 ESLint、Prettier、Playwright、CSP 与 Referrer Policy。
- 创建抽屉在真实浏览器中出现 React 无限更新；关闭抽屉与详情导航存在竞态。
- 原 Session 页面仍保留内联样式并与新页面重复。

## 已完成

### 工程与设计基础

- 从两份 Prompt 提取并保存 `.impeccable.md` 设计上下文。
- 保留“Neo-Industrial Observatory / 新工业观测站”视觉方向。
- 恢复 ESLint、Prettier、Playwright、React Hook Form、Zod 和 Resolver。
- 恢复 CSP 与 Referrer Policy；删除第三方字体网络依赖。
- 增加 Reduced Motion、Focus Ring、图标按钮 aria-label 和离线 Banner。
- 页面路由改为 Lazy Chunk，生产构建核心入口 gzip 约 98 KiB。
- 删除旧 `pages/SessionsPage.tsx` 与 `pages/SessionPage.tsx`。

### 真实 API

- 支持 `VITE_API_BASE_URL`、`VITE_DEV_PROXY_TARGET` 和 `VITE_TENANT_ID`。
- API Client 支持 AbortSignal、结构化 Error 与 Request ID。
- Browser State 后端闭环完成后重新接入正式 `/sessions/{id}/state`，正确处理 204。
- 增加 TanStack Query Key、列表/详情 Query 与创建/启动/终止 Mutation。
- 总览由真实 Session 列表派生，不再显示固定成本、Node 或 Agent 数字。
- 环境列表使用服务端状态筛选、服务端分页和当前页搜索。
- 创建抽屉使用 React Hook Form + Zod，提交真实幂等请求。
- Session 详情显示状态、Node、Runtime、Epoch、Operation、时间线和能力接入状态。
- Start 与 Terminate 等高风险状态不做乐观成功；Terminate 有明确确认步骤。
- 活跃 Operation 期间每两秒刷新详情；Node Event 闭环后页面可自动观察到
  `RUNNING/TERMINATED` 终态，后续仍需用 SSE 替换轮询。
- Session `GET/List` 正式契约新增 `displayName`、`profileId`、`region` 和
  `resourceClass`。
- 新增受控 `SessionDescriptor` 查询投影：只从 metadata 白名单提取
  `displayName`，不会把任意 metadata 返回前端；缺失、空白或非法值回退到 Session ID。
- 环境列表现在显示名称、Session ID、Profile、租户、区域、资源等级、
  Runtime 和 Node，并支持按新增字段搜索。
- Session 详情页显示同一组权威配置和运行上下文，创建后可立即核对用户输入是否持久化。
- Session 详情页展示真实 Browser State：Document、URL、Quality、State/Target
  Revision、Context Epoch、Content Hash 以及前 12 个交互目标的 Role、Bounds、
  Visible 和 Enabled。
- Session 详情在 `RUNNING` 期间也持续同步权威 Session 状态，因此能发现突发 Crash；
  `RECOVERING` 明确显示写入冻结、替代 Runtime 与状态重采集过程，Recovery 熔断后的
  `FAILED` 显示 Circuit Open 与人工排查指引。

### Fixture 管理

- Groups、Node、Proxy、Runtime、Profile、Extension、Automation、Logs、Security 保留现有开发 Fixture。
- 开发环境显示醒目 Fixture 提示。
- 生产构建默认关闭 Fixture 内容，改为“后端接口尚未接入”状态。
- 可通过 `VITE_ENABLE_FIXTURES=true` 在受控环境显式开启。

## 测试

- API 单元测试：5 个，覆盖租户 Header、幂等创建、Start Operation、结构化错误与
  Browser State 204 空结果。
- Java 应用服务测试新增 Session 查询投影契约覆盖。
- 集成烟雾测试新增 `session_descriptor_visible=true`，验证新增字段从 PostgreSQL
  持久化到 List/Get API 的完整链路。
- TypeScript/Vite Build：通过。
- ESLint 与 Prettier Check：通过。
- npm Registry 高危依赖审计：无已知漏洞；已将存在 Critical 漏洞的 Vitest 2.1.x 升级为 3.2.7。
- 真实浏览器 E2E：通过。

E2E 使用真实 PostgreSQL、Redis、Java Control Plane、Rust Browser Node 和 Vite，验证：

1. 列表读取；
2. 创建 Session；
3. 打开详情；
4. Start Operation 经真实 Node Event 自动提交，页面显示“运行中”；
5. 第二个 Session 的 Termination Operation 自动提交，页面显示“已终止”；
6. 浏览器 Console 无错误。

测试入口：`make test-e2e`；启动器为 `tests/e2e/run.sh`，浏览器流程为
`tests/e2e/web_console_session_flow.mjs`。该入口会自动托管 PostgreSQL、Redis、
Browser Node、Control Plane 与 Vite，不依赖人工预启动服务。

## 尚未完成

| 缺口 | 原因/下一步 |
|---|---|
| 创建向导只有当前后端支持的字段 | Runtime、Proxy、Persona、Extension、Agent 策略需要各自正式 API 后再扩展到完整九步 |
| 实时事件仅用轮询 | 实现统一 SSE/WebSocket Manager，并校验 sequence/context_epoch/operation_epoch |
| OIDC/RBAC 未实现 | 生产前必须从 Principal 派生租户，不能信任 `VITE_TENANT_ID` |
| API Client 尚非 OpenAPI 自动生成 | 建立生成包并用适配层替换当前手写 Client |
| 全局搜索、通知、主题和用户菜单未实现 | 目前明确 Disabled，等待对应契约与设计步骤 |
| 其余模块仍是 Fixture | 按 Runtime → Profile → Proxy → Node → Logs/Security 的顺序接入 |
| 完整 E2E 尚未进入 GitHub Actions | 需要稳定的浏览器缓存、服务编排与独立 CI Job |
