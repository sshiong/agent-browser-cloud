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

## 未关闭：Security / Nodes / Enterprise 固定轮询

本轮核对了现有两条可续传 SSE 的真实事件源，结论是**不能**用它们无损替换剩余
轮询，因此不做替换，也不记为已关闭：

| 查询 | 现状 | 能否用现有 SSE 替换 |
| --- | --- | --- |
| `break-glass-requests`、`key-rotation-requests`、`secure-debug-sessions` | 5s / 3s 轮询 | 状态流转均写 `SECURITY` 类审计，但 `workspace_overview_events` 的 SECURITY 触发器要求 `severity IN ('WARNING','CRITICAL')`，`*_APPROVED`、`SECURE_DEBUG_STARTED` 等 `INFO` 动作不会产生 Overview 事件 |
| `audit-events` 全量列表 | 5s 轮询 | Overview 与 Notification 两条流都只覆盖高信号子集；改为纯事件驱动会把“5 秒内必然收敛”换成“漏事件即永不刷新”，是可靠性倒退 |
| `browser-nodes` | 5s 轮询 | `BROWSER_NODE` 事件写 `tenant_id IS NULL`，只有 `includePlatformEvents`（PLATFORM_ADMIN）订阅者可见，非平台管理员会完全收不到更新 |
| `enterprise-overview` | 15s 轮询 | 无对应变更事件类型 |

正确的关闭方式是新增一条租户级 `audit_events.sequence_no` 可续传 SSE
（`audit_events` 已有 `uq_audit_events_tenant_sequence` 单调序列，可直接作为
游标），并让 Browser Node 事件按租户可见性投影。该项涉及新 API Operation、
OpenAPI 与四语言 SDK 同步，留作后续独立闭环。

## 已验证

```text
make test-upgrade-compatibility   # PASS，含新增 V100 滚动不变式
bash -n tests/integration/smoke.sh
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
   替换上表四处轮询；
2. 完整辅助技术矩阵：全键盘、焦点陷阱、屏幕阅读器、200% 缩放与自动 axe/ARIA；
3. 目标环境与组织 Gate 不因本轮改变。
