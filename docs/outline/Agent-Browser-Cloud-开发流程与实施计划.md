# Agent Browser Cloud 开发流程与实施计划
## 基于 V16 最终架构的工程落地指南

> 本文档用于把《Agent Browser Cloud / Chromium Runtime Platform 架构设计 V16》转换为可执行的研发流程。
>
> 原则：**V16 是最终目标架构，不是第一期全部开发清单。**
>
> 推荐默认技术栈：
>
> - 控制面：Java 21 + Spring Boot + PostgreSQL + Redis
> - Browser Node：Rust + Tokio
> - Web Console：React + TypeScript
> - 服务协议：OpenAPI + Protobuf
> - 消息总线：MVP 可先使用 PostgreSQL Outbox；规模化后接 NATS JetStream、Kafka 或 Pulsar
> - 本地环境：Docker Compose
> - 集群环境：Kubernetes
>
> 技术栈可以替换，但 V16 冻结的核心原语和权限边界不能随意修改。

---

# 1. 开发目标

第一阶段要形成最小可运行闭环：

```text
创建 Session
→ 启动 Chromium Runtime
→ 绑定 Profile 和网络出口
→ 获取浏览器状态
→ Agent 或用户操作
→ 观察并验证结果
→ 保存 Profile
→ 终止或休眠 Session
```

首个可交付版本不追求：

- 多 Region；
- 完整 Chromium Deep Fork；
- 全量 Runtime Validation Farm；
- 完整成本学习模型；
- 全量企业合规；
- 多语言 SDK；
- 自动化复杂恢复；
- 大规模 Extension Marketplace。

首个版本要优先证明：

1. Session 生命周期可以稳定运行。
2. 同一 Session 不会发生并发写操作。
3. Browser 崩溃能够被发现和恢复。
4. Profile 可以保存和恢复。
5. HumanTakeover 可用且输入不会卡键。
6. Tenant 数据不会互相泄漏。
7. 网页内容不能修改系统策略或扩大 Agent 权限。

---

# 2. 冻结的核心原语

开发过程中不得另起一套平行状态模型。

必须统一使用：

- `SessionContext`
- `ExclusiveOperation`
- `StateCursor`
- `DurableWorkflow`
- `ProfileCheckpoint`
- `RuntimeBuild`
- `ProxyBinding`
- `HumanAuthorizationEvent`

写操作统一经过：

```text
Command
→ Session Coordinator
→ Exclusive Operation
→ Browser Node / Workflow
→ Result Event
→ State Commit
```

禁止：

- 每个模块自行创建分布式锁；
- Browser Node 直接写控制面业务表；
- Agent 直接访问 CDP、Profile 文件或宿主权限；
- Redis 成为唯一事实来源；
- Detection Service 获得输入权限；
- 网页内容直接变成高优先级 Agent 指令。

---

# 3. 建议仓库策略

推荐 Monorepo：

```text
agent-browser-cloud/
├── apps/
│   ├── control-plane/
│   ├── browser-node/
│   ├── web-console/
│   └── cli/
├── packages/
│   ├── contracts/
│   ├── policy-schemas/
│   ├── sdk-typescript/
│   └── test-fixtures/
├── deploy/
│   ├── compose/
│   ├── kubernetes/
│   └── helm/
├── docs/
│   ├── architecture/
│   ├── adr/
│   ├── api/
│   ├── runbooks/
│   └── threat-model/
├── tests/
│   ├── integration/
│   ├── e2e/
│   ├── failure-injection/
│   └── replay-dataset/
└── tools/
    ├── local-dev/
    ├── benchmark/
    ├── schema-check/
    └── release/
```

Monorepo 的好处：

- Contract 统一；
- Java、Rust、TypeScript 共享 Schema；
- 端到端测试更容易；
- MVP 阶段减少跨仓库版本管理；
- 可以统一 CI、发布和变更审查。

---

# 4. 开发环境分层

## 4.1 Local

用途：

