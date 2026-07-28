# AUTO 非特权扩展后台暂停闭环

> 完成日期：2026-07-29
> 状态：仓库内真实 CDP、Node ACK、PostgreSQL、API/Web 与自动化验收闭环

## 关闭的缺口

达到资源上限时，平台此前只能降低整个 Extension 子 Cgroup 的 CPU Weight，不能停止
某个非关键扩展持续运行的 background page 或 service worker。直接暂停全部扩展又会
破坏密码管理、安全、身份和企业策略类关键扩展。

本轮完成：

1. `V044` 在 `browser_placements.paused_extension_ids` 持久化 Node 权威确认的暂停列表；
2. Control Plane 只从 Session 已绑定扩展中选择正式 Extension Profile
   `privileged=false` 的扩展；
3. 未登记扩展和 `privileged=true` 扩展默认不暂停，避免把未知能力误判成非关键；
4. Browser Node 只连接本机回环 CDP，对匹配 Extension Target 执行可逆
   `Debugger.pause/resume`；
5. Node 首次执行成功后才发送 `RuntimeResourcesAdjusted`，Control Plane 校验旧值、
   新值、Placement 扩展集合和 Operation 后才提交 PostgreSQL；
6. Session 重启时从 Placement 恢复策略，普通资源调整会解除暂停。

## 真实执行语义

Node 只处理 URL 为 `chrome-extension://<id>/...` 且 Target 类型属于以下集合的目标：

- `background_page`
- `service_worker`
- `worker`
- `shared_worker`

启用策略时执行：

```text
Debugger.enable
→ Debugger.pause
```

解除策略或资源回滚时执行：

```text
Debugger.resume
→ Debugger.disable
```

每秒轻量监视器会继续发现策略生效后新建或重启的后台 Target。普通网页 Page Target、
Content Script 所属 Renderer、未列入策略的扩展和特权扩展不会被该执行器暂停。

## Operation 与滚动升级

执行链为：

```text
RESOURCE_ADJUSTMENT REQUESTED
→ Node Command Outbox
→ Cgroup / State / RDP / Media / Tab 调整
→ Extension Background Debugger Policy
→ RuntimeResourcesAdjusted ACK
→ Placement + Resource Event COMMITTED / FAILED
```

Protobuf 使用带 presence 的 `ExtensionBackgroundPolicy`：

- `StartRuntimeCommand.extension_background_policy = 25`
- `AdjustRuntimeResourcesCommand.extension_background_policy = 19`
- `AdjustRuntimeResourcesCommand.extension_ids = 20`
- `RuntimeResourcesAdjustedEvent.old/new_extension_background_policy = 29/30`

新 Control Plane 收到 N−1 Node 不带策略字段的 ACK 时保留原值；旧 Node 会忽略新字段。
V044 仅新增带安全默认值的 JSONB 列，并用 `NOT VALID → VALIDATE` 约束其必须为数组。

## API 与 Web

以下正式投影新增 `pausedExtensionIds`：

- Browser Placement API
- `GET /api/v1/sessions/{id}/resources`
- Resource Event 的 old/new resources

Session Resource Panel 以文字显示“Node 已暂停 N 个非特权扩展”，并列出具体 ID；没有
真实 ACK 时不会根据压力原因猜测暂停状态。

## 验收证据

- Control Plane 定向单测验证只下发非特权扩展，特权和未知扩展不进入策略；
- Rust 真实 CDP 假服务验证 ACK 前按顺序执行 pause，解除时执行 resume；
- Browser Node Workspace Test、Fmt、Clippy；
- Web format、lint、单测和 production build；
- OpenAPI、Buf、V044 N/N−1 Gate；Evidence Hash：
  `c4a8501846abc22635b8186b4907f0183f73138150ec22ab9d62f04641e08f22`；
- 完整 PostgreSQL/Browser Node Integration 验证 V044、Node ACK、Placement、
  Resource API 和 Resource Event 返回同一暂停扩展列表。

## 仍需完成

1. 成功 Trace 动态采样已由
   [进度 68](68-AUTO成功Trace动态采样闭环.md)关闭；
2. 视频/Observer 录制停止与帧率执行器；
3. Agent/Observer 截图频率执行器；
4. 逐 Extension/Content Script CPU、内存和成本归因；本轮只关闭“暂停已知非特权后台
   Target”，不等于逐扩展计费；
5. 目标 Linux 真实企业扩展、MV2/MV3 重启风暴和多 Session 长稳矩阵。
