# Application Recovery Contract 版本审批与审计闭环

> 完成日期：2026-07-29
> 状态：精确版本双人审批、Session 版本固定、审计证据、真实 UI 和滚动升级兼容已完成

## 本轮关闭的缺口

此前恢复契约支持版本 CAS、Session 绑定、声明式 Ready Gate 和有界自动恢复，但存在两
个生产风险：

1. 契约发布后无需独立审批即可绑定新 Session；
2. Session 只保存 Contract ID，契约升级后既有 Session 会隐式读取新版本。

V050 新增 PostgreSQL 权威审批记录，并把精确 `contract_version` 固化到
`session_application_bindings`。既有 Session 不会静默继承后续版本；当前契约版本与
绑定版本不一致时，校验和自动恢复均 fail-closed。

## 审批与审计规则

- 契约创建或变更后状态为 `DRAFT`；
- Tenant Admin 对精确版本发起 `REQUESTED` 审批，重复请求返回同一记录；
- 请求人与批准人必须不同，服务端强制执行双人审批；
- 审批只能作用于当前已启用且版本完全一致的契约；
- 支持 `APPROVED` 和 `REJECTED`，批准后才允许创建并绑定新 Session；
- 证据哈希绑定完整规范化契约、版本、请求人、批准人和时间；
- 创建、发布新版本、请求、拒绝同人批准、批准和驳回均写入租户哈希审计链；
- 数据迁移不会替历史契约伪造人工批准，升级后必须真实完成审批。

## 正式 API 与 UI

新增正式接口：

```text
POST /api/v1/applications/{applicationId}/recovery-contract:request-approval
POST /api/v1/applications/{applicationId}/recovery-contract-approvals/{approvalId}:approve
POST /api/v1/applications/{applicationId}/recovery-contract-approvals/{approvalId}:reject
```

“扩展与应用”恢复契约工作区现在显示 `DRAFT / PENDING / APPROVED / REJECTED` 文本状态、
精确版本、请求人与批准人、审批原因和证据哈希，并提供请求、批准和驳回操作。前端会禁用
同人批准，但真正的隔离仍由后端 RBAC 和双人规则保证。

创建 Session 的 Application Contract 选择器只显示已启用且 `APPROVED` 的契约。新
Web 连接 N−1 Control Plane 时，缺失审批字段按未批准处理，避免滚动升级期间放宽 Gate。

## 版本固定与恢复行为

- Session 创建时保存当前已批准的精确 Contract Version；
- Business Recovery 校验要求绑定版本、当前版本和批准版本三者一致；
- 自动恢复动作沿用同一精确版本 Gate；
- 契约发布新版本后，旧 Session 保持旧绑定并拒绝继续套用新配置；
- 使用新版本需要审批后创建或显式重建绑定，不存在静默策略漂移。

当前模型只保存最新契约正文和历史审批证据。因此旧版本 Session 在新版本发布后安全
拒绝恢复，而不是继续执行历史正文。若未来要求长期并行运行多个契约版本，仍需增加不可变
Contract Revision 存储和受控 Rebind/Upgrade Operation。

## 滚动升级兼容

- V050 为旧绑定确定性回填升级时的当前版本；
- N−1 Control Plane 继续插入不含 `contract_version` 的绑定时，兼容 Trigger 填入当前
  版本；
- Trigger 只补版本，不创建审批；
- OpenAPI 中新增审批投影保持响应兼容，N−1 服务缺字段时新 UI fail-closed；
- N/N−1 Gate 验证旧写入、新约束和无伪造审批。

## 验收证据

- `./gradlew -p apps/control-plane test`
- Web ESLint、13 个测试文件 / 44 项测试和 production build
- `make contracts-check`
- `python3 tests/upgrade/n-minus-one-gate.py`
- PostgreSQL 17 从空库顺序应用 V001—V050
- N−1 旧式 Session Binding 写入由 Trigger 填充精确版本，审批记录仍为 0
- `make test-integration`

完整集成实测：

```text
DRAFT
→ 未审批创建 Session 返回 RECOVERY_CONTRACT_APPROVAL_REQUIRED
→ REQUESTED 幂等重放
→ 同人批准被拒绝
→ 第二管理员 APPROVED
→ v1 Session 绑定 contract_version=1
→ 发布并独立批准 v2
→ v1 Session 校验 fail-closed
→ 新 v2 Session 执行受信 Extension 自动恢复
→ State ACK / 二次 Ready Gate / Migration COMPLETED
→ 审计链包含 RECOVERY_CONTRACT 与 RECOVERY_CONTRACT_APPROVAL
```

同一完整烟测还通过真实 Chromium 截图证据、Storage Helper、Coordinator 故障切换、
资源执行和企业治理矩阵，最终 `audit_chain_valid=true`。

## 仍未完成

1. 不可变 Contract Revision 历史正文、版本差异预览和回滚；
2. 既有 Session 的显式 Rebind/Upgrade Operation；
3. 支付、账号安全、SPA/Form 和关键业务事务的站点 Adapter/SDK 实际接入；
4. Provider/API 级账号、权限和业务实体恢复证明；
5. 真实双 Browser Node、Object Storage、网络分区和目标云长期验收。
