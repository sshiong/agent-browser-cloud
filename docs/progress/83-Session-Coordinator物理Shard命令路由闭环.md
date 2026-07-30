# Session Coordinator 物理 Shard 命令路由闭环

> 完成日期：2026-07-30
> 状态：仓库内实现、双 Control Plane 真实集成和 N/N−1 兼容 Gate 已完成；目标集群
> 长稳、指标告警与跨 Region Gate 仍待完成

## 本轮关闭的缺口

V040 已把 Tenant/Session Route 固化到 PostgreSQL，V041 已把 Node Command Outbox
按 Shard 分配给物理 Control Plane Worker，但此前仍有一个边界没有闭合：

- HTTP/API 请求由 Kubernetes Service 落到任意 Pod 后，会在该入口 Pod 直接执行；
- Operation/Workflow Deadline、Agent Recovery、AUTO 资源决策和迁移协调器由每个
  Pod 各自扫描并尝试执行；
- 逻辑 `route_epoch/shard_id` 已存在，但没有强制 Coordinator 命令只在当前物理
  Shard Owner 上提交。

本轮新增 V058 持久命令 Inbox，并把 Session 生命周期、Agent、Timer、Workflow、
AUTO 资源决策和迁移协调命令统一路由到由当前 Membership + Rendezvous Hash 选出的
唯一物理 Worker。

## 权威路由与事务边界

执行链如下：

```text
入口 Pod 解析 PostgreSQL Session Route
→ 本 Pod 是 Shard Owner：直接进入既有应用事务
→ 本 Pod 不是 Owner：写 coordinator_commands
→ Active Worker Membership + Rendezvous Hash 选择唯一 Owner
→ Owner 使用 FOR UPDATE SKIP LOCKED + Claim Lease 领取
→ 再次校验 Route Epoch / Shard / Worker Membership
→ 在同一 PostgreSQL 事务执行应用变更并提交命令 Result
→ API 入口只返回已提交的真实 Result / Operation ID
```

用户 JWT、Cookie 和 Secret 不在 Pod 间转发。队列只保存已完成租户鉴权后的版本化业务
Payload；Owner 仍通过 Session 复合租户关系、Route Epoch、Coordinator Term 和应用
状态机重复校验。

`SessionCoordinator` 对非 Node Event 命令新增物理本地性 Gate。Node Event 继续允许
落到任意健康 Pod，但必须通过既有 Route/Term/Owner Lease 栅栏，避免把 Service
入口 Pod 错当成 Session Owner。

## V058 持久命令 Inbox

`coordinator_commands` 保存：

- Command ID、Tenant/Session、Route Epoch、Shard 和版本化 Command Type；
- Tenant 级唯一 Deduplication Key、JSONB Payload；
- `PENDING → EXECUTING → COMMITTED/FAILED` 状态；
- Claim Owner/Lease、Attempt、Deadline、Result、Failure Code 和时间；
- `(session_id, tenant_id)` 复合外键和路由/状态/Claim 完整性约束。

V058 是 expand-only 迁移。N−1 应用可忽略新表并继续写既有 Session；升级夹具验证新
命令与 N−1 Session 写入可共存。回滚到 N−1 前必须先停止新入口、排空
`PENDING/EXECUTING` 命令再降级，因为旧版本不会消费 V058 Inbox。

## 已路由命令

- Session Start、Terminate、HumanTakeover、Release HumanTakeover；
- Agent Execute、Accept Handoff；
- Exclusive Operation Deadline、Durable Workflow Deadline；
- Agent Executor Lease/Step Recovery；
- AUTO Resource Policy 30 秒聚合决策及上限动作；
- Session Migration Reconcile。

后台扫描器只负责发现候选并幂等入队，不再在任意 Pod 直接修改 Session。迁移 Route
变化时，未领取命令会更新到最新 Route；已领取命令在执行前再次校验并 fail-closed
重试。

## 验收中修复的问题

双实例集成发现 Workflow Deadline 路由后存在一个事务语义差异：缺失 Operation 会从
带 `@Transactional` 的 Operation 仓库抛错并把 Owner 命令事务标记为 rollback-only，
导致 Workflow DLQ 证据也被回滚。现在执行前先校验精确 Operation：

- Operation 缺失、跨 Session 或非 `ACTIVE`：直接提交
  `COMPENSATION_FAILED` Dead Letter；
- Operation 有效：再执行 Coordinator Timeout 和既有补偿；
- Command Result 与 Workflow/DLQ 状态仍在同一 Owner 事务提交。

## 可重复验收证据

- Java 单测覆盖非 Owner 拒绝、Owner 本地执行、远端 Result、Payload 幂等冲突和
  Workflow 缺失 Operation 的 DLQ；
- V058 空库迁移和独立升级 Schema 夹具通过；
- N/N−1 Gate 固定 expand-only Schema、路由代码和兼容事实；
- 完整 Integration 同时启动两个 Control Plane，共享 PostgreSQL/Redis/Browser Node；
- 测试计算当前 Shard Owner，故意把 Start/Terminate HTTP 请求发往非 Owner 端口；
- 两条命令均由 Owner 执行，返回真实 Operation ID，数据库状态为 `COMMITTED`；
- 完整 PostgreSQL、mTLS Browser Node、Profile/Proxy、Coordinator 换主、Workflow
  DLQ、AUTO、三 Node 迁移和审计烟测继续通过，并输出
  `coordinator_command_routed=true`、`workflow_dead_letters=1`。

## 仍需完成

1. 双/多 Control Plane、热点 Tenant、大量 Shard、数据库高延迟、连接池拥塞、
   时钟偏差、Pod 抖动和滚动升级的长时间容量与公平性证书；
2. Routed Command 的 Submit/Claim/Commit/Retry/Deadline、Claim Latency、Shard 分布
   和重平衡 Prometheus 指标、Alertmanager/Pager 与运维工作台；
3. 目标 Kubernetes 的跨 Pod 网络分区、CNI、进程强杀和 Claim Lease 故障注入；
4. 跨 Region Event Bus 与物理 Control Plane 分区演练；
5. 生产数据保留策略下的终态 Command 清理和审计归档作业。
