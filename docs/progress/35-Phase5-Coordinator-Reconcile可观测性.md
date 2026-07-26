# Phase 5：Coordinator Reconcile 可观测性

> 状态：应用指标、Prometheus 端点、告警规则制品和 Runbook 已完成；目标监控平台接入待验收
> 日期：2026-07-26
> 验收入口：`make test-integration`

## 本轮实现

Coordinator Ownership 换主对账新增四项低基数指标：

| 指标 | 类型 | 含义 |
| --- | --- | --- |
| `browsercloud.coordinator.reconcile.duration` | Timer/Histogram | 每次非 Node Event 命令执行 Ownership Reconcile 的延迟 |
| `browsercloud.coordinator.reconcile.stale.operations.aborted` | Counter | 新 term 成功中止的旧 Active Operation |
| `browsercloud.coordinator.reconcile.cleanup.started` | Counter | STARTING/RECOVERING/TERMINATING 换主后创建的新 term Cleanup |
| `browsercloud.coordinator.reconcile.cleanup.failures` | Counter | Cleanup 创建、状态更新、Node Dispatch 或 Outbox 失败 |

指标不包含 Tenant、Session、Operation、Actor 等高基数字段。Prometheus 端点为
`/actuator/prometheus`；生产环境只有带 MFA 约束的 `PLATFORM_ADMIN` 身份可以访问。

Kubernetes 基线新增 `coordinator-alert-rules`：

1. 任意五分钟 Cleanup Failure 触发 Critical；
2. 十分钟旧 Operation 中止超过 20 触发 Warning；
3. Reconcile P99 连续十分钟超过一秒触发 Warning。

每条规则指向 `docs/security/incident-response-runbook.md#coordinator-failover`，包含停止
受影响 Shard 准入、核对 Ownership/Operation Term、禁止旧命令重放和恢复验收步骤。

## 验收证据

单元测试覆盖：

1. STARTING 换主成功：Stale Aborted=1、Cleanup Started=1、Failure=0、Timer=1；
2. Node Dispatcher 在新 term StopRuntime 失败：Failure=1 且异常保持 fail-closed。

完整集成演练分别在 Coordinator C 被 Kill 前和 Coordinator D 接管后抓取 Prometheus：

- C 记录三个 Runtime 生命周期 Cleanup 和全部旧 Operation 中止；
- D 记录 TYPE_TEXT 副作用换主中止；
- 两个世代的 Cleanup Failure 都为 0；
- 测试不把不同进程的 Counter 错当成单 JVM 累计。

已通过：

```bash
./gradlew -p apps/control-plane test
kubectl kustomize deploy/kubernetes/base
make test-integration
make ci
```

关键输出：

```text
coordinator_inflight_operation_reconciled=true
coordinator_reconcile_metrics=true
coordinator_final_term=4
audit_chain_valid=true
audit_events=97
```

## 尚未完成

1. 在目标监控平台安装/挂载规则 ConfigMap，并验证 Prometheus 跨 Pod 聚合；
2. 接通 Alertmanager/Pager、值班路由、静默和升级策略；
3. 通过受控 Cleanup Failure 注入验证真实告警到达、确认和关闭；
4. 为 Ownership Lease Age、Scanner Lag、Outbox Age 和 Node Journal Backlog 补指标。
