# Phase 4—7 大纲缺口审计

> 历史审计：本文件保留当时的缺口基线，不代表当前状态。Phase 6/7 后续实现已关闭其中
> 多项缺口；当前结论以
> [当前未实现清单](33-当前未实现清单.md)、
> [Phase 7 企业运营核心验收](38-Phase7-企业运营核心验收.md) 和
> [Phase 4—7 本地最终验收](39-Phase4-7-本地最终验收与生产Gate.md) 为准。
>
> 审计日期：2026-07-26
> 审计口径：代码、数据库迁移、部署清单和可重复测试必须同时有证据；只有接口、
> 页面、字段、Kubernetes YAML 或本地 Fixture 时，不计为生产 Gate 已关闭。

## 历史结论（已被后续实现取代）

| 范围                          | 当前状态                                                     | Gate                       |
| ----------------------------- | ------------------------------------------------------------ | -------------------------- |
| Phase 4：Agent 基础与安全边界 | 开发计划中的 MVP 已闭环；V16 增强项仍缺                      | MVP 已关闭，生产增强未关闭 |
| Phase 5：可靠性、安全与审计   | Durable Workflow、身份、mTLS 和哈希审计主链路可运行          | 未关闭                     |
| Phase 6：密度与 Kubernetes    | 路由、Mailbox、容量 Hysteresis、CRD/Operator/Kind E2E 已实现 | 未关闭                     |
| Phase 7：企业运营             | 除只读 Runtime Registry 外，核心能力未实现                   | 未开始验收                 |
| Web Console                   | Session/Profile/Proxy/Agent/Audit/Runtime 已接真实 API       | 非生产就绪                 |

当前仓库是可重复运行的工程 PoC，不是 V16 生产完成版。尤其不能把
`runtime_builds` 表、一个 `STABLE` 本地 Seed 和只读页面等同于 Runtime Validation
Farm，也不能把 Kubernetes 清单等同于真实集群容量证书。

## Phase 4：仍未实现的 V16 增强项

开发计划 10.5 的 MVP Gate 已有真实 E2E 证据，但以下能力仍缺：

1. 高级 Action Validation DSL：Network、Toast、Dialog、Visual、Login、
   Business Entity，以及 All/Any/Sequence/Negative 组合表达式。
2. 独立 Agent Worker Sandbox。Planner/Executor 当前在 Control Plane 进程内运行，
   没有独立 UID、无宿主权限 Worker、固定 IPC 和单独故障域。
3. Reviewer Agent、真实模型 Provider/模型治理和 Production-like Agent Replay。
   当前是受限规则 Planner，不是可处理任意网站目标的通用智能体。
4. Challenge Detection 与一次性 HumanAssist。现有 HumanTakeover 是完整人工接管，
   不能代替 `allowed_action_count=1` 的挑战单击授权。
5. 已下发 Node 动作的协作取消协议、复杂补偿和跨 Region Agent Workflow。
6. Purpose-bound 截图访问后续已由进度 87 关闭；仍缺完整 State 数据分类和截图敏感
   区域模糊。

## Phase 5：生产 Exit Gate 缺口

| 缺口                 | 代码事实                                                                                                                                   | 验收要求                                                                         |
| -------------------- | ------------------------------------------------------------------------------------------------------------------------------------------ | -------------------------------------------------------------------------------- |
| Node Helper 权限拆分 | Network/Storage Helper 已独立进程、固定有界 IPC、Peer UID、不同容器 UID/seccomp/Capability Drop、独立卷，并通过 Kill/恢复测试；GPU 未实现  | 补 GPU、LSM Profile、独立 Audit Identity 与真实集群跨 UID/互相崩溃验收           |
| Break-glass          | 双人审批、限时、撤销、Review，以及一次性/单 Operator/最小投影/逐次证据的 Secure Debug 数据面和 UI 已完成；独立 Worker 与像素录像未完成     | 补独立 UID Worker、WORM Recording Manifest 和真实集群 Kill/撤销演练              |
| 审计事件覆盖         | 八类必需事件已全部接入并通过集成链验证                                                                                                     | 已关闭                                                                           |
| 制品真实验签         | Runtime Policy 已验证 Ed25519 Provenance；CI 已对四个 GHCR 镜像执行 Keyless Cosign 签名与 SPDX Attestation                                 | 补 Offline Root/HSM、Control Plane OCI Digest 复算、Admission 强制验证与撤销演练 |
| 供应链发布           | Run `30195955615` 已生成四个 Digest 镜像、签名/Attestation、SBOM Hash 绑定和签名 Kustomize 发布包                                          | 补 N/N-1 兼容、Admission Policy 和生产回滚演练                                   |
| 故障矩阵             | 已覆盖 Browser/Node、PostgreSQL、S3/MinIO 超时、Coordinator term=2/3/4、HumanTakeover 全阶段、Agent pending/TYPE_TEXT 已执行未提交、Runtime 生命周期 Kill/Reconcile、Remote Desktop 双向 TCP 分区、Proxy、Profile Corruption、Key-up、Diff、Workflow DLQ | 补目标云单向网络分区及 Provider 演练       |
| mTLS 生命周期        | CA 内节点证书轮换已测                                                                                                                      | 在线 Root 双写、CRL/SPIFFE 撤销和过期证书演练                                    |
| 审计生命周期         | Retention/Legal Hold 字段已落库                                                                                                            | 真正的 Hold 工作流、删除 Receipt、签名 Export Manifest                           |

