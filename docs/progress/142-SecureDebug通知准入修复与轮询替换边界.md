# Secure Debug 通知准入修复与轮询替换边界

> 日期：2026-08-13
> 状态：Secure Debug 特权访问事件已进入通知中心并有集成回归；剩余固定轮询的
> 事件源边界已用代码证据界定，未以语义降级方式伪装关闭

## 本轮目标

继续关闭[进度 141](141-AUTO资源清单复核与WebConsole可访问性技术质量收口.md)
列出的“平台安全 / Nodes / Enterprise 仍使用固定轮询”缺口。在核对事件源的过程中
先发现并修复了一个更严重的既有缺陷：Secure Debug 的高信号安全事件从未进入通知
中心。

## 已修复：Secure Debug 通知准入缺口

### 缺陷

V064 的 `append_workspace_notification` 触发器分两段：先判断“是否高信号”（准入
门），再判断分类与级别。分类段已经明确把 `%SECURE_DEBUG_%` 归为 `SECURITY`
类别，准入门却只按整前缀收录了同族的 `BREAK_GLASS_%` 和 `KEY_ROTATION_%`，漏掉
`SECURE_DEBUG_%`。

Secure Debug 的审计事件写法是 `event_type=ADMIN_ACCESS`、`action=SECURE_DEBUG_*`
（`SecureDebugApplicationService`）。其中两个最关键的动作不带任何高信号后缀：

- `SECURE_DEBUG_STARTED`（result `ACTIVE`）
- `SECURE_DEBUG_SNAPSHOT_ACCESSED`（result `MINIMIZED`）

因此实际效果是：Secure Debug **被拒绝**时会通知（命中 `_DENIED` 后缀），而真正
**授予了特权调试访问并读取了会话快照**时反而不通知。这与 Break-glass 授权后
可见的既有产品语义不一致，且缺失的正是最需要被看到的那一类事件。

### 修复

新增 `V100__secure_debug_notification_admission.sql`，以 `CREATE OR REPLACE
FUNCTION` 只加宽准入门，补 `SECURE_DEBUG_%` 整前缀（`event_type` 与 `action`
两侧），分类与 severity 逻辑逐字不变：

- Secure Debug 沿用 Break-glass 既有约定，成功动作为 `INFO`，拒绝/过期为
  `WARNING`，不擅自提级；
- 不重建触发器、不改表结构，N−1 Control Plane 继续通过同一触发器写入，滚动升级
  期间两侧行为一致；
- 通知是前向投影，按 V064 既有口径不回填历史审计行。

## 已完成：三个治理列表改为通知游标驱动

上面的准入修复正是让这一步成立的前提。`BREAK_GLASS_%`、`KEY_ROTATION_%` 和
（修复后的）`SECURE_DEBUG_%` 三族现在都按**整前缀**准入通知投影，也就是说这三个
治理列表的每一次状态流转都必然推进租户通知游标，可以无损替换各自的固定轮询：

- `useBreakGlassRequests`（5s）、`useKeyRotationRequests`（5s）、
  `useSecureDebugSessions`（3s）已移除 `refetchInterval`；
- `useWorkspaceNotificationStream` 在游标推进时一并失效这三个 key。通知中心挂在
  `TopContextBar` 上无条件常开一条 SSE，因此不新增连接，也不占用每租户订阅额度；
- React Query 的失效是惰性的：未挂载的页面只被标记为 stale，不会发请求；
- Query Key 统一收敛为 `platformKeys`，不再散落字符串字面量。

`useAuditEvents` 从 5 秒固定轮询降为 30 秒兜底刷新，同样受通知游标驱动的失效影响
但**不**依赖它保证收敛，原因见下。

## 未关闭：审计全量列表、Nodes 与 Enterprise 轮询

| 查询 | 现状 | 能否用现有 SSE 无损替换 |
| --- | --- | --- |
| `audit-events` 全量列表 | 事件驱动 + 30s 兜底 | 否。Overview 与 Notification 两条流都只覆盖高信号子集；改为纯事件驱动会把“至多陈旧一个周期”换成“漏事件即永不刷新”，是可靠性倒退 |
| `browser-nodes` | 5s 轮询 | 否。`BROWSER_NODE` 事件写 `tenant_id IS NULL`，只有 `includePlatformEvents`（PLATFORM_ADMIN）订阅者可见，非平台管理员会完全收不到更新 |
| `enterprise-overview` | 15s 轮询 | 否。无对应变更事件类型 |

正确的关闭方式是新增一条租户级 `audit_events.sequence_no` 可续传 SSE
（`audit_events` 已有 `uq_audit_events_tenant_sequence` 单调序列，可直接作为
游标），并让 Browser Node 事件按租户可见性投影。该项涉及新 API Operation、
OpenAPI 与四语言 SDK 同步，留作后续独立闭环。

`NOTIFICATION_DRIVEN_PLATFORM_KEYS` 与其单测把这条边界固化为代码约束：一旦有人
把审计全量列表加进事件驱动集合，测试会失败。

## 已验证

```text
make test-upgrade-compatibility     # PASS，含新增 V100 滚动不变式
bash -n tests/integration/smoke.sh
pnpm --dir apps/web-console lint    # 通过，0 warning
pnpm --dir apps/web-console test    # 23 个测试文件、106 项通过
pnpm --dir apps/web-console build   # 通过
```

本地 PostgreSQL 顺序应用 `V001`—`V066`（PG14 在 V067 的 `ON DELETE SET NULL
(column)` 处停止，该语法需 PG15+；V064 触发器已在其之前建立），随后按真实审计
形态验证：

- 修复前：`ADMIN_ACCESS/SECURE_DEBUG_STARTED` 与
  `ADMIN_ACCESS/SECURE_DEBUG_SNAPSHOT_ACCESSED` 均不产生通知，同批
  `BREAK_GLASS_APPROVED` 正常产生 `SECURITY/INFO`；
- 应用 V100 后：两者均产生 `SECURITY/INFO`，`SECURE_DEBUG_AUTO_EXPIRE`
  （result `EXPIRED`）为 `SECURITY/WARNING`，普通 `ADMIN_ACCESS/ADMIN_VIEW`
  与 `SESSION_HEARTBEAT` 仍被过滤，准入门没有被过度放宽。

`tests/integration/smoke.sh` 新增针对 `tenant-integration` 的直接数据库断言：
必须存在 `SECURE_DEBUG_STARTED` 与 `SECURE_DEBUG_SNAPSHOT_ACCESSED` 的
`SECURITY` 通知，且 `SECURE_DEBUG_STARTED` 精确为 `SECURITY:INFO`。本机
Docker 未运行，该断言未在本地执行，由 CI 的 `Integration smoke test` 步骤验证。

## 尚未完成

1. 租户级 audit sequence SSE 与按租户可见的 Browser Node 事件投影，用于无损
   替换上表三处轮询；三个治理列表的事件驱动尚未覆盖真实浏览器端到端回归，
   仅有 Query Key 约束单测和现有 Playwright/集成链路；
2. 完整辅助技术矩阵：全键盘、焦点陷阱、屏幕阅读器、200% 缩放与自动 axe/ARIA；
3. 目标环境与组织 Gate 不因本轮改变。
