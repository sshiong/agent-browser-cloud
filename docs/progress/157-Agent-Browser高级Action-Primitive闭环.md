# Agent Browser 高级 Action Primitive 闭环

> 日期：2026-08-25
> 状态：仓库内实现、完整本地 Gate 与 GitHub CI/Desktop Gate 均已通过

## 目标与自动模式边界

本切片在既有 `snapshot/inspect/find/execute-actions`、稳定 Element ID、精确 State Cursor、
持久 Agent Task/Operation/Outbox/Node Journal 和真人输入优先机制上，补齐
SELECT_OPTION、PRESS_KEY、DRAG_TARGET、DROP_TARGET、SWIPE_TARGET，以及受目标约束的通用
Mouse/Keyboard/Touch Primitive。它们不是任意坐标或任意 CDP 入口，仍由 Control Plane 与
Browser Node 双层校验租户、Session、Capability、State/Target Revision 和活动 Page。

AUTONOMOUS 下普通动作、登录和可恢复失败继续静默重试。只有 OTP、设备确认或高风险决定等
确实需要真人提供的信息缺失，或低风险 Challenge 的自动预算耗尽时才通知一次；操作员可把
验证码发送给 Agent 代填，也可自愿进入 VNC 自己填写，系统不会强制人工接管。

## 契约与权威执行

- Tool ID 新增 `PRESS_KEY`、`SELECT_OPTION`、`DRAG_TARGET`、`DROP_TARGET`、`SWIPE_TARGET`、
  `MOUSE_MOVE/DOWN/UP/WHEEL`、`KEY_DOWN/UP` 和 `TOUCH_START/MOVE/END`；
- 公共 Action 输入以 additive 字段增加 `endTargetRef`、`key`、`button`、`deltaX/deltaY`、
  `durationMs`，内部命令另携带稳定 `endElementId`；OpenAPI/四 SDK 保持
  `235 Operations / 313 Schemas`；
- Protobuf `AgentActionCommand` additive 使用 tags 21—26，`AgentActionPrimitive` 使用
  tags 16—22；N/N−1 Gate 固定新字段并保留旧命令语义；
- PostgreSQL 继续以现有 Agent Task、Plan、Operation 与 Outbox/Inbox 作为权威账本，无需新增
  数据库表或迁移；SELECT_OPTION 的 option value 使用现有 AES-GCM 动作载荷，只在 Node 派发
  前解封，不进入 Plan、普通 Audit 或 Node Journal 明文；
- 一个 Batch 最多仍为 20 个 Primitive。完整 20 动作计划另需固定 Start/Review/Finish 三步，
  因此 INTERACTIVE 计划上限由 20 兼容扩为 23；回归测试锁定该预算，避免把内部计划不足误报为
  人工阻塞。

## Browser Node 安全执行

- Select 在精确 combobox Target 上点击、输入受保护 option text、按 Enter，并重采真实 State；
- Press/KeyDown/KeyUp 只接受修饰键、Enter/Tab/Escape/Backspace/Delete、方向键或单个可打印
  字符，不开放任意 Chromium key definition；
- Drag 同时绑定源与目标稳定 Element ID，每步按最新 Target Revision 重新解析；拖动中失败会
  释放按键，Drop 必须建立在已有左键按下状态上；
- 通用 Mouse 和 Touch 均只能使用结构化 Target 的中心点或目标间路径，不接受裸屏幕坐标；
  MouseMove 保留已按下 buttons，Touch ledger 与输入 watchdog/release-all 一起清理；
- Swipe 以有界 duration 生成 touchStart/move/end，并在失败时发送 touchEnd；真人实际输入到达
  时仍优先两秒，之后续行同一 Operation，不创建 Human Takeover 或人工通知。

## 验证证据

- `make test`：Control Plane 482 项、Rust Workspace、Web 119 项、Application Adapter、
  Validation/GameDay/Agent/Reviewer/Vision Worker 和 Go Provider race 均通过；新增
  20 Primitive + 3 固定步骤的计划预算回归；
- `make lint` 与 `make build`：Java Check、Rust fmt/Clippy `-D warnings`、Web ESLint/
  Prettier/Production Build、Python compile 和 Go vet/build 均通过；
- Desktop 原生 2 项测试、fmt/check 和 unsigned release build 通过；
- Web 25 个文件/119 项测试、lint、TypeScript/Production Build 通过；
- OpenAPI/Buf 和四 SDK 生成、运行、打包通过，基线仍为 235 Operations / 313 Schemas；
  供应链、Operator 17 项、N/N−1 Gate 和 50k Coordinator Capacity 均通过；
- 完整 PostgreSQL/Redis/MinIO/mTLS/Chromium Integration 退出 0，单一 Batch 真实执行并验证
  19 个 Primitive：既有 Hover/Double/Right/Clear/Check/Uncheck，以及本切片 Select、Press、
  Drag、Mouse、Keyboard、Touch、Swipe 全部成功，最终 combobox 值为 `beta`，并继续通过
  Dialog、File、Screenshot、Evaluate、Challenge、Overview SSE、Recording 与 19 个持久
  Workflow；最终输出 `agent_browser_advanced_actions=true`，审计链有效且累计 312 项事件。

第一次 Integration 在任务创建阶段发现 `MAX_ACTIONS_TOO_SMALL`：19 个 Primitive 加固定三步
超过旧上限 20。该失败没有进入 Node，也不是验证码或人工授权问题；修复计划上限和回归测试后，
第二次完整 Integration 通过。

## 剩余边界

实现提交 `18c2186` 的 GitHub `ci` run `32827778113` 已通过 Verify、镜像、SBOM/扫描、完整
Integration、Object Storage/Recording GameDay 和 Kubernetes Operator E2E；`desktop` run
`32827778135` 的 Windows/macOS 原生安全测试与 unsigned build 均通过。Workflow 仅有 GitHub
对固定 Action 的 Node 20 弃用维护提示，Runner 已用 Node 24 正常执行，不影响本次 Gate。

Agent Browser 高级 Action Primitive 已从当前未实现清单移除。仍缺
AgentClipboard/UserClipboard 显式、受控、可审计 Bridge；底层两套剪贴板独立存在不等于允许
Agent 任意读取或写入真人剪贴板。目标客户站点的复杂控件组合与长期稳定性仍属于生产环境 Gate。
