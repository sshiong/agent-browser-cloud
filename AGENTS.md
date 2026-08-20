# Agent Browser Cloud 项目交接与开发约定

> 更新日期：2026-08-20
> 基准分支：`main`
> 编写时基准提交：`54b28ea fix: rebind autonomous action batches`
> 适用范围：本仓库全部目录。子目录若以后出现更具体的 `AGENTS.md`，以更深层文件为准。

## 1. 接手时必须先做

1. 阅读本文件，然后执行 `git status --short --branch` 和 `git log -12 --oneline --decorate`。
2. 阅读与当前任务直接相关的代码、测试、数据库迁移、OpenAPI/Protobuf 契约和最新进度文档。
3. 判断状态时采用以下优先级：**当前代码与可重复测试证据 > 最新进度文档 > 旧进度文档 > 聊天或历史计划**。
4. 若本文件、进度文档与代码不一致，以代码为准，并在同一改动中更新本文件和对应进度文档。
5. 不重新讨论或重做本文中标记为“已确认”且已有代码证据的架构和能力。

## 2. 项目定位、目标与当前阶段

### 项目名称与定位

**Agent Browser Cloud** 是以受控 Chromium Runtime 为核心的企业级、多租户浏览器基础设施平台。它为 Agent 自动化、真人远程协作、持久会话、Profile、代理出口、状态恢复、资源调度、安全治理和企业运营提供统一控制面、Browser Node 数据面与 Web/Desktop 管理端。

### 最终目标

交付满足 `docs/outline/` 中 V16 架构、安全威胁模型、治理闭环和生产运营要求的正式产品：

- 支持大规模、可恢复、可审计的浏览器 Session 和 Agent Workflow；
- Web 为主入口，同一套 React UI、API Client、权限和状态逻辑复用于 Tauri 2 Windows/macOS；
- PostgreSQL 是控制面权威状态，所有关键写入幂等、可审计、可恢复，并通过 Operation/Outbox/Inbox/Node Journal 执行；
- 达到真实企业 IdP、目标 Linux/云、多 Region、供应链、组织审批和长期稳定性 Gate 后才允许处理真实客户数据。

### 当前阶段目标

仓库内 Phase 4 MVP、Phase 6 本机容量与 N/N-1、Phase 7 企业运营核心已闭环；当前阶段是：

1. 关闭仍存在的代码产品化缺口；
2. 移除有损轮询并建立覆盖完整业务域的权威可续传事件源；
3. 补齐目标环境长稳、真实外部系统集成和组织发布 Gate。

**当前产品状态：尚未通过 V16 全量生产发布 Gate，不得据此处理真实客户数据。**

## 3. 当前技术栈

| 层级 | 技术 |
| --- | --- |
| Control Plane | Java 21、Spring Boot 3.2、Spring Security/OAuth2 Resource Server、JDBC/JPA、Gradle Kotlin DSL |
| Browser Node | Rust 2021、Tokio、Tonic/gRPC、Chromium/CDP、Cgroup v2、x11vnc/noVNC、S3-compatible object store |
| Web Console | React 19、TypeScript 5.7、Vite 6、React Router 8、TanStack Query/Table、Zustand、Tailwind CSS 4、Radix UI、Vitest/Playwright |
| Desktop | Tauri 2；复用 Web UI；OS Vault、系统浏览器 OIDC/Deep Link、Updater Gate |
| 数据与消息 | PostgreSQL 17 + Flyway、Redis 7、事务 Outbox/Inbox、PostgreSQL 单调游标 SSE |
| 契约与 SDK | OpenAPI 3.1、Protobuf/gRPC、TypeScript/Python/Go/Java SDK |
| Worker/平台 | Python Application Adapter、Validation/GameDay/Agent/Reviewer/Vision Worker；Go Terraform Provider；Kubernetes Operator |
| 交付与验证 | Docker/Compose、Kubernetes/Kind、GitHub Actions、Cosign、SPDX/SBOM、N/N-1 Gate |

当前公开 OpenAPI 基线为 **213 Operations / 287 Schemas**；修改正式 API 后必须同步契约、生成 SDK、Manifest 与相关测试。

## 4. 整体架构与主要模块

