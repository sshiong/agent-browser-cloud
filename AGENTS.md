# Agent Browser Cloud 项目交接与开发约定

> 更新日期：2026-09-21
> 基准分支：`main`
> 编写时基准提交：`4b8be14 fix: close real OTP challenge flow`
> 适用范围：本仓库全部目录。子目录若以后出现更具体的 `AGENTS.md`，以更深层文件为准。

## 1. 接手时必须先做

1. 阅读本文件，然后执行 `git status --short --branch` 和 `git log -12 --oneline --decorate`。
2. 阅读与当前任务直接相关的代码、测试、数据库迁移、OpenAPI/Protobuf 契约和最新进度文档。
3. 判断状态时采用以下优先级：**当前代码与可重复测试证据 > 最新进度文档 > 旧进度文档 > 聊天或历史计划**。
4. 若本文件、进度文档与代码不一致，以代码为准，并在同一改动中更新本文件和对应进度文档。
5. 不重新讨论或重做本文中标记为“已确认”且已有代码证据的架构和能力。

### 1.1 macOS 本地 Docker 运行时（所有会话强制）

1. macOS 本地开发、Compose、Integration、Kind 和镜像构建统一使用 **OrbStack**；本仓库所有
   Codex 会话、子代理和人工终端默认 Docker context 必须是 `orbstack`。不得启动或使用
   Docker Desktop，也不得使用 `desktop-linux` context。Linux CI、目标 Kubernetes/云环境不受
   此本机约束影响。
2. 每次会话首次执行 Docker 命令前，以及运行任何 Docker Gate 前，必须先执行并核对：

   ```bash
   orbctl status
   docker context show
   docker info --format 'Name={{.Name}} OS={{.OperatingSystem}} Server={{.ServerVersion}}'
   ```

   期望结果分别包含 `Running`、`orbstack` 和 `OS=OrbStack`。OrbStack 未运行时执行
   `orbctl start`；context 不正确时执行 `docker context use orbstack`，然后重新核对，禁止在错误
   daemon 上继续测试。
3. `docker context ls` 中存在 `desktop-linux` 仅表示保留了 context 配置，不代表 Docker Desktop
   正在使用；判断必须以 `docker context show`、endpoint 和 `docker info` 为准。
4. 若未来发现 Docker Desktop 中确有必须保留的容器、镜像或卷，不得把切换 context 冒充迁移。
   必须先只读盘点 Desktop 与 OrbStack 两侧对象，停止在 Desktop 创建新状态，对镜像和命名卷数据
   做显式导出/导入，在 OrbStack 中重建并验证服务和数据完整性；只有验证成功后才能经用户授权清理
   Desktop 旧对象。不得为盘点而擅自启动 Docker Desktop，也不得删除来源数据。

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

当前公开 OpenAPI 基线为 **253 Operations / 350 Schemas**；修改正式 API 后必须同步契约、生成 SDK、Manifest 与相关测试。

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
| `database/migrations/` | Expand-only Flyway 迁移；当前最新迁移至少包含 V128 |
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

中英文 README 模块表均从 Git 跟踪文件生成；模块变更先暂存，再执行 `make docs-generate`。
`make docs-check` 在 CI 同时拒绝 `README.md`、`README.en.md` 的目录表漂移和本地链接失效，见
progress 166。

## 6. 已完成功能与真实开发进度

以下是已由代码、测试或真实运行证据确认的能力摘要；完整证据见 `docs/08-进度追踪.md` 和对应 progress 文档。

### 核心平台

- [已确认] Chromium/CDP 生命周期、State/Input、Crash Recovery、Profile/Checkpoint、Proxy、真实 noVNC 主链已实现。
- [已确认] Session 幂等/CAS、Operation、Workflow、Outbox/Inbox、Node Journal、Lease、Term/Route Epoch Fencing 和 PostgreSQL 权威路由已实现。
- [已确认] OIDC/RBAC、mTLS、哈希审计、Break-glass、Secure Debug、签名审计导出和供应链签名核心链已实现。
- [已确认] 本机真实 Chrome 500 次顺序及并发 4 容量证书、Kind N/N-1、Operator List/Watch 与核心告警已实现。

### Web、Desktop 与企业运营

- [已确认] 环境为长期可复用对象：`:start` 打开浏览器，`:stop` 正常关闭后保存 Profile 并进入
  HIBERNATED，随后可启动同一 Session；未删除的历史 TERMINATED 也允许显式再启动。
  Web/Tauri 显示“已停止”，与删除独立；批量删除兼容 HIBERNATED。Chromium 先 Browser.close
  再 Checkpoint，重启恢复 Session Cookie；真实 Chrome Checkpoint 恢复与 OrbStack 两次启停已验证，
  见 progress 162。网站主动撤销/过期登录、强制清理时未落盘数据不作保证。

- [已确认] 环境管理、创建向导、Session Detail、Workspace Overview、Groups/Tags、批量生命周期/归属、Saved View、全局搜索、通知、主题、用户菜单、Settings 已接正式 API/PostgreSQL。
- [已确认] 环境列表三点菜单已接详情与 Tenant/RBAC 隔离的 PostgreSQL 重命名；无 Workflow 的超期 START/TERMINATE Operation 会由 deadline scanner 收敛，不再长期显示“启动中”，见 progress 159。
- [已确认] 环境列表已增加左侧复选框、当前页全选和批量删除；V113 软删除仅允许 CREATED/TERMINATED 且无 ACTIVE Operation 的 Session，按 Tenant/RBAC 原子、幂等处理，保留 Audit/Recording/Recovery 证据并释放实时 Coordinator Route/Ownership，见 progress 160。
- [已确认] Profile 导入/用途绑定一次性导出、Proxy Provider/Binding/探测/自动路由、Safe Point Rebind 已实现。
- [已确认] Tauri 2 容器、OS 安全存储、系统浏览器 OIDC/Deep Link 和 Updater Gate 已实现；Web 与 Desktop 复用业务 UI。
- [已确认] Validation Matrix、Recovery GameDay、Cost/SLO/Retention/Compliance/Residency/DR Registry、Error Budget Freeze、Terraform、四语言 SDK 和统一发布包已实现。
- [已确认] 独立 Personal Secure 单机部署层只绑定 loopback，强制非 Local OIDC/API Audience、
  随机文件 Secret、数据库/Redis 认证、内部 mTLS、完整四 Worker 独立身份/进程、受控 Browser
  出口、HTTPS 对象/模型端点和加密 Profile；它不是公网反代模板或 V16 生产认证，见 progress 188。
- [已确认] 默认 localhost Compose 已启动 Agent Executor、Reviewer、Outcome Verifier、Vision 四个
  独立真实进程并开启对应外部队列；Local Compose 的模型入口支持任意域名/IP 的 HTTP(S)
  `/v1/responses`，Key 作为供应商无关的不透明凭据处理，而非 local/test Worker 仍强制 HTTPS 与
  Host Allowlist。私有文件 Key 缺失时 fail-closed，首次成功长轮询后才 healthy，见 progress 194。

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
- [已确认] Challenge Vision 只接受精确 State/Target/Active Tab 围栏的有界 Region；隔离 Worker
  在外部模型调用前执行本地 Tesseract OCR/PII 检测、ImageMagick 像素遮罩和二次 OCR 零残留
  复核。无安全 Target、能力或证明时 fail-closed/Human Handoff，见 progress 176。
- [已确认] Session 默认 `SAFE`，操作员可一次切换 `AUTONOMOUS`；后者允许 Agent 通过租户/Session/用途绑定的一次性 AES-GCM API 输入账号、密码和 OTP，默认三次输入代理重试且可调 1—10 次，不逐动作索要人工确认。
- [已确认] 密文引用只能由一次 `TYPE_TEXT` Step 事务消费；Plan/API/审计/Agent Worker/Vision Worker 不含明文或低熵 OTP Hash。若已有密文计划则登录/OTP Challenge 直接续行；确需人工时只通知一次，操作员可发送 OTP 由 Agent 有界重试代填并恢复原 Task，也可自愿进入协作自行填写，系统不强迫接管。
- 支付、转账、购买、修改密码、删除账号等决策仍需独立高风险确认；自动登录不等于绕过安全门禁。人工 VNC 是随时可加入的协作能力，不是 Agent 的必经步骤。
- 证据见 `docs/progress/148-AUTONOMOUS按需人工协助与OTP续行闭环.md`、147、146、139 及 115、117、123—126、131—132。

### Agent Browser 结构化感知与低延迟执行

