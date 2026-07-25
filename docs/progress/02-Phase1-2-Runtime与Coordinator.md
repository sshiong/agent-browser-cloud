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
- Coordinator 将 Crash 转换为高优先级 `RECOVERY` 排他 Operation，重启同一
  Runtime Build；恢复成功后提交 Operation、递增 Context Epoch/Browser Generation
  并返回 `RUNNING`。
- Recovery 一小时最多三次，达到预算后熔断为 `FAILED`。
- SQLite Runtime Lease 持久化 PID 启动身份与 Generation；Node 重启会清理身份匹配的
  孤儿进程、恢复 Generation 下界并向 Control Plane 发起自动恢复。
- Runtime Start/Stop 的 ACK、Event 与 Lease 状态原子提交，Node 不会观察到只完成一半的
  生命周期副作用。
- Input Sandbox 通过 CDP `Input.dispatchMouseEvent/Input.dispatchKeyEvent` 执行真实键鼠输入，
  单 Session 串行化、sequence 去重/拒绝陈旧输入，并在 Runtime 停止时释放全部按键与按钮。
- Runtime Monitor 每秒检查 Input Ledger，5 秒无活动且仍有按下/拖拽状态时自动执行
  All-keys-up / All-buttons-up；CDP 鼠标位掩码与常用非文本按键已显式映射。
- Node 每两秒采集一次页面状态；仅在内容哈希变化时生成持久
  `BrowserStateUpdated`，并复用 SQLite Journal 的失败重投路径。
- Network Helper、Storage Helper、Browser Supervisor 已有可编译接口和部分单元测试。

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
- Browser State 事件经同一 Inbox/版本门禁写入 PostgreSQL JSONB 最新状态；
  REST API 在校验 Session 租户归属后返回状态，尚无状态时返回 204。

## 集成测试已经证明

- 全新数据库迁移成功；
- 幂等重放与幂等冲突符合契约；
- 租户列表隔离与跨租户 403；
- Start Operation 创建后由 `RuntimeStarted` 事件提交为 `COMMITTED`，Session
  进入 `RUNNING` 并记录 Node、Runtime Build、Context Epoch 和 Browser Generation；
- Terminate Operation 由 `RuntimeStopped` 事件提交为 `COMMITTED`，Session
  进入 `TERMINATED`；
- 集成流程中的 Start、两次 Recovery、Stop 共四个 Node Command 均被 ACK；
  Runtime Start/State、进程 Crash、Recovery Start/State、Node Restart
  Reconciliation、第二次 Recovery Start/State、Stop 共九个 Event 写入 Inbox。
- Browser State 的 URL、Title、Quality、Version、Target Role 与 Bounds 从 CDP
  经 Node Event、PostgreSQL 到 REST API 完整可读；
- SQLite Journal 关闭并重开后仍保留去重结果、最高 Term、Event Sequence 和待投事件；
- 实际强杀 Chromium 后 Session 自动恢复到 Context Epoch 2 / Browser Generation 2；
  随后重启 Browser Node，Runtime Lease 对账使 Session 再恢复到 Epoch 3 /
  Generation 3，两个 Recovery Operation 均为 `COMMITTED`；
- 真实 Chromium 可启动、通过 CDP Probe、导航本地页面、采集按钮/输入框并干净停止。

## Gate 缺口

- 跨网络输入心跳/断线信号尚未形成正式契约；本地 5 秒空闲释放已接入，但仍需完成
  端到端 Key Up Loss 故障注入和 500 次 Runtime 循环验收。
- Domain Outbox 消息总线 Publisher/Consumer、重放与 DLQ 演练。
- noVNC、HumanTakeover 与接管后的 State Resync。
- 多 Coordinator 并发抢占、Outbox Claim 与故障注入的完整验收。
