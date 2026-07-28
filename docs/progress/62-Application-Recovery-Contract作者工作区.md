# Application Recovery Contract 作者工作区

> 完成日期：2026-07-28
> 状态：正式 API Client、版本化作者 UI、前端边界校验、错误状态与浏览器验收已完成

## 本轮关闭的缺口

此前 Application Recovery Contract 已有 PostgreSQL 权威模型、Admin API、版本 CAS、
Session 不可变绑定、迁移 Ready Gate 和有界自动恢复动作，但管理员只能直接调用 API
维护契约。

本轮在“扩展与应用”正式页面增加“应用恢复契约”工作区，继续复用现有真实 API：

```text
GET /api/v1/applications/recovery-contracts
PUT /api/v1/applications/{applicationId}/recovery-contract
```

没有增加生产 Mock、`localStorage`、JSON 文件或前端伪造写入结果。

## 信息架构与交互

- “扩展与应用”页面使用可深链的 `view=profiles|recovery` 工作区导航；
- 桌面视口使用契约索引与编辑器双栏，窄屏按索引→编辑器自然堆叠；
- 契约索引显示 Application ID、版本、启用状态、恢复动作、预算和 Origin；
- 编辑器支持新建、发布新版本、启停和 Depth Limited 策略；
- 支持 Expected Origins、Ready/Login Route Prefix；
- 支持 Ready、Login、Permission Denied、Account Mismatch 四类语义 Target；
- 支持 Required Extension IDs、受信 Extension 重启目标；
- 支持 `NONE`、Reload、忽略缓存刷新、受限导航和 `RESTART_EXTENSION`；
- 明确显示“禁止租户 JavaScript、正则表达式和任意 CDP Method”的安全边界。

## 一致性与失败处理

- 新建提交 `expectedVersion=0`，编辑提交当前持久版本；
- 409 版本冲突明确提示，不覆盖其他管理员发布的新版本；
- 保存成功后使正式契约查询失效并读取服务端返回版本；
- 写入失败显示真实 API Message 和 Request ID；
- 表单按后端同一边界校验：
  - 1—16 个仅含 Scheme/Host/Port 的 `http/https` Origin；
  - Route 必须以 `/` 开头，不能包含 `..`、Query 或 Fragment；
  - Target Role/Name 的格式与数量上限；
  - Extension ID 格式、去重和排序；
  - `NONE ↔ 0`、有动作 ↔ 非零预算；
  - `RESTART_EXTENSION` 目标必须是 Required Extensions 中的真实 Chromium ID。

## 共享边界

- API 类型、Query/Mutation 和作者组件都位于共享 React Web Console；
- Tauri 2 继续复用同一页面、API Client、权限和错误状态；
- 页面路由保持 Admin 角色 Gate，服务端仍再次执行 Admin RBAC 和 Tenant 隔离；
- 前端只编辑声明式契约，不直接执行恢复动作或节点命令。

## 验收证据

- Session API 单测验证正式 PUT 路径、Tenant Header 和 `expectedVersion` 请求体；
- 表单单测验证列表去重排序、Target 规范化和 Origin/Route/Chromium ID 边界；
- Web ESLint 0 warning；
- Vitest：13 个测试文件、42 项测试通过；
- TypeScript 与 Vite production build 通过，`ExtensionsPage` 独立路由块约 27.72 kB；
- Playwright 真实浏览器验收：
  - 1440×900 双栏布局；
  - 390×844 `scrollWidth == clientWidth == 390`，无横向溢出；
  - 非法带 Path Origin 被阻止并显示错误；
  - 合法新建真实发出 PUT，请求体为 `expectedVersion: 0` 且未携带无关
    `recoveryExtensionId`；
  - 浏览器 Console 0 error。

## 明确未完成

1. Contract 双人审批、发布状态与变更审计事件；
2. 版本差异预览、回滚到历史版本和独立 Business Recovery 事件流；
3. 支付、账号安全、SPA/Form、关键业务事务等站点 Adapter/SDK 实际接入；
4. Provider/API 级账号、权限和业务实体恢复证明；
5. 真实双 Browser Node、Object Storage、网络分区和目标云长期验收。
