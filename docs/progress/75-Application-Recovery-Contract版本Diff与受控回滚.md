# Application Recovery Contract 版本 Diff 与受控回滚

> 完成日期：2026-07-30
> 状态：不可变历史列表、服务端字段 Diff、受控恢复、真实 Web、幂等/审计与端到端验收已完成

## 本轮关闭的缺口

V051 已保存完整不可变 Contract Revision，并允许 Session 显式 Rebind，但管理员仍无法
在正式产品中查看所有精确版本、比较策略变化或安全恢复已知良好版本。

本轮补齐的“回滚”不会修改历史行，也不会把运行中的 Session 静默切换到旧策略。其
语义是：

```text
选择已批准历史版本 v1
→ expectedCurrentVersion CAS 校验当前头 v2
→ 复制 v1 正文并发布为新头 v3
→ v3 状态为 DRAFT
→ 重新经过双人审批
→ 如需升级既有 Session，再执行显式 Rebind
```

## 正式 API 与服务端约束

新增接口：

```text
GET  /api/v1/applications/{applicationId}/recovery-contract/revisions
GET  /api/v1/applications/{applicationId}/recovery-contract/revisions/{version}/diff?compareToVersion=N
POST /api/v1/applications/{applicationId}/recovery-contract:restore
```

服务端保证：

1. Revision 列表来自 PostgreSQL 不可变快照，并附带精确版本的审批状态；
2. Diff 在服务端比较两个同租户、同 Application、同 Contract 的精确快照，只返回实际
   变化字段；
3. Restore 仅管理员可执行，必须携带 `Idempotency-Key`、当前版本 CAS 和恢复原因；
4. 来源必须严格早于当前头，并且该精确版本已通过双人审批；
5. 恢复复用 V051 Trigger 生成新的不可变 Revision，来源和所有旧版本不发生 UPDATE；
6. 新版本没有继承旧审批，固定返回 DRAFT，必须重新申请并由第二位管理员批准；
7. 租户哈希审计记录来源版本、旧头版本、新版本、操作者、Request ID 和脱敏原因；
8. 幂等重放从已提交的新 Revision 返回同一版本，不会重复发布；
9. Session Binding 表不参与 Restore 事务，已运行 Session 继续使用原精确版本。

## Web Console

Application Recovery Contract 作者工作区新增：

- 按版本倒序的不可变快照索引；
- CURRENT、DRAFT、PENDING、APPROVED、REJECTED 文本状态；
- 服务端字段 Diff，逐项显示旧值、新值和字段名，不只依赖颜色；
- 仅管理员可见的恢复原因和二次确认；
- 未批准来源、未保存编辑或无权限时明确禁用；
- 明确提示“生成新 DRAFT、现有 Session 不自动切换”；
- 失败时展示后端错误及 Request ID。

组件继续复用现有 React、TanStack Query、API Client、权限和设计 Token，可被 Tauri 2
桌面容器直接复用。视觉保持 Neo-Industrial Observatory：紧凑信息层级、1px 边界、
等宽版本标识和克制的状态色。

## 验收证据

已通过：

```text
./gradlew -p apps/control-plane spotlessApply test
pnpm -C apps/web-console test
pnpm -C apps/web-console lint
pnpm -C apps/web-console format:check
pnpm -C apps/web-console build
make contracts-check
python3 tests/upgrade/n-minus-one-gate.py
make test-integration
make test-e2e
```

关键结果：

- Java 全量测试通过，新增历史、Diff、受控恢复、幂等重放和 Session Binding 不变单测；
- Web 13 个测试文件、47 项测试通过；
- OpenAPI 校验和 N−1 Gate 通过；
- PostgreSQL 17 V001—V051 空库迁移与完整 Browser Node Integration 通过；
- Integration 实测 v1/v2 历史、字段 Diff、v1→v3 DRAFT、同幂等键重放，以及两个
  既有 Session 分别继续固定在 v1/v2；
- 管理员真实浏览器完成历史选择、Diff、两步恢复和 v3 DRAFT 展示；Viewer RBAC 回归
  同时通过。

## 仍未完成

Contract 平台能力已经具备版本作者、双人审批、不可变历史、Diff、受控恢复和显式
Session Rebind。Phase 3 仍缺的是目标业务实际接入：

- 支付、账号安全、SPA 表单和关键事务的 Lease Adapter/自动埋点；
- Provider/API 级账号、权限和业务实体恢复证明；
- 真实双 Browser Node、目标 Region、网络分区和长期运行 Gate。
