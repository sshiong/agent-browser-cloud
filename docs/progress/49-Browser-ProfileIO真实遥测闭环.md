# Browser/Profile I/O 真实遥测闭环

> 日期：2026-07-28
> 状态：Browser Node 真实内核生产者、gRPC/PostgreSQL/API、AUTO 决策和 Web 展示已完成；
> 目标 Linux 多 Session 长稳与路径级 Profile 归因仍待验收

## 目标与口径

资源模型原有 `profileIoBytesPerSecond` 协议字段、PostgreSQL 列和 50 MiB/s 持续压力
决策，但 Browser Node 没有真实生产者。本轮用 Linux Cgroup v2 `io.stat` 补齐生产者，
不使用定时器、随机数、前端 Mock 或宿主机全局磁盘指标。

该指标来自 Chromium 进程树所在的独立 Browser 子 Cgroup，包含 Profile、Cache 以及
Chromium 运行时产生的其他块设备 I/O。它是保守的 Browser/Profile I/O 压力信号，
不是按 Profile 目录逐文件归因的精确计费指标。

## 已完成

### Browser Node 内核采集

- Session Cgroup 下新增 `browser` 与 `desktop` 子 Cgroup：
  - Chromium 及其 Renderer 子进程进入 `browser`；
  - Xvfb 与 x11vnc 进入 `desktop`；
  - CPU、内存和 PID 硬限制继续位于父 Cgroup，对全部后代生效。
- 仅当委派根真实暴露 `io` controller 时启用子 Cgroup I/O 统计。
- Runtime Supervisor 读取 Browser 子 Cgroup 的 `io.stat`，跨块设备累加
  `rbytes + wbytes`，返回单调累计字节数。
- Node Agent 以相邻真实采样的累计差和单调时间计算每秒速率；首次样本、计数器回退、
  Runtime 重建或不可读时不产生数值。
- Session Crash、Stop 和 Node Shutdown 会清理速率基线，避免跨 Runtime Generation
  计算错误突增。
- Node 通过 `profileIoTelemetry=browser-cgroup-io-v1` 声明能力；没有 Cgroup/IO
  controller 的节点明确上报 `unavailable`，资源字段保持为空。

### Control Plane 与 Web

- 复用现有可选 Protobuf 字段和 PostgreSQL `profile_io_bytes_per_second` 列，无需破坏性
  数据库迁移，也不会影响 N/N-1 旧 Node。
- gRPC Endpoint 将可选速率原样映射到 `RecordResourceSampleRequest`；缺失值保持
  `null`，不会转换为零。
- 资源 API 的 `usage.profileIoBytesPerSecond` 返回最近一次真实样本。
- 既有 30 秒窗口对该信号执行持续时间、P95、EWMA、快扩慢缩和冷却判断；超过
  50 MiB/s 的一次尖峰不会直接扩容，持续压力理由为
  `SUSTAINED_PROFILE_IO_PRESSURE`。
- Session Resource Panel 新增 Profile I/O 卡片，显示真实速率与压力线；无遥测时显示
  等待 Linux Browser Cgroup I/O，不绘制模拟曲线。

## 验证

```text
cargo test --locked --manifest-path apps/browser-node/Cargo.toml -p runtime-supervisor -p node-agent
cargo clippy --locked --workspace --all-targets --manifest-path apps/browser-node/Cargo.toml -- -D warnings
./gradlew -p apps/control-plane test
pnpm --dir apps/web-console test
pnpm --dir apps/web-console lint
make test-integration
make ci
```

聚焦覆盖：

- 多块设备 `io.stat` 的 `rbytes/wbytes` 累加；
- Browser 与 Desktop PID 分离进入各自子 Cgroup；
- 单调累计计数转换为真实时间速率，计数器回退时重新建立基线；
- gRPC 可选字段到应用请求的无损映射；
- 持续 Profile I/O 压力触发扩容，一次样本不触发；
- 本地无 IO controller 集成环境声明 `unavailable`，证明不会伪造数据。

## 仍未完成

1. 目标 Linux 委派 Cgroup v2 `io` controller 下的多 Session 5 秒长稳、磁盘压力和
   Runtime 重启证书。
2. 如果产品需要精确区分 Profile、Cache、Download 和其他 Chromium I/O，需要增加
   独立挂载/块设备、eBPF 或文件系统级归因；当前不能用于目录级计费。
3. Extension CPU/内存真实生产者与 Extension Resource Weight 执行器；当前 Chromium
   启动仍禁用扩展，不能伪造扩展负载。
4. Media Encoder 真实生产者与 Media Encoder Slot 执行器；当前尚无独立 Encoder
   Helper 数据面。
5. OOM、磁盘满等危险事件的目标 Linux 即时保护与长期故障注入证书。
