# Profile 网站 Session Health 与 Reauth 闭环

日期：2026-09-16
范围：用户问题 A18；网站登录健康独立于 Profile 技术恢复状态。

## 问题

`TECHNICAL_READY` 只证明 Profile Checkpoint 可以恢复，不能证明网站 Cookie/Session 仍被服务端
接受。此前 Profile 列表只有恢复来源，容易把“文件恢复成功”误读为“仍然登录”。

## 实现

- V123 新增 `profile_site_session_health`，按 Tenant、Profile、规范化网站 Origin 唯一保存状态；
  不保存 Cookie、Token、页面正文或 Browser State JSON。
- 状态只由带 Context/State 围栏的 Business Recovery 验证产生：
  - `READY / READY_WITH_WARNING` → `HEALTHY`；
  - `LOGIN_REQUIRED` → `REAUTH_REQUIRED`；
  - `PERMISSION_CHANGED / ACCOUNT_MISMATCH` → `DEGRADED`；
  - 页面变化、应用不可用或无法判定不会伪造认证结论。
- `HEALTHY` 证据 15 分钟后显示 `STALE`。`REAUTH_REQUIRED` 和 `DEGRADED` 不会因时间经过自动
  弱化，只有更新 Context/State 的可信 `READY` 结果才能清除。
- Profile 行悲观锁与 Context Epoch / State Version 单调比较阻止并发写入和迟到旧验证覆盖新结果。
- Profile API 增加独立 `sessionHealth` 汇总；新增
  `GET /api/v1/profiles/{profileId}/session-health` 返回逐站点 Origin、原因、来源 Session、围栏、
  新鲜度和重新认证时间。
- 逐站点 Origin 详情要求 Tenant Operator；只读 Viewer 只能看到 Profile 中不含 Origin 的汇总，
  避免扩大网站清单的可见范围。
- Web/Tauri 共用 Profile 列表把“检查点恢复”和“网站会话”分列显示，明确区分“登录有效”、
  “需要重新登录”、“需检查”、“状态过期”和“未检查”。

## 安全边界

- 不能从 Cookie 是否存在推断登录有效；本实现只接受现有应用恢复契约的路由/可访问目标及 Provider
  证据判定。
- Checkpoint、导入、恢复和启停不会把健康状态改成 `HEALTHY`。
- 未配置 Application Contract 时仍可使用默认 Validator，但只能形成其能证明的有限结论。
- 本切片不主动登录，也不绕过 OTP/MFA；`REAUTH_REQUIRED` 仍进入既有一次性敏感输入或人工协作链。
- 站点主动撤销可能发生在两次验证之间，所以任何 `HEALTHY` 都有显式 `freshUntil`，不是永久保证。

## 验证

- Control Plane 定向测试覆盖重新登录持续、可信 READY 清除、健康过期和迟到旧 State 拒绝。
- Business Recovery 测试验证登录页证据会写入 Profile Health。
- Web 142 项测试、Lint、Build 通过，API 测试覆盖新端点。
- OpenAPI/四语言 SDK 基线更新为 247 Operations / 341 Schemas。
- 完整 PostgreSQL/Redis/MinIO/mTLS/Chromium Integration 通过并输出
  `profile_session_health=true`：真实 READY 状态写入逐站点账本，API 与数据库均为
  `HEALTHY:READY`，且 Profile 技术恢复状态保持独立。
- Control Plane 全量、完整 `make ci`、Desktop test/lint、OpenAPI、四语言 SDK、供应链、
  Operator、50k Capacity 与 V123 N/N−1 Gate 均通过；GitHub 结果在推送后记录。

## 结论

A18 的仓库内代码项已闭环。真实目标网站的撤销延迟、各应用 Recovery Contract/Provider 证据质量
与客户 Replay 仍属于生产接入 Gate，不能由通用 Fixture 代替。
