# AUTO 成功 Trace 动态采样闭环

> 完成日期：2026-07-29
> 状态：仓库内真实 Node 执行、ACK、PostgreSQL、API/Web 与自动化验收闭环

## 关闭的缺口

达到资源上限时，平台此前会降低 State Collector、桌面、Media、Extension 和标签页开销，
但 Browser Node 的成功命令 Trace 仍按固定方式输出。Telemetry 本身可能在资源压力期
放大 CPU、I/O 和存储压力，也无法从 Node ACK 证明采样策略已经生效。

本轮完成：

1. `V045` 在 `browser_placements.success_trace_sample_percent` 持久化 Node 权威确认值；
2. 正常 Session 默认采样 100%，`MAXIMUM_NON_CORE_MITIGATION` 将成功 Trace 降为 10%；
3. Browser Node 以 Session + Message ID 执行稳定 FNV-1a 采样，同一 Trace 重试不会随机
   改变采样结果；
4. 只有成功命令 Trace 进入采样器；失败、Crash、Audit、Operation、Billing 和资源事件
   不经过该采样器；
5. Node 执行策略后在 `RuntimeResourcesAdjusted` 返回旧值和新值，Control Plane 校验
   Placement 旧值与 `1..100` 边界后才提交 PostgreSQL；
6. Session 重启通过 `StartRuntime` 恢复 Placement 值，普通资源调整恢复为 100%。

## 真实执行语义

Browser Node 为每个运行 Session 保存当前成功 Trace 百分比。成功命令完成且 Event 已
交付后，以命令 `message_id` 作为 Trace ID 做确定性采样，并向现有 `tracing` 管线输出：

```text
target = browsercloud.success_trace
trace_id = command.message_id
session_id
command_type
success_trace_sample_percent
```

失败命令继续无条件输出 `warn`，因此降低成功 Trace 不会吞掉故障证据。该执行器不会修改
Audit、Operation、Crash、Billing 或资源时间线的持久化逻辑。

## Operation 与滚动升级

执行链为：

```text
RESOURCE_ADJUSTMENT REQUESTED
→ Node Command Outbox
→ Browser Node 设置 Session Trace Sampler
→ RuntimeResourcesAdjusted ACK
→ Placement + Resource Event COMMITTED / FAILED
```

Protobuf 使用带 presence 的可选字段：

- `StartRuntimeCommand.success_trace_sample_percent = 26`
- `AdjustRuntimeResourcesCommand.success_trace_sample_percent = 21`
- `RuntimeResourcesAdjustedEvent.old/new_success_trace_sample_percent = 31/32`

新 Control Plane 收到 N−1 Node 缺失字段的 ACK 时保留原 Placement 值；旧 Node 会忽略
新命令字段。V045 是带 100 安全默认值的 expand-only 列，并用
`NOT VALID → VALIDATE` 校验 `1..100`。

## API 与 Web

以下正式投影新增 `successTraceSamplePercent`：

- Browser Placement API
- `GET /api/v1/sessions/{id}/resources`
- Resource Event 的 old/new resources

Session Resource Panel 以文字显示“Node 采样 N%”以及“失败与强制证据始终保留”。
Web 不根据压力状态猜测百分比，也不在前端执行随机采样。

## 验收证据

- Browser Node 单测验证默认 100%、10% 稳定采样分布、重试确定性和非法值拒绝；
- Control Plane 定向测试验证最大降载命令发送 10%、ACK presence/边界和 Legacy 缺失；
- Browser Node Workspace Test、Fmt、Clippy；
- Web format、lint、13 个测试文件/42 项测试和 production build；
- OpenAPI、Buf、V045 N/N−1 Gate；Evidence Hash：
  `54d4aed6b919f64d5c8a389e985d3b41e6de6a61be3dcb3ca155de6347cfa1bf`；
- 完整 PostgreSQL/Browser Node Integration 验证 V045、Node ACK、Placement、
  Resource API 和 Resource Event 均为 10%。

## 仍需完成

1. Observer 帧率已由
   [进度 69](69-AUTO-Observer帧率在线执行闭环.md)关闭；真实 Recording Worker、
   有界队列、Storage Helper 提交和视频录制停止已由进度 70 关闭；
2. Agent/Observer 成功截图频率执行器；
3. 正式 OpenTelemetry Collector/Exporter、Tail Sampling 和跨服务 Trace Context
   属于完整 Observability 平台能力；本轮关闭的是 Browser Node Session 级成功命令
   Trace 背压执行器；
4. 目标 Linux 多 Session Trace 吞吐、存储预算和 Collector 故障长稳矩阵。
