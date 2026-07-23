# ADR-002: PostgreSQL 作为权威状态

## 状态

Accepted

## 背景

系统有多种数据存储需求：Session 状态、操作记录、事件日志、缓存等。需要明确哪些数据是权威的。

## 决策

PostgreSQL 作为权威状态存储：
- Session Context
- Exclusive Operation
- Coordinator Ownership
- Durable Workflow
- Audit Events

Redis 仅用于：
- 缓存（可重建）
- 路由信息
- 短期状态
- 幂等键

## 后果

### 优点
- 单一事实来源，避免数据不一致
- 事务保证 ACID
- 成熟的故障恢复机制
- 明确的数据权威性

### 缺点
- 写操作有延迟（相比纯内存）
- 需要处理 PostgreSQL 故障

## 实现

- 所有关键写操作在同一 PostgreSQL 事务中完成
- Redis 清空不会导致永久数据丢失
- 使用 Transactional Outbox 模式
