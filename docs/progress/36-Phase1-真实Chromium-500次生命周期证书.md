# Phase 1：真实 Chromium 500 次生命周期证书

> 状态：Phase 1 连续启动/终止 500 次 Gate 已关闭；Phase 6 并发 Browser Capacity 未关闭
> 日期：2026-07-26
> 源码提交：`e59e24a35bbbf4da3e3f4c1520c470d858d7af0d`
> 证据：[runtime-capacity-e59e24a.json](../evidence/capacity/runtime-capacity-e59e24a.json)

## 验收负载

新增 `runtime-capacity-certificate`，必须至少执行 500 次才允许生成通过证书。正式运行
使用 Google Chrome `150.0.7871.182`，负载模型为：

1. 单 Node、顺序 Headless；
2. 同一 Session ID，验证 Browser Generation 与 Supervisor 长期状态增长；
3. 每轮独立 Profile/Cache，禁止复用上一轮残留；
4. 每轮真实等待 CDP `/json/version` Ready 并验证 Runtime Health；
5. 采样主进程及携带该 Profile 的整棵 Chrome 进程树；
6. Stop 后验证主 PID 消失、所有 Profile 子进程消失、CDP 端口可重新绑定；
7. Profile 目录必须可删除；
8. 记录 Runner 前后 RSS 与打开的文件描述符。

## 证书结果

```text
RUNTIME_CAPACITY_CERTIFICATE_OK
cycles=500
start_p99_ms=708
stop_p99_ms=20
rss_growth_bytes=6356992
fd_growth=0
```

| Gate | 结果 |
| --- | ---: |
| 完成全部真实循环 | 500/500 |
| Cycle Failure | 0 |
| 残留 Profile 进程 | 0 |
| Start P99 / 限制 | 708ms / 15s |
| Stop P99 / 限制 | 20ms / 10s |
| 峰值 Runtime 进程树 RSS / 限制 | 957,857,792 / 2,147,483,648 bytes |
| 峰值 Runtime 进程数 / 限制 | 8 / 64 |
| Runner RSS 增长 / 限制 | 6,356,992 / 67,108,864 bytes |
| Runner FD 增长 / 限制 | 0 / 8 |

证书内全部九个 Gate 为 `true`，失败列表为空。Canonical Payload SHA-256 为：

```text
aa06b74b7e13c166bc98995747e8b4368082d6a57d3402d20650308d7f684838
```

## 运行入口

```bash
make test-browser-runtime-capacity \
  REAL_CHROMIUM_PATH="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" \
  RUNTIME_CAPACITY_CYCLES=500
```

该入口不会进入通用 GitHub Runner CI，因为它必须使用明确版本的真实浏览器和专用容量
节点；二进制本身继续由 `cargo test/clippy --all-targets` 构建检查。

## 仍未完成

1. Linux 目标 Browser Node 的 Cgroup v2 CPU/Memory/PID/IO 硬限制；
2. 并发 Runtime 密度、PSI、OOM/Pressure 驱逐和安全余量；
3. Extension Weight/Probation、GPU/Media 和 Anti-affinity；
4. Xvfb/x11vnc 桌面模式 500 次与目标云 Node 长稳；
5. 多 Node/多资源等级的正式 Browser Capacity Certificate。
