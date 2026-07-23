# Phase 1—2：Browser Runtime 与 Session Coordinator

## Phase 1 已完成的 PoC

- Rust Node Agent 提供 `Ping` 与 `Dispatch` gRPC。
- `ChromiumRuntimeSupervisor` 持有 Child 句柄，限制 Session ID 和工作目录，支持启动、停止、进程探针与降级健康状态。
- Node 在进程内拒绝旧 `coordinator_term`，并按 `message_id` 去重。
- Input Sandbox、State Collector、Network Helper、Storage Helper、Browser Supervisor 已有可编译接口和部分单元测试。

## Phase 2 已完成的 PoC

- Session、Context、Exclusive Operation 的 V16 核心字段已进入领域模型、Proto、JPA 与 SQL。
- Session 创建拥有 PostgreSQL 权威幂等记录；同 Key 同请求返回原资源，不同请求返回 409。
- 列表、详情、启动和终止执行租户归属校验。
- Session 写路径使用行锁、`context_epoch` CAS 和单 Session Active Operation 唯一索引。
- Coordinator 事务内只写 Node Command Outbox；后台任务通过 gRPC 投递，并含超时、退避、最大重试和 Dead Letter 字段。
- Coordinator Ownership 使用 PostgreSQL 条件 Upsert 和心跳续约。
- API 错误不泄露堆栈，并带 Request ID、`no-store` 和基础安全响应头。

## 集成测试已经证明

- 全新数据库迁移成功；
- 幂等重放与幂等冲突符合契约；
- 租户列表隔离与跨租户 403；
- Start Operation 创建成功；
- Node Command 被 Browser Node ACK，并写入 `published_at`。

## Gate 缺口

- 真实 Chromium CDP Readiness、Crash Recovery 与资源统计。
- SQLite Node Journal；当前去重和最高 Term 仍只保存在内存。
- Node 尚未回传 `RuntimeStarted/Stopped/Failed`，因此 Operation 可能长期保持 Active。
- 持久 Inbox/Node Dedup、Domain Outbox 消息总线 Publisher/Consumer、重放与 DLQ 演练。
- noVNC、Input Broker、State Collector 和 HumanTakeover 状态重同步。
- 多 Coordinator 并发抢占、Outbox Claim 与故障注入的完整验收。
