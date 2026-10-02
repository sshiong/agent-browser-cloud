# Replay 事务重试证明与 OTP 轮询复核

> 日期：2026-10-03
> 已验证基线：`061883a8f5575f690408718204a9b5e4952125f1`
> 范围：测试请求契约与真实平台公开 Replay；不修改产品网络静默策略。

## 已关闭的脚本缺口

公开矩阵的实际 `request()` 原先只要求 HTTP 503 和
`DATABASE_TRANSACTION_RETRY` 错误码，未要求正式 Handler 的回滚证明。
现在同时要求 `details` 为对象且 `retryable is True`，最多三次、间隔
0.1/0.2 秒，继续保留相同 Tenant、幂等键和请求正文。

回归从真实矩阵源文件提取并运行实际函数，以本机 HTTP 服务返回故障响应，
避免导入矩阵时启动浏览器。缺失证明、空对象、false、字符串 true、整数 1
五种情况在修改前均错误发送三次，修改后只发送一次；明确布尔 true 下两次
回滚再成功仍保持精确请求。`make test-replay-gate` 共 30 项通过，Python
语法和 `git diff --check` 通过。本轮不新增正式 API/数据库/SDK 契约。

## 真实平台验证

先核对 OrbStack Running、`orbstack` context 与 `OS=OrbStack`，使用当前未改动
的产品构建、仓库原矩阵断言和网站公开测试账号，经一次性 Secret API 输入。

- `make test-real-public-login` 定向六例通过，含错误密码 alert、正确登录
  `/secure` 与 Logout、公开导航和表单。独立只读 CDP 记录两个 Document POST
  302 与返回 GET 200，精确主 Frame 身份一致。
- 完整 17 例运行失败于 OTP 页的网络静默等待。最后正式 State 为 FRESH、
  COMPLETE、Document complete、State Version 51、Target Revision 36，
  `networkQuietMillis=0`。独立 CDP 确认 OTP 入口 Document POST 在 UTC
  18:32:07.808 返回 200 并完成；当前主 Frame/Loader 此后持续 Socket.IO
  GET/POST XHR。多次新 GET 在上一次完成前开始，最后仍有该 Loader 的 GET
  在途。该失败不同于历史长时间未返回的 Document POST，不能合并归因。

首轮定向运行的 API 旁路没有采到记录：macOS 无模板 `mktemp -d` 未落入
诊断指定的 TMPDIR。完整运行使用私有拷贝，仅将仓库根路径与临时目录模板
改为明确路径，业务断言、期限和围栏未变，才取得 API 与 CDP 对照。
旁路 `pageActivity` 白名单未覆盖实际返回值，记录为 OTHER，不能据此判断
页面 ACTIVE/STABLE。原失败与上述诊断限制均保留。

证据目录为 `/tmp/ab-login-probe.poj0o4z5/`（六例）和
`/tmp/ab-login-probe.0y591q64/`（完整失败），目录 0700、文件 0600；后者保留
五类子进程日志、API 枚举投影与 CDP 元数据。诊断不输出正文、凭据、Cookie
或完整 URL；自有 Gate/观察进程已关闭。临时诊断不是新增正式产品能力。

## 轮询适配边界

公开 OTP 入口 HTML 的内联脚本在 onload 创建 Socket.IO 并发送 `pageChange`。
该静态入口证据不证明验证页所有消息的用途，也没有证明此轮 WebSocket 升级
为何未发生。[Socket.IO 官方协议说明](https://socket.io/docs/v4/how-it-works)
明确 polling 使用 GET 接收、POST 发送，且传输承载应用消息。因此不能仅按
`/socket.io` 路径、GET 方法或请求年龄将它们排除。后续需精确握手/升级诊断与
业务 Adapter 的用途、动作和结果证明；当前未知请求与事务保护保持原样。

追加一轮 OTP 定向八例，沿用原断言，仅在只读 CDP 观察器增加 WebSocket
创建、握手状态和关闭事件，以及固定 polling/websocket 传输类别。网站公开
账号/固定 OTP 仍经一次性 Secret API；错误密码、正确登录、错误码拒绝、正确码
续行与四个导航/表单 Case 均通过。证据目录 `/tmp/ab-login-probe.lq24gb7w/`。
此轮观察中 Socket.IO 请求和 WebSocket 事件均为零，未重现完整失败轮的轮询，
无法判断历史升级失败原因。未读取握手头、应用消息或自由文本错误。

一个独立、无 Cookie/凭据的公开 Engine.IO polling 握手 GET 返回 HTTP 200、
有效 open packet、`upgrades` 含 websocket、pingInterval 10000 毫秒。只保存
上述元数据，不保存 sid/响应正文。它与平台 Browser 是独立连接，不证明平台
在失败轮收到相同握手，也不将八例定向通过当成完整 17 例通过。
追加观察器 SHA-256 为 `19c8c0d3709ad739c0f33dfee0763d7a80b2f7f09f4d02cde5185f8ba9ae4f65`，
运行器为 `a2f303c904130b652c554cdcbd341511304d4c634c45c30fdad8aec4d9f0cb16`；
均为 `/tmp/` 私有诊断，未进入产品或改变网络静默策略。

## CI 与剩余目标

基线完整 SHA 对应的 [CI 37045454725](https://github.com/sshiong/agent-browser-cloud/actions/runs/37045454725)
和 [Desktop 37045454597](https://github.com/sshiong/agent-browser-cloud/actions/runs/37045454597)
全部成功，包含 Integration、Object Storage/Recording GameDay、Operator E2E
和 Windows/macOS。本文新改动推送后的 CI 仍需另行核验。

请求修复已推送 `517820e621f9aa8a27fe4f8d77c2826d1fb8648c`，其 CI
`37050160840` 与 Desktop `37050160834` 检查时仍在运行。未因观察超时重启。

十一项整体边界沿用 [252](252-十一项目标完成边界与SSE修复CI核验.md)。
本轮六例通过不关闭 17 例连续稳定性，亦不证明真实短信/邮件、企业 IdP、支付、
客户 SPA、目标云/Linux/硬件、云 Legal Hold、审计死锁完整根因或许可证决定。
