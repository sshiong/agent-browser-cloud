# AgentClipboard 与 UserClipboard 显式受控 Bridge 闭环

> 日期：2026-09-02
> 功能提交：`372aee5 feat: add governed clipboard bridge`
> 状态：本地全量 Gate 与 GitHub `ci` / `desktop` 已通过

## 1. 目标与行为边界

本切片关闭 V108 只完成双剪贴板隔离、但尚无显式跨边界契约的缺口。Bridge 不是 Agent
普通执行路径，也不是敏感输入的替代品：

- `USER_TO_AGENT` 只在操作员显式点击后，将当前 noVNC 连接最近两分钟内真实收到的
  `ServerCutText` 写入 AES-GCM 加密的 AgentClipboard；
- `AGENT_TO_USER` 只在操作员显式点击、当前连接允许控制时，短暂返回当前 Actor 可读的
  AgentClipboard，再由同一 noVNC 连接调用 `clipboardPasteFrom` 写入 UserClipboard；
- 账号、密码和 OTP 继续使用 V104/V105 的用途绑定一次性敏感输入 API，不能借通用
  Clipboard Bridge 绕过 Secret、Target、State、Capability 或高风险确认边界；
- AUTONOMOUS 下普通点击、输入、滑动、连续动作和默认三次可调的失败重试保持静默。
  只有 OTP、设备确认、高风险决定等必须由人提供的信息缺失，或低风险 Challenge 自动预算
  确实耗尽时才通知一次。操作员可以把 OTP 发给 Agent 代填，也可以自愿进入 VNC 自己填，
  系统不会强迫人工接管。

## 2. PostgreSQL 权威账本与租户/RBAC

V112 新增 `agent_clipboard_bridges`：

- 主键和唯一幂等键绑定 Tenant、Session、Actor；外键同时绑定 Session 与 Tenant；
- 固定记录 Direction、Purpose、Connection ID、Context Epoch、Source Clipboard Version、
  内容长度、HMAC 指纹、状态、签发/完成/过期时间；
- 状态仅允许 `ISSUED/COMPLETED/EXPIRED`，文本长度上限 2000；
- 表中没有 `value`、`sealed_value`、对象路径或第二份密文列，Bridge 不复制剪贴板正文；
- 请求幂等指纹使用服务端 HMAC，不保存可枚举的低熵文本 SHA；
- 创建、完成和过期都写最小化 Audit，只记录方向、用途、连接、版本、哈希、长度和
  `plaintextPersisted=false`。

Control Plane 在事务中锁定 Session，并重验：

1. Session 属于当前 Tenant 且处于 `RUNNING/DEGRADED`；
2. 当前 Actor 是该 Connection 的在线参与者，Context Epoch 与 Session 一致；
3. `AGENT_TO_USER` 必须是非只读连接；`USER_TO_AGENT` 必须携带最多两分钟前的真实
   noVNC Clipboard observation；
4. AgentClipboard 的期望版本、Bridge 状态、过期时间和完成幂等指纹都一致；
5. 跨租户、跨 Actor、连接重连后的旧 Epoch、过期 Observation、版本冲突和重复消费均
   fail-closed。

## 3. Web/Tauri 共用交互

Web 与 Tauri 继续复用同一个 `RemoteDesktopPage`、Session API Client 和 Query 层：

- `NoVncViewport` 通过 ref 暴露当前连接的 `writeUserClipboard()`，并监听 noVNC
  `clipboard` 事件保存短期 observation；断线立即清空；
- AgentClipboard 页面查询改成 `includeValue=false`，常规渲染只取元数据，不为显示页面而
  解密正文；
- 页面提供两个明确按钮并展示成功/失败状态：UserClipboard → AgentClipboard、
  AgentClipboard → UserClipboard；
- UI 明确说明密码/OTP 走一次性输入 API，Bridge 需要操作员主动触发，但远程桌面接管始终
  是可选协作能力。

## 4. 正式契约与兼容性

OpenAPI 新增：

- `POST /api/v1/sessions/{sessionId}/agent-browser/clipboard-bridges`；
- `POST /api/v1/sessions/{sessionId}/agent-browser/clipboard-bridges/{bridgeId}:complete`；
- AgentClipboard GET 的 `includeValue` 元数据读取参数；
- `CreateAgentClipboardBridgeRequest`、`CompleteAgentClipboardBridgeRequest`、
  `AgentClipboardBridge` 三个 Schema。

TypeScript、Python、Go、Java SDK 和 Manifest 已确定性重生成，公开基线更新为
**237 Operations / 316 Schemas**。V112 为 expand-only，N/N−1 Gate 显式检查内容不落库、
约束、索引和旧版本可忽略新表。

## 5. 可重复验证证据

本地已通过：

- Control Plane 484 项测试；
- Rust Workspace fmt、Clippy `-D warnings` 与全部测试；
- Web lint、format、120 项测试与生产构建；
- Application/Validation/GameDay/Agent/Reviewer/Vision Worker、Terraform Provider；
- OpenAPI/Buf、四 SDK 生成漂移、消费、打包与统一发布包；
- Supply Chain、Operator 17 项、50k Coordinator Capacity、V112 N/N−1；
- Desktop test、fmt/check 与 Tauri unsigned build；
- 完整 PostgreSQL/Redis/MinIO/mTLS/真实 Chromium Integration。

最终 Integration 明确输出 `agent_clipboard_bridge=true`，并验证：

- 只读连接执行 `AGENT_TO_USER` 返回 409；
- `AGENT_TO_USER` 只向当前 Actor 的当前连接返回一次短期内容，完成后账本为
  `COMPLETED`；
- `USER_TO_AGENT` 以当前 observation 写入新的 AgentClipboard Version，API 响应不回显
  observation 正文；
- Bridge request hash 不等于内容的直接哈希，账本不存在内容列；
- 跨租户访问返回 403；
- 原有 Challenge、Dialog、Evaluate、Screenshot、File 和 19 Primitive Batch 均保持通过。

功能提交 `372aee5` 的 GitHub `ci` run `33533657239` 已通过 Verify、供应链、完整
Integration、Object Storage/Recording GameDay 与 Kubernetes Operator E2E；`desktop`
run `33533657129` 的 Windows/macOS 均通过。GitHub 对固定 Action 的 Node 20→24 平台迁移
给出弃用提示，但没有测试或构建失败。

## 6. 保留边界与下一任务

- Bridge 是显式、用途绑定、连接绑定的操作员协作能力，不应被 Agent Planner 自动调用；
- 目标 Linux 8 Client 弱网/长稳、跨 Region RFB 和真实企业 IdP 仍属于生产环境 Gate；
- 下一仓库级 P1 是 Recording purpose-bound 一次性播放 Grant、目标 Bucket Object
  Lock/WORM 与到期删除 Worker。PostgreSQL Retention/Legal Hold 投影不能冒充对象已经
  物理删除。
