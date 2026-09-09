# Agent 结构化任务记忆闭环

日期：2026-09-09。承接 progress 165 的 A10；本切片实现完成并通过完整 Integration。

## 三类状态分离

- Browser State 继续由现有 PostgreSQL 权威快照、单调版本和新鲜度投影负责，不复制进任务记忆；
- `agent_tasks` 继续保存当前 Task、Plan、Step、Lease、恢复决策等可变运行状态；
- V116 新增 append-only `agent_task_memory_events`，按 Tenant / Session / Task 保存已完成 Step、
  终止失败和 Replan 的最小化执行历史。

每个 Task 在行锁内分配单调 `memorySequence`，并以稳定事件键实现幂等写入。历史独立于当前
Step 和 Browser State，页面刷新、Worker 重领、Human Assist 续行或有界 Replan 不会清除已经
完成的工作。正式 API 返回最近 100 条、按序排列的 `memory.executionHistory` 和当前 revision；
Web/Tauri 共用任务详情显示同一数据最小化时间线。

## 语义与数据最小化

Step 事件保存 Plan Intent、Step 序号、Tool、状态、验证码、结果哈希和可选 State Version。
`semanticKey` 复用 A09 的规范化动作描述哈希，因此不受随机 Step/Action ID、Target Revision
或 Capability Token 变化影响，也不保存正文、Secret 密文、Capability、工具输出或 Browser
State JSON。Replan 只保存稳定原因码与当时 State Version。

V116 对事件种类、序号、语义哈希和各代码字段设置数据库边界；旧 Task 的既有
`executionResults` 会迁移为稳定、最小化的兼容历史。任务删除时历史随 Task 清理，Tenant 与
Session 复合外键继续保证隔离。

## 失败与恢复证据

- 同步 Read、异步 Browser Action、Challenge 前已验证 Step 和 Human Handoff 请求均先追加
  历史，再推进持久 Task checkpoint；同一事务失败时一起回滚；
- Node 失败、显式 Batch/验证失败和 A09 循环阻断写入 `STEP_FAILURE`；
- State Stale 等有界恢复写入 `REPLAN`，但不删除先前成功历史；
- API 不把执行输出复制到 memory，调用方若需要当前页面仍必须读取带新鲜度的 Browser State。

## 边界

- 本切片解决“已经做过什么”的持久、结构化证据，不证明业务目标已达成；A04 Expected Outcome
  与 A11 独立 Outcome Verifier 仍须单独完成。
- 最多返回最近 100 条是 API 投影上限，数据库账本仍 append-only；长期归档/保留策略沿用后续
  Agent 数据治理工作，不在本切片中声称完成。
- Human Handoff 的请求 Step 会进入执行历史；人工接受/拒绝本身仍属于独立治理与审计事件，
  不伪装成 Agent 执行 Step。

## 验证

- `AgentTaskMemoryServiceTest` 覆盖语义哈希、正文/Secret/Capability/输出排除、Replan 幂等键与
  Browser State 不复制；Control Plane 525 项测试通过；
- Web 140 项、Lint 和生产 Build 通过；OpenAPI lint 与 240 Operations / 322 Schemas 的
  TypeScript/Python/Go/Java SDK 重新生成和一致性检查通过；
- V116 N/N−1 additive Gate 覆盖无破坏迁移、API 字段滚动可选、100 条投影上限和幂等事件键；
- 完整 `make ci` 通过，覆盖 Java/Rust/Web、Python Worker、Go Provider、文档漂移、Lint、
  契约、四 SDK、供应链、Operator、50k Coordinator Capacity 和 N/N−1；Desktop 2 项测试、
  Lint/check 与 unsigned release build 通过；
- 完整 PostgreSQL/Redis/MinIO/mTLS/Chromium Integration 通过，输出
  `agent_task_structured_memory=true`；真实四步任务返回连续 1—4、全部 VERIFIED 的历史，
  重新 GET 后内容不变，数据库为四条互异语义哈希；循环任务保留先前已验证读取，并以最终
  `STEP_FAILURE / AGENT_ACTION_LOOP_DETECTED` 收尾。Integration 同时复验 A09、高级动作、
  Dialog、Evaluate、截图、文件、恢复、资源、录制和四 SDK 主链。

首次重跑在 A10 场景前命中既有 failover 后 Browser State 心跳竞态；夹具等待窗口改为最多
八个 15 秒心跳周期，但最终 API 断言仍严格要求样本年龄小于生产 30 秒阈值。第二次已到达 A10，
暴露循环任务在阻断前还有规划器读取历史，修正为验证连续序列、先前 VERIFIED、最终失败；最终
第三次使用完整 V116 约束重跑通过，未放宽生产 State 或动作围栏。

实现提交 `42f9901` 已推送；GitHub `ci` run `34317007418`（含 Verify、供应链、完整
Integration、Object Storage/Recording GameDay 与 Kubernetes Operator E2E）及 `desktop`
run `34317007425`（Windows/macOS）均成功。