- [已确认] 正式粗粒度操作面为 `snapshot/find/inspect/act/wait/handoff`，复用现有 Browser
  State、Operation、Reviewer、Capability 与 Outcome Verification；旧 `execute-actions` 保留为
  deprecated 兼容别名。普通页面以 DOM/A11y/Layout 为主，Screenshot/Vision 只作为 Challenge
  或结构化感知失败的 fallback，见 progress 187。
- [已确认] Browser Node 以连续权威采样形成 DOM/Layout/Focus/Route 四类 quiet window，并与
  Network 证据共同约束普通动作、动态微批和 Outcome 稳定状态；任一证据缺失或变化均
  fail-closed，真实 Chrome 动态页面验证通过，见 progress 186。
- [已确认] Target 已具备稳定 Element ID、iframe/open Shadow Root 上下文、
  Focus/Form State，以及隐藏、离屏、遮挡和不可交互判定；Action Executor 以一个持久 Batch
  顺序执行 CLICK/TYPE/FILL/AgentClipboard/SCROLL/WAIT，每步重读真实状态并支持 stop-on-error；
  Batch Primitive 已以 additive `element_id` 在每步后按最新 Target Revision 稳定重绑定，
  N−1/历史命令保持原围栏并 fail-closed，见 progress 150。
- [已确认] 跨域、Sandbox 或不可读取 iframe 以 Origin-only Opaque Frame 显式投影，不暴露
  内部 DOM 或 URL Path/Query；Frame 永不成为可执行 Target。受治理截图按精确 State/Tab/
  frameRef/Bounds 双重围栏并复用脱敏 Evidence。progress 198 仅为 Session 精确 Origin 与当前
  Task Domain 双重授权的低风险托管 Challenge 开放一次左键；文本、Secret、键盘、滑动、连续点击
  与支付/账号决策仍固定 Human Handoff。
- [已确认] 同一 Batch 已增加真实 CDP 双击、右键、悬停、清空、勾选和取消勾选；
  Check/Uncheck 会在动作后重采并验证结构化 checked 状态，非文本动作不得夹带 Secret/Value，
  见 progress 151。
- [已确认] Browser Node 已从真实 Chromium Page Target 投影完整 `tabs/activeTabId`，活动页
  证据歧义时 fail-closed；Input Broker 随活动 Page 安全重绑定。统一 Batch 已支持
  OPEN/SWITCH/CLOSE Tab，并重验允许域、Capability、State/Target Revision、Tab Resource
  Policy 和最后 Tab 保护，见 progress 152。
- [已确认] Browser Node 已通过持续 CDP 事件和安全 Runtime Probe 投影原生
  alert/confirm/prompt/beforeunload；PostgreSQL 保存最后 Dialog 与 freshness，断线保留但
  降级并拒绝旧动作。统一 Batch 已支持 ACCEPT/DISMISS/Prompt，且与 DOM Dialog、权限弹窗
  分离，见 progress 153。
- [已确认] V106—V108 分别增加有界 Human-like Motion Policy、创建时锁定且
  每次 Runtime 启动重放的 Session Identity Spec，以及与 VNC UserClipboard 完全隔离、
  PostgreSQL/AES-GCM 权威的 AgentClipboard。详细边界见 progress 149。
- [已确认] V112 已增加显式、用途/租户/Session/Actor/当前 noVNC Connection/Context Epoch
  绑定的 UserClipboard ↔ AgentClipboard Bridge。USER_TO_AGENT 只接受两分钟内真实 noVNC
  observation；AGENT_TO_USER 只允许当前非只读连接。账本不保存正文或第二份密文，常规 UI
  只读 AgentClipboard 元数据；密码和 OTP 仍必须走一次性敏感输入 API，见 progress 158。
- [已确认] Agent Browser 文件上传通过精确 Placement mTLS stream、V109 PostgreSQL 元数据
  账本、持久 Operation/Outbox/Node Journal 与 CDP `DOM.setFileInputFiles` 完成，不打开 OS
  chooser；文件字节和 Node 路径不进入公共 API、数据库或审计。下载从真实 Browser/Network
  CDP 事件投影有界安全元数据、进度和 freshness，观察中断明确 `INTERRUPTED`，见 progress 154。
- [已确认] Agent Browser 截图已通过 V110 PostgreSQL 元数据账本、精确 State/Target/Active
  Tab 围栏、活动 Page CDP 捕获、整页敏感遮罩和 create-only 对象提交覆盖 Viewport、Full Page、
  Element、Region 与 Challenge Region；只有原 Actor 可用五分钟 `AGENT_PERCEPTION` 一次性 Grant
  兑换，API/数据库/审计不含像素、对象路径或 URL，见 progress 155。
- [已确认] Agent Browser 受治理 JavaScript Evaluate 已通过 V111 PostgreSQL 权威账本、
  AES-GCM 密封源码派发、精确 State/Target/Active Tab 围栏和持久 Operation/Outbox/Node
  Journal 完成；READ_ONLY 使用 Chromium `throwOnSideEffect`，PAGE_ACTION 复用 Intent 风险
  策略，Cookie/Storage/Credential/Clipboard/Network/Navigation/Tab/DevTools 逃逸在 Control
  Plane 与 Node 双重拒绝。状态过期时不执行脚本，先投影真实新 State 再允许自动重试；源码
  不进入 API、普通 Audit、Worker 或 PostgreSQL Evaluation 结果，见 progress 156。
- [已确认] 统一 Batch 已补齐 SELECT_OPTION、PRESS_KEY、DRAG/DROP/SWIPE，以及目标约束的
  通用 Mouse/Keyboard/Touch Primitive；源/目标稳定 Element ID 会按最新 Target Revision
  重绑定，键值白名单、按键/触控 ledger 和失败 release-all 在 Node 双重执行。完整 20 动作
  Batch 保持上限 20，Agent Plan 为固定校验步骤兼容扩为 23；真实 Chromium Integration 已
  验证 19 个混合 Primitive，见 progress 157。
- 自动模式普通操作和有界失败重试保持静默；只在 OTP/设备确认/高风险决定等真人信息缺失，
  或低风险 Challenge 自动预算确实耗尽时通知一次。操作员可发 OTP 由 Agent 代填或自愿进入
  VNC，系统不得把人工接管设为普通自动化的必经步骤。

### 事件流与录制

- [已确认] Session/Resource/State/Operation/Agent Task、Workspace Overview、通知和租户审计已使用 PostgreSQL 单调游标、`Last-Event-ID`、Reset/Replay 的可续传 SSE。
- [已确认] Browser Node 仅在 `FRESH/STALE` 状态转换时发布 payload-free 事件；Node 页面已删除 5 秒轮询，见 progress 144。
- [已确认] Enterprise Overview 已用 V102 专用 PostgreSQL 投影覆盖 Validation、Cost、Media、SLO/Freeze、SLA、Retention、License、Region、GameDay/Trend/Remediation、Compliance 及时间窗口到期变化；Web/Tauri 已删除 15 秒轮询并显示断线过期状态，见 progress 145。
- [已确认] Recording 的像素采集、语义遮罩、create-only Segment/Marker/Manifest、Node Journal 收尾和 PostgreSQL Retention/Legal Hold 投影已实现。
- [已确认] Recording purpose-bound 播放授权通过 V127 一次性 Grant、五分钟 Actor-bound
  访问窗口及 60 秒分段 URL 闭环；Node/Storage Helper 在签名前重验 aggregate Manifest、逐段
  COMMITTED Marker 与对象大小，URL/对象 Key 不进入账本或 Audit，见 progress 200。
- [已确认] Recording 到期物理删除通过 V128 权威队列、Recording 行锁、mTLS Node 能力和
  Storage Helper PREPARED/COMMITTED tombstone 闭环；Legal Hold 与删除严格排序，只有真实对象
  前缀清空并返回 proof 后才写 Receipt。真实 MinIO 已验证 25 段对应 51 个对象删除，见 progress 201。

### 最近验证状态

- Recording purpose-bound 播放授权已通过 OrbStack 真实 MinIO 与完整 Integration：25 个不可变
  脱敏 Segment 经 Control Plane → mTLS Node → Storage Helper 分成 24+1 两页签发 60 秒 URL；
  跨 Actor、重复兑换、Retention 到期均 fail-closed，Legal Hold 可在同一五分钟访问窗口恢复读取，
  PostgreSQL 与 Audit 不含 URL/Signature。公开基线更新为 253 Operations / 350 Schemas，四语言
  SDK 已同步，见 progress 200。到期物理删除 Worker 后由 progress 201 以真实 MinIO/完整
  Integration 闭环；全帧 OCR/非文本视觉分类、目标 Bucket Object Lock/WORM 和目标云原生 Legal
  Hold 仍未完成。