因此 Phase 5 只能判定为“Stage A 主链路完成”，不能判定 Exit Gate 关闭。

## Phase 6：生产 Exit Gate 缺口

1. Coordinator Capacity Certificate：
   - 50k Actor、250k Route、10k 满 Mailbox 的单进程 Stage A 证书已绑定 Build、
     负载模型、JVM/OS、P95/P99、Shard 分布和证书 Hash，并进入 `make ci`；
   - 尚缺端到端 Emergency Control P99、Mailbox Byte Budget、Passivation/恢复、
     PostgreSQL/gRPC 和容器 GC 长稳压测；
   - 目标云证书仍未完成。
2. Browser Capacity Certificate：
   - Extension Weight、未知扩展 Probation、持续 P95 Profile 未实现；
   - PSI/Cgroup Burst 深度采样、Node Pressure 驱逐和安全余量验证未实现；
   - Chrome 150 的 500 次顺序 Runtime 循环已通过并生成 Build-bound Hash 证书；
   - Linux 资源硬限制、桌面/并发密度和目标云证书仍未通过。
3. Hot Tenant/Shard：
   - Route Epoch 和安全点模型已存在；
   - 缺双 Coordinator 实例热点迁移、旧 Epoch 拒绝和跨 Shard 压测。
4. Kubernetes：
   - CRD/Operator 已在临时 Kind 集群真实运行，双副本 Lease Leader Election、
     CRD admission、RBAC、Leader Kill 接管与 Finalizer E2E 已通过；
   - 尚缺目标云多节点安装、Watch/Informer 长稳、API Server 故障和 N/N-1 升级验收；
   - CNI 防直连与 CSI 应用一致性 Adapter 只有清单，无目标云实测；
   - 没有 N/N-1 Rolling Upgrade GameDay。
5. Media/GPU Capacity 和 Extension Anti-affinity 未进入调度模型。

## Phase 7：尚未实现

| 能力组                    | 当前事实                                                | 缺少的完成证据                                                                              |
| ------------------------- | ------------------------------------------------------- | ------------------------------------------------------------------------------------------- |
| Runtime Validation        | 有 Registry、发布/禁用双人治理 API、只读 UI 和本地 Seed | Build 创建/验证、Validation Farm、隔离 Worker、能力观测、Persona 一致性                     |
| Replay/Compatibility      | 无生产近似数据集和矩阵服务                              | 数据授权/脱敏、版本绑定、Chromium Major 矩阵、Profile Corruption Replay                     |
| Cost                      | 无成本表、Rate Card 或 Scheduler                        | Cost-aware Placement、可复算 Breakdown、版本化 Rate Card、隔离策略不可降级                  |
| SLA                       | 无 SLI/SLO/Error Budget 服务                            | Exclusion、Burn Rate、发布冻结和 Console 可观测                                             |
| Retention/Compliance      | 只有审计字段                                            | Policy Engine、Legal Hold 工作流、删除 Receipt、Residency Gate、License Inventory、签名导出 |
| Recovery GameDay          | 无 GameDay 领域模型和 Runner                            | 场景编排、RTO/RPO 自动记录、失败冻结发布                                                    |
| Media/Extension Isolation | 无独立调度与配额                                        | Encoder/Bitrate 配额、独立 Browser/Profile、Privileged Extension 禁止混部                   |
| Multi-region/DR           | 未实现                                                  | Region Authority、复制/故障切换、RPO/RTO、跨 Region Workflow                                |
| IaC                       | 无 Terraform 目录/模块                                  | 网络、数据库、对象存储、Kubernetes、密钥和可回滚环境                                        |
| SDK                       | `apps/cli` 为空，无生成 SDK                             | 至少一种正式 SDK、版本协商、重试/幂等策略；再扩展多语言                                     |

## Web Console 与真实使用缺口