```text
Web Console / Tauri Desktop / SDK
                │ HTTPS + OIDC/RBAC + SSE
                ▼
Java Control Plane
  API / Application / Domain / Coordinator / Persistence / Security
                │ PostgreSQL authority + Outbox/Inbox + mTLS gRPC
                ▼
Rust Browser Node
  Node Agent / Runtime & Browser Supervisor / Input Sandbox
  State Collector / Remote Desktop Gateway / Session Recorder
  Network Helper / Storage Helper / Node Journal
                │
                ├─ Chromium/CDP + Cgroup v2 + Xvfb/x11vnc
                └─ S3-compatible Profile/Checkpoint/Recording objects
```

关键边界：

- Control Plane 负责租户、策略、Operation、调度、审计和权威投影；前端不得直接操作 Node cgroup。
- Browser Node 只通过 mTLS 契约接收命令、产生 ACK/Event，并借助 Node Journal 保证重试和恢复。
- PostgreSQL 保存业务权威状态；对象存储保存大对象，数据库只暴露安全元数据，不向公共 API 泄露对象路径或 Secret。
- SSE 只发最小化变化提示，客户端收到后重取正式 API；断线必须明确显示数据可能过期。

## 5. 关键目录与文件

| 路径 | 作用 |
| --- | --- |
| `apps/control-plane/` | Java 控制面；`api`、`application`、`domain`、`coordinator`、`persistence`、`security` 分层 |
| `apps/browser-node/` | Rust Workspace；Runtime、Browser、Input、State、VNC、Recording、Network、Storage、Journal 等 Crate |
| `apps/web-console/` | Web/Tauri 共享 React UI、API Client、Query、权限和状态逻辑 |
| `apps/desktop/` | Tauri 2 原生容器与平台安全适配 |
| `apps/application-adapter/` | 最小权限 Provider/业务 Lease 适配运行时 |
| `apps/validation-worker/` | Runtime Validation 隔离执行队列与 Runner |
| `apps/gameday-worker/` | Recovery GameDay 隔离 Worker 与 Runner |
| `apps/agent-worker/` | Agent Executor 与 Reviewer Worker |
| `packages/contracts/openapi/session-api.yaml` | 外部正式 API 权威契约 |
| `packages/contracts/proto/` | Control Plane 与 Browser Node 的内部 Protobuf 契约 |
| `database/migrations/` | Expand-only Flyway 迁移；当前最新迁移至少包含 V105 |
| `sdks/` | 四语言生成 SDK 与生成 Manifest；禁止手工造成契约漂移 |
| `deploy/kubernetes/` | Kubernetes 部署、策略、监控和 BrowserSession 资源 |
| `deploy/terraform/` | Terraform Module 与 Go Provider |
| `tools/browser-session-operator/` | BrowserSession Operator |
| `tests/` | Integration、E2E、Failure Injection、Upgrade、Kubernetes、Supply Chain 等 Gate |
| `docs/outline/` | 原始代码大骨架、V16 架构与实施计划；是目标，不等于当前完成状态 |
| `docs/08-进度追踪.md` | 当前进度总览；更新重大阶段结果 |
| `docs/progress/33-当前未实现清单.md` | 剩余代码、目标环境和组织 Gate 的权威清单 |
| `docs/progress/` | 每个闭环的实现、验证和剩余边界证据 |
| `docs/prompt/` | Web 优先、跨平台复用及 Neo-Industrial Observatory UI 设计输入 |
| `Makefile` | 统一构建、测试、契约、SDK、集成和发布检查入口 |

注意：根 `README.md` 的目录树仍保留了早期 `apps/cli` 等描述，可能落后于真实目录；以当前文件系统和本文件为准，后续可单独清理 README。

## 6. 已完成功能与真实开发进度

以下是已由代码、测试或真实运行证据确认的能力摘要；完整证据见 `docs/08-进度追踪.md` 和对应 progress 文档。

### 核心平台

- [已确认] Chromium/CDP 生命周期、State/Input、Crash Recovery、Profile/Checkpoint、Proxy、真实 noVNC 主链已实现。
- [已确认] Session 幂等/CAS、Operation、Workflow、Outbox/Inbox、Node Journal、Lease、Term/Route Epoch Fencing 和 PostgreSQL 权威路由已实现。
- [已确认] OIDC/RBAC、mTLS、哈希审计、Break-glass、Secure Debug、签名审计导出和供应链签名核心链已实现。
- [已确认] 本机真实 Chrome 500 次顺序及并发 4 容量证书、Kind N/N-1、Operator List/Watch 与核心告警已实现。

