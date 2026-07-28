# 受信 Extension 自动恢复动作闭环

> 完成日期：2026-07-28
> 数据库版本：V039
> 状态：契约目标、Placement Gate、Node CDP 执行、State ACK、二次 Ready Gate、
> API/Web 投影和真实集成验收已完成

## 本轮关闭的缺口

V034 已能执行 Reload、忽略缓存刷新和契约内受限导航，但
`RESTART_EXTENSION` 一直停留在设计项。直接重启 Browser 会扩大故障域并中断页面、
网络连接和登录状态；从网页执行任意 JavaScript 或任意 CDP Method 又会破坏安全边界。

本轮将 Extension 重启接入既有持久 Business Recovery Attempt，不增加前端伪状态：

```text
Application Recovery Contract
→ Session/Placement Extension Gate
→ BusinessRecoveryAction REQUESTED/EXECUTING
→ trusted chrome-extension:// CDP Context
→ chrome.runtime.reload()
→ Full State Resync
→ BrowserStateUpdated ACK
→ COMMITTED
→ Business Recovery Validation
→ READY 后恢复 Agent
```

## 契约与 PostgreSQL

- Recovery Action 新增 `RESTART_EXTENSION`；
- Contract 新增可选 `recoveryExtensionId`，只接受 Chromium 的 32 位 `a-p` ID；
- 该 ID 必须同时存在于 `requiredExtensionIds`，其他动作携带 Extension 目标会被拒绝；
- V039 为 `application_recovery_contracts` 增加 `recovery_extension_id`，为
  `business_recovery_actions` 增加不可变 `target_extension_id`；
- V039 使用 Expand→Validate→Contract 更新既有动作约束，旧 Contract/Action 的新增列
  保持 `NULL`，N-1 代码仍可读写原动作；
- 数据库约束重复校验“动作类型—URL—Extension ID”的合法组合，不能只依赖 Java。

## Control Plane 与滚动兼容

- 执行动作前在 Session 行锁和当前 Context 内读取正式 Placement；
- 目标 Extension 必须同时属于版本化 Contract 和当前 Placement，否则
  `AUTO_RECOVERY_EXTENSION_NOT_BOUND_TO_SESSION` fail-closed；
- Node 保留 `businessRecoveryActions=cdp-low-risk-v1`，并追加独立能力
  `businessRecoveryExtensionActions=cdp-extension-restart-v1`：
  - N-1 Control Plane 仍能在 N Node 上执行原有 Reload/导航；
  - N Control Plane 仍能在 N-1 Node 上执行原有动作；
  - 只有声明新增能力的 Node 才会收到 `RESTART_EXTENSION`；
- Proto 只在 `BusinessRecoveryActionCommand` 追加字段号 6 `extension_id`，未复用字段号；
- Action ID、Attempt、Deadline、Base/Result State Version 和 ACK 校验沿用 V034 状态机。

## Browser Node 安全执行

- Node 只接受固定 `RESTART_EXTENSION` 枚举和合法 Chromium Extension ID；
- State Collector 从 Browser Node 回环 CDP `/json/list` 查找精确
  `chrome-extension://<id>/` Target；
- 只允许 `service_worker`、`background_page` 或 Extension `page` Context；
- 执行表达式固定为
  `setTimeout(() => chrome.runtime.reload(), 0); true`，租户不能传入脚本；
- 找不到 Target、ID 不合法、表达式被拒绝或 CDP 超时时，命令失败且不会重启 Browser；
- Extension 接受重启后强制 Full State Resync，Control Plane 只提交 Context、Action ID
  和递增 State Version 全部匹配的 ACK。

## API 与 Web

- OpenAPI 的 Contract/Action 枚举和目标字段已更新；
- Session Migration 的 `latestRecoveryAction.targetExtensionId` 返回真实持久目标；
- Session Detail 的 Business Recovery 卡显示 Extension ID、Attempt、状态和版本推进；
- Web 与 Tauri 继续复用同一类型/API Client/组件，没有桌面端独立状态。

## 验收证据

- Java 单元测试覆盖 Contract 目标约束、Placement 绑定、v2 能力 Gate 和 Proto 载荷；
- Rust State Collector 测试使用真实 WebSocket 协议验证只向匹配 Extension Context
  发送固定表达式；
- V039 升级夹具先构造 V034 旧 Contract/Action，再执行迁移并验证旧行与新
  `RESTART_EXTENSION` 行同时满足约束；夹具还验证数据库会拒绝缺失目标 Extension
  ID 的新 Contract/Action；
- V039 N/N-1 Gate 通过，证据 Hash：
  `2e7e222c499036aaf7e1024cdd4fd04f9578ecd9ee38e548bd5193973ec749b4`；
- OpenAPI/Proto、Web Lint 和 12 个测试文件/39 项测试通过；
- 完整 PostgreSQL 17 + Browser Node Integration 使用带固定 Manifest Key 的 MV3
  Extension，验证：
  `RESTART_EXTENSION → chrome.runtime.reload → State ACK → COMMITTED →
  二次 READY → Migration COMPLETED`。

## 明确未完成

1. Recovery Contract 作者 UI 已在进度 62 完成；审批、变更审计和更友好的版本差异
   体验仍待完成；
2. Provider/API 级账号、权限和业务实体恢复证明；
3. 支付、账号安全、SPA/Form、关键事务等目标站点 Adapter/SDK 实际接入；
4. 逐 Extension/Content Script CPU 与内存归因；
5. 目标 Linux 真实企业扩展长稳、包签名/企业分发治理与生产组织 Gate；
6. State/Audit/Agent Step 统一事件层和跨 Region 重放。