- 真实 OTP 续行已加入 OrbStack/Chrome 153 登录矩阵：Browser Node 只从标准
  `autocomplete=one-time-code` 投影隐私安全控件类别，敏感 name/value 继续为空；Control Plane
  绑定精确 Target/Anchor，操作员以用途绑定一次性 Secret 响应后，同一 Task 恢复并通过 Outcome
  Verification。V126 兼容历史无 Target OTP 事件，资源 Sample 重投也在 Placement 行锁内幂等，
  不再重复写入或重放危险保护。四个独立 Profile 的登录成功、错误密码、假成功拒绝和 OTP 均通过，
  完整 Integration 与 `make ci` 通过，见 progress 199。真实短信/邮箱/TOTP、企业 IdP 和客户站点
  仍是目标环境 Gate。

- Opaque Frame 低风险单击自动化已以 OrbStack 和 Chrome 153 真实异步跨域 iframe 闭环：Session
  精确 Origin 策略与当前 Task Domain 双重授权后，任务在 `OPAQUE_FRAME_SINGLE_CLICK` 暂停，
  受治理截图进入 Vision Job，Node 按 State/Tab/Hash/frameRef/Bounds 即时重验一次左键，父页
  Outcome、Run 与原 Task 均完成。跨域输入、Secret、滑动、连续点击和支付/账号决策继续 Human
  Handoff；完整 Integration 与 `make ci` 均通过，见 progress 198。

- 极端完全同名目标现有明确 Adapter 身份契约：Application Adapter 以租户/应用隔离 Key 将原始
  实体值 HMAC 为 `data-agent-entity-hash/type/scope`，Node 从目标自身、祖先与 open Shadow Host
  读取并在公开 State 前再次哈希。无任何业务上下文的完全同名目标 fail-closed；Chrome 153 真实
  页面验证不同 Adapter 身份可恢复交互且 Element ID 不同，见 progress 197。

- 外部模型客户端传输取消已补齐：Reviewer、Outcome Verifier 与 Vision 的 HTTP(S) 请求绑定
  权威 Job Lease；Task 或 Owner 状态前进使 Heartbeat fail-closed 后，Worker shutdown 实际 socket，
  停止本地等待、响应下载和迟到回写。真实阻塞 HTTP Provider 与 41 项 Worker 测试通过。第三方
  是否停止服务端推理/计费仍依赖其显式 Cancel API，见 progress 196。

- 真实 Agent 综合验收以 OrbStack、Chrome 153 和运营方 `code` HTTP 聚合 Provider 完成：真实网页
  导航/读取/输入/滚动、三组独立 Profile 登录 Outcome、官方 Turnstile、Cookie 注入后 Checkpoint
  恢复、Profile 加密导入导出、截图/Vision 和完整 Integration 均通过。真实 Gate 首次发现跨导航
  遗留 `Image:parser` 使稳定页误报 `PAGE_UNSTABLE`，现按 Page load 只收敛 Document/parser-bound
  请求，Fetch/XHR/上传/下载/交易仍 fail-closed；Vision 同步支持 Responses JSON `id` 作为请求追踪
  回退。完整验证与真实 OTP 续行见 progress 195、199；客户站点边界仍是目标环境 Gate。

- Local Compose 模型入口已支持任意域名/IP 的 HTTP(S) OpenAI Responses 兼容 Provider，可直接连接
  OpenAI 官方、第三方 HTTPS 或可信本机/LAN HTTP 聚合接口；API Key 不校验供应商前缀，聚合路由允许
  动态响应模型。必填配置收敛为 `/v1` Base URL、Key、Model，Worker 自动补 `/responses`，Revision
  默认 `local-v1`；非 local/test Worker 继续强制 HTTPS 与显式 Host Allowlist。Worker 37 项、
  Compose 5 项与完整 `make ci` 通过，见 progress 194。

- 仓库已提供简体中文 `README.md` 与英文 `README.en.md` 双入口；两份文件包含语言切换并保持相同的
  安全边界、启动方式、Worker 链、模型 Provider、真实浏览器 Gate、项目结构和开发入口。
  `make docs-generate`/`make docs-check` 会同时生成并校验双语模块表和本地链接，见 progress 193。

- `code` 聚合模型 Provider 已以真实 Chrome 登录矩阵验证：请求模型保持聚合路由别名，响应允许合法的
  动态后端模型身份；Reviewer、Outcome 与 Vision 不再依赖所有下游都实现原生 JSON Schema，而以最小
  Responses 请求、明确 JSON Prompt 和 Worker 本地严格校验 fail-closed。V124 以最小化语义证据哈希
  容忍真实模型延迟期间非语义 State 游标前进，同时仍要求当前页面新鲜、稳定且 URL/Target/Expected
  Outcome/执行证据不变。成功登录、错误密码预期失败和假成功拒绝均经真实外部模型通过，三次最终调用
  分别约 4.9s、4.3s、6.8s，Secret 未输出。当时用户入口仅为 LAN HTTP并通过临时 TLS Relay 验证；
  progress 194 已允许 Local Compose 直接填写 HTTP(S) `/v1` Base URL、Key、Model，但仍不能据此
  宣称生产 Provider Gate 完成，见 progress 192。

- 真实登录 Outcome 与交互 Challenge Gate 已加入：三个真实 Chrome 登录 case 各自使用独立 Profile，
  正确密码、错误密码语义和假成功拒绝均通过，Secret 只经一次性引用使用；Cloudflare 官方
  forced-interactive dummy sitekey 已在 headed Chrome 中发生真实 checkbox 点击并返回测试 token。
  实现提交 `594bf54`，本地 `make ci`、Worker/Compose 定向测试和两个真实浏览器 Gate 均通过。
  文档提交 `1997bf2` 的 GitHub `ci` run `35493322329`（含 Verify、完整 Integration、Object
  Storage/Recording GameDay 与 Kubernetes Operator E2E）和 `desktop` run `35493322318`
  （Windows/macOS）均成功。
  `make test-real-login-agent-provider` 已在 progress 194 支持直接 Key 或显式 0600/0400 Key 文件，
  并接受 HTTP(S) `/v1` Base URL；真实聚合 Provider 调用、动态后端模型兼容和 LAN HTTP 边界见
  progress 192，不能据此冒充生产 HTTPS Provider 已验收。

- 真实 Chrome 初始状态与契约告警切片已修复：Chromium 内部 scheme 以 originless Opaque
  Frame 投影，Control Plane 保持非 Web origin 拒绝；活动 Page 网络 quiet、Document load
  收敛和结构化 Challenge 单调 State 复验已通过精确真实 Chrome Matrix。实现提交 `f799e51`，
  Fake Chromium 动态 Target 事件提交 `ccec50c`；本地 `make ci`、完整 PostgreSQL/Redis/
  MinIO/mTLS/Chromium Integration、Rust Workspace/Clippy、Control Plane Mapper、OpenAPI 与
  四语言 SDK 均通过。Redocly 无活动 warning，三个显式 ignore 分别对应始终 409 的锁定接口和
  两个 extension-only SSE schema；公开基线保持 250 Operations / 345 Schemas，见 progress 190。

- 默认 Compose 完整 Worker 链本地 Agent Worker 32 项、Compose 契约/预检 3 项通过；真实
  `make compose-up` 构建并启动完整栈，Control Plane 与 Agent/Reviewer/Outcome/Vision 四 Worker
  均在成功访问权威队列后 healthy，`compose-verify` 通过并确认模型 Key 未进入环境变量。验收未
  冒充真实模型调用；完整 `make ci`/`make build`、Desktop test/lint/unsigned build 与完整
  PostgreSQL/Redis/MinIO/mTLS/Chromium Integration 均通过。实现提交 `f7810af` 与 GNU/BSD
  `stat` 权限检查兼容修复 `fbc5179` 的 GitHub `ci` run `35446270368`（含 Verify、供应链、
  完整 Integration、Object Storage/Recording GameDay 与 Kubernetes Operator E2E）和
  `desktop` run `35446270328`（Windows/macOS）均成功；目标 Provider 任务仍为部署 Gate，见
  progress 189。

