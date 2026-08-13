# CDP 业务事务 Safe Point 自动聚合闭环

> 日期：2026-08-13
> 状态：仓库内闭环完成；客户专有业务 Adapter 与目标 Linux 长稳仍待完成

## 完成内容

- Browser Node 持续 CDP Safety Monitor 在既有上传、下载、导航表单之外，追踪非幂等
  Fetch/XHR、常见支付/账号安全路径和关键业务提交路径。
- Node 只在内存中读取 URL Path；Origin、Query、Fragment、请求正文和 Header 不进入
  protobuf、PostgreSQL、日志或 UI。控制面只接收三个 `uint32` 活动计数。
- 短请求完成后有 10 秒结算保护窗，覆盖 5 秒采样间隙；CDP 观察断线继续 fail-closed。
- Node 以 `safePointBrowserTransactions=cdp-transaction-v1` 独立声明能力。Control Plane
  只对能力节点要求三字段完整快照，缺报/过期为 UNKNOWN，活动大于零为 BLOCKED。
- V097 使用 `NOT VALID → VALIDATE → DROP/RENAME` 扩展信号约束，滚动期间没有无约束
  窗口。Proto 35—37 为 additive optional 字段，N/N−1 Gate 固定字段号和迁移顺序。
- Web/Tauri 共用 Session Resource Panel 新增三种清晰阻断文案。

## 验证证据

- State Collector Fake CDP：上传/下载/表单、XHR/Fetch 分类、支付/安全/关键事务、Query
  排除、完整路径词边界、完成后保护窗。
- Control Plane：能力协商 fail-closed、三种信号持久化、浏览器检测支付阻断迁移、部分
  gRPC 组拒绝且不产生部分写入。
- 完整 PostgreSQL/Browser Node Integration 已通过：当前 Session 8 个 Node Safe Point
  信号，其中 6 个来自 CDP，全部为 LIVE 且非活跃；输出
  `safe_point_browser_transactions=true`、`health={"status":"UP"}`、
  `dual_node_migration=true`、`audit_chain_valid=true`。

## 仍未完成

1. 启发式不是业务提交成功证明；客户 CRM/支付/IAM 必须接入已有 Application Lease SDK、
   Provider Evidence 和版本化 Recovery Contract。
2. 仍缺目标 Linux 正式 Chromium 的网络分区、快速短事务、多 Tab、高并发与跨 Node 长稳。
3. 站点可配置事务 Path Prefix、版本审批、精确 Session 绑定与 Node 哈希校验已由
   [进度 137](137-版本化Site-Policy浏览器事务规则闭环.md)完成；无语义像素/OCR
   事务识别仍未实现。
