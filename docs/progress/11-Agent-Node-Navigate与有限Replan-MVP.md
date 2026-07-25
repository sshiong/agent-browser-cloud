# Agent Node Navigate 与有限 Replan MVP

> 状态：Phase 4 第三个里程碑已完成；受限 Navigate 与导航后只读验证闭环可用。
> 日期：2026-07-26

## 已完成

### 异步导航执行链路

含 `NAVIGATE` 的计划现在经过：

```text
Capability Verify + 单次消费
→ AGENT_INTERACTIVE Exclusive Operation
→ PostgreSQL Outbox
→ AgentNavigateCommand
→ Browser Node CDP Page.navigate
→ Full Browser State
→ BrowserStateUpdated
→ Context / Operation / State / Domain Verify
→ 后续只读 Tool
→ Operation + Task Commit
```

- Execute API 首次返回 `RUNNING`，不会在 HTTP/数据库事务中等待浏览器网络 I/O。
- `AgentNavigateCommand` 只携带 Session、Task、Step、URL 与 Base State Version；
  签名 Capability Bearer 始终留在 Control Plane。
- Node 验证 URL 仅为 HTTP/HTTPS、有 Host、无 UserInfo，执行 CDP 导航后强制 Full State。
- 导航失败通过 `AgentNavigationFailedEvent` 返回稳定错误码，不回传底层错误正文。
- 成功状态复用现有 `BrowserStateUpdated`，继续获得 Node Journal 重投、Inbox 去重、
  Coordinator Term、Context Epoch、Operation Epoch 与 Event Sequence 保护。

### 结果验证与最小化

Control Plane 只有在以下条件全部满足时才提交导航 Step：

1. 回调属于当前唯一的 Agent Operation；
2. Task 仍为 `RUNNING` 且 Operation ID 匹配；
3. State Version 大于下发命令时的 Base State Version；
4. State Quality 为 `COMPLETE` 或 `DEPTH_LIMITED`；
5. 最终 URL Domain 与用户授权的精确 Domain 相同。

Navigate Result 保存 Requested URL、Final URL、Domain、State Version 与 Target Revision；
两个 URL 均移除 UserInfo、Query 和 Fragment，并生成 SHA-256 Result Hash。

### 有限 Replan

- V007 新增 `replan_count` 与 `pending_state_version`。
- 首次状态未前进、不可执行或最终域名不匹配时，只在 Plan `replanBudget` 内请求
  一次绑定同一 Operation 的 Full Resync。
- Resync 不是新的浏览器写动作，不重新使用 Navigate Capability。
- 预算耗尽后停止，Operation 进入 `ABORTED`，Task 进入 `FAILED`。
- 域名授权不会因重定向或网页内容而扩大；持续越界只会失败。

当前实现属于“导航结果观测 Replan”，不是通用 Planner 重新生成计划。后者仍列为
Phase 4 后续缺口。

### Web Console

- 含 Navigate 的计划不再 Disabled，统一使用“执行并验证安全计划”。
- 顶部状态为 `Verified read tools · Node Navigate live`。
- Inspector 展示 Replan Budget 与已用次数。
- 完成后按顺序展示 Navigate、Get Current State、Get URL、Get Page Summary
  四个 `VERIFIED` Result。

## 验收证据

```bash
make ci
make test-integration
make test-e2e
```

已验证：

- V007 在空 PostgreSQL 上与 V001—V006 连续迁移成功；
- Navigate Capability 与三个只读 Capability 均恰好消费一次；
- Outbox 将正式命令投递到真实 Browser Node；
- 假 Chromium CDP 收到 `Page.navigate`，Node 回传新 Full State；
- Navigate Task 从 `PLANNED → RUNNING → COMPLETED`；
- 四个 Result 全部为 `VERIFIED`，Agent Operation 为 `COMMITTED`；
- Requested URL `https://example.test/agent-start` 与 Final URL
  `https://example.test/runtime` 的域名验证通过；
- 单元测试验证重定向域名不匹配只重试一次，随后因预算耗尽 Abort；
- Web E2E 从真实 UI 创建并执行含 Navigate 的计划，浏览器 Console 无错误。

视觉验收截图：

- `/tmp/agent-browser-cloud-session-flow-automation.png`

## 仍未完成

| 缺口 | 说明 |
|---|---|
| `click_target` | 需绑定 Target Ref/Revision、可见/可用状态、坐标新鲜度和点击后验证 |
| `type_text` | 需实现敏感字段分类、Password/OTP 禁入、输入前确认与 Input Ledger |
| `scroll` / `wait_for` | 需实现有界等待、Deadline、稳定性条件和取消 |
| `request_human_takeover` Tool | 平台 HumanTakeover 已有，但 Agent Tool 与安全交接尚未接入 |
| 通用 Action Validation | DOM/A11y/Network/Toast/Dialog/Visual/Login/Business Entity 表达式未完成 |
| 通用 Durable/Replan | 尚无持久 Step Lease、Heartbeat、Timeout Recovery 和 Planner 重新生成 |
| Human Confirmation | R3—R5 仍安全阻断，平台确认事件与完整展示未实现 |

## 下一步

第四个 Phase 4 里程碑实现 `click_target` 与 `type_text` 的最小自建表单流程。必须先完成
Target Revision/State Version Gate、Input Sequence、敏感输入阻断和执行后 Full State
验证，再开放 UI。
