# Workspace 批量元数据 Mutation 闭环

> 日期：2026-08-01
> 适用范围：Workspace Group/Tag 归属、Control Plane、Web/Tauri 共用 UI
> 数据来源：正式 PostgreSQL、正式 API、真实批处理 Operation

## 本轮结论

Workspace Group/Tag 已支持对最多 100 个明确 Session 执行批量加入和移出。请求、逐项
状态、重试、取消和审计都持久化到 PostgreSQL；前端只提交意图并读取真实 Operation，
不使用 Mock、localStorage、JSON 文件或定时器伪造执行结果。

## 已完成

### 1. V068 独立持久账本

新增：

- `workspace_metadata_batch_operations`：保存 Tenant、Actor、Action、Selector、Target、
  原因、请求哈希、幂等键、取消信息和 15 分钟 Deadline；
- `workspace_metadata_batch_operation_items`：保存每个 Session 的顺序、状态、失败码、
  尝试次数、Claim Owner、Lease 和下一次尝试时间；
- 四种正式动作：`ASSIGN_GROUP`、`REMOVE_GROUP`、`ASSIGN_TAGS`、`REMOVE_TAGS`；
- 每批最多 100 个 Session、每个 Tag Target 最多 16 项、每项最多 3 次尝试；
- Operation/Session 均使用带 Tenant 的复合外键，防止跨租户引用；
- Claim 使用 `FOR UPDATE SKIP LOCKED`、租约和 Owner Fencing，进程退出后可恢复。

没有扩展原 `workspace_batch_operations.action` 约束，而是建立独立 Metadata Ledger。
因此应用回滚到 N−1 时，旧生命周期 Worker 不会读取无法识别的新动作，也不会错误认领
Metadata Item。V068 是纯 expand 迁移，不删除、重命名或修改既有列。

### 2. 事务、幂等和取消语义

- 创建请求必须携带 `Idempotency-Key`、至少 8 个字符的原因和显式确认；
- Selector 支持明确 Session ID，也支持 Group/Tag `ANY / ALL` 服务端解析；
- Target Group/Tag 在接受请求前验证同租户存在；
- 请求按稳定排序和规范化 JSON 计算 SHA-256，同键异义返回冲突；
- 每个 Item 的成员变更、审计和成功提交处于同一数据库事务；
- Tag 多目标写入以单 Item 原子完成，不暴露部分 Tag 成功；
- 取消只把尚未 Claim 的 Item 标记为 `CANCELLED`，已执行项继续原子完成；
- Deadline 或三次重试耗尽后写入稳定失败码，不无限重试。

### 3. 正式 API 与权限

新增：

```text
POST /api/v1/workspace-metadata-batch-operations
GET  /api/v1/workspace-metadata-batch-operations
GET  /api/v1/workspace-metadata-batch-operations/{id}
POST /api/v1/workspace-metadata-batch-operations/{id}:cancel
```

创建与取消要求 `TENANT_OPERATOR` 或更高权限，读取遵循现有租户 Read Gate。跨租户
Operation、Session、Group 或 Tag 不可见；Viewer 不显示写入口且服务端返回 403。
错误响应保留正式 Request ID，前端不展示内部异常栈。

### 4. Web/Tauri 共用批量归属 UI

Group 和 Tag 卡片新增折叠式“批量归属管理”：

- 在可加入与已加入环境间切换；
- 支持逐项选择、最多 100 项全选和清空；
- 必须填写原因并确认真实 PostgreSQL 变更；
- 展示接受、执行中、成功、失败、取消数量和 Operation ID；
- 读取真实终态并刷新 Group、Tag 和 Session 查询；
- 活跃时允许取消未执行项，失败时显示稳定 Failure Code 和 Request ID；
- 同一组件直接复用于 Web 和 Tauri 2，没有桌面专用业务分叉。

## 安全迁移与回滚

### 发布顺序

1. 先执行 V068 expand migration；
2. 发布包含 Metadata Worker 和 API 的 N 版本；
3. 确认 N 实例健康后开放前端入口；
4. 观察 Claim Lease、失败码、Deadline 和 Audit；
5. 不在本版本删除独立表或收紧旧生命周期批处理契约。

### 回滚

可先回滚应用到 N−1。旧版本完全忽略 V068 表，新提交入口随应用回滚关闭；已经被 N
接受的 Item 保留在数据库，不会被 N−1 生命周期 Worker 误执行。若需要继续处理，应恢复
N Worker；若决定停止，应由受控运维流程取消未执行项。物理删表属于后续独立 Contract
版本，不与本轮发布合并。

## 可重复证据

- Java Application Service 单元测试覆盖规范化、租户 Target 验证和 Tag 原子执行；
- Web 20 个测试文件、62 项 Vitest 通过，API 测试断言正式请求与轮询；
- TypeScript/Vite production build、OpenAPI/JSON Schema/Proto Contract 通过；
- V068 N/N−1 Gate 确认纯 expand、独立 Ledger、复合外键和租约索引；
- Integration 在 PostgreSQL 中依次执行四类动作并验证幂等重放、Viewer 403、跨租户
  404、四个成功 Header/Item 终态、四条接受审计，以及延后 Item 的取消与幂等重放；
- 管理员 Chromium 完成 Tag 批量移除并显示 `全部成功`，Viewer RBAC E2E 通过；截图：
  `/tmp/agent-browser-cloud-session-flow-metadata-batch.png`。

## 仍未完成

- Agent 大列表 N+1、列表级 SSE、跨 Region Event Bus 和目标规模容量证书；
- 目标业务 Lease Adapter、真实 CRM/支付 Provider 凭据与恢复证明联调；
- Proxy 主动探测、商业 Provider 质量/成本和目标云 Secret 解引用；
- Phase 4 高级 Agent、企业运营自动编排和 V16 目标环境/组织发布 Gate。
