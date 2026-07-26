# Agent 结构化动作与持久执行闭环

> 状态：Phase 4 动作执行缺口已关闭；Phase 5 Durable Workflow Stage A 的 Agent 切片已完成。
> 日期：2026-07-26

## 已完成

### 首批写动作 Tool

首版 Tool API 现已真实支持：

- `click_target`
- `type_text`
- `scroll`
- `wait_for`

动作从 Web Console 创建时绑定当前权威 Browser State，不接受自由选择器或任意 CDP：

```text
Current State / Target Revision
→ Plan + Capability
→ Tool Service 再验签并单次消费
→ PostgreSQL Outbox
→ AgentActionCommand
→ Browser Node Target Registry / Input Sandbox
→ BrowserStateUpdated 或 AgentActionFailed
→ State / Domain / Step Result Verify
→ Commit / 有限 Replan / Abort
```

- Click/Type 必须精确匹配 `target_ref + target_revision`。
- Node 再次校验目标存在、可见、可用且有有效坐标；过期引用 Fail Closed。
- Type 只允许公开文本输入目标；Password、OTP 等敏感目标不进入 Agent Context，
  目标名称也不会出现在 State API。
- Scroll 只接受有界垂直位移。
- Wait 支持 `STATE_CHANGED`、`STATE_STABLE` 和 `TARGET_PRESENT`，超时范围为
  100—10,000 ms；`STATE_STABLE` 以连续两次页面内容指纹一致为准。
- 所有成功动作都回传新的 Browser State，由 Control Plane 验证版本前进和状态质量。

### 敏感载荷保护

- `type_text` 明文不写入 Plan、Task View、Outbox 或日志。
- Control Plane 使用 AES-256-GCM 加密动作载荷，AAD 绑定 Tenant、Task 和 Step。
- 只有 Outbox Dispatcher 在实际投递 Node 前短暂解密。
- 生产配置拒绝默认本地 Secret；篡改 Ciphertext 或 AAD 会被拒绝。
- 单元测试覆盖密文不可见、AAD 绑定、篡改拒绝和生产 Secret Gate。

### 持久 Step Executor

V008 为 Agent Task 增加：

- Pending Step / Tool；
- Base State Version / Content Hash；
- Step Deadline；
- Executor Lease；
- Step Result Checkpoint；
- Replan Reason / Count。

执行语义：

1. 每个同步 Step 完成后单独保存 Checkpoint。
2. Node 写动作在下发前保存 Pending Step、Base State 和 Deadline。
3. 回调必须匹配当前 Task、Operation、Step、Context、State Version。
4. Executor Lease 过期后可从数据库恢复，但不会自动重放 Click/Type 等写命令。
5. Step Deadline 由 Scheduler 扫描，超时后 Fail Closed 并释放 Operation。
6. 迟到回调、重复回调和旧 State 不得提交结果。
7. 结果不确定时只允许请求 Full State Resync；预算耗尽后 Abort，不盲目重做写动作。

这关闭了此前“仅进程内连续执行、重启可能卡死或重复写动作”的缺口。

### Target Revision 语义修复

- `state_version` 仍随每次采集递增。
- `target_revision` 只在 URL、交互目标集合实际变化或显式 Full Resync 时递增。
- Click/Type 后若目标集合未变化，后续计划动作可继续使用同一 Revision。
- DOM/URL 真的变化时旧引用立即失效，后续动作必须重新获取状态。

真实 E2E 在此处发现并修复了“每个动作都强制 Full Resync，导致同一计划第二个
Target 永远过期”的问题。

## 验收证据

```bash
cargo test -p node-agent
make ci
make test-integration
make test-e2e
```

真实链路已验证：

1. Web Console 从 Browser State 选择按钮和公开文本框；
2. 创建 Click → Type → Scroll → Wait 的结构化计划；
3. API View 和数据库证据不包含输入明文；
4. 四个写动作经 Outbox 到真实 Browser Node；
5. Input Sandbox 收到受控鼠标、键盘和 `Input.insertText`；
6. Wait 以稳定页面指纹完成；
7. 四个动作均生成 Verified Result，Task 和 Operation 最终提交；
8. 浏览器 Console 和 HTTP 监控没有错误。

## 本里程碑关闭的旧缺口

| 原缺口 | 当前结论 |
|---|---|
| `click_target` | 已关闭 |
| `type_text` | 已关闭，敏感目标与载荷保护已验证 |
| `scroll` / `wait_for` | 已关闭，有界输入与 Deadline 已验证 |
| 通用 Durable/Replan | Agent Step 的持久 Lease、Deadline、恢复和有限 Resync Replan 已关闭 |
| Action 执行后验证 | 对首批 Tool 的 State、Target、Domain、Result 验证已关闭 |

## 仍未完成

这些属于 V16 后续增强，不再是 Phase 4 首批 Tool 的阻塞项：

| 缺口 | 说明 |
|---|---|
| 高级 Validation DSL | 尚未实现 Network、Toast、Dialog、Visual、Login、Business Entity 以及 All/Any/Sequence/Negative 组合表达式 |
| 独立 Agent Worker Sandbox | 当前为 Control Plane 内受限规则 Planner/Executor，尚未拆成无宿主权限的独立 Worker |
| 通用 Workflow DeadLetter | Agent Deadline 能终止并恢复活性，但跨领域 DLQ、复杂 Compensation 和全局 Scheduler 尚未实现 |
| 生产级取消语义 | 尚无对已下发 Node 动作的协作取消协议；当前以 Deadline、Operation 抢占和 Fail Closed 为主 |
