# Agent 任务大列表轻量投影与游标分页闭环

> 日期：2026-08-01
> 适用范围：Agent Task 队列、Control Plane、PostgreSQL、Web/Tauri 共用 UI
> 数据来源：正式 PostgreSQL、正式 API、真实 Agent Task 详情

## 本轮结论

Agent 队列的大列表读取已改为轻量摘要投影、50 条硬上限和稳定 Keyset Cursor；只有用户
选中的 Task 才读取完整 Plan、Execution Result、Allowed Domain 与安全事件。原实现并非
传统“每行一次 SQL”的 N+1，而是单次读取最多 100 条宽 JSON 实体并在前端汇总，本轮按
真实根因关闭了数据库读取、JSON 解析和响应体随列表规模同步放大的问题。

旧 `GET /api/v1/agent-tasks?limit=&offset=` 保留给 N−1 Client，未做破坏性替换。

## 已完成

### 1. 有界摘要接口

新增：

```text
GET /api/v1/agent-task-summaries?limit=20&cursor=...
```

- `limit` 默认 20、最大 50；
- Cursor 对 `(created_at, task_id)` 编码，同时间记录也有稳定顺序；
- Cursor 非法时在访问 PostgreSQL 前返回 `AGENT_TASK_CURSOR_INVALID`；
- 租户条件进入每条列表和聚合 SQL，不接受前端传入 Tenant 查询范围；
- 响应只包含 Task ID、Session ID、Goal、状态、风险、策略、Step/Event 数量与时间；
- 不返回 `plan`、`allowedDomains`、`executionResults` 或 `securityEvents` 正文；
- 列表和状态聚合固定为两次 SQL，不随页面条目数增加 Repository 调用。

全租户 `planned/completed/blocked/total` 只聚合标量状态。安全事件数量只对已经加载的
有界摘要页求和，UI 明确标为“已载安全事件”，不会为了顶部指标周期扫描全租户 JSON。

### 2. PostgreSQL 在线索引

V069 新增：

```sql
CREATE INDEX CONCURRENTLY ...
ON agent_tasks(tenant_id, created_at DESC, task_id DESC)
INCLUDE (state);
```

该索引同时服务稳定 Cursor 和标量状态聚合。Migration 配置
`executeInTransaction=false`，不把 concurrent index 放入 Flyway 事务；不改列、不回填、
不删除旧索引。

#### 发布与回滚

1. 先执行 V069 在线索引并观察锁等待、复制延迟和磁盘增长；
2. 发布支持摘要接口的 Control Plane；
3. 发布切换到摘要/按需详情的 Web/Tauri UI；
4. 回滚时可先回退 UI 和应用，旧列表接口仍可用；
5. 确认无新版本使用后，可在独立运维窗口执行
   `DROP INDEX CONCURRENTLY idx_agent_tasks_tenant_summary_cursor`。

### 3. Web/Tauri 共用数据流

- Automation 队列使用 Infinite Query 按 Cursor 加载，不再启动时请求 100 条完整 Task；
- 顶部状态数使用后端权威聚合，已加载安全事件数使用摘要计数；
- 选择 Task 后才调用原详情 API；运行中、等待确认和等待人工状态按真实详情刷新；
- 摘要自动刷新只在首屏存在活动任务时启用；加载多页后不会周期重拉所有历史页；
- 支持“加载更多”、详情 Loading/Error/Retry；
- 创建、执行和人工治理继续使摘要与详情查询失效，不伪造状态；
- React API/Query/组件继续由 Tauri 2 直接复用，无桌面专用业务分叉。

## 验收证据

- Control Plane 全量单元测试通过；新增 Cursor 往返、非法 Cursor 前置拒绝、50 条上限、
  固定两查询和宽字段不投影测试；
- 完整 PostgreSQL/Control Plane/Browser Node Integration 通过：V069 空库迁移、两页
  Cursor 不重复、租户隔离、权威总数/状态数和响应字段最小化均已验证；
- Web 64 项单测、TypeScript Build、ESLint、Prettier、真实 Chromium E2E 和 Tauri 2
  unsigned native build 通过；
- OpenAPI/Proto Contract 与 N/N−1 Gate 通过；V069 被 Gate 强制为 concurrent、非事务、
  纯扩展迁移。

## 仍未完成

- 尚未生成 10k/50k Agent Task 的目标规模数据库延迟、连接池和 Payload 容量证书；
- Agent 列表仍以有界真实 API 刷新为主，未接 Workspace/跨 Region 列表级事件流；
- Network/Toast/Dialog/Visual/Login/Business Entity 基础 Validator 已由进度 107 关闭，
  独立 Agent Worker 已由进度 113 关闭，Reviewer/固定 Responses Provider/模型治理已由
  进度 114 关闭；高级 Validator、客户大规模 Replay和多 Agent 协作仍属于 Phase 4 后续；
- 目标 Linux/目标云长稳、真实 IdP、KMS/HSM、跨 Region 和组织发布 Gate 未关闭。

因此本轮关闭的是 Agent 队列代码级大列表读取缺口，不等同于目标规模和生产发布验收。
