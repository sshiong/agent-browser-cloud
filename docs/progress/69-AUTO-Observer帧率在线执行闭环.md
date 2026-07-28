# AUTO Observer 帧率在线执行闭环

> 完成日期：2026-07-29
> 状态：仓库内真实数据面节流、失败回滚、Node ACK、PostgreSQL、API/Web 与自动化验收闭环

## 关闭的缺口

达到资源上限时，Control Plane 已能降低 Remote Desktop 码率，但没有独立的 Observer
帧率边界。单纯在 API 返回一个帧率数字并不能降低 Browser Node 数据面开销，也无法
证明 Human Input 未被 Observer 背压阻塞。

本轮完成：

1. `V046` 在 `browser_placements.observer_frame_rate_fps` 保存 Node 权威确认值；
2. 有桌面数据面的 Session 默认 30 FPS，`MAXIMUM_NON_CORE_MITIGATION` 降为 5 FPS；
   无桌面 Session 的值严格为 0；
3. Browser Node 的受控 VNC Gateway 在线限制 Server → Client Observer 转发批次频率；
4. Observer 节流等待不占用 Client → Server Human Input 分支，输入优先级保持高于
   Observer；
5. Cgroup、State Collector、码率、Observer 帧率、标签页或 Trace 策略任一步失败时，
   Node 恢复调整前的资源与数据面策略并返回失败；
6. Node 只在全部执行成功后返回旧值/新值 ACK，Control Plane 校验 Placement 旧值、
   桌面能力和 `0..60` 边界后才提交 PostgreSQL。

## 数据面语义

当前桌面数据面是 RFB TCP 流，不暴露 H.264/WebRTC 编码帧边界。因此执行器限制的是
受控 Gateway 的 Server → Client 转发批次频率，不伪称已经实现编码器级丢帧、关键帧
生成或 `Latest Frame Wins`。

节流器同时满足现有码率与帧率边界，待发送 Observer 数据使用单批有界背压。等待期间
Gateway 仍持续处理：

- Human Input；
- Ping/Pong 与客户端存活检查；
- 断线与接管回收。

这避免“降低 Observer FPS”反向增加输入延迟。

Media-only Session 虽然会启动内部 x11vnc 编码进程，但不会注册到用户桌面 Gateway；
只有 `desktop_required=true` 的 Placement 才能建立 Observer 数据面。反过来，桌面
Placement 如果缺少 Gateway 或 VNC Endpoint 会失败关闭，不能静默退化。

## Operation 与滚动升级

执行链为：

```text
RESOURCE_ADJUSTMENT REQUESTED
→ Node Command Outbox
→ Gateway 在线改变 Observer 转发频率
→ RuntimeResourcesAdjusted ACK
→ Placement + Resource Event COMMITTED / FAILED
```

Protobuf 使用带 presence 的可选字段：

- `StartRuntimeCommand.observer_frame_rate_fps = 27`
- `AdjustRuntimeResourcesCommand.observer_frame_rate_fps = 22`
- `RuntimeResourcesAdjustedEvent.old/new_observer_frame_rate_fps = 33/34`

N−1 Node 忽略新命令字段；新 Control Plane 收到不含字段的旧 ACK 时保持 Placement
原值。V046 使用安全默认值、确定性桌面回填和 `NOT VALID → VALIDATE`，不删除或重命名
旧列。

## API 与 Web

以下正式投影新增 `observerFrameRateFps`：

- Browser Placement API；
- `GET /api/v1/sessions/{id}/resources`；
- Resource Event old/new resources。

Session Resource Panel 显示 Node 确认的 FPS 或“未启用桌面数据面”，并明确 Human
Input 不经过该节流器。Web 不根据状态推算 FPS，也不使用定时器模拟变化。

## 验收证据

- Remote Desktop Gateway 单测验证 1–60 边界、5 FPS 间隔、策略注销，以及 1 FPS
  Observer 背压期间 Human Input 仍在 200ms 内抵达 VNC；
- Control Plane 测试验证最大降载下发 5 FPS、ACK presence/旧值匹配和 Legacy 缺失；
- Browser Node Workspace Test、Fmt、Clippy；
- Web format、lint、单测和 production build；
- OpenAPI、Buf、V046 N/N−1 Gate；Evidence Hash：
  `7636d83ecc2460ce24f4bac214b2383212f844cd599bb3aecc4a14c7a562eed6`；
- 完整 PostgreSQL/Browser Node Integration 验证 V046、Node ACK、Placement、
  Resource API 和 Resource Event 使用同一值。

## 仍需完成

1. 当前没有真实 Recording Worker、独立录制队列和对象存储提交链；停止非必要视频录制
   必须建立在该数据面之上，不能用 `recording=false` 状态伪造；
2. Agent/Observer 成功截图采集与频率执行器；
3. WebRTC/H.264 编码器级 `target_fps`、Latest Frame Wins、关键帧恢复、音频与 GPU
   Helper；
4. 目标 Linux 多 Session Observer 吞吐、输入延迟、断线、策略抖动和长期存储/网络
   成本矩阵。
