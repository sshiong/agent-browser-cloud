# Phase 1—2：Browser Runtime 与 Session Coordinator

## Phase 1 已完成的 PoC

- Rust Node Agent 提供 `Ping` 与 `Dispatch` gRPC。
- `ChromiumRuntimeSupervisor` 持有 Child 句柄，限制 Session ID 和工作目录，支持启动、停止、进程探针与降级健康状态。
- Chromium 只有在 `/json/version` 返回合法 Browser 与 WebSocket 地址后才报告启动成功；
  Health 同时检查进程和 CDP，并提供 PID、RSS、虚拟内存与 CPU 采样。
- `CdpStateCollector` 已通过 `/json/list` 和 CDP WebSocket 实现真实页面导航与状态采集，
  返回 URL、Title、交互目标、Role、Name、Bounds、可见性、State Version 和内容哈希。
- State Collector 只接受 Node 回环地址，密码输入不会把值采集到目标名称。
- Node 使用 SQLite Journal 持久拒绝旧 `coordinator_term`，并按 `message_id` 去重。
- Node 在 Start/Stop 成功后生成 `RuntimeStarted/RuntimeStopped` Protobuf 事件，
  事件发送失败时持久保留结果并由后台扫描器重投。
- Runtime 主动监控检测进程退出并生成持久 `BrowserCrashed` Event。
- Input Sandbox、State Collector、Network Helper、Storage Helper、Browser Supervisor 已有可编译接口和部分单元测试。

## Phase 2 已完成的 PoC

- Session、Context、Exclusive Operation 的 V16 核心字段已进入领域模型、Proto、JPA 与 SQL。
- Session 创建拥有 PostgreSQL 权威幂等记录；同 Key 同请求返回原资源，不同请求返回 409。
- 列表、详情、启动和终止执行租户归属校验。
- Session 写路径使用行锁、`context_epoch` CAS 和单 Session Active Operation 唯一索引。
- Coordinator 事务内只写 Node Command Outbox；后台任务通过 gRPC 投递，并含超时、退避、最大重试和 Dead Letter 字段。
- Coordinator Ownership 使用 PostgreSQL 条件 Upsert 和心跳续约。
- Control Plane 提供独立 Node Event gRPC 服务；事件限制为 128 KiB，
  Payload 白名单解析上限为 64 KiB。
- Node Event 携带 Tenant、Coordinator Term、Context Epoch、Operation Epoch、
  Sequence 和 Browser Generation；Coordinator 在写入前逐项校验。
- 事件处理在单一事务内完成 Session 行锁、Inbox 去重、Context/State 提交和
  Operation Commit。
- API 错误不泄露堆栈，并带 Request ID、`no-store` 和基础安全响应头。

## 集成测试已经证明

- 全新数据库迁移成功；
- 幂等重放与幂等冲突符合契约；
- 租户列表隔离与跨租户 403；
- Start Operation 创建后由 `RuntimeStarted` 事件提交为 `COMMITTED`，Session
  进入 `RUNNING` 并记录 Node、Runtime Build、Context Epoch 和 Browser Generation；
- Terminate Operation 由 `RuntimeStopped` 事件提交为 `COMMITTED`，Session
  进入 `TERMINATED`；
- 两个 Node Command 均被 ACK 并写入 `published_at`，两个 Node Event 均写入 Inbox。
- SQLite Journal 关闭并重开后仍保留去重结果、最高 Term、Event Sequence 和待投事件；
- 真实 Chromium 可启动、通过 CDP Probe、导航本地页面、采集按钮/输入框并干净停止。

## Gate 缺口

- 自动 Crash Recovery 与 Node 重启后的存量 Runtime 进程对账。
- State Collector 尚未通过正式 Node Command/Event 和 Control Plane State API 暴露。
- 真实输入执行、断线 All-keys-up、Key Up Loss 和 500 次 Runtime 循环验收。
- Domain Outbox 消息总线 Publisher/Consumer、重放与 DLQ 演练。
- noVNC、Input Broker 和 HumanTakeover 状态重同步。
- 多 Coordinator 并发抢占、Outbox Claim 与故障注入的完整验收。
