# Agent Browser 原生 Dialog 权威状态与粗粒度处理闭环

> 日期：2026-08-23
> 状态：仓库内实现与本地完整 Gate 已通过，GitHub Gate 待提交后验证

## 目标与边界

本切片补齐浏览器原生 JavaScript `alert/confirm/prompt/beforeunload`，不再把 DOM/A11y
`role=dialog` 当成浏览器原生 Dialog。Agent 仍只看到粗粒度 Snapshot 与
`execute-actions`：

- `ACCEPT_DIALOG`
- `DISMISS_DIALOG`
- `ACCEPT_DIALOG + value/secretId`（仅 `PROMPT`）

浏览器权限弹窗、DOM Modal 和原生 JavaScript Dialog 保持三条独立边界。普通低风险 Dialog
动作在 AUTONOMOUS 下静默执行；只有 OTP、设备确认、支付/账号安全决定等确实缺少真人信息
或授权时才沿用既有一次通知规则。操作员可把 OTP 发给 Agent 代填，也可自愿进入 VNC，系统
不把人工接管设为必经步骤。

## 权威状态与恢复

- Browser Node 在独立回环 CDP Browser WebSocket 上使用 `Target.setAutoAttach + Page.enable`
  持续接收 `Page.javascriptDialogOpening/Closed`，覆盖当前及后续 Page Target；
- 投影包含有界 `dialogId/tabId/dialogType/message/defaultPrompt/hasBrowserHandler`，并与 DOM
  `dialogElementIds` 明确分离；Dialog ID、Tab 绑定、类型、数量和文本边界在 Node 与 Control
  Plane 两层 fail-closed；
- 观察连接重建后，成功的无副作用 `Runtime.evaluate("void 0")` 才能证明目标没有阻塞 Dialog；
  若 Dialog 仍打开，Runtime Probe 不会成功，观察器不会伪造“已关闭”；
- 全量与 Diff State 均以 additive Protobuf 承载 `native_dialogs` 和
  `native_dialog_evidence_fresh`。PostgreSQL Browser State 保存最后投影；观察中断时保留
  已知 Dialog、撤销 freshness 并把已知阻断状态降为 `DEGRADED`，禁止用旧状态继续动作；
- 原生 Dialog 阻塞页面 Runtime 时，State Collector 复用上一份真实结构化页面投影，只推进
  Dialog 生命周期和 State Version，不用失败的 DOM 抓取覆盖权威状态，也不轮换仍有效的
  Element Target Revision；首次即被 Dialog 阻塞时明确为 `DEGRADED`，但允许精确 Dialog
  恢复动作。

## 动作、安全与审计

- Action Executor 使用 `Page.handleJavaScriptDialog` 处理精确 `dialogId`；CDP ACK 后重新采集
  真实状态，Control Plane 只有在 freshness 为真且该 Dialog 已消失时才提交 Step；
- Prompt 文本复用一次性 Secret、AES-GCM Step AAD、Capability 单次消费和 Outbox 最后时刻
  解封。Plan、Task API、Reviewer、审计和结果均不含明文；非 Prompt、Dismiss 或不匹配的
  Dialog 不得携带文本；
- `dialogId` 进入 Capability Data Scope、Reviewer 最小化 Hash、Operation/Outbox/Node
  Journal 与统一 Action Outcome；稳定错误包括 `DIALOG_STATE_STALE`、`DIALOG_NOT_FOUND`、
  `DIALOG_PROMPT_INVALID`；
- VNC 真人实际输入继续拥有两秒优先级，同一 Batch 等待后续行，不创建或强迫 Human
  Takeover；支付、转账、购买、修改密码等高风险意图仍由既有 Intent/Confirmation Gate
  决定，原生 Confirm 不绕过它。

## 契约与验证

- OpenAPI、Web/Tauri 共用类型及 TypeScript/Python/Go/Java SDK 已同步；公开基线为
  `226 Operations / 302 Schemas`；
- N/N−1 Gate 显式锁定 Agent Action Dialog tag、`BrowserNativeDialogState` 及 Full/Diff
  freshness tag，旧 Node 缺少字段时保持 fail-closed；
- Fake Chromium 真实维护 Dialog 生命周期、四种类型、Prompt 文本结果和 Runtime 阻塞语义；
  Integration 逐一执行 Alert Accept、Confirm Dismiss、Prompt Accept、BeforeUnload Dismiss，
  每次都先读取权威 Dialog、再处理并确认权威空状态，输出
  `native_dialog_lifecycle=true`；
- 本地 `make test` 通过：Control Plane 466 项、Rust Workspace、Web 115 项、Python Worker
  与 Go Provider 全部通过；
- `make lint`、`make build`、Desktop Test/Lint/无签名 Build、OpenAPI、四 SDK 生成/验证、
  N/N−1 与完整 PostgreSQL/Redis/MinIO/mTLS/Chromium Integration 均通过；完整 Integration
  还保持 `durable_workflows=19`、Profile 精确集合及既有恢复/安全断言。

## 剩余边界

下一切片继续 File Upload/Download 粗粒度 API；之后是局部 Screenshot、受治理 Evaluate、
Select/Press/Drag/Drop/Swipe、通用 Mouse/Keyboard/Touch，以及显式 Clipboard Bridge。权限
弹窗仍应单独建模，不得混入本切片的 JavaScript Dialog。
