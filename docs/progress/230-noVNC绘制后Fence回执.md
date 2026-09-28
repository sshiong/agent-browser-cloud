# noVNC 绘制后 Fence 回执

> 日期：2026-09-28
> 范围：Web/Tauri 共用 noVNC 1.7 Viewer 对 Gateway 私有帧 Fence 的回执时机。

## 问题与改动

progress 228 的 Gateway 按标准 RFB Fence 回执调整每连接 FPS，但上游 noVNC 1.7 在解析 Fence 后立即回送。该信号没有覆盖异步图像解码和 Canvas 绘制队列，弱网调速因此可能把慢绘制 Viewer 当成快 Viewer。

本仓库以 pnpm 可重现的依赖补丁，只对 Gateway 的 8 字节 `ABCF` 帧 Fence 等待 noVNC `Display.flush()` 完成和下一次 `requestAnimationFrame`，随后才回复标准 RFB Fence。断开的连接不回送迟到回执。其他服务器的 Fence 仍立即按 noVNC 原行为回复；Gateway 与未使用本仓库补丁的旧 Viewer 仍按既有兼容路径工作。依赖版本保持 1.7.0，补丁 Hash 写入锁文件。

## 验证与剩余边界

- Node 协议定向测试覆盖绘制队列未完成、动画帧未到、连接断开和普通 Fence 兼容。
- Web 全量 145 项、补丁协议 3 项、生产构建、Lint、`pnpm install --frozen-lockfile` 通过。
- OrbStack `Running`、Docker context `orbstack`、Daemon `OS=OrbStack`；`make test-e2e` 的真实 Web/noVNC 多协作者与 Viewer RBAC 链通过，输出 `real_web_console_e2e=true` 与 `viewer_rbac_e2e=true`。

回执现在覆盖浏览器画布绘制完成，不证明显示器实际呈现，也没有 capture/encode/receive/display 的端到端时间戳。Viewer 展示帧龄、按 Frame ID 绑定每次输入、目标 Linux 八协作者小时级长稳和硬件 Codec 仍是独立 Gate。
