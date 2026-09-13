# Agent Reviewer 风险分层路由闭环

日期：2026-09-12。承接 progress 165 的 A12；本切片关闭所有任务无差别进入模型 Reviewer 所造成的
低风险延迟和成本缺口，同时保持中高风险与不确定计划 fail-closed。

## 确定性低风险旁路

- Reviewer 开启时，两个正式执行入口统一调用 `routeForExecution`，不再各自无条件创建 Review Job；
- 只有 Task 与全部 Plan Step 均不高于 R1，且 Intent 为 `ALLOWED`、计划未过期、允许域非空、
  来源包含平台策略、Trust Floor 为 `TRUSTED`、无 taint/确认/敏感密文或 PII/Credential/OTP 输入，
  才以确定性策略直接进入原有持久 Agent Worker 队列；
- 旁路不伪造模型审核结果：Reviewer 状态保持 `NOT_REQUIRED`，模型、Token、Latency 和 Cost 均为空，
  原因码固定为 `DETERMINISTIC_LOW_RISK_BYPASS`；Audit 记录风险等级和 `risk-tier-v1` 策略版本；
- 工具集合与嵌套 Batch Action 由控制面独立白名单复核，不信任 Plan 自报 Risk。解析失败、Task/Step
  风险不一致、未知工具、过期计划或不可信来源均回到模型 Reviewer。

## 高风险边界保持

R2 数据变更及 R3—R5 账号、金融、安全任务继续创建 PostgreSQL Review Job，并经过现有 Plan Hash、
Claim Token/Epoch/Lease、模型版本、置信度、成本和审计门禁。平台高风险人工确认仍先于 Reviewer，
本切片不改变确认策略；已有精确 Plan Hash 的 `APPROVED` 重试也沿用原幂等执行链。

公开 OpenAPI、Protobuf 和数据库结构均不变，仍为 245 Operations / 338 Schemas。

## 验证

- Control Plane 544 项通过；新增单测覆盖可信 R1 直接入执行队列、零 Review Job/模型成本，以及 R2、
  风险低报和 taint 计划 fail-closed；
- 完整 `make ci`、Desktop test/lint/unsigned build、N/N−1 与四语言 SDK/供应链 Gate 通过；
- 完整 PostgreSQL/Redis/MinIO/mTLS/真实 Chromium Integration 同时验证 R2 仍经人工和真实 Python
  Reviewer Worker，R0 不产生 Review Job、模型成本为空、直接产生持久 Execution Job 并写入版本化
  Audit，输出 `agent_reviewer_risk_routing=true`。

## 边界

- A12 仓库内通用代码项关闭；目标模型生产准入、客户 Replay 和站点领域风险分类仍是生产 Gate；
- 低风险旁路只省略独立模型策略审核，不省略 Capability、Operation/Worker、State/Target 围栏、循环
  检测、动态微批次或最终 Outcome Verification；
- 页面文本不能授予自身可信度或权限。更完整的 Prompt Injection 来源/权限传播仍由 A13 独立跟踪。
