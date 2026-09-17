# DOM / Layout / Network / Focus / Route 组合稳定性闭环

日期：2026-09-17。对应专项清单 A20。

## 结论

A20 的仓库内通用代码项已关闭。页面稳定性不再只依据 `document.readyState` 与 Network quiet，
而由 Browser Node 连续权威采样 DOM、Layout、Focus、Route 四类隐私安全指纹，并与既有 Network
观察共同决定动作准入、动态微批次续行和 Outcome Verification 的 `pageActivity`。

任何一类证据缺失或中断都得到 `UNKNOWN`；任一组件发生变化都重置对应 quiet window。普通页面
动作必须等待全部组件和 Network 至少安静 250ms，动态 Batch 每段仍要求连续两份精确状态；持续
变化超过五秒时保留已完成动作并终止剩余动作，不重放副作用。`WAIT_FOR`、Tab 控制和原生 Dialog
处理保留恢复通道，不会因为页面不稳定而被互锁。

## 实现

- State Collector 只保存并比较 Node 内 SHA-256 指纹：DOM 使用结构化 Target/Opaque Frame，Layout
  使用 Viewport、Document、Target 和 Frame 几何，Focus 使用当前焦点路径与 document focus，Route
  使用 Active Tab 与当前 URL。原始页面文本、Selector、URL 或用户输入不会新增进入公开证据。
- `PageStability` 公开四个最大五分钟的 quiet window 和 `evidenceFresh`。原生 Dialog 阻断、REGION
  Resync、观察中断及 N−1 Node 缺字段时 fail-closed；FULL State 与 Diff 通过 additive Protobuf
  字段传递，PostgreSQL Browser State JSON、OpenAPI 和四语言 SDK 同步投影。
- quiet window 跨过 250ms 动作阈值或 2s Outcome 稳定阈值时进入有界 Content Hash bucket，保证
  控制面能收到新的权威 State Version，同时避免每毫秒制造事件。
- Node 单动作和 Batch 都在输入前执行组合稳定性检查；动态微批边界提供
  `DOM_CHANGING / LAYOUT_CHANGING / FOCUS_CHANGING / ROUTE_CHANGING /
  PAGE_STABILITY_UNKNOWN` 稳定原因码。
- 控制面的 `pageActivity=STABLE` 现在要求 DOM/Layout/Network/Focus/Route 全部新鲜且安静至少
  两秒；独立 Outcome Verifier 原有的稳定状态门禁因此自动升级为组合证据。

## 验证

- Browser Node 定向测试与 Clippy 通过；阈值 bucket、微批准入、变化边界和旧 Node 缺证据均有回归。
- macOS 本机真实 Google Chrome 动态修改 DOM、元素位置、焦点和 `history.pushState`：四个 quiet
  window 分别归零，静止 300ms 后均恢复到至少 250ms；虚拟列表语义围栏回归同时通过。
- Control Plane 全量 `spotlessApply test check` 与 V123 N/N−1 Gate 通过；旧 Node 未提供
  `PageStabilityState` 时页面活动为 `UNKNOWN`，不会误判稳定。
- 完整 PostgreSQL/Redis/MinIO/mTLS/真实 Chromium Integration 退出码为 0，输出
  `page_composite_stability=true`；同时覆盖 19 动作动态微批、独立 Outcome Verifier、Expected
  Outcome、Reviewer、Vision、加密 Profile 往返、双 Node 与 19 个持久 Workflow。
- Web 142 项及生产构建、Desktop test/lint/unsigned build、四语言 SDK、Worker 与 Terraform
  Provider 测试均通过。
- 完整 `make ci` 通过，包含文档漂移、Control Plane、Rust Workspace/Clippy、Web、Worker、
  Provider、契约/SDK、供应链、Operator 17 项、50k Coordinator Capacity 与 N/N−1 Gate。
- 公开契约保持 247 Operations，Schema 从 342 增至 343。

## 剩余生产边界

- 组合稳定性是动作时序与结果验证的保守证据，不证明业务结果；站点领域结果仍由 Expected
  Outcome、独立 Outcome Verifier、Provider Contract 和客户 Replay 负责。
- CSS 动画、视频、Canvas 像素变化若不改变受治理布局/结构，不会被当作可交互 DOM 变化；需要
  像素语义的场景继续走受治理截图、隐私检测或 Human Handoff。
- 目标 Linux/云、真实客户 SPA、长稳采样成本与站点特定阈值仍属于生产 Gate。
