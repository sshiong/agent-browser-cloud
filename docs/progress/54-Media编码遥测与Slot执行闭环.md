# Media 编码遥测与 Slot 执行闭环

> 日期：2026-07-28
> 状态：仓库内真实 Producer、Slot 执行、Operation/ACK、持久化、API/UI 与集成闭环完成；
> 目标 Linux 长稳、硬件编码 Helper 和编码器级动态码率仍是生产 Gate

## 本轮关闭的缺口

1. Browser Node 不再固定上报 `media_encoder_percent: None`。当前 Remote Desktop
   数据面的真实 RFB 编码进程 `x11vnc` 进入独立 `media` Cgroup，Runtime Supervisor
   从该子组 `cpu.stat` 读取累计 CPU，Node Agent 按相邻样本、单调时钟和当前 Slot
   计算编码负载百分比。首次样本、计数器回退、无委派 Cgroup或未分配 Slot 时保持为空。
2. `RuntimeResourceLimits` 新增当前 `media_encoder_slots`。Slot 变化会真实调整
   Media 子 Cgroup 的 `cpu.weight`；写入失败由既有 Cgroup 回滚逻辑恢复旧值。Xvfb
   继续位于 Desktop 子组，只有 x11vnc 编码进程进入 Media 子组。
3. Placement 的容量语义被明确拆开：
   - `media_slots`：节点 admission 时预留的最大并发上限，用于容量释放；
   - `media_encoder_slots`：当前已执行的分配，可在 `1..media_slots` 内在线调整。
4. V032 以带默认值的新增列、回填和 `NOT VALID → VALIDATE` 约束持久化当前 Slot。
   非 Media Placement 必须为 0；Media Placement 必须至少为 1 且不超过预留上限。
5. AUTO 决策复用既有 Media P95、EWMA、最小持续时间、缩容迟滞和冷却期。扩容每次增加
   一个 Slot且不超过预留上限，缩容每次减少一个且不会降到 0。
6. Start/Adjust Command 与 Adjusted Event 使用向后兼容 optional 字段。Node 只把实际
   应用的旧/新 Slot 放入 ACK；Control Plane 校验 Node、Operation、旧 Placement 快照
   和 Slot 上限后，才提交 Placement 与资源时间线。
7. Session Resource API 和 Web 详情新增当前/最大 Slot与真实 Media Encoder 压力。
   页面没有定时器、随机数或本地状态生成媒体曲线。
8. Node 容量标签新增
   `mediaTelemetry=x11vnc-cgroup-v1/unavailable`。声明 `NODE_SUPPORTS_MEDIA=true`
   时必须同时具备认证 Slot 和 x11vnc Desktop Runtime，避免只报容量却没有执行器。

## 协议与滚动兼容

- `StartRuntimeCommand.media_encoder_slots = 22`
- `AdjustRuntimeResourcesCommand.media_encoder_slots = 16`
- `RuntimeResourcesAdjustedEvent.old_media_encoder_slots = 23`
- `RuntimeResourcesAdjustedEvent.new_media_encoder_slots = 24`

四个字段均为 `optional`。N-1 Node 会忽略未知字段；新 Node 收到旧命令时使用 0 或保持
当前值；新 Control Plane 接受未携带 Slot 的旧 ACK，并保持已有 Placement 值。

## 已通过的证据

```text
make contracts
python3 tests/upgrade/n-minus-one-gate.py
cargo test --locked --manifest-path apps/browser-node/Cargo.toml --workspace
cargo clippy --locked --manifest-path apps/browser-node/Cargo.toml --workspace --all-targets -- -D warnings
./gradlew -p apps/control-plane test check
pnpm --dir apps/web-console lint
pnpm --dir apps/web-console test
pnpm --dir apps/web-console build
make test-integration
```

- Rust Cgroup 单测验证 Media 子组、CPU 计数、进程归属、Slot 权重写入和在线调整。
- Control Plane 测试验证 optional ACK 映射、N-1 缺字段兼容和当前 Slot 不得超过预留
  Placement 上限。
- Integration Smoke 使用真实 PostgreSQL、Flyway V032、mTLS gRPC、Browser Node、
  fake Xvfb 和真实 RFB 协议测试服务器启动 Media Session；Placement/API 明确断言
  `mediaSlots=1`、`mediaEncoderSlots=1`。无 Cgroup 的普通 Session 在资源 Operation
  后仍保持 Slot 0，证明控制面没有伪造执行结果。

## 明确未完成

1. 当前 Producer 是仓库现有 x11vnc/RFB 编码路径，不是独立硬件 H.264/H.265/AV1
   Encoder Helper；也没有逐 Codec、逐帧编码队列和 GPU 显存指标。
2. 当前 Slot 执行器通过独立 Media Cgroup CPU 权重约束真实编码进程；还需目标 Linux
   多 Session 验证权重效果、CPU 抢占、OOM/PSI 和编码质量/帧龄关联。
3. Remote Desktop Gateway 的 Kbps 是传输限速，不等于编码器动态码率；真正的编码参数、
   独立 CDP Pixel Recording、有界录制队列和 Storage Helper 提交已由进度 70
   实现；编码器级封装、播放和目标环境 Media Storage Backpressure 仍需完成。
4. 仓库级无桌面双 Node + Object Storage 迁移恢复已由进度 80 完成；仍缺双真实桌面
   Browser Node + 正式 Chromium 的 Media 迁移、恢复和长稳证书。
