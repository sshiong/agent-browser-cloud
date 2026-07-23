# ADR-006：At-least-once + Idempotency

- 状态：Accepted
- 日期：2026-07-23

## 决策

跨进程命令和事件采用 At-least-once Delivery。发送侧使用 PostgreSQL Transactional Outbox，接收侧使用稳定 Message ID、Idempotency Key 与 Inbox/Node Journal 去重。

## 原因

网络、进程和 Coordinator 接管会产生不确定结果，端到端 Exactly-once 无法可靠承诺。重复投递必须安全，外部副作用需要 Receipt 或 Commit Marker。

## 后果

Coordinator 事务内只写状态与 Outbox，不等待 gRPC。Browser Node 拒绝旧 Coordinator Term，并返回重复命令的原结果。当前内存去重只能用于 PoC；进入 Phase 2 Gate 前必须落到 SQLite Node Journal。