### Web、Desktop 与企业运营

- [已确认] 环境管理、创建向导、Session Detail、Workspace Overview、Groups/Tags、批量生命周期/归属、Saved View、全局搜索、通知、主题、用户菜单、Settings 已接正式 API/PostgreSQL。
- [已确认] Profile 导入/用途绑定一次性导出、Proxy Provider/Binding/探测/自动路由、Safe Point Rebind 已实现。
- [已确认] Tauri 2 容器、OS 安全存储、系统浏览器 OIDC/Deep Link 和 Updater Gate 已实现；Web 与 Desktop 复用业务 UI。
- [已确认] Validation Matrix、Recovery GameDay、Cost/SLO/Retention/Compliance/Residency/DR Registry、Error Budget Freeze、Terraform、四语言 SDK 和统一发布包已实现。

### AUTO 资源治理

- [已确认] 普通用户创建时只提交 `resourcePolicy.mode=AUTO`；用户界面不再展示 L1-L5，`Native OS` 不再是资源等级。
- [已确认] Execution Environment 与 Resource Policy 独立建模；内部 Resource Template 仅用于后端调度。
- [已确认] 5 秒资源采集、窗口/EWMA/P95、持续时间、冷却、迟滞、快扩慢缩、成本趋势、Cgroup/媒体/桌面/扩展执行器与 ACK 状态机已实现。
- [已确认] 达到上限默认 `PAUSE_AGENT` 并保留 Browser/Login/HumanTakeover；迁移、休眠、严格终止均走真实 Operation。
- [已确认] Safe Point 汇总、跨 Node 迁移、State Resync、Business Recovery、周期 Readback/Drift Reconcile 和资源 SSE 已实现。
- 旧聊天或旧清单若仍称上述 AUTO P0/P1 未完成，视为过时；代码复核见 `docs/progress/141-AUTO资源清单复核与WebConsole可访问性技术质量收口.md`。

### Agent 与真人远程协作

- [已确认] VNC 连接不会断开、暂停或终止 Agent；默认只读观察可以持续查看 Agent 行为。
- [已确认] 开启协作控制后，只有 Gateway 实际收到真人键盘/鼠标/剪贴板输入时才触发 `HUMAN_INPUT_PRIORITY`；真人停止输入 2 秒后，同一持久 Operation 自动续行。
- [已确认] 新票据只签发 `COLLABORATIVE`；遗留 `EXCLUSIVE_TAKEOVER` 在 Gateway 中 fail-collaborative，不再踢出协作者。
- [已确认] 多参与者、单上游 RFB Fan-out、慢消费者隔离、每 Actor 带宽/FPS/成本、在线列表、精准撤销和历史治理已实现。
- [已确认] 低风险 `SINGLE_CLICK/IMAGE_SELECTION/PUZZLE/MULTI_ROUND` Challenge 支持脱敏截图 OCR/视觉定位，默认三次且可按 Session 调整，并可执行点击、连续点击和滑动；AUTONOMOUS 只有在自动路径耗尽后才写一次人工协助通知，原 Task 保持可续行。
- [已确认] Vision Worker 只有 Purpose-bound 一次性截图读取和结构化动作输出权限；Browser Node 在 State Hash/Version、Operation Epoch、八次动作预算及真人输入优先级下重新校验，不接受键盘、文本、Secret 或任意 CDP。
- [已确认] Session 默认 `SAFE`，操作员可一次切换 `AUTONOMOUS`；后者允许 Agent 通过租户/Session/用途绑定的一次性 AES-GCM API 输入账号、密码和 OTP，默认三次输入代理重试且可调 1—10 次，不逐动作索要人工确认。
- [已确认] 密文引用只能由一次 `TYPE_TEXT` Step 事务消费；Plan/API/审计/Agent Worker/Vision Worker 不含明文或低熵 OTP Hash。若已有密文计划则登录/OTP Challenge 直接续行；确需人工时只通知一次，操作员可发送 OTP 由 Agent 有界重试代填并恢复原 Task，也可自愿进入协作自行填写，系统不强迫接管。
- 支付、转账、购买、修改密码、删除账号等决策仍需独立高风险确认；自动登录不等于绕过安全门禁。人工 VNC 是随时可加入的协作能力，不是 Agent 的必经步骤。
- 证据见 `docs/progress/148-AUTONOMOUS按需人工协助与OTP续行闭环.md`、147、146、139 及 115、117、123—126、131—132。

