# Agent Browser 结构化感知与低延迟自动执行基础闭环

> 日期：2026-08-20
> 状态：仓库实现、完整本地 Gate 与 GitHub `ci/desktop` 均已通过

## 1. 本轮边界

本轮不重做既有 Agent、VNC、Challenge、AUTO Resource 和 Enterprise Overview 架构，
而是在现有 Browser State、Operation、Capability、Challenge Vision Worker、Input
Broker 和 PostgreSQL 权威模型之上，收拢 Agent Browser 的高频感知与执行路径。

自动模式继续遵守一个明确边界：普通页面识别、点击、填写、滚动、等待、失败重试和低风险
Challenge 自动尝试都由 Agent 静默完成，不逐动作请求操作员授权。只有 OTP、设备确认、
支付/账号安全决定、用户主观选择等必须由真人提供的信息，或低风险 Challenge 的自动预算
真正耗尽后，才产生一次人工协助通知。该通知不创建或强制 Human Takeover；操作员既可发送
OTP 让 Agent 在原 Task 中有界代填，也可自愿通过 VNC 协作填写。

## 2. 结构化 Browser Perception

- `snapshot`、`inspect`、`find` 三个粗粒度 API 复用 PostgreSQL 中最近一次 Node 权威
  Browser State，不为一次推理拆出大量细粒度 RPC。
- Snapshot 返回 URL、Title、精确 `stateVersion:targetRevision:stateHash` Cursor、活动页、
  可见文本摘要、交互元素、焦点、表单/Dialog 线索、页面加载状态、Viewport 和是否建议视觉
  fallback；不把 Screenshot/OCR 作为普通页面默认入口。
- Browser Node 同时采集 DOM、A11y、Computed Style、布局、焦点和 Form State，递归处理
  open Shadow Root 与同源 iframe，并给每个目标维护稳定 `elementId` 和版本化 `targetRef`。
- 可操作性会排除祖先隐藏、`display:none`、`visibility:hidden`、`opacity:0`、
  `aria-hidden`、`hidden`、disabled、零尺寸、离屏、`pointer-events:none`、折叠和中心点遮挡。
  Node 执行前仍再次检查 Target Revision、Viewport 与 Occlusion，避免只因 DOM 存在就点击。

## 3. 统一有序 Action 与 Fast Path

- `EXECUTE_ACTIONS` 将最多 20 个明确动作持久化为一个 Step；当前已支持
  `CLICK_TARGET`、`TYPE_TEXT`、`FILL`、`PASTE_AGENT_CLIPBOARD`、`SCROLL`、`WAIT_FOR`。
- 每个子动作独立绑定 `actionId`、目标版本和必要的密文 AAD；Node 严格按序执行，每步后读取
  真实状态并返回结构化 Outcome，支持 `stopOnError`。VNC 真人真实输入始终优先，停止输入后
  继续同一 Batch，而不是强制接管或重建 Task。
- `POST /agent-browser/execute-actions` 提供一次 Gateway Fast Path：一次校验 State Cursor，
  创建一个有审计、有 Capability 的持久 Batch，并走原有 Reviewer/Worker/Operation。SAFE
  确认和高风险策略仍然权威；Fast Path 不会绕过确认。
- `FILL` 与 `TYPE_TEXT` 已明确区分：`FILL` 覆盖字段；`TYPE_TEXT` 保留追加语义，敏感字段的
  每次重试则先覆盖清空，防止密码或 OTP 重复追加。自动敏感输入沿用默认三次、1—10 可调
  预算；`FILL` 也可在导航后的已授权敏感输入计划中直接续行。
- 错误结果增加机器码映射：State stale、元素不存在/不可见/不可交互/被遮挡、权限拒绝、等待
  失败、动作超时和 CDP 失败无需 Agent 解析自然语言。

## 4. Challenge Human-like Interaction

- 保留默认三次、Session 可调的低风险 Challenge 策略及 Purpose-bound 脱敏截图 Vision/OCR。
- Node 以配置有界的三次 Bezier 鼠标轨迹、速度/延迟和微小偏移执行点击、连续点击与滑动；
  每个动作前检查真人输入优先级，每个动作后读取真实 Browser State，状态变化后不盲目继续
  旧坐标。
