# Workspace 批量生命周期与组合筛选闭环

> 日期：2026-08-01
> 适用范围：Workspace Groups、Workspace Tags、Environment 列表、Web/Tauri 共用 UI
> 数据来源：正式 PostgreSQL、Routed Coordinator Command、子 Operation/Migration Ledger

## 本轮结论

Group/Tag 已具备租户隔离、可恢复、可追踪的批量生命周期操作；Environment 列表已具备
Group 与最多 16 个 Tag 的 `ANY / ALL` 服务端组合筛选和分页。前端只提交选择器与动作，
不直接操作 Node、Cgroup 或浏览器，也不伪造批量进度。

本轮关闭进度 92 留下的两项代码产品化缺口：

- Group/Tag 批量生命周期 Operation；
- Group/Tag 组合服务端筛选。

Agent 宽 JSON 大列表读取后续已由进度 96 关闭；列表级跨 Region 事件流及目标规模容量
证书仍未完成。

## 已完成

### 1. 持久批量 Operation

- V066 新增 `workspace_batch_operations` 与 `workspace_batch_operation_items`；
- Header 固化 Tenant、Actor、Action、Selector、Reason、请求哈希和幂等键；
- 每个 Item 以外键绑定一个 `coordinator_commands` 记录；
- 创建 Header、Routed Command 和 Item 在同一事务中完成，Control Plane 重启后仍可恢复；
- 每批最多 100 个 Session，显式 Session 选择器不能与 Group/Tag 选择器混用；
- `START / PAUSE_AGENT / MIGRATE / HIBERNATE` 均通过物理 Owner 路由，不从入口 Pod
  直接执行 Session 副作用。

### 2. 真实状态聚合

批量状态来自下列权威账本：

```text
Workspace Batch Item
→ Coordinator Command PENDING / EXECUTING / COMMITTED / FAILED
→ Exclusive Operation 或 Session Migration Ledger
→ Batch ACCEPTED / EXECUTING / CANCELLING / SUCCEEDED /
  PARTIAL_SUCCESS / FAILED / CANCELLED
```

- Start/Hibernate 跟踪子 `ExclusiveOperation`；
- Migrate 跟踪持久 `SessionMigration`；
- Pause Agent 在 Resource Policy、Agent Task、Resource Event 同一事务提交后才成功；
- 失败 Item 返回明确 Failure Code，Web 同时显示 Request ID；
- 取消只把尚未 Claim 的 `PENDING` Command 标记为取消，执行中的子 Operation 不被
  伪装成已取消；
- 创建与取消均具备 Idempotency-Key；同一键不同正文 fail-closed；
- 风险动作要求至少 8 字符原因和显式确认。

### 3. Safe Point 与资源策略边界

- Migrate/Hibernate 复用既有 Safe Point Aggregator、Checkpoint、State Resync 和
  Business Recovery；
- HumanTakeover、输入/拖拽、上传下载、表单提交、Snapshot/Profile Flush、应用 Lease
  等屏障不会被批量入口绕过；
- `PAUSE_AGENT` 保持 Browser 与登录状态，写入真实 Resource Event；
- 前端风险说明明确 Browser 可能重启和网络重连，不描述为绝对无感。

### 4. 服务端组合筛选

`GET /api/v1/sessions` 新增：

- `groupId`；
- 可重复的 `tagId`，最多 16 个；
- `tagMatch=ANY|ALL`。

筛选在 PostgreSQL 查询中先执行，再执行 `limit/offset`，不是把一页结果拉到前端后过滤。
查询始终携带 `tenant_id`；Tag `ALL` 使用租户相关的去重 Assignment Count，`ANY` 使用
租户相关的 `EXISTS`。搜索字符串继续转义 `%`、`_` 与反斜杠。

### 5. Web/Tauri 共用 UI

- Environment 高级筛选新增 Group、Tag Chips 与 ANY/ALL；筛选、搜索、状态和分页同步 URL；
- Group 与 Tag 卡片新增批量动作、风险原因/确认、真实进度计数、失败明细和取消未执行项；
- 非终态按 2 秒读取真实批量 Operation，终态立即停止刷新；
- Viewer 不显示写入口；Web 与 Tauri 继续复用同一 React 组件、API Client、权限和状态逻辑；
- 状态同时使用文字和图标，不只依赖颜色。

## API 与契约

新增正式接口：

```text
POST /api/v1/workspace-batch-operations
GET  /api/v1/workspace-batch-operations
GET  /api/v1/workspace-batch-operations/{batchOperationId}
POST /api/v1/workspace-batch-operations/{batchOperationId}:cancel
```

OpenAPI 同步声明选择器、动作、批量/Item 状态、风险确认、幂等和取消语义。

## 可重复证据

- Control Plane 全量测试通过，新增 3 组批量服务测试覆盖持久路由、风险确认、幂等取消；
- Web 19 个文件、61 项测试通过；新增 API 测试覆盖组合查询参数、批量创建/读取/取消；
- 全仓 Java/Rust/Web lint 通过；OpenAPI/Proto/JSON Schema Contract 通过；
- V066 已纳入 N/N−1 仅增量迁移 Gate；PostgreSQL 17 空库成功执行 V001—V066；
- 完整 Integration 通过：真实 Chromium 批量 START、Coordinator Command、子 Operation、
  外键账本、Group+Tag ANY/ALL、跨租户空结果/404 均有断言；
- 真实 Web Console 管理员 E2E 完成 Group/Tag 创建与归属、组合筛选、Safe Point 批量动作
  和终态显示；Viewer E2E 证明批量写入口隐藏；
- Tauri Rust lint/测试、共享 Web Production Build 和 macOS unsigned release 构建通过。

## 仍未完成

- Agent 宽 JSON 大列表读取后续已由进度 96 关闭；目标规模分页/批量容量证书仍未完成；
- 列表级 SSE/跨 Region Event Bus、慢客户端与 Ingress 长稳；
- 目标 Linux 正式 Chromium 下的大批量并发、数据库连接池与热点 Tenant 长稳；
- Environment Saved View 的 Group/Tag 组合条件已由
  [进度 94](94-Saved-View-Group-Tag筛选持久化闭环.md)纳入 V067 Schema、租户验证、
  OpenAPI 和 Web/Tauri 保存/恢复流程，不再属于未完成项；
- 批量归属/移除标签等管理类 Mutation 当前仍使用既有逐 Session API；本轮的“批量生命周期”
  特指 Start、Pause Agent、Migrate、Hibernate，不包含批量重写 Workspace 元数据；
- OpenAPI TypeScript Client 自动生成与正式发布仍是独立缺口。
