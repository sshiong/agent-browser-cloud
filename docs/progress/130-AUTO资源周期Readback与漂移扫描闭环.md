# AUTO 资源周期 Readback 与漂移扫描闭环

> 日期：2026-08-12
> 状态：仓库实现与验证完成

## 问题

进度 129 能消费晚到的 `RuntimeResourcesAdjusted` ACK，但 ACK 若永久丢失，Browser Node
已经生效的新资源仍可能长期不同于 PostgreSQL Placement。本轮把读回并入既有五秒 mTLS
资源遥测，不依赖一次性事件，也不让前端或 Control Plane 猜测 Node 状态。

## 已完成

1. `ReportSessionResourcesRequest` 以 40—56 的 additive 字段携带完整实际资源快照：
   Cgroup CPU/内存/PID/Tab、State Collector、Remote Desktop、Extension、Media、后台
   Tab、Trace/截图采样、Observer FPS 与录像状态。N-1 Node 缺失整组时只跳过对账。
2. Node 每次资源遥测都从各真实 Actuator 读取当前值；任何一项不可读时不发送部分快照，
   防止用最后命令或默认值冒充实际分配。
3. Control Plane 要求整组字段、当前 Tenant/Session/Context Epoch/Placement Node、30 秒
   新鲜度和字段范围全部通过。普通一致快照不写额外数据库事件，避免五秒周期写放大。
4. 若快照不同于 Placement，存在 Active Operation 时等待收敛；没有 Active Operation 时，
   只允许它匹配 Session 最新 `FAILED/NODE_ACK_TIMEOUT` Ledger 的完整请求快照，并要求原
   Operation 为 `TIMED_OUT`、当前 Placement 等于 Ledger 旧快照、当前 Policy 仍允许。
5. 严格匹配后创建独立 `resource.reconcile` 已提交 Operation，原超时 Operation 和失败码
   保留；Placement、Policy、Ledger 与资源时间线在同一事务更新，事件原因为
   `NODE_ACK_TIMEOUT_READBACK_VERIFIED`。
6. 无候选 Ledger、快照越权或新旧快照不匹配时不修改 Placement，资源策略进入
   `CRITICAL`，时间线记录 `RESOURCE_ALLOCATION_DRIFT_DETECTED /
   MANUAL_RECONCILIATION_REQUIRED`；同一冲突原因不会每五秒重复写事件。
7. N/N-1 门禁固定了 40—56 字段号和可选语义；完整/部分快照、永久丢 ACK 对账、未知漂移
   和非超时失败均有回归测试。

## 验证

- Control Plane 全量测试与 Spotless 通过；
- Browser Node `node-agent` 测试、格式与 Clippy `-D warnings` 通过；
- Buf/OpenAPI 契约检查通过；
- N/N-1 升级门禁通过，证据明确包含 `resource-readback-tags-40-56`。
- 真实 PostgreSQL 集成从空库执行 92 个迁移后通过，`health=UP`、`public_tables=107`、
  资源调整/晚到 ACK Ledger、双 Node 迁移、Coordinator Failover 与审计链全部为绿。

## 尚未完成

- 目标 Linux 多 Node 的 ACK 丢失、乱序、网络分区、Coordinator 换主和 Node 重启组合长稳；
- VNC 多协作者每 Actor 独立带宽/FPS 配额；
- 其余生产环境 Gate 继续以 `33-当前未实现清单.md` 为准。
