# AUTO 独立像素录制与停止执行闭环

> 完成日期：2026-07-29
> 状态：仓库内真实 CDP 录制、Storage Helper 隔离提交、最大资源停止、Node ACK、PostgreSQL、API/Web 与自动化验收闭环

## 关闭的缺口

此前“达到资源上限时停止视频录制”没有可执行的数据面。只在 Placement 或 UI 写入
`recording=false` 会伪造资源调整结果，也无法证明录制文件已经完成提交。

本轮建立并贯通真实的独立像素录制链：

1. 创建 Session 可用正式 `videoRecording` 字段请求录制；
2. V047 分开保存不可混淆的 `video_recording_requested` 与 Node 权威确认的
   `video_recording_enabled`；
3. Browser Node 通过回环 CDP `Page.startScreencast` 独立采集 JPEG 帧，不依赖
   Observer/Remote Desktop 客户端是否连接；
4. 采集到写盘使用 8 帧有界队列，段文件按 4 MiB 或 10 秒滚动；Storage Helper 上传
   期间队列满则丢弃新帧并计数，不允许内存无界增长；
5. Node 只能写 Session 的 `ephemeral/recordings/{recordingId}`。Storage Helper 根据
   Tenant/Profile/Session/Recording ID 推导固定路径，拒绝符号链接、目录逃逸、超限
   文件、大小或 SHA-256 不匹配；
6. 只有 Storage Helper 持有 S3-compatible Object Storage 凭据。每个段先提交
   `.ndjson`，再提交独立 `.COMMITTED` 标记；停止时最后提交 Recording 级
   `COMMITTED`；
7. `MAXIMUM_NON_CORE_MITIGATION` 将当前录制状态设为 false；Node 等待 CDP
   `Page.stopScreencast`、队列排空、剩余段提交和 Recording 最终标记成功后才 ACK；
8. 任一步失败，资源调整返回失败并尝试恢复调整前的录制、Cgroup、State、桌面和
   Tab 策略；Control Plane 在 ACK 前不修改 Placement；
9. Chromium Crash 清理会先注销旧 CDP、释放失效输入代理并 finalize 录制注册项，
   再允许 Control Plane 使用新的 Browser Generation 恢复 Runtime。

## 数据格式和边界

当前产物是分段 NDJSON Pixel Recording。每行包含捕获时间、CDP Session ID、JPEG
Base64 和 CDP Frame Metadata。它是真实可恢复的像素帧归档，但不伪称为 MP4、
H.264/H.265/AV1、WebRTC Recording 或带音频的媒体文件。

对象键隔离为：

```text
tenants/{tenantId}/profiles/{profileId}/sessions/{sessionId}/
recordings/{recordingId}/segments/{sequence}.ndjson
```

每段及整次 Recording 都有 commit-last 标记。Node 不持有 Bucket、Access Key 或
Secret Key，也不能通过 IPC 指定任意本地路径或对象键。

Storage Helper 未配置 Object Storage 时，`videoRecording=true` 的 Runtime 启动
失败关闭；不会退化为本地文件假成功。仓库默认本地 Compose/Kubernetes Base 仍关闭
Object Storage，正式环境必须注入 HTTPS Endpoint、Bucket 和 Secret 后显式启用。

## Operation 与滚动升级

Protobuf 新增 presence 字段：

- `StartRuntimeCommand.video_recording_enabled = 28`
- `AdjustRuntimeResourcesCommand.video_recording_enabled = 23`
- `RuntimeResourcesAdjustedEvent.old/new_video_recording_enabled = 35/36`

N−1 Node 会忽略新增命令字段；新 Control Plane 收到不含 Recording 字段的旧 ACK 时
保持 Placement 原值。V047 只增加带安全默认值的列，并用
`NOT VALID → VALIDATE` 增加约束，不删除、重命名或改变旧列。

## API 与 Web

以下正式投影新增 Recording 状态：

- Create Session Request：`videoRecording`
- Browser Placement：`videoRecordingRequested`、`videoRecordingEnabled`
- `GET /api/v1/sessions/{id}/resources`
- Resource Event old/new resources

创建向导提供“启用独立像素录制”，并说明达到上限时优先停止。Session Resource
Panel 区分“正在录制”“创建时请求但已由资源策略停止”和“创建时未请求”。Web 不读取
本地文件、不模拟帧或自行推算当前状态。

## 验收证据

- Session Recorder 单测使用真实 HTTP `/json/list` 与 WebSocket 握手，验证
  `Page.enable → Page.startScreencast → frame → frameAck → Page.stopScreencast`；
- Rust Workspace Test/Fmt/Clippy 覆盖有界队列、回环限制和 Helper 契约；
- S3-compatible MinIO GameDay 同时验证 Recording Segment、Segment COMMITTED 和
  Recording COMMITTED，并保留超时失败有界；
- Control Plane 测试验证最大资源降载下发 `videoRecordingEnabled=false`；
- Web lint、42 项单测和 production build；
- OpenAPI、Buf、V047 N/N−1 Gate；Evidence Hash：
  `fe0c16ea985f381579c22905109f4efa3ac8e5c9aae6a64092a040ea9ffa666b`；
- PostgreSQL/Browser Node Integration 验证 V047 默认、Start/Adjust ACK、
  Placement、Resource API 和 Resource Event 使用同一状态，并覆盖 SIGKILL 后
  Browser Generation 恢复时不会复用旧录制注册项。

## 仍需完成

1. Agent/Observer 单次截图的独立频率执行器；
2. MP4/WebM 封装、硬件 Codec、WebRTC/音频、Latest Frame Wins 和关键帧恢复；
3. Recording 索引、播放、下载、保留期、Legal Hold、WORM Manifest、敏感区模糊和
   Purpose-bound 访问审计；
4. 录制任务异常后的主动 Node Event/告警；当前异常会令任务结束，并在后续调整或停止
   时 fail-closed 暴露，不会伪报仍在录制；
5. 目标 Linux 多 Session 长时间录制、Object Storage Backpressure、磁盘满、网络分区、
   成本和恢复矩阵。
