# CDP 浏览器活动 Safe Point

> 日期：2026-07-28
> 状态：文件上传、文件下载和导航级表单提交的仓库内真实信号闭环完成；应用业务语义和真实双 Node 长稳待完成

## 本轮完成

### Browser Node 真实观察

- State Collector 新增持续 CDP Safety Monitor，自动发现并附着 Page Target，启用
  `Network` 和 Browser Download 事件，不通过定时器或前端 Mock 伪造活动状态。
- 按在途 Request/GUID 维护三项权威计数：
  - 文件上传：multipart、octet-stream、attachment/form-data 请求；
  - 文件下载：响应 `Content-Disposition: attachment` 与 Browser Download 生命周期；
  - 表单提交：非 GET/HEAD/OPTIONS 的 Document 导航请求。
- Network finished/failed、Download completed/canceled 后释放对应活动；同一下载在
  Network 和 Browser 两条事件链同时出现时不会简单相加。
- 只有 Browser Download 事件启用且至少一个 Page 的 Network 监听成功后，观察才标记
  为 Fresh。已建立连接一旦中断，同一 Runtime Generation 保持 fail-closed，因为无法
  从重连后的 CDP 重建断线前的在途请求。
- Runtime 注销时同步终止 Monitor 并删除观察，避免旧 Generation 的信号污染新
  Browser。

### 契约、持久化与滚动兼容

- `ReportSessionResourcesRequest` 新增三个 optional 字段：
  `active_upload_count`、`active_download_count`、
  `active_form_submission_count`，兼容 N/N-1 Node。
- Browser Node 容量标签新增 `safePointBrowserActivity=cdp-network-v1`。Control
  Plane 仅在当前 Node 声明该能力时要求全部三项信号：
  - 新 Node 缺少任一项或信号过期：Safe Point 为 UNKNOWN；
  - 旧 Node 未声明能力：继续只要求 Input/Drag，不阻断滚动升级；
  - 任一活动计数大于零：Safe Point 为 BLOCKED。
- V028 使用 Expand→Validate→Contract 更新 `session_safety_signals` 检查约束，先
  创建允许新旧信号的 `NOT VALID` 超集约束、验证存量数据，再替换旧约束。
- Node gRPC 先校验三项字段必须成组出现且不得为负，再写入 JSON 详情；安全观察完成后
  才持久化资源样本，保留同一 stream 请求的处理屏障。

### AUTO 决策与 UI

- Browser Node 启动后会尽快上报首个安全观察；当资源采样周期被测试或运维配置为较长
  周期时，该上报不携带 CPU/内存等资源指标，避免安全专用样本稀释 P95/EWMA 压力窗口。
- 修复空字符串 `dangerEvent` 被资源策略误判为 Critical；只有非空危险事件才走即时
  保护路径。
- Session 详情对 `FILE_UPLOAD_ACTIVE`、`FILE_DOWNLOAD_ACTIVE` 和
  `FORM_SUBMISSION_ACTIVE` 显示明确文本，不只依赖颜色。

## 验证

- State Collector Fake CDP 测试覆盖上传、表单、下载的开始和完成计数。
- 本机真实 Chrome 的 CDP State/Safety Collector ignored test 已显式运行并通过。
- Control Plane 测试覆盖：
  - 新 Node 缺少活动信号 fail-closed；
  - 活跃上传阻止 Safe Point；
  - 三项 JSON 信号可解析；
  - gRPC 成组接受、部分字段拒绝且不产生部分写入；
  - 旧 Node 报告继续兼容；
  - 安全专用样本不稀释持续 CPU 压力；
  - 空危险事件不触发 Critical。
- PostgreSQL 17 + Browser Node 完整集成通过，验证当前 Session 有 5 项 Node Safety
  信号，其中 3 项来自 `BROWSER_NODE_CDP_ACTIVITY`，均为 LIVE 且非活跃。
- N/N-1 Gate 固定校验字段 28—30 保持 optional、字段号不复用，以及 V028 必须保留
  Expand→Validate→Contract 顺序。

## 仍未完成

1. CDP 只能可靠识别网络层上传、下载和导航级提交；客户端路由 SPA、Fetch/XHR 的业务
   含义、支付/账号安全操作和关键业务事务需要应用侧 SDK/Adapter/Lease Producer。
2. 真实双 Browser Node + S3-compatible Object Storage + Chromium 的迁移/休眠故障
   注入和长期稳定性证书。
3. Tenant/Application-aware Business Recovery Validator 与业务规则 DSL。
4. Browser/Profile I/O 已由 Linux Cgroup v2 Browser 子组真实生产者补齐；仍缺
   Extension、Media 的真实指标生产者，以及 Extension Weight、Media Encoder Slot
   在线执行器。
