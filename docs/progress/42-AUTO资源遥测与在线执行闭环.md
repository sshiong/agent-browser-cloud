# AUTO 资源遥测与在线执行闭环

> 日期：2026-07-27
> 状态：CPU/内存/Memory PSI/Input Ledger 的 5 秒真实遥测、同节点 Cgroup 在线扩缩容、
> Safe Point、休眠、持久跨 Node 迁移和可恢复资源 SSE 已完成；完整指标与目标双 Node
> E2E 待完成

## 本轮完成

### Browser Node 真实采集

- Runtime Supervisor 从当前 Runtime 进程和委派 Cgroup v2 读取：
  - CPU 累计使用量和当前 CPU 上限；
  - `memory.current`；
  - `memory.pressure` 的 `some avg10`；
  - `pids.current`。
- Node Agent 对健康 Runtime 每 5 秒计算一次真实 CPU 使用率，并通过现有 mTLS
  `NodeEventService.ReportSessionResources` 上报。
- 上报绑定 `node_id`、`tenant_id`、`session_id` 和当前 `context_epoch`；Control Plane
  拒绝旧 Context、错误 Tenant 和非当前 Placement Node。
- 未能从 Runtime/Cgroup 取得的 Renderer、Tab、主线程阻塞、Agent Action、State Diff、
  Profile I/O、Extension、Remote Desktop 与 Media 指标保持为空，不使用模拟值。

### Resource Actuator 与 Operation

- 新增 `AdjustRuntimeResources` Node Command 和
  `RuntimeResourcesAdjusted` Node Event。
- Control Plane 的 30 秒决策循环新增：
  - P95 + EWMA + 最小持续时间扩容；
  - 20 分钟低负载窗口缩容；
  - 5 分钟调整冷却期；
  - 扩容步长快于缩容步长；
  - 已有 Active Operation 时等待，不重复提交调整。
- 调整请求先创建真实 `RESOURCE_ADJUSTMENT` Active Operation 并写入 Outbox。
- Runtime Supervisor 只在委派 Cgroup v2 可用时执行 `cpu.max`、`memory.high`、
  `memory.max` 和 `pids.max` 更新；无资源执行边界时 fail-closed。
- 内存/PID 缩容会先校验当前使用量；部分写入失败会尽力恢复旧限制。
- Browser Node 只有在 Cgroup 全部调整成功后才发送 ACK Event。
- Control Plane 收到且验证 Node、Context、Operation、旧资源快照均匹配后，才更新
  `browser_placements`、Policy 当前模板和资源时间线。
- 非 Durable Workflow 的 Active Operation 增加 1 秒 Deadline Scanner；Node 不可用或
  命令长期失败时 Operation 会进入 `TIMED_OUT`，不会永久占用 Session。

### GitHub 工作流稳定性

- Kind N/N-1 Job 的源码 Checkout 改为完整历史，确保 `HEAD^` 可用于构建 N-1 Operator。
- Integration Smoke 为 Browser Node 注入确定性的零压力 PSI 目录，避免 GitHub Runner
  宿主机瞬时 PSI 使 `pressureState=NORMAL` 断言随机失败。
- 生产默认采集周期保持 5 秒；覆盖精确故障注入时序的长 Integration Smoke 将周期设为
  300 秒，避免新增遥测 RPC 改变原有故障窗口。遥测入口由聚焦 gRPC 测试覆盖，目标
  Linux 上的 5 秒持续上报仍列入专用 E2E 验收。
- 修复 Node 重启后 CDP 端口从固定起点复用的竞态：分配器会逐个执行真实回环绑定探测，
  跳过仍占用或处于回收窗口的端口，避免 Readiness 误连旧服务后产生伪 Crash/Recovery。
- Integration Smoke 继续严格验证 Node 重启只产生一次 Recovery、Context Epoch 为 5、
  Browser Generation 为 3；没有通过放宽断言隐藏竞态。

## 已验证

- `make contracts-check`
- `./gradlew -p apps/control-plane test`
- `cargo test --locked --manifest-path apps/browser-node/Cargo.toml --workspace`
- `make test-integration`
- `make ci`
- Runtime Cgroup 单测验证初始限制和在线调整后的精确文件值。
- gRPC 单测验证 Node Session Resource Sample 进入正式资源服务。

## 尚未完成

1. Renderer、Tab、主线程阻塞、Agent Action、State Diff、Profile I/O、Extension、
   Remote Desktop Frame Age 和 Media Encoder 的真实采集器。
2. State Collector 预算、Media Encoder Slot、Remote Desktop 码率和 Extension Weight
   的独立在线执行器。
3. Safe Point 已覆盖 Input/Drag、HumanTakeover、Agent Task 和 Durable Workflow；
   上传下载、表单、支付、安全和应用关键事务仍缺真实信号生产者。
4. 跨 Node 核心链已实现；仍缺双真实 Browser Node + S3 + Chromium 的故障注入和长稳证书。
5. `WAIT_SAFE_POINT_MIGRATE`、`HIBERNATE` 和 `TERMINATE_STRICT` 已进入真实执行链；
   业务恢复非 READY 时继续保持 Agent 暂停。
6. Resource Event SSE、断线游标与 Web 资源轮询替换已完成；State/Audit 通用事件层
   与跨 Region Event Bus 尚未完成。
7. 目标 Linux 多 Session 长稳、缩容抖动、OOM/磁盘满即时保护和多 Node 容量验收。
