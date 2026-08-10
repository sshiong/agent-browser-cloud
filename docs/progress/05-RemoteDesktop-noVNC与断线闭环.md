# Remote Desktop：noVNC、输入与断线闭环

> 状态：Phase 1 noVNC PoC 与 RUN-002 Gate 已完成；2026-08-10 已升级为普通 VNC/Agent 协作控制。显式 HumanTakeover 仍作为独立交接流程保留。

## 已完成

- Browser Runtime 使用独立 Xvfb Display 启动有界尺寸的图形 Chromium。
- x11vnc 仅监听 Node 回环地址，不向宿主或集群网络直接暴露 VNC TCP。
- Rust `remote-desktop-gateway` 将同源 WebSocket 二进制帧代理到已注册的回环 VNC。
- Control Plane 为 `RUNNING/DEGRADED` Session 的授权 Operator 签发绑定当前
  Session Context 的短期协作 Ticket；普通连接不创建、替换或结束 Agent Operation。
- Ticket 使用 HMAC-SHA256，绑定 Tenant、Session、Actor、Coordinator Term、
  Context Epoch、Access Mode、过期时间和一次性 Nonce；`operationEpoch` 仅作为旧线协议
  兼容字段承载 Context Epoch。
- Gateway 校验 Origin、票据寿命、Nonce 重放、单 Session 单客户端和最大二进制帧。
- WebSocket Ping/Pong 检测半开连接；两秒断线宽限允许瞬时网络抖动安全重连。
  Heartbeat、Liveness 和 Grace 均有生产配置边界，Liveness 至少覆盖两个 Heartbeat。
- Web Console 使用正式 `@novnc/novnc`，展示真实 Canvas 并转发键鼠输入。
- Browser Node 识别真实 RFB KeyEvent、PointerEvent 和 ClientCutText。仅连接观察不影响
  Agent；真人产生输入后进入两秒优先窗口，Agent 的写动作延后重试并在空闲后自动续行。
- 数据面断线后 Browser Node 独立执行 CDP `release_all` 和 x11vnc `clear_all`，
  随后重采集 Browser State。
- 协作连接断线不会发布 `HumanTakeoverEnded`，也不会移除 Agent Operation；显式
  `EXCLUSIVE_TAKEOVER` 票据仍沿用带 `GATEWAY_DISCONNECT` 的持久结束事件。
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

1. 运行中 Session 无需创建 HumanTakeover 即可签发协作票据；
2. noVNC 完成 RFB 3.8 协商并收到 320×200 像素帧；
3. Canvas 鼠标点击与键盘输入抵达 VNC Server；
4. 只观察画面时 Agent 继续运行；真人键鼠输入期间 Agent 写命令无损延后；
5. 断开连接前只发送 Shift KeyDown，不发送 KeyUp；
6. 暂停独立 TCP 故障代理，在不发送 WebSocket Close 的情况下冻结双向链路；
7. Gateway 心跳超时后触发 x11vnc `clear_all`，Agent Session 保持，之后可再次连接；
8. Gateway 单测确认 Grace 内重连不会被旧 Connection Generation 误释放。

## 仍属后续阶段

- Frame ID / Input Timestamp Alignment 与高风险 Stale-frame Guard。
- WebRTC/H.264、最新帧丢弃策略、媒体资源等级和录制审计。
- 多区域媒体路由、带宽 Admission 和 Media Capacity Certificate。
- 目标云 Ingress/CNI 上的单向分区、丢包和连接迁移 GameDay。
- 目标 Linux 上 Agent 连续写动作与多种真人输入组合的竞争/长稳证书。
