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

## 剩余边界

诊断能力本身不等于导航故障已修复；具体故障需由带类别的真实回放重新确认。
完整 17 例连续通过、小时级稳定性、目标 Linux，以及真实 OTP 交付/企业 IdP/支付/
客户 SPA 等外部 Gate 继续保持未完成。
