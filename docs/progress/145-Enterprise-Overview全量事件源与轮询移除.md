# Enterprise Overview 全量事件源与轮询移除

> 日期：2026-08-18  
> 状态：仓库实现、本地完整验证及远端 `ci` / `desktop` Workflow 均通过。

## 目标与边界

删除 `useEnterpriseOverview()` 的 15 秒固定轮询，但前提是先证明
`GET /api/v1/enterprise/overview` 每个返回域都有完整、持久、单调的变化源。
高信号 Notification 会漏普通治理变化，Audit 流又仅允许 `SECURITY_ADMIN`，二者均未
被复用或冒充本次全量事件源。Recovery GameDay timeline 读取独立事件表，其五秒刷新也
没有被顺手删除。

## 返回域、来源表与全部写路径

以下矩阵由当前 Overview 读取 SQL 反向追踪，并用 `rg` 对全部生产 Java 写入点复核。
V102 在来源表边界使用 `AFTER INSERT OR UPDATE OR DELETE` 触发器，因此 JPA、JDBC、
Worker lease reaper、测试运维 SQL 和未来新增写入口都不会绕开投影。

| Overview 返回域 | 权威来源表 | 当前全部生产写路径 | 事件范围与补充 |
| --- | --- | --- | --- |
| `validations` | `runtime_validation_runs`、`runtime_validation_jobs` | `EnterpriseOperationsApplicationService` 创建/完成/失败；`RuntimeValidationQueueApplicationService` Claim、Start、Heartbeat、Complete、Fail、Lease 到期与重试 | 平台全局 `RUNTIME_VALIDATION` |
| `costRates` | `enterprise_cost_rates` | `EnterpriseOperationsApplicationService.createCostRate`；V019/V048 迁移种子/兼容触发器 | 平台全局 `COST_RATE` |
| `mediaQuota` | `tenant_media_quotas`、`browser_placements` 的 tenant/state/media slots/bitrate | `EnterpriseOperationsApplicationService.upsertMediaQuota`；`BrowserCapacityApplicationService` Placement 创建/激活/释放/重放；`SessionResourceApplicationService` 资源 ACK、Readback 与 Drift 对账 | 租户 `MEDIA_QUOTA`；Placement 仅在媒体用量相关列变化时发事件 |
| `errorBudget` | `enterprise_slo_policies`、`enterprise_service_level_events` | `EnterpriseOperationsApplicationService.upsertSlo`、`recordServiceLevelEvent` | 租户 `ERROR_BUDGET`；滑动窗口到期另见下文 |
| `releaseFreeze` | `enterprise_slo_policies`、`enterprise_release_freeze_states`，并间接受 service-level window 驱动 | `EnterpriseOperationsApplicationService.upsertSlo/recordServiceLevelEvent`；`ReleaseFreezeApplicationService.evaluateTenant`；`ReleaseFreezeEvaluationScheduler` | SLO/Service Event 已使整个 Overview 失效；状态行另发租户 `RELEASE_FREEZE` |
| `slaExclusions` | `enterprise_sla_exclusions` | `EnterpriseOperationsApplicationService.upsertSlaExclusion` | 租户 `SLA_EXCLUSION` |
| `retentionPolicies` | `enterprise_retention_policies` | `EnterpriseOperationsApplicationService.upsertRetention` | 租户 `RETENTION`；Recording 服务只读取该策略，不是该返回域写入口 |
| `licenseInventory` | `enterprise_license_inventory` | `EnterpriseOperationsApplicationService.upsertLicense`；V021 种子 | 平台全局 `LICENSE` |
| `regions` | `enterprise_regions` | `EnterpriseOperationsApplicationService.upsertRegion`；V019 种子 | 平台全局 `REGION` |
| `recoveryGameDays` | `enterprise_recovery_gamedays`、`recovery_gameday_jobs` | `EnterpriseOperationsApplicationService` 启动/手工完成/失败；`RecoveryGameDayQueueApplicationService` 全部 Worker、Abort、Lease、Deadline、Recovery 状态机 | 平台全局 `RECOVERY_GAMEDAY` |
| `recoveryGameDayTrends` | `enterprise_recovery_gamedays`、`recovery_gameday_remediation_tickets` | 上述 GameDay 状态机；`RecoveryGameDayGovernanceApplicationService.ensure/updateRemediation` | 同一全局游标；90 天窗口到期另见下文 |
| `recoveryGameDayRemediations` | `recovery_gameday_remediation_tickets` | `RecoveryGameDayGovernanceApplicationService.ensureRemediation/updateRemediation` | 平台全局 `RECOVERY_GAMEDAY` |
| `latestCompliance` | `enterprise_compliance_snapshots` | `EnterpriseOperationsApplicationService.generateCompliance` | 租户 `COMPLIANCE` |
| `generatedAt` | Control Plane 当前时钟 | 无业务写路径 | 仅快照元数据，不单独制造事件 |