### Agent Browser 结构化感知与低延迟执行

- [已确认] 新的粗粒度 `snapshot/inspect/find/execute-actions` 复用现有
  Browser State、Operation、Reviewer 和 Capability；普通页面以 DOM/A11y/Layout 为主，
  Screenshot/Vision 只作为 Challenge 或结构化感知失败的 fallback。
- [已确认] Target 已具备稳定 Element ID、iframe/open Shadow Root 上下文、
  Focus/Form State，以及隐藏、离屏、遮挡和不可交互判定；Action Executor 以一个持久 Batch
  顺序执行 CLICK/TYPE/FILL/AgentClipboard/SCROLL/WAIT，每步重读真实状态并支持 stop-on-error；
  Batch Primitive 已以 additive `element_id` 在每步后按最新 Target Revision 稳定重绑定，
  N−1/历史命令保持原围栏并 fail-closed，见 progress 150。
- [已确认] V106—V108 分别增加有界 Human-like Motion Policy、创建时锁定且
  每次 Runtime 启动重放的 Session Identity Spec，以及与 VNC UserClipboard 完全隔离、
  PostgreSQL/AES-GCM 权威的 AgentClipboard。详细边界见 progress 149。
- 自动模式普通操作和有界失败重试保持静默；只在 OTP/设备确认/高风险决定等真人信息缺失，
  或低风险 Challenge 自动预算确实耗尽时通知一次。操作员可发 OTP 由 Agent 代填或自愿进入
  VNC，系统不得把人工接管设为普通自动化的必经步骤。

### 事件流与录制

- [已确认] Session/Resource/State/Operation/Agent Task、Workspace Overview、通知和租户审计已使用 PostgreSQL 单调游标、`Last-Event-ID`、Reset/Replay 的可续传 SSE。
- [已确认] Browser Node 仅在 `FRESH/STALE` 状态转换时发布 payload-free 事件；Node 页面已删除 5 秒轮询，见 progress 144。
- [已确认] Enterprise Overview 已用 V102 专用 PostgreSQL 投影覆盖 Validation、Cost、Media、SLO/Freeze、SLA、Retention、License、Region、GameDay/Trend/Remediation、Compliance 及时间窗口到期变化；Web/Tauri 已删除 15 秒轮询并显示断线过期状态，见 progress 145。
- [已确认] Recording 的像素采集、语义遮罩、create-only Segment/Marker/Manifest、Node Journal 收尾和 PostgreSQL Retention/Legal Hold 投影已实现。

### 最近验证状态

- Agent Browser Batch 稳定重绑定切片实现提交 `54b28ea`；本地 Control Plane 456 项、Rust Workspace、Web
  115 项、Worker/Provider、完整 Test/Lint/Build、Desktop、OpenAPI/四 SDK、N/N−1 与
  PostgreSQL/mTLS/真实 Chromium Integration 已通过；GitHub `ci/desktop` 待执行，
  见 progress 150。

- Agent Browser 结构化感知/Batch/Identity/Clipboard 基础切片本地 Control Plane 456 项、
  Web 115 项、Rust Workspace、Python Worker、Go Provider、全量 Test/Lint/Build、Desktop、
  OpenAPI/四 SDK、供应链、Operator、50k Coordinator Capacity、N/N−1 与完整
  PostgreSQL/mTLS/Chromium Integration 已通过；Integration 显式覆盖 Identity 锁定/审批
  应用、AgentClipboard RBAC/清除及 Snapshot/Inspect/Find 一致性。提交 `a14e5f1` 的
  GitHub `ci`（run `32363001442`，含供应链、Integration、Object Storage/Recording
  GameDay 与 Kubernetes Operator E2E）和 `desktop`（run `32363001455`，Windows/macOS）
  均通过，见 progress 149。