- 单开发者调试；
- 单 Browser Node；
- 单 PostgreSQL；
- 单 Redis；
- 本地 Chromium；
- noVNC；
- Mock Proxy Provider。

使用 Docker Compose，但 Browser Node 可直接运行在宿主机，方便调试输入、Display 和 Chromium。

## 4.2 Dev

用途：

- 多开发者集成；
- 最少两个 Browser Node；
- 测试消息总线；
- 测试 Profile 恢复；
- 测试 Coordinator 接管；
- 测试权限隔离。

不使用真实客户数据。

## 4.3 Staging

要求：

- 与生产相同的部署拓扑；
- 独立 KMS 测试密钥；
- 独立 Proxy 测试账户；
- Production-like Replay Dataset；
- 故障注入；
- 性能压测；
- 滚动升级测试。

## 4.4 Production

要求：

- 架构 Gate 全部通过；
- Capacity Certificate；
- Threat Model；
- On-call；
- Runbook；
- SLA / SLO；
- 数据保留策略；
- 回滚方案；
- GameDay 记录。

---

# 5. 开发阶段总览

```text
Phase 0  合同与工程基础
Phase 1  单机 Browser Runtime
Phase 2  Session Coordinator
Phase 3  Profile、Proxy、State 与 HumanTakeover
Phase 4  Agent 基础与安全边界
Phase 5  可靠性、安全与审计
Phase 6  集群密度与 Kubernetes
Phase 7  企业运营能力
```

只有上一阶段达到退出 Gate 后，才能把下一阶段作为主开发重点。

---

# 6. Phase 0：合同与工程基础

## 6.1 目标

先冻结第一版接口，不直接开发全部 V16 服务。

第一版只冻结：

- `SessionContext v1`
- `ExclusiveOperation v1`
- `StateCursor v1`
- `RuntimeManifest v1`
- `NodeCommandEnvelope v1`
- `NodeEventEnvelope v1`
- Session REST API v1
- Browser Node RPC v1
- Error Envelope v1

## 6.2 任务

### 仓库

- 初始化 Monorepo；
- 配置 Java、Rust、TypeScript 构建；
- 配置格式化、Lint、测试；
- 配置统一版本文件；
- 配置 Commit Convention。

### Contract

- 建立 Protobuf；
- 建立 OpenAPI；
- 建立 JSON Schema；
- 建立 Schema Compatibility Check；
- 生成 Java、Rust、TypeScript 类型。

### 数据库

第一批表：

- sessions
- session_contexts
- coordinator_ownership
- exclusive_operations
- outbox_events
- audit_events
- runtime_builds
- profiles

### ADR

至少编写：

- ADR-001：为什么采用 Session Coordinator
- ADR-002：为什么 PostgreSQL 是权威状态
- ADR-003：为什么 Browser Node 不直接写控制面
- ADR-004：为什么 Profile 与 Cache 分层
- ADR-005：为什么控制面 Java、节点 Rust
- ADR-006：为什么采用 At-least-once + Idempotency

## 6.3 退出 Gate

- Contract 可以生成三种语言类型；
- Schema Compatibility CI 生效；
- 数据库可以执行全新安装和升级；
- Control Plane、Browser Node 可互相发送一个 Ping Command；
- 每个核心模块已有 Owner；
- 无阻塞性架构争议。

---

# 7. Phase 1：单机 Browser Runtime PoC

## 7.1 目标

证明 Browser Runtime 主链路成立。

```text
Start Chromium
→ Attach CDP
→ Open Page
→ Capture State
→ Open noVNC
→ Execute Input
→ Stop Chromium
```

## 7.2 实现范围

### Runtime Supervisor

负责：

- 启动 Chromium；
- 生成启动参数；
- 分配 Profile 路径；
- 分配 Display；
- 记录 PID；
- 停止 Runtime；
- 获取 Runtime Health。

### Browser Supervisor

负责：

- Process Health；
- CDP Health；
- Page Health；
- 内存和 CPU；
- Crash Event；
- 基础 Recovery。

### State Collector

只做基础版：

