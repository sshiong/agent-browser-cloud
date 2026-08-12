# AUTO 资源遥测与在线执行闭环

> 日期：2026-07-27
> 状态：CPU/内存/Memory PSI/Input Ledger、Renderer/Tab、CDP 主线程执行压力、
> Agent Action 延迟、持久 State Diff 深度和 Remote Desktop Frame Age 的 5 秒真实
> 遥测，同节点 Cgroup、State Collector Budget、Remote Desktop Bitrate 在线调整、
> Safe Point、休眠、持久跨 Node 迁移、可恢复资源 SSE 和 Browser/Profile I/O
> 指标已完成；Extension 进程级指标/Weight 与 x11vnc Media 编码指标/Slot 执行器已完成，
> 仓库级双 Node/Object Storage 迁移集成证书已完成；目标 Linux 长稳和硬件编码
> Helper 待完成

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
- State Collector 通过轻量 CDP 采集 Page Target 数、Browser Process 中 Renderer 数，
  并读取 Page `Performance.getMetrics` 的累计 `TaskDuration`；Node Agent 使用相邻
  采样差值上报主线程执行压力，不把该值误述为 Long Tasks 精确计数。
- Agent Executor 记录两次资源报告间真实 Action 执行延迟的最大值；报告后清空窗口，
  避免旧 Action 反复触发压力判断。
- State Diff/Truncated Event 写入 Node Journal 后增加待交付深度，只有 Control Plane
  接受并持久标记 Delivered 后才递减；Node 重启会从最多 10,000 条未交付 Journal Event
  重建深度，超过扫描上限时告警而不是伪造精确值。
- Remote Desktop Gateway 只在真实客户端连接期间记录最近 VNC Server Frame 时间，
  无活跃客户端时保持为空，避免空闲 Session 被误判为帧延迟。
- Browser/Profile I/O 的后续真实生产者已通过 Linux Cgroup v2 `io.stat` 完成，详见
  [进度 49](49-Browser-ProfileIO真实遥测闭环.md)。Extension CPU/内存和 Weight 已在
  [进度 52](52-Extension真实加载遥测与资源权重.md)闭环；x11vnc Media 编码 CPU 和
  Slot 已在[进度 54](54-Media编码遥测与Slot执行闭环.md)闭环。

### Resource Actuator 与 Operation

- 新增 `AdjustRuntimeResources` Node Command 和
  `RuntimeResourcesAdjusted` Node Event。
- Control Plane 的 30 秒决策循环新增：
  - CPU、内存、Memory PSI、Renderer、Tab、主线程、Agent Action、State Diff、
    Profile I/O、Extension、Remote Desktop 和 Media 的 P95 + EWMA + 最小持续时间扩容；
  - 20 分钟低负载窗口缩容；
  - 5 分钟调整冷却期；
  - 扩容步长快于缩容步长；
  - 任一次级指标仍高于缩容迟滞阈值时保持 `OBSERVING`，不因 CPU/内存短时低位缩容；
  - 已有 Active Operation 时等待，不重复提交调整。
- 调整请求先创建真实 `RESOURCE_ADJUSTMENT` Active Operation、V091 资源专用 Ledger 并
  写入 Outbox。Ledger 持久记录 `REQUESTED → EXECUTING → ACKNOWLEDGED →
  COMMITTED/FAILED`；首次真实派发才进入 `EXECUTING`。
- Runtime Supervisor 在委派 Cgroup v2 可用时执行 `cpu.max`、`memory.high`、
  `memory.max` 和 `pids.max` 更新；无 Cgroup 的非生产节点保持原 CPU/内存并在 ACK
  中回报实际值，不把未执行的 Cgroup 请求伪装成成功。
- 内存/PID 缩容会先校验当前使用量；部分写入失败会尽力恢复旧限制。
- State Collector Budget 会按 Session 调整采集 cadence、State Diff 上限与 CDP Target
  上限；Remote Desktop Bitrate 会限制已注册桌面会话的 VNC→WebSocket 数据面速率。
