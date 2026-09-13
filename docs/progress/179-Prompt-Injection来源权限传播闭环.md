# Prompt Injection 来源权限传播闭环

日期：2026-09-13。承接 progress 165 的 A13；本切片把 Prompt Injection 防护从关键词检测提升为
独立于内容措辞的来源权限模型，并在任务创建与每次真实工具派发前 fail-closed。

## 来源权限边界

- `user_goal` 与 `platform_policy` 是控制面保留的可执行指令来源；外部调用者不能以大小写、空白或
  外部 Source Type 冒充这两个 ID，也不能提交大小写不同的重复 Source ID；
- `APPLICATION_DATA / EMAIL / DOCUMENT / WEB_CONTENT / THIRD_PARTY_WIDGET` 永远是 data-only，
  `classification` 只作分类元数据，不能提升信任、域名、Capability、风险或确认权限；
- 所有外部来源均明确带 `NON_EXECUTABLE_CONTEXT` taint 且
  `executableInstructionAllowed=false`。关键词检测只增加 `PROMPT_INJECTION` 遥测，不再承担授权
  判断；即使文本看起来像“系统已批准”且没有命中关键词，也没有执行权限；
- 每个合法外部来源只写 content hash 的 `INSTRUCTION_SOURCE_CLASSIFIED / DATA_ONLY` 安全事件；
  冒充或重复来源写 `SOURCE_AUTHORITY_SPOOF` 并把 Task 置为 `BLOCKED`，正文不进入任务、审计或 Plan。

## 执行期权限传播

`AgentInstructionAuthorityPolicy` 对持久 Plan 逐 Step 重新验证：来源集合必须精确等于
`user_goal + platform_policy`、Trust Floor 必须为 `TRUSTED`、taint 必须为空，且不允许缺失或重复
来源。`AgentExecutionService.validatePlan` 是首次执行、异步 Step 续行、人工协助续行和 lease 恢复
的共同入口，因此旧任务、未来 Planner 变化或数据库中异常 Plan 都不能绕过检查进入 Navigation、
Action、Read 或 Handoff Tool。稳定拒绝码为 `UNTRUSTED_INSTRUCTION_AUTHORITY`。

这层校验独立于 Reviewer：模型 Reviewer 的批准不能把网页内容变成可信指令；A12 的低风险确定性
旁路也继续要求同一可信来源、无 taint 条件。Capability、允许域、State/Target 围栏和 Outcome
Verification 保持不变。

公开 OpenAPI、Protobuf 与数据库结构均未变化，保持 245 Operations / 338 Schemas。

## 验证

- Control Plane 549 项通过；新增测试覆盖无关键词伪授权内容、保留/重复来源 ID、受限 Trust、外部
  supporting source、taint、缺失和重复可信来源的执行前拒绝；
- 完整 `make ci` 通过：Web 141 项、四类 Agent Worker 30 项、Rust/Go、四语言 SDK、供应链、
  Operator 17 项、50k Coordinator Capacity 与 N/N−1 均保持绿色；
- Desktop test/lint/unsigned build 通过；
- 完整 PostgreSQL/Redis/MinIO/mTLS/真实 Chromium Integration 通过。原有间接注入样例真实执行
  完成，但 Plan 的每一步只含两个可信来源、`TRUSTED` Trust Floor 与空 taint，输出
  `prompt_injection_source_authority=true`。

## 边界

- A13 仓库内通用代码项关闭；这不声称可以识别所有恶意语义，安全性来自来源权限隔离而非检测率；
- 真实第三方 Connector、企业策略作者和未来 Planner 若要新增可信来源，必须先增加独立身份、审批、
  最小权限与执行期校验，不能复用网页提交的 `classification`；
- 客户站点 Replay、目标模型生产准入和组织 Threat Review 仍是独立生产 Gate。