- URL；
- Title；
- 可见可交互元素；
- A11y 摘要；
- 当前 Target；
- State Version。

暂不做：

- 完整 OOPIF；
- BFCache；
- 大型 Resync；
- 高级敏感信息分类。

### Input Sandbox

支持：

- Mouse Move；
- Mouse Down / Up；
- Key Down / Up；
- All-keys-up；
- Input Ledger；
- 断线释放。

### noVNC

支持：

- 用户连接；
- 基础画面；
- 输入转发；
- 会话断开。

## 7.3 必须完成的 PoC

### PoC A：生命周期

- 启动 Chromium；
- 打开测试网站；
- 获取 CDP；
- 终止 Chromium；
- 没有残留进程。

### PoC B：输入

- 鼠标点击；
- 键盘输入；
- Ctrl / Shift 正常释放；
- VNC 断开后执行 All-keys-up。

### PoC C：崩溃

- Kill Renderer；
- Kill Browser；
- Browser Supervisor 能正确分类；
- 产生 Crash Event。

## 7.4 退出 Gate

- 连续启动、终止 500 次无明显资源泄漏；
- Browser Crash 可检测；
- noVNC 可连接；
- Key Up Loss 测试通过；
- Agent Worker 无法访问宿主 Shell；
- Runtime 运行目录隔离。

---

# 8. Phase 2：Session Coordinator

## 8.1 目标

建立控制面最关键的串行状态机。

```text
Client
→ Session API
→ Priority Mailbox
→ Session Coordinator
→ Exclusive Operation
→ Browser Node Command
→ Result Event
→ Commit
```

## 8.2 最小功能

### Session Service

- Create Session；
- Start Session；
- Get Session；
- Terminate Session；
- 列表和过滤。

### Coordinator

- Session Context；
- Exclusive Operation；
- State Cursor；
- Command Validation；
- Node Event；
- Operation Commit / Abort；
- Coordinator Term。

### Mailbox

首版可简单分为：

- Critical
- Interactive
- Normal
- Maintenance

不必立即实现完整 Weighted Fair Queue，但接口需要预留。

### 幂等

- API Idempotency Key；
- Node Command ID；
- Operation ID；
- Event Inbox；
- PostgreSQL Outbox。

## 8.3 非阻塞要求

Coordinator 不能等待：

- Chromium 启动；
- Profile Flush；
- Snapshot 上传；
- Proxy 探测；
- 大型 State Snapshot。

首版长任务可以使用：

- Background Worker；
- Completion Event；
- Phase Deadline；
- 简单 Durable Workflow 表。

## 8.4 故障测试

- Coordinator 在 Command 发出后崩溃；
- Node 重复收到同一 Command；
- Completion Event 重复；
- Completion Event 丢失；
- 新 Coordinator 接管；
- 旧 Coordinator 命令被拒绝。

## 8.5 退出 Gate

- 同一 Session 最多一个 Active Operation；
- Coordinator 重启后 Session 可继续；
- 重复命令不重复创建 Browser；
- PostgreSQL 是权威状态；
- Redis 清空不会导致永久数据丢失；
- Emergency Terminate 能优先处理。

---

# 9. Phase 3：Profile、Proxy、State 与 HumanTakeover

## 9.1 Profile MVP

先实现：

- Core / Ephemeral 目录分层；
- 活跃 Profile 单 Writer；
- Profile Checkpoint；
- Manifest；
- Commit Marker；
- 基础 Integrity Check；
- Restore。

暂不实现：

- 完整 Warm Tier Delta Journal；
- 所有数据库 Adapter；
- 跨 Region Archive。

## 9.2 Profile 恢复 PoC

```text
启动浏览器
→ 登录自建测试站点
→ 保存 Profile
→ 销毁 Runtime
→ 新 Runtime 恢复
→ 验证登录状态
```

恢复结果必须区分：

- Technical Ready
- Login Required
- Business Ready

## 9.3 Proxy MVP

先实现：

- Static Proxy Provider Adapter；
- Proxy Allocation；
- Proxy Binding；
- Exit IP 验证；
- 默认禁止直连；
- Release；
- Provider Circuit Breaker。

