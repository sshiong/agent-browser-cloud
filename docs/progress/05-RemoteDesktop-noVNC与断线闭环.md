# Remote Desktop：noVNC、输入与断线闭环

> 状态：Phase 1 noVNC PoC 与 RUN-002 Gate 已完成；V16 高级媒体能力不在本里程碑范围。

## 已完成

- Browser Runtime 使用独立 Xvfb Display 启动有界尺寸的图形 Chromium。
- x11vnc 仅监听 Node 回环地址，不向宿主或集群网络直接暴露 VNC TCP。
- Rust `remote-desktop-gateway` 将同源 WebSocket 二进制帧代理到已注册的回环 VNC。
- Control Plane 只为当前 `EXECUTING` HumanTakeover 的匹配 Actor 签发短期 Ticket。
- Ticket 使用 HMAC-SHA256，绑定 Tenant、Session、Actor、Coordinator Term、
  Context Epoch、Operation Epoch、过期时间和一次性 Nonce。
- Gateway 校验 Origin、票据寿命、Nonce 重放、单 Session 单客户端和最大二进制帧。
- WebSocket Ping/Pong 检测半开连接；两秒断线宽限允许瞬时网络抖动安全重连。
- Web Console 使用正式 `@novnc/novnc`，展示真实 Canvas 并转发键鼠输入。
- 数据面断线后 Browser Node 独立执行 CDP `release_all` 和 x11vnc `clear_all`，
  随后重采集 Browser State。
- Node 将断线结果作为带 `GATEWAY_DISCONNECT` 原因的持久
  `HumanTakeoverEnded` 事件发送；过期事件由 Control Plane 拒绝并在 Node 端终止重投。
- 生产环境启动时拒绝默认 Ticket Secret 和空 Origin 白名单。

## 验收证据

```bash
make ci
make build
make test-integration
make test-e2e
docker compose config
```

真实浏览器 E2E 验证：

1. HumanTakeover 到达 `EXECUTING`；
2. noVNC 完成 RFB 3.8 协商并收到 320×200 像素帧；
3. Canvas 鼠标点击与键盘输入抵达 VNC Server；
4. 显式结束接管后完成输入释放、State Resync 和 Operation Commit；
5. 再次接管后只发送 Shift KeyDown，不发送 KeyUp；
6. 直接离开远程桌面后，Gateway 宽限到期并触发 x11vnc `clear_all`；
7. Control Plane 自动提交 HumanTakeover，Session 可以再次接管。

## 仍属后续阶段

- Frame ID / Input Timestamp Alignment 与高风险 Stale-frame Guard。
- WebRTC/H.264、最新帧丢弃策略、媒体资源等级和录制审计。
- 多区域媒体路由、带宽 Admission 和 Media Capacity Certificate。
