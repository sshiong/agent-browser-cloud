# Phase 6：密度与 Kubernetes

## 已完成

- 确定性 Shard Router，支持 Hot Tenant Virtual Partition 和 Route Epoch。
- 只有安全点允许重分区；旧路由可由 Epoch fencing 识别。
- Bounded Priority Mailbox 保证 Emergency/Human 消息可抢占 Telemetry，并提供空闲
  Passivation 判定。
- 容量准入绑定 Coordinator Build ID，使用 85% 关闭/70% 重开 Hysteresis。
- BrowserSession `v1alpha1` CRD。
- Operator 实现幂等创建、`observedGeneration`、Finalizer 终止和失败重试。
- Control Plane 与 Browser Node 使用独立 Node Selector/Pool。
- Kata RuntimeClass、默认拒绝 NetworkPolicy、仅 CP/Proxy/DNS Egress、CSI Warm Tier、
  PDB 和 RollingUpdate 清单。
- Java 密度单元测试、Python 语法检查、`kubectl kustomize` 均通过。
- Operator 镜像已进入统一 GHCR 构建、SBOM、签名、Attestation 与 Digest 发布流水线。

## 尚未完成

1. 尚未在真实 Kubernetes 集群安装 CRD/Operator 并执行 N/N-1 Rolling Upgrade。
2. Coordinator Capacity Certificate 目前是配置绑定证书模型，尚无正式 10k/50k Actor
   压测报告、Emergency Control P99 和 Mailbox Byte Budget 数据。
3. Browser Capacity Certificate、Extension Weight/Probation、PSI/Cgroup 深度采样和
   Node Pressure 驱逐尚未实现。
4. Hot Actor 安全点迁移有 Router/Epoch 核心，但缺双 Coordinator 实例的迁移压测。
5. CNI/CSI 清单已定义，仍需目标云环境验证防直连泄漏与 Snapshot 一致性 Adapter。
6. Operator Lease Leader Election 和集群级 E2E 尚未完成。

## Gate 判定

Phase 6 当前为“基础实现完成、真实集群容量与升级 Gate 未关闭”，不得发布稳定并发承诺。