暂不实现：

- Provider Learning Model；
- 多供应商自动优化；
- 复杂连接迁移。

## 9.4 State Gateway MVP

支持：

- Current State；
- State Version；
- Target Ref；
- Diff Event；
- DiffTruncated；
- Full / Region Resync；
- State Quality。

## 9.5 HumanTakeover

支持：

- 获取控制权；
- Agent 暂停；
- 原始 Desktop Input；
- Frame Age；
- Input Ledger；
- Release；
- 结束后 Resync。

## 9.6 退出 Gate

- Profile 可恢复；
- Cache 不进入默认归档；
- Proxy 失败不回退直连；
- HumanTakeover 可抢占 Agent；
- State Invalid 时 Agent 不执行；
- 人工接管结束后 Agent 必须重新获取状态；
- Tenant A 无法访问 Tenant B 的 Session。

---

# 10. Phase 4：Agent 基础与安全边界

## 10.1 Agent MVP

首版只实现：

- 单 Planner；
- 单 Executor；
- Intent Guard；
- Plan Validator；
- Execution Strategy Selector；
- Action Validation；
- Replan Budget。

不必立即实现完整 Multi-Agent。

## 10.2 Tool API

首批 Tool：

- navigate
- get_current_state
- click_target
- type_text
- scroll
- wait_for
- get_url
- get_page_summary
- request_human_takeover

Agent 无法直接调用：

- CDP Raw Command
- Shell
- Profile Files
- Cookie Database
- Vault
- Kubernetes
- Node Helper

## 10.3 Prompt Injection MVP

必须从第一版 Agent 就实现：

- Instruction Source；
- Trust Level；
- Web Content 默认 Untrusted；
- Context Partition；
- High-risk Sink；
- Tool Capability Token；
- Plan Provenance；
- Prompt Security Event。

测试样例：

- 网页要求忽略系统指令；
- 邮件要求上传文件；
- 文档要求读取 Cookie；
- 页面伪造管理员确认；
- 隐藏 DOM 中包含工具调用提示。

## 10.4 数据最小化

首版至少实现：

- Password 不进入 Agent Context；
- Cookie 不进入 Agent Context；
- OTP 不进入 Agent Context；
- Email / Phone 基础 Mask；
- Debug Log Redaction。

## 10.5 退出 Gate

- Agent 能完成自建表单流程；
- Agent 不能访问未授权域名；
- 网页指令无法扩大工具权限；
- 高风险动作需要确认；
- Planner 有 Replan 上限；
- Action 必须经过结果验证；
- Prompt Injection 测试集通过。

---

# 11. Phase 5：可靠性、安全与审计

## 11.1 Durable Workflow Stage A

实现：

- Workflow Record；
- Phase Deadline；
- Durable Deadline Scanner；
- Idempotency；
- Stale Callback Reject；
- Commit Marker；
- 基础 Compensation；
- DeadLetter。

暂不立即实现：

- 跨 Region Workflow；
- 自动化复杂补偿；
- 全局 Workflow Scheduler。

## 11.2 安全

- Threat Model；
- Cross-tenant Test；
- Node Helper 权限拆分；
- Runtime Signature；
- SBOM；
- Key Rotation；
- Break-glass；
- Admin MFA；
- Incident Runbook。

## 11.3 审计

必须保存：

- Session Context Commit；
- Operation Transition；
- Human Authorization；
- Admin Access；
- Security Event；
- Runtime Release；
- Key Rotation；
- Profile Restore。

## 11.4 故障注入

- Coordinator Kill；
- Browser Node Kill；
- Worker Kill；
- PostgreSQL 短暂不可用；
- Redis 清空；
- Proxy Provider 故障；
- Profile Chunk 丢失；
- Key Up 丢失；
- DiffTruncated；
- Object Storage 超时。

## 11.5 退出 Gate

- 核心故障均有确定恢复路径；
- Workflow 不会永久卡死；
- Profile 损坏不会误报成功；
- 安全审计可查询；
- Node 高权限组件已拆分；
- Threat Model 关键控制有测试。