- AUTONOMOUS 按需人工协助切片本地 Control Plane 446 项、Web 115 项、Rust Workspace、
  Python Worker、Go Provider、全量 Test/Lint/Build、Desktop、OpenAPI/四 SDK、N/N-1 与
  完整 PostgreSQL/mTLS/Chromium Integration 已通过；实现提交 `dde38da` 的 GitHub `ci`
  （run `32159504238`）和 `desktop`（run `32159504071`，Windows/macOS）均通过，见
  progress 148。

- Agent SAFE/AUTONOMOUS 切片本地 Control Plane 442 项、Web 114 项、Rust Workspace、
  Python Worker、Go Provider、全量 Test/Lint/Build、Desktop、OpenAPI/Protobuf、四 SDK、
  N/N-1 与完整 PostgreSQL/mTLS/Chromium Integration 已通过。
- 最终功能提交 `0ee151a` 的 GitHub `ci`（run `32149210380`，含 Verify、供应链、
  Integration、Object Storage/Recording GameDay 与 Kubernetes Operator E2E）和 `desktop`
  （run `32149210343`，Windows/macOS）均通过。
- 上一基准提交 `bcedd5d` 时工作区干净，`main == origin/main`。
- Challenge 视觉自动化切片本地 Java 439 项、Web 113 项、Rust Workspace、Python Worker、
  Go Provider、全量 Test/Lint/Build、Desktop、OpenAPI/Protobuf、四 SDK、N/N-1、Operator
  和完整 PostgreSQL/mTLS/Chromium Integration 已通过。
- 提交 `bcedd5d` 的 GitHub `ci`（run `32139754379`，含 Verify、供应链、Integration、
  Object Storage/Recording GameDay 与 Kubernetes Operator E2E）和 `desktop`
  （run `32139754412`，Windows/macOS）均通过。
- Enterprise Overview 切片本地 Java 437 项、Web 112 项、全量 Test/Lint/Build、Desktop、SDK、N/N-1 与完整 PostgreSQL/mTLS/Chromium Integration 已通过。
- 该提交的 GitHub `ci`（run `32126377468`，含 Verify、Integration 与 Kubernetes Operator E2E）和 `desktop`（run `32126377512`，Windows/macOS）均通过。
- `StopRuntime` + Recording 的幂等回归保持修复，主干绿色。

## 7. 当前正在处理的任务

最近切片与当前最高优先级开发任务：

### Agent Browser 结构化感知与低延迟执行（基础切片已闭环）

- Snapshot/Inspect/Find、精确 State Cursor、稳定 Element ID、可见/可操作性判定和同源
  iframe/open Shadow DOM 已完成开发；
- 统一 Batch/Fast Path 已支持 CLICK、TYPE、FILL、AgentClipboard Paste、SCROLL、WAIT，
  每步重验真实状态，VNC 真人输入优先后续行同一 Batch；
- Challenge Human-like 轨迹、Session Identity 创建时锁定/Change Request/Runtime 应用、
  独立 AgentClipboard 和 OpenAPI/四 SDK 已完成开发；
- Java 456 项、Rust/Web/Worker/Provider、Test/Lint/Build、四 SDK、Desktop、供应链、
  Operator、50k Coordinator Capacity、N−1 和完整 PostgreSQL/mTLS/Chromium Integration
  已通过；提交 `a14e5f1` 的 GitHub `ci` run `32363001442` 与 `desktop` run
  `32363001455` 也均通过；
- 当前继续收口 Dialog/Tab/File/局部 Screenshot/受治理 JS Evaluate 和其余高级 Action
  Primitive，见 progress 149；稳定 Element ID 的逐 Primitive 重绑定已完成定向验证，
  且完整本地 Gate 已通过；实现提交 `54b28ea`，远端 Workflow 待执行，见 progress 150。

### Agent SAFE/AUTONOMOUS 与敏感输入自动化（已闭环）

