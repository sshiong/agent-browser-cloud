# 单上游 RFB Fan-out 与慢消费者隔离闭环

> 日期：2026-08-12
> 状态：代码、定向测试、GitHub CI 和真实 noVNC 关键路径已完成；完整 E2E 的后续非 RFB Agent UI 时序仍有已知抖动。

## 目标

普通 VNC 连接是 Agent 的观察和辅助通道。多个真人页面可以同时查看同一个 Browser，
真人发生真实输入时短时优先，Agent 保持原 Operation 并在输入停止后继续。多个 Viewer
不能线性增加 x11vnc 上游连接或互相施加背压。

## 已完成

- `RemoteDesktopGateway` 按 Session 创建一个共享 RFB Hub；所有协作和只读连接复用同一条
  回环 x11vnc TCP 连接，不再为每个 WebSocket 创建一个上游编码上下文；
- Gateway 终止下游 RFB 3.8 握手，统一 noVNC 使用的 32-bit little-endian True Color
  像素格式，并在共享上游明确协商 Raw Encoding；不兼容像素格式或不支持 Raw 的客户端
  失败关闭，不允许改变其他参与者的上游协议状态；
- Hub 解析有边界的 FramebufferUpdate、Bell 和 ServerCutText 完整消息后再广播，不会把
  任意 TCP 分片交给晚加入客户端；单消息和完整 Framebuffer 均限制为 32 MiB；
- Hub 合并多个客户端的 FramebufferUpdateRequest，并严格保持最多一个上游帧请求在途；
  修复了两个 noVNC 页面各自请求下一帧导致上游反馈放大、健康连接误判为慢消费者的问题；
- 每个连接持有独立的容量 4 广播游标和 5 秒 WebSocket 写超时。连接落后时只终止该连接，
  不阻塞 Hub、Agent 或其他 Viewer；
- Hub 持有由增量 Raw Rectangle 合成的最新完整 Framebuffer。晚加入客户端先收到当前完整
  基线，再订阅后续广播，不会从半条 RFB 消息或依赖前一 Viewer 的状态开始解码；
- KeyEvent、PointerEvent 和 ClientCutText 走独立容量 32 的有界低延迟输入通道；刷新流量
  不会阻塞真人输入。只读票据在 Node 继续拒绝真实输入；
- 单连接精准撤销、最多 8 个协作者、显式 HumanTakeover 排他撤销、最后连接断开后的
  All-keys-up/State Resync，以及两秒重连宽限均保持原语义；仅打开或观察 VNC 不创建
  HumanTakeover，也不停止 Agent。

## 已验证

- Rust Gateway 18 项测试通过，覆盖单上游双客户端、刷新请求合并、晚加入完整基线、增量 Rectangle 合成、
  有界广播 Lag、只读输入拒绝、不兼容协议拒绝、精准撤销、断线清键、黑洞超时和重连宽限；
- `cargo clippy -p remote-desktop-gateway --all-targets -- -D warnings` 通过；
- 完整 `make test`、`make lint` 和 `make test-integration` 通过；Integration 从空库顺序执行
  90 个迁移，最终 `health=UP`、`public_tables=106`、`node_events_inbox=70`、
  `node_command_published=51`、`audit_chain_valid=true`；
- GitHub Linux 冷机构建首轮使黑洞/重连测试的 30 ms 客户端存活窗口在输入转发或 Pong 前
  到期；生产 10 秒/30 秒心跳参数未修改，仅把测试时钟扩为 20 ms Heartbeat、200 ms
  Liveness 和 500 ms Grace。Gateway 18 项测试随后本地连续运行 10 轮全部通过；修复提交
  `2144f74` 的 GitHub 主 CI `31578702250` 已完整通过 Linux Verify、集成冒烟、对象存储
  GameDay 和 Kubernetes Operator E2E，Desktop `31578702285` 的 macOS/Windows 构建也已通过；
- 真实 Web/Control Plane/PostgreSQL/Browser Node/noVNC E2E 已越过本轮全部关键断言：两个
  同时在线 noVNC 页面均为 `RFB LIVE`，Fake x11vnc 日志只有一个完成握手的上游连接；
  像素、鼠标、键盘和网络分区清键抵达真实数据面；VNC 在线时 Agent 可执行，真人连续输入
  时任务保持 `RUNNING / HUMAN_INPUT_PRIORITY`，停止输入后同一 Operation 到达
  `COMPLETED`；
- 完整 E2E 在上述关键路径之后曾两次停在高风险任务创建、一次停在结构化任务执行按钮，
  都表现为页面未发出预期 HTTP 请求且没有 HTTP 4xx/5xx 或 Console Error。UI 创建成功后
  本身会清空动作，因此没有用多余的测试操作掩盖该时序抖动；它不影响已经越过并留下
  x11vnc/Control Plane 证据的本轮 RFB 断言，但完整 `make test-e2e` 仍不能计作通过。

## 仍未完成

1. 带宽和帧率当前仍是 Session 级资源 Actuator；每 Actor/connection 独立配额、租户策略
   解析和超额审计尚未实现；
2. 共享上游当前为有界 Raw Encoding；生产压缩编码的无状态 Fan-out/转码成本验证仍需在
   目标 Linux x11vnc 上完成；
3. 跨 Region Desktop Relay/Workflow；
4. 目标 Linux 正式 Chromium/x11vnc 的 8 Client 长稳、输入法/剪贴板竞争、弱网和告警
   到达演练；
5. 完整 E2E 后续 Agent UI 创建/执行按钮偶发不发请求的时序治理。