---

# 12. Phase 6：集群密度与 Kubernetes

## 12.1 Coordinator Density

增加：

- Shard；
- Virtual Actor；
- Shared Timer Wheel；
- Actor Passivation；
- Hot Tenant Virtual Partition；
- Online Capacity Feedback。

## 12.2 Browser Density

增加：

- Resource Class；
- Extension Weight；
- Node Admission；
- Anti-affinity；
- Capacity Benchmark；
- Node Pressure Handling。

## 12.3 Kubernetes

增加：

- 独立 Browser Node Pool；
- Browser RuntimeClass；
- CNI Egress；
- CSI Warm Tier；
- Browser Operator 基础版；
- BrowserSession CRD；
- Rolling Upgrade。

## 12.4 退出 Gate

- Coordinator Capacity Certificate；
- Browser Capacity Certificate；
- Shard 热点可迁移；
- Node 超载不会拖垮全局；
- Browser Node 与控制面分池；
- Rolling Upgrade 通过。

---

# 13. Phase 7：企业运营能力

按客户需求逐步增加：

- Runtime Validation Farm；
- Production-like Replay Dataset；
- Runtime Compatibility Matrix；
- Cost-aware Scheduler；
- Cost Explainability；
- SLA / Error Budget；
- Retention Policy；
- Compliance Service；
- Recovery GameDay；
- Media Resource Class；
- 高权限 Extension Isolation；
- Multi-region；
- DR；
- Terraform；
- 多语言 SDK。

---

# 14. Epic 建议

建议项目管理系统中建立以下 Epic：

1. Platform Contracts
2. Session Lifecycle
3. Session Coordinator
4. Browser Runtime
5. Browser Supervisor
6. Desktop Input
7. Remote Desktop
8. Profile Storage
9. Proxy and Egress
10. Browser State
11. Agent Tool API
12. Agent Safety
13. HumanTakeover
14. Durable Workflow
15. Security and Isolation
16. Observability and Audit
17. Capacity and Benchmark
18. Kubernetes and Operator
19. Runtime Validation
20. Cost and Billing
21. Compliance and Retention

每个 Epic 必须有：

- Owner
- Scope
- Non-goal
- API / Schema
- Threat
- Test
- Exit Gate
- Rollback

---

# 15. 第一批建议任务

## Contracts

- 定义 `SessionContext v1`
- 定义 `ExclusiveOperation v1`
- 定义 `NodeCommandEnvelope v1`
- 定义 `NodeEventEnvelope v1`
- 定义 Error Code
- 建立 Schema CI

## Control Plane

- Session Create API
- Session Start API
- Session Terminate API
- Session Repository
- Coordinator Ownership
- Operation Repository
- Outbox Publisher
- Inbox Deduplication

## Browser Node

- Runtime Supervisor
- Chromium Launcher
- CDP Probe
- Process Monitor
- Input Sandbox
- noVNC Adapter
- Node Journal
- Command Dedup

## Profile

- Profile Directory Layout
- Core / Ephemeral Split
- Checkpoint Manifest
- Commit Marker
- Restore Test

## State

- Current State
- Target Ref
- State Version
- Basic Collector
- DiffTruncated
- Resync

## Security

- Tenant Context Filter
- Agent Tool Allowlist
- Instruction Source
- Web Content Trust Label
- Password Redaction
- Basic Audit

---

# 16. 分支与发布策略

推荐：

- `main`：始终可发布；
- 短生命周期 Feature Branch；
- Pull Request；
- 必须通过 CI；
- 不使用长期 `develop` 分支。

Release：

```text
Commit
→ Unit Test
→ Contract Test
→ Integration Test
→ Build Artifact
→ Security Scan
→ Dev Deploy
→ Staging
→ Canary
→ Stable
```

Runtime Build 与 Control Plane 独立版本化。

版本示例：

