# AUTO 细粒度资源信号与决策迟滞

> 日期：2026-07-28
> 状态：首批六类细粒度真实信号、多指标决策迟滞、State Collector Budget 和
> Remote Desktop Bitrate 执行器和 Browser/Profile I/O 真实生产者已完成；
> Extension 指标/Weight 与 x11vnc Media 编码指标/Slot 执行器已完成

## 本轮目标

把资源接口中已经存在但长期为空的 Browser、Agent、State 和 Remote Desktop 字段
接到真实运行路径，并让 Control Plane 在 30 秒聚合决策中实际使用这些信号。任何无法
采集的字段继续为空，Web 不生成曲线，Control Plane 不把空值当成零。

## 已完成

### Browser 与页面压力

- State Collector 调用 Chromium `/json/list` 获取真实 Page Target 数作为 Tab 数。
- 通过 Browser WebSocket 执行 `SystemInfo.getProcessInfo`，统计真实 Renderer Process。
- 对最多 32 个 Page Target 执行 `Performance.getMetrics`，累计 `TaskDuration`；
  Node Agent 保存上次累计值并上报相邻 5 秒采样差值。
- 整个 CDP 细粒度采集有 2 秒总超时。CDP 不可用时，CPU、RSS、PSI 基础上报继续，
  细粒度字段为空，不阻塞资源心跳。

`mainThreadBlockedMs` 当前代表 CDP `TaskDuration` 的采样差值，是主线程执行压力近似值，
不是 Long Tasks API 的精确阻塞时长。

### Agent 与 State 压力

- Agent Executor 在真实 Action 执行路径记录延迟；每个资源窗口上报窗口最大值并清空。
- `BrowserStateDiff` 与 `DiffTruncated` 在进入 Node Journal 后增加待交付深度。
- 只有 Control Plane 接受 Event、Node Journal 标记 Delivered 后，队列深度才减少。
- Node 启动时从最多 10,000 条未交付 Journal Event 重建深度；达到扫描上限时记录告警。

这使 State Diff 压力能跨短时网络故障和 Node 进程重启保存，不再依赖内存瞬时计数。

### Remote Desktop 压力

- Remote Desktop Gateway 在有客户端的 Session 上记录最近一次 VNC Server Frame。
- Node Agent 上报当前 Frame Age；无活跃客户端时字段为空。
- 连接断开后清理帧龄状态，避免空闲 Session 被误判为远程桌面质量恶化。

### 多指标决策与迟滞

Control Plane 对以下真实或未来可用字段统一执行持续时间、P95、EWMA 判断：

- CPU、内存和 Memory PSI；
- Renderer 与 Tab；
- 主线程 `TaskDuration` 差值；
- Agent Action 延迟；
- State Diff 队列深度；
- Profile I/O；
- Extension CPU/内存；
- Remote Desktop Frame Age；
- Media Encoder 负载。

持续高压才触发扩容。缩容窗口内只要任一次级指标仍超过较低迟滞阈值，就保持
`OBSERVING / SECONDARY_LOAD_WITHIN_SCALE_DOWN_WINDOW`，防止 CPU/内存回落后过早缩容。

## 已验证

```text
cargo fmt --all --check
cargo test --locked --manifest-path apps/browser-node/Cargo.toml --workspace
cargo clippy --locked --manifest-path apps/browser-node/Cargo.toml --workspace --all-targets -- -D warnings
./gradlew -p apps/control-plane test
./gradlew -p apps/control-plane check
make test-integration
```

聚焦测试覆盖：

- Fake CDP 返回 Target、Process 与 Performance 指标；
- Remote Desktop 代理收到 Server Frame 后产生帧龄，断连后清理；
- Agent 窗口选择最大真实执行延迟；
- Node Journal 重启后恢复 State Diff 深度，ACK 后归零；
- 持续 Agent 延迟在 CPU/内存低位时仍触发扩容；
- Remote Desktop 次级压力阻止缩容。

## 尚未完成

1. Browser/Profile I/O、Extension 聚合和 x11vnc Media Encoder CPU 已由 Linux
   Cgroup v2 子组真实生产者补齐；硬件 Codec/GPU 指标仍缺目标实现。
2. State Collector Budget、Remote Desktop Bitrate、Extension Weight、Media Slot、
   回滚和 Node ACK 语义已完成；独立 CDP Pixel Recording、有界队列和上限停止已由
   进度 70 完成，编码器动态码率与封装仍未完成。
3. Long Tasks/页面主线程阻塞的更精确采集；当前使用 CDP `TaskDuration` 差值。
4. 目标 Linux 的多 Session 5 秒遥测长稳、缩容抖动和 OOM/磁盘满即时保护证书。
5. 双真实 Browser Node + S3 + Chromium 的迁移故障注入与长稳证书。
6. 上传下载、表单、支付、账号安全和应用关键事务的 Safe Point 信号生产者。

## 下一步

下一步在目标 Linux 和双真实桌面 Browser Node 上验证 Media Cgroup/Slot、
Remote Desktop Bitrate、迁移和业务恢复的故障矩阵；如产品需要 WebRTC/录制，再引入
独立硬件编码 Helper。
