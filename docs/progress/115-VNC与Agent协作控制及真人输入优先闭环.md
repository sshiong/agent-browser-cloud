# VNC 与 Agent 协作控制及真人输入优先闭环

> 日期：2026-08-10
> 状态：代码、全量单元/静态检查和完整托管 E2E 完成；目标 Linux 竞争长稳待执行。

## 问题与目标

此前 Session 详情点击“人工接管”会先创建独占 `HUMAN_TAKEOVER` Operation，再进入
noVNC。Coordinator 的排他 Operation 语义会抢占正在执行的 Agent，因此即使用户只是
连接 VNC 观察 Agent 行为，也会让 Agent 会话停止。

本轮将两种意图分开：

- 普通远程桌面是协作观察/辅助通道，Agent 与真人可以同时存在；
- 真人实际键鼠输入优先，Agent 写命令等待真人空闲后自动继续；
- 显式 HumanTakeover 仍是 Agent→Human Handoff 或高风险独占控制，不改变其安全屏障。

## 已完成

### Control Plane 与契约

- `POST /api/v1/sessions/{id}:desktop-connection` 不再读取或要求活跃
  HumanTakeover Operation，只允许 `RUNNING/DEGRADED` Session；
- 短期单次票据绑定 Tenant、Session、Actor、Coordinator Term、Context Epoch、Nonce
  和 `accessMode=COLLABORATIVE`；
- 为保持旧 Web/Node 线协议兼容，响应和 Claim 中的 `operationEpoch` 暂时承载当前
  Context Epoch，并在 OpenAPI 中明确说明；
- 普通连接不会调用 Coordinator acquire/preempt/abort，不改变现有 Agent Operation。

### Browser Node 真人优先仲裁

- Gateway 对 RFB Client 流做有界增量解析，区分协议协商、像素格式、编码、帧请求与
  KeyEvent、PointerEvent、ClientCutText；
- 仅真实真人输入写入最近输入时间，持续观看画面和 noVNC 帧请求不会阻塞 Agent；
- 真人输入后的两秒窗口内，Agent Navigate/Click/Type/Scroll 返回可重试
  `HUMAN_INPUT_PRIORITY`，`WAIT_FOR` 只读观察仍可执行；
- Node 不持久化这类动态拒绝，因此同一 Message ID 可在窗口结束后再次安全执行；
- 资源安全点的 `inputActive` 同时包含 CDP Input Ledger 与真人 VNC 输入窗口，迁移和
  休眠不会在人类连续输入中开始。

### 无损续行与断线

- Control Plane Dispatcher 将 `HUMAN_INPUT_PRIORITY` 视为 500ms 延后，不增加
  publishAttempts、不进入 Dead Letter、不创建失败证据；
- Agent Navigation/Action Pending Deadline 扩展到 30 分钟，允许合理的人机协作时段；
- 协作 VNC 断线仍执行 CDP `release_all`、X11 All-keys-up、State 重采集与 Baseline 更新；
- 协作断线不会删除 `active_human_takeovers`、不会发布 `HumanTakeoverEnded`、不会终止
  Agent Operation；只有旧/显式 `EXCLUSIVE_TAKEOVER` 保留原结束事件语义。

### Web / Tauri 共用 UI

- Session 详情按钮改为“打开远程桌面”，直接导航，不再隐式调用 HumanTakeover Mutation；
- 远程桌面按 Session Context Epoch 校验票据，移除“结束接管”按钮；
- 页面显示 `AGENT + HUMAN READY` / `HUMAN READY`、两秒真人输入窗口、Agent Deferred
  和断线保留 Agent 的真实语义；
- 组件仍位于共享 React Web 代码，未来 Tauri 2 复用同一 API Client 与状态逻辑。

## 已通过验证

```bash
cargo test --manifest-path apps/browser-node/Cargo.toml -p remote-desktop-gateway
cargo check --manifest-path apps/browser-node/Cargo.toml -p node-agent
./gradlew -p apps/control-plane test \
  --tests 'io.browsercloud.application.RemoteDesktopTicketServiceTest' \
  --tests 'io.browsercloud.application.SessionApplicationServiceTest' \
  --tests 'io.browsercloud.infrastructure.NodeCommandMultiNodeRoutingTest'
pnpm --dir apps/web-console lint
pnpm --dir apps/web-console test
pnpm --dir apps/web-console build
make contracts-check
make test
make lint
make test-e2e
```

结果：Gateway 8 项测试、Web 67 项测试、Java/Rust/Python 全量测试、Clippy `-D warnings`、
Web Lint、OpenAPI/Proto 契约、生产构建与真实托管 noVNC E2E 全部通过；E2E 输出
`WEB_CONSOLE_E2E_OK`、`WEB_CONSOLE_VIEWER_RBAC_OK` 和 `health={"status":"UP"}`。

## 尚未完成

1. 目标 Linux/正式 Chromium/x11vnc 下覆盖连续拖拽、组合键、输入法、剪贴板和长按；
2. 托管 E2E 已证明普通 VNC 不创建/替换 Operation；仍需增加活跃 Agent 写命令与真人
   输入真实并发、两秒后自动续行的跨进程时序用例；当前由 Agent Operation 应用服务测试、
   RFB 解析测试和 Outbox 无损延后测试分别覆盖；
3. 多分钟连续真人输入时的 Agent UI 等待原因/SSE 可视化目前仅有 Node/Outbox 状态，
   尚未增加专用 `WAITING_FOR_HUMAN_INPUT` 公开投影；
4. 单 Session 仍限制一个 VNC Client，多观察者广播不在本轮范围；
5. 若未来支持需要 VNC Password Challenge 的 RFB 安全类型，需将客户端输入解析从当前
   受控无认证 RFB 3.8 握手扩展为协商状态机。
