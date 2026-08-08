# Business Recovery Provider Evidence 可信证明闭环

> 完成日期：2026-07-30
> 状态：正式契约、独立信任边界、PostgreSQL 不可变证据、迁移 Ready Gate、只读 Web
> 账本和真实端到端验收已完成；通用 Application Adapter/Lease SDK 后续由进度 106 关闭

## 本轮关闭的缺口

DOM、Route、Target 和 Extension 状态不能证明恢复后的 Browser 仍属于正确账号、
Tenant/Workspace，拥有原权限，或指向同一业务实体。V052 为这些证明建立平台级协议：

```text
已批准的精确 Contract Revision 声明证明要求
→ 目标业务 Adapter 调用真实 Provider/API
→ APPLICATION_ADAPTER 提交当前 Context/State 的短时证明
→ Control Plane 验证身份、版本、哈希、时效与幂等
→ Business Recovery Ready Gate 决定继续、等待或人工恢复
```

平台仍不会从网页内容猜测业务语义，也不会把测试 Fixture 或前端数据当作证明。

## 契约与持久模型

Recovery Contract 新增 `requiredProviderEvidence`，支持：

- `ACCOUNT`
- `TENANT_WORKSPACE`
- `PERMISSION`
- `BUSINESS_ENTITY`

每条要求包含受控 Key、Provider ID、预期值 SHA-256 和 30—900 秒最大时效。要求进入
审批哈希、版本 Diff、不可变 Revision、历史恢复和显式 Session Rebind；旧版本
Session 继续使用其精确 Revision，不会随最新头版本漂移。

V052 新增 append-only `business_recovery_provider_evidence`：

- 精确绑定 Tenant、Session、Application、Contract Version、Context Epoch 和 State
  Version；
- 保存 `MATCH / MISMATCH / UNKNOWN`、期望/观察值哈希、Adapter Actor、Request ID、
  观察时间和过期时间；
- Provider 原始 Reference 不落库，只保存 SHA-256；
- 数据库约束最大 TTL 为 15 分钟，并以复合外键绑定不可变 Contract Revision；
- 列表由数据库侧按时间倒序限制 100 条，避免长期 Session 无界加载历史。

## 独立信任边界和正式 API

```text
GET  /api/v1/sessions/{sessionId}/business-recovery/provider-evidence
POST /api/v1/sessions/{sessionId}/business-recovery/provider-evidence
```

写接口不复用普通 Operator 权限，只接受独立 `APPLICATION_ADAPTER` 角色，并要求：

1. `Idempotency-Key`，同一 Actor/Session/Key 重放返回同一 Evidence；
2. Session 当前 Context Epoch 与 Browser State Version 精确匹配；
3. Session 绑定的已批准精确 Revision 确实声明了该 Type/Key/Provider；
4. `observedAt` 不超过未来 30 秒，也没有超出契约规定的最大时效；
5. `MATCH` 的观察哈希必须等于 Revision 中的期望哈希；
6. Tenant 隔离、稳定错误码、Request ID 和哈希审计。

生产 JWT Claim 转换和本地 Header 验收路径都显式允许该角色；普通
`TENANT_OPERATOR` 提交返回 403，避免浏览器用户自证恢复成功。

## 迁移 Gate

Provider Evidence 参与同一持久 Business Recovery Validation：

- 缺失、过期或 `UNKNOWN`：`MANUAL_RECOVERY_REQUIRED`，迁移保持
  `BUSINESS_VALIDATION`，Browser 保持运行，Agent 保持暂停；
- Account 或 Tenant/Workspace 不匹配：`ACCOUNT_MISMATCH`；
- Permission 不匹配：`PERMISSION_CHANGED`；
- Business Entity 不匹配：`STATE_CHANGED`；
- 所有要求 `MATCH`：证据 ID 进入持久 Validation Evidence，Ready Gate 才能继续。

Provider Evidence 账本修订量进入迁移校验幂等版本。新的可信证明到达后会触发新的
权威校验，而不是永久重放先前“证据缺失”的结果。

## Web Console

Recovery Contract 作者工作区可以配置 Provider 证明要求，并与后端使用相同的枚举、
标识符、SHA-256、TTL 和去重边界。

Session Detail 新增只读 `Provider Attestation Ledger`，显示：

- 类型、Key、Provider、结果和是否过期；
- Contract Version、Context Epoch、State Version 与 Evidence ID；
- Provider Reference Hash 前缀和观察时间。

Web 不提供证据写入按钮，不生成 CPU/业务状态 Mock，也不保存 Provider Reference。
组件沿用现有 React、TanStack Query、API Client、权限和设计 Token，可由 Tauri 2
复用；状态始终有文本，不只依赖颜色。

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

- Java 覆盖缺失证明 fail-closed、精确状态证明解锁 Ready、迁移等待和含 `Instant`
  幂等哈希回归；
- Web 13 个测试文件、48 项测试通过；
- OpenAPI 校验和 V052 N/N−1 Gate 通过，兼容证据 Hash 为
  `8a22324bf03f1e8bb6488cfa52bc1499787d7539b76e7a2ed6a0b1afee900235`；
- PostgreSQL 17 V001—V052 空库迁移与完整 Browser Node/Chromium Integration 通过；
- Integration 实测 Operator 403、Adapter 提交、幂等重放、跨 Tenant 隔离、证据与
  Audit 各一条、数据库 UPDATE 被不可变 Trigger 拒绝，以及迁移在证明到达后继续完成。

## 仍未完成

本轮完成的是平台协议与信任 Gate，不是某个客户业务系统的 Provider 实现。Phase 3
仍需：

- 通用 Lease Adapter/SDK 已由
  [进度 106](106-Application-Adapter真实Provider与业务Lease-SDK闭环.md)关闭；仍需在
  客户支付、账号安全、SPA/Form 和关键事务开始/结束位置实际接入；
- 配置真实 CRM、支付或 IAM Provider 凭据，并由目标 Adapter 调用其正式 API 后提交
  Evidence；
- 两个真实 Browser Node、目标 Region、网络分区、Provider 故障和长期稳定性证书；
- 独立 Business Recovery 事件流与 State/Audit/Agent Step 统一事件层。
