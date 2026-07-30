# Hot Tenant 权威路由与 Route Epoch 安全迁移

> 完成日期：2026-07-29
> 状态：PostgreSQL 权威路由、增量安全点迁移、所有权栅栏、管理 API 与本地集成验收已完成

## 本轮关闭的缺口

此前 `CoordinatorShardRouter` 使用进程内 Map 和全局计数器保存 Tenant 分区与
Route Epoch，而且只被容量测试调用：

- Control Plane 重启会丢失 Tenant 重分区结果；
- 生产 Session 命令没有读取 Shard Route；
- Route Epoch 没有进入 `coordinator_ownership`，无法拒绝旧 Route owner；
- 没有迁移 Attempt、逐 Session 绑定、幂等管理 API 或重启续跑。

V040 和本轮 Control Plane 改动将该模型升级为正式持久化控制链。

## PostgreSQL 权威模型

新增：

- `coordinator_tenant_routes`：Tenant 当前/待提交 Virtual Partition 数、单调
  Route Epoch、迁移状态和乐观版本；
- `coordinator_session_routes`：每个 Session 当前 Route Epoch、Virtual Partition
  和逻辑 Shard；
- `coordinator_route_migrations`：来源/目标 Epoch、分区数、迁移/阻塞 Session 数、
  Actor、Request ID、结果与时间；
- `coordinator_ownership.route_epoch`：与 Coordinator Term 一起构成 owner 栅栏。

V040 是纯增量迁移。旧版本忽略三张新表；新增 ownership 列带
`DEFAULT 1`，N-1 写入无需认识新列。新版本会为滚动升级期间由 N-1 创建的 Session
惰性补齐 Route Binding。

## 安全迁移流程

```text
Platform Admin 请求目标分区数
→ expectedRouteEpoch CAS
→ 创建 Durable Migration
→ Tenant Route 进入 MIGRATING
→ 新 Session 直接绑定目标 Epoch
→ 调度器分批扫描旧 Epoch Session
→ 持有 Session 行锁重新评估 Safe Point
→ Unsafe：保持原 Session/Browser，等待下轮
→ Safe：更新 Session Route
→ 提升 ownership.route_epoch 与 coordinator_term
→ 全部 Session 到达目标 Epoch
→ 原子提交 Tenant Route
```

Safe Point 继续复用现有 PostgreSQL 聚合器，因此 HumanTakeover、连续输入/拖拽、
上传下载、表单提交、Agent Task、Snapshot/Profile Workflow 和应用安全 Lease
都会阻止自动 Route 迁移。系统不会把“正在迁移”描述为绝对无感。

## 生产命令与栅栏

- 每个 Coordinator 命令和 Node Event 都先解析持久 Session Route；
- owner 获取、续租和 Node Event 校验必须匹配当前 `route_epoch`；
- Session Route 提升时，如果已有 ownership，会同时提升 Coordinator Term 并令租约
  过期；
- 旧 Epoch owner 不能续租，旧 Term Node Event 也不能提交状态；
- 路由哈希器改为纯函数，分区数和 Epoch 不再保存于 JVM 内存。

## 正式 API

```text
GET  /api/v1/coordinator/tenant-route
GET  /api/v1/coordinator/tenant-route/migration
POST /api/v1/coordinator/tenant-route/migrations
```

读取要求 Tenant Admin；发起迁移只允许 Platform Admin。写入必须携带
`Idempotency-Key` 和 `expectedRouteEpoch`，重复请求返回原 Migration，冲突不会覆盖
较新 Route。

## 验收证据

- 路由纯函数确定性、分区边界单测通过；
- Tenant Route 服务单测覆盖单调 Epoch、过期 expected Epoch、Unsafe 保持源 Route、
  Safe 迁移并提交；
- ownership 单测覆盖旧 Route Epoch 心跳拒绝；
- Control Plane 完整 `check` 通过；
- OpenAPI/Buf/JSON Contract Gate 通过；
- N/N-1 Gate 验证 V040 不含破坏性列操作；
- PostgreSQL 升级夹具验证旧 ownership 数据和 N-1 省略新列的写入均得到 Epoch 1；
- 完整 Integration 验证 Flyway V040、延迟外键事务和 Browser Node/Control Plane
  原有主链无回归；
- 正式 API Integration 验证单 Session `1 → 2` 迁移、8 个 Virtual Partition、
  幂等重放、调度器提交和持久 Session Route。

## 仍需完成

1. Node Command Outbox 的物理分片派发、Worker Lease、Rendezvous Hash 与 Node 双重
   栅栏已在[进度 64](64-Node-Command物理分片派发与双重栅栏.md)完成；HTTP/API、
   Timer、Workflow 等 Session Coordinator 命令的物理 Owner 路由已在
   [进度 83](83-Session-Coordinator物理Shard命令路由闭环.md)完成；
2. 双 Coordinator、高数据库延迟、热点 Tenant 大规模 Session 的长时间并发压测和
   Route 分布容量证书；
3. 迁移 Prometheus 指标、目标 Alertmanager/Pager 到达与运维工作台；
4. 跨 Region Event Bus 与物理 Control Plane 分片故障演练。
