# Business Recovery 有界自动动作闭环

> 日期：2026-07-28
> 状态：低风险动作、持久尝试预算、Node 执行、State ACK、二次 Ready Gate、API/Web
> 展示和真实集成已完成；受信 `RESTART_EXTENSION` 已在 V039/进度 61 关闭，
> 契约作者 UI、审批/审计与 Provider Evidence 平台协议已分别在进度 62、73、76
> 关闭；目标站点 Adapter、真实 Provider 集成和生产环境 Gate 仍待完成

## 目标与安全边界

V030 已能用声明式 Application Recovery Contract 判定恢复后的业务页面是否 Ready，
但 `maximumAutoRecovery` 当时只是持久化字段。V034 将它接入有界动作状态机：仅当
迁移进入 `BUSINESS_VALIDATION`、判定为可自动恢复且契约显式配置动作时，Control Plane
才会按预算请求一次低风险动作。

当前允许的动作是：

- `RELOAD`：保留缓存的页面刷新；
- `REFRESH_SESSION`：忽略缓存的页面刷新；
- `NAVIGATE_HOME`：导航到契约首个精确 Origin；
- `REOPEN_KNOWN_ROUTE`：导航到契约 Origin 与已规范化 Route Prefix；
- `RESTART_EXTENSION`：仅重启契约和 Session Placement 同时绑定的真实 Chromium
  Extension；
- `NONE`：禁用自动动作。

平台不接受租户 JavaScript、任意 CDP Method、正则表达式或任意 URL。Login、权限变化、
账号不一致和人工恢复 Verdict 不会触发自动动作。Extension 重启的完整安全边界与证据
见[进度 61](61-受信Extension自动恢复动作闭环.md)。

## 已完成

### V034 持久状态与迁移阶段

- `application_recovery_contracts.recovery_action` 保存显式动作，内部模板和动作对普通
  Session 用户不可见；
- `business_recovery_actions` 保存 Action ID、Migration、Contract/Version、Attempt、
  Base/Result State Version、Command Message ID、Deadline、错误与时间戳；
- 状态限定为 `REQUESTED → EXECUTING → ACKNOWLEDGED → COMMITTED`，失败进入
  `FAILED`；`(migration_id, attempt_number)` 与命令消息均唯一；
- Session Migration 新增 `BUSINESS_RECOVERY_ACTION`，调度器可在进程重启后继续对账，
  30 秒无权威 State ACK 会失败并重新进入有界判定，不会无限重试。

### Control Plane 与 Browser Node

- Control Plane 在 Session 行锁内检查 Runtime、Context、State、Node 能力和动作预算，
  从绑定契约解析目标，不接受前端直接传入目标 URL；
- Node 保留 `businessRecoveryActions=cdp-low-risk-v1` 支持原有动作，并用独立
  `businessRecoveryExtensionActions=cdp-extension-restart-v1` 追加受信 Extension
  重启，Control Plane 按动作做双向滚动兼容 Gate；
- Node 对刷新动作执行真实 CDP `Page.reload`，对导航动作执行受限 URL 导航；
- Node 对 Extension 动作只在匹配的 `chrome-extension://<id>/` Context 执行固定
  `chrome.runtime.reload()`，不接受租户脚本；
- 动作完成后强制 Full State Resync，并以既有 `BrowserStateUpdated` 事件返回
  `snapshotKind=BUSINESS_RECOVERY_ACTION`、Action ID 和递增 State Version；
- Control Plane 只有在 Session/Action/Context 与 State Version 全部匹配时才提交动作，
  然后回到 `BUSINESS_VALIDATION`。只有新的持久 Verdict 为
  `READY/READY_WITH_WARNING`，迁移才完成并恢复 Agent；
- 迁移校验幂等键包含恢复尝试号，修复首次非 Ready 结果被后续尝试永久重放的问题。

### API 与 Web

- Recovery Contract 请求新增可选 `recoveryAction`；旧客户端未发送时按 `NONE` 处理，
  保留旧预算但不执行动作，支持 N/N-1 滚动升级；
- V039 新增可选 `recoveryExtensionId`，必须属于 `requiredExtensionIds` 和当前
  Placement；Action 详情返回持久 `targetExtensionId`；
- 新客户端显式提交 `NONE + 非零预算` 或“有动作 + 零预算”时 fail-closed 拒绝；
- Session Migration 响应新增当前尝试数、最大次数和最近一次动作；
- Session Detail 的 Business Recovery 卡显示动作类型、状态、尝试预算、Base/Result
  State Version 与错误，不用颜色替代文本；
- Recovery Contract 作者页面后续已在
  [进度 62](62-Application-Recovery-Contract作者工作区.md)完成，并继续使用正式
  Admin API、版本 CAS 和同一声明式边界。

## 可重复验证

- Java 单元测试覆盖动作请求持久化、真实 Node 命令载荷、能力 Gate、State ACK、
  COMMITTED 和返回 Business Validation；
- Rust Workspace 测试覆盖 Node Agent 与 State Collector，动作执行后必须完成 Full
  Resync 且 State Version 递增；
- OpenAPI、Protobuf 字段号与 V034/V039 Expand→Validate→Contract 进入 N/N-1 Gate；
- Web lint、39 项单测和生产构建覆盖新增详情字段；
- `make test-integration` 在 PostgreSQL 17、Control Plane 和真实 Browser Node 进程上，
  同时覆盖真实 `Page.reload` 协议和受信 Extension Context
  `chrome.runtime.reload()`；Extension 动作收到 State ACK，持久
  `COMMITTED:RESTART_EXTENSION:<extension-id>:1`，二次应用校验为 READY，迁移
  进入 COMPLETED。

集成测试从同一个真实运行 Session 的 `BUSINESS_VALIDATION` 阶段开始验证动作闭环；
它没有替代两个真实 Browser Node、S3-compatible Object Storage、网络分区和长期压力
矩阵，这些仍是目标环境 Gate。

## 仍未完成

1. 支付、账号安全、SPA/Form 和关键业务事务的站点 Adapter/SDK 实际接入；
2. Provider Evidence 平台协议和迁移 Gate 已在进度 76 完成；真实 CRM/支付 Provider
   凭据与目标业务系统联调仍待完成；
3. Recovery Contract 作者 UI、审批、变更审计和版本差异体验已完成；Document/Network、
   Toast/Dialog 与基础声明式 Validator 已由进度 107 关闭；仍缺无语义视觉/OCR；
4. 独立 Business Recovery 事件流与 State/Audit/Agent Step 统一事件层；
5. 两个真实 Browser Node + Object Storage 的迁移、故障注入和长期稳定性证书。
