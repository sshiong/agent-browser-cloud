# 应用业务安全点 Lease 闭环

> 日期：2026-07-28
> 状态：通用持久 Lease、Safe Point、SSE、迁移并发屏障、声明式业务恢复规则和有界
> 低风险动作已完成；目标业务 Adapter、Provider 级证明与 Extension 重启动作仍待接入

## 为什么需要 Lease

CDP 能观察网络请求、下载和导航，但无法可靠判断“这是支付提交”“这是账号安全操作”
或“这笔业务事务是否已经提交”。这些语义必须由了解业务的应用 Adapter 声明，不能由
前端曲线、固定定时器或通用浏览器启发式伪造。

本轮提供一个短期、Owner 绑定、默认 fail-closed 的协议：

```text
Acquire → 业务操作 → Renew（需要时）→ Commit/Rollback 已知 → Release
```

这些枚举表示“可信 Adapter 可以显式声明的阻塞类型”，不是平台已经能自动检测的网页
业务语义：

- `FILE_TRANSFER`
- `FORM_SUBMISSION`
- `PAYMENT_OR_SECURITY`
- `CRITICAL_TRANSACTION`
- `BUSINESS_RECOVERY_UNKNOWN`

CDP 自动覆盖网络层 Upload/Download 与 Navigation Form；Lease 覆盖 CDP 无法判定的
SPA/API/业务提交。两类 Blocker 独立聚合，不做可能丢失语义的前端去重：任一仍然活跃，
迁移和休眠都保持阻塞。

## 已完成

### PostgreSQL 权威状态

- V029 新增 `session_safety_leases` 与 append-only
  `session_safety_lease_events`。
- Lease 绑定 Tenant、Session、Context Epoch 和 Actor Owner，TTL 限制为 5—300 秒；
  状态为 `ACTIVE / RELEASED / EXPIRED`。
- Acquire、Renew、Release 均要求 `Idempotency-Key`。相同请求重放返回同一 Lease/
  Event；幂等键复用到不同请求继续返回冲突。
- 1 秒过期扫描使用 `FOR UPDATE SKIP LOCKED`，多实例只会由一个实例提交 EXPIRED；
  自然过期也会产生可审计、可恢复的持久事件。

### Safe Point 与并发屏障

- Aggregator 只读取当前 Context Epoch、ACTIVE 且尚未过期的 Lease；旧 Runtime 的
  Lease 不会阻塞新 Runtime。
- Blocker 包含业务信号类型、`APPLICATION_SAFETY_LEASE` 来源、Reason Code、Lease ID、
  更新时间和过期时间。
- Acquire/Renew/Release 与迁移请求、资源策略自动休眠最终派发都锁定同一 Session 行。
  因此迁移不会在读取 SAFE 后与新业务事务并发穿越。
- Session 已进入 Quiesce/Snapshot/Hibernate/Recovery/Proxy Transition/
  Extension Maintenance/Termination 时拒绝新 Lease，避免生命周期操作中途开始业务写。
- Owner 不匹配按 Not Found 处理，避免泄露其他应用 Adapter 的 Lease。
- 诊断列表按最新时间线索引读取，单次最多返回 100 条，并单独返回总数，不随长期
  Session 的历史记录无界扩大响应。

### API 与 Web 更新

```text
POST /api/v1/sessions/{id}/safety-leases
GET  /api/v1/sessions/{id}/safety-leases
PUT  /api/v1/sessions/{id}/safety-leases/{leaseId}
POST /api/v1/sessions/{id}/safety-leases/{leaseId}:release
```

- Web/Tauri 共用 API Client 和类型，不使用 `localStorage` 或生产 Mock。
- Lease Event 使用 V026 的每 Session 事务游标进入
  `SAFETY_LEASE_EVENT`，支持实时 SSE、`Last-Event-ID` 和断点重放。
- Session 详情收到 Lease 事件后重新读取权威 Safe Point；Blocker TTL 到期时也会按
  `expiresAt` 触发刷新，不会长期显示已经失效的阻塞项。
- OpenAPI、Java 校验和前端类型保持同一五类 Signal、TTL 和 ID 约束。

## 验证

- Control Plane 全量单测通过，新增覆盖：
  - Acquire 持久化 Owner/Context 与 ACQUIRED Event；
  - 幂等 Acquire 不生成重复事件；
  - 非 Owner Renew 返回 Not Found；
  - 到期转为 EXPIRED 并追加事件；
  - HIBERNATE 等生命周期 Operation 拒绝新业务 Lease；
  - Active Payment Lease 真实阻塞 Safe Point。
- `SessionMigrationApplicationServiceTest.
  automaticHibernateLocksSessionBeforeFinalSafePointAssessmentAndDispatch` 验证休眠必须按
  Session `PESSIMISTIC_WRITE` 锁 → Safe Point → Operation 派发排序；
  `SessionSafetyLeaseApplicationServiceTest.
  acquirePersistsOwnerBoundCurrentContextLeaseAndAuditEvent` 验证 Lease 使用同一锁并在锁后
  才执行幂等 Claim 与持久化。真实 PostgreSQL 集成再验证 Active Lease 会使 Safe Point
  返回 BLOCKED。
- Web API/SSE 测试验证幂等 Header、Acquire/Release 路径和
  `SAFETY_LEASE_EVENT` 分块解析。
- PostgreSQL 17 + 真实 Chromium `make test-integration` 通过，`tests/integration/smoke.sh`
  实际验证：
  Acquire 幂等重放、Owner 隔离、Renew、Safe Point BLOCKED、Release 后 SAFE、
  ACQUIRED/RENEWED/RELEASED 顺序、实时 SSE 和资源样本共用递增游标。
- 远端 CI 复跑发现 Coordinator failover 的 Exactly-once 用例会按 Session 误取历史
  `AgentAction` Outbox 行；现已改为使用本次 `operationId`（Outbox
  `idempotencyKey`）定位命令，消除固定时间窗口下的偶发假失败，并由完整集成烟雾测试
  再次验证。
- V029 进入 N/N-1 Gate：只新增表、索引和 Trigger，不删除、重命名或修改旧列。

## 仍未完成

1. 各 Tenant/Application 的支付、账号安全、SPA 和关键事务 Adapter/SDK 包装；本轮完成
   的是通用 Producer 协议，不会自动理解任意网页业务语义。
2. Application-aware Business Recovery 的版本化契约、受限规则 DSL、持久 Verdict、
   迁移 Ready Gate 和有界低风险动作已完成；仍缺各站点 Adapter、契约作者 UI、
   Provider/API 级账号/权限/业务实体证明和受信 `RESTART_EXTENSION` 动作。详见
   [Business Recovery 有界自动动作闭环](56-Business-Recovery有界自动动作闭环.md)。
3. 两个真实 Browser Node + S3-compatible Object Storage 的迁移并发压力、网络分区、
   Node 故障和长期稳定性证书。
4. State/Audit/Agent Step 统一事件总线与跨 Region 消费；当前 Lease 已接入 Session
   Resource/Migration SSE。
