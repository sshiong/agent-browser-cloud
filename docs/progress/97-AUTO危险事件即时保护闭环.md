# AUTO 危险事件即时保护闭环

> 日期：2026-08-01
> 适用范围：Browser Node、Runtime Supervisor、Control Plane、Agent Task、Operation
> 数据来源：Linux Cgroup v2、Profile 文件系统、真实 Runtime 健康探针、PostgreSQL

## 本轮结论

AUTO 资源控制不再只处理 30 秒聚合窗口。Browser Node 现在能够从真实 Cgroup v2
`memory.events`、Profile 所在文件系统容量和持续 CDP 健康失败中识别危险事件，并通过
既有 mTLS gRPC 上报到 Control Plane。危险事件会立即暂停 Agent；需要调整资源或停止
Runtime 时，继续复用正式 Operation、Node Command、ACK 和持久事件链，不由前端或
Node 直接修改控制面状态。

普通 CPU、内存或页面负载尖峰仍只进入滑动窗口、EWMA/P95、持续时间和冷却决策，
不会走危险事件旁路。

## 已完成

### 1. Node 真实危险信号

- Runtime Supervisor 读取 Session Cgroup 的 `memory.events`：`oom` 与 `oom_kill` 使用
  单调计数器；
- Node Agent 维护每个 Session 的 OOM 基线，只有计数增加才上报 `OOM`，避免每 5 秒
  重放同一个历史事件；
- Chromium 因 Cgroup OOM Kill 退出时，在清理 Cgroup 前读取计数，并把 Crash Event
  分类为 `OOM`；普通进程退出分类为 `BROWSER_PROCESS_EXIT`；
- 使用 `statvfs` 读取当前 Profile Ephemeral 文件系统的真实可用/总容量；可用空间低于
  64 MiB 或 1% 时上报 `DISK_FULL`，不使用内存数据或固定曲线；
- CDP 连续 5 次健康探针失败才上报 `BROWSER_UNRESPONSIVE`，单次探针失败不升级为
  危险事件；恢复为 Healthy 后重新计数；
- Stop、Crash、Node 关闭时清理 OOM 基线，防止新 Runtime 继承旧计数。

### 2. Control Plane 即时保护

所有危险事件先暂停状态为 `RUNNING` 的 Agent Task，Browser 是否保留由故障类型决定：

| 危险事件 | 即时行为 | 后续行为 |
| --- | --- | --- |
| `OOM`，Runtime 仍在线且未达上限 | 暂停 Agent | 立即请求有界内存扩容，走 `AdjustRuntimeResources → ACK → COMMITTED` |
| `OOM` 已达上限 | 暂停 Agent、保留 Browser | 标记 `AGENT_PAUSED`，不因单个 Session Cgroup OOM 自动终止 |
| OOM/普通 Crash 已导致 Runtime 退出 | 暂停 Agent | 交给既有 Recovery Operation，资源时间线保留真实原因 |
| `BROWSER_UNRESPONSIVE` | 暂停 Agent | 标记 Browser Recovery，不直接伪造恢复或终止 |
| `DISK_FULL` | 暂停 Agent | 先通过真实资源调整 Operation 停止录制/降采样/冻结后台标签等非核心开销；告警持续且降载已提交后，进入受控终止 Operation |
| `SECURITY_ISOLATION_FAILURE` | 暂停 Agent | 立即持久化终止意图并通过正常 Stop Runtime Operation 执行 |

磁盘满和隔离失效的自动终止只允许由内部危险事件入口触发；公开资源策略仍默认
`PAUSE_AGENT`，普通用户不能借此绕过平台管理员权限选择严格预算终止。

### 3. 可恢复的持久派发

- 资源策略先写入 `DANGER_*_TERMINATION_REQUIRED`，再由
  `SessionResourceDecisionExecutor` 派发终止 Operation；
- gRPC 上报在样本事务提交后立即尝试派发；定时决策器会先重试遗留的 Pending 意图，
  Control Plane 在提交与派发之间重启也不会永久丢失动作；
- 成功派发写入 `DANGER_ACTION_DISPATCHED`，带真实 Operation ID 和
  `PENDING_NODE_STOP`；
- Runtime Crash Event 进入 Inbox/Coordinator 事务时同步写入资源保护状态，Crash
  数据面与资源时间线不再割裂；
- 近期危险样本不会覆盖更具体的 `DANGER_*` 原因，便于 UI、Audit 和值班定位。

## 验收证据

- `cargo test --workspace`：Browser Node 全工作区通过；真实 Chromium 与真实对象存储
  条件测试按既有 Gate 明确跳过；
- Control Plane 全量 `test + spotlessCheck` 通过；新增 OOM 即时扩容、Agent Pause、
  隔离失效持久终止意图、危险终止 Operation、gRPC 即时派发和 OOM Crash Inbox 测试；
- `make ci` 通过：Java/Rust/Web、Clippy、OpenAPI/Proto、N−1 Gate、50k Coordinator
  容量证书和四语言 SDK 全部通过；
- `make build` 通过；
- `make test-integration` 通过：PostgreSQL、双 Control Plane、三 Browser Node、mTLS、
  Crash Recovery、AUTO 执行器、迁移和对象存储主链没有回归。

## 仍未完成

- 仍缺目标 Linux 使用真实 Cgroup OOM 注入、真实磁盘耗尽、长时间 CDP Hang、磁盘恢复
  和节点级资源竞争的长期 GameDay 证书；
- 当前磁盘信号按 Profile 所在文件系统聚合，尚未区分 Core/Ephemeral/Object Storage
  的独立配额与路径级 I/O 归因；
- `SECURITY_ISOLATION_FAILURE` 的控制面处理已闭环，但具体 LSM/容器运行时/集群安全
  监控 Producer 仍依赖目标部署环境；
- 危险事件告警仍需接入真实 Pager/值班渠道和目标环境 Burn Rate；
- 本轮不关闭真实 IdP、KMS/HSM、多 Region、GPU/LSM、桌面签名和组织发布 Gate。

因此本轮关闭的是 AUTO 的代码级危险事件采集、即时保护和持久 Operation 派发缺口，
不等同于目标 Linux 故障注入证书或 V16 全量生产发布验收。