已接真实 API 的页面包括 Session、Profile、Proxy、Agent、Runtime、Logs 和 Security。
本轮已验证 Runtime Registry、可验证审计链和事件流页面。

仍未完成：

1. Nodes、Extensions、Groups 仍直接读取 `src/mocks/data.ts`；生产导航和直接路由
   已 fail-closed，但正式领域模型/API 尚未实现。
2. Session 创建向导不是大纲中的完整九步，缺 Persona、Extension、Agent Policy 等正式
   后端契约。
3. Session/State/Audit 仍以轮询为主，没有统一 SSE/WebSocket 事件管理器和序列校验。
4. 前端标准 OIDC 会话、动态 Bearer、Claim 身份、角色路由和操作 Gate 已完成，并以
   生产 Viewer E2E 验收；尚缺真实企业 IdP 的 Metadata、Claim、MFA/ACR 与 Logout 联调。
5. API Client 仍手写，没有从 OpenAPI 生成并进行 N/N-1 契约兼容验证。
6. 全局搜索、通知、主题和用户菜单仍为禁用或静态状态。
7. 完整浏览器 E2E 尚未进入 GitHub Actions。
8. 390×844 移动端布局和基础导航语义已进入 E2E；仍缺 1280×800、1440×900、
   1920×1080 视觉回归，以及完整键盘和屏幕阅读器验收。

## “真实网址”和 Agent 控制的当前边界

- 集成与 Web E2E 主要使用 `fake-chromium.sh`、本地 HTTP Proxy 和本地 RFB Server，
  可重复验证控制链路，但不代表真实公网兼容性。
- 仓库有可选的真实 Chromium CDP/State 测试，当前验证的是本地测试页。
- 尚未形成对多个真实网址的导航、登录、Canvas、下载、弹窗、跨域、BFCache、
  Prerender、超时和反自动化兼容矩阵。
- Agent 已能受控执行 Navigate、State Read、Click、Type、Scroll、Wait 和
  Human Handoff；它不是可对任意真实网址自主规划的通用 Agent。

在运行真实网址验收前，需要使用授权测试站点和专用测试账户，不能把绕过验证码、
反自动化或网站安全控制作为验收目标。

## 当前可重复证据

本轮通过：

```bash
./gradlew -p apps/control-plane test
pnpm --dir apps/web-console lint
pnpm --dir apps/web-console test
pnpm --dir apps/web-console build
make contracts-check
make test-integration
make test-postgres-outage
make test-e2e
```

集成输出确认 `runtime_registry=true`、`internal_grpc_mtls=true`、
`node_certificate_rotation=true`、`durable_workflows=13`、
`workflow_dead_letters=1`、`break_glass_dual_approval=true`、
`coordinator_failover_term=2`、`coordinator_final_term=4`、
`coordinator_agent_side_effect_once=true`、`automatic_crash_recovery=3`、
`node_restart_reconciliation=4`、
`break_glass_cross_tenant=404`、`break_glass_reviewed=true`、
`break_glass_expiry_persisted=true`、`secure_debug_minimized=true`、
`secure_debug_single_operator=true`、`secure_debug_cross_tenant=404`、
`secure_debug_evidence_chain=true`、`secure_debug_revocation_closed=true`、
`audit_chain_valid=true` 和 96 条审计事件。
浏览器输出确认 `WEB_CONSOLE_E2E_OK` 和 `real_web_console_e2e=true`，覆盖
Runtime、Security、Logs 以及既有 Session、Agent、HumanTakeover、Profile、Proxy 流程；
Break-glass 真实表单和 Secure Debug 启动/最小快照/结束均可操作，且页面无
Console/HTTP 异常。

## 建议实施顺序

1. P0：为现有 Network/Storage Helper 补 LSM/真实集群验收，按需实现 GPU Helper，
   将已完成的 Secure Debug 治理数据面拆为独立 Worker/强制录像，并补 Offline
   Root/HSM、Admission 强制验证、回滚演练和目标云 Object Storage/IAM。
2. P0：完成 Profile Business Ready、基础设施出口防逃逸和真实 Provider 故障演练。
3. P0：完成 Phase 6 Browser/Coordinator Capacity Certificate 与真实集群
   Rolling Upgrade。
4. P1：建设 Runtime Validation Farm + Compatibility/Replay，作为 Phase 7 第一条主线。
5. P1：建设 Cost/SLA/Retention/Compliance/GameDay。
6. P1：替换 Nodes/Extensions/Groups Fixture，接入 RBAC UI、实时事件和生成 Client。
7. P1：在授权站点上建立多网址 Agent/浏览器兼容矩阵。
8. P2：Multi-region/DR、Terraform 和多语言 SDK。
