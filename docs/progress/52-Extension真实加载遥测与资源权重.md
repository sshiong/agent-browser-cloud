# Extension 真实加载、遥测与资源权重

> 日期：2026-07-28
> 状态：生产代码闭环；目标 Linux 长稳和逐扩展归因仍是 Gate

## 本轮关闭的缺口

1. `StartRuntimeCommand` 以向后兼容字段携带 Placement 已接纳的 Extension ID 和初始
   `cpu.weight`。Browser Node 只允许从 `NODE_EXTENSION_ROOT` 的直接子目录解析扩展，
   拒绝路径逃逸、目录/Manifest 符号链接、超大或不完整 Manifest。
2. Chromium 不再无条件使用 `--disable-extensions`。无扩展 Session 继续禁用扩展；
   有扩展 Session 使用精确的 `--disable-extensions-except` 与 `--load-extension`，
   不接受前端提交任意文件路径。
3. Linux Cgroup v2 Session 下新增独立 `extension` 子组。Runtime Supervisor 从 Browser
   子组进程读取 `/proc/<pid>/cmdline`，把带 `--extension-process` 的真实 Chromium
   Extension 进程迁入该子组，并从其 `cpu.stat`、`memory.current` 采集累计 CPU 与 RSS。
4. Node Agent 按相邻样本和单调时钟计算 Extension CPU 百分比；首次样本、计数器回退、
   非 Linux 或无委派 Cgroup 时保持字段为空，不生成模拟值。Node 能力标签使用
   `extensionTelemetry=extension-cgroup-v1/unavailable`。
5. V031 将 `extension_cpu_weight` 持久化到 Browser Placement。持续 Extension 压力进入
   既有 P95/EWMA/最小持续时间决策后，Control Plane 通过 Operation 下发在线权重调整；
   Node 只有实际写入 Cgroup 后才在 ACK 中回报新值。无 Cgroup 的集成节点明确回报旧值，
   Control Plane 不会把请求值伪装为已应用值。
6. Session Resource API 和 Web 详情显示当前 Extension Weight、CPU 压力与 RSS；资源
   时间线的新旧资源快照包含权重。

## 协议与滚动兼容

- `StartRuntimeCommand.extension_ids = 20`
- `StartRuntimeCommand.extension_cpu_weight = 21`
- `AdjustRuntimeResourcesCommand.extension_cpu_weight = 15`
- `RuntimeResourcesAdjustedEvent.old/new_extension_cpu_weight = 21/22`

新增标量均为 `optional`，扩展集合为默认空的 `repeated` 字段。N-1 Node 会忽略未知字段，
新 Node 收到旧命令时使用安全默认值或保持当前值。V031 只有带默认值的新增列和约束。

## 已通过的证据

```text
cargo test --locked --manifest-path apps/browser-node/Cargo.toml -p runtime-supervisor
./gradlew -p apps/control-plane test
pnpm --dir apps/web-console test
pnpm --dir apps/web-console lint
pnpm --dir apps/web-console build
make contracts-check
python3 tests/upgrade/n-minus-one-gate.py
make test-integration
```

集成测试使用真实 PostgreSQL、Flyway V031、gRPC 和 Browser Node，记录并断言 Chromium
启动参数包含可信扩展目录，同时验证无 Cgroup 环境下权重仍保持 `100`，证明 ACK 没有
伪造执行结果。Cgroup 进程分类、CPU/RSS 读取、权重写入和回滚由 Rust 单测覆盖。

## 明确未完成

1. 当前遥测是所有 `--extension-process` 的 Session 级聚合，不是逐 Extension 计费；
   Content Script 若与页面 Renderer 共享进程，也不会被误报为 Extension 子组专属 CPU。
2. 仍需在目标 Linux 委派 Cgroup v2 节点上使用真实 Chromium/真实企业扩展完成多 Session
   长稳、进程重分类竞态、OOM/PSI 和资源权重效果证书。
3. Media Encoder 仍没有独立 Helper、真实负载生产者和 Slot 在线执行器；Remote Desktop
   网关码率不能替代 Media Encoder Slot。
