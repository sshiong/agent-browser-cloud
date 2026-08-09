# Agent 人工治理与控制台联调

> 状态：Phase 4 Human Confirmation 与 Agent→Human Takeover 缺口已关闭。
> 日期：2026-07-26

## 已完成

### 平台高风险确认

V009 增加持久确认状态和审计字段：

```text
PLANNED
→ AWAITING_CONFIRMATION
→ 平台 UI / API Approve 或 Reject
→ PLANNED / FAILED
```

- R3—R5 任务不再只有笼统阻断，而是进入有期限的确认 Gate。
- 确认视图展示具体 Intent、风险、授权域、数据范围、计划步骤、外部内容污染状态和
  不可逆影响提示。
- Approve/Reject 必须来自平台 API，并记录 Actor、时间、决定和 Evidence Hash。
- 网页文字、Prompt 或 Agent 输出无法生成确认事件。
- 过期确认由 Recovery Scheduler Fail Closed，不能执行旧计划。
- API 使用 `X-Actor-Id` 只作为本地开发身份；生产仍必须由 OIDC Principal 派生。

### Agent 请求人工接管

`request_human_takeover` 已作为正式 Agent Tool 接入：

```text
Agent Plan
→ Capability Verify + 单次消费
→ WAITING_FOR_HUMAN
→ 平台 Actor Accept / Reject
→ HumanTakeover Operation
→ Node 输入释放屏障
→ 人工接管 / Task Commit
```

- Handoff 必须是计划最后一步；Agent 在请求后停止继续动作。
- Request 保存过期时间、请求原因和证据 Hash。
- 只有平台 Actor 接受后才创建真实 `HUMAN_TAKEOVER` Operation。
- 接受、拒绝和过期均形成持久状态；错误 Actor 或旧请求不能改变结果。
- 接受后沿用既有 noVNC、单客户端 Ticket、All-keys-up、结束 State Resync 与
  Operation 所有权闭环。

### Web Console 优化

Automation 页面已从只读计划查看器升级为“Agent 执行控制台”：

- 左侧任务队列显示风险、Revision、Step 数和运行状态；
- 中间 Inspector 展示 Plan、Capability Handle、Durable Step、Deadline、Lease、
  Replan、结构化 Result 和 Prompt Security Event；
- 右侧 Builder 绑定权威 Browser State，提供 Click、Type、Scroll、Wait、
  Human Takeover 动作编辑；
- 敏感目标、不可见/不可用目标和错误角色在 UI 层提前禁用，服务端仍再次校验；
- 高风险任务显示独立确认 Gate；
- Handoff 显示接受/拒绝 Gate；
- Loading、Empty、Error、活动任务轮询和响应式三栏布局与现有深色运维设计统一。

视觉验收截图：

- `/tmp/agent-browser-cloud-session-flow-automation.png`

## 前后端与真实运行验收

`make test-e2e` 自动启动 PostgreSQL、Redis、Control Plane、Browser Node、假 Chromium
CDP/VNC 与 Vite，并从真实浏览器完成：

1. Navigate 与只读 Tool；
2. Click、Type、Scroll、Wait；
3. R4 风险任务进入 `AWAITING_CONFIRMATION`；
4. 平台 Approve 后执行并完成；
5. Agent 请求 Human Takeover；
6. 平台接受后进入真实 `HUMAN_TAKEOVER / EXECUTING`；
7. 人工释放并完成 State Resync；
8. 页面无 Console Error、无未预期 HTTP 4xx/5xx。

## Phase 4 退出 Gate 复核

| 退出 Gate | 证据 | 状态 |
|---|---|---|
| 自建表单流程 | Click + 公开 Type + Scroll + Stable Wait E2E | 已通过 |
| 未授权域名不可访问 | 精确 Domain Capability 与最终 URL 校验 | 已通过 |
| 网页不能扩大权限 | Untrusted Context、Injection Event、Tool Token 单次账本 | 已通过 |
| 高风险动作需要确认 | 平台确认状态、Actor 与 Evidence Hash | 已通过 |
| Planner 有 Replan 上限 | 持久 Budget/Count，耗尽 Abort | 已通过 |
| Action 经过结果验证 | 每 Step State/Target/Domain/Result Verification | 已通过 |
| Prompt Injection 测试 | 隔离、脱敏和 E2E 安全事件 | 已通过 |

结论：按《开发流程与实施计划》的 Phase 4 MVP 口径，退出 Gate 已关闭。该结论不等同于
V16 全量生产就绪。

## 仍未完成

| 后续项 | 所属范围 |
|---|---|
| OIDC Principal、RBAC、管理员 MFA | Phase 5 / 生产安全 |
| Human Authorization 长期审计、Legal Hold、删除证明 | Phase 5—7 / 治理 |
| 高级 Action Validation DSL | MVP-B / 可靠 Agent |
| Reviewer Agent 与真实模型治理 | 后续已由进度 114 以 PostgreSQL 权威队列、独立 Worker、固定 Responses Provider 和版本/数据/Token/成本审计关闭；客户大规模 Replay 与目标云模型准入仍属生产 Gate |
| Challenge Detection / HumanAssist 单击授权 | MVP-B，不能与通用 Takeover 混淆 |
| 跨 Region Workflow、复杂 Compensation、全局 Scheduler | Durable Workflow 后续阶段 |
