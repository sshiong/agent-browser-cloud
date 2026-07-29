# Application Recovery Contract 不可变历史与 Session Rebind

> 完成日期：2026-07-29
> 状态：不可变精确版本正文、既有 Session 显式升级、Operation/幂等/审计和真实 Web 已完成

## 本轮关闭的缺口

进度 73 已完成精确版本双人审批和 Session 版本固定，但正文仍只保存在一条可变的
`application_recovery_contracts` 当前行。发布 v2 后，绑定 v1 的 Session 只能
fail-closed，无法继续按获批的 v1 正文恢复，也没有受控升级入口。

V051 将“当前发布头”和“运行时版本”分开：

- `application_recovery_contracts` 继续作为当前编辑/发布头；
- `application_recovery_contract_revisions` 保存每个版本的完整不可变正文；
- Session 校验和自动恢复只读取绑定的精确 Revision；
- 当前发布头的 `enabled=false` 仍是全局 Kill Switch，不允许旧版本绕过停用；
- 发布新版本不会再使已批准并绑定旧版本的 Session 无故失效。

## PostgreSQL 不可变版本

`application_recovery_contract_revisions` 使用
`(contract_id, contract_version)` 主键，保存 Origin、Route、Target、Extension、
Depth Limited、恢复动作、预算、启用状态和发布时间。

数据库层保证：

1. 当前 Contract INSERT/UPDATE 后由 Trigger 追加 Revision，兼容 N−1 Control Plane；
2. 相同版本使用 `ON CONFLICT DO NOTHING`，不会覆盖历史正文；
3. Revision UPDATE/DELETE 由不可变 Trigger 拒绝；
4. 新 Session Binding 和 Approval 使用精确版本复合外键；
5. 外键以 `NOT VALID` 接入，保留升级前无法重建正文的历史 Binding，但所有新写入立即
   受约束；
6. V051 升级时只能回填当时的当前版本，不伪造已丢失的更早正文。此类旧 Binding 保持
   fail-closed，可由管理员显式 Rebind 到当前批准版本。

## 显式 Session Rebind Operation

新增正式接口：

```text
GET  /api/v1/sessions/{sessionId}/application-binding
POST /api/v1/sessions/{sessionId}/application-binding:rebind
```

已存在但未绑定 Application 的 Session，GET Binding 返回 `204 No Content`；尚未形成
Business Recovery Verdict 时同样返回 204。真正不存在或跨租户的 Session 仍返回
404，避免把正常空状态记录成浏览器控制台错误。

Rebind 请求必须携带：

```json
{
  "expectedCurrentVersion": 1,
  "targetContractVersion": 2
}
```

服务端执行规则：

- 仅 Tenant/Security/Platform Admin 可调用；
- 必须提供 `Idempotency-Key`，同请求重放返回同一个 Operation；
- 锁定 Session 主行和 Binding 行，使用 `expectedCurrentVersion` 做 CAS；
- Session 存在活跃 Exclusive Operation 时拒绝升级；
- 目标只能是当前已启用、已双人批准且已落 Revision 的发布版本；
- PostgreSQL 同一事务写入 `APPLICATION_BINDING`、`COMMITTED` Exclusive Operation、
  Binding 新版本、Rebind 历史和租户哈希审计；
- 前端不能直接改数据库版本，也不能静默跟随最新 Contract。

## Web Console

Session Detail 的 Business Recovery 卡片现在显示：

- 当前绑定 Application 和 Contract Version；
- 当前发布头版本、审批状态和全局启用状态；
- 有批准的新版本时显示 `vN → vN+1` 升级入口；
- 非管理员、存在活跃 Operation 或无可升级版本时禁用/隐藏写操作；
- Rebind 完成后刷新 Binding、Session 和最新验证结果，用户可对新版本重新执行真实
  Ready Gate。

Web 仍复用同一 React/API/权限逻辑，可直接被现有 Tauri 2 容器复用。

## 真实验收证据

已通过：

```text
./gradlew -p apps/control-plane spotlessApply test
pnpm --dir apps/web-console test
pnpm --dir apps/web-console build
pnpm --dir apps/web-console lint
make contracts-check
python3 tests/upgrade/n-minus-one-gate.py
./tests/integration/smoke.sh
./tests/e2e/run.sh
```

结果：

- Java 全量测试通过；
- Web 13 个测试文件、46 项测试通过，production build 和 ESLint 通过；
- OpenAPI/Proto/JSON Schema 检查通过；
- PostgreSQL 17 空库顺序应用 V001—V051；
- 集成实测 v1 Session 在 v2 发布并批准后仍使用不可变 v1 正文返回 `READY`；
- GET Binding 返回 `v1 / head v2 / APPROVED / upgradeAvailable=true`；
- Rebind `v1 → v2` 返回真实 `APPLICATION_BINDING / COMMITTED` Operation；
- 相同幂等键重放返回同一个 Operation，不重复写 Rebind 历史；
- 直接 UPDATE v1 Revision 被数据库 Trigger 拒绝；
- 完整 PostgreSQL/Browser Node 生命周期、自动恢复、资源执行和审计链继续通过，
  `public_tables=66`、`audit_chain_valid=true`、`audit_events=127`。
- 管理员 Web E2E 已真实通过 AUTO 创建、M2/Remote Desktop 能力调度、启动、noVNC
  像素与输入、异常断线安全释放、Agent 和终止；Viewer E2E 已验证分组/标签可读但
  管理写操作不可见。

## 仍未完成

1. Contract Revision 列表、版本差异预览和受控回滚 UI/API；
2. 支付、账号安全、SPA/Form 和关键业务事务的站点 Adapter/SDK 实际接入；
3. Provider/API 级账号、权限和业务实体恢复证明；
4. 真实双 Browser Node、Object Storage、网络分区和目标云长期验收；
5. 目标环境中审批、Rebind、迁移和回滚的组织 Runbook 与签字。
