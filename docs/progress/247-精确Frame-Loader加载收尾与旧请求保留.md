# 精确 Frame/Loader 加载收尾与旧请求保留

> 日期：2026-10-02
> 范围：Browser Safety Observer 的加载终态、事务保护与公开回放诊断。

## 独立复现

进度 244 已将当前页 Quiet 与其他 Document 的未完成写入分开，复查发现原有
`Page.loadEventFired` 仍清除同一 CDP Session 下所有 Document/parser 请求，
没有检查 Document 归属。新页面 load 可能清掉旧页面尚未结束的表单 Document
请求，降低全局网络和表单事务计数；短期 settle 不能替代持续在途保护。

新单元回归覆盖已知新 Document 和 Document 身份缺失两种情况，旧逻辑均提前
清掉旧/未知表单请求；真实 WebSocket/CDP 事件回归在旧 XHR 写入之外加入旧
Document POST 和新页 load，旧逻辑全局请求仅剩一条、表单计数为零，两项均失败。

## 修改

[官方 CDP 协议](https://raw.githubusercontent.com/ChromeDevTools/devtools-protocol/master/json/browser_protocol.json)
中 `loadEventFired` 仅有 timestamp，`lifecycleEvent` 含 Frame ID、Loader ID、
事件名和 timestamp。Observer 启用必需的 `Page.setLifecycleEventsEnabled`，
以 `lifecycleEvent(load)` 收尾：事件必须匹配已提交当前 Document 的 Frame/Loader，
请求也必须具有相同 Frame/Loader 和已证明的当前 Document 归属。只处理
Document/parser 加载资源，Script Fetch/XHR 继续等待自身网络终态。

普通无身份 load、未知上下文、旧 Document、旧子 Frame Loader、其他 Frame/Session
均不能完成请求；父子 Frame 的 load 不互相清账。精确终态后仍执行原事务 settle，
未完成旧写入保留全局计数，历史 Loader 恢复时仍阻塞当前页。必需订阅被 CDP 拒绝
时观察器失败，不回退无身份清账。未扩大输入、网络白名单或普通动作静默规则。

API、Protobuf、数据库与 SDK 未改动，协议身份只在 Node 的既有有界内存上下文使用。

## 浏览器内部请求分类

新加载围栏后的公开 IdP 定向回放在 Username 稳定等待失败。独立 CDP 固定 Scheme
诊断确认持续约 45 秒的 `Document:script` 是 Chrome 新标签页的 `chrome-untrusted`
子 Frame，返回 200 后未收到网络终态；不能将此结果归为 Duende 业务请求。
原先仅按 initiator 排除浏览器服务，对该 Document 类型无效。

[Chromium 官方文档](https://chromium.googlesource.com/chromium/src/+/HEAD/docs/webui/chrome_untrusted.md)
说明该 Scheme 提供浏览器内置 WebUI 资源；名称不代表其中内容可信。本次只将
CDP 精确报告的 `chrome/chrome-untrusted/devtools` 内部 URL 归入既有浏览器服务
排除范围，解析有长度/格式边界。HTTP(S)、Data/Blob、Extension、未知或非法 URL
保持原规则，不以 Host、路径、GET、年龄或响应状态忽略业务请求。

加入内部 Document 的真实 WebSocket 回归在分类修复前再次失败，修复后通过；
精确 Scheme、仿冒 HTTP 路径/Host、Data/Blob/Extension 和非法/超长 URL 边界均通过。
原新页 load 不得完成旧/未知业务表单的回归继续成立。

本轮另一次诊断在浏览器启动前因监听端口占用失败，不能算网站回放。测试 Runner
改为按 Gateway 的实际全部 IPv4 监听地址同时保留五个独立端口后统一释放，减少
重复或部分地址占用的选择；释放到服务绑定间仍可能被其他进程竞争，不宣称消除
全部启动竞态，也不据此确定历史失败的占用者。

最终重建 Node 后，公开 IdP 两例定向回放通过：`public-duende-idp-login` 与
`public-duende-oidc-code-pkce`。网站公布的账号仍仅经一次性 USERNAME/PASSWORD
输入，固定 Client/Scope/S256/精确 HTTPS 回调、签名/Claim/UserInfo/授权码复用拒绝
验证保持有效。独立 CDP 的五个快照仍包含内部请求，网页正常完成，验证分类修复
消除了此类阻塞。该两例不代替完整 17 例连续稳定性或真实企业 IdP Gate。

成功回放和观察器均已结束，私有证据位于
`/tmp/agentbrowser-idp-internal-fixed.aJDiNk/`。定位 Scheme 的失败回放证据位于
`/tmp/agentbrowser-idp-scheme-pool.vOOlYE/`，初始 Frame 时序诊断位于
`/tmp/agentbrowser-idp-frame-trace.kJUrkX/`。独立 CDP 观察器仅保存固定 Scheme/Host/Route
类别、Hash 身份和生命周期时序，不输出页面 URL Query、Header、正文或 Token。
Harness 的异常页面摘要是另一条路径：本轮还发现隐藏表单防伪字段值可进入 State
和异常日志，需进一步在 State 源头遮罩并收紧异常摘要；不能把观察器脱敏当作
整份异常日志的隐私保证。相关运行目录均为本机私有测试证据，未使用真实客户数据。

## 验证

- 上述两项旧/未知请求回归修复前失败、修复后通过。
- 新增父子 Frame/旧 Loader 隔离和必需生命周期订阅拒绝回归，通过。
- State Collector 默认 54 项通过、3 项真实环境测试默认忽略。
- Rust Workspace 195 项通过、6 项环境测试默认忽略。
- 显式三项真实 Chrome State、Tab 和旧 Document keepalive POST 回归通过。
- Rust 1.99 Workspace/all-targets 严格 Clippy、Node Agent 重建、格式与差异检查通过。
- Replay Gate 18 项、Shell 语法检查通过。
- 前一精确提交 `8c133e80d486822def61b7e79cca95a4444cf08d` 的 CI
  `37001784272` 与 Desktop `37001784154` 全部通过；本次提交仍须单独核验。

## 本轮公开回放

完整矩阵使用前一精确构建 `8c133e80d486822def61b7e79cca95a4444cf08d`，原 Dataset
和白名单未变，Session `ses_9ba2747459714ca7` 在 `practice-login-logout` 以
`NAVIGATE_RESPONSE_TIMEOUT` 失败，未完成 17 例。Node 超时 UTC 11:39:00.813569，
独立只读 CDP 的 Document `loadingFailed` 为 11:39:00.961950，晚于 Node 超时且
接近 Harness 清理；未知错误仅归为 OTHER，不能用该通知证明超时前的传输根因。

Harness、浏览器和观察器均已退出并完成自身清理。私有日志位于
`/tmp/agentbrowser-full17-finite.Tg6G2L/`。本次加载收尾缺口由独立回归证明，
没有证据表明它导致该导航超时，不能把修复当作公开完整矩阵通过证据。

原 11 项目标中的公开连续稳定性、客户 Adapter/Provider/Replay、真实 OTP/企业
IdP/支付、目标 Linux/云/硬件 Codec/跨 Region、供应商取消回执、组织发布证据与
权利人的许可证选择仍待完成。
隐藏表单字段的 State/日志脱敏缺口也是本轮确认的后续代码待办。
