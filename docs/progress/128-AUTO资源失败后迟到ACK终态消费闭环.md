# AUTO 资源失败后迟到 ACK 终态消费闭环

> 日期：2026-08-12
> 状态：仓库实现与验证完成

## 问题

资源调整进入 Dead Letter 或超过 ACK Deadline 后，V091 Ledger 与通用 Operation 已经进入
失败终态。如果 Browser Node 此后才送达 `RuntimeResourcesAdjusted`，Coordinator 会正确
返回 `STALE_RESOURCE_OPERATION`，但 Node Journal 不会把该拒绝视为已消费，导致同一迟到
事件持续重投。

直接把所有 stale 资源 ACK 当成功会掩盖 Node 身份、Tenant 或仍活动 Operation 的安全
错误，因此必须由 PostgreSQL 的精确失败记录提供终态证据。

## 已完成

1. Node Event Ingestion 在 Coordinator 拒绝 `RuntimeResourcesAdjusted` 后，仅查询同一
   Tenant、Session、Operation ID 的 V091 Ledger。
2. 只有 Ledger 已为 `FAILED`，且拒绝码属于 `STALE_RESOURCE_OPERATION`、
   `INVALID_RESOURCE_OPERATION_PHASE` 或 `INVALID_SESSION_STATE` 时，才接受该 Event 并写入
   Inbox，允许 Browser Node 清理持久 Journal。
3. 迟到 ACK 只追加 `LATE_ADJUSTMENT_ACK_IGNORED / IGNORED_AFTER_FAILED` 资源事件；不会
   重开 Ledger、提交 Operation、更新 Placement、Policy 或当前资源模板。
4. `RESOURCE_NODE_MISMATCH`、Tenant/Session/Operation 不匹配、没有失败 Ledger，以及仍处于
   `REQUESTED/EXECUTING/ACKNOWLEDGED` 的调整继续 fail-closed，不会被迟到语义吞掉。
5. 重复 Event 仍由既有 Inbox Event ID 幂等去重。

## 验证

- Lifecycle 单测覆盖精确 FAILED Ledger、非终态 Ledger 和 Node 身份错误。
- Inbox 单测覆盖失败后迟到 ACK 被终止消费、正常资源提交链不执行，以及无失败证据时继续
  返回原 Coordinator 拒绝。
- `./gradlew -p apps/control-plane check` 通过。
- Web Console 70 项测试、Lint 与生产构建通过。
- OpenAPI、TypeScript SDK 和 Python/Go/Java SDK 漂移验证通过；本轮没有公开协议变更。

## 仍待目标环境验证

- 目标 Linux 多 Node 下的 ACK 延迟、乱序、网络分区、Coordinator 换主和 Node Journal
  重启组合长稳矩阵。
