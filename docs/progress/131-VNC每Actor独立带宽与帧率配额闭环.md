# VNC 每 Actor 独立带宽与帧率配额闭环

> 日期：2026-08-12
> 状态：代码、合同、四语言 SDK 与本地定向验证已完成；目标 Linux 8 Client 长稳仍是生产 Gate。

## 目标

普通 VNC 是 Agent 的观察和辅助通道。连接 VNC 不创建 HumanTakeover、不终止 Agent；只有
真实键盘、鼠标或剪贴板输入触发短时真人优先。多个观察者共享单条上游 RFB 时，还必须避免
某个 Actor 通过多个浏览器窗口消耗整个 Session 的桌面输出预算。

## 已完成

- Control Plane 为控制连接和只读连接分别解析受管配置，默认分别为 `8000 Kbps / 30 FPS`
  与 `4000 Kbps / 15 FPS`；四个值均支持生产环境变量覆盖，并在启动时验证范围；
- 每次连接把 `actorBitrateLimitKbps` 和 `actorFrameRateLimitFps` 写入短期、单次 HMAC
  签名票据。前端只能展示服务端返回值，不能自行提高配额；
- Browser Node 严格校验票据中的带宽 `250—100000 Kbps` 和帧率 `1—60 FPS`。滚动升级时，
  缺少新字段的旧票据使用保守兼容缺省值，不会被误判为独占接管；
- Gateway 使用 `Tenant + Session + Actor` 作为配额键。同一 Actor 的多个连接共享同一个
  Leaky-Bucket 时间预算，不能通过打开多个窗口绕过限制；不同 Actor 的预算互相隔离；
- 实际输出采用 `min(Session AUTO Actuator, Actor 签名上限)`。资源策略仍可在线降低整个
  Session 的桌面码率/Observer FPS，但一个慢观察者不会挤占其他 Actor；
- 配额只作用于 RFB Server → WebSocket Client 画面转发。真人输入继续走独立有界输入队列，
  Agent 输入也不被桌面画面节流；真人输入仍只获得两秒优先窗口，原 Agent Operation 保持
  `RUNNING` 并在空闲后续行；
- 最后一个同 Actor 连接断开时清除该 Actor 的转发预算；Session 注销时清除全部 Actor
  状态，不保留跨 Session 的内存配额状态；
- OpenAPI、Web/Tauri 共用类型和四语言 SDK 已增加 additive 配额投影；旧 Control Plane
  响应仍可被新 Web 接受。noVNC 在线标识会显示权威 Actor 配额。

## 已验证

- Control Plane 定向测试通过，覆盖控制/只读票据配额签名、响应投影、签名校验和既有
  Agent/HumanTakeover 边界；
- Rust Gateway 19 项测试通过，新增用例证明同 Actor 连续预留会累积预算，而不同 Actor
  不继承该等待时间；旧票据 additive 缺省值测试通过；
- Web 70 项测试通过；OpenAPI 和四语言 SDK 已重新生成并通过结构/哈希验证；
- `make lint`、`make test`、`make contracts-check` 和 `make test-upgrade-compatibility` 通过，
  包含 Java 全量 Check、Rust Workspace Clippy `-D warnings`、Web ESLint/Prettier 和 Go Vet；
- `make test-integration` 通过：空库 92 个迁移后 `health=UP`、`public_tables=107`，双 Node
  迁移、资源调整/晚到 ACK 对账和 Audit Chain 全部为真。

## 仍未完成

1. 配额目前是 Control Plane 生产配置，不是 Workspace PostgreSQL 可编辑策略；租户级覆盖、
   管理员 UI、变更审计与超额计量事件尚未实现；
2. 目标 Linux 正式 Chromium/x11vnc 的 8 Client 长稳、弱网、输入法/剪贴板竞争和告警到达；
3. 跨 Region Desktop Relay/Workflow；
4. 生产压缩编码的无状态 Fan-out/转码成本验证。
