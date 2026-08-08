# Phase 6：密度与 Kubernetes

## 已完成

- 确定性 Shard Router，支持 Hot Tenant Virtual Partition 和 Route Epoch。
- 只有安全点允许重分区；旧路由可由 Epoch fencing 识别。
- Bounded Priority Mailbox 保证 Emergency/Human 消息可抢占 Telemetry，并提供空闲
  Passivation 判定。
- 容量准入绑定 Coordinator Build ID，使用 85% 关闭/70% 重开 Hysteresis。
- 新增 Build-bound Stage A Coordinator Capacity Certificate：固定 50,000 Actor、
  250,000 次路由、64 Shard、10,000 个满载 Mailbox，验证 Route/Emergency P99、
  最大 Shard 负载、Emergency 抢占和容量 Hysteresis；已进入 `make ci`。
- 新增 Build-bound 真实 Chromium 生命周期证书：Chrome 150 顺序启动/健康采样/停止
  500 次，验证 Profile 进程树、端口、Runner RSS/FD 与零残留；证书 Hash 已入库。
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
2. Coordinator 50k 单进程 Stage A 微基准证书已完成；它不包含 PostgreSQL、网络、
   GC 长稳、Mailbox Byte Budget、Passivation/恢复和多实例调度，尚不能作为生产并发承诺。
3. 真实 Chrome 500 次顺序生命周期已完成；并发 Browser Capacity Certificate、
   Extension Weight/Probation、PSI/Cgroup 深度采样和 Node Pressure 驱逐尚未实现。
4. Hot Actor 安全点迁移有 Router/Epoch 核心，但缺双 Coordinator 实例的迁移压测。
5. CNI/CSI 清单已定义，仍需目标云环境验证防直连泄漏与 Snapshot 一致性 Adapter。
6. Operator resourceVersion List/Watch、410 重列举和周期 resync 已由
   [进度 104](104-Kubernetes-Operator-List-Watch与AUTO-CRD闭环.md)关闭；指标/告警和
   多个 API Server/etcd 故障下的长时间稳定性尚未完成。

## Gate 判定

Phase 6 当前为“基础实现完成、真实集群容量与升级 Gate 未关闭”，不得发布稳定并发承诺。
