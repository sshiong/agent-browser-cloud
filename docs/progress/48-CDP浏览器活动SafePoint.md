# CDP 浏览器活动 Safe Point

> 日期：2026-07-28
> 状态：文件传输、表单与隐私有界的浏览器事务启发式信号闭环完成；客户专有业务语义继续由通用 Lease/Adapter 提供强证明，目标环境长稳待完成

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
- 在原三项计数上新增 SPA Mutation、支付/账号安全和关键事务三项：非幂等 Fetch/XHR
  计为 SPA Mutation；支付/安全及关键事务只按有界 URL Path 完整词段分类。Node 会先
  丢弃 Origin、Query、Fragment，不上传或持久化 URL、Header、正文及匹配词。
- 短事务完成后保留 10 秒结算保护窗，确保 5 秒上报至少捕获一次；保护窗结束自动解除。
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
- 新增 optional 字段 35—37：`active_spa_mutation_count`、
  `active_payment_or_security_count`、`active_critical_transaction_count`；独立标签
  `safePointBrowserTransactions=cdp-transaction-v1` 协商能力，旧 Node/Control Plane
  均保持滚动兼容。
- V028 使用 Expand→Validate→Contract 更新 `session_safety_signals` 检查约束，先
  创建允许新旧信号的 `NOT VALID` 超集约束、验证存量数据，再替换旧约束。
- Node gRPC 先校验三项字段必须成组出现且不得为负，再写入 JSON 详情；安全观察完成后
  才持久化资源样本，保留同一 stream 请求的处理屏障。
- V097 对新增信号重复 `NOT VALID → VALIDATE → DROP/RENAME` 在线约束替换，整个迁移
  期间不存在无校验窗口。

### AUTO 决策与 UI

- Browser Node 启动后会尽快上报首个安全观察；当资源采样周期被测试或运维配置为较长
  周期时，该上报不携带 CPU/内存等资源指标，避免安全专用样本稀释 P95/EWMA 压力窗口。
- 修复空字符串 `dangerEvent` 被资源策略误判为 Critical；只有非空危险事件才走即时
  保护路径。
- Session 详情对 `FILE_UPLOAD_ACTIVE`、`FILE_DOWNLOAD_ACTIVE` 和
  `FORM_SUBMISSION_ACTIVE` 显示明确文本，不只依赖颜色。
- Session 详情也显示 SPA 写入/结算保护、浏览器检测支付/账号安全和关键事务，不直接
  暴露内部 Signal Code。

## 验证

- State Collector Fake CDP 测试覆盖上传、表单、下载的开始和完成计数。
- Fake CDP 继续覆盖 XHR/Fetch 分类、支付/安全/关键事务、Query 排除、完整路径词边界和
  完成后的结算保护窗。
- 本机真实 Chrome 的 CDP State/Safety Collector ignored test 已显式运行并通过。
- Control Plane 测试覆盖：
  - 新 Node 缺少活动信号 fail-closed；
  - 活跃上传阻止 Safe Point；
  - 三项 JSON 信号可解析；
  - gRPC 成组接受、部分字段拒绝且不产生部分写入；
  - 旧 Node 报告继续兼容；
  - 安全专用样本不稀释持续 CPU 压力；
  - 空危险事件不触发 Critical。
- PostgreSQL 17 + Browser Node 完整集成已通过，验证当前 Session 有 8 项 Node Safety 信号，
  其中 6 项来自 `BROWSER_NODE_CDP_ACTIVITY`，均为 LIVE 且非活跃。
- N/N-1 Gate 固定校验字段 28—30、35—37 保持 optional、字段号不复用，以及 V028/V097
  必须保留 Expand→Validate→Contract 顺序。

## 仍未完成

1. 通用 CDP 启发式已覆盖 SPA 写入及常见支付/账号安全/关键事务路径，但不能证明客户
   专有业务事务已经开始或完成；目标业务仍必须使用 Application Lease SDK/Adapter 和
   Provider Evidence 提供字段、事务与恢复语义的强证明。
2. 仓库级双 Browser Node + S3-compatible Object Storage + CDP 数据面迁移已由
   进度 80 完成；仍缺目标 Linux 正式 Chromium 的迁移/休眠故障注入和长期稳定性证书。
3. Tenant/Application-aware 声明式 Business Recovery Validator、规则 DSL 和有界
   低风险动作执行器已完成；V039 又补齐受信 Extension 重启，进度 62 已完成契约作者
   UI，进度 73—76 已补齐审批、审计和 Provider Evidence 平台协议。仍缺目标站点
   Adapter 与真实 Provider 凭据。
4. Browser/Profile I/O 已由 Linux Cgroup v2 Browser 子组真实生产者补齐；仍缺
   逐 Extension 归因、硬件 Codec/GPU 和目标 Linux 长稳；Session 级 Extension/Media
   生产者及 Weight/Slot 在线执行器已分别关闭。