- V104 已增加默认 SAFE、可显式开启 AUTONOMOUS 和默认三次可调敏感输入预算；旧 Session/客户端行为保持 fail-closed；
- 新的一次性密文 API 支持 USERNAME/PASSWORD/OTP，要求幂等键，租户/Session/用途绑定、短 TTL、单次事务消费且不向 Worker/API 回显明文；
- Planner、Prompt Security、Action Tool 和 Browser Node 四层重新校验模式、用途、State/Target Revision、Capability 与域名；Node 使用覆盖式有界重试，N/N-1 新字段为 additive；
- 自动模式有已绑定密文时继续登录/OTP Step；无输入、自动禁用或预算耗尽时保留原 Task 并只通知一次，不强迫人工接管；
- V105 增加租户隔离、幂等、密文持久的 OTP 响应 Intent。操作员可发送 OTP 由 Agent 以默认
  三次可调预算代填并恢复原 Task，也可主动进入协作自行填写；本地输入失败保持 Challenge
  可重试且不重复通知；
- Web/Tauri 共用模式、重试与 OTP 响应 UI；OpenAPI/四 SDK 已同步为 213 Operations /
  287 Schemas；本地全量验证通过，见 progress 147、148；支付和破坏性账号决策仍独立确认。

### Enterprise Operations Overview 全量事件源与轮询移除（已闭环）

当前代码证据：

- V102 `enterprise_overview_events` 已以 16 个来源表 Trigger 覆盖全部 Overview 域；`browser_placements` 媒体用量和 Error Budget/GameDay 时间窗口也已纳入；
- `GET /api/v1/enterprise/overview/event-stream` 已沿用 ADMIN RBAC，支持租户/平台全局隔离、Last-Event-ID、Replay/Reset、Keepalive 和双层连接上限；
- Web/Tauri 共用 `useEnterpriseOverviewStream()`，15 秒固定轮询已删除，离线/重连明确提示数据可能过期；
- Notification/Audit 流未被复用；GameDay timeline 的 5 秒轮询因读取独立 `recovery_gameday_job_events` 而保留；
- OpenAPI/四 SDK 已同步为 203 Operations / 273 Schemas，N/N-1 和完整 Integration 已通过。

必须继续遵守的结论：

- [已确认] 不允许用部分 Notification 或 Security-only Audit 流冒充 Enterprise Overview 的全量变化源。
- [已确认] 新事件源必须是租户隔离、持久、单调、payload-free、支持 `Last-Event-ID`、Reset/Replay、连接上限和断线过期提示的正式 API。
- [已确认] 采用独立 `enterprise_overview_events`，来源/写路径矩阵见 progress 145；不要退回 Notification/Audit 子集。
- [已确认] `useRecoveryGameDayEvents()` 的 5 秒轮询不能由 Overview 流替换；后续只有为 timeline 建立完整单调源后才可删除。

Enterprise Overview、Challenge 视觉自动化与 Agent SAFE/AUTONOMOUS 基线均已推送 `main`
且对应 GitHub `ci`/`desktop` 通过；V105 按需人工协助续行切片的本地 Gate 与 GitHub
`ci`/`desktop` 也已通过。

### Recording 播放授权与对象治理（后续开发切片）

Agent Browser 当前切片全量验证和剩余高级 Tool 收口后，下一仓库级任务为 Recording
purpose-bound 一次性播放 Grant、目标 Bucket
Object Lock/WORM 与到期删除 Worker；实施前必须先复核现有 Manifest、Retention、Legal
Hold、对象存储 Helper 和 Evidence Grant 边界，不得把 PostgreSQL 删除投影冒充对象已经
物理删除。

## 8. 尚未完成的功能

### P0/P1：仓库内代码产品化

1. Warm Tier SQLite/LevelDB 应用感知 Adapter、Multipart Resume、跨 Region Restore、Profile 对象保留/Legal Hold 深度联动。
2. 目标 CRM/支付/IAM Provider 的真实凭据、字段/事务映射和 Provider 特有认证接入。
3. 目标云 Secret 解引用/轮换/撤销、商业 Proxy Provider Adapter、高级 SLA/业务成功率路由、Challenge/黑名单与受约束探索。
4. 无语义像素/OCR Validator、客户站点高级组合规则、大规模 Replay/Canary/回滚阈值。
5. Recording purpose-bound 一次性播放 Grant、目标 Bucket Object Lock/WORM、到期对象删除 Worker；OCR 级敏感信息分类。
6. Agent Browser Dialog/Tab/File、局部 Screenshot、受治理 JS Evaluate、完整高级键鼠
   Action Primitive 和 AgentClipboard/UserClipboard 显式受控 Bridge；现有底层能力不等于
   已完成粗粒度 Agent Gateway 契约。

