# 版本化 Site Policy 浏览器事务规则闭环

> 完成日期：2026-08-13
> 关联迁移：`V098__browser_transaction_site_policy.sql`

## 本轮完成

1. Application Recovery Contract 新增 `paymentSecurityRoutePrefixes` 与
   `criticalTransactionRoutePrefixes`。规则复用 CAS 版本、不可变 Revision、精确 Session
   绑定、双人审批、Diff、受控恢复和审计，没有建立第二套策略模型。
2. Control Plane 从 Session 已绑定的精确 Revision 解析规范化 Origin/Path，生成 canonical
   SHA-256，并通过 `StartRuntimeCommand` 下发版本、Origin、路径和哈希。普通启动、Crash
   Recovery、Hibernate 恢复和跨 Node Migration Restore 都复用该链。
3. Browser Node 校验版本、数量、长度、排序去重、Origin、Path 和 SHA-256。自定义规则
   只有在写方法成立、请求 Origin 精确匹配且 Path segment 前缀匹配时生效；`/pay` 匹配
   `/pay/confirm`，不匹配 `/payload`。
4. URL 只在 Node 内存中读取。Control Plane 不接收 URL、Query、Header 或 Body，仍只
   接收三类有界活动计数；Query/Fragment 在分类前丢弃。
5. Node 新增 `safePointBrowserTransactionPolicy=approved-route-v1` 能力。配置了自定义规则
   但当前 Node 为 N−1 时，Safe Point 返回 `UNKNOWN/MISSING`，禁止迁移或休眠误放行。
   该 Gate 只在存在未释放 Browser Placement、确实依赖 Node 观测时生效；尚未启动的
   CREATED Session 保持 `SAFE/NOT_REQUIRED`，不会误阻断 Coordinator 路由迁移。
6. Recovery Contract 作者 UI 新增 Site Policy 面板。发布规则会形成新 DRAFT，必须由
   第二位管理员重新批准；Web 与 Tauri 共用同一 React/API/权限组件。
7. OpenAPI、TypeScript/Python/Go/Java SDK、Protobuf Java/Rust/TypeScript 和 N/N−1 Gate
   已同步。

## 安全与滚动迁移

- V098 先以非空 `[]` 增加 JSONB 列，再用 `NOT VALID → VALIDATE` 建立数组约束，最后
  更新 Revision snapshot trigger；旧 Contract/Revision 等价于无自定义规则。
- 管理员禁用 Contract 会停止新的绑定与自动恢复，但既有 Session 的事务保护仍读取其
  精确绑定 Revision，避免 kill switch 意外关闭 Safe Point 防护。
- N−1 Node 会忽略新增 Protobuf 字段；Control Plane 通过独立 capability fail-closed，
  不把“字段被忽略”误报为策略已执行。

## 验证证据

- Java：Recovery Contract、Safe Point、Session Coordinator 与策略解析测试通过；覆盖
  “运行中 N−1 fail-closed”以及“未放置 Session 不要求 Node capability”两种边界。
- Rust：State Collector 21 个测试通过（1 个真实 Chromium 测试按环境忽略），覆盖
  Origin 隔离、segment 边界、Query 隐私与哈希篡改拒绝。
- Web：72 个 Vitest 用例和 TypeScript build 通过。
- N/N−1 Gate：V098 在线迁移不变量和 StartRuntime tags 34—38 固定检查通过。
- 完整 PostgreSQL/双 Browser Node 集成以真实自定义规则完成 Contract 创建、双人审批、
  Session 精确 Revision 绑定和 Runtime 启动，并通过路由迁移、Crash Recovery、跨 Node
  Migration、Profile 与审计链回归；结果为 `public_tables=110`、
  `audit_chain_valid=true`。GitHub CI 结果见本轮提交记录。

## 仍未完成

1. Site Policy 是通用浏览器侧保护，不是业务系统事务完成证明。客户 CRM、支付、IAM
   仍需 Application Lease SDK 与 Provider Evidence Adapter 提供可信完成语义、字段映射
   和正式凭据。
2. 无语义像素/OCR 事务识别、Recording 帧级敏感遮罩尚未实现。
3. 目标 Linux 多 Node、网络分区、多 Coordinator 和长期压力矩阵仍是生产 Gate。
