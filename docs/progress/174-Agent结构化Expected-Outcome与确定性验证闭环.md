# Agent 结构化 Expected Outcome 与确定性验证闭环

日期：2026-09-12。承接 progress 165 的 A04；本切片实现完成并通过完整 Integration。

## 任务结果成为正式输入契约

- `CreateAgentTaskRequest` 新增最多十条 Expected Outcome，支持最终 URL、页面标题、语义目标
  存在/不存在，以及目标 checked/selected 状态；URL 必须属于 Task 允许域，目标必须绑定规范化
  role 与可访问名称；
- 原始 `matchValue` 只在创建事务内参与规范化，进入 PostgreSQL、公开响应、Audit、Worker
  Payload 和四语言 SDK 读模型的只有 SHA-256；URL 比较会去除 query/fragment，避免把 Token
  当作结果证据；
- V118 以 additive JSONB 字段保存规范化声明和最终判定，数据库约束数组形状及十条上限，旧
  Task 默认空数组；正式 API 保持 245 Operations，扩展到 338 Schemas，N/N−1 保持兼容。

## 控制面确定性 Intent Verification

Outcome Verifier 领取的精确最终 Browser State 会先由控制面计算每条断言。URL、标题和目标名称
均按相同规范化规则比较；敏感或不可见目标不能充当成功证据。完整状态中缺失目标是确定性失败，
深度受限状态中的缺失/不存在结论为 `INDETERMINATE`，不得把不完整采集误判为成功。

声明与判定会进入 A11 的 evidence/input hash、Task 投影和完成 Audit。即使模型返回
`VERIFIED / GOAL_SATISFIED`，任一 `NOT_SATISFIED` 或 `INDETERMINATE` 仍由服务端改写为
`NOT_VERIFIED`，分别给出 `EXPECTED_OUTCOME_NOT_MET` 或
`EXPECTED_OUTCOME_INDETERMINATE`。因此动作 ACK、Reviewer 允许或模型自报成功都不能覆盖
确定性业务结果。

Web/Tauri 共用 Automation 页面可声明、校验和删除 Expected Outcome；任务详情显示每条
`SATISFIED / NOT_SATISFIED / INDETERMINATE` 与稳定原因码，不显示原始匹配文本。

## 验证

- Control Plane 531 项、Web 141 项、Agent/Reviewer/Vision/Outcome Worker 24 项通过；
- OpenAPI lint、245/338 TypeScript/Python/Go/Java SDK 生成完整性、编译、打包与发布清单通过；
- V118 N/N−1 Gate 验证 additive 默认值、数量约束、请求 write-only 与响应 hash-only；
- 完整 PostgreSQL/Redis/MinIO/mTLS/Chromium Integration 输出
  `agent_task_expected_outcomes=true` 和 `agent_task_outcome_verification=true`：创建响应及数据库
  无原始匹配值；最终标题确定性不匹配时，测试故意让模型提交 `VERIFIED`，控制面仍把 Outcome
  Job、Task 和等待中的执行 Job 收敛为未验证/失败；同轮其余 Agent Browser、Profile、资源、
  恢复和持久 Session 主链继续通过；
- 完整 `make ci` 与 Desktop test/lint/unsigned build 在提交前复验。功能提交 `153f077` 的首次
  GitHub CI `34681195902` 仅因 Docker Hub 已无法拉取固定 MinIO 镜像而失败，Verify、构建、
  四个 Worker 镜像、供应链扫描和 Operator E2E 均已成功；未将外部依赖失败冒充产品通过；
- 固定 MinIO Server/Client 版本保持不变，只将官方来源迁移至 Quay，并以真实对象存储
  GameDay 验证正常写入、500 ms 超时及本地 Checkpoint 可重试。修复提交 `172f6d3` 的 GitHub
  CI `34681675184`（含完整 Integration、供应链、对象存储/录制 GameDay 与 Operator E2E）和
  Desktop `34681675198`（Windows/macOS）均成功。

## 边界

- A04 的仓库内通用结构化 Expected Outcome/Intent Verification 已关闭；站点专用的订单、CRM、
  支付等领域 Validator 与真实客户 Replay 仍属于目标业务接入 Gate，不由通用 DOM 状态断言冒充；
- A03 的虚拟列表同名业务实体绑定、A05 像素隐私、A06 动态微批次与 A20 组合页面稳定性仍是
  独立问题；本切片没有提前关闭它们；
- 未声明 Expected Outcome 的旧 Task 仍由 A11 独立语义 Outcome Verifier 审核，滚动升级期间
  保持兼容；生产部署仍必须启用并验收该 Worker，不能以空声明绕过结果审核。
