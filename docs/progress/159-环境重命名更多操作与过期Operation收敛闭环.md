# 环境重命名、更多操作与过期 Operation 收敛闭环

> 日期：2026-09-02  
> 状态：仓库内实现、本机真实运行与 GitHub Workflow 验收均已通过。
> 实现提交：`29bbc6b fix: make environment operations usable`

## 问题与根因

个人 OrbStack 环境暴露了两个直接影响使用的问题：环境列表的三点按钮被前端硬编码为
`disabled`，服务端也没有正式重命名契约；历史 START/TERMINATE Operation 在没有 durable
workflow 且超过 deadline 时不会被扫描器收敛，因为查询错误地只选择
`RESOURCE_ADJUSTMENT`，页面因此长期显示“启动中”。

## 实现

- 新增 `PATCH /api/v1/sessions/{sessionId}` 与 `UpdateSessionRequest`。服务端要求
  `OPERATE` 权限并校验 Tenant，使用行锁更新 PostgreSQL `display_name`，保留 Session
  State、Runtime、Profile、Operation 和既有 Metadata；提交 `SESSION_METADATA/RENAME`
  审计事件。
- 环境列表三点菜单改为可操作的 Radix Dropdown，提供详情和重命名 Dialog；Viewer 不展示
  Mutation，提交中、防重复、错误提示和 Query 失效刷新由 Web/Tauri 共用实现承担。
- 无 workflow 的 ACTIVE Operation deadline 扫描覆盖全部 Operation mode，不再只处理资源
  调整。已有 durable workflow 继续由 Workflow reconciler 负责，避免两个恢复器争用同一
  Operation。
- OpenAPI 与 TypeScript/Python/Go/Java SDK 同步，公开基线更新为
  **238 Operations / 317 Schemas**。
- Integration 增加重命名成功、状态不变、Viewer 拒绝、跨 Tenant 拒绝和单条审计证据；
  Recovery GameDay recovery-only claim 对合法的短暂 `204 No Content` 使用最多 5 秒有界
  重试，防止数据库/应用时钟边界造成测试竞态。

## 验证证据

- Control Plane 486 项、Rust Workspace、Web 121 项、Worker/Provider 的完整 `make test`
  通过；完整 lint/build、Desktop test/lint/unsigned build、四 SDK 和供应链 Gate 通过。
- Kubernetes Operator 17 项、50k Coordinator Capacity、N/N−1 迁移 Gate 通过；N/N−1
  evidence hash 为
  `8577ad98d4e2fc5e22a626dfa03fc0642680137abed1c0ea854cc16ccdee8c5e`。
- 完整 PostgreSQL/Redis/MinIO/mTLS/真实 Chromium Integration 通过并输出
  `session_rename=true`；高级 Action、Dialog、Evaluate、Screenshot、File、Challenge、
  Clipboard Bridge、Recovery GameDay 和 Enterprise Overview 既有标记继续通过。
- 真实 OrbStack Compose 中，历史超期 START/TERMINATE 自动收敛，不再长期“启动中”；真实
  API 重命名、读取和恢复原名称通过。
- Headless Chrome 对 `http://localhost:3000/environments` 完成三点菜单打开、重命名、列表
  回读和恢复原名称，浏览器控制台无错误。页面包含持久 SSE，故自动化以表格稳定条件等待，
  不使用永远无法满足的 `networkidle`。
- GitHub `ci` run `33588990497` 已通过 Verify、供应链、完整 Integration、Object
  Storage/Recording GameDay 与 Kubernetes Operator E2E；`desktop` run `33588990467` 的
  Windows/macOS 均通过。

## 保留边界

本切片只关闭列表菜单可用性、环境重命名和无 workflow 超期 Operation 收敛。环境删除、复制、
导出等尚未实现的菜单动作不得写成已完成；真实客户数据仍受 V16 全量生产 Gate 限制。
