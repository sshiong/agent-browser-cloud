# 远程桌面 Fence 反馈自适应 FPS

> 日期：2026-09-27
> 范围：Gateway 内每个 Viewer 的反馈驱动发送节奏；目标环境与完整展示帧龄 Gate 仍独立。

## 问题与实现

此前 Gateway 能按 Actor/Session 固定 FPS 和码率上限发送，弱网积压时丢弃旧增量、补最新完整帧并临时降低 Tight JPEG 质量，但不知道 Viewer 何时解析了已发送的画面。仅凭固定上限无法按连接及时减慢或恢复发送节奏。

Gateway 现在只对在 SetEncodings 中声明标准 RFB Fence 的 Viewer 发送有界 Fence Request。每个连接同时最多一个未确认 Fence，使用连接内递增编号匹配响应；响应不作为真人输入，也不转发给共享 x11vnc。客户端未协商 Fence 时沿用原固定配额。noVNC 1.7 已在 SetEncodings 中声明 Fence，并在解析后回送响应。

Fence 往返达到 400 ms、超过 2 秒无响应或 Viewer 落后广播队列时，连接私有 FPS 减半，最低 1 FPS；三个快速回执且距上次恢复至少 500 ms 后每次增加 1 FPS，始终受原 Actor/Session 策略上限约束。自适应等待只作用于当前连接；原 Actor 共享码率与 FPS 配额仍独立执行，避免同一 Actor 的慢 Viewer 拖低另一个 Viewer。输入路径和真人优先级不读取此等待。

## 验证

- `cargo test -p remote-desktop-gateway`：32 项通过，覆盖分片/未协商/错误 Fence 拒绝、慢回执降速、快速回执恢复、超时、真实 TCP/WebSocket/RFB 回执、同 Actor 两连接的私有调速及旧客户端固定配额。
- `cargo test --workspace` 与严格 `cargo clippy --workspace --all-targets -- -D warnings` 通过。
- OrbStack `Running`、Docker context `orbstack`、Daemon `OS=OrbStack`；`make test-e2e` 的真实 Web/noVNC 八 Actor 与 Viewer RBAC 链通过。
- `make test-integration` 的 PostgreSQL/Redis/MinIO/mTLS/Chromium 完整链通过，包含 `observer_frame_rate_actuator=true`、双 Node 迁移、Coordinator 故障恢复和 Recording/Profile/Agent 回归。

Fence 回执只证明客户端按 RFB 顺序解析了此前消息，**不证明像素已经显示**。V16 要求的 capture/encode/receive/display 时间戳、展示帧龄、基于 Frame ID 的输入围栏，以及目标 Linux 弱网长稳和硬件 Codec 仍未完成；不能把本次 FPS 调节当作这些 Gate 的证据。