### P1/P2：目标环境与外部集成 Gate

1. 目标 Linux Cgroup v2/OOM/PSI/Xvfb/x11vnc、多 Node、8 Client 弱网和正式 Chromium 长稳；GPU Helper/硬件 Codec。
2. 真实企业 IdP Metadata/Claim/MFA/ACR/Logout/租户映射；Apple/Microsoft 签名、Notarization、真实更新源与 Windows 验收。
3. 目标云 CNI/CSI/KMS/IAM/LSM、Object Storage、Ingress、Alertmanager/Pager 到达与故障演练。
4. 真实多 Region 数据复制/切换、跨 Region Event Bus、全局预算一致性和 Workflow。
5. Registry Trusted Publishing、命名空间、撤销和客户 SDK 升级策略。

### P2：组织 Gate

- Primary/Secondary Owner、RACI、Threat Review、Residual Risk、Staging 证据、发布审批、值班与生产签字。

完整而细粒度的清单仍以 `docs/progress/33-当前未实现清单.md` 为准。

## 9. 已确认的重要设计决策与技术方案

1. [已确认] **真实数据原则**：生产功能不得使用 Mock Data、`localStorage`、JSON 文件或进程内数据伪造状态；演示/测试 Fixture 必须隔离并显式标注。
2. [已确认] **写操作原则**：前端写操作等待真实 Operation；失败显示 Request ID；相同写操作防重复提交；Node 调整只通过受控命令/ACK。
3. [已确认] **AUTO 资源策略**：5 秒采集、30 秒决策、5 分钟趋势；危险事件立即保护；滑动窗口/EWMA/P95/持续时间/冷却/Hysteresis；扩容快、缩容慢。
4. [已确认] **资源与运行环境分离**：Native OS 是 Execution Environment，不是资源等级；内部 Template 不直接暴露成用户等级。
5. [已确认] **迁移安全**：真人连续输入、拖拽、上传/下载、表单/支付/账号安全、Snapshot、Profile Flush、关键事务或 Business Recovery Unknown 时不得自动迁移。
6. [已确认] **VNC/Agent 协作**：连接不触发 Agent 断开；真人实际输入优先 2 秒；Agent 保持同一 Operation 并自动恢复。
7. [已确认] **Challenge 与敏感输入自动化**：低风险视觉挑战默认三次自动尝试，截图先脱敏、Worker 最小权限、Node 状态绑定；Session 默认 SAFE，AUTONOMOUS 只通过一次性用途绑定密文输入账号/密码/OTP，只有自动路径耗尽后才通知一次；原 Task 保持可续行，人工可发送 OTP 由 Agent 代填或自愿协作填写，真人输入始终优先；支付和破坏性账号决策仍独立确认。
8. [已确认] **事件流**：只在有完整、持久、单调变化源时删除轮询；SSE payload 最小化，租户隔离，支持 Resume/Reset，不在前端伪造曲线或状态。
9. [已确认] **安全默认值**：OIDC/RBAC、mTLS、最小权限、fail-closed、用途绑定短期授权、签名与重放防护；公共 API 不返回 Secret URL、对象路径或敏感快照内容。
10. [已确认] **迁移策略**：数据库迁移 expand-only；必须保持 N/N-1 滚动兼容，旧枚举/字段在兼容窗口结束前不物理删除。
11. [已确认] **UI 方向**：Neo-Industrial Observatory，高信息密度、企业级、深浅主题、状态不只依赖颜色；Web 优先并与 Tauri 共用组件/API/权限逻辑。

## 10. 重要约束和开发原则

