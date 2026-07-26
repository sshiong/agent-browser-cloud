# Phase 6：Coordinator Capacity Certificate Stage A

> 状态：单进程 Stage A 已完成
> 日期：2026-07-26
> 验收入口：`make test-coordinator-capacity`

## 目标

原容量模型只有 Shard Router、Bounded Mailbox 和 Hysteresis 单元测试，没有统一负载模型、
性能阈值、Build 绑定或机器环境记录。本轮增加可重复证书 Runner，并将它纳入 `make ci`。

## 固定负载模型

- 50,000 个逻辑 Actor；
- 1,000 个 Tenant，其中 10% 流量属于具有 32 个 Virtual Partition 的 Hot Tenant；
- 每 Actor 5 次路由，共 250,000 次 Route；
- 64 个 Coordinator Shard；
- 10,000 个 Mailbox，每个预填 64 条 Telemetry；
- 每个满载 Mailbox 插入一个 Priority 100 Emergency Stop，并立即 Poll；
- 容量准入按 86% → 80% → 70% → Node Pressure 序列验证 85/70 Hysteresis。

## Gate

证书只有在以下条件全部成立时成功：

1. Route P99 不超过 1 ms；
2. 满 Mailbox 的 Emergency Offer + Poll P99 不超过 1 ms；
3. 10,000 条 Emergency Stop 全部抢占成功；
4. 64 个 Shard 全部承载流量；
5. 最大 Shard 负载不超过平均值的 2 倍；
6. Admission 在 86% 关闭、80% 保持关闭、70% 重开，并在 Node Pressure 下关闭。

证书 JSON 记录 Build Commit、Java/OS/CPU、完整负载参数、P95/P99、吞吐、Shard 分布、
所有 Gate 和对证书正文计算的 SHA-256。

## 本机验收结果

环境：Apple Silicon、10 个逻辑处理器、Java 21.0.8。

```text
COORDINATOR_CAPACITY_CERTIFICATE_OK
actors=50000
route_p99_ns=1416
emergency_p99_ns=4416
```

本次还验证：

- 64/64 Shard 有流量；
- 10,000/10,000 Emergency Stop 抢占成功；
- 最大 Shard 负载低于平均值 2 倍；
- Hysteresis 全序列通过；
- 证书写入
  `apps/control-plane/build/reports/capacity/coordinator-capacity.json`。

## 未关闭的生产 Gate

这是 JVM 单进程、内存内核心算法的微基准，不包含 Spring HTTP、PostgreSQL Ownership、
Outbox/Inbox、gRPC、Node、网络、磁盘和 Kubernetes 调度，因此不能用于承诺 50k 生产
并发。Phase 6 仍需：

1. 固定 CPU/Memory Limit 的容器化 30—60 分钟长稳和 GC/Allocation Profile；
2. Emergency Control 端到端 P95/P99，包括数据库锁、Mailbox Queueing 和 Node ACK；
3. Mailbox Byte Budget、Overload Reject、Passivation/恢复和 Stale Route Epoch 测量；
4. 双/多 Coordinator、Hot Tenant 迁移和旧 Epoch Fencing 压测；
5. 目标云 CNI/CSI/Kata 环境下的 Capacity Certificate；
6. 真实 Chrome 500 次顺序生命周期证书已由进度 36 完成；Browser Runtime
   Linux 资源硬限制、PSI/Cgroup 和并发容量证书仍待完成。
