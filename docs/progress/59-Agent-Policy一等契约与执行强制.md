# Agent Policy 一等契约与执行强制

> 完成日期：2026-07-28
> 数据库版本：V037
> 状态：创建绑定、PostgreSQL 持久化、Planner/Executor/Handoff 强制、API/UI 投影和
> 完整集成验收已完成

## 本轮关闭的缺口

此前创建向导会把 `agentEnabled` 和 `agentPolicy` 写入任意 `metadata`。Control Plane
不会读取这些值，Agent 任务仍可按调用方提交的预算和 Tool 执行，因此页面显示的
Restricted/Disabled 不构成安全边界。

本轮把 Agent Policy 升级为 Session 创建时不可变的一等契约，并在计划、执行和人工
交接三个入口重复校验。前端禁用只用于提前解释冲突，真正的拒绝由服务端完成并持久化。

## PostgreSQL 权威模型

- V037 新增 `sessions.agent_policy`，允许 `DISABLED`、`RESTRICTED`、`BALANCED` 和
  `INTERACTIVE`；
- 旧 Session 按 `metadata.agentEnabled` / `metadata.agentPolicy` 确定性回填，缺失值
  使用兼容默认 `BALANCED`；
- `agent_tasks.agent_policy` 固化任务创建时的策略绑定，执行前会与当前 Session 再次
  比对，旧计划不能绕过策略；
- 两个字段都有数据库约束，不使用 `localStorage`、JSON 文件或进程内状态作为权威值。

## 策略语义

| 策略 | 允许范围 | 默认/最大动作 | 默认/最大 Replan |
| --- | --- | ---: | ---: |
| `DISABLED` | 禁止创建可执行 Agent 计划 | — | — |
| `RESTRICTED` | 只读、等待、请求人工接管；禁止导航、点击、输入和滚动 | 5 / 6 | 0 / 0 |
| `BALANCED` | 当前有界 Tool 集合 | 8 / 12 | 1 / 1 |
| `INTERACTIVE` | 当前有界 Tool 集合，允许更长交互计划 | 12 / 20 | 2 / 3 |

`REQUEST_HUMAN_TAKEOVER` 还必须同时满足 Session 的
`humanTakeoverEnabled=true`。Agent Policy 和人工接管能力是两个独立契约，任一不允许
都会由服务端拒绝。

## 纵深执行强制

1. Planner 在持久化计划前校验策略、动作预算、Replan 预算和每个 Tool；
2. 违反策略的请求仍保存为 `BLOCKED` Agent Task，包含稳定原因码和安全事件，便于审计；
3. Executor 在领取任务及每次驱动执行时重新读取 Session，校验任务绑定与当前策略，
   防止旧计划或内部调用绕过 Planner；
4. Agent→Human Handoff 的请求与接受路径再次校验 Agent Policy 和
   HumanTakeover，不能直接绕过 Session 服务；
5. 高风险确认、Capability Token、单次消费账本和 Safe Point 既有边界保持不变。

## API 与 Web Console

- `CreateSessionRequest` 新增正式 `agentPolicy`，创建向导不再写入旧 Metadata；
- Session List/Detail 和 Agent Task 返回受控策略投影，OpenAPI 提供统一枚举；
- 环境列表、Session Detail 和 Automation 控制台显示服务端策略绑定；
- Automation 按策略给出预算和可用动作，`DISABLED`、`RESTRICTED` 或
  HumanTakeover 冲突会在提交前说明，但服务端仍独立校验；
- 创建向导明确解释 Restricted、Balanced 和 Interactive 的实际工具/预算边界。

## 验收证据

- Java 单元测试覆盖 Restricted 导航拒绝、Interactive 默认预算和 Disabled 全面拒绝；
- Web ESLint、12 个测试文件/39 项测试和 Production Build 通过；
- OpenAPI Redocly 校验通过；
- V037 N/N-1 Gate 确认迁移和新增字段可滚动升级，证据 Hash：
  `4c78f376b5a63645975bb79a3034ec0c32c768f2675de2b548d653de3414219b`；
- 完整 PostgreSQL 17 + Browser Node Integration 验证默认 `BALANCED`、正式
  `INTERACTIVE` List/Detail/Task 投影，以及 `DISABLED`、`RESTRICTED` 请求被真实保存为
  `BLOCKED` 且没有执行 Step。

## 明确未完成

1. Network/Toast/Dialog/Visual/Login/Business Entity Validator 与组合 DSL；
2. 独立无宿主权限 Agent Worker 已由进度 113 关闭；Reviewer Agent、固定 Responses
   Provider 和模型治理已由进度 114 关闭；仍缺客户大规模 Replay、模型升级 Gate 和
   目标云 Provider 准入；
3. Challenge Detection、一次性 HumanAssist、协作取消和跨 Region Workflow；
4. Purpose-bound 截图访问后续已由进度 87 关闭，基础 State 敏感分类与 Evidence
   截图不透明遮罩已由进度 88 关闭；仍缺 Site Policy、无语义视觉分类和 Recording
   帧级遮罩；
5. Extension Session 基础投影已在
   [进度 60](60-Session-Extension正式绑定与投影.md)完成；仍缺 Session Ownership、
   Group/Tags 和 Agent 大列表批量查询与 N+1 优化；
6. 目标 Linux/双 Browser Node 长稳、真实企业 IdP 和生产发布组织 Gate。