- 修改前先用 `rg`/`rg --files` 定位，保留用户已有改动，不执行破坏性 Git/文件命令。
- 文件修改优先使用 `apply_patch`；不要用脚本绕过可审阅的补丁来写少量文件。
- 任何新增正式状态都必须有 PostgreSQL 权威模型、租户边界、幂等/并发语义、审计和失败恢复。
- API 变更同步 OpenAPI、四 SDK、Manifest、契约测试与集成测试；内部 RPC 变更同步 Protobuf 与 N/N-1 Gate。
- Web 不得因一次指标变化自行判断扩缩容，不得用定时器伪造资源变化。
- 高风险策略（严格预算终止、显式接管、Secure Debug 等）必须保留权限检查、风险提示和审计。
- 重大功能完成后同步更新：`docs/08-进度追踪.md`、`docs/progress/33-当前未实现清单.md`、一个独立 `docs/progress/<编号>-<主题>.md`，以及本文件的阶段/任务/剩余项。
- 先跑与改动匹配的定向测试，再按风险扩大到 `make test`、`make lint`、`make build`、`make contracts-check`、`make test-upgrade-compatibility`、`make test-integration` 等。
- 提交应聚焦、可回滚，推送后检查 GitHub `ci` 和 `desktop` Workflow；没有通过验证的能力不得写成“完成”。

常用命令：

```bash
make test
make lint
make build
make contracts-check
make sdk-typescript-check
make sdk-multilang-check
make test-upgrade-compatibility
make test-integration
make test-kubernetes-e2e
make test-desktop
```

## 11. 已知问题、Bug 和技术债

1. `useRecoveryGameDayEvents()` 仍以 5 秒轮询读取；替换前需先证明 timeline 事件分页的完整顺序和权限边界。
2. 遗留 `EXCLUSIVE_TAKEOVER` 枚举/协议字段尚在 N/N-1 兼容窗口内；行为已失效，但暂不能物理删除。
3. VNC/Agent 综合 E2E 历史上出现与 VNC 无关的 Agent 表单响应 30 秒偶发超时；并发关键段已有真实证据，完整长稳仍需单独稳定。
4. README 目录树和部分早期进度快照可能落后；不要据此恢复已经删除或重构的模块。
5. 目标环境、真实外部凭据和组织审批缺失是发布阻断项，不是本地单测通过即可关闭的代码任务。

## 12. 当前重点与推荐优先级

| 优先级 | 任务 | 原因 |
| --- | --- | --- |
| P0 | Agent Browser 当前切片全量 Gate 与剩余粗粒度 Tool | 决定自主 Agent 是否能低延迟、可靠地长期操作真实页面 |
| P1 | Recording 播放授权、WORM/删除 Worker 和对象治理 | 涉及敏感浏览器证据、Retention/Legal Hold 的生产闭环 |
| P1 | Warm Tier 数据库感知 Adapter/Resume/跨 Region Restore | Profile 一致性和迁移恢复的主要剩余代码缺口 |
| P1 | 目标 Provider/Secret/Proxy Adapter | 真实客户业务接入的前提 |
| P1 | OCR/高级 Validator/Replay | 视觉安全和生产 Agent 质量 Gate |
| P2 | 目标 Linux/云/多 Region/桌面签名长稳矩阵 | 发布 Gate，需要真实环境和外部凭据 |
| P2 | 组织安全与发布签字 | 正式上线治理 Gate |

## 13. 下一步开发计划

1. 按 progress 149 的保留边界继续收口 Dialog/Tab/File/Screenshot/Evaluate 与高级
   Action Primitive；基础结构化感知/Batch/Identity/Clipboard 切片不得重做。
2. 随后开始 Recording purpose-bound 一次性播放 Grant、目标 Bucket Object Lock/WORM 与
   到期删除 Worker；实施前复核对象存储和 Retention/Legal Hold 当前边界。
3. Warm Tier 数据库感知 Adapter/Resume/跨 Region Restore、目标 Provider/Secret/Proxy 和 OCR/Replay 按第 12 节顺序推进。
4. 持续补齐目标 Linux/云/多 Region/桌面签名长稳和组织安全发布 Gate；仓库测试通过不等同于允许处理真实客户数据。

## 14. 何时必须更新本文件

发生下列任一变化时，在同一提交中更新 `AGENTS.md`：项目目标变化、架构变化、核心模块增删、重要方案确认或推翻、重要阶段完成、当前开发重点切换、出现影响后续开发的 Bug/限制，或本文与代码真实状态不一致。

本文件只保存跨会话接手所需的稳定信息；不要粘贴大量调试日志、失败命令输出、重复讨论或尚未验证的推测。未确定事项必须标为“待确认”或“待评估”。
