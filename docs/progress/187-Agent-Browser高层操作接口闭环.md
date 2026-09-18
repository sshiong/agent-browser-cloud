# Agent Browser 高层操作接口闭环

日期：2026-09-18。对应专项清单 A21。

## 结论

A21 的仓库内通用代码项已关闭。Agent Browser 的正式高层操作面现在统一为
`snapshot / find / inspect / act / wait / handoff`。模型不需要为了等待页面或请求人工协作而
构造底层 Task/Plan 字段；所有写入口仍先校验精确 `stateCursor`，再进入同一持久 Agent Task、
Reviewer、风险确认、Capability、执行和 Outcome Verification 链。

旧的 `execute-actions` 保留为 `act` 的 deprecated 兼容别名，已有 Web、Tauri、SDK 和 N−1
客户端不会中断。接口收敛没有移除底层安全围栏，也没有赋予 Agent 任意 CDP、键盘、Secret 或
直接接管权限。

## 实现

- `act` 接收最多 20 个现有受限 Browser Primitive，复用动态微批、稳定 Element ID 重绑定、
  `stopOnError`、真人输入优先级与既有风险分层。
- `wait` 只暴露 `STATE_CHANGED / STATE_STABLE / TARGET_PRESENT`、可选 Target 和 100—10000ms
  有界超时；控制面将其转换为单一 `WAIT_FOR` Step。
- `handoff` 只接收操作员可见目的与精确 State Cursor；控制面转换为单一
  `REQUEST_HUMAN_TAKEOVER` Step。执行后进入 `WAITING_FOR_HUMAN`，不会自动批准或授予控制；
  接受、拒绝、过期继续复用既有审计治理。
- 三个写入口统一使用 `Idempotency-Key`，先从权威 Snapshot 重验当前 Cursor，再推导当前 Tab
  的允许域；旧 Cursor 在创建 Task、领取 Capability 或派发 Node 命令前拒绝。
- OpenAPI 与 TypeScript/Python/Go/Java SDK 已同步。公开契约从 247 Operations / 343 Schemas
  更新为 **250 Operations / 345 Schemas**。

## 验证

- Control Plane 定向测试覆盖兼容别名、直接 `WAIT_FOR`/`REQUEST_HUMAN_TAKEOVER` 计划、Reviewer
  路由及 stale Cursor 创建前拒绝；完整 Java 测试共 566 项通过。
- Web API 测试覆盖三个正式 URL、Tenant 与幂等头；Web 当前全量 143 项通过。
- 完整 PostgreSQL/Redis/MinIO/mTLS/Chromium Integration 实际调用 `act` 完成 HOVER、调用
  `wait` 完成稳定等待，并调用 `handoff` 进入 `WAITING_FOR_HUMAN`；随后以显式 Operator 拒绝，
  Task 以 `HUMAN_HANDOFF_REJECTED` 终止，输出 `agent_browser_high_level_tools=true`。
- OpenAPI lint、四语言 SDK 生成/漂移检查、Rust Workspace、Worker/Provider、完整 `make ci`、
  Desktop test/lint/unsigned build、供应链、Operator 17 项、50k Coordinator Capacity 与
  N/N−1 本地 Gate 均通过。GitHub `ci`/`desktop` 冷机结果在推送后记录。

## 剩余边界

- 该接口减少模型动作面，不改变网页、模型或第三方服务的可信级别。Prompt Injection、风险确认、
  Opaque Frame 与敏感输入规则继续独立生效。
- `handoff` 是显式治理动作，不表示人工一定在线，也不替代远程桌面连接授权、组织排班或真实
  IdP/支付/托管 Challenge 的生产验收。
- A22 Personal Secure 一键部署仍是下一项仓库产品化缺口。
