# 页面静默绑定 Chromium 文档身份与区域基线围栏

> 日期：2026-10-03
> 基线：`c21d374bf06c42c461dcaa9a4f6cd5fcb2b14a1e`
> 范围：Node 主文档采样身份、静默窗口与 Region Resync；不开放稳定区域动作。

## 实际复现

既有静默指纹只包含 DOM/Layout/Focus/Route 语义、Tab 和 URL。真实 Chrome
在相同 URL 重载同一自有页面后，第一次完整采样仍得到 route quiet 365 毫秒；
新增“新文档应从零累计”的断言实际失败。首次试验先在 document interactive
前置断言失败，不算该缺陷证据；校准为独立只读探针等待 complete 后，取得了
上述旧生产代码失败证据。独立探针没有更新 Collector Cursor。

## 修复边界

- 在同一 Page CDP WebSocket 上，用 `Page.getFrameTree` 分别读取采样前后的
  主 Frame ID 与 Loader ID；身份有界、必须完整且为主 Frame，缺失、拒绝或
  两端不一致均拒绝快照。摘要由 Node 生成，页面 by-value JSON 中的同名字段
  经 serde skip 丢弃，原始 CDP 身份不进入公共 API。
- DOM/Layout/Focus/Route 四类指纹都加入该摘要。同 URL 新 Loader 清零四类
  quiet，Content Hash/State 更新，Target Revision 也增加，旧引用不能沿用。
- Collector Cursor 保存该私有摘要。Region Resync 重新读取真实文档，要求与
  精确 baseline cursor 的摘要相同；否则拒绝合并旧文档的区域外 Targets。
- 前置身份、Runtime.evaluate、后置身份共用原三秒单调截止时间，发送与读取
  都受预算约束。既有动作确认的一次有界重读覆盖新增查询的 transport timeout/
  close，不重发输入；协议、身份变化/缺失仍直接拒绝。

协议字段与命令见 [Chromium 官方 CDP 定义](https://raw.githubusercontent.com/ChromeDevTools/devtools-protocol/master/json/browser_protocol.json)。
此处证明的是采样端点的主 Frame/Loader 一致性，不声称覆盖采样之间的全部
DOM 变化、子文档身份或任意导航往返交错。未来稳定区域动作仍需要更完整的
区域/实体/连续事件及风险证明，不能据此免除网络 quiet 或独立 Outcome。

## 验证

- 旧实际生产逻辑的真实 Chrome 回归失败，日志
  `/tmp/agentbrowser-document-reload-old.log`；修复后同 URL 重载四类窗口为零，
  旧 Region baseline 返回固定 `Region baseline document changed`。
- 真实 Chrome 还验证初始 `about:blank` 可采、后续动态 DOM/Layout/Focus/Route
  变化与实体替换继续成立。四项 ignored Chrome 用例显式执行，**4 passed**。
- 新 WebSocket 故障回归验证采样中 Loader 切换、后置身份缺失及页面伪造字段
  均不能产出混合快照；另验证后置查询不重新获得三秒预算。
- 最终 Rust Workspace **200 passed / 0 failed / 7 ignored**；State Collector
  默认 **59 passed / 4 ignored**；Rust 1.99 严格 Clippy 成功。
- 最终日志 `/tmp/agentbrowser-document-identity-rust-final.log`、
  `/tmp/agentbrowser-document-identity-real-final.log`、
  `/tmp/agentbrowser-document-identity-clippy-final.log`。

没有 API、Protobuf、数据库或 SDK 字段变化。新增两次只读 CDP 查询的实际
目标环境延迟与小时级长稳仍需验收，不以本机短测试代替。

## 公开矩阵与 CI

采样空档修复基线 `c21d374` 的完整公开回放退出 1，在代码 line 1244 的 OTP
页面稳定等待失败；最后 State v63/rev41，FRESH/COMPLETE、document complete、
quiet 0。相同主 Frame/Loader 的入口 Document POST 在 06:09:32 UTC 以 200
完成；末尾两项 Socket.IO GET/POST XHR 分别在途约 39 秒，Node 同时投影两项
`XHR:script`。该文档 WebSocket 在 06:09:38 UTC 收到 101，约十二秒后关闭；
没有关闭原因或业务消息正文，不推断其根因，也不能直接忽略轮询。

证据目录 `/tmp/ab-login-probe.njqxndzc/` 为 0700、日志为 0600。本轮 private
runner SHA-256 `01bb3b13983df2e6fd89ee9a49a1476bc9f4ed618da1e621e10bb34dfd506e08`，
observer `7d38880f9e0be4e647fbe2c5cdced443d175af30e636740ce614c435797abd7a`。
断言与超时保持原样，旁路只读取有限元数据，不保存 Header、页面文字、输入
值、Socket.IO SID 或帧正文。此轮运行的是基线二进制，不含本次文档身份修复。

本次文档身份修复重建 Node 后的 LOGIN 定向回放也退出 1，在 line 1013 的
练习站入口 NAVIGATE 响应超时。此前 Selenium 两个 Document GET 均 200
完成；练习站新 Loader GET 约十五秒后失败、无 HTTP 响应，未知具体原因。
Node 未记录新增文档身份拒绝/超时，不能据此归因或证明该检查永不失败。
证据 `/tmp/ab-login-probe.ndi6ty5y/`；未取得登录六例通过，更不是完整十七例。

`c21d374` [Desktop 37101954265](https://github.com/sshiong/agent-browser-cloud/actions/runs/37101954265)
已确认 Windows/macOS 成功；[CI 37101954321](https://github.com/sshiong/agent-browser-cloud/actions/runs/37101954321)
随后确认 Verify（含 Integration/Object Storage GameDay）与 Operator 全部成功。
本次提交的 CI/桌面必须另按精确 SHA 核验。

十一项目标的稳定区域 fallback、公开连续全量、真实客户 Adapter/OTP/IdP/支付、
供应商取消与账单回执、目标云/Linux/硬件/多 Region、组织及许可证决定仍保留，
见 [252](252-十一项目标完成边界与SSE修复CI核验.md) 和
[33](33-当前未实现清单.md)。
