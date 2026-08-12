# AUTO 资源晚到 ACK 权威对账闭环

> 日期：2026-08-12
> 状态：仓库实现与验证完成

## 问题

进度 128 已让资源调整失败后的迟到 ACK 可以被终态消费，Browser Node 不再永久重投。
但 `NODE_ACK_TIMEOUT` 不等于 Node 执行失败：Node 可能已经完成 Cgroup、State Collector、
Remote Desktop、Extension、Media 和降载执行器，只是 ACK 晚于 Control Plane Deadline。
若一律忽略迟到 ACK，PostgreSQL Placement 仍是旧值，而 Node 真实资源已是新值，会形成
权威资源漂移。

## 已完成

1. V092 为 V091 Ledger 增加 `RECONCILED` 终态、`reconciliation_operation_id` 和
   `reconciled_at`。原 Operation 保持 `TIMED_OUT`，原 `NODE_ACK_TIMEOUT` 失败码也保留，
   不伪造为原请求按时成功。
2. 只有以下条件全部满足才允许自动对账：
   - Coordinator 拒绝是 `STALE_RESOURCE_OPERATION`；
   - 精确 Tenant、Session、Operation Ledger 为 `FAILED/NODE_ACK_TIMEOUT`；
   - 该 Ledger 仍是 Session 最新资源调整；
   - 原通用 Operation 确实为 `TIMED_OUT`，且当前没有 Active Operation；
   - Session 仍为 `RUNNING/DEGRADED`，Placement 仍 Active 且 Node 未改变；
   - ACK 旧值逐字段匹配当前 Placement，ACK 新值逐字段匹配 Ledger 请求快照；
   - 新值仍满足当前 Policy、桌面、扩展、媒体和录制边界。
3. 验证通过后创建独立、已提交的 `resource.reconcile` Operation，并在同一 Inbox 事务更新
   Placement、Policy、V091/V092 Ledger 与资源时间线；Web/Tauri 共用资源面板明确显示
   `晚到 ACK 已对账` 和对账 Operation ID。
4. 新资源决策与晚到 ACK 对账统一持有 Session 主行锁；最新 Ledger 查询使用
   `requested_at, operation_epoch` 稳定排序，避免并发后续决策被旧 ACK 覆盖。
5. 若 Ledger/Placement/Policy、Node、Session 或 Operation 任一条件不一致，绝不更新
   Placement；写入 `LATE_ADJUSTMENT_ACK_CONFLICT / MANUAL_RECONCILIATION_REQUIRED`，并将
   Resource Policy 标为 `CRITICAL`。Dead Letter、非法 ACK 等非超时失败仍按进度 128 的
   终态忽略语义处理。

## 公开契约

- `ResourceAdjustment.state` 新增 `RECONCILED`；
- 新增 `reconciliationOperationId` 与 `reconciledAt`；
- OpenAPI、Web 类型和 TypeScript/Python/Go/Java SDK 已同步，仍为 197 个 Operation，
  Schema 增至 264 个。

## 验证

- `make lint`、`make test` 全部通过；
- OpenAPI/Buf、TypeScript SDK 和 Python/Go/Java SDK 结构及哈希验证通过；
- Web Console 70 项测试与生产构建通过；
- `make test-integration` 从空库顺序执行 92 个迁移，通过真实 PostgreSQL、Control Plane、
  Browser Node、恢复、迁移、资源执行与审计链主流程，并验证 V092 对账列存在。

## 尚未完成

- 目标 Linux 多 Node 的 ACK 延迟、乱序、网络分区、Control Plane 换主和 Node Journal
  重启组合长稳矩阵；
- 若 Node 已执行但 ACK 永久丢失，仍需要目标环境的周期资源 Readback/Drift Scanner；
- VNC 多协作者每 Actor 独立带宽/FPS 配额仍未实现。
