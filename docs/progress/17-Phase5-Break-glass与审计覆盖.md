# Phase 5：Break-glass 与审计覆盖

> 状态：双人审批治理主链路已完成；安全调试数据面和会话录像仍待实现。

## 已完成

- V013 `break_glass_requests`：
  - Tenant 隔离；
  - 工单、原因、资源、Scope 和 5—60 分钟有效期；
  - `REQUESTED → ACTIVE → REVOKED/EXPIRED`；
  - Reject 和事后 Review；
  - 数据库约束禁止申请人和审批人为同一 Actor。
- Security Admin API：
  - Create/List；
  - Approve/Reject/Revoke/Review；
  - 跨租户请求返回 404，不暴露目标是否存在。
- 自动撤销：
  - 定时扫描过期 Active Grant；
  - 授权检查时再次执行过期 Gate；
  - 过期后不能继续授权。
- 防篡改审计：
  - 申请、审批、自批拒绝、访问检查、撤销、过期和 Review 全部写入
    `ADMIN_ACCESS`；
  - 被拒绝且业务事务回滚的自批尝试使用独立审计事务保存。
- Agent 与 Profile 审计补齐：
  - Prompt Injection/Plan Validation 写入 `SECURITY_EVENT`；
  - 高风险确认和 Human Handoff 写入 `HUMAN_AUTHORIZATION`；
  - 技术恢复成功写入 `PROFILE_RESTORE`。
- Web Console 安全中心：
  - 真实申请表单；
  - Request/Active/Terminal 状态；
  - 第二管理员审批、拒绝、撤销和 Review 操作；
  - 本人申请明确显示“等待另一位管理员”。

## 已验证不变量

| 不变量 | 证据 |
|---|---|
| 申请人不能自批 | Java 单元测试 + 集成 HTTP 409 |
| 第二 Security Admin 可激活 | 集成 API 返回 `ACTIVE` 和 64 位 Evidence Hash |
| Grant 只授权申请人、精确资源和 Scope | Java `authorize` 单元测试 |
| 过期自动撤销 | Scanner 单元测试 |
| 审批到达时已过期不会因 409 回滚撤销 | Java 单元测试 + 集成持久状态 `EXPIRED` |
| 跨租户不可见 | 集成 HTTP 404 |
| 终止后可事后 Review | 集成 API `reviewedAt` |
| 审计链包含新增类型 | 集成测试验证 `ADMIN_ACCESS`、`SECURITY_EVENT`、`HUMAN_AUTHORIZATION`、`PROFILE_RESTORE` |
| UI 可申请且进入双人审批 | 真实浏览器 E2E，且无 Console/HTTP 异常 |

## Phase 5 审计类型覆盖

开发计划 11.3 要求的八类事件：

| 类型 | 状态 |
|---|---|
| Session Context Commit | 已完成 |
| Operation Transition | 已完成 |
| Human Authorization | 已完成 |
| Admin Access | 已完成 |
| Security Event | 已完成 |
| Runtime Release | 已完成 |
| Key Rotation | 未完成 |
| Profile Restore | 已完成 |

## 尚未完成

1. Break-glass 当前治理“授权事实”，但尚未接入独立 Secure Debug Worker、敏感 State
   数据面和强制会话录像。
2. Key Rotation 仍需正式领域模型、双人审批和审计事件。
3. Security Admin 前端当前依赖现有 OIDC/本地身份；生产需要用户会话与权限查询 API，
   不能依赖前端推测 JWT Subject。
4. Phase 5 Exit Gate 仍受 Node Helper OS 级隔离、完整故障矩阵和真实制品验签阻塞。
