# 远程桌面帧龄语义与 Viewer 积压指标

> 日期：2026-09-28
> 范围：Gateway、Node 资源报告、Control Plane 资源压力判断及 N−1 协议兼容。

## 问题与改动

原 `remote_desktop_frame_age_ms` 表示距上游最后 RFB 帧的时间。Gateway 在 Viewer 连接或消费缓存帧时误刷新该时间，使旧画面看起来很新；静止网页又会让正确的上游帧龄持续增长，不能将其直接解释为 Viewer 卡顿。

Gateway 现在只在真正收到上游 FramebufferUpdate 时刷新上游帧时间；RFB Bell、ServerCutText 等非图像消息继续转发，但不能让旧画面显得新。新增逐连接、逐 Session 的最早待确认 Fence 时间，在 Viewer 成功发送帧 Fence 后开始计时，仅匹配的回执清除；旧客户端无 Fence 时保持空值，断开和注销时清理。这个指标代表传输/绘制队列积压，本仓库 noVNC 的回执已延至 Canvas flush 和下一动画帧；仍不等于物理屏幕呈现时间。

内部 Protobuf 新增可选字段 38 `remote_desktop_unacknowledged_frame_age_ms`。字段 21 保持原上游帧龄语义，旧 Control Plane 可安全忽略 38；新 Control Plane 只将 38 映射到现有资源样本的桌面压力字段，旧 Node 缺少 38 时为空，避免静止页面被错误扩容。公开 OpenAPI 和数据库结构没有变化。

## 验证与剩余边界

- 真实 TCP/WebSocket/RFB Gateway 回归覆盖缓存帧重放、晚加入 Viewer、上游 Bell 非图像消息、不支持 Fence、延迟回执及匹配回执后的清理；Gateway 32 项、Node Agent 27 项通过。Bell 补充回归后的 32 项与严格 Clippy 再次通过。
- Java gRPC Endpoint 定向回归覆盖 21 与 38 同时出现时只采用 38，以及 N−1 Node 仅发 21 时不形成 Viewer 压力；资源压力既有测试通过。
- Protobuf lint/generate、严格 Rust Clippy、Java Spotless 检查通过。macOS arm64 本机 Gradle 1.62.2 gRPC 插件缓存为 x86_64，定向 Java 测试使用 Buf 已生成的同一契约源码并跳过该本机插件。
- 提交 `d97b4b1` 的 [主 CI](https://github.com/sshiong/agent-browser-cloud/actions/runs/36382780657) 全部成功，包含 Verify、Integration smoke test 与 Kubernetes Operator E2E；[桌面 CI](https://github.com/sshiong/agent-browser-cloud/actions/runs/36382780667) 的 macOS/Windows 均成功。

Viewer 端到端展示帧龄、Frame ID 输入围栏、目标 Linux 八客户端长稳和硬件 Codec 尚未完成；这些需要独立证据。
