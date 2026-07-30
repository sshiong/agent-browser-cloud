# Node Command 物理分片派发与双重栅栏

> 完成日期：2026-07-29
> 状态：仓库内实现与完整故障切换集成已通过；Session Coordinator 入口的物理
> Owner 路由后续已由进度 83 完成，目标集群长稳仍待完成

## 本轮关闭的缺口

V040 已经把 Hot Tenant Route、逐 Session Route Binding 和 Route Epoch 固化到
PostgreSQL，但 Node Command Outbox 仍由所有 Control Plane 副本无差别扫描：

- Outbox 行没有记录创建命令时的权威 Route Epoch/Shard；
- 多副本只能依赖进程竞争，不能把 Shard 稳定分配给物理 Worker；
- Worker 崩溃后没有显式的短租约 Claim；
- Browser Node 只校验 Coordinator Term，旧 Route 或错误 Shard 的命令仍可能到达执行
  路径；
- Node Event 被错误地要求只能由本地 Owner Pod 接收，不符合 Kubernetes Service
  负载均衡入口。

本轮完成 V041、动态 Worker Membership、Rendezvous Hash、Outbox Claim Lease，以及
Browser Node 的 Route Epoch + Coordinator Term 原子栅栏。

## V041 与在线索引

V041 以 expand-only 方式新增：

- `outbox_events.route_epoch`、`coordinator_shard_id`：命令进入事务 Outbox 时捕获的
  权威 Route；
- `dispatch_owner`、`dispatch_lease_until`：可崩溃恢复的行级派发 Claim；
- `coordinator_dispatch_workers`：物理 Control Plane Worker 的短期 Membership Lease；
- Route/Shard 完整性、范围和 Claim 完整性约束。

上述列在 N/N-1 窗口保持可空，旧 Writer/Dispatcher 不需要认识新字段。大表派发索引
使用独立的
`database/online-migrations/create_outbox_node_command_shard_claim_index.sql`
执行 `CREATE INDEX CONCURRENTLY IF NOT EXISTS`，没有放入持有 Flyway Advisory Lock
的启动迁移；脚本可重试，暂未执行时仍可使用既有未发布事件索引。

## 动态物理 Worker 分配

每个 Control Plane Pod 使用 Kubernetes `metadata.name` 作为稳定进程实例 ID，并每
250ms 刷新短 Membership Lease。派发流程为：

```text
读取当前 Active Worker Membership
→ Rendezvous Hash(Shard, Worker) 选出唯一 Worker
→ FOR UPDATE SKIP LOCKED 批量领取可投递 Outbox 行
→ 写入短期 dispatch_owner / dispatch_lease_until
→ 校验 Outbox Route 仍等于 PostgreSQL 权威 Route
→ gRPC 派发到 Placement 对应 Browser Node
→ ACK 后提交 published_at；失败释放 Claim 并按策略重试/死信
```

不需要固定副本数、StatefulSet ordinal 或手工 Worker 编号，因此现有 Deployment 的
`maxUnavailable=0`、`maxSurge=1` 滚动策略可以继续使用。正常关闭会注销 Membership；
进程被强杀时，3 秒 Membership Lease 和 6 秒行 Claim Lease 负责接管。

## Browser Node 双重栅栏

`CommandEnvelope` 以兼容新增字段携带：

- `route_epoch = 13`
- `coordinator_shard_id = 14`

Node Journal 新增持久 `coordinator_routes`，并在同一个 SQLite 事务中校验和提交：

1. Route Epoch 不能倒退；
2. 同一 Epoch 必须命中同一 Shard；
3. Coordinator Term 不能倒退；
4. Route 与 Term 必须一起成功，错误 Shard 不得提前推进 Term，未来 Route 的旧 Term
   也不得提前污染 Route。

拒绝码包括 `ROUTE_EPOCH_REQUIRED`、`STALE_ROUTE_EPOCH`、
`WRONG_COORDINATOR_SHARD` 和 `STALE_COORDINATOR_TERM`。滚动窗口内默认仍接收从未建立
Route 的 legacy epoch 0 命令；旧命令排空后可设置 `NODE_REQUIRE_ROUTE_EPOCH=true`
收紧。

## Node Event 多 Pod 入口

Node Event 仍由 Kubernetes Service 分发到任意健康 Pod。接收 Pod 不再冒充其他
Session Owner，而是校验 PostgreSQL 权威 Route Epoch、Coordinator Term 和远端 Owner
Lease 新鲜度：

- 当前世代且远端 Owner Lease 有效：任意健康入口 Pod 可在 Session 行锁下提交事件；
- Route/Term 过期：拒绝；
- 远端 Owner Lease 已过期：拒绝并等待新 Owner 提升 Term，避免在 Coordinator 已死亡
  后提交未知结果；
- 事件恰好落到当前 Owner Pod：允许续租自己的 Lease；
- 命令路径仍通过 `acquireSession` 维持单 Owner。

这修复了负载均衡入口与逻辑 Owner 身份混淆，同时保留故障切换的未知结果清理语义。

## 验收中关闭的 Runtime Monitor 竞态

重复故障注入发现旧 Runtime Monitor 只按 `sessionId` 判断所有权：Stop 后快速 Recovery
会为同一 Session 注册新 Monitor，旧任务可能误把新 Browser 当成自己的世代继续探测，
从而重复产生 Crash/Recovery。监控注册现改为 `sessionId → start message token`：

- 每次合法 Start/Recovery 都有唯一 Monitor Token；
- 旧任务发现 Token 已替换后立即退出；
- 旧任务只允许删除与自己 Token 匹配的注册，不能移除新 Monitor；
- Node 进程关闭仍会清空全部 Monitor。

因此 Browser Generation 快速替换不会留下跨世代健康探针。

## 验收证据

- Control Plane 完整 `check` 通过；
- Browser Node 全 Workspace Test、Fmt 与 Clippy 通过；
- Buf/OpenAPI/JSON Contract Gate 通过；
- V041 N/N-1 Gate 验证纯增量 Schema、Proto Tag 唯一性、在线索引隔离和 Deployment
  实例 ID；
- PostgreSQL 升级夹具验证 N-1 Outbox 行可保持 Route/Claim 为空，新版本正常读取；
- 多 Node 路由测试验证新命令携带权威 Route，迁移后的旧 Epoch Outbox 被
  `STALE_ROUTE_EPOCH` 终止；
- 完整 `tests/integration/smoke.sh` 通过，输出
  `tenant_route_migration=true`、`node_command_route_fenced=true`；
- 同一完整集成继续通过四次 Coordinator Term 提升、Start/Stop/Recovery 未知结果
  Cleanup、HumanTakeover Barrier 重建与 Agent 副作用 exactly-once。

## 仍需完成

1. 当前关闭的是 **Node Command Outbox 的物理分片派发**；HTTP/API、Timer、Workflow
   等 Session Coordinator 命令的物理 Owner 路由后续已由
   [进度 83](83-Session-Coordinator物理Shard命令路由闭环.md)完成；
2. 双/多 Control Plane、热点 Tenant、大量 Shard、数据库高延迟、Pod 抖动和滚动升级的
   长时间容量/公平性证书；
3. 目标 Kubernetes 集群上的跨 Pod gRPC、CNI 单向分区、时钟偏差和 Claim Lease
   故障注入；
4. Worker/Shard 分布、Claim 延迟、重平衡和死信的 Prometheus 指标、告警与运维工作台；
5. 跨 Region Event Bus 和物理 Control Plane 分区演练。
