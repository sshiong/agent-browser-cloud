# Error Budget Burn Rate 自动发布冻结闭环

> 状态：仓库内 PostgreSQL/API/Runtime Release/UI/审计闭环已完成
> 日期：2026-08-09
> 边界：外部生产发布系统、GameDay 故障执行器和组织审批仍属于目标环境 Gate

## 本轮完成

1. `V075` 为 Tenant SLO 增加默认关闭、可滚动兼容的发布冻结策略：冻结阈值、恢复阈值、
   评估窗口和稳定恢复窗口均由 PostgreSQL 保存；旧版本 Control Plane 可继续使用原有
   SLO 字段，既有 SLO Policy 会回填为默认关闭的 `OPEN` 状态。
2. 建立 `enterprise_release_freeze_states` 权威当前状态和
   `enterprise_release_freeze_events` 不可变转换账本。状态明确区分 `OPEN`、`FROZEN`
   和 `RECOVERING`，每次评估带单调版本、原因和时间戳。
3. Control Plane 默认每 30 秒按 Tenant keyset 分页评估最近窗口内、排除 SLA
   Exclusion 后的真实不可用时长；多个 Control Plane 通过 SLO Policy 行锁串行决策，
   不会因并发调度重复写冻结/解冻事件。
4. 冻结使用 Burn Rate 上阈值；恢复使用更低阈值和持续稳定时间，负载反弹会清空恢复
   计时，形成 Hysteresis。策略关闭时会显式清理已有冻结并留下不可变事件。
5. Runtime Promotion 在“提交申请”和“双人审批”两个时点同步重算并检查 Gate；冻结或
   评估失败时 fail-closed，阻断晋级并写独立 Audit。紧急 `DISABLED` 不受冻结影响，
   仍保留请求人与审批人分离的双人治理。
6. 新增 `GET /api/v1/enterprise/release-freeze`，并将当前 Gate 投影到 Enterprise
   Overview。Web/Tauri 共用企业运营页面展示当前阶段、Burn Rate、冻结/恢复阈值、
   观察窗口、原因、评估时间和版本，不伪造状态。
7. 正式 OpenAPI 和 TypeScript/Python/Go/Java SDK 已重新生成；当前契约为 166 个唯一
   Operation、224 个公开 Schema，TypeScript 为 262 个服务方法、32 个服务和 241 个
   Model。V075 的加法迁移和新增可选 JSON 字段进入 N/N-1 Gate。

## 安全与可靠性语义

- 数据来源仅为正式 `enterprise_service_level_events` 和 SLA Exclusion，不使用 Mock、
  localStorage、内存计数或浏览器定时器伪造。
- Runtime Promotion 每次写前同步评估；后台状态陈旧或数据库不可用不会放行晋级。
- 冻结、阻断、恢复和解冻均进入 Tenant Audit Chain；转换事件与当前投影分表保存。
- Emergency Disable 只绕过“晋级冻结”，不绕过平台管理员角色、租户隔离或双人审批。
- 新字段默认关闭且有数据库约束；恢复阈值必须低于冻结阈值，避免抖动配置。

## 验收证据

- `./gradlew -p apps/control-plane test`：通过；覆盖阈值冻结、恢复稳定窗口、反弹重置、
  策略关闭清理、申请/审批阻断和 Emergency Disable 旁路。
- Web Console：Lint、67 项 Vitest 和生产构建通过。
- `make test-e2e`：真实 API 页面展示 `Runtime promotion gate` 和
  `BURN_RATE_WITHIN_POLICY`，Viewer 仍不能进入企业运营页面；页面就绪判断从与持久
  SSE 冲突的 `networkidle` 改为 DOM 就绪加明确元素断言。验收还发现 Proxy Overview
  会复用创建向导挂载时的旧缓存，现已在代理页挂载时强制重取正式 API，避免后端已经
  `BOUND` 而页面仍显示 0 条分配。
- OpenAPI Redocly、TypeScript SDK Verify、多语言 SDK Verify、N/N-1 Gate 均通过。
- `make test-integration`：真实 PostgreSQL/Redis、双 Control Plane、Browser Node、mTLS
  全链路通过；验证 `OPEN → FROZEN → RECOVERING → OPEN`、`FROZEN,CLEARED` 不可变
  事件、Promotion `409`、Emergency Disable 成功和六类 Runtime Release Audit，输出
  `release_freeze=true`。
- `make test-object-storage`：通过，输出 `OBJECT_STORAGE_GAMEDAY_OK`。

## 仍未完成

1. 将仓库内 Runtime Release Gate 接到客户/目标组织实际使用的 GitHub、Argo CD、
   Spinnaker 或其他生产发布流水线，并完成凭据、Webhook、回滚与断网演练。
2. GameDay Runner 真实执行基础设施故障并自动写入 SLO/冻结 Gate。
3. 目标 Region 的多副本一致性、跨 Region Gate 复制顺序和长稳证书。
4. 正式 SLO Owner、发布 Owner、值班和 Residual Risk 审批签字。
