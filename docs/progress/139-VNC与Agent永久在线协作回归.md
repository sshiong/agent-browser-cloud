# VNC 与 Agent 永久在线协作回归

> 日期：2026-08-13
> 状态：代码、定向测试、Web 构建和契约文档已完成；目标 Linux 长稳仍待执行。

## 问题

普通 VNC 已经支持观察 Agent，但仓库仍保留一条旧的排他 HumanTakeover 分支：显式人工流程
可能签发 `EXCLUSIVE_TAKEOVER` 票据，Gateway 会撤销协作者，Coordinator 也可能先中止活跃
Agent Operation。用户因此仍可能遇到“真人连接 VNC 后 Agent 断开”。

## 本轮完成

- Control Plane 的远程桌面接口始终签发 `COLLABORATIVE` 票据，票据绑定 Session Context，
  不再根据当前 Operation 所有者切换成排他连接；
- Browser Node Gateway 对滚动升级期间遗留的、有效签名的 `EXCLUSIVE_TAKEOVER` 票据执行
  fail-collaborative 归一化，不撤销已有 Viewer，也不阻止新协作者加入；
- 真人加入协作桌面时，Coordinator 保留当前 Agent Operation，不执行 abort，不下发
  `BeginHumanTakeover` 输入屏障；
- VNC 连接、仅查看和断开均不改变 Agent Task/Operation。只有 Gateway 实际解析到键盘、
  指针或剪贴板输入后，Agent 写操作返回可重试 `HUMAN_INPUT_PRIORITY`；
- 真人停止输入两秒后，Control Plane 重投同一持久命令，Agent 继续原任务，浏览器和 VNC
  连接始终保留；
- Web/Tauri 共用页面不再因 HumanTakeover Actor 不匹配而禁用远程桌面入口。默认仍是服务端
  强制只读观察；用户可显式切换“协作控制”，UI 明示真人输入优先而非 Agent 断开；
- 高风险 Challenge、支付和账号安全步骤仍保留人工治理门禁。本轮取消的是 VNC 连接级排他，
  不是允许 Agent 越过敏感业务授权。

## 验证

```bash
cargo test --manifest-path apps/browser-node/Cargo.toml -p remote-desktop-gateway
cargo clippy --manifest-path apps/browser-node/Cargo.toml \
  -p remote-desktop-gateway --all-targets -- -D warnings
./gradlew -p apps/control-plane test \
  --tests io.browsercloud.application.RemoteDesktopTicketServiceTest \
  --tests io.browsercloud.application.SessionApplicationServiceTest \
  --tests io.browsercloud.coordinator.SessionCoordinatorTest
pnpm --dir apps/web-console lint
pnpm --dir apps/web-console test -- --run
pnpm --dir apps/web-console build
```

- Gateway 20 项测试通过；
- Control Plane 43 项定向测试通过；
- Web 21 个测试文件、72 项测试、Lint 和生产构建通过；
- 新增回归覆盖旧排他票据不踢协作者、人工加入不终止 Agent Operation、旧票据签发入口
  只输出协作 Context 绑定。
- 真实托管 E2E 连续两次越过本轮关键路径：两路 noVNC 均在线、单 RFB 上游、像素/键鼠
  转发、VNC 在线时 Agent 执行、真人输入等待与同一 Operation 续行均成立；Node 断线日志
  明确记录 `Collaborative desktop disconnected; Agent operation remains active`。完整套件随后
  分别在第 850 行高风险计划创建、第 818 行普通结构化计划创建的 UI `waitForResponse`
  发生 30 秒超时，无 HTTP/Console 错误；这是进度 133 已记录的后续 Agent 表单偶发时序，
  本轮不放宽断言掩盖，完整 `make test-e2e` 因此不计作通过。

## 尚未完成

1. 目标 Linux/正式 Chromium/x11vnc 的连续拖拽、组合键、输入法和剪贴板竞争长稳；
2. 8 Client 弱网、断网重连与跨 Region Relay 长稳；
3. 旧 `EXCLUSIVE_TAKEOVER` 枚举和协议字段需等待 N−1 兼容窗口结束后再物理删除；当前已在
   所有新签发和 Gateway admission 上失效，不再具有排他行为。
4. 综合 E2E 的 Agent 表单提交响应偶发超时需单独稳定；VNC/Agent 并发关键段已有两次真实
   运行证据，但完整套件仍保持失败口径。
