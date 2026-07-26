# Phase 5：Coordinator 进行中 Operation 接管

> 状态：HumanTakeover 真实演练完成；Runtime 生命周期与 Agent Step 真实故障矩阵待补
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
7. START API 如果触发的是换主终止清理，会创建 `TERMINATE_RUNTIME` Workflow 和
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

本轮真实输出包含：

```text
coordinator_failover_term=2
coordinator_inflight_operation_reconciled=true
node_events_inbox=17
node_command_published=11
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

1. 真实进程级 `STARTING`、`RECOVERING`、`TERMINATING` Kill，验证 Node 命令已执行和
   未执行两种竞态都收敛到安全终态；
2. 在 HumanTakeover `PREPARING` 与 `COMPLETING` Barrier 中间 Kill，而不只是在
   `EXECUTING` 状态 Kill；
3. 在 Navigate/Click/Type 等异步 Agent Step 等待 Node Event 时 Kill，验证任务最终
   `COORDINATOR_FAILOVER_ABORTED` 且 Capability 不重复消费；
4. 双 Coordinator 长稳、网络分区、时钟偏差、连接池拥塞和 Kubernetes Pod Kill；
5. 将 Reconcile 延迟、旧 Operation 中止数和 Cleanup 失败数接入 Metrics/Alert。