- V106 以 expand-only 迁移保存 Human-like Motion Policy，并保持 N−1 缺字段时的安全默认值。

## 5. Session Identity 与双剪贴板

- V107 将 UA、时区、Locale/Language、WebRTC、DNS、Viewport/Screen/Scale、Fingerprint、
  Browser/OS Profile 固化为创建时 Session Identity Spec；首次启动和 Crash Recovery 都从
  PostgreSQL 权威值重新下发，Node 将其应用到 Chromium/Xvfb 启动参数。
- 创建后直接修改明确返回 `SESSION_CONFIG_LOCKED`；只有 Change Request 经管理权限批准，
  且 Session 位于 `CREATED/HIBERNATED` 安全边界时才可应用。Proxy 继续沿用既有创建时
  Binding 和 Safe Point Rebind 审批模型，没有新增无审批 `change_proxy()`。
- V108 建立独立加密 `AgentClipboard`。Agent 的 read/write/paste 永远只访问该租户/Session
  绑定的 PostgreSQL 投影；VNC/X11 `UserClipboard` 保持独立，不存在默认共享或隐式读取。
  通用 AgentClipboard 不得粘贴到敏感 Target，密码/OTP 必须继续使用 Purpose-bound 一次性
  Secret。

## 6. 契约与兼容性

- Protobuf 仅追加结构化 Target、Batch Outcome、Human-like Policy 和 Session Identity 字段；
  V106—V108 均为 expand-only。
- OpenAPI 当前生成基线为 **226 Operations / 301 Schemas**；Web 与 Tauri 共用同一 API、
  类型和行为；TypeScript/Python/Go/Java SDK 全部由契约生成。
- N/N−1 Gate 覆盖新迁移顺序、Identity additive tags 和旧 Node 缺字段安全默认值。

## 7. 已验证与保留边界

本地已通过：Control Plane 456 项、Rust Workspace（含 State Collector 21 项通过/1 项需
真实 Chrome 环境的显式忽略）、Web 115 项、四类 Python Worker、Go Provider、完整
`make test/lint/build`、OpenAPI/Protobuf、四 SDK 生成/漂移/消费/发布包、Desktop、供应链、
Operator、50k Coordinator Capacity 和 N/N−1 Gate。完整 PostgreSQL 17 + Redis + mTLS +
真实 Chromium Integration 输出 `challenge_visual_automation=true`、
`coordinator_agent_side_effect_once=true`、`public_tables=121`、`audit_chain_valid=true`。
同一轮真实 Integration 还显式验证了 Identity 创建值、锁定冲突、审批后安全态应用与版本
前进；AgentClipboard 的 Viewer 拒绝、Operator 写入/读取/清除、响应不回显明文；以及
Snapshot Cursor、稳定 Element ID、Inspect/Find 在当前真实页面上的一致性。

Integration 在收口过程中真实发现并修复三项仅靠 Mock/编译不会暴露的问题：JPA Session
父行必须在 JDBC Identity 投影前 flush；V106 Motion 字段必须对旧请求保持 additive 默认值；
非 Batch Action 不得因新增 `actions` 字段被 Bean Validation 错误拒绝。Integration 的关键
Takeover 请求也增加 20 秒硬超时，基础设施异常不再无限阻塞 Gate。实现提交 `a14e5f1` 的
GitHub `ci` run `32363001442`（含供应链、Integration、Object Storage/Recording GameDay、
Kubernetes Operator E2E）和 `desktop` run `32363001455`（Windows/macOS）均已通过。

本轮尚未把 Dialog、Tab、File、局部 Screenshot、受治理 JS Evaluate、完整
double/right-click/hover/select/check/drag/drop/高级键鼠 Primitive 收敛进新的粗粒度 Agent
Browser API。这些已有部分底层 CDP/Session 能力，但 Agent Gateway 契约仍需后续切片闭环；
不得因本轮完成 Snapshot 和首批 Batch Action 就宣称全部 Browser Tool 已完成。
