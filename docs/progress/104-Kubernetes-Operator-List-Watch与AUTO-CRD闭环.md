# Kubernetes Operator List/Watch 与 AUTO CRD 闭环

> 完成日期：2026-08-08
> 状态：仓库内实现、单元测试和真实 Kind N/N-1 升降级已通过；目标云 API
> Server/etcd 故障长稳仍待完成；仓库监控指标与告警后续已由进度 105 关闭。

## 本轮关闭的缺口

BrowserSession Operator 原来由 Leader 每 2 秒对全部 CR 执行一次全量 LIST。资源规模
增大后，这会持续放大 API Server、网络和反序列化开销；如果直接改成 Watch，稳定
`Ready` 状态的重复 PATCH 又会产生由 Operator 自己触发的 `MODIFIED` 反馈环。

本轮将调和入口改为 Kubernetes 原生 List/Watch 语义：

1. 首次同步用 `resourceVersion=0`、`resourceVersionMatch=NotOlderThan` 和 500 条分页
   LIST 获取一致快照，逐页调和，不把全部 CR 保留在进程内；
2. 从快照 `resourceVersion` 开始 Watch，启用 bookmark，每条 `ADDED/MODIFIED` 事件
   成功调和后才推进游标；`DELETED` 只推进游标，删除清理由 deletionTimestamp 的
   `MODIFIED` 和 finalizer 路径完成；
3. Watch 事件限制为 1 MiB，非法 JSON、未知事件和非 410 API 错误 fail closed；
4. `410 Gone` 或 Watch `ERROR 410` 会丢弃旧游标并重新 LIST，不会跳过已压缩历史；
5. Watch 每 5 秒返回一次，使 15 秒 Kubernetes Lease 能持续续租；每 5 分钟全量
   resync 修复可能的外部漂移；API 失败使用 1—30 秒有界指数退避；
6. 稳定 `Ready/observedGeneration` 状态不再重复 PATCH，避免 Watch 自反馈空转。

这是直接消费 Kubernetes API 的轻量 Watch 实现，不引入生产 Mock，也不把 CR 缓存到
本地文件、内存数据库或其他非权威存储。

## AUTO 契约修正

公开 BrowserSession CRD 删除了旧 `resourceClass: L1—L4`，并将两个概念分开：

- `executionEnvironment`：`SYSTEM_MANAGED / CONTAINER / ENHANCED_SANDBOX / MICROVM /
  NATIVE_OS`；
- `resourcePolicy.mode`：公开只接受 `AUTO`，同时支持达到上限行为、迁移/休眠开关、
  最低内部模板和管理员资源边界。

Operator 创建 Session 时不再发送 `resourceClass`，而是发送正式 AUTO Resource Policy；
Execution Environment 只在 Control Plane API 边界合并到 Resource Policy 请求。内部
`standard-v1` 等 Resource Template 仍由后端解析，不重新暴露为普通用户等级。

## 可重复验收

```bash
python3 -m unittest discover -s tools/browser-session-operator -p 'test_*.py' -v
KIND_BIN=/path/to/kind make test-kubernetes-e2e
```

14 项 Operator 单元测试覆盖 Lease CAS、分页快照、resourceVersion/bookmark、410
重列举、有界退避、AUTO 创建契约和稳定状态无反馈 PATCH。

Kind v0.32.0 / Kubernetes v1.36.1 的真实 API Server 验收完成：

- CRD 结构化 Schema 与 admission 通过；
- N-1 双副本 Operator 创建首个 Session；
- 删除 Lease Holder 后另一副本接管并继续创建；
- 滚动到 N 后日志确认 List/Watch 快照同步，既有 CR 保持 Ready，并以 AUTO +
  `CONTAINER` 创建新 CR；
- 回滚 N-1 后 N 创建的 Session 仍 Ready；
- finalizer 删除调用成功，最终严格得到 `createCalls=4`、`terminateCalls=1`。

验证过程中真实 API Server 发现并关闭了两个 Schema/协议错误：CRD 对象节点中
`properties` 与 `additionalProperties` 的不兼容组合，以及 Watch 请求误带仅适用于
LIST 的 `resourceVersionMatch` 参数。

## 仍未完成

1. 目标云多 API Server、etcd 延迟/压缩、API Server 短时不可用和网络分区的长稳；
2. reconcile、LIST/Watch、410、退避和 Lease 的 Prometheus 指标及告警规则已由
   [进度 105](105-Kubernetes-Operator-Prometheus指标与告警闭环.md)关闭；仍缺目标
   Alertmanager 路由与 Pager 到达/关闭演练；
3. 目标云 Node Drain、正式 Registry 制品 N/N-1 和不会双 Leader 的长期证明；
4. 目标云 CNI 防直连、CSI Snapshot、Kata RuntimeClass 和 Browser Node Pool 验收。

因此“每 2 秒全量 LIST、缺少 Watch”和“CRD 继续暴露 L1—L4”已不再是仓库代码缺口；
Phase 6 生产 Exit Gate 仍由目标云故障长稳、监控/Pager 到达、隔离和容量证书阻塞。
