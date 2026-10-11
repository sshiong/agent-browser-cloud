# 单目标输入前 Revision 冲突重采

> 日期：2026-10-11
> 基线：f81b81ce4df9cdd2a7734f99555909c974e446fd
> 范围：原十一项中的结构化动作可靠性与真实网站回放。

## 问题与证据

基线公开十七例复验在 Selenium 表单 TYPE_TEXT 失败，Task 结果为 STATE_STALE；Node
固定错误文字为 `target revision is stale`。此前 GET_CURRENT_STATE 已 VERIFIED。
该次临时日志已被系统清理，不能据此推定全部失败的根因或输入派发时序。

本次用自有真实 Chrome 重建可重复场景：保存已经稳定的主文档输入元素与样本，随后
改变其 focus/value，再取得新稳定样本。Element ID 不变而 Target Revision 推进。
把旧样本的已重绑定命令交给输入前 Target 解析，旧逻辑拒绝；新增精确期望失败，
**0 passed / 1 failed / exit 101**。这模拟 Node 已采样后、解析前发生 revision 推进，
不把自有回归的原因追溯为所有公开网站失败。

## 实现与边界

- 正式单目标路径在调用输入执行器前先解析 source，DRAG 同时解析 destination。
  准备阶段只读取 State/Target 元数据，不调用 Input Broker 或 CDP 输入命令。
- 只有已由 Control Plane 授予主文档稳定 Element ID 的命令，遇到精确
  `target revision is stale` 才可重新采集并沿用原 `rebound_single_action` 校验。
  总共最多三次解析，即最多两次重采；其他错误立即返回。
- 重采使用原命令的 Element ID、base cursor、Secret/敏感输入权限和动作字段。
  页面未稳定、主文档或实体变化、Frame/Tab/可见性歧义仍由既有校验拒绝。
- 成功解析的 Target 交给原输入执行逻辑；该输入执行函数只调用一次。输入已派发后
  的失败、确认读取失败或不明结果不进入此重采流程。
- 历史/N−1 无稳定身份命令、Batch、Opaque Challenge/Human Assist 保持原路径与
  围栏。没有 API/Protobuf/SDK/数据库变更，也没有开放跨域输入或高风险决定。

## 已完成验证

- 同一个自有真实 Chrome 回归新逻辑 **1 passed / exit 0**。同时验证同 URL 重载后
  旧实体拒绝、历史无身份命令不能取得重绑定权限。测试在自己的 Chrome/目录清理后
  执行精确断言。
- Rust Workspace **241 passed / 0 failed / 10 ignored**。
- Rust 1.99 `node-agent --all-targets` 严格 Clippy、格式与 diff 检查通过。
- 完整 OrbStack Integration **exit 0、audit_chain_valid=true**，高层动作、快速取消、
  Dialog、Evaluate/Screenshot/文件、Recording 播放/删除与 Profile 恢复均通过。
- Chrome **154.0.8037.98** 当前构建公开回放已通过此前失败的 Selenium 表单输入；
  随后练习登录页以 NAVIGATION_FAILED 失败，Node 固定类别为
  `NAVIGATE_RESPONSE_TIMEOUT`，整体 exit 1。没有新的 `target revision is stale`。
  该轮与集成并行执行，无法归因站点、网络或本机负载。
- 集成结束后用新 Session 串行复验：Chrome **154.0.8037.98** 完整 **17 例 PASS /
  exit 0**，正式 Build-bound Runtime Validation 的 PASSED/COMMITTED 证据断言通过，
  精确主机出口断言通过。仅计一次新代码全量；没有重放失败 Task 或修改原动作/预算/
  网络围栏，不把串行通过追溯为并行负载导致此前失败的原因证明。

私有证据：`/tmp/agentbrowser-preinput-old-regression.log`、
`/tmp/agentbrowser-preinput-new-regression.log`、`/tmp/agentbrowser-preinput-workspace.log`；
同前缀 `.exit` 记录进程退出码。不会把页面正文、凭据或 Task 原始响应写入本文。
后续私有证据：`/tmp/agentbrowser-preinput-clippy.log`、
`/tmp/agentbrowser-preinput-integration.log`、`/tmp/agentbrowser-preinput-public.log`。
串行复验：`/tmp/agentbrowser-preinput-public-serial.log` 与同前缀 `.exit`。

基线 f81b81c 主 CI **37901534702** 与 Desktop **37901534629** 已按精确 SHA 重新
读取最终 JSON：Integration/GameDay、Operator、Windows/macOS 全部 success。
这不替代本次改动自己的 CI。

## 剩余范围

此处增加输入前准备的有界恢复，不证明完整稳定区域授权/动作/独立 Outcome 已完成。
原十一项中的客户实体属性与 SPA Dataset、真实 OTP/MFA/支付、具体跨域 Provider
协议、服务端模型取消回执、云 KMS/IAM/Legal Hold、多 Region/硬件/目标 Linux 长稳、
组织签字及权利人许可证决定仍需独立证据。总目标保持 active。
