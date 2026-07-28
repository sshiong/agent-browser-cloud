# Web Console AUTO 资源策略与详情面板

> 日期：2026-07-27
> 状态：AUTO 创建、策略持久化、基础真实遥测、同节点在线资源执行器、安全点/迁移详情
> UI 和可恢复资源 SSE 已完成；完整指标与真实双 Node E2E 待完成

## 本轮目标

将“新建浏览器环境”从用户手动选择 L1～L5，改为默认 `AUTO` 资源策略；把运行环境与资源策略分离，并在 Session 详情中展示真实分配、真实遥测、上限、压力和调整事件。

## 已完成

### 创建向导

- 普通创建流程不再显示 L1 Lite、L2 Standard、L3 Interactive、L4 Heavy 或 L5 Native。
- Web 创建请求默认提交：

  ```json
  {
    "resourcePolicy": {
      "mode": "AUTO",
      "onMaximumReached": "PAUSE_AGENT",
      "allowMigration": true,
      "allowHibernate": true,
      "blockMigrationDuringHumanTakeover": true,
      "executionEnvironment": "SYSTEM_MANAGED"
    }
  }
  ```

- `Native OS` 只作为独立 `ExecutionEnvironment`，不再作为资源等级。
- 普通用户只看到自动分配、资源范围和达到上限行为；管理员高级设置才显示运行环境、CPU/内存上限、冷却期和缩容窗口。
- `TERMINATE_STRICT` 只对 Platform Admin 显示，前端要求显式二次确认，后端也做独立角色校验。
- 达到上限默认 `PAUSE_AGENT`，明确保留 Browser、登录状态和 HumanTakeover。
- 1440 宽抽屉与 390×844 窄屏均完成视觉检查。

### PostgreSQL 与后端

- 新增 `V022__session_auto_resource_policy.sql` 和 V023 兼容回填：
  - `session_resource_policies`
  - `session_resource_samples`
  - `session_resource_events`
- V023 为 V022 前已存在的 Session 回填 AUTO Policy，避免升级后出现资源详情 404。
- Resource Class 和 `standard-v1`、`interactive-v1`、`heavy-v1`、
  `native-standard-v1` 继续保留为内部 Placement 实现，不要求普通用户提交。
- 创建 Session 时同步创建 AUTO Policy 和已提交的真实
  `RESOURCE_ADJUSTMENT` Operation。
- Placement 完成后将实际 Node、CPU、内存上限和内部模板写入资源事件。
- 资源策略 PATCH 使用 PostgreSQL 幂等记录；同一幂等键重放返回同一 Operation ID。
- 新增真实接口：
  - `GET /api/v1/sessions/{id}/resources`
  - `GET /api/v1/sessions/{id}/resource-events`
  - `PATCH /api/v1/sessions/{id}/resource-policy`
  - `POST /api/v1/sessions/{id}/resource-samples`（Platform Admin / Node 遥测入口）
- Session 级样本只接受当前 Placement Node；未上报时返回
  `AWAITING_TELEMETRY`，不构造 CPU/内存曲线。
- 决策循环默认每 30 秒聚合真实样本，使用滑动窗口、P95、EWMA 和最小持续时间；
  单次尖峰不会改变状态或资源。
- 达到上限且策略为 `PAUSE_AGENT` 时，运行中的 Agent Task 进入
  `PAUSED_BY_RESOURCE_POLICY`，Browser Session 保持运行。
- Node Critical PSI 的默认处置从“立即终止低优先级 Session”改为：
  暂停 Agent、保留 Browser、Placement 进入 `WAITING_SAFE_POINT`。

### Session 详情 UI

- 新增可复用 `SessionResourcePanel` 及以下组件：
  - `ResourcePolicyCard`
  - `ResourceUsageChart`
  - `ResourceAdjustmentTimeline`
  - `ResourcePressureBadge`
  - `ResourceLimitProgress`
  - `ResourcePolicyDrawer`
  - `MigrationStatusCard`
  - `CapacityWarning`
- 展示真实当前分配、允许上限、解析模板、资源状态、最近评估/调整原因和真实事件。
- 没有真实 Node 样本时显示“等待 Node 遥测 / 不生成模拟指标”。
- 数据超过 20 秒未更新时显示 `STALE`，不把旧数据描述为实时。
- 策略抽屉等待后端 Operation；HumanTakeover 期间自动迁移开关禁用。
- 组件只依赖 React、API Client、权限和 Query 状态，可用于后续 Tauri 2 复用。

