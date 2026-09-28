# 远程桌面 Frame ID 来源与绘制回执

> 日期：2026-09-28
> 范围：上游 RFB FramebufferUpdate、Gateway 广播/完整基线、Viewer Fence 与 noVNC 绘制回执。

## 改动

Gateway 为每个共享上游的真实 FramebufferUpdate 分配单调 `u64` Frame ID，并将 ID 与本机单调采样时间绑定在同一帧对象上。增量广播与最新完整基线共用该来源；弱网 Viewer 丢帧后读取完整基线时保留来源 ID，不从当前全局时间或像素内容猜测身份。Bell/ServerCutText 作为非图像消息继续传递，不分配图像 ID，也不生成图像 Fence。

协商标准 RFB Fence 的 Viewer 在 Gateway 签发的图像回执中收到私有 `ABCF` 16 字节载荷：4 字节前缀、4 字节回执序号、8 字节来源 Frame ID。Gateway 同时核对序号与 ID，错误或过期回执不会调整连接私有 FPS。Web/Tauri 共用 noVNC 补丁在 Canvas flush 与下一动画帧后原样回复 16 字节载荷；旧 Gateway 的 8 字节私有 Fence 仍沿用相同绘制后回执。补丁 Hash 已同步锁文件。每个协商回执的图像在后续图像进入发送队列前获得独立 Fence；等待回执时不继续消耗该 Viewer 的广播帧，超时后重新订阅并发送最新完整基线。动态画质切换会退役旧 Fence，并给替换基线签发新 Fence。未协商 Fence 的旧 Viewer 沿用原有转发方式。

## 验证与边界

- Gateway 32 项通过，包含真实 TCP/WebSocket/RFB 回执中 Frame ID `1 → 42`、错误 ID 拒绝、未确认时阻止下一图像，以及超时后跳过旧增量并恢复最新 Frame ID `44`；严格 Clippy 通过。
- Web 145 项、noVNC 协议 4 项、Lint、生产构建、`pnpm install --frozen-lockfile` 通过。

这一步建立可核对的帧来源和协商 Viewer 的逐图像绘制回执。输入尚未携带 `based_on_frame_id`，Gateway 尚未在高风险点击时计算该输入的有效帧龄、拒绝旧坐标并释放按键状态；Viewer 也尚未展示来源到绘制的帧龄。物理显示器呈现、目标 Linux 八客户端长稳与硬件 Codec 仍是独立 Gate。
