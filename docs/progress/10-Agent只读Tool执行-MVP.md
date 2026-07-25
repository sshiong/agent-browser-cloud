# Agent 只读 Tool 执行 MVP

> 状态：Phase 4 第二个里程碑已完成；只读执行闭环可用，浏览器写操作仍保持关闭。
> 日期：2026-07-26

## 已完成

### 单 Executor 与排他 Operation

- 新增 `POST /api/v1/agent-tasks/{taskId}:execute`，使用独立 Idempotency-Key。
- Executor 只接受 `PLANNED` 且未过期的任务，并重新校验 Session Tenant、
  `RUNNING` 状态、动作预算和 Tool 支持范围。
- 执行时创建 Owner=`AGENT`、Mode=`AGENT_INTERACTIVE`、Actor=Task ID 的排他
  Operation；Operation 经过 `PREPARING → EXECUTING → COMPLETING → COMMITTED`。
- 与 HumanTakeover、Recovery 或其他活跃写 Operation 冲突时拒绝执行。
- Task 保存 Operation ID、Current Step、结构化 Result、Last Error 和完成时间；
  Tool 失败会 `ABORTED` Operation，并将任务置为 `FAILED`。

### 只读 Tool Service

已真实实现：

- `get_current_state`
- `get_url`
- `get_page_summary`

每次调用均强制：

1. 从 PostgreSQL/Browser State Repository 读取权威状态；
2. 校验 Tenant、Session、Context Epoch；
3. 仅接受 `COMPLETE` / `DEPTH_LIMITED`；
4. 对 Capability Token 重新验签；
5. 精确校验 Intent、Task Operation、Tool、Action、Domain、Data Scope、Risk 与 Expiry；
6. 向 `tool_capability_uses` 原子写入 Token ID，重复使用返回
   `CAPABILITY_TOKEN_REPLAYED`；
7. 生成 Result SHA-256，并按 Step Verification 标记 `VERIFIED`。

### 输出最小化

- `get_current_state` 只返回 Version、Revision、Quality、Hash 和 Target Count。
- `get_url` 移除 UserInfo、Query 与 Fragment，只返回安全 URL 和精确 Domain。
- `get_page_summary` 只返回安全 URL、脱敏 Title、State Quality、目标计数、
  Role Count 和最多 20 个可见可用 Target Name。
- Title/Target Name 再次经过 Secret、Email 和 Phone Redaction。

### Web Console

- `PLANNED` 且不含 Navigate 的任务显示“执行并验证只读计划”。
- 含 Navigate 的任务显示“等待 Navigate Executor”，按钮保持 Disabled。
- 完成后展示 Task `COMPLETED`、每步 `VERIFIED`、结构化脱敏 Output 和 Result Hash。
- 顶部状态明确显示 `Read tools live · Navigate pending`。

## 验收证据

```bash
./gradlew -p apps/control-plane test
pnpm --dir apps/web-console build
pnpm --dir apps/web-console test
make contracts-check
make test-integration
make test-e2e
```

真实集成验证：

- V006 迁移成功；
- 三步只读计划进入 `COMPLETED`；
- 三枚 Capability Token 在数据库中各消费一次；
- `AGENT_INTERACTIVE` Operation 进入 `COMMITTED`；
- 重复 Execute 返回同一 Task/Operation/Result Hash，不重复消费 Token；
- URL Result 为 `https://example.test/runtime`，没有 Query/Fragment；
- Web Console 真实点击执行并展示三个 Verified Result；
- 浏览器 Console 无错误。

## 仍未完成

| 缺口 | 下一步 |
|---|---|
| Navigate | 增加正式 Node Command/Event，校验最终 URL、Context Epoch、State Version 与域名 |
| Click/Type/Scroll/Wait | 绑定 Target Ref、Target Revision、Input Sequence、坐标新鲜度和执行后 State |
| 异步 Durable Executor | 当前只读执行在单个数据库事务中同步完成；写操作前需持久 Step Lease、Heartbeat、Timeout 和 Recovery |
| Replan | Budget 已进入 Plan，但尚无失败分类、预算递减、重新规划和熔断 |
| Human Confirmation | R3—R5 仍安全阻断；平台确认事件和完整展示尚未实现 |
| 更强 Result Validation | 当前验证 URL/State/Summary Schema；DOM/A11y/Network/Toast/Dialog/Business Entity 待开发 |

## 下一步

第三个里程碑接入 Node `navigate`，使导航计划经过：

```text
Capability Verify
→ Exclusive Operation
→ Node Command Outbox
→ CDP Navigate
→ Full State
→ Final Domain / State Version Validation
→ Step Commit
```
