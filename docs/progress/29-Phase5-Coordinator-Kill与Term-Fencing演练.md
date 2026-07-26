# Phase 5：Coordinator Kill 与 Term Fencing 演练

> 状态：安全点接管已完成
> 日期：2026-07-26
> 验收入口：`make test-integration`
> 后续扩展：进行中 HumanTakeover 接管见
> [进度 32](32-Phase5-Coordinator进行中Operation接管.md)

## 本轮关闭的缺口

此前 `coordinator_ownership` 表和 CAS Repository 只有代码骨架，没有进入
Session Command、Node Event 或 Session Repository 的运行路径：

- 新建 Session 的 `coordinatorTerm` 长期为 0；
- Control Plane 重启后没有真实 Lease 接管；
- Node Event 只比较 Session Context 字段，不能证明当前实例是 Owner；
- Browser Node 的 Runtime Monitor 在启动时捕获 term，接管后仍可能持续上报旧 term。

本轮完成以下闭环：

1. 每个非 Node Event 的 Session Command 先获取或续租 PostgreSQL Ownership；
2. 当前 Owner 只更新 Heartbeat，不提升 term；
3. 其他实例仅在 Lease 过期后通过单条原子 CAS 接管，term 严格 `old + 1`；
4. Session Repository 读取时叠加权威 Ownership Term，新的 Operation 和 Node Command
   自动携带最新 term；
5. Node Event 必须同时满足“当前实例是 Owner”和“事件 term 等于当前 term”；
6. 非 Owner 请求稳定返回脱敏 `503 COORDINATOR_NOT_OWNER`；
7. 旧 term Node Event 返回终止性 `STALE_COORDINATOR_TERM`，Browser Node 将其标记为
   已处理，不再形成永久重投；
8. Runtime Monitor 的 State、Diff、Truncated 与 Crash Event 在发送前读取 Node Journal
   中最新已接受 term，Node 重启对账也使用同一权威值。

## 真实演练流程

集成测试使用同一 PostgreSQL、Redis、Browser Node 和 Session，执行：

1. 以固定实例 ID `coordinator-integration-a` 启动 Control Plane；
2. 创建并启动真实 Session，等待 Runtime 与 Browser State 稳定；
3. 验证 Ownership 为 `coordinator-integration-a:1`；
4. 对实例 A 执行 `SIGKILL`，确保没有优雅关闭或主动交接；
5. 以 `coordinator-integration-b` 在相同 API/gRPC 端口重启；
6. Lease 未过期时，接管请求只能得到 503；
7. 3 秒 Lease 过期后，实例 B 原子接管并创建 HumanTakeover Operation；
8. 验证 Operation 携带 `coordinatorTerm=2`，Ownership 为
   `coordinator-integration-b:2`；
9. 释放接管并执行 Full State Resync；
10. Kill Chromium Runtime，验证 Browser Crash 使用 term=2 被接受并自动恢复；
11. 重启 Browser Node，验证 Runtime Lease 对账与第二次恢复；
12. 再次完成人工接管/释放和 Session 终止；
13. 验证 Inbox、Outbox、Operation 和防篡改审计链保持一致。

本轮真实输出：

```text
coordinator_failover_term=2
automatic_crash_recovery=3
node_restart_reconciliation=4
recovery_operation_committed=2
node_events_inbox=16
node_command_published=10
audit_chain_valid=true
audit_events=54
```

## 自动化与回归证据

已通过：

```bash
make ci
make test-integration
make test-postgres-outage
make test-e2e
```

其中：

- Java Ownership 单测覆盖当前 Owner 续租、过期 CAS 接管、Live Owner 拒绝和旧 term
  Event 拒绝；
- Rust Node Journal 单测覆盖最新 term 跨进程重开持久化；
- 全仓 Clippy 使用 `-D warnings`；
- PostgreSQL GameDay 继续验证读写有界 503 和恢复后幂等续作；
- Web Console Operator/Viewer 两套生产 E2E 均通过。

## 尚未完成

本演练在 Browser State 稳定且无 Active Operation 的安全点 Kill 实例 A，然后由实例 B
创建新的 Operation。它关闭了“Session 可接管、term 递增、旧世代不能提交”的基础 Gate，
但不等同于完整生产 Exit Gate：

1. HumanTakeover `EXECUTING`、Navigate pending、`STARTING` 与 `STOPPING`
   Kill/Reconcile 已由进度 32 关闭；`RECOVERING`、有副作用 Agent Step 以及 Barrier
   `PREPARING/COMPLETING` 的真实进程级竞态仍待完成；
2. 尚未双实例同时长稳运行并注入 PostgreSQL 延迟、时钟偏差和连接池拥塞；
3. 尚未验证数万 Session Heartbeat、Lease 扫描和 Ownership 查询的容量/P99；
4. Session 列表当前逐项读取 Ownership Term，容量阶段需要批量投影以消除潜在 N+1；
5. 尚未在 Kubernetes Pod Kill/网络分区下结合 PDB、RollingUpdate 和 N/N-1 版本执行；
6. 热租户迁移、Route Epoch 与 Coordinator Ownership 的联合 GameDay 仍属于 Phase 6。

后续 S3/MinIO 超时 GameDay 已在进度 31 关闭 Stage A；Phase 5 故障矩阵仍保留
“进行中 Operation 接管”和目标云 Object Storage/IAM/跨 Region 恢复。
