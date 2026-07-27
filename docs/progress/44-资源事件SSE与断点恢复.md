# 资源事件 SSE 与断点恢复

> 日期：2026-07-28
> 状态：资源样本、调整、安全点通知屏障和迁移状态的 PostgreSQL 持久事件流已完成；
> State/Audit 统一流和跨 Region 事件总线仍待完成

## 本轮完成

### 持久化游标

- 新增 V026 `session_resource_stream_cursors`，按 `tenant_id + session_id` 在写事务内
  分配严格递增游标。
- 游标没有使用 PostgreSQL `SEQUENCE`。连接级 Sequence Cache 会让后提交的行取得
  更小值，可能落到客户端已经确认的 `Last-Event-ID` 之后并永久漏报。
- 每个 Session 的游标行通过 `INSERT ... ON CONFLICT DO UPDATE` 加锁；资源样本和
  资源事件共用同一分配函数，但不同 Session 不互相争用。
- V026 对升级前已有 Sample/Event 按时间、类型和 ID 做确定性回填，并为旧版本应用的
  新写入安装兼容 Trigger。Session 删除时 Cursor 随外键级联清理。
- `session_resource_samples` 和 `session_resource_events` 新增租户、Session、序列联合
  索引；断点重放只读取正式 PostgreSQL 数据。

### SSE API

- 新增：

  ```text
  GET /api/v1/sessions/{id}/resource-stream
  Accept: text/event-stream
  Last-Event-ID: <numeric cursor>
  ```

- 连接先返回 `resource-stream-ready`；客户端游标超过服务器权威游标时返回
  `resource-stream-reset`，要求重新读取权威视图。
- 新资源样本或资源时间线行返回 `session-resource-change`，包含：
  `sequence`、`changeType`、`entityId`、`occurredAt` 和 `replayed`。
- 事件只作为“权威数据已变化”的通知。CPU、内存、Safe Point、Migration 和时间线
  详情仍由原正式 API 读取，不把 SSE Socket 内存当业务状态。
- 每个进程和每个 Session 都有连接上限；15 秒 Keepalive、30 分钟连接轮换、数据库
  故障断开重连和独立调度线程已配置。
- 响应设置 `Cache-Control: no-cache, no-transform` 与
  `X-Accel-Buffering: no`，避免常见反向代理缓存或聚合 SSE 帧。
- 租户归属在建立异步响应前验证；跨租户请求不暴露 Session。

### Web Console

- 资源详情从 5 秒/30 秒定时轮询改为认证 `fetch` 流式读取，OIDC Bearer 和本地
  开发身份继续复用同一个 API Client。
- 浏览器保存最后处理的游标；断线后使用 `Last-Event-ID` 重放，指数退避上限为
  30 秒并加入抖动。
- 收到 Resource Sample 时刷新资源与 Safe Point；收到 Resource Event 时同时刷新
  Session、资源、时间线、Safe Point 和 Migration。
- 页面显示 `LIVE / CONNECTING / RECONNECTING / OFFLINE`，断线时明确提示数据可能
  过期，不把旧数据描述为实时。
- 未使用 `localStorage`、生产 Mock、固定定时器或前端模拟曲线。

## 验证

- Control Plane 单测覆盖：
  - 新连接从当前游标开始，不重放历史；
  - `Last-Event-ID` 后的持久事件重放；
  - 非数字游标拒绝；
  - 每 Session 订阅上限。
- Web API 测试用分块 CRLF SSE 验证跨 Chunk 解析、身份 Header 和
  `Last-Event-ID`。
- `make test-integration` 在 PostgreSQL 17 上实际验证：
  - V026 从空库执行成功；
  - 已连接 Client 收到随后提交的真实资源样本；
  - 断开后从旧游标收到 `replayed=true` 的同一持久事件；
  - 资源 API 返回相同 Sample；
  - 跨租户订阅返回 404。
- 集成测试曾确定性发现并修复全局缓存 Sequence 的游标倒退：
  已连接游标为 `101`、后写 Sample 为 `6`。当前事务型每 Session 游标使后写 Sample
  必须大于连接时游标。
- Integration Smoke 的失败输出会保留 SSE 原文、Sample 响应和数据库持久行，便于
  CI 直接定位游标或投递回归。

## 仍未完成

1. Browser State、Audit、Agent Step 和通用 Operation 的统一事件流；本轮只关闭
   Resource/Migration 详情的轮询缺口。
2. PostgreSQL Polling 之外的跨 Region Event Bus、分区消费和灾备切换。
3. 大规模并发订阅、慢客户端、代理 Idle Timeout、背压和目标云 Ingress 长稳证书。
4. Web 列表级批量资源摘要流；当前流按打开的 Session 详情页建立。
5. Tauri 2 已打包复用同一 Fetch/SSE Client；仍缺桌面端断网、网络切换和休眠唤醒
   的长期恢复验收。
