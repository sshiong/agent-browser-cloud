# Phase 6：密度与 Kubernetes

## 已完成

- 确定性 Shard Router，支持 Hot Tenant Virtual Partition 和 Route Epoch。
- 只有安全点允许重分区；旧路由可由 Epoch fencing 识别。
- Bounded Priority Mailbox 保证 Emergency/Human 消息可抢占 Telemetry，并提供空闲
  Passivation 判定。
- 容量准入绑定 Coordinator Build ID，使用 85% 关闭/70% 重开 Hysteresis。
- BrowserSession `v1alpha1` CRD。
- Operator 实现幂等创建、`observedGeneration`、Finalizer 终止、失败重试和双副本
  Kubernetes Lease Leader Election。
- Control Plane 与 Browser Node 使用独立 Node Selector/Pool。
- Kata RuntimeClass、默认拒绝 NetworkPolicy、仅 CP/Proxy/DNS Egress、CSI Warm Tier、
  PDB 和 RollingUpdate 清单。
- Java 密度单元测试、7 项 Operator 单元测试、Python 语法检查、`kubectl kustomize`
  均通过。
- Operator 镜像已进入统一 GHCR 构建、SBOM、签名、Attestation 与 Digest 发布流水线。
- 临时 Kind 集群 E2E 已真实安装 CRD/RBAC/Operator，验证 admission、最小权限、
  Ready/observedGeneration/finalizer、Leader Pod Kill 后 Lease 接管和继续调和。
- 真实集群测试发现并修复容器入口遮蔽 Python 标准库、namespaced PATCH URL 错序；
  删除 CR 后 Control Plane 终止调用与 finalizer 清理均通过。
- 生产工作负载清单统一增加 RuntimeDefault AppArmor；Kind 只做 Server-side
  Admission 验证，目标云节点的 LSM 强制执行仍需单独验收。

## 尚未完成

1. CRD/Operator 已在临时 Kind 集群完成安装、选主和故障接管；尚未在目标云
   Kubernetes 执行 N/N-1 Rolling Upgrade。
2. Coordinator Capacity Certificate 目前是配置绑定证书模型，尚无正式 10k/50k Actor
   压测报告、Emergency Control P99 和 Mailbox Byte Budget 数据。
3. Browser Capacity Certificate、Extension Weight/Probation、PSI/Cgroup 深度采样和
   Node Pressure 驱逐尚未实现。
4. Hot Actor 安全点迁移有 Router/Epoch 核心，但缺双 Coordinator 实例的迁移压测。
5. CNI/CSI 清单已定义，仍需目标云环境验证防直连泄漏与 Snapshot 一致性 Adapter。
6. Operator Watch/Informer、指标/告警和多个 API Server 故障下的长时间稳定性尚未完成。

## Gate 判定

Phase 6 当前为“基础实现完成、真实集群容量与升级 Gate 未关闭”，不得发布稳定并发承诺。
