# Kubernetes Operator Prometheus 指标与告警闭环

> 完成日期：2026-08-08
> 状态：仓库指标、Service、告警规则、Runbook、单元测试与真实 Kind 抓取已完成；目标
> Prometheus/Alertmanager/Pager 到达和 API Server/etcd 长稳仍是生产 Gate。

## 本轮关闭的缺口

进度 104 已把 BrowserSession Operator 改为 Kubernetes List/Watch，但只能从日志判断
Leader、Watch 和调和健康。本轮新增无外部运行时依赖的 Prometheus 数据面：

- 每个 Operator Pod 在 `:8080/metrics` 暴露 Prometheus Text Format，在
  `:8080/healthz` 暴露进程级健康入口；
- 指标覆盖当前 Leader、Lease 尝试与最近成功时间、一致 LIST 请求/快照/资源数、
  Watch 事件/重启原因/最近成功时间、resourceVersion 过期、reconcile 成功率/P99、
  循环错误类别和当前退避秒数；
- reconcile 直方图只保留固定 bucket、count 和 sum，不保存无界 Observation；
- 标签只使用固定的 `result/source/type/reason/category`，不包含 Tenant、Session、
  Resource Name、Request ID、Token 或错误正文，不制造高基数或凭据泄露；
- `browser-session-operator-metrics` Service 使用命名端口和 Prometheus scrape annotation；
- N-1 镜像可以忽略新增端口和环境变量。Deployment 没有强加旧镜像无法响应的新探针，
  因此 N-1→N→N-1 回滚仍成立。

## 告警和处置

`browser-session-operator-alert-rules` ConfigMap 交付七条规则：

1. `BrowserCloudOperatorNoLeader`；
2. `BrowserCloudOperatorLeaseRenewalStale`；
3. `BrowserCloudOperatorLoopErrors`；
4. `BrowserCloudOperatorResourceVersionExpirySpike`；
5. `BrowserCloudOperatorReconcileFailures`；
6. `BrowserCloudOperatorReconcileP99High`；
7. `BrowserCloudOperatorSnapshotStale`。

事故 Runbook 新增 Operator List/Watch 章节，要求跨 Pod 对照 Lease，不从单个 Service
响应推断 Leader；API Server/网络故障期间保持既有 Browser Session，不删除 CR/finalizer，
恢复前必须确认单 Leader、Lease/快照新鲜度、调和错误归零和 create/delete 演练。

## 可重复验收

```bash
python3 -m unittest discover -s tools/browser-session-operator -p 'test_*.py' -v
kubectl kustomize deploy/kubernetes/base >/dev/null
KIND_BIN=/path/to/kind make test-kubernetes-e2e
```

- 17 项单元测试通过，其中指标测试验证 Counter/Gauge、Prometheus exposition、HTTP
  健康入口、固定内存直方图和有界错误分类；
- Prometheus 3.13.1 `promtool check metrics` 通过；
- 同版本 `promtool check rules` 解析并通过全部 7 条规则；验证镜像固定为
  `prom/prometheus@sha256:3c42b892cf723fa54d2f262c37a0e1f80aa8c8ddb1da7b9b0df9455a35a7f893`；
- Kind v0.32.0 / Kubernetes v1.36.1 真实安装 Service/ConfigMap，先运行 N-1，再
  Leader Kill、滚动 N；测试定位当前 Lease Holder Pod 并直接抓取 `/healthz` 和
  `/metrics`，确认 Leader、Lease、成功 Snapshot、Watch Restart 和 reconcile
  Histogram；之后回滚 N-1、创建 CR、删除 finalizer，严格得到
  `createCalls=4`、`terminateCalls=1`。

## 仍未完成

1. 目标 Prometheus 的跨 Pod 抓取、规则加载和持久保留；
2. 目标 Alertmanager 路由、抑制、Pager 实际到达、确认和关闭演练；
3. 多 API Server、etcd 延迟/压缩、单向网络分区和 Node Drain 的长时间稳定性；
4. 生产 SLO 阈值基于真实容量证书的校准。

因此“Operator 没有指标、告警和处置手册”已不再是仓库代码缺口；生产 Gate 不会因
本地规则存在而自动关闭，仍必须归档目标监控和 Pager 的实际到达证据。
