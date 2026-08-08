# Session 统一事件流与轮询替换

> 完成日期：2026-07-30
> 状态：仓库内实现、N/N−1 兼容、OpenAPI/Web 单测和完整 PostgreSQL 集成已完成；
> 跨 Region Event Bus、大规模慢客户端和目标云 Ingress 长稳仍待完成

## 本轮关闭的缺口

V026/V029 已为 Resource Sample、Resource Event 和应用 Safety Lease 建立 PostgreSQL
事务游标与可续传 SSE，但 Browser Current State、Session 生命周期、Operation、Agent
Task 和 Audit 仍依赖详情页固定轮询或其他刷新副作用。

本轮新增 V059，将既有游标提升为统一 Session 事务游标，并新增无业务正文的
`session_event_envelopes`：

- `SESSION`
- `BROWSER_STATE`
- `AUDIT_EVENT`
- `OPERATION`
- `AGENT_TASK`
- `RESOURCE_SAMPLE`
- `RESOURCE_EVENT`
- `SAFETY_LEASE_EVENT`

事件信封只包含 Tenant、Session、单调序号、类型、实体 ID 和发生时间。Browser State、
审计详情、资源指标和 Operation 正文仍从原权威 API 读取，事件表不复制 Cookie、
页面正文、Audit Details 或其他敏感业务数据。

## 事务顺序与滚动兼容

统一流继续使用 `session_resource_stream_cursors` 的逐 Session 行锁分配，不使用可缓存
的 PostgreSQL Sequence。写入者在业务事务内分配序号并提交信封，因此客户端确认某个
`Last-Event-ID` 后，不会遗漏“序号更小但更晚提交”的变更。

V059 为旧写路径安装数据库 Trigger：

- V026/V029 的 Resource/Safety 写入继续由原 BEFORE Trigger 分配序号，再由 AFTER
  Trigger 镜像同一序号；
- Browser State、Session、Operation 和 Agent Task 的 INSERT/UPDATE 直接分配新序号；
- Session-scoped Audit INSERT 分配新序号；
- 迁移前已有 Resource/Safety 历史按原序号回填；旧 Browser/Audit 历史不伪装成新事件。

因此 N−1 应用可以忽略新表继续写，N 版本仍能读取旧节点产生的变更。回滚到 N−1 不会
破坏原 Resource SSE，但新类型不会被旧客户端消费。

## API 与 Web/Tauri

新增正式接口：

```text
GET /api/v1/sessions/{id}/event-stream
Accept: text/event-stream
Last-Event-ID: <numeric Session cursor>
```

控制事件为 `session-stream-ready/session-stream-reset`，数据事件为 `session-change`。
原 `/resource-stream` 和原事件名称保留，作为滚动升级兼容入口；两个协议共享同一
PostgreSQL 游标和 Socket 容量边界。

Web/Tauri 共用 API Client 已切到统一入口：

- `SESSION/OPERATION/AUDIT_EVENT` 刷新详情及关联权威视图；
- `BROWSER_STATE` 只刷新 Current State；
- `RESOURCE_SAMPLE` 精确刷新资源和 Safe Point；
- 其他类型触发完整 Session 相关视图失效；
- 断线继续使用 Last-Event-ID、指数退避、OFFLINE/RECONNECTING 和数据过期提示。

Session 详情与 Browser Current State 的固定 2 秒轮询已删除。Safe Point 的租约到期
本地定时刷新和 Evidence 尚有独立语义，不属于伪造事件或固定资源扩容轮询。

## 可重复验收

- Java 单测验证统一协议复用同一 durable cursor、断点续传和订阅边界；
- Web 测试验证分块 SSE、Event ID/正文序号一致性、Session/State/Audit/Operation/
  Agent Task 类型和身份 Header；
- OpenAPI 与 V059 expand-only/N−1 Gate 通过；
- 完整 Integration 在 PostgreSQL 17、双 Control Plane、三 Browser Node、mTLS、
  MinIO 和 Helper 隔离环境中验证：
  - `session-stream-ready` 与真实 `session-change`；
  - Safety Lease、Resource Sample 实时通知和断线重放；
  - Session、Browser State、Audit、Operation、Resource、Safety 信封真实落库；
  - 序号严格前进、`replayed=true/false` 正确；
  - 跨租户订阅返回 404；
  - 既有 Coordinator、Agent、迁移、审计链和企业运营烟测无回归。

## 仍需完成

1. 跨 Region Event Bus、分区消费、灾备切换和 Region 级游标语义；
2. 大规模并发订阅、慢客户端、Ingress Idle Timeout、背压和长连接容量证书；
3. Session 列表级批量摘要/事件投影，避免为每一行建立独立 SSE；
4. Tauri 桌面休眠、网络切换和长时间离线后的恢复矩阵；
5. Kubernetes Operator List/Watch 已由进度 104 关闭；仍缺 API Server/etcd 故障长稳。
