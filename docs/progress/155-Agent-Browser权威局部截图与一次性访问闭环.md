# Agent Browser 权威局部截图与一次性访问闭环

> 日期：2026-08-24
> 状态：仓库内实现与本地完整 Gate 已通过；GitHub CI/Desktop 待首次推送验证

## 目标与边界

本切片为 Agent Browser 增加正式、状态围栏的截图能力，覆盖 `VIEWPORT`、`FULL_PAGE`、
`ELEMENT`、`REGION` 和 `CHALLENGE_REGION`。截图不是无约束 CDP 或对象地址透传：Control
Plane 只返回 PostgreSQL 权威元数据；像素经 Browser Node 脱敏后 create-only 提交到对象
存储，只有发起截图的同一 Actor 能使用五分钟、用途绑定、一次性的 Grant 兑换短期下载。

AUTONOMOUS 下的结构化感知、截图和有界失败重试保持静默。只有 OTP、设备确认、高风险决定等
确实需要真人提供的信息缺失，或低风险 Challenge 的自动预算已耗尽时通知一次。操作员可把 OTP
发给 Agent 代填，也可自愿进入 VNC；截图能力不把人工接管变成普通自动化的必经步骤。

## PostgreSQL 权威模型与 API

- V110 创建 `agent_browser_screenshot_requests`，以租户、Session、Actor、幂等键和精确
  `stateVersion/stateHash/targetRevision/activeTabId` 保存请求、执行和终态元数据；数据库不保存
  图片字节、对象 Key、页面 URL 或签名 URL；
- 五种模式使用互斥约束：Element 必须携带稳定 Element ID，Region/Challenge Region 必须携带
  有界矩形，Viewport/Full Page 不得夹带 Element 或 Region；所有尺寸和像素预算均 fail-closed；
- `POST /api/v1/sessions/{sessionId}/agent-browser/screenshots` 要求 `OPERATE` 和幂等键；
  `GET .../{screenshotId}` 只读 PostgreSQL，支持最多 30 秒有界等待且单实例最多 32 个等待者；
  `POST .../{screenshotId}:redeem` 只允许原 Actor 兑换；
- 成功事件事务完成截图投影，并签发预分配的 `AGENT_PERCEPTION` Grant；普通 Observer Evidence
  API 明确拒绝创建该保留用途，兑换查询同时锁定 Tenant、Session、Actor、Purpose、未过期和
  `ISSUED` 状态，成功后变为 `REDEEMED`，第二次兑换拒绝；
- 审计只记录截图 ID、模式、状态围栏、SHA-256、字节数和脱敏摘要，不记录像素、对象路径或 URL。

## Browser Node 捕获与失败恢复

- `CaptureAgentScreenshotCommand` 通过现有 mTLS、Outbox、Node Journal、Coordinator Lease、
  Term/Route Epoch 和命令幂等链路执行；Node 先检查真人输入优先级，再重验当前 Page、精确
  State Hash/Version、Target Revision 和 Active Tab；
- Element 模式只接受当前结构化 State 中可见、在视口内、未遮挡且可交互的稳定 Element，截图
  前在 Node 侧解析真实 bounds；Region 与 Challenge Region 严格区分 viewport/document 坐标；
- Session Recorder 使用活动 Page Target 的 `Page.captureScreenshot`。全页截图先基于文档坐标
  安装整页敏感区域遮罩；任一遮罩安装、复核或清理失败均不允许退回原始像素；
- DPR、页面/视口几何、截图矩形、最大像素数和 8 MiB 内容上限均有界；对象提交使用预分配
  Evidence ID/时间戳和 Storage Helper create-only 写入，重复命令只能得到相同不可变对象；
- Node 成功/失败都通过扩展后的通用 Evidence Event 回传；Control Plane 严格验证截图专用字段，
  普通 Evidence 携带截图元数据或截图缺少围栏时均拒绝。Outbox 死信会把 PostgreSQL 请求置为
  FAILED，不留下永久 EXECUTING。

## 契约、复用与兼容

- OpenAPI 和 TypeScript/Python/Go/Java SDK 同步为 `233 Operations / 310 Schemas`；Web 与
  Tauri 复用同一个 API Client、Actor Header 和类型，不引入 Desktop 分叉；
- Protobuf additive 增加截图命令和 Evidence Event tags 16—28；N/N−1 Gate 锁定 tag、V110
  expand-only 约束和 `agentScreenshot=state-fenced-region-v1` 能力标签；旧 Node 缺能力时
  Control Plane fail-closed，不降级为无围栏 Screenshot；
- `EvidencePurpose.AGENT_PERCEPTION` 仅供截图链路内部使用，旧 Observer capture/redeem 行为保持
  兼容；Purpose 查询使用显式 SQL 分支，避免 PostgreSQL 对可空占位符的类型推断差异。

## 本地验证证据

- `make test`：Control Plane、Rust Workspace、Web 118 项、Application Adapter、Validation、
  GameDay、Agent/Reviewer/Vision Worker 和 Go Provider race 均通过；
- `make lint`：Java check、Rust fmt/Workspace Clippy `-D warnings`、Web ESLint/Prettier 和 Go vet
  通过；Clippy 暴露的 Evidence 枚举大分支已改为 Box 并复验；
- `make build`、OpenAPI/Buf、四 SDK 重新生成零漂移、四 SDK 运行/打包、Desktop test/lint/
  unsigned build、供应链、Operator 17 项、50k Coordinator Capacity 与 V110 N/N−1 均通过；
- 完整 PostgreSQL/Redis/MinIO/mTLS/Chromium Integration 退出 0，输出
  `agent_browser_screenshots=true`，显式验证 Region 捕获、自动状态游标重试、元数据不泄露、
  跨租户/跨 Actor 拒绝、`AGENT_PERCEPTION` 精确用途、下载 SHA-256 以及第二次兑换拒绝；后续
  File、Challenge、Overview SSE、Recording 和 19 个持久 Workflow 也继续通过。

## 剩余边界

受治理 JavaScript Evaluate、Select/Press/Drag/Drop/Swipe、通用 Mouse/Keyboard/Touch 和
AgentClipboard/UserClipboard 显式受控 Bridge 仍未完成。截图只提供脱敏视觉证据，不等于授权
任意页面脚本、读取浏览器下载内容或绕过支付/账号安全确认。GitHub `ci`/`desktop` 必须在本切片
推送后通过，届时才能把远端 Gate 写成完成。