- Personal Secure 单机部署切片本地专用测试 4 项、Control Plane 定向安全测试、Rust 定向与
  Workspace 测试、完整 `make ci`/`make build`、Desktop test/lint/unsigned build、四类生产
  镜像构建及完整 PostgreSQL/Redis/MinIO/mTLS/Chromium Integration 均通过；Web 镜像在只读
  RootFS、Drop All Capabilities 和 `no-new-privileges` 下真实启动，验证 OIDC CSP 与 Nginx
  运行变量。实现提交 `8725d56` 的 GitHub `ci` run `35438884436`（含 Verify、供应链、完整
  Integration、Object Storage/Recording GameDay 与 Kubernetes Operator E2E）和 `desktop`
  run `35438884442`（Windows/macOS）均成功，见 progress 188。

- Agent Browser 高层操作接口切片本地 Java 566 项、Web 143 项、Rust Workspace、
  Worker/Provider、完整 `make ci`、Desktop test/lint/unsigned build、OpenAPI/四 SDK、供应链、
  Operator 17 项、50k Coordinator Capacity、N/N−1 与完整 PostgreSQL/Redis/MinIO/mTLS/Chromium
  Integration 均通过；Integration 输出 `agent_browser_high_level_tools=true`。公开基线为
  250 Operations / 345 Schemas。实现提交 `1b1bae7` 的 GitHub `ci` run `35346560766` 与
  `desktop` run `35346560687`（Windows/macOS）均成功，见 progress 187。

- Profile 网站 Session Health 切片本地 Control Plane 全量、Web 142 项、Rust Workspace、
  Worker/Provider、完整 `make ci`、Desktop test/lint、OpenAPI/四 SDK、供应链、Operator、
  50k Capacity、V123 N/N−1 与完整 PostgreSQL/Redis/MinIO/mTLS/Chromium Integration 均通过；
  Integration 输出 `profile_session_health=true`，显式验证真实 READY 状态形成独立网站健康，
  API 与数据库为 `HEALTHY:READY`。公开基线为 247 Operations / 341 Schemas，见 progress 184。

- 历史环境初始化兼容切片本地 Java 496 项、Web 133 项、完整 Test（首次 Lint 仅格式失败，
  格式化后 Lint/Build 重跑通过）、Desktop test/lint、契约与 N/N−1、完整 Integration 通过。
  `legacy_session_start_metadata=true` 覆盖缺 Demand/Profile 的旧 TERMINATED 环境启动及停止；
  操作员截图原环境 `ses_9797bc3306c944d0` 已经由真实 OrbStack 启动到 RUNNING，保留运行。
  本轮 GitHub 待推送检查，见 progress 163。

- 持久环境启停切片本地 Java 492 项、Web 132 项、Rust Workspace/Clippy、完整
  Test/Lint/Build、Desktop test/lint/unsigned build、OpenAPI/四 SDK、供应链、Operator、
  50k Capacity、N/N−1 与完整 PostgreSQL/Redis/MinIO/mTLS Integration 均通过。
  集成使用确定性 Chromium fixture，输出 `reusable_session_lifecycle=true`；另以真实 Chrome
  验证持久/会话 Cookie 经正常关闭、Checkpoint、清除测试工作区、同 Session 恢复后均保留。
  OrbStack 真实 Chromium 的 RUNNING→HIBERNATED→RUNNING 已验收；实现提交 `2c98988` 已推送，
  GitHub `ci` run `33608543787` 与 `desktop` run `33608543798` 均已通过；后续文档提交
  `a65c5a3` 的 `ci` run `33608695247`、`desktop` run `33608695235` 也通过，见 progress 162。

- 环境列表批量删除切片本地 Control Plane 488 项、Rust Workspace、Web
  122 项、Worker/Provider、完整 Test/Lint/Build、Desktop test/lint/unsigned build、
  OpenAPI/四 SDK、供应链、Operator 17 项、50k Coordinator Capacity、V113 N/N−1 与完整
  PostgreSQL/Redis/MinIO/mTLS/Chromium Integration 已通过；Integration 输出
  `session_batch_delete=true`，覆盖运行中原子拒绝、Viewer/跨租户拒绝、精确幂等重放、
  删除后不可见、审计保留和 Coordinator 路由释放。真实 OrbStack/API 已验证，
  Web/Tauri 共用复选/全选/确认交互已在本地页面验收且无控制台错误。公开基线为
  239 Operations / 319 Schemas；实现提交 `c46940f` 已推送，GitHub `ci` run `33603116263`
  与 `desktop` run `33603116248` 均通过；文档提交 `ac8e2e8` 的 `ci` run `33603368249`
  与 `desktop` run `33603368436` 也均通过，见 progress 160。

- 环境重命名与超期 Operation 收敛切片本地 Control Plane 486 项、Rust Workspace、Web
  121 项、Worker/Provider、完整 Test/Lint/Build、Desktop test/lint/unsigned build、OpenAPI/
  四 SDK、供应链、Operator 17 项、50k Coordinator Capacity、N/N−1 与完整 PostgreSQL/
  Redis/MinIO/mTLS/Chromium Integration 已通过；Integration 输出 `session_rename=true`，
  显式覆盖名称更新、状态保持、Viewer/跨租户拒绝和 Audit。真实 OrbStack 历史超期 Operation
  自动收敛，Headless Chrome 三点菜单及重命名往返通过且无控制台错误。公开基线为
  238 Operations / 317 Schemas；实现提交 `29bbc6b` 的 GitHub `ci` run `33588990497`
  已通过 Verify、供应链、完整 Integration、Object Storage/Recording GameDay 与 Kubernetes
  Operator E2E；`desktop` run `33588990467` 的 Windows/macOS 均通过，见 progress 159。

- AgentClipboard/UserClipboard 显式受控 Bridge 切片本地 Control Plane 484 项、Rust
  Workspace、Web 120 项、Worker/Provider、完整 Test/Lint/Build、Desktop
  test/lint/unsigned build、OpenAPI/四 SDK、供应链、Operator 17 项、50k Coordinator
  Capacity、V112 N/N−1 与完整 PostgreSQL/Redis/MinIO/mTLS/Chromium Integration 已通过；
  Integration 输出 `agent_clipboard_bridge=true`，显式覆盖当前连接/Context、只读拒绝、
  双向 Bridge、租户隔离和账本无正文。公开基线为 237 Operations / 316 Schemas；实现提交
  `372aee5` 的 GitHub `ci` run `33533657239` 已通过 Verify、供应链、完整 Integration、
  Object Storage/Recording GameDay 与 Kubernetes Operator E2E；`desktop` run
  `33533657129` 的 Windows/macOS 均通过，见 progress 158。

- Agent Browser 高级 Action Primitive 切片本地 Control Plane 482 项、Rust Workspace、Web
  119 项、Worker/Provider、完整 Test/Lint/Build、Desktop test/lint/unsigned build、OpenAPI/
  四 SDK、供应链、Operator 17 项、50k Coordinator Capacity、N/N−1 与完整 PostgreSQL/
  Redis/MinIO/mTLS/Chromium Integration 已通过；Integration 输出
  `agent_browser_advanced_actions=true`，以单一 19 Primitive Batch 显式覆盖 Select、Press、
  Drag/Drop/Swipe、Mouse/Keyboard/Touch 和最终结构化状态。第一次 Integration 在创建阶段
  发现 19 动作加固定三步超过旧计划上限 20，修复为 Plan 23/Batch 20 并增加回归后第二次与
  最终第三次完整 Integration 均通过。实现提交 `18c2186` 的 GitHub `ci` run
  `32827778113` 已通过 Verify、供应链、完整 Integration、Object Storage/Recording GameDay
  与 Kubernetes Operator E2E；`desktop` run `32827778135` 的 Windows/macOS 均通过，见
  progress 157。

- Agent Browser 受治理 JavaScript Evaluate 切片本地 Control Plane 481 项、Rust Workspace、
  Web 119 项、Worker/Provider、完整 Test/Lint/Build、Desktop test/lint/unsigned build、
  OpenAPI/四 SDK、供应链、Operator 17 项、50k Coordinator Capacity、V111 N/N−1 与完整
  PostgreSQL/Redis/MinIO/mTLS/Chromium Integration 已通过；Integration 输出
  `agent_browser_javascript_evaluations=true`，显式覆盖 READ_ONLY 副作用保护/递归脱敏、
  PAGE_ACTION 真实页面变更、租户/Actor 隔离、禁止 `fetch`、源码不落库/审计/Outbox 明文和
  State Cursor 自动重试。OpenAPI 基线为 235 Operations / 313 Schemas；实现提交 `2240f75`
  的 GitHub `ci` run `32733435238` 第二次尝试（首轮仅因 GitHub API Unicorn 导致固定 Buf
  Action 安装失败）已通过 Verify、供应链、完整 Integration、Object Storage/Recording
  GameDay 与 Kubernetes Operator E2E；`desktop` run `32733435176` 的 Windows/macOS 均通过，
  见 progress 156。

