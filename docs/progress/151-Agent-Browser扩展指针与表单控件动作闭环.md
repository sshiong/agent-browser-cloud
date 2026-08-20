# Agent Browser 扩展指针与表单控件动作闭环

> 日期：2026-08-20
> 状态：仓库内实现与本地全量 Gate 已通过；GitHub `ci/desktop` 待提交推送后确认

## 目标与边界

本切片继续收口统一 `execute-actions`，新增六个不需要人工提供信息的普通浏览器动作：

- `DOUBLE_CLICK_TARGET`
- `RIGHT_CLICK_TARGET`
- `HOVER_TARGET`
- `CLEAR_TARGET`
- `CHECK_TARGET`
- `UNCHECK_TARGET`

它们不是新的细粒度顶层 HTTP Tool，而是继续复用现有粗粒度 Agent Browser Gateway、
Agent Task、Exclusive Operation、Reviewer、Capability、Outbox 和 Browser State 围栏。
AUTONOMOUS 下这些普通动作及内部状态重绑保持静默；只有 OTP、设备确认、高风险决定等
确实缺少真人信息，或 Challenge 自动预算真正耗尽时才沿用既有一次性人工协助通知。

本切片没有把尚未完成的 Dialog、Tab、File、局部 Screenshot、受治理 Evaluate、
Select/Press/Drag/Drop/Swipe/通用 Mouse/Keyboard/Touch 和显式 Clipboard Bridge 写成完成。

## 实现

### Control Plane

- `ToolId`、Planner、风险分类、Capability Data Scope、Action Tool 二次校验和 Executor
  统一支持六个新 Primitive；
- 所有动作必须绑定当前 `targetRef/elementId + targetRevision`，并继承 progress 150 的
  每 Primitive 稳定 Element ID 重绑定；
- `CLEAR_TARGET` 只允许 textbox/combobox；`CHECK_TARGET` 只允许 checkbox/radio；
  `UNCHECK_TARGET` 只允许 checkbox，且 checked 状态缺失时 fail-closed；
- 非文本 Target Action 禁止夹带 value、Secret、Data Class、Scroll、Wait 或 Timeout，
  避免无用敏感输入被消费或进入 Plan；
- `CLEAR_TARGET` 归类为 R2 数据变更，其余新增动作归类为 R1 低风险页面交互；高风险
  业务意图的独立确认规则没有改变。

### Browser Node

- `CdpDesktopInput.mouse_click()` 使用真实 Chromium `Input.dispatchMouseEvent` 的显式
  button/clickCount，双击不是两个无语义普通点击，右键也不伪装成左键；
- 原子点击成功后不残留按钮；若 release 失败，Input Ledger 保留 pressed 状态，现有
  watchdog 可继续 fail-safe 释放；
- Hover 只移动指针；Clear 执行聚焦、`Ctrl+A`、Backspace；Check/Uncheck 先读取当前
  `checked`，只在需要时点击，并在动作后重新采集结构化 State 验证最终布尔值；
- 每个 Primitive 仍先等待真人输入优先级结束，再执行、重采集 State、推进确认版本并
  为下一 Primitive 使用最新 Target Revision；`stopOnError` 语义保持不变。

### 契约、SDK 与 Integration Fixture

- OpenAPI 与 TypeScript/Python/Go/Java SDK 生成基线保持 `226 Operations / 301 Schemas`，
  Tool 枚举已同步；
- 未修改 Protobuf tag，只扩展既有受限 `tool_id` 值；N−1 Node 对未知动作安全拒绝，
  不会把未知输入解释成其他动作；
- Integration fake Chromium 现在会根据真实 CDP Input 事件维护公开输入值、焦点、
  Ctrl+A/Backspace 与 checkbox checked 状态，不再用固定返回值伪造动作成功；
- 完整 Integration 通过一个六动作 Batch 验证逐步重绑定、六个成功 Outcome、输入清空和
  checkbox `false -> true -> false` 的最终结构化状态。

## 验证

- Control Plane：457 项通过；
- Rust Workspace：通过；新增 Input Sandbox 原子双击/右键与 Ledger 回归通过；
- Web Console：115 项通过；
- Python Workers、Go Provider：通过；
- `make test`、`make lint`、`make build`、`make test-desktop`：通过；
- OpenAPI/TypeScript SDK/多语言 SDK：226 Operations / 301 Schemas，生成与漂移检查通过；
- `make test-upgrade-compatibility`：通过；
- `make test-integration`：完整 PostgreSQL、Redis、MinIO、mTLS、Chromium CDP、Coordinator
  Failover、N/N−1 Runtime、Challenge、资源治理与六动作 Batch 验证通过。

## 剩余边界

下一切片必须先建立真实活动 Target、输入 Broker 重绑定和原生 Dialog 事件状态，再实现
Tab/Dialog 粗粒度 API；不得继续使用当前 Snapshot 中由单页 URL/DOM dialog 临时推导的
`activeTab/dialogElementIds` 冒充浏览器原生权威状态。随后再收口受治理 Evaluate、截图变体、
文件上传/下载，以及 Select/Press/Drag/Drop/Swipe 和通用 Mouse/Keyboard/Touch。
