# VNC 协作缺省与 Agent 不断线回归

> 日期：2026-08-11  
> 状态：兼容性修复、Rust 定向测试和真实托管 Web/Node E2E 已通过。

## 问题

当前正式链路已经把普通远程桌面与显式 HumanTakeover 分开：打开 noVNC 不创建或抢占
Agent Operation，只有真人真实键鼠输入才触发两秒优先窗口，停止输入后同一 Agent 命令
自动续行。

但 Browser Node 对旧版、缺少 `accessMode` Claim 的远程桌面票据仍默认解释为
`EXCLUSIVE_TAKEOVER`。在 Control Plane/Node 滚动升级或本地仍运行旧进程时，这会让普通
VNC 票据落入排他连接仲裁，与“连接只用于观察和辅助 Agent”的默认语义不一致。

## 本轮完成

- 缺少 `accessMode` 的兼容票据现在缺省为 `COLLABORATIVE`；
- `EXCLUSIVE_TAKEOVER` 必须由 Control Plane 在已经存在显式 HumanTakeover Operation 时
  明确签名，不能由字段缺失隐式获得；
- 保留真人输入优先：Key/Pointer/Clipboard 活跃期间 Agent 命令可重试等待，Agent Task 和
  Operation 始终保持连接；仅观看像素、握手、帧请求和只读观察不会暂停 Agent；
- 保留 Shared RFB 与最多 8 个协作者，新的 Viewer 不会踢掉已有 Viewer 或 Agent；
- 新增旧票据反序列化回归测试，锁定“缺字段 = 协作”规则。

## 验证结果

```bash
cargo test --manifest-path apps/browser-node/Cargo.toml -p remote-desktop-gateway
./gradlew -p apps/control-plane test \
  --tests io.browsercloud.application.RemoteDesktopTicketServiceTest \
  --tests io.browsercloud.application.SessionApplicationServiceTest \
  --tests io.browsercloud.infrastructure.NodeCommandMultiNodeRoutingTest
make test-e2e
```

- Gateway 13 项测试通过；
- Control Plane 定向测试通过；
- 真实托管 E2E 输出 `WEB_CONSOLE_E2E_OK`、`WEB_CONSOLE_VIEWER_RBAC_OK`；
- E2E 明确校验普通 VNC 不创建 HumanTakeover、不改变活跃 Agent Operation，真人连续输入时
  Task 仍为 `RUNNING + HUMAN_INPUT_PRIORITY`，输入空闲后同一 Task 自动继续。

## 仍保留的边界

“打开远程桌面”始终走协作连接。显式 Agent→Human Handoff 仍属于另一条高风险治理流程，
只有调用专用 HumanTakeover API 才会进入；普通 VNC 页面不会隐式调用它。

