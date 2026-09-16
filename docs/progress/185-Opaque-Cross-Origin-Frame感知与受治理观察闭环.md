# Opaque Cross-Origin Frame 感知与受治理观察闭环

日期：2026-09-16。对应专项清单 A19。

## 结论

A19 的仓库内通用代码项已关闭。Browser Node 不再静默忽略无法读取的 iframe，而是把它投影为
不可执行的 `OpaqueFrame` 边界。Agent 可以看到稳定 `frameRef`、父 Frame、去路径化 Origin、
外框 Bounds、可见性和边界原因，但看不到 iframe 内部 DOM、完整 URL、Path、Query、Fragment、
Credential 或内容。

Opaque Frame 不会进入 `InteractiveTarget`，也不能传给 CLICK、TYPE、JavaScript Evaluate 或
Challenge 自动坐标动作。唯一自动路径是新的 `OPAQUE_FRAME` 截图模式：控制面从当前新鲜
Browser State 中按 `frameRef` 推导 Region，Node 再以精确 State Version、Target Revision、
Content Hash、Active Tab、`frameRef` 和 Bounds 重验。随后复用既有整页敏感区域遮罩、create-only
Evidence、Purpose-bound 五分钟一次性 Grant；观察之后若需要输入，只能进入 Human Handoff。

## 实现

- State Collector 在同源 iframe 继续递归 DOM/A11y；`contentDocument` 不可用时按
  `CROSS_ORIGIN / SANDBOXED / INACCESSIBLE` 显式记录边界，最多 32 个。
- `frameRef` 由 Active Tab、外层 iframe 稳定 DOM Path、父 Frame 和 Origin 哈希生成。Origin
  只保留 scheme + authority；未知、data/blob 或无法解析的来源为 null。
- Opaque Frame 参与 Content Hash 与 Target Revision。FULL State 提供 fresh evidence；原生
  Dialog 阻断、旧 Node 和 REGION Resync 只保留旧投影并标记 stale，不允许据此截图。
- Protobuf、Control Plane PostgreSQL State JSON、公开 Browser State、Agent Snapshot 和四语言
  SDK 均增加 additive 投影。N−1 Node 缺字段时得到空集合且 freshness=false。
- `OPAQUE_FRAME` 截图请求只接收 `ofr_` 引用，不接收调用方 Region。Control Plane 与 Node 分别
  校验可见、在 Viewport、无遮挡、布局存在和精确 Bounds；任何变化都 fail-closed。
- `interactionStrategy` 固定为 `BOUNDED_VISION_THEN_HUMAN_HANDOFF`。这里的 Vision 指受治理、
  脱敏后的观察，不授予点击或文本输入；支付、登录、Cloudflare 等跨域交互不会被误报为自动化成功。

## 验证

- Control Plane 全量 `spotlessApply test check` 通过；截图服务定向测试覆盖服务端 Region 推导、
  `frameRef` 下发与任意 Region 输入不可用。
- Rust Workspace test 与 Clippy 通过；Node 单测覆盖 freshness/Bounds 精确不匹配 fail-closed。
- macOS 本机真实 Google Chrome 启动两个不同 loopback Origin：父页面加载带 Secret Query 的
  iframe，State 成功投影 `CROSS_ORIGIN`，只公开 Origin，序列化结果不包含 Query Secret。
- Web 142 项与生产构建、OpenAPI/Protobuf、四语言 SDK 测试通过。公开契约保持 247 Operations，
  Schema 从 341 增至 342。

- 完整 `make ci` 通过，包含 Control Plane、Rust Workspace/Clippy、Web 142 项、四类 Python
  Worker、Terraform Provider、供应链、Operator 17 项、四语言 SDK、50k Coordinator Capacity
  与 N/N−1 Gate。
- Desktop test/lint/unsigned build 通过；完整 PostgreSQL/Redis/MinIO/mTLS/Chromium Integration
  退出码为 0，覆盖 130 张公开表、双 Node、19 个持久 Workflow、State freshness、受治理截图、
  Outcome Verification、动态微批、快速取消、加密 Profile Archive 与恢复链。

## 剩余生产边界

- 浏览器安全模型决定跨域 iframe 内部 DOM 不可由父 Page 结构化读取；本实现没有也不会绕过
  Same-Origin Policy、支付提供方、IdP 或 Cloudflare 安全机制。
- 真实第三方 IdP/支付/托管 Challenge 的 Human Handoff 体验、客户授权 Replay、目标模型准入和
  合规审批仍属于生产 Gate。
- 非文本图像语义分类与 Recording 全帧隐私治理继续按既有清单跟踪，不因本切片自动关闭。
