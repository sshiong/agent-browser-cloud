# State Collector 与远程桌面在线执行器

> 日期：2026-07-28
> 状态：State Collector Budget 与 Remote Desktop Bitrate 已形成真实决策、执行、
> 回滚、ACK、持久化和 UI 展示闭环；Extension Weight 与 Media Encoder Slot 待实现

## 已完成

### 权威模型与兼容契约

- V027 在 `browser_placements` 持久化 `state_collector_budget_percent` 与
  `remote_desktop_bitrate_kbps`，约束有效范围并为已有桌面 Placement 安全回填。
- 新建 Placement 默认使用 50% State Collector 预算；桌面 Session 默认 8000 Kbps，
  无桌面 Session 固定为 0。
- Start/Adjust Command 与 Adjusted Event 使用 Protobuf optional 字段扩展，旧 Node
  可忽略新命令字段，新 Control Plane 也接受未携带新字段的旧 ACK。
- Session Resource API、OpenAPI、Web 类型和详情面板显示 PostgreSQL 权威值，不使用
  localStorage、内存状态或模拟曲线。

### 真实在线执行

- State Collector 按 Session 保存预算，并实际影响：
  - 浏览器状态采集 cadence；
  - State Diff 最大字节数和最大变更数；
  - 单轮 CDP Page Target 采样上限。
- Remote Desktop Gateway 按 Session 保存码率上限，对真实 VNC Server→WebSocket
  二进制流分块并按 Kbps 计算发送节奏。该能力是网关传输限速，不等同于编码器码率控制。
- 无桌面 Session 的 `0 Kbps` 是有效 no-op，不会错误调用未注册的桌面会话。

### Operation、ACK 与回滚

- Control Plane 扩容时提高 State Collector 预算，并在桌面 Session 上提高网关码率；
  缩容使用更慢窗口和更保守步长。
- Node 按 Cgroup → State Collector → Remote Desktop 顺序执行。任一后续步骤失败或
  Event Sequence 生成失败，会恢复已应用的旧值。
- ACK 携带实际旧值与实际新值；Control Plane 校验 Node、Placement 快照、Policy 上限
  和非 Cgroup 范围后才更新 Placement 与 `ALLOCATION_ADJUSTED` 时间线。
- Linux 生产节点继续要求 Cgroup。无 Cgroup 的本地开发节点不会宣称 CPU/内存调整
  成功，而是回报原值，并允许真实可用的非 Cgroup 执行器独立完成。
- 修复首次 Placement 解析内部模板时误写 `lastAdjustedAt` 的问题；初始化不再触发
  5 分钟调整冷却，持续高压可在首个有效决策窗口扩容。

## 已验证

- State Collector 单测验证 25% 预算改变 cadence，并将 Diff 上限从
  60000 bytes/200 changes 降为 15000 bytes/50 changes。
- Remote Desktop Gateway 单测验证 250 Kbps 下 1 KiB 数据产生至少 30 ms 的真实
  传输延迟，断连与输入清理测试继续通过。
- Control Plane 测试验证首次 Placement 不进入冷却期、新旧 Protobuf ACK 兼容与字段
  校验。
- PostgreSQL 17 集成实际应用 V001—V027；持续 61 秒的高 CPU 样本触发真实
  Resource Operation，Node 将 State Collector 从 50% 调为 75%，Control Plane
  仅在 ACK 后提交 Placement 和资源事件。
- 完整 Integration Smoke 在资源调整后继续通过 Coordinator Failover、HumanTakeover、
  Crash Recovery、Node Restart、Profile、Proxy、安全治理和审计链路。

## 尚未完成

1. Extension Resource Weight 的独立运行时执行器与真实 Extension CPU/内存生产者。
2. Media Encoder Slot 的独立 Helper/执行器与真实编码负载生产者。
3. 双真实桌面 Browser Node + Chromium + S3 下的码率调整、迁移与故障注入 E2E。
4. 目标 Linux 多 Session 的 5 秒采集、扩缩容抖动、OOM/磁盘满即时保护和长稳证书。
5. 当前 Remote Desktop 能力控制网关传输速率；编码器级动态码率仍属于 Media 缺口。
