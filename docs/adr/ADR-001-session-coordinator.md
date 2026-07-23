# ADR-001: 采用 Session Coordinator

## 状态

Accepted

## 背景

需要一个机制来保证同一 Session 不会有并发写操作。多个组件（Agent、Human、System）可能同时尝试操作浏览器。

## 决策

采用 Session Coordinator 模式：
- 每个 Session 有且只有一个逻辑 Coordinator
- Coordinator 串行处理所有状态转换
- 使用 Exclusive Operation 保证同一时刻只有一个写操作
- PostgreSQL 作为权威状态存储

## 后果

### 优点
- 状态转换清晰，易于理解和调试
- 不会出现并发写冲突
- 故障恢复路径明确
- 审计追踪完整

### 缺点
- 单点瓶颈，需要 Shard 支持规模化
- Coordinator 重启期间 Session 暂时不可用
- 需要处理 Coordinator 双主问题

## 实现

- 使用 PostgreSQL CAS 更新 coordinator_term
- Browser Node 拒绝旧 Term 的命令
- 使用 Transactional Outbox 发布事件
