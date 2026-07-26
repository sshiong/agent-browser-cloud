# Proxy MVP 与出口闭环

> 状态：Phase 3 / 9.3 技术 MVP 已完成；生产级出口隔离、多 Provider 与质量/成本学习仍待开发。
> 日期：2026-07-26

## 已完成

### Control Plane

- 新增 V004 `proxy_allocations` 增量迁移，持久化 Allocation、Session、Provider、
  Endpoint、状态、观察出口、验证时间、释放时间与失败原因。
- Session 启动前创建或恢复唯一活跃 Allocation，并将 `proxy_binding_id` 写入权威
  Session Context；绑定变化同时递增 `context_epoch` 与 `network_revision`。
- `StartRuntimeCommand` 携带 Proxy Binding；`RuntimeStartedEvent` 回传 Binding、
  Exit IP、Country 与 ASN。
- Node Event Ingestion 校验 Tenant、Session、Binding 和预期出口完全一致后，才把
  Allocation 从 `ALLOCATED` 提交为 `BOUND`。
- Runtime 正常停止后把活跃 Allocation 提交为 `RELEASED`。
- 新增租户隔离的 `GET /api/v1/proxies`，返回 Static Provider 配置状态和 Allocation
  账本；Session 读模型同步返回 `proxyBindingId`。
- 生产环境禁止 `proxy.allow-direct=true`，且缺少 Static Proxy Endpoint 时拒绝启动。

### Browser Node 与 Runtime

- Rust `network-helper` 实现 Static HTTP Proxy 的 bind、出口验证、release 与本地
  Circuit Breaker；连续失败达到阈值后在冷却窗口内拒绝新绑定。
- Static Endpoint 只接受 `http://host:port`，拒绝 URL 内嵌 Credential、Path、
  Query 和 Fragment，避免 Secret 进入命令、日志与进程参数。
- 出口探测强制通过已绑定代理访问 JSON Check Endpoint，并要求观察 IP 与预期 IP
  精确匹配；验证失败时 Runtime 不启动，不回退直连。
- Chromium 启动参数注入 `--proxy-server` 和
  `--proxy-bypass-list=<-loopback>`；Runtime 停止时释放 Node 内绑定。
- Browser Node 生产启动时拒绝 `ALLOW_DIRECT_NETWORK=true`，并要求配置
  `NETWORK_HELPER_SOCKET`；Static Provider Endpoint 与出口探测配置只交给独立
  Network Helper。

### Web Console

- “代理与出口”页面已删除 Fixture，接入真实 Provider/Allocation API。
- 页面展示 Active Allocation、Verified Exit、Direct Fallback、Provider 配置、
  观察出口、位置/ASN、验证时间及 Allocation 生命周期。
- 补齐 Loading、Empty、Error、桌面表格与移动端卡片状态。

## 验收证据

已通过：

```bash
./gradlew -p apps/control-plane test
cargo test --locked --manifest-path apps/browser-node/Cargo.toml -p network-helper
cargo test --locked --manifest-path apps/browser-node/Cargo.toml -p node-agent
cargo test --locked --manifest-path apps/browser-node/Cargo.toml -p runtime-supervisor
pnpm --dir apps/web-console test
pnpm --dir apps/web-console lint
pnpm --dir apps/web-console build
make test-integration
make test-e2e
```

测试覆盖：

- Static Provider 分配后，Context Epoch 和 Network Revision 同步递增。
- Node 观察出口与预期出口不一致时拒绝 `BOUND`。
- 生产环境无法开启直连回退。
- Circuit Breaker 达到阈值后打开并拒绝请求。
- 假 Chromium 缺少 Proxy 参数时直接失败，防止参数接线退化。
- 集成链路实际经过本地 HTTP Proxy 完成出口探测，确认观察出口为
  `203.0.113.10`，且 Session 终止后 Allocation 为 `RELEASED`。
- Web E2E 在运行中 Session 上观察到 Provider `CONFIGURED`、Allocation `BOUND`、
  出口 IP 和 Direct Fallback `DENIED`。
- Network Helper 已拆为独立进程；Node Agent 仅通过固定有界 Unix IPC 调用，不再持有
  Provider Endpoint。Helper Kill 时 Runtime 在 Chromium 启动前失败且不回退直连，
  Helper 独立恢复后控制面重试成功。

## 仍未完成

| 缺口 | 说明 |
|---|---|
| 基础设施级禁止直连 | 当前已在 CP、Node、独立 Network Helper、Chromium 参数和 Kubernetes NetworkPolicy 清单层 fail-closed，但尚未在真实集群用 eBPF/宿主机防火墙证明任何被攻陷进程都无法绕过代理 |
| 真实 Provider 与 Secret | 尚无供应商 Adapter 接口族、短期 Credential、Vault 引用、轮换和独立测试账户 |
| 分布式 Provider 健康 | Circuit Breaker 当前是 Node 本地内存状态，尚无共享健康状态、探测调度、告警和跨节点熔断 |
| 连接迁移 | 尚无显式 Proxy Transition、连接 Drain、旧新代理隔离、失败回滚和 DNS/WebRTC/QUIC 泄漏矩阵 |
| 质量与成本学习 | Reputation、成功率归因、Provider Score、成本计量与多供应商选择尚未实现 |
| 失败事件审计 | 同步启动失败会回滚 Allocation，但尚无独立 `ProxyFailure` 事件、失败账本和运营告警闭环 |
| 真实外部网络验收 | 当前使用可重复的本地 Proxy Fixture；仍需使用独立真实 Proxy 账户和真实 Chromium 完成出口、DNS 与断网故障演练 |

## 结论

开发计划 9.3 所列 Static Provider、Allocation、Binding、Exit IP 验证、默认禁止直连、
Release 和 Provider Circuit Breaker 已具备可重复的技术 MVP 验收。该结论不等于
V16 的生产级 Network/Proxy Gate 已完成；上述基础设施隔离、Secret、Provider
治理和故障运营项仍是发布阻塞项。
