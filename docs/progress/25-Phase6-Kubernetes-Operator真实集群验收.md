# Phase 6：Kubernetes Operator 真实集群验收

> 状态：临时 Kind 集群中的 CRD、RBAC、双副本选主、调和、故障接管与 Finalizer
> 已闭环；目标云多节点、CNI/CSI 和 N/N-1 Rolling Upgrade Gate 仍未关闭。

## 已完成

- Operator 使用 `coordination.k8s.io/v1 Lease` 选主：
  - Pod 名称作为 Holder Identity；
  - 15 秒租约、2 秒调和周期；
  - 当前 Holder 续租；
  - 外部 Holder 未过期时不调和；
  - 过期后通过 `resourceVersion` CAS 接管并递增 `leaseTransitions`；
  - 创建/更新冲突均不错误宣称 Leader。
- Deployment 通过 Downward API 注入 Pod Name/Namespace，生产保持 2 副本。
- RBAC 仅授予 BrowserSession 调和与 Lease 选主所需权限；测试确认 ServiceAccount
  可访问 Lease、不可创建 Secret。
- 修复两个只有真实容器/API Server 才暴露的问题：
  1. `operator.py` 文件名遮蔽 Python 标准库 `operator`，容器启动循环导入；
  2. 单资源 PATCH 错用
     `/browsersessions/namespaces/{namespace}/{name}`，现改为标准
     `/namespaces/{namespace}/browsersessions/{name}`。
- Control Plane、Browser Node、Network Helper、Storage Helper、Web Console 和
  Operator 生产清单均配置 RuntimeDefault AppArmor；既有 RuntimeDefault Seccomp、
  非 root、只读根文件系统和全部 Capability Drop 保持不变。

## 可重复验收

```bash
make test-kubernetes-operator
KIND_BIN=/path/to/kind make test-kubernetes-e2e
```

集群 E2E 每次自动创建并销毁独立 Kind 集群，执行：

1. 真实 API Server 安装 Namespace、BrowserSession CRD、RBAC 和双副本 Operator；
2. 对带 RuntimeDefault AppArmor 的生产清单执行 Server-side Dry-run；
3. 由于 Docker 内嵌 Kind 节点没有宿主 AppArmor，临时运行清单只移除 AppArmor，
   其余 Security Context 由实际 Pod 执行并断言；
4. 创建合法 BrowserSession，等待 `phase=Ready`、`observedGeneration=1`、
   `browsercloud.io/session-cleanup` finalizer 和实际 Session ID；
5. 删除当前 Lease Holder Pod，等待另一 Pod 接管，再创建第二个 BrowserSession；
6. 创建非法 tenantId，确认 CRD admission 拒绝；
7. 删除首个 BrowserSession，确认终止调用完成且 finalizer 被移除；
8. 查询 Mock Control Plane，严格断言 `createCalls=2`、`terminateCalls=1`。

GitHub CI 设有独立 `kubernetes-operator-e2e` Job；Kind v0.32.0 下载内容固定
SHA-256，以上集群验收会阻断主分支合并。

本轮实际输出：

```text
Kubernetes operator E2E passed:
leader=browser-session-operator-...-9g9wc
failoverLeader=browser-session-operator-...-xv6qm
createCalls=2 terminateCalls=1
```

## 尚未完成

1. 在目标云多节点集群强制加载 AppArmor/SELinux Profile 并验证拒绝、审计与回滚。
2. 使用 Watch/Informer 替代每 2 秒全量 List，并提供 reconcile/lease 指标和告警。
3. API Server 短时不可用、etcd 延迟、网络分区下的长时间稳定性与不会双 Leader 证明。
4. CNI 防直连、CSI Snapshot 一致性 Adapter、Kata RuntimeClass 在目标环境的实测。
5. Operator 和 CRD 的 N/N-1 兼容、Rolling Upgrade 与回滚 GameDay。
6. Browser/Coordinator Capacity Certificate、目标密度和多节点调度压力证书。

## Gate 判定

“Operator 只有 YAML、未在集群运行”以及“2 副本无选主”两个缺口已关闭。Phase 6
完整 Exit Gate 仍受目标云隔离、容量证书、CNI/CSI 和滚动升级演练阻塞。