- control-plane: `0.1.0`
- browser-node: `0.1.0`
- contracts: `1.0.0`
- runtime-build: `chromium-001`
- schema: `session-context/v1`

---

# 17. CI/CD

## Pull Request

必须执行：

- Java Test
- Rust Test
- TypeScript Test
- Format
- Lint
- OpenAPI Check
- Protobuf Compatibility
- SQL Migration Check
- Dependency Scan
- Secret Scan

## Main

增加：

- Integration Test
- Docker Image Build
- SBOM
- Image Signature
- E2E
- Local Replay Dataset
- Artifact Publish

## Release

增加：

- Staging Deploy
- Failure Injection
- Capacity Smoke
- Upgrade / Rollback
- Canary
- Release Gate

---

# 18. 测试策略

## Unit Test

覆盖：

- 状态转换；
- Policy；
- Version Check；
- Error Mapping；
- Strategy Selection；
- Redaction。

## Contract Test

覆盖：

- Java / Rust / TypeScript Schema；
- N / N-1；
- API Error；
- Event Envelope。

## Integration Test

覆盖：

- Coordinator + PostgreSQL；
- Coordinator + Browser Node；
- Profile + Chromium；
- Proxy + Egress；
- State + Agent。

## E2E

覆盖：

- Session Lifecycle；
- HumanTakeover；
- Agent Form；
- Profile Restore；
- Browser Crash；
- Prompt Injection。

## Property-based Test

覆盖：

- Workflow State Matrix；
- Operation State；
- Coordinator Recovery；
- Input Sequence。

## Failure Injection

覆盖：

- Kill
- Timeout
- Duplicate
- Reorder
- Network Partition
- Disk Full
- Object Missing
- Clock Change

---

# 19. Definition of Done

一个功能只有同时满足以下条件才算完成：

- 代码已合并；
- Contract 已更新；
- Migration 已更新；
- Unit / Integration Test；
- Threat Review；
- Observability；
- Error Code；
- Runbook；
- Ownership；
- Rollback；
- 文档；
- 不破坏 N / N-1；
- 在 Staging 验证。

---

# 20. 研发度量

跟踪：

- Lead Time
- Change Failure Rate
- Mean Time to Recovery
- Test Flakiness
- API Compatibility Failure
- Browser Crash Rate
- Coordinator Mailbox Delay
- Profile Restore Success
- Prompt Injection Block Rate
- Cross-tenant Test Result
- Capacity Drift
- Cost per Active Session

避免只用“完成了多少接口”衡量进度。

---

# 21. 团队最小配置建议

小团队 MVP 可以合并角色，但职责不能消失。

最低建议：

- 1 名平台/控制面开发
- 1 名 Browser Node/Rust 开发
- 1 名前端/Console 开发
- 1 名测试或测试开发
- 安全和运维可由资深成员兼任

完整 V16 需要逐步扩展：

- Runtime
- Control Plane
- Node
- Agent Safety
- Storage
- Network
- SRE
- Security

第一阶段不要同时建立所有团队。

---

# 22. 开发决策原则

遇到设计选择时，按以下顺序判断：

1. 是否破坏安全边界？
2. 是否破坏核心原语？
3. 是否影响数据一致性？
4. 是否能恢复？
5. 是否可观测？
6. 是否有明确成本？
7. 是否是 MVP 必需？
8. 是否可以延后？

优先选择：

- 简单；
- 可测试；
- 可回滚；
- 权限更小；
- 数据更少；
- 状态更明确；
- 依赖更少。

---

# 23. 开工检查清单

正式编码前确认：

- [ ] V16 架构文档进入只读冻结
- [ ] Phase 0 范围确定
- [ ] 技术栈确定
- [ ] Monorepo 创建
- [ ] Contract v1 创建
- [ ] 数据库迁移创建
- [ ] Owner 分配
- [ ] ADR 创建
- [ ] CI 生效
- [ ] 本地 Chromium PoC 可运行
- [ ] 测试站点准备
- [ ] 不使用真实客户数据
- [ ] Threat Model 初版建立

完成以上项目后，即可进入正式迭代。
