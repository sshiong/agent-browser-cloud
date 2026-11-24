# Agent 自主操作顺滑度与可靠性修复

2026-11-24

围绕「以 Agent 自主操作浏览器为主，真人仅在显式主动接管/切换协作控制时介入」的核心目标，对 Browser Node、Control Plane、可选 MCP Gateway 及 Web/Desktop 直连控制台执行端到端排查与修复。

## 发现的问题与修复

1. **Browser Node CDP 输入通道每次事件重建 WebSocket（延迟与抖动）**
   - `apps/browser-node/crates/input-sandbox/src/lib.rs` 原实现在每次 `mouse_move` / `mouse_down` / `mouse_up` / `key_down` / `key_up` / `insert_text` 时均重新建立并销毁一次 CDP WebSocket 连接（单次点击 3 次握手、填充 8 次、拖动 11 次）。
   - 修复：`CdpDesktopInput` 复用持久 CDP WebSocket 连接并维护单调递增请求 ID，在 `rebind()` 切换活动标签页或传输层关闭时自动清理并单次重连；同时保持对单次连接测试夹具的透明兼容。
2. **Browser Node 长按手势（`DRAG_TARGET` 同点长按）易被 5 秒空闲看门狗误释放，且未校验终点坐标范围**
   - `apps/browser-node/crates/node-agent/src/main.rs` 中 `DRAG_TARGET` 支持最长 5000ms 长按，但 `start == end` 时直接整段 `sleep` 未刷新输入活跃时间，易在 5 秒阈值处被监控循环 `release_if_idle(5s)` 提前释放鼠标；同时终点中心坐标缺少有限性与正值范围检查。
   - 修复：新增 `CdpDesktopInput::keep_alive()`，长按按 ≤250ms 切片睡眠并刷新活跃时间，同时补齐终点坐标范围校验。
3. **Browser Node 输入异常/重试时可能残留按下键（`Control` / `PRESS_KEY`）**
   - `CLEAR_TARGET` / `FILL` / `TYPE_TEXT` / `PASTE_AGENT_CLIPBOARD` / `SELECT_OPTION` 重试循环与 `PRESS_KEY` 在中段报错时未调用 `input.release_all()`，可能残留 `Control` 修饰键导致后续点击变成 `Ctrl+Click`。
   - 修复：上述路径失败时统一先 `release_all()` 清理按下状态，再重试或返回错误。
4. **Control Plane Outbox 派发将长动作 `COMMAND_IN_PROGRESS` 误判为失败并指数退避**
   - `apps/control-plane/src/main/java/io/browsercloud/infrastructure/NodeCommandOutboxDispatcher.java` 单次 gRPC 超时为 5 秒；超过 5 秒的长动作（真实页面导航、6–10 秒 `WAIT_FOR`、5 秒长按或批处理）在重试时由 Node 返回 `COMMAND_IN_PROGRESS`，原逻辑将其计入失败次数、触发 2s→60s 指数退避，10 次后甚至进入死信队列。
   - 修复：新增 `recordInFlightDeferral()`，对 `COMMAND_IN_PROGRESS` 不消耗 `publishAttempts`、不写 `HUMAN_INPUT_PRIORITY` 等待态，每 500ms 复查直到 Node 落盘返回。
5. **Control Plane 直连模式拒绝后残留孤立 `PLANNED` 任务，且 `about:blank` 无法直接 `OPEN_TAB`、感知错误码被吞**
   - `AgentBrowserActionApplicationService.createAndExecuteDirect()` 原先在独立事务中提交 `tasks.create()` 后才执行 `reviewer.requireDirectEligible()`，校验拒绝后在 PostgreSQL 留下孤立 `PLANNED` 任务；`allowedDomains()` 对 `about:blank` 直接抛 `STATE_URL_INVALID`；`AgentConnectionAccessController` 缺少 `PerceptionException` 处理器。
   - 修复：将 `tasks.create()` 与 `reviewer.requireDirectEligible()` 合并到同一事务（拒绝自动回滚，执行派发仍在事务外）；`allowedDomains()` 允许在当前页为 `about:blank` 且动作显式提供 `OPEN_TAB` 域名时正常规划；`AgentConnectionAccessController` 直接返回精确感知错误码（如 `BROWSER_STATE_STALE`、`BROWSER_STATE_UNAVAILABLE`）。