两项只随时间经过也会变化的聚合已显式补齐，而不是依赖客户端轮询：

- Service Level Event 写入时按当前 `window_minutes` 创建持久到期失效；SLO 窗口改变时
  删除并重算该租户尚未到期的记录；
- GameDay 创建时为 `started_at + 90 days` 创建趋势窗口到期失效；
- `EnterpriseOverviewTemporalInvalidationScheduler` 用 `FOR UPDATE SKIP LOCKED`、
  `DELETE ... RETURNING` 与同事务事件插入发布到期项，多 Control Plane 不会丢失或
  重复消费，单轮最多处理十个 1000 行批次。

## 事件投影与 API

- V102 新增 `enterprise_overview_events`：全局 Identity Sequence、租户 ID 可空、
  受限 `change_type` 与时间戳，不保存实体 ID、治理正文或 Secret；
- 租户字段只读取 `tenant_id = currentTenant OR tenant_id IS NULL`。NULL 行只对应
  Overview 本来就向 ADMIN 展示的平台全局字段，不包含其他租户变化；
- 新增 `GET /api/v1/enterprise/overview/event-stream`，沿用 Overview 的
  `PlatformRoles.ADMIN`，租户只来自认证身份；
- 支持 `Last-Event-ID`、历史 Replay、超前游标 Reset、`replayed`、1 秒跨实例发布、
  15 秒 Keepalive、全局 500 与每租户 50 的连接上限；依赖失败关闭连接，不静默继续；
- OpenAPI 和 TypeScript/Python/Go/Java SDK 同步到 **203 Operations / 273 Schemas**，
  四语言均包含 `streamEnterpriseOverviewChanges` 及 Control/Change 模型。

## Web / Tauri 共用行为

- `useEnterpriseOverview()` 删除 `refetchInterval: 15_000`；
- 共用 React API Client 校验 SSE event id 与 cursor/sequence 精确一致、序号必须前进，
  并在断线后携带最后游标指数退避重连；
- Ready、Reset、Replay 或 Live Change 都使 `enterpriseOverviewKey` 失效并重取正式 API；
- 离线或重连期间页面明确显示“当前数据可能过期”，恢复后自动重取；
- `useRecoveryGameDayEvents()` 的五秒刷新保留，因为 timeline 读取
  `recovery_gameday_job_events`，不属于 Overview 返回域，不能由本流替代。

## 本地验证证据

```text
Java 全量：437 tests / 89 suites，通过
Web 全量：25 files / 112 tests，通过
Rust Workspace、四个 Python Worker/Adapter、Terraform Provider：通过
make lint / make build：通过
make test-desktop：2 tests，通过
make test-sdk：四语言测试、TypeScript ESM Pack、8 个统一发布制品，通过
make contracts-check：OpenAPI 有效
make test-upgrade-compatibility：PASS，含 V102 expand-only 与新 SSE additive 断言
make test-integration：完整 PostgreSQL/mTLS/Chromium Integration 通过
```

Integration 真实执行 V102 后，以 `TENANT_ADMIN + Last-Event-ID` 收到
`enterprise-overview-stream-ready`、`enterprise-overview-change` 和
`"replayed":true`；`TENANT_VIEWER` 返回 403；另一个租户的 `MEDIA_QUOTA` 序号不进入
当前租户查询。测试还确认实际业务链已产生 Validation、Media、SLO、Freeze、SLA、
Retention、Compliance 和 GameDay 类型，原有 `audit_chain_valid=true` 保持成立。

提交 `343baa1` 推送 `main` 后，GitHub `ci` run `32126377468` 的 Verify、完整 Integration
与 Kubernetes Operator E2E 全部通过；`desktop` run `32126377512` 的 Windows/macOS
任务全部通过。该远端结果与上述本地证据共同关闭本切片的仓库 Gate。

## 未改变的发布边界

本轮只关闭仓库内 Enterprise Overview 固定轮询，不代表 V16 生产 Gate 已通过。
跨 Region Event Bus、大规模慢客户端/Ingress 长稳、目标云/真实 IdP/外部 Provider、
目标 Linux 多 Node 以及组织发布签字仍是阻断项。
