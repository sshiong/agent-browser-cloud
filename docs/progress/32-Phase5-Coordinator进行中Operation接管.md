# Phase 5：Coordinator 进行中 Operation 接管

> 状态：HumanTakeover、Agent pending Step、Runtime STARTING/TERMINATING 真实演练完成
> 日期：2026-07-26
> 验收入口：`make test-integration`

## 本轮关闭的缺口

此前 Coordinator Kill 只在 Session 没有 Active Operation 的安全点执行。虽然已经证明
Lease/CAS、term 递增和旧事件 fencing 有效，但换主后数据库中的旧 term Operation 仍会保持
`ACTIVE`，同 Actor 的 HumanTakeover 请求还会直接返回旧 Operation ID，造成操作永久卡住。

本轮新增换主 Reconcile：

1. 非 Node Event 命令取得权威 Ownership Term 后，先锁定 Session 并检查 Active Operation；
2. Active Operation 的 `coordinator_term` 小于当前 term 时，以 CAS 将其转为 `ABORTED`；
3. `STARTING`、`RECOVERING`、`TERMINATING` 不重放结果不确定的旧命令，而是创建新 term 的
   Termination Operation，幂等停止 Runtime 并释放 Profile、Proxy 和输入资源；
4. 同 Actor 的 HumanTakeover Request/Release 在新 term 下重建 Begin/End Barrier，
   新 Operation 使用更高 `operation_epoch`；
5. 旧 Agent/Human 输入使用 Node 已有的 `ReleaseAllInput` 命令清理，该命令携带新 term、
   不绑定新 Operation，并由 Node Journal 去重；
6. Agent Executor 创建和短 Lease 恢复前显式进入 Coordinator Reconcile。换主后旧 Agent
   Operation 被安全中止，遗留输入被释放，恢复扫描将任务标记
   `COORDINATOR_FAILOVER_ABORTED`，不盲目重放可能已执行的写 Step；
7. Agent Executor Lease 支持 1—300 秒受控配置，生产默认仍为 30 秒；故障演练使用
   2 秒缩短确定性等待，不改变 fail-closed 语义；
8. START API 如果触发的是换主终止清理，会创建 `TERMINATE_RUNTIME` Workflow 和
   `COORDINATOR_FAILOVER_ABORT` 审计，不再错误标记成新的启动工作流。

## 真实 SIGKILL 演练

`tests/integration/smoke.sh` 现在执行：

1. Coordinator A 以 term=1 启动真实 Session；
2. 创建 HumanTakeover 并等待 Operation 进入 `EXECUTING`，保持操作不释放；
3. 对 A 执行 `SIGKILL`；
4. Coordinator B 启动，在 Lease 到期前保持 503 fail-closed；
5. B 以 term=2 接管，同 Actor 再次请求 Takeover；
6. 验证 term=1 Operation 变为 `ABORTED`，返回的是不同的新 Operation ID；
7. 验证 term=2 Operation 进入 `EXECUTING`，释放后变为 `COMMITTED`；
8. 继续执行 Agent、Runtime Crash Recovery、Node Restart 和 Session Termination，
   证明 Reconcile 没有破坏后续链路。

同一演练随后覆盖 Agent pending Step：

1. Coordinator B/term=2 创建 Navigate Task；
2. `SIGSTOP` Browser Node，使 Step 保持 `RUNNING + pendingStepId`；
3. `SIGKILL` Coordinator B 后恢复 Node，并启动 Coordinator C；
4. 2 秒 Executor Lease 到期后，Recovery Scanner 经 Coordinator 取得 term=3；
5. term=2 Agent Operation 变为 `ABORTED`，Task 变为 `FAILED`，`lastError` 精确为
   `COORDINATOR_FAILOVER_ABORTED`，`currentStep=0`；
6. 随后的新 Agent Task 正常执行完成，证明旧 Capability/Step 没有被恢复线程重复消费。

同一次 Node Pause/Coordinator Kill 还使用独立 Profile/Session 覆盖 Runtime STARTING：

1. Browser Node 暂停时，Coordinator B 创建 term=1 Start Operation 和持久 Outbox；
2. B 被 `SIGKILL`，Node 恢复后旧 Start 可能已执行，也可能尚未执行；
3. Coordinator C 对该 Session 再次处理 Start 请求，Lease 到期后 claim term=2；
4. Reconcile 将旧 Start Operation 置为 `ABORTED`，创建 term=2 Stop Cleanup；
5. Node 若已启动 Runtime，则 Stop 完成资源释放；若旧 Start 尚未到达，则 Stop 先提升
   Node term，后到的 term=1 Start 被 fencing；
6. Cleanup Operation=`COMMITTED`，Session=`TERMINATED`，旧 Start Workflow 不会误提交。

另一个独立 Session 在暂停前先进入 RUNNING，随后覆盖 Runtime TERMINATING：

1. Node 暂停后 Coordinator B 创建 term=1 Termination Operation；
2. B 被 `SIGKILL`，旧 Stop 的执行结果保持未知；
3. C 对该 Session 再次执行 Terminate，claim term=2；
4. term=1 Stop Operation=`ABORTED`，term=2 Stop Cleanup=`COMMITTED`；
5. 首次 Stop 若已完成，第二次 Stop 仍幂等产出终止事件；若尚未执行，term fencing
   拒绝后到的旧命令；
6. Session 最终稳定为 `TERMINATED`，Profile/Proxy/Input 资源均由新 term 清理。

本轮真实输出包含：

```text
coordinator_failover_term=2
coordinator_inflight_operation_reconciled=true
coordinator_agent_step_aborted=true
coordinator_lifecycle_start_aborted=true
coordinator_lifecycle_stop_aborted=true
coordinator_final_term=3
node_events_inbox=20
node_command_published=18
audit_chain_valid=true
audit_events=56
```

## 同步修复的启动回归

S3 Object Storage SDK 更新后，Rust workspace 同时带入 rustls 的 `ring` 和
`aws-lc-rs` feature。Node Agent 在创建 TLS Channel 前无法自动选择进程级
CryptoProvider，会直接 panic。

Node Agent 现显式安装 `ring` provider；`cargo test -p node-agent` 和完整 mTLS
集成演练均通过。这是运行时修复，不只是测试绕过。

## 自动化证据

已通过：

```bash
./gradlew -p apps/control-plane test
cargo test --manifest-path apps/browser-node/Cargo.toml -p node-agent
make test-integration
```

Coordinator 单测覆盖：

- STARTING 旧 Operation → term=2 Termination Cleanup；
- HumanTakeover 同 Actor → term=2 Barrier 重建；
- 旧 Agent 输入先 `ReleaseAllInput`，再创建新 HumanTakeover。

## 尚未完成

本轮不能把“进行中 Operation 故障矩阵”整体标记完成，仍需：

1. `STARTING`、`TERMINATING` 已完成真实进程级验证；仍需 `RECOVERING` Kill，
   以及在可控故障点分别固定“旧命令已执行/未执行”两条时序；
2. 在 HumanTakeover `PREPARING` 与 `COMPLETING` Barrier 中间 Kill，而不只是在
   `EXECUTING` 状态 Kill；
3. Navigate pending Step 已完成；仍需 Click/Type 等“Node 可能已经执行但 Event 未提交”
   的副作用竞态与 Capability Ledger 精确计数断言；
4. 双 Coordinator 长稳、网络分区、时钟偏差、连接池拥塞和 Kubernetes Pod Kill；
5. 将 Reconcile 延迟、旧 Operation 中止数和 Cleanup 失败数接入 Metrics/Alert。
