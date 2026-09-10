# Agent 动作尝试签名与循环检测闭环

日期：2026-09-09。承接 progress 165 的 A09；本切片已完成并通过完整 Gate。

## 持久 Action Attempt 账本

V115 新增 `agent_action_attempts`，按 Tenant / Session / Task 保存动作尝试的哈希化证据、
执行前 Browser State 版本与哈希、连续次数、派发/验证/失败状态及结果状态证据。账本不保存
输入正文、Secret 密文、Capability Token、一次性 Token ID 或完整 Browser State；动作描述会
排除 Step/Action 随机 ID 和 Target Revision 等重规划噪声，同时保留 Tool、目标语义、载荷哈希、
Tab/Dialog、按键/指针参数及 Batch 子动作语义。

签名为规范化动作描述哈希与执行前权威 State Hash 的组合 SHA-256。同一 Task 内，只有签名与
上一条已派发、已验证或已失败尝试相同时才增加连续次数；页面状态或动作语义变化会自然开启新
序列。`WAIT_FOR` 不计入循环，Capability 校验失败或 Node 派发前异常记为 `ABANDONED`，不会把
未执行动作误计为循环。

## 执行前阻断与完成证据

Control Plane 在精确 Task 行锁内完成“读取上一尝试—决定—插入”，避免并发执行绕过阈值。
前两次相同签名可以按既有恢复策略执行；第三次在 Capability 单次账本消费和 Node 命令派发前
写入 `LOOP_BLOCKED`，以稳定错误码 `AGENT_ACTION_LOOP_DETECTED` 终止 Task。现有统一恢复投影
将其显示为 `TERMINAL`，不再让模型继续猜测或重复点击。

异步 Navigate 和结构化 Action 的成功 State 回调会把最近一次派发更新为 `VERIFIED`；显式 Node
失败、Batch 失败或验证失败会写为 `FAILED` 并保留结果状态版本/哈希。控制面日志只记录 Task、
Operation、稳定失败码和异常类型，不记录异常正文，避免诊断日志扩大敏感输入泄漏面。

## 边界

- 循环域有意限定为同一 Task；它不是跨用户、跨任务或跨站点的全局行为画像。
- State Hash 变化会重置连续序列，因此 A20 的组合页面稳定性仍需单独完成。
- 本切片判断“重复动作没有状态进展”，不证明用户业务目标已经达成；A11 独立 Outcome
  Verifier 后由 progress 173 关闭，A04 Expected Outcome 仍待实施。
- 阈值当前为两次可执行、第三次阻断；后续如需按站点/动作风险调整，必须保留服务端硬上限和
  本轮的执行前 fail-closed 语义。

## 验证

- Control Plane 523 项测试通过，新增规范化哈希、Secret/Capability 排除、连续第三次阻断、
  `WAIT_FOR` 不入账及完成状态回归；
- Web 140 项、Rust Workspace、Python Worker、Go Provider、完整 Test/Lint/Build 通过；
- Desktop test/lint/unsigned build 通过；
- README 文档检查、OpenAPI/Protobuf、四 SDK、供应链、Operator、50k Capacity 与 V115 N/N−1
  additive Gate 通过，公开 API 仍为 240 Operations / 320 Schemas；
- 完整 PostgreSQL/Redis/MinIO/mTLS/Chromium Integration 通过，输出
  `agent_action_loop_detection=true`；真实验证第三次动作得到 `LOOP_BLOCKED:3`、Task 返回
  `AGENT_ACTION_LOOP_DETECTED` / `TERMINAL`，且对应 CLICK Capability 使用数为 0。

完整集成第一次暴露 PostgreSQL JDBC 不能自动推断 `Instant` 参数类型；最终实现统一使用显式
SQL Timestamp 后重跑通过。这一失败未被单元 Mock 掩盖为成功。

首次 GitHub CI 的 A09 场景已通过，但随后既有 PAGE_ACTION Evaluate 在冷 Runner 上连续三次
命中合法 `STATE_STALE`，使 Integration 失败；本地两轮完整集成未复现。集成夹具现要求连续
两次相同 State Cursor 后才提交 PAGE_ACTION，并把仍可能发生的受围栏失败保留为五次有界重试，
不放宽生产状态围栏。修复提交 `8970047` 已推送；GitHub `ci` run `34311805818`（含 Verify、
供应链、完整 Integration、Object Storage/Recording GameDay 与 Kubernetes Operator E2E）及
`desktop` run `34311805802`（Windows/macOS）均成功。
