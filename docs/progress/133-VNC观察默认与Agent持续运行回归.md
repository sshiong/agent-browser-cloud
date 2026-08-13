# VNC 观察默认与 Agent 持续运行回归

> 日期：2026-08-13
> 状态：协作数据面与 Agent 无损续行已复核；本轮补齐默认只读观察和 E2E 回归，目标 Linux 长稳仍待执行。

## 问题

用户打开 VNC 的主要目的通常是观察 Agent 的浏览器行为。虽然现有服务端已经把普通 VNC
建模为 `COLLABORATIVE`，不会创建 `HUMAN_TAKEOVER`、中止 Agent Operation 或断开
Agent，但 Web 默认进入可输入的协作控制模式。鼠标点击或键盘事件会按设计触发两秒真人输入
优先窗口，容易让用户误以为“连接 VNC 就切断了 Agent”。

## 本轮完成

- 远程桌面默认改为服务端强制的“只读观察（推荐）”；打开页面后 Agent 持续执行，鼠标移动、
  聚焦或误触不会形成真人输入事件；
- 用户需要辅助操作时可显式切换到“协作控制”。只有 Gateway 实际解析到 RFB KeyEvent、
  PointerEvent 或剪贴板输入后，Agent 写命令才以 `HUMAN_INPUT_PRIORITY` 延后；
- 真人停止输入两秒后，Control Plane 重投同一条持久 Node Command，原 Agent Task 和
  Operation 自动续行，不创建失败证据、不增加失败次数；
- UI 明示 `VIEW ONLY / AGENT CONTINUES`、`OBSERVE ONLY` 与 `Agent ACTIVE / VISIBLE`，
  避免把“在线观察”和“显式接管”混为一谈；
- E2E 先验证默认票据为 `viewOnly=true`，再显式切换协作控制验证键鼠转发、断线
  All-keys-up，以及 Agent 在持续真人输入时保持 `RUNNING`、输入空闲后完成原 Operation。

## 代码复核结论

- `POST /api/v1/sessions/{id}:desktop-connection` 只签发短期协作票据，不调用 Coordinator
  获取或抢占 Operation；已有 Agent Operation 保持原 ID 和状态；
- Browser Node 对 `COLLABORATIVE` 断线只做输入释放和 State Baseline 更新，不发布
  `HumanTakeoverEnded`，日志明确记录 Agent Operation 保持活跃；
- 缺少 `accessMode` 的旧票据仍 fail-collaborative，不能在滚动升级中隐式变成独占接管；
- 只有用户明确发起的 HumanTakeover/Handoff 才继续使用 `EXCLUSIVE_TAKEOVER` 安全屏障。

## 验证

```bash
pnpm --dir apps/web-console lint
pnpm --dir apps/web-console test
pnpm --dir apps/web-console build
make test-e2e
```

Web Lint、70 项单测和生产构建通过。真实 noVNC E2E 已走通默认只读、显式协作输入、
Agent `HUMAN_INPUT_PRIORITY` 等待与原 Operation 恢复段；完整套件随后在与本改动无关的
高风险确认用例响应等待处出现既有偶发超时，未观察到 Agent 因 VNC 连接进入失败、取消或
HumanTakeover。该套件需继续单独稳定高风险确认阶段。

## 尚未完成

1. 目标 Linux/正式 Chromium/x11vnc 的 8 Client 长稳、输入法、组合键和连续拖拽矩阵；
2. 跨 Region Remote Desktop Relay 与断网重连长稳；
3. 现有综合 E2E 中高风险确认步骤的偶发响应超时稳定性治理。
