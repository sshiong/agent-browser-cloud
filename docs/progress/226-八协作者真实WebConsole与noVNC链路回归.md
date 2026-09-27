# 八协作者真实 Web Console 与 noVNC 链路回归

> 日期：2026-09-27

## 验证范围

在既有 `make test-e2e` 中，将远程桌面并发从两个页面扩展为八个真实 headless Chromium 页面。第一个页面保持协作控制，第二个页面保持只读，其余六个页面各使用独立 Actor 身份和只读模式。测试逐个等待 noVNC 的 `RFB LIVE`，再通过正式 `/desktop-participants` API 验证 `onlineCount=8`，并检查 fake x11vnc 的 TCP 连接日志仅有一个上游连接。关闭额外观察者后，原协作页面继续 `RFB LIVE`，后续真人输入、Agent 续行和 Viewer RBAC 用例继续执行。

首次增加页面时，一个新增页面在 20 秒内未完成 RFB 协商；该次测试让所有页面共用一个 Actor，不能作为八个独立协作者的证据，也不能单凭超时断定根因。改为每个页面使用独立 Actor，并从参与者 API 核验八个不同 Actor ID 后，完整 `make test-e2e` 通过，输出 `WEB_CONSOLE_E2E_OK`、`WEB_CONSOLE_VIEWER_RBAC_OK`、`real_web_console_e2e=true` 和 `viewer_rbac_e2e=true`。运行前 OrbStack 状态为 `Running`、Docker context 为 `orbstack`、Daemon OS 为 `OrbStack`。

## 边界

这是本机短时 E2E，RFB 上游是 fake x11vnc；它验证 Web UI、票据、Control Plane 参与者投影、Gateway Fan-out 和 noVNC 协议链共同工作，不证明目标 Linux 真实 Chromium/x11vnc 八客户端小时级长稳、硬件编码、丢包环境的持续帧龄或多用户高强度输入。进度 225 的真实 TCP/WebSocket/RFB Gateway 测试另验证 250 Kbps 弱网观察者的积压恢复。