- Agent Browser 截图切片本地 Control Plane、Rust Workspace、Web 118 项、Worker/Provider、
  完整 Test/Lint/Build、Desktop test/lint/unsigned build、OpenAPI/四 SDK、供应链、Operator、
  50k Coordinator Capacity、V110 N/N−1 与完整 PostgreSQL/Redis/MinIO/mTLS/Chromium
  Integration 已通过；Integration 输出 `agent_browser_screenshots=true`，显式覆盖 Region 捕获、
  自动 State Cursor 重试、租户/Actor/Purpose 隔离、SHA-256 和一次性兑换。OpenAPI 基线为
  233 Operations / 310 Schemas；实现提交 `703f974` 的 GitHub `ci` run `32715223974`（含
  供应链、完整 Integration、Object Storage/Recording GameDay 与 Kubernetes Operator E2E）
  和 `desktop` run `32715224012`（Windows/macOS）均通过，见 progress 155。

- Agent Browser 文件切片本地 Rust 最终 Clippy/Workspace 测试、Control Plane/Web 117 项、
  Worker/Provider、完整 Test/Lint/Build、Desktop、OpenAPI/四 SDK、N/N−1 与最新完整
  PostgreSQL/Redis/MinIO/mTLS/Chromium Integration 已通过；Integration 显式验证隐藏文件
  输入、上传提交、跨租户拒绝、下载生命周期及数据库/审计无文件内容或 Node 路径，输出
  `agent_browser_files=true`。OpenAPI 基线为 230 Operations / 306 Schemas；实现提交
  `8663157`，SDK 测试基线修复提交 `2b6ed3c` 的 GitHub `ci` run `32704051504`（含供应链、
  完整 Integration、Object Storage/Recording GameDay 与 Kubernetes Operator E2E）和
  `desktop` run `32704051540`（Windows/macOS）均通过，见 progress 154。

- Agent Browser 原生 Dialog 切片本地 Control Plane 466 项、Rust Workspace、Web 115 项、
  Worker/Provider、完整 Test/Lint/Build、Desktop、OpenAPI/四 SDK、N/N−1 与两轮完整
  PostgreSQL/Redis/MinIO/mTLS/Chromium Integration 已通过；Integration 显式覆盖四种
  JavaScript Dialog、Prompt 回填、明文不回显、freshness 与权威关闭，输出
  `native_dialog_lifecycle=true`。实现提交 `1d64a75`，Rust 1.98 冷机 Clippy 兼容修复最终
  提交 `519f588`；GitHub `ci` run `32629343630`（含供应链、完整 Integration、Object
  Storage/Recording GameDay 与 Kubernetes Operator E2E）和 `desktop` run
  `32629343648`（Windows/macOS）均通过，见 progress 153。

- Agent Browser 权威多标签页切片本地 Control Plane 462 项、Rust/Web 定向测试、
  OpenAPI/四 SDK、N−1 与完整 PostgreSQL/Redis/MinIO/mTLS/Chromium Integration 已通过；
  Integration 显式覆盖 open/switch/close、跨允许域收尾、Profile 精确集合及 19 个持久
  Workflow。实现提交 `66d0fef` 的 GitHub `ci` run `32386875084`（含供应链、Integration、
  Object Storage/Recording GameDay 与 Kubernetes Operator E2E）和 `desktop` run
  `32386874924`（Windows/macOS）均通过，见 progress 152。

- Agent Browser 扩展指针/表单动作切片本地 Control Plane 457 项、Rust Workspace、Web
  115 项、Worker/Provider、完整 Test/Lint/Build、Desktop、OpenAPI/四 SDK、N/N−1 与
  PostgreSQL/mTLS/Chromium Integration 已通过；实现提交 `de8b87a` 的 GitHub `ci`
  run `32374772306`（含供应链、Integration、Object Storage/Recording GameDay 与
  Kubernetes Operator E2E）和 `desktop` run `32374772298`（Windows/macOS）均通过，
  见 progress 151。

- Agent Browser Batch 稳定重绑定切片实现提交 `54b28ea`；本地 Control Plane 456 项、Rust Workspace、Web
  115 项、Worker/Provider、完整 Test/Lint/Build、Desktop、OpenAPI/四 SDK、N/N−1 与
  PostgreSQL/mTLS/真实 Chromium Integration 已通过；最终提交 `ec7b61f` 的 GitHub `ci`
  （run `32368996758`，含供应链、Integration、Object Storage/Recording GameDay 与
  Kubernetes Operator E2E）和 `desktop`（run `32368997078`，Windows/macOS）均通过，
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

- progress 187：Agent Browser 正式高层操作面已收敛为
  `snapshot/find/inspect/act/wait/handoff`。`act` 复用既有动态微批和风险链；`wait`、`handoff`
  只暴露有界输入并转换为持久 `WAIT_FOR`/`REQUEST_HUMAN_TAKEOVER` Task，继续经过精确 State
  Cursor、Reviewer、Capability、人工治理和 Outcome Verification。旧 `execute-actions` 保留为
  deprecated 兼容别名。A21 仓库内通用代码项已关闭。

- progress 188：独立 Personal Secure 单机部署层已闭环 A22 仓库代码项。它不复用开发身份，
  只绑定 loopback，使用真实 OIDC/API Audience、随机文件 Secret、内部 mTLS、四 Worker 独立
  进程/身份、受控出口、HTTPS 对象/模型与加密 Profile；不允许据此直接反代公网。A01 默认开发
  Compose 完整 Worker 链后由 progress 189 闭环。

- progress 189：默认 localhost Compose 已启用外部 Agent/Reviewer/Outcome 队列并启动四个独立
  Worker；模型配置与私有文件 Key 缺失时 fail-closed，首次成功权威长轮询后才 healthy。真实
  Compose 运行链已通过，但运营方真实 Provider 任务仍是部署环境 Gate。

- progress 186：DOM、Layout、Focus、Route 的连续权威采样已与 Network 组合为统一页面稳定
  证据；单动作和动态微批至少等待全部组件安静 250ms，控制面 Outcome 稳定要求全部组件至少
  2 秒。阈值进入有界 Content Hash bucket，旧 Node/REGION/Dialog 阻断均 fail-closed。真实 Chrome
  已验证 DOM 增删、布局移动、焦点和 SPA Route 变化。A20 仓库内通用代码项已关闭；CSS/Canvas
  纯像素变化、真实客户 SPA Replay 和目标环境长稳仍是生产 Gate。

- progress 185：跨域、Sandbox 或不可读取 iframe 已投影为 Origin-only Opaque Frame，包含稳定
  `frameRef`、Bounds、边界原因与 freshness，不含内部 DOM 或 URL Path/Query。Frame 永不进入
  可执行 Target；progress 198 增加精确 Origin 策略与 Task Domain 双重授权下的低风险单次左键，
  截图和动作均由 Node 按 State/Hash/Active Tab/frameRef/Bounds 即时重验。所有跨域输入及支付/
  账号决策继续 Human Handoff。A19 仓库通用观察项与该安全自动化切片已关闭；真实 IdP/支付/托管
  Challenge Replay 仍是生产 Gate。

- progress 184：Profile 网站 Session Health 已与 Checkpoint `TECHNICAL_READY` 分离。V123 按
  Tenant/Profile/Origin 保存来自精确 Business Recovery State 的 `HEALTHY / REAUTH_REQUIRED /
  DEGRADED` 证据及新鲜度；健康可过期，Reauth/降级不会随时间自动清除，只有更新 Epoch/Version
  的可信 READY 可以恢复。Profile API、逐站点详情和 Web/Tauri 独立状态列已接入；A18 仓库内
  代码项已关闭，真实站点 Contract/Provider 证据和撤销延迟仍是生产 Gate。

- progress 183：Profile Cold Archive 已从明文 `checkpoint.tar.zst` 改为版本化 KEK 封装随机
  DEK 的 AES-256-GCM `.tar.zst.enc`；Tenant/Profile/Checkpoint/明文哈希纳入 AAD。受限文件
  Keyring 支持历史 Key 读取；旧明文对象在恢复/导出时 commit-last 迁移后删除；导出保持密文，
  导入先认证解密。已有 checkpoint 的 Placement 与 Import/Export 候选要求
  `profileArchiveEncryption=aead-envelope-v1`，滚动升级不会落到不兼容 N−1 Node。A17 仓库内代码项
  已关闭；本地卷加密、目标云 KMS/HSM/Workload Identity 与正式轮换/灾备演练仍是生产 Gate。

