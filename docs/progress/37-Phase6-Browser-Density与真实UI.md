# Phase 6：Browser Density、资源硬限制与真实 UI

> 状态：Stage B、本机并发容量、Media/Extension 自适应和本地 N/N-1 滚动已通过；
> 目标 Linux/目标云 Gate 仍未关闭
> 日期：2026-07-26

## 本轮完成

1. 新增 Browser Node、Extension Profile、Session Resource Demand、Browser
   Placement 四组 PostgreSQL 权威数据及 V018 迁移。
2. Placement 已实现 Resource Class、Extension Weight、未知扩展 Probation、Web3/
   Crypto/Remote Desktop 自动升档、安全余量、节点准入、租户/扩展反亲和与高权限
   Extension 独占隔离。
3. Browser Node 通过 mTLS gRPC 周期上报认证容量、能力标签和 Linux PSI；控制面在
   DEGRADED/CRITICAL 时立即关闭 Admission，并需连续三个健康样本后恢复。
4. Node Command Outbox 按 Placement 的 `node_id` 路由到各 Node 的 gRPC Target，
   不再把所有命令发往单一静态地址；旧消息保留 N/N-1 回退兼容。
5. Start Runtime Contract 增加 CPU、Memory High/Max、PID、Tab Budget 硬限制。
   Runtime Supervisor 在生产模式要求委派的 Cgroup v2 Root，写入精确限制并将
   Chromium、Xvfb、x11vnc 全部加入同一 Runtime Cgroup，Stop/Crash 时统一清理。
6. Kubernetes Browser Node 清单增加 Cgroup v2 委派挂载、认证容量环境变量及按
   Hostname 的 Pod Anti-affinity；Docker Compose 改由真实 Node Heartbeat 注册。
7. Web Console 的 Nodes、Extensions 页面由 Fixture 改为真实 API，并补齐加载、
   空态、错误、PSI、容量、隔离与画像信息。创建 Session 改为基础配置、工作负载、
   确认三步向导，可提交标签页、Agent 速率、Extension、Remote Desktop 和 Web3
   需求。
8. PSI Critical 处置升级为有界驱逐：Admission 立即关闭后，每个调度 Tick 最多领取
   一个低优先级 Active Placement，通过 Coordinator Operation/Term Fencing 终止；
   若存在冲突 Operation 则归还候选并重试，不会形成全节点 Stop 风暴。
9. 多 Node gRPC 路由测试启动两个独立 NodeControl Server，验证每条 Placement 命令
   只到达其注册 Node。Kubernetes Runtime Journal 改存 Warm Tier，并增加 Startup、
   Readiness、Liveness Probe，为有序滚动恢复保留 Node Journal。
10. Media 使用独立认证 Slot、租户配额和码率预算；开启 Media 会升到 L4 资源预算，
    但不会错误强制 GPU。Node Heartbeat、Placement、OpenAPI、Session 创建 UI 和成本
    解释均贯穿 Media 字段。
11. Extension 画像增加持续样本、滑动窗口 P95、自适应采样层级和单次 CPU Budget。
    至少 20 个样本才调整画像，单个尖峰不会触发迁移，PSI Burst 使用 Deep Sample。
12. N/N-1 Gate 对 V019—V021 执行 Expand-only/default 检查，并校验 Protobuf Field
    Number、JSON Optional、RollingUpdate、PDB 和 Probe。
13. Kind 实际先运行提交 `540a72e` 的 N-1 Operator，随后 Kill Leader、升级到 N、
    保持既有 Session Ready，再回滚 N-1 并创建新 Session；最终严格断言
    `createCalls=4`、`terminateCalls=1`。

## 验收证据

- `make test-integration`：通过真实 PostgreSQL、Redis、Control Plane、Browser
  Node、Helper 与浏览器生命周期链路；实际 Node Heartbeat、Placement、Probation
  L1→L2、资源限制、Crash Recovery 和 Node Restart 均通过。
- `make test-e2e`：通过 Nodes/Extensions、三步创建、Session 生命周期、真实远程
  桌面、Agent 操作、网络分区恢复和 Viewer RBAC。
- Control Plane 单元/集成测试、Rust Workspace、Web 25 项测试、Lint、Production
  Build、Contract Check、Kustomize Render 和 Diff Check 均通过。
- Cgroup v2 单元测试验证 `cpu.max`、`memory.high`、`memory.max`、`memory.swap.max`
  和 `pids.max` 的精确值及进程附着。
- `make test-browser-density-capacity` 使用 Chrome `150.0.7871.182` 完成 500/500
  个真实生命周期、并发 4：Start P99 6,543ms、Stop P99 152ms、峰值聚合 RSS
  4,083,220,480 bytes、35 个进程、Runner RSS 增长 17,760,256 bytes、FD 增长 0、
  残留进程 0。全部十项 Gate 为 `true`，证书 Hash 为
  `9bb31e30...5095a28b`；证据见
  [browser-density-capacity-7cfd318.json](../evidence/capacity/browser-density-capacity-7cfd318.json)。
- `make test-upgrade-compatibility`：V019—V021 Schema、Protobuf、JSON 与 Kubernetes
  滚动策略全部通过，Evidence Hash 为
  `2c40f45b...72a55f63`。
- `KIND_BIN=/tmp/agentbrowser-kind-v0.32.0 make test-kubernetes-e2e`：Kind v0.32.0、
  Kubernetes v1.36.1 上 N-1→N→N-1 与 Leader Kill 全部通过。

## 尚未完成

1. 目标 Linux 节点上真实委派 Cgroup v2、OOM/PSI 压力及受控驱逐证书。
2. 多个真实 Browser Node 同时在线时的网络分区、重路由与长稳容量证书；当前已完成
   双 gRPC Server 精确路由测试和单 Node 并发 Chrome 证书。
3. Hot Tenant 迁移长稳、目标云 CNI/CSI、Browser Node Pool 与目标云 Rolling
   Upgrade GameDay；本地 Kind N/N-1 已通过。
4. GPU Helper、硬件编解码、目标云 Media Capacity 与生产容量承诺。
5. Operator List/Watch 已由进度 104 关闭；仍缺 API Server/etcd 故障长稳和目标监控告警。

上述未完成项不得以本轮 Stage B 结果替代，Phase 6 生产退出 Gate 仍保持开启。
