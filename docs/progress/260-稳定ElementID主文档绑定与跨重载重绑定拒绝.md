# 稳定 Element ID 主文档绑定与跨重载重绑定拒绝

> 日期：2026-10-03
> 基线：`bf36093c7ed7ce8675efc9faa75f3ea63b7f578d`
> 范围：主文档内稳定元素的身份与真实 Resolver 重绑定路径。

## 实际缺口

[259](259-页面静默绑定Chromium文档身份与区域基线围栏.md) 已让同 URL 重载
更新 Target Revision，但 Batch 会以稳定 Element ID 在最新 Revision 重绑定。
旧 ID 只含路径/Frame 上下文/角色/控制类型/业务实体语义、URL 和 Tab；相同
URL 的新文档可产生相同 ID，因此单纯拒绝旧 Revision 不能关闭该路径。

新增真实 Chrome 回归使用同一自有页面重载，旧实际代码的新旧“执行验收”
按钮 ID 相同，`assert_ne` 实际失败。日志
`/tmp/agentbrowser-document-entity-old.log`；没有输入第三方站点或账号。

## 修复

`evaluate_state` 取得并核对受信主 Frame/Loader 摘要后，把摘要写入私有
`EvaluatedTarget.document_identity`。该字段同时禁止 serde 序列化和页面
JSON 反序列化，页面或 Adapter 无法声明这个绑定。`element_id` 的身份输入
加入它，其余语义/实体、URL/Tab、敏感字段排除规则保持原样。

主 Loader 改变后，即便 URL、路径、按钮名称和角色相同，新稳定 ID 也不同。
Batch 仍传原 ID 和最新 Revision；正式 Resolver 找不到旧 ID 时拒绝，不能
静默改投新文档。没有按路径兜底、输入重试或授权变化。

同一主文档的值、焦点、checked 状态变化继续保持稳定 ID，保留既有微批重绑定
能力。Region Resync 同文档复制私有绑定，跨主文档仍由 259 的基线围栏拒绝。
原始 Frame/Loader 不进入公共 API、数据库或 Audit；公开仍只有原有 opaque ID。
没有增加 API、Protobuf、数据库或 SDK 字段。

## 验证

- 新的真实 Chrome 断言验证重载后的 ID 不同；用旧稳定 ID 和**最新** Revision
  调用正式 `resolve_target` 仍拒绝，用新 ID 成功。此前旧 Revision 拒绝、旧
  Region baseline 拒绝、空白页启动、动态页面及实体替换用例继续通过。
- 新单位回归验证同文档输入值/焦点变化不改变 ID，主文档改变后 ID 改变，
  私有文档字段不序列化且页面伪造字段被忽略。
- Rust Workspace **201 passed / 0 failed / 7 ignored**；State Collector 默认
  **60 passed / 4 ignored**。另外显式运行四项真实 Chrome，**4 passed**。
- Rust 1.99 `clippy --workspace --all-targets -- -D warnings` 成功。
- 日志 `/tmp/agentbrowser-document-entity-rust-final.log`、
  `/tmp/agentbrowser-document-entity-real-final.log`、
  `/tmp/agentbrowser-document-entity-clippy-final.log`。

真实测试验证了 Batch 使用的 Resolver 路径，未宣称另外执行过跨重载的持久
Batch 全链场景。该场景和目标环境长稳仍应纳入后续扩展矩阵。

## CI 与剩余范围

基线 `bf36093` 的 [CI 37103375143](https://github.com/sshiong/agent-browser-cloud/actions/runs/37103375143)
检查时 Operator 成功、Verify 仍在运行；
[Desktop 37103375027](https://github.com/sshiong/agent-browser-cloud/actions/runs/37103375027)
macOS 成功、Windows 仍在运行，尚未确认整轮完成。本次新提交须按新 SHA 验收。

此摘要绑定的是主 Frame/Loader，不能替代每个子 Frame 的精确 Document 身份、
区域连续事件和风险证明。缺业务键且可见语义相同的实体仍需 Adapter 或拒绝。
V16 稳定区域 fallback、公开十七例连续通过，以及原十一项客户/供应商、真实
OTP/IdP/支付、目标云/Linux/硬件/多 Region、组织与许可证 Gate 继续保留，见
[33](33-当前未实现清单.md) 与 [252](252-十一项目标完成边界与SSE修复CI核验.md)。
