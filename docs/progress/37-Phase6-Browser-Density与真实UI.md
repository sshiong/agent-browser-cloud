# Phase 6：Browser Density、资源硬限制与真实 UI

> 状态：Stage B 已通过；并发容量、压力驱逐和目标集群 Gate 仍未关闭  
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

## 尚未完成

1. 目标 Linux 节点上真实委派 Cgroup v2、OOM/PSI 压力及受控驱逐证书。
2. 多个 Browser Node 同时在线时的真实命令路由故障注入与并发 Chromium 容量证书。
3. Extension 持续自适应采样、P95 偏离升权和采样 CPU Budget。
4. Hot Tenant 迁移长稳、目标云 CNI/CSI、Browser Node Pool 与 Rolling Upgrade
   GameDay。
5. GPU/Media Capacity 与目标云生产容量承诺。

上述未完成项不得以本轮 Stage B 结果替代，Phase 6 生产退出 Gate 仍保持开启。
