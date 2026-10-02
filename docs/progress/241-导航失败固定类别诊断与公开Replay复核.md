# 导航失败固定类别诊断与公开 Replay 复核

> 日期：2026-10-02
> 范围：Browser Node 本地导航失败诊断、真实 URL 稳定性验证。

## 发现与实现

进度 240 的完整 17 例曾一次通过，随后独立 Session 在练习站初始导航返回
`NAVIGATION_FAILED`。此前 Node 用 `.is_err()` 丢弃 State Collector 原因，失败日志不能
区分 CDP 响应超时、WebSocket、协议错误或 Chromium 网络拒绝。

State Collector 现在给导航路径附加类型化固定类别，覆盖页面不可用、连接超时、
传输失败、响应超时、关闭连接、非法响应和 CDP 拒绝。Chromium `errorText` 只有与
预先列出的 `net::ERR_*` 字符串完全相等时才映射为固定类别；带 URL/正文后缀、
未知码或未知 Error 均降级为固定的 `NETWORK_ERROR_OTHER`/`UNKNOWN`。

Node 仅在本地日志写入固定类别和 Session/Command/Task ID，不输出原 Error、URL、
Query、Cookie、Secret 或页面文字。公共失败事件/API 仍返回 `NAVIGATION_FAILED`，
没有增加公开契约字段，没有自动重放导航或放宽允许域、状态稳定性及敏感输入围栏。

## 验证

- 新增四项 Rust 回归，验证带敏感 URL/文本的 Error Context 不进入诊断、网络码必须
  精确匹配，以及真实 HTTP/WebSocket 测试链上的成功、网络失败、CDP 拒绝、非法
  JSON、关闭连接、方案拒绝和缺 Runtime 分类。
- Rust Workspace **182 项通过、5 项环境测试保持忽略**；State Collector **42 项通过、
  2 项真实 Chrome 测试默认忽略**。
- Rust 1.99 严格 Clippy 和 Node 重建通过；真实 Chrome State Collector 测试又验证
  本机关闭端口确实产生 `NET_CONNECTION_REFUSED`，诊断不包含测试 URL/Query。
- 新 Node 的 `make test-real-public-idp` 通过，实际验证公开 IdP 站内登录及独立
  OIDC/PKCE/SSO 两例。完整 17 例本轮在练习站等待网络静默失败：State 为
  `COMPLETE/FRESH`、Document `complete`，仍有一个 `XHR:script` 请求在途。
  没有重现上轮 `NAVIGATION_FAILED`，因此不能为上轮错误推定具体原因。
- 网络采样代码会按精确 CDP Session/Request ID 处理 `loadingFinished/loadingFailed`；
  现有证据没有证明漏记收尾，也没有证明该 XHR 的服务端状态。不能以年龄、页面
  load 或已完成 DOM 为由把脚本请求直接视为完成。
- 功能提交 `7ea6ede26852ab713217d426624ce410f482e4b6` 的 `ci` run `36971447349`
  与 `desktop` run `36971447454` 均通过，包括 Integration、Object Storage/Recording
  GameDay、Kubernetes Operator E2E 和 Windows/macOS。

## 独立 CDP 对照诊断

2026-10-02 使用新的临时 Chrome Profile 和同一精确域名出口代理做辅助诊断，不输入
账号或 OTP。两次单独访问练习站 `/login` 的 50 秒观察均收到 XHR 完成事件，没有
在途残留；再按 Example/W3C/Cloudflare/Selenium/Practice 导航顺序观察，收到 24 个
XHR 完成事件且没有残留。该辅助 Headless 诊断不包含平台完整 Agent/敏感输入流程，
不能据此宣布平台矩阵通过，也不能解释此前单独的 `NAVIGATION_FAILED`。

随后在真实平台完整矩阵的同一临时 Chrome 上添加第二条只读 CDP 连接。诊断仅保留
固定 Host/Route/请求类别、方法、响应状态和持续时间；不记录 URL/Query、Header、
正文、Cookie、Secret 或原始错误。观察器从首个 Example Document 开始收到事件。
矩阵最终在第二轮 OTP 入口等待网络静默失败，尚未取得完整 17 例通过。

| UTC 观察时间 | 独立 CDP 的练习站请求 | Node/权威 State |
| --- | --- | --- |
| 06:26:41 | 一个 `POST / XHR / script` 在途约 23 秒，尚无响应状态 | 网络静默为零 |
| 06:27:01 | 同一在途请求约 43 秒，仍无响应状态；XHR 启动 19 次、完成 18 次 | 一个 `XHR:script` 在途，`COMPLETE/FRESH/CHANGING` |

清理前的独立证据确认浏览器也有真实在途请求，因此本次失败不是只有 Node 的旧计数
残留。清理后的零计数来自关闭 Browser/Target，不能当成请求成功证明。这项对照没有
隔离上游、代理传输或浏览器网络服务的具体原因，也没有观察该 POST 的事务内容，不能
把它当成可忽略的后台流量或盲目重发请求。网络静默、敏感输入与事务围栏保持原样。

### OTP 定向复核

同日再次执行 `REAL_URL_SKIP_BUILD=true REAL_URL_OTP_ONLY=true make test-real-url-agent`，
仍使用 OrbStack、真实 Chrome 和原精确出口白名单。公开导航、Selenium 表单、错误密码/
正确登录、错误码/正确码 OTP 共 **8 例通过**，输出 `practiceOtp=verified`。
第二条只读 CDP 连接从首个 Document 开始观察，不发送页面动作或读取请求正文。

[练习站公开 OTP 页](https://practice.expandtesting.com/otp-login)的内联脚本在 `window.onload` 中调用 `io()`，然后发送
`pageChange`；公开表单本身则向 `/otp-login` POST。辅助诊断因此仅增加精确路径的
固定 `SOCKET_IO` 与 `PRACTICE_OTP_ENTRY` 类别，以及两个精确 RUM 路径匹配，不输出
Query、Header、正文或凭据。这些类别未写入产品策略，也未使请求跳过网络静默。

UTC 06:56:44、06:57:04 与 06:57:09 观察到的三个采样分别包含约 1.6、0.5、3.4 秒的
`SOCKET_IO / GET / XHR` 在途请求，后续均收到完成事件。该定向运行没有重现持续
43 秒的 POST，不能追溯认定此前 POST 属于 Socket.IO，也不能宣称网络故障已修复。
这是 8 例定向通过证据，不是完整 17 例或连续长稳通过证据。

辅助日志位于本机私有临时目录 `/tmp/agentbrowser-otp-network-probe.v0eTNB/`；
观察器与测试进程均正常退出，原测试 Harness 已清理本轮容器、临时 Profile 与私有证书。

## 剩余边界

诊断能力本身不等于导航故障已修复；具体故障需由带类别的真实回放重新确认。
完整 17 例连续通过、小时级稳定性、目标 Linux，以及真实 OTP 交付/企业 IdP/支付/
客户 SPA 等外部 Gate 继续保持未完成。
