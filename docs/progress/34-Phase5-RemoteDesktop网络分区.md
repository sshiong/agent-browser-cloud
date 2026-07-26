# Phase 5：Remote Desktop 输入网络分区

> 状态：本地确定性故障注入 Gate 已关闭；目标云网络设备/Ingress GameDay 仍待完成
> 日期：2026-07-26
> 验收入口：`make test-e2e`

## 本轮关闭的缺口

此前 E2E 只在用户离开页面、noVNC 正常关闭 WebSocket 后验证未配对 Shift 的服务端
释放。该路径不能证明双向链路成为黑洞时仍可释放输入。

本轮增加：

1. Remote Desktop Gateway 的 Disconnect Grace、Heartbeat Interval 和 Client
   Liveness Timeout 可配置，生产环境保持有界下限/上限；
2. Liveness Timeout 必须至少覆盖两个 Heartbeat，拒绝会造成误判的配置；
3. Gateway 单测在输入已发送后停止消费 WebSocket，证明客户端成为黑洞时会被驱逐并
   精确触发一次 Disconnect Barrier；
4. 第二项单测在旧连接超时后的 Grace 内建立新连接，证明旧 Generation 不会错误释放
   新连接；新连接最终断开时才触发一次 Barrier；
5. E2E 在 Vite 与 Gateway 之间加入独立 TCP 故障代理。发送未配对 Shift KeyDown 后
   对代理执行 `SIGSTOP`，双向字节完全冻结，不发送 WebSocket Close；
6. Gateway 心跳超时后，Browser Node 独立执行 CDP `release_all`、x11vnc
   `clear_all` 和 State Resync，再持久发布 `GATEWAY_DISCONNECT`；
7. 恢复代理后，Control Plane 已结束 HumanTakeover，页面可再次发起接管。

测试未使用 Chromium `setOffline()` 作为最终证据，因为该模式会暂停业务帧，但现存
WebSocket 仍可能自动回应 Ping/Pong，不能代表双向传输层分区。

## 默认与生产边界

| 参数 | 默认值 | 生产允许范围 |
| --- | ---: | ---: |
| Disconnect Grace | 2 秒 | 0.5—10 秒 |
| Heartbeat Interval | 10 秒 | 1—60 秒 |
| Client Liveness Timeout | 30 秒 | 5—120 秒 |

E2E 使用 200ms/100ms/300ms，只缩短故障注入等待，不改变生产默认值。

## 验收证据

已通过：

```bash
cargo test --locked --manifest-path apps/browser-node/Cargo.toml \
  -p remote-desktop-gateway
make ci
make test-e2e
make test-integration
```

关键输出：

```text
WEB_CONSOLE_E2E_OK
WEB_CONSOLE_VIEWER_RBAC_OK
real_web_console_e2e=true
coordinator_final_term=4
audit_chain_valid=true
audit_events=96
```

## 仍未完成

1. 目标云 Ingress、Service Mesh、NAT 和 CNI 上的丢包/单向分区/连接迁移 GameDay；
2. 多客户端竞争、跨 Region 媒体路由和带宽 Admission；
3. Frame ID/Input Timestamp Alignment、高风险 Stale-frame Guard 和媒体容量证书。