- Browser Node 只有所有适用执行器成功后才发送 ACK Event；后续执行器或 Event Sequence
  失败时会回滚已应用的 Cgroup、State Collector 和 Remote Desktop 值。
- Control Plane 收到且验证 Node、Context、Operation、旧资源快照均匹配后，才更新
  `browser_placements`、Policy 当前模板、通用 Operation 和资源时间线。非法 ACK 被持久化
  为 `FAILED` 并释放写围栏，不会无限重投；详见[进度 127](127-AUTO资源调整持久ACK状态机闭环.md)。
- 非 Durable Workflow 的 Active Operation 增加 1 秒 Deadline Scanner；Node 不可用或
  命令长期失败时 Operation 会进入 `TIMED_OUT`，资源 Ledger 同步记录
  `NODE_ACK_TIMEOUT`，不会永久占用 Session。命令进入 Dead Letter 时也会立即失败 Ledger。
- 已失败调整的迟到 ACK 由精确 Tenant/Session/Operation/FAILED Ledger 证明后终止消费；
  非超时失败只记录 `LATE_ADJUSTMENT_ACK_IGNORED`。`NODE_ACK_TIMEOUT` 后晚到的、可逐字段
  验证且未被后续调整取代的 ACK 会通过独立 `resource.reconcile` Operation 对账 Placement，
  避免 Node 与 PostgreSQL 权威资源漂移；冲突时进入 `CRITICAL` 且不覆盖当前值。详见
  [进度 128](128-AUTO资源失败后迟到ACK终态消费闭环.md)和
  [进度 129](129-AUTO资源晚到ACK权威对账闭环.md)。

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
- `cargo clippy --locked --manifest-path apps/browser-node/Cargo.toml --workspace --all-targets -- -D warnings`
- `./gradlew -p apps/control-plane check`
- `make test-integration`
- `make ci`
- Runtime Cgroup 单测验证初始限制和在线调整后的精确文件值。
- gRPC 单测验证 Node Session Resource Sample 进入正式资源服务。
- Fake CDP 单测验证 Target/Process/Performance 指标来自真实协议响应。
- Journal 重启测试验证 State Diff 深度恢复并在 ACK 后归零。
- 资源决策测试验证持续 Agent 延迟触发扩容、Remote Desktop 压力阻止缩容。

## 尚未完成

1. Browser/Profile I/O、Renderer、Tab、CDP `TaskDuration` 差值、Agent Action、
   State Diff、Remote Desktop Frame Age、Extension CPU/内存和 x11vnc Media CPU
   已完成；仍缺硬件 Codec/GPU 编码 Helper 和目标 Linux 长稳证书。
2. State Collector Budget、Remote Desktop Bitrate、Extension Resource Weight 和
   Media Encoder Slot 在线执行器已完成；独立 CDP Pixel Recording、有界队列和
   上限停止已由进度 70 完成，编码器级动态码率与封装仍未完成。
3. Safe Point 已覆盖 Input/Drag、HumanTakeover、Agent Task、Durable Workflow、
   CDP 上传下载与导航级表单；支付、安全、SPA 和应用关键事务已具备短 Lease Producer
   API，但仍缺各目标业务 Adapter 的真实接入。
4. 跨 Node 核心链与仓库级双 Browser Node + MinIO + CDP 数据面集成证书已由进度 80
   完成；仍缺正式 Chromium/目标 Linux 的节点故障、网络分区和长稳证书。
5. `WAIT_SAFE_POINT_MIGRATE`、`HIBERNATE` 和 `TERMINATE_STRICT` 已进入真实执行链；
   业务恢复非 READY 时继续保持 Agent 暂停。
6. Resource Event SSE、断线游标与 Web 资源轮询替换已完成；State/Audit 通用事件层
   与跨 Region Event Bus 尚未完成。
7. 目标 Linux 多 Session 长稳、缩容抖动、OOM/磁盘满即时保护和多 Node 容量验收。
8. Node 已执行但 ACK 永久丢失时的周期 Resource Readback/Drift Scanner 仍待目标环境闭环。
