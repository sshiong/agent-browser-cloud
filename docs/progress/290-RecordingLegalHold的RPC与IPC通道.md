# Recording Legal Hold 的 RPC 与 IPC 通道

> 日期：2026-10-23
> 基线：b75dbc519117008be151afb4cf85df521e654e2d
> 状态：Node/Helper 通道已接执行器；控制面正式生产者仍待接入。

## 实现

- Protobuf 新增 `ApplyRecordingLegalHold`，请求包含操作、Epoch、精确 Tenant/Profile/
  Session/Recording、Manifest Hash/大小/Segment 数、ON/OFF、前序 Proof 和预算。
  Enum 零值为 UNSPECIFIED，不能解释成 OFF。未复用或修改旧字段号。
- Node 在进入 Helper 前拒绝未指定/未知模式、错误 ID 前缀、零 Epoch、错误 Hash、
  没有前序 Proof 的 OFF、越界 Segment 数和 1—120 秒之外的预算。结果只返回安全
  元数据、Hash、计数、状态和时间，不返回对象路径、Version ID、URL 或凭据。
- 新方法位于既有 mTLS Node Service，派发到现有 Unix Socket Helper。Helper 继续
  先校验 peer UID，再解码/执行；新命令与 Profile 操作共用分片锁，真正的跨 Node
  副作用串行化仍依赖进度 287 的远端 CAS 账本。
- Helper IPC Schema 保持 1，新请求必须显式给出 ON/OFF。新响应为 optional 字段，
  老操作/老响应可继续解码；新 Hold 调用没有精确 Hold 结果时必须失败。
- Helper Client 在请求前校验范围，以执行预算加一秒作为此次调用的有界超时；
  请求 ID/schema 和操作/Epoch/Recording/状态/版本计数/Hash/完成时间均重新校验。
  未知结果不转换为成功，不增加自动输入或存储写重试。
- 正式 REST API/SDK/公共 Schema 数量保持。尚无控制面调用者或 Node 能力宣告；
  此通道不使既有 Legal Hold 策略自动变为已在云端生效。

## 验证

- 共享契约：缺失/未知模式拒绝、旧响应解码为无 Hold 结果。
- 真实 Unix Socket 客户端回归：错误 Epoch、状态、操作、Recording、版本计数、
  Proof、Inventory Hash、时间和缺失结果全部拒绝，合法回执接受；坏请求在连接前
  拒绝。Node 的生产请求转换函数覆盖默认/未知模式、身份和前序 Proof 等拒绝。
- 启用 Object Lock 的真实 MinIO：经过实际 `serve_connection`、peer UID 检查、帧
  解码和执行器完成 ON→OFF，回传关联 Proof；错误 UID 在读取命令前拒绝，解除后
  原物理删除仍成功。不是只有模拟的 IPC 返回值。
- 三项原执行器回归继续执行：历史版本/重放/ON/OFF、并发 CAS、迟到 OFF。默认
  完整 `make test-object-storage` 已包含上述四个环境测试，以及原 Multipart、DR、
  WORM、旧无版本桶和超时恢复。
- Rust Workspace **247 passed / 0 failed / 14 ignored**；环境测试由真实 GameDay
  单独运行，ignored 不算通过。四个相关 Crate 严格 Clippy 通过；Node 校验错误按
  大小装箱，未放宽 lint。
- Buf lint 与相对精确基线的 breaking 检查通过；N/N-1 静态 Gate 增加明确模式、
  Epoch、前序 Proof 和 optional Helper 响应声明。Rust Protobuf 已真实生成并编译；
  Java 生成/编译和完整平台集成仍需本提交 Linux CI 验证。

新增全路径 debug 测试曾在默认测试线程栈溢出；测试改为生产 Helper 已有的 8 MiB
栈配置后通过，没有提高生产栈上限或减少断言。

基线 b75dbc5 的主 CI **39823822283**、Desktop **39823822034** 已读取最终精确 SHA
JSON：Verify、扫描、PostgreSQL、Integration/GameDay、Operator 与 Windows/macOS
全部成功。正式 CI 日志还确认三项 MinIO Hold 测试实际执行。该证据不替代本次
Node/RPC/IPC 改动自己的 CI。

私有证据：`/tmp/agentbrowser-hold-transport-workspace.log`、
`/tmp/agentbrowser-hold-transport-clippy-v2.log`、
`/tmp/agentbrowser-hold-rpc-final-test.log`、`/tmp/agentbrowser-hold-ipc-minio-v2.log`、
`/tmp/agentbrowser-hold-transport-gameday.log`、
`/tmp/agentbrowser-perception-ci-final.json` 与对应退出记录。

## 仍须完成

1. PostgreSQL 权威队列、Gateway、幂等派发、审计与 PENDING/UNKNOWN/VERIFIED 投影。
2. 策略 API/四语言 SDK/UI：保存策略不能被展示为云保全已完成。
3. 删除授权/迟到录制/策略变更的原子联锁、未知远程 Intent 核对/恢复、批量与规模。
4. 由真实控制面通过 mTLS 调用新 RPC 的端到端验收、N−1 能力准入、目标 AWS
   账户/IAM/Object Lock/Legal Hold、跨 Region 和生产环境 Gate。

当前不发布能力宣告或全量生产结论。原十一项目标、组织与许可证决策继续保留。
