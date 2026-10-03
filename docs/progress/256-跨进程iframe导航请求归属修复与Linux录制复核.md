# 跨进程 iframe 导航请求归属修复与 Linux 录制复核

> 日期：2026-10-03
> 已核验基线：`41eaa5f9c32f19c50b432dd02346e214a4404bb0`
> 范围：子 Frame 导航网络归属、真实 Chrome 回归与已有录制诊断。

## 公开完整回放发现的新缺口

基线完整回放通过前面的 OTP、公开站点和托管 Opaque Challenge 断言，随后在
`real_url_agent_matrix.py` 返回 Example 页的稳定 Target 等待处超时。最后 State
FRESH/COMPLETE、Document complete，Version 145、Target Revision 88、quiet 为零，
一个 Link 离屏。该轮不是此前 OTP 页的稳定等待失败，不能合并原因。

同一 Browser 的只读 CDP 确认主 Frame 已提交新 Example Document 并完成 HTTP 200，
旧托管子 Frame 的新 Document Loader 请求仍在 Page 事件流中未收尾；此前该子
Frame 从 about:blank 导航，出现 `Page.frameDetached` 的 `reason=swap`。原归属实现
要求请求 Loader 与已提交子 Frame Loader 相等，并在 swap 时删除子 Frame 上下文。
新 Loader 的 Document 请求因此失去已知父文档归属，主页面导航后仍按未知请求阻塞。

自有真实 Chrome 回归使用两个仅解析到 loopback 的 `.invalid` Host、独立 Profile、
`--site-per-process` 和自有 HTTP 服务：about:blank 子 Frame 导航到另一站点，之后
主 Frame 离开该页。旧代码实际失败，当前页仍有 `Document:script`；修复后同一
回归通过。它证明所述代码缺口，不证明所有历史未知请求都来自这一原因。

## 修复与围栏

- 已知子 Frame 的 Document 导航可沿当前已提交父树绑定主 Frame/Loader 身份，
  无需等待新子 Loader 提交。只适用于 Document、有效 Frame/Loader、已知子 Frame
  且父文档仍是当前已提交文档。主 Frame 的未提交 Loader、XHR 与未知 Frame 不能继承。
- `swap` 保留同一子 Frame 的父文档归属；`remove`、未知原因与无效身份移除或
  降级上下文。主 Frame detach 仍使根上下文失效。
- 只改变当前文档 Quiet 的归属判断。不将旧请求标记完成，旧表单/写入仍进入全局
  网络与事务保护；BFCache 恢复旧主 Loader 会重新阻塞，未知归属保持保守。
- Opaque Frame 输入、DOM 读取、Secret、支付和允许域策略没有开放。
  没有新增公开状态、API、数据库迁移或 SDK 契约。

Rust Workspace **196 项**通过；State Collector 四项真实 Chrome Gate 全部通过，
包含新子 Frame 用例、原 keepalive POST、动态结构化状态与围栏回归。
Rust 1.97 和 1.99 的 Workspace/all-targets 严格 Clippy 均通过。
新增单元断言还验证移除/未知原因、主导航、未知资源/身份、全局表单保护与旧 Loader 恢复。
日志位于 `/tmp/agentbrowser-child-frame-{rust-tests,real-chrome,clippy-1.99}.log`。

## 同一 Browser 的 Socket.IO 证据

上述失败轮三个精确主 Frame/Loader 的 Engine.IO polling 握手均 HTTP 200，
有效 open packet 宣告 websocket、pingInterval 10000 毫秒。其中两个后续
WebSocket 握手返回 101，另一个产生 frame error/关闭，未记录错误正文或状态。
证明该平台路线能够升级 WebSocket；不证明全部连接稳定，不追溯认定此前升级失败原因。
无 SCRIPT_EXCEPTION 观察，但 Runtime.enable 回执未核验，不能把未观察到事件当成
脚本始终正常的证明。未读取 WebSocket 应用消息、握手 Header 或保存 Engine.IO sid。

原失败证据目录 `/tmp/ab-login-probe.lryfsrt8/`，0700；子日志与枚举投影 0600。
观察器 SHA-256 `ab410b5f60df81bb59a110fb7bb672db9e3518349cb8e8c43ae8717a1a772931`，
运行器 `de06ef9278c40bf1afde7a8283b36af429acfed5bd6ea33c3059b5700c9d9be0`。
二者是私有诊断，原业务断言、期限、Secret 及网络静默门槛不变。

修复后新二进制的完整回放在 Sauce Labs 的 Login Target 等待处失败，未到达
原末尾 iframe 场景。该轮 Document HTTP 200 已完成，最后 State COMPLETE、STABLE、
Document complete、Version 101、Revision 57、quiet 31821 毫秒，但 Target 数为零。
不能把 200/Quiet 当成登录页面已经渲染，也不能据此宣布 iframe 修复已通过完整
矩阵。原始私有证据 `/tmp/ab-login-probe.a199yle3/`；实际 Gate 退出 1。
最后 API 旁路 freshness 为 OTHER（该枚举投影未覆盖完整 freshness 值），不反推
FRESH；未观察到脚本异常仍不证明脚本执行正常。待隔离页面渲染/脚本证据。

## Linux Recorder 与精确提交 CI

基线 `41eaa5f` 的 [CI 37054039997](https://github.com/sshiong/agent-browser-cloud/actions/runs/37054039997)
和 [Desktop 37054040015](https://github.com/sshiong/agent-browser-cloud/actions/runs/37054040015)
均完整成功，包含 Integration、Recording GameDay、Operator 与 Windows/macOS。
不覆盖文档提交 `ec28bb8` 的原 Recorder ready 接收失败。

使用 OrbStack Linux aarch64 Bookworm、Rust/Cargo 1.98、实际 lockfile Tokio 1.53.1，
只读挂载当前仓库与 registry、独立私有 target、容器网络关闭。实际 Recorder 模块
先通过 10 项，随后相同测试二进制以八线程运行十轮，每轮 10 项全通过，共 **110 项**。
原 ready 前置失败未重现，底层原因仍未知，未加重试或跳过断言。这个 ARM64 本地
容器不是 GitHub Ubuntu/x86，也不是目标生产 Linux 长稳证据。

初次 offline 构建因 CARGO_HOME 未对应只读 registry 失败；初次重复脚本因测试二进制
路径归一化错误未启动测试，均不计入 110 项。修正后真实运行结果和工具链证明在
`/tmp/agentbrowser-recorder-linux-41eaa5f/`（0700/0600）；自有容器已清理。

## 尚未完成

V16 大纲要求持续变化页面超时后给出 `BestEffortStableState`、`unstableRegions`，
Planner 仅能操作有证明的稳定区域。目前仓库没有这些契约与执行闭环：Node 的普通
单动作/Batch 都要求当前文档网络 quiet，Control Plane 的 STABLE 同样约束全组件。
这是仍需实现的自动化覆盖率代码项，不能仅称为外部环境 Gate，也不能用路径/请求
年龄白名单或把全页伪造为 STABLE 来关闭。

公开完整 17 例连续稳定性仍待验证；真实客户 Adapter、企业 IdP、短信/邮件/TOTP、
支付 Provider、云 KMS/IAM/Legal Hold、多 Region、硬件/目标 Linux 长稳、供应商
计费取消与许可证权利人决定继续按 [252](252-十一项目标完成边界与SSE修复CI核验.md)
保留。修复后完整回放与本提交 CI 须各自以实际终态记录。