6. **MCP Gateway 缺少任务有界等待工具且透传错误原因不完整**
   - 外部 AI 调用 `browser_navigate` / `browser_act` 后需反复手工轮询 `browser_get_action_task`，浪费轮次；错误解析仅取 `code` 忽略 `details.reason`；`browser_act` 说明只列出 4 个动作。
   - 修复：新增第 14 个工具 `browser_wait_action_task(environment_id, task_id, timeout_seconds=10)`（1—25 秒有界等待、250ms 轮询、每次重验鉴权、超时不取消任务）；透传安全大写的 `details.reason`；补全 `browser_act` 与 README 中已支持的低风险原语说明。
7. **Web/Desktop 直连控制台状态刷新、目标重绑定、动作覆盖与 VNC 默认只读兜底**
   - `NoVncViewport` 组件默认 `viewOnly` 从 `false` 收紧为 `true`，确保任何页面挂载远程桌面时默认只读观察，绝不因误触或光标移动打断 Agent，仅在显式开启协作控制后允许人工输入。
   - `useDirectBrowserAction` 提交成功后自动刷新并失效当前 Session 的 `browserState` 缓存，选中任务进入终态时同步刷新；`rebindActionsToState` 优先按稳定 `elementId` 重绑定并在直连模式校验 `isDirectTarget`；同步 URL `searchParams` 变更；完善错误码中文提示；直连模式新增已支持的低风险动作：`DOUBLE_CLICK_TARGET`、`RIGHT_CLICK_TARGET`、`HOVER_TARGET`、`PRESS_KEY` 与 `WAIT_FOR`。

## 验证结果

- Rust：`cargo test -p input-sandbox -p node-agent`（`input-sandbox` 10/10 通过，含持久 WebSocket 复用与 `keep_alive` 回归；`node-agent` 35/35 通过、1 项需 `REAL_CHROMIUM_PATH` 忽略）、`cargo test -p remote-desktop-gateway`、`cargo fmt --all --check`、`cargo clippy -p input-sandbox -p node-agent --all-targets -- -D warnings` 均通过。
- Java：`./gradlew -p apps/control-plane spotlessApply check` 全量单测（含新增 `COMMAND_IN_PROGRESS` 派发与 `about:blank` `OPEN_TAB` 回归）与 Spotless 通过。
- MCP Gateway：官方 HTTP 客户端协议测试 3/3 通过（含 14 工具清单、`browser_wait_action_task` 终态/等待/超时/校验与 `details.reason` 透传）。
- Web Console：`pnpm --dir apps/web-console lint`（0 warning）、`format:check`、`test`（31 文件 153 项 Vitest + 5 项 noVNC fence 检查）与 `build` 通过；`tests/e2e/direct_browser_mode_ui.py` 与 `tests/integration/mcp-environment-lifecycle.py` 已同步更新并通过 `py_compile`。
- OpenAPI（271 Operations / 368 Schemas）与数据库迁移（V141）保持不变。

## 仍待完成的边界

- 外部 AI 直连模式仍仅直接派发 `R0/R1` 确定性低风险动作；涉及 `R2+` 表单写数据（如 `TYPE_TEXT` / `FILL`）、凭据/OTP 及高风险决策仍保持既有的 Reviewer / AUTONOMOUS 一次性密文 / 人工确认边界。
- 持续变化页面的 `BestEffortStableState/unstableRegions` 动作与独立 Outcome 闭环、真实多站点长稳回放、以及 Oracle ARM 实机新版本验收仍待独立推进。