## 已验证

- `./gradlew -p apps/control-plane test`：118 项通过。
- `pnpm --dir apps/web-console test --run`：26 项通过。
- `pnpm --dir apps/web-console build`：通过。
- `pnpm --dir apps/web-console lint`：通过。
- Flyway 在本地 PostgreSQL 17 实际从 V021 升级到 V023，Flyway/Hibernate Schema
  Validation 通过，Control Plane Health=`UP`；Gradle Flyway PostgreSQL 任务也已可直接执行。
- 真实 API 联调：
  - AUTO 创建返回 `CREATED`、Policy 和持久化 Operation ID。
  - 启动后资源接口返回真实 Placement：`720m CPU / 768MiB request /
    1280MiB limit`、`node_live_local`、`standard-v1`。
  - 无 Session 遥测时 `usage=null`、`AWAITING_TELEMETRY`。
  - 策略更新返回 `COMMITTED`；相同幂等键返回同一 Operation ID。
  - 局部 PATCH 只更新显式字段，不会把已有 CPU/内存上限重置成默认值。
  - 创建请求幂等重放返回原 Session 和原资源 Operation ID。
  - Tenant Admin 配置 `TERMINATE_STRICT` 返回 403 和 Request ID。
- Playwright 检查：
  - 详情资源面板真实读取 Policy、Placement 和事件。
  - 六步向导资源步骤不再出现 L1～L5。
  - 1440 桌面和 390×844 窄屏无水平溢出，底部操作区可用。

## 尚未完成

以下项目不能因为本轮 UI/API 已存在而计为完成：

> 本节是滚动状态记录。资源遥测/Actuator/Safe Point/迁移/SSE 已由进度 42−54
> 逐步关闭；五分钟成本趋势、成本上限执行和上限前一次性非核心降载已由
> [进度 65](65-AUTO成本趋势与上限前降载闭环.md)关闭。以下以各条最新说明为准。

1. Browser Node 已按 5 秒周期自动上报 CPU、RSS 和 Memory PSI；Renderer、Tab、主线程、
   Agent Action、State Diff、Profile I/O、Extension、Remote Desktop 与 Media 指标尚未接入。
2. 在线 Cgroup CPU/Memory/PID 调整已完成；State Collector 预算、Encoder Slot、
   Remote Desktop 码率和 Extension Weight 执行器尚未实现。
3. 30 秒决策引擎已通过真实 Operation、Outbox、Node ACK Event 和 PostgreSQL 提交完成
   同节点快扩慢缩；Node ACK 前不会写入新分配。
4. Safe Point 已覆盖真实 Input Ledger/Drag、HumanTakeover、Agent Task、Snapshot/Profile
   Durable Workflow；上传下载、表单、支付、安全和应用关键事务仍缺对应业务 Producer。
5. Checkpoint → 排除源 Node Placement → S3 Restore → State Resync → 默认 Business
   Recovery Validation 已实现；真实双 Node + S3 + Chromium 故障矩阵仍待验收。
6. 自动休眠和严格预算终止已接入真实 Operation/Node 执行链。
   V042 已使 `maximumCostPerHour` 进入真实五分钟成本决策；Trace/录制/截图/后台 Tab
   等完整 Level 1 Node Actuator 仍待实现。
7. Resource Event 已通过 PostgreSQL 持久 SSE、`Last-Event-ID` 和断线重放推送；
   Web 已移除 Resource/Safe Point/Migration 的 5 秒/30 秒轮询。State/Audit 统一事件
   层仍未实现。
8. Tauri 2 容器、平台适配器与 OS 安全存储已在后续阶段创建，AUTO 组件继续复用同一
   React/API/权限逻辑；Apple/Microsoft 签名、真实 Updater 和桌面矩阵仍是发布 Gate。

## 下一步建议

1. 补齐 Trace/录制/截图/后台 Tab 等 Level 1 Node Actuator 与 ACK。
2. 接入目标站点支付/账号安全/关键事务 Adapter 和 Provider 级恢复证明。
3. 增加 State/Audit 统一事件流和跨 Region Event Bus。
4. 完成 Session Coordinator HTTP/Timer/Workflow 的物理 Shard Pod 路由。
5. 执行目标 Linux/云压力、成本校准、HumanTakeover 与危险事件长稳矩阵。
