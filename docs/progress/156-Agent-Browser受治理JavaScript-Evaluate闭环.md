# Agent Browser 受治理 JavaScript Evaluate 闭环

> 日期：2026-08-24
> 状态：仓库内实现、本地完整 Gate 与 GitHub CI/Desktop Gate 均已通过

## 目标与边界

本切片增加正式的粗粒度 JavaScript Evaluate API，但不向 Agent 暴露任意 CDP。调用必须绑定
租户、Session、Actor、持久 Operation、精确 Browser State Cursor 和唯一活动 Page Target；
`READ_ONLY` 使用 Chromium `throwOnSideEffect`，`PAGE_ACTION` 复用既有 Intent 风险策略并在
活动 Challenge 期间 fail-closed。Cookie、Credential、Storage、Clipboard、Network、Navigation、
Tab、Chrome/DevTools 等逃逸入口在 Control Plane 与 Browser Node 双重拒绝。

AUTONOMOUS 下的 Evaluate、状态过期后重取再试和其他普通失败恢复保持静默。只有 OTP、设备确认、
高风险决定等确实需要真人提供的信息缺失，或低风险 Challenge 自动预算耗尽时通知一次；操作员可把
OTP 发给 Agent 代填，也可自愿进入 VNC，系统不会把人工接管变成普通自动化的必经步骤。

## PostgreSQL 权威模型与 API

- V111 创建 `agent_browser_javascript_evaluations`，保存 Tenant/Session/Actor/幂等键、请求/
  Operation/Command、Node、State/Target/Active Tab 围栏、模式、表达式 SHA-256/字节数、受限结果
  和终态；没有表达式源码列；
- `POST /api/v1/sessions/{sessionId}/agent-browser/evaluations` 要求 `OPERATE`、Actor 和幂等键；
  同一 Actor/幂等请求复用原 Operation，跨 Actor、跨租户读取返回 404；
- `GET .../evaluations/{evaluationId}` 只重读 PostgreSQL，可在连接上限内有界等待；结果大小限制
  1—32768 字节，超时限制 100—5000 ms；
- 表达式先以 AES-GCM 密封进入持久 Outbox，仅在 mTLS 派发前解封并清空密封字段；源码不进入
  Evaluation 表、普通 Audit、API 响应、Agent Worker 或 Reviewer；
- 返回值递归脱敏常见敏感键；异常类型/消息、错误码、耗时和执行后 State Cursor 均有界持久化。

## Browser Node 执行与恢复

- `AgentBrowserEvaluateCommand` 继续使用 Coordinator Lease、Term/Route Epoch、mTLS、Outbox、
  Node Journal 和命令幂等链；Node 先检查真人输入优先级，再重验完整 State Hash/Version、Target
  Revision、Active Tab、State Quality 和原生 Dialog；
- READ_ONLY 设置 `throwOnSideEffect=true`；PAGE_ACTION 可修改当前页面，但不能访问被禁止的
  浏览器能力；执行后始终重新采集权威 Browser State；
- 预执行状态过期时不运行脚本，Node 先发布真实 `AGENT_EVALUATE` StateUpdated，再以
  `STATE_STALE` 终结 Evaluation，使调用方可基于新 Cursor 自动重试而不会锁死在旧状态；
- 真人输入优先返回暂态 `HUMAN_INPUT_PRIORITY`，Dispatcher 延后同一命令；这类内部续行不创建
  Human Takeover 或人工通知；
- Control Plane 对 Evaluation 专用 StateUpdated 不再误走 Agent Task Step 完成逻辑，仍会保存
  PostgreSQL Browser State 并执行 Challenge/Recovery 观察。

## 契约、复用与兼容

- OpenAPI 和 TypeScript/Python/Go/Java SDK 同步为 `235 Operations / 313 Schemas`；Web 与
  Tauri 复用同一 `agent.ts` Client、Actor Header 和 Evaluation 类型；
- Protobuf additive 增加命令 tags 1—12、完成事件 tags 1—19，以及
  `agentJavascriptEvaluate=state-fenced-bounded-v1` 能力标签；旧 Node 不具备能力时 Control
  Plane fail-closed；
- N/N−1 Gate 锁定 V111 expand-only 迁移、字段 tags、能力协商和命令类型，不改变历史命令语义。

## 本地验证证据

- `make test`：Control Plane 481 项、Rust Workspace、Web 119 项、Application Adapter、
  Validation/GameDay/Agent/Reviewer/Vision Worker 和 Go Provider race 均通过；
- `make lint` 与 `make build`：Java Check、Rust fmt/Clippy `-D warnings`、Web ESLint/Prettier/
  Production Build、Python compile 和 Go vet/build 均通过；
- OpenAPI/Buf、V111 N/N−1、四 SDK 生成/运行/打包、Desktop test/lint/unsigned build、供应链、
  Operator 17 项和 50k Coordinator Capacity 均通过；SDK Gate 实际发现并修复三语言 Operation
  覆盖断言仍停留在 233 的漂移；
- 完整 PostgreSQL/Redis/MinIO/mTLS/Chromium Integration 退出 0，输出
  `agent_browser_javascript_evaluations=true`。READ_ONLY 验证受保护副作用、递归敏感键脱敏；
  PAGE_ACTION 验证真实页面变更；同时覆盖 Tenant/Actor 隔离、禁止 `fetch`、源码不落 Evaluation/
  Audit/Outbox 明文、状态过期后最多三次基于权威 Cursor 重试，以及后续 Screenshot、File、
  Challenge、Overview SSE、Recording 和 19 个持久 Workflow 继续通过。

## 剩余边界

实现提交 `2240f75` 的 GitHub `ci` run `32733435238` 第二次尝试已通过 Verify、镜像、
SBOM/扫描、完整 Integration、Object Storage/Recording GameDay 和 Kubernetes Operator E2E；
首轮 Verify 在项目代码执行前因 GitHub API 返回 Unicorn HTML 导致固定 Buf Action 安装失败，
原 run 只重跑失败 Job 后通过。`desktop` run `32733435176` 的 Windows/macOS 原生安全测试与
unsigned build 均通过。

本切片不授权任意浏览器调试、Secret 读取、跨域网络或高风险业务决定。Select/Press、
Drag/Drop/Swipe、通用 Mouse/Keyboard/Touch 和 AgentClipboard/UserClipboard 显式受控 Bridge
仍未完成；目标客户站点 Evaluate 允许表达式模板、CSP/扩展组合和长期稳定性仍属于生产环境 Gate。