- progress 182：Real-URL Agent Matrix 已在隔离 Docker 服务和真实 Chromium 中实际访问
  Cloudflare 公共 trace 页面；该证据不冒充 Cloudflare 托管 CAPTCHA 绕过。安全可绑定 Target 的
  `SINGLE_CLICK` 改走 V122 `STRUCTURAL_CLICK`，Node 在输入前重验 State/Target/Bounds/Visual
  Anchor，不依赖截图、对象存储、模型或人工确认；仓库自有授权 Fixture 已验证一次 CLICK 完成且
  Challenge Event 未人工授权。Web Console 两个“添加”按钮已有唯一可访问名称，提交前继续 JIT
  Rebind；真实 Web E2E、Viewer RBAC E2E、完整 `make ci`、Desktop 和完整 PostgreSQL/Redis/
  MinIO/mTLS/Chromium Integration 均通过。

- progress 181：幂等 Agent Task cancel API 在终止 Task/Operation 前先持久化精确
  `CancelAgentAction`；Step 失败、Operation 丢失/过期和权威 Epoch 前进复用同一终止语义。
  Coordinator 与 Node 取消命令走独立双线程高优先级调度链；Node 以 Task/Context/Operation/
  Term/Route 栅栏、注册竞态 tombstone 和 watch 中断长动作并 release-all。迟到失败事件以
  `STALE_AGENT_OPERATION` 终态排空，不能复活任务；旧 Node 缺少取消能力时动作前 fail-closed。
  V121 在通用 API 幂等账本增加可选响应快照；本地与远程路由都按同一取消幂等键返回首次提交的
  `AgentTaskView`，不会因迟到 Node 事件继续更新 Task 投影而产生不同重放响应。
  公开 API 为 246 Operations / 338 Schemas；
  Control Plane 555 项、Rust、完整 `make ci`、Desktop、N/N−1 与 PostgreSQL/Redis/MinIO/mTLS/
  Chromium Integration 均通过，输出 `agent_action_fast_cancellation=true`。A15 仓库内代码项关闭；
  实现提交 `2695153` 的 GitHub `ci` run `34939222987` 与 `desktop` run `34939222878` 均成功。
  外部 HTTP/模型客户端传输取消后由 progress 196 闭环；Provider 服务端强取消、目标云网络时延
  与副作用补偿仍是独立生产边界。

- progress 180：Agent Executor、Reviewer、Outcome Verifier、Vision、Runtime Validation 与
  Recovery GameDay 六类 Worker Claim 已使用 V120 PostgreSQL 事务通知与 15 秒有界长轮询；
  Control Plane 每实例一个专用 LISTEN 连接，以 generation 关闭注册竞态、单通知只唤醒一个本实例
  等待者，并在通知/超时后重查权威队列。断线保留 backoff+jitter，默认 `waitSeconds=0` 保持 N−1。
  公开 API 保持 245 Operations / 338 Schemas；Control Plane 553 项、Worker 43 项、完整
  `make ci`、Desktop、N/N−1 与 PostgreSQL/Redis/MinIO/mTLS/Chromium Integration 均通过，输出
  `worker_queue_long_poll_notify=true`。A14 仓库内代码项关闭；GameDay UI timeline 轮询仍独立跟踪。

- progress 179：外部 Application/Web/Email/Document/Widget 来源不论内容或自报分类均永久
  data-only，保留/重复 Source ID 在任务创建期阻断；首次执行、异步 Step 续行、人工协助续行和
  lease 恢复的共同入口逐 Step 要求来源精确等于 `user_goal + platform_policy`、Trust Floor 为
  `TRUSTED` 且 taint 为空。Reviewer 批准不能提升网页内容权限，关键词检测只保留为遥测。
  公开 API 保持 245 Operations / 338 Schemas；Control Plane 549 项、完整 `make ci`、Desktop、
  N/N−1 与 PostgreSQL/Redis/MinIO/mTLS/Chromium Integration 均通过，输出
  `prompt_injection_source_authority=true`。A13 仓库内代码项关闭；客户 Replay、目标模型生产准入
  和组织 Threat Review 仍独立待完成。

- progress 178：Reviewer 开启时，正式执行入口统一按风险分流；仅 Intent ALLOWED、计划未过期、
  来源可信、无 taint/确认/敏感输入，且 Task/全部 Step 均不高于 R1 的计划以 `risk-tier-v1`
  确定性策略直接进入持久 Agent Worker 队列。Task 明确记录 `NOT_REQUIRED` 和旁路原因，模型、
  Token、Latency、Cost 为空并写版本化 Audit；R2+、风险低报、未知工具或解析异常仍强制 Reviewer。
  公开 API 保持 245 Operations / 338 Schemas；Control Plane 544 项、完整 `make ci`、Desktop、
  N/N−1 与 PostgreSQL/Redis/MinIO/mTLS/Chromium Integration 均通过，输出
  `agent_reviewer_risk_routing=true`。A12 仓库内代码项关闭；A13 后由 progress 179 关闭，客户
  Replay 和目标模型生产准入仍独立待完成。

- progress 177：Browser Node 在同一持久 `execute-actions` Batch 内按 Route/Active Tab、Native
  Dialog、Document/Network、Target Revision、Content Hash 变化或四动作稳定上限动态切段；边界
  取得连续两份相同且 Network Fresh/Quiet 的可执行状态后才续行。动作结果通过 additive
  Protobuf 字段携带微批次索引和有界边界原因；5 秒内无法稳定时保留已完成动作、将剩余动作标记
  SKIPPED 并终止 Step，不自动 Replan 重放副作用。公开 API 保持 245 Operations / 338 Schemas；
  Control Plane 541 项、Web 141 项、Worker 30 项、Rust、Desktop、N/N−1、完整 `make ci` 与
  PostgreSQL/Redis/MinIO/mTLS/Chromium Integration 均通过，输出
  `agent_browser_dynamic_micro_batches=true`。A06 仓库内通用代码项关闭；当时独立跟踪的 A20
  后由 progress 186 关闭。

- progress 176：V119 增加 `CHALLENGE_SCREENSHOT` 与 nullable、整组约束的捕获范围/隐私证明；
  Control Plane 与 Node 在截图前后精确围栏 State Version/Hash、Target Revision、Active Tab 和
  有界 Region，Vision Worker 在外部模型前本地 OCR/PII 遮罩并二次复核，OCR 原文不进入
  PostgreSQL/API/Audit/模型。无安全 Target 的 Canvas/图片/PDF 不回退整页 Vision。API/四 SDK
  保持 245 Operations / 338 Schemas；Control Plane 539 项、Web 141 项、Worker 30 项、Rust、
  Desktop、N/N−1、完整 `make ci` 与 PostgreSQL/Redis/MinIO/mTLS/Chromium Integration 均通过，
  输出 `challenge_visual_pixel_privacy=true`。A05 仓库内通用代码项关闭；Recording 全帧 OCR、
  非文本视觉分类、A19 Opaque Frame 与目标模型生产准入仍独立待完成。

- progress 175：State Collector 将最近显式业务实体键或 `row/listitem/treeitem/tr/li` 规范化
  行语义以 Node 内 hash-only 指纹绑定 Element ID；原文在 State Hash/Registry 前清除，不进入
  Browser State/API/Audit。真实 Chrome 在同一 DOM 槽位保持同名按钮、只替换业务行后验证旧
  ID 拒绝与新 ID 可解析。A03 仓库内通用代码项关闭；无业务键且可见语义完全相同的站点需由
  Adapter 提供实体属性，A06 后由 progress 177 关闭；当时独立跟踪的 A20 后由 progress 186
  关闭。Rust Workspace、
  完整 `make ci` 与 PostgreSQL/Redis/MinIO/mTLS/Chromium Integration 均通过，公开契约保持
  245 Operations / 338 Schemas。功能提交 `4cbfd99` 的 GitHub CI `34682995669` 与 Desktop
  `34682995642`（Windows/macOS）均成功。

