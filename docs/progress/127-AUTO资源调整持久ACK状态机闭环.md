# AUTO 资源调整持久 ACK 状态机闭环

> 日期：2026-08-12
> 状态：仓库实现与验证完成；目标 Linux/多 Node 长稳仍是生产 Gate

## 本阶段目标

把在线资源调整从“通用 Active Operation + 最终 Node ACK”补成可审计、可恢复的资源专用
协议，明确区分请求、首次派发、Node 确认、权威投影提交和失败，避免命令拒绝或非法 ACK
长期占用 Session 写围栏。

## 已完成

1. V091 新增 PostgreSQL 权威表 `session_resource_adjustments`，以 `operation_id` 关联
   `exclusive_operations`，持久保存 `REQUESTED → EXECUTING → ACKNOWLEDGED →
   COMMITTED/FAILED`、新旧资源快照、原因、失败码和各阶段时间戳；终态、失败码与时间由
  数据库约束保护，并建立 Session 时间线和非终态扫描索引。
2. 资源决策与 Outbox 同一事务创建 `REQUESTED` Ledger；Outbox 第一次真实派发前转为
   `EXECUTING`，同时把资源 Operation 从 `PREPARING` 推进到 `EXECUTING`。已有 V090 之前
   创建、没有 Ledger 的 N−1 命令继续兼容。
3. Node ACK 先经过 Session/Node/Operation、Placement 旧快照、策略上限及细粒度执行器
   结果验证；验证成功后在同一事务内按顺序提交 Placement、Policy、通用 Operation 和
   资源 Ledger。Coordinator 只做 Epoch/Session/Node/Operation 围栏，不再提前提交。
4. 非法或越权 ACK 被收敛为 `FAILED`，释放匹配的资源 Operation，Policy 回到
   `OBSERVING`，浏览器保持运行；Inbox 接受该终态事件，避免 Node 无限重投。
5. 命令 Dead Letter 会立即失败 Ledger 并释放写围栏；ACK Deadline 到期同步写入
   `NODE_ACK_TIMEOUT`。所有阶段和失败均写入真实资源事件时间线。
6. `GET /api/v1/sessions/{id}/resources` 新增 `currentAdjustment`，Web/Tauri 共用资源面板
   展示明确文字状态、Operation ID、原因和失败码，不只依赖颜色。OpenAPI 及 TypeScript、
  Python、Go、Java SDK 已同步生成；`ResourceAdjustment` 是四语言命名强类型，不会降级为
  `any/Object`。

## 验证

- Java 实体状态机测试覆盖合法转换、非法提交和终态幂等。
- Lifecycle Service 测试覆盖首次派发、Dead Letter、非法 ACK 和精确 Operation 围栏释放。
- Session Resource 测试验证成功 ACK 的原子提交顺序：`ACKNOWLEDGED → Placement → Policy
  → Operation VERIFYING/COMPLETING/COMMITTED → Ledger COMMITTED`。
- Node Event Inbox 测试验证非法 ACK 会先持久失败再确认 Inbox。
- `./gradlew -p apps/control-plane check`、`make lint`、`make test`、Web 70 项测试、Rust
  Workspace 测试、SDK 结构/哈希验证均通过。
- PostgreSQL Integration 从空库执行 91 个迁移并覆盖 N/N−1 升级矩阵，真实调整记录均为
  `COMMITTED` 且 `executing_at/acknowledged_at/completed_at` 完整；最终
  `health=UP`、`public_tables=107`、`resource_adjustment_lifecycle=true`、
  `audit_chain_valid=true`。

## 尚未完成

1. 目标 Linux 多 Session/多 Node 的长时间扩缩容、网络分区、Coordinator 换主和 ACK
   延迟/乱序故障注入证书。
2. 硬件 Codec/GPU Helper、编码器级动态码率与封装。
3. CRM、支付、账号安全等客户业务 Adapter 的关键事务 Safe Point 真实接入。