- progress 174：V118 为 Task 增加最多十条结构化 Expected Outcome，覆盖最终 URL/标题、语义
  目标存在性与 checked/selected 状态；创建时原始匹配值规范化后只持久化 SHA-256。控制面对
  Outcome Verifier 的同一精确最终 State 先做确定性判定，完整状态失败或深度受限不确定时，
  即使模型提交 `VERIFIED` 也强制收敛为 `NOT_VERIFIED`。API/四 SDK/Web/Tauri 已同步至
  245 Operations / 338 Schemas；完整 Integration 输出 `agent_task_expected_outcomes=true`，
  覆盖原文不落库与模型假成功拒绝。A04 仓库内通用代码项关闭；站点领域 Validator 仍独立待完成，
  当时跟踪的 A20 后由 progress 186 关闭，A03/A05 已由 progress 175/176 关闭。功能提交
  `153f077` 后 Docker Hub 固定 MinIO 镜像下架导致
  首次 CI 非产品失败；保持版本不变迁移至官方 Quay 的修复提交 `172f6d3` 已通过 GitHub CI
  `34681675184` 与 Desktop `34681675198`（Windows/macOS）。

- progress 173：动作技术成功后先进入持久 `VERIFYING_OUTCOME`，独立
  `OUTCOME_VERIFIER_WORKER` 以 Task Goal、最小化执行证据和新鲜、完整、稳定的最终结构化状态
  作语义判定；V117 增加独立 Job/Event 账本、租约/Claim Epoch/模型版本/State 与 Target
  精确围栏、失败重试和成本审计。`NOT_VERIFIED` 会以 `AGENT_OUTCOME_NOT_VERIFIED` 同时终止
  Task 与等待中的 Agent Worker Job，不再把动作 ACK 冒充业务成功。API/四 SDK/Web/Tauri
  已同步至 245 Operations / 334 Schemas；完整 Integration 输出
  `agent_task_outcome_verification=true` 并覆盖真实三 Worker 正向链及假成功拒绝；Java 528 项、
  Web 140 项、Worker 24 项、完整 `make ci`、Desktop test/lint/unsigned build 均通过。
  首次 GitHub CI 由新披露 `CVE-2026-84445` 阻断，Terraform Provider 的 gRPC-Go 已从
  `v1.83.1` 升至修复版 `v1.83.2` 并通过 race/vet/供应链检查，未增加豁免；修复提交
  `61f4a76` 的 GitHub CI `34467992718` 与 Desktop `34467992747` 均成功。A11 仓库内代码项
  关闭；A04 的结构化 Expected Outcome 契约和目标模型生产准入仍独立待完成。

- progress 172：V116 将 Browser State、可变 Task State 与 append-only Execution History 分离；
  持久历史只保存规范化语义哈希、状态/验证/结果哈希、稳定原因和可选 State Version，不复制
  正文、Secret、Capability、工具输出或 Browser State JSON。API/四 SDK/Web/Tauri 已同步至
  240 Operations / 322 Schemas；完整 PostgreSQL/Redis/MinIO/mTLS/Chromium Integration 已
  输出 `agent_task_structured_memory=true`；Java 525 项、Web 140 项、完整 `make ci`、Desktop
  test/lint/unsigned build 均通过。实现提交 `42f9901` 已推送，GitHub CI `34317007418` 与
  Desktop `34317007425` 均成功。A10 已关闭；它本身不证明业务 Outcome，A11 后由
  progress 173 关闭，A04 仍待完成。

- progress 171：V115 新增不含正文、Secret 或 Capability 的 Agent Action Attempt 哈希账本；
  规范化动作签名绑定执行前权威 State Hash，同一 Task/State 的第三次相同动作在 Capability 消费
  和 Node 派发前以 `AGENT_ACTION_LOOP_DETECTED` 终止。Task 行锁保证并发判定，`WAIT_FOR` 与
  派发前失败不误计；Java 523 项、Web 140 项、完整 Test/Lint/Build、Desktop、N/N−1 与
  PostgreSQL/Redis/MinIO/mTLS/Chromium Integration 已通过。首次 GitHub CI 的 A09 场景通过，
  随后既有 PAGE_ACTION 在冷 Runner 上连续命中合法 State Stale；夹具已要求稳定 Cursor 后提交
  并保留有界重试，未放宽生产围栏。修复提交 `8970047` 已推送，GitHub CI `34311805818` 与
  Desktop `34311805802` 均成功。A09 已关闭；它本身不证明业务 Outcome，A11 后由
  progress 173 关闭；当时待完成的 A04/A20 后由 progress 174/186 关闭。

- progress 170：V114 单独保存控制面接收最后权威 Browser State 样本的时间；API/四 SDK
  增加 age/freshness/pageActivity，STALE 状态禁止结构化规划，Web/Tauri 显示样本年龄与页面
  活动。稳定页面通过 15 秒合并、精确状态围栏且不触发公开 SSE 的最小 observation heartbeat
  保持新鲜；完整 Test/Lint/Build、契约/四 SDK、N/N−1 与完整 Integration 已通过。组合
  当时仍由 A20 跟踪的 DOM/Layout/Focus/Route 稳定性后由 progress 186 关闭。实现提交
  `677f694` 已推送，GitHub CI
  `34224365484` 与 Desktop `34224365485` 均成功。
- progress 169：Agent Task 正式 API 增加由持久 Task 状态确定的
  `RETRY/REFRESH/REPLAN/WAIT/HUMAN/TERMINAL` 恢复指令；Web/Tauri 共用任务详情已展示
  当前步骤、动作、验证、失败原因和 Why Stuck/下一决策。公开基线为 240 Operations / 320
  Schemas；Java 512 项、Web 139 项、完整 Test/Lint/Build、四 SDK、N/N−1 与完整
  PostgreSQL/Redis/MinIO/mTLS/Chromium Integration 已通过，`a51579d` 已推送。
- progress 168：资源策略 PATCH 增加精确 Tenant/Session 行锁和真实 PostgreSQL
  竞争回归；控制面非 local/test 环境统一默认密钥、mTLS、签名及下载检查，Node/Helper
  已同步环境判断；Vision HTTP 错误分类已修。Java/Rust/Worker 定向 Gate 和完整集成通过；
  `4feb93c` 已推送，GitHub CI `34099931923` 与 Desktop `34099931978` 均成功。

最近切片与当前最高优先级开发任务：

- 2026-09-03 用户要求逐项修复 Agent 可靠性、安全部署和治理问题并验证推送；当前以
  `docs/progress/165-Agent可靠性与个人安全部署修复清单.md` 的 A01—A24 为实施账本。
  第一切片已加三 Worker 有界退避、Vision lease-lost 主流程阻断和 finally 心跳清理；
  Local Header 仅允许显式 local/test，其他环境进入 OIDC 链。语义目标、Expected Outcome 和
  Challenge 像素隐私、动态微批次、Reviewer 风险路由、取消、Profile 加密、Personal Secure
  和默认 Compose 完整 Worker 链已由 progress 175、174、176、177、178、181、183、188、189
  关闭；真实 Provider 与目标环境运行仍是部署 Gate。
  不得把 Worker 心跳修复冒充已完成浏览器长操作取消。仓库许可证元数据 MIT/UNLICENSED
  不一致，未经权利人选择不得擅自对整个仓库授予新许可证。
- progress 167/175：Element ID/target_ref 新增名称/角色/控件类型/Route/Tab 及最近业务实体
  hash-only 语义围栏，可变输入值/焦点/勾选/位置不参与；真实 Chrome 已覆盖改名及同路径同名
  业务行替换后拒绝旧 ID，A03 仓库内代码项关闭。控制面不再把包含 FAILED/SKIPPED 的 Batch 判为 VERIFIED，已知
  失败直接结束该 Step，不以 Resync 洗白；这不替代业务 Expected Outcome 验证。

### Agent Browser 结构化感知与低延迟执行（高级 Action Primitive 已闭环）

- Snapshot/Inspect/Find、精确 State Cursor、稳定 Element ID、可见/可操作性判定和同源
  iframe/open Shadow DOM 已完成开发；
- 统一 Batch/Fast Path 已支持 CLICK、DOUBLE_CLICK、RIGHT_CLICK、HOVER、CLEAR、CHECK、
  UNCHECK、TYPE、FILL、AgentClipboard Paste、SCROLL、WAIT、SELECT、PRESS、DRAG、DROP、
  SWIPE 和目标约束的通用 Mouse/Keyboard/Touch，
  每步重验真实状态，VNC 真人输入优先后续行同一 Batch；
- Challenge Human-like 轨迹、Session Identity 创建时锁定/Change Request/Runtime 应用、
  独立 AgentClipboard 和 OpenAPI/四 SDK 已完成开发；
- Java 456 项、Rust/Web/Worker/Provider、Test/Lint/Build、四 SDK、Desktop、供应链、
  Operator、50k Coordinator Capacity、N−1 和完整 PostgreSQL/mTLS/Chromium Integration
  已通过；提交 `a14e5f1` 的 GitHub `ci` run `32363001442` 与 `desktop` run
  `32363001455` 也均通过；
- 原生 Dialog、File、Screenshot、受治理 JS Evaluate 和高级 Action Primitive 已分别由
  progress 153—157 闭环；显式受控 Clipboard Bridge 已由 progress 158 关闭，基础结构化
  感知和高级动作不得重做。

### AgentClipboard/UserClipboard 显式受控 Bridge（已闭环）

- V112 以内容零持久化账本保存 Direction/Purpose/Connection/Context/Version/Hash/Length，
  Tenant/Session/Actor/幂等和状态约束均由 PostgreSQL 强制；
- USER_TO_AGENT 只消费当前 noVNC 连接两分钟内的真实 Clipboard observation；
  AGENT_TO_USER 只允许当前 Actor 的非只读连接，读取后由同一 noVNC 连接写入并完成账本；
- Web/Tauri 共用 Remote Desktop 入口和 API Client；页面元数据查询不解密正文；OpenAPI/
  四 SDK 为 237 Operations / 316 Schemas；
- 本地全量 Gate 与 Integration 已通过；功能提交 `372aee5` 的 GitHub `ci` run
  `33533657239` 和 `desktop` run `33533657129` 均通过。自动模式仍只在 OTP/设备确认/高风险
  决定等真人信息确实缺失，或 Challenge 自动预算耗尽时通知一次，人工可发 OTP 由 Agent
  代填或自愿进入 VNC。

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

### Recording 对象治理（后续开发切片）

Recording purpose-bound 一次性播放 Grant 与到期物理删除 Worker 已由 progress 200、201 闭环。
下一仓库级切片为全帧 OCR/非文本视觉敏感分类、目标 Bucket Object Lock/WORM 和更深的目标云
Legal Hold 联动；不得把普通 Delete API 或短期签名 URL 冒充对象不可变或目标云监管保留。

## 8. 尚未完成的功能

### P0/P1：仓库内代码产品化

1. Warm Tier SQLite/LevelDB 应用感知 Adapter、Multipart Resume、跨 Region Restore、Profile 对象保留/Legal Hold 深度联动。
2. 目标 CRM/支付/IAM Provider 的真实凭据、字段/事务映射和 Provider 特有认证接入。
3. 目标云 Secret 解引用/轮换/撤销、商业 Proxy Provider Adapter、高级 SLA/业务成功率路由、Challenge/黑名单与受约束探索。
4. 无语义像素/OCR Validator、客户站点高级组合规则、大规模 Replay/Canary/回滚阈值。
5. Recording 全帧 OCR/非文本视觉敏感分类、目标 Bucket Object Lock/WORM 和目标云原生 Legal Hold 联动；到期对象删除 Worker 已由 progress 201 完成。

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
12. [已确认] **原生 Dialog**：只认持续 `Page.javascriptDialogOpening/Closed` 和安全 Probe；
    DOM Dialog/权限弹窗独立。Prompt Secret 只在 Node 派发前解封；freshness 丢失时保留最后
    PostgreSQL 投影但拒绝旧动作，禁止把 DOM role 或空列表冒充已关闭。

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

- 2026-09-03 环境 UI 与远程桌面流畅模式（progress 164）已通过本地与 OrbStack 验收：详情页在
  `maximumHourlyCost=null` 时崩溃，已修复；生命周期成功提示改为不参与列宽的状态播报。
  Gateway 共享上游仍是精确 Raw，但每连接可按标准 RFB Quality hint 转为独立 Tight JPEG，
  低画质使用色彩降采样；清晰档/旧客户端保持 Raw。不是 CSS 缩放，也没有降低 Chromium
  页面分辨率或绕过 Actor/Session FPS、带宽配额。两条原环境保存后恢复 RUNNING；
  真实画面三档切换不重连，首个详情可用。2ecbfda已推送；ci33713083175、desktop33713083347
  检查时运行中；不声称动态FPS达标。

- 2026-09-02 操作员复测发现六条旧环境缺 Demand，其中三条缺 Profile 元数据。
  progress 163 已补启动时事务初始化：只适用于 runtimeBuild/node 均空、generation/epoch=0、
  CREATED/TERMINATED 的未运行旧记录，使用既有资源级别/扩展/真人协作设置，保留租户校验与审计。
  其他曾运行或现代环境若缺资源数据仍拒绝，不能通过重建空数据掩盖损坏；其余旧记录在用户启动时修复。

0. OrbStack Chromium 的沙箱启动阻塞已由 progress 162 修复：显式安装 chromium-sandbox，
   仅 Browser Node 使用版本固定的 Docker 默认 seccomp 加 clone/setns/unshare 规则。
   未启用 privileged/SYS_ADMIN/--no-sandbox。普通停止必须保持可重启，不得回退到一次性环境。
   本机容器内 Maven 下载曾遇 TLS 握手失败，本次 Control Plane 以本机验证后的同版 Boot JAR
   构建运行镜像；标准源码镜像构建仍依赖 Maven 网络可用性。目标平台沙箱长稳仍独立验收。
1. `useRecoveryGameDayEvents()` 仍以 5 秒轮询读取；替换前需先证明 timeline 事件分页的完整顺序和权限边界。
2. 遗留 `EXCLUSIVE_TAKEOVER` 枚举/协议字段尚在 N/N-1 兼容窗口内；行为已失效，但暂不能物理删除。
3. VNC/Agent 综合 E2E 历史上出现与 VNC 无关的 Agent 表单响应 30 秒偶发超时；并发关键段已有真实证据，完整长稳仍需单独稳定。
4. README 目录表已由 progress 166 增加生成/CI 门禁；历史进度快照仍不能替代代码证据。
   实际查询确认 `917a8ff`/`2ecbfda` 的 ci 因 gRPC-Go 高危告警失败，desktop 成功。
   Provider 已升级修复版 1.83.1，本地 race/vet/build/发布门禁通过，GitHub 复验待完成。
5. 目标环境、真实外部凭据和组织审批缺失是发布阻断项，不是本地单测通过即可关闭的代码任务。

## 12. 当前重点与推荐优先级

| 优先级 | 任务 | 原因 |
| --- | --- | --- |
| P1 | Recording 全帧视觉分类、WORM/目标云 Legal Hold 和对象治理 | 涉及敏感浏览器证据与监管型保留的生产闭环 |
| P1 | Warm Tier 数据库感知 Adapter/Resume/跨 Region Restore | Profile 一致性和迁移恢复的主要剩余代码缺口 |
| P1 | 目标 Provider/Secret/Proxy Adapter | 真实客户业务接入的前提 |
| P1 | OCR/高级 Validator/Replay | 视觉安全和生产 Agent 质量 Gate |
| P2 | 目标 Linux/云/多 Region/桌面签名长稳矩阵 | 发布 Gate，需要真实环境和外部凭据 |
| P2 | 组织安全与发布签字 | 正式上线治理 Gate |

## 13. 下一步开发计划

1. Clipboard Bridge 已由 progress 158 完成；不得让 Agent Planner 自动调用该操作员显式
   协作通道，也不得用它替代账号/密码/OTP 一次性敏感输入 API。
2. Recording purpose-bound 一次性播放 Grant 与到期删除 Worker 已由 progress 200、201 完成；
   继续全帧 OCR/非文本视觉分类、目标 Bucket Object Lock/WORM 和目标云原生 Legal Hold 联动。
3. Warm Tier 数据库感知 Adapter/Resume/跨 Region Restore、目标 Provider/Secret/Proxy 和 OCR/Replay 按第 12 节顺序推进。
4. 持续补齐目标 Linux/云/多 Region/桌面签名长稳和组织安全发布 Gate；仓库测试通过不等同于允许处理真实客户数据。

## 14. 何时必须更新本文件

发生下列任一变化时，在同一提交中更新 `AGENTS.md`：项目目标变化、架构变化、核心模块增删、重要方案确认或推翻、重要阶段完成、当前开发重点切换、出现影响后续开发的 Bug/限制，或本文与代码真实状态不一致。

本文件只保存跨会话接手所需的稳定信息；不要粘贴大量调试日志、失败命令输出、重复讨论或尚未验证的推测。未确定事项必须标为“待确认”或“待评估”。
