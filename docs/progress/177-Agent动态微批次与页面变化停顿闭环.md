# Agent 动态微批次与页面变化停顿闭环

日期：2026-09-12。承接 progress 165 的 A06；本切片关闭固定 20 动作直接连续执行对动态 SPA
过于激进的通用执行缺口。

## 页面变化驱动的微批次

- 对外 `execute-actions` 仍保持最多 20 个动作和原有一次 Operation/Capability/Node Journal，避免
  把协议扩张成大量独立命令；Browser Node 在同一持久 Batch 内按真实执行后状态动态切段；
- 每个动作仍重采并重绑定最新稳定 Element ID。Route/Active Tab、原生 Dialog、Document/
  Network 未稳定、Target Revision、Content Hash 任一变化都会立即结束当前微批次；即使页面完全
  稳定，连续四个动作后也强制形成边界；
- 边界后至少取得连续两份相同的 Route/Tab/Dialog/Target/Content 状态，并要求可执行 State、
  `document.readyState=complete`、连续 Network 观察及至少 250ms Network Quiet，才执行下一动作；
- 每个动作结果带从 1 开始的 `micro_batch_index` 和有界 `boundary_reason`。Control Plane 校验后把
  该最小化节奏证据写入 Step 结果，便于 Trace 判断动作在哪个页面变化边界执行。

## 不稳定超时与副作用安全

微批次边界最多等待 5 秒。页面持续变化或状态采集失败时，已经完成的动作保留 `SUCCEEDED` 证据，
边界标记 `PAGE_UNSTABLE_TIMEOUT`；尚未执行的动作标记为 `SKIPPED / DYNAMIC_PAGE_UNSTABLE`。控制面
将整步以既有 `BATCH_ACTION_FAILED` 终止，不进入自动 Resync/Replan，也不会重放已完成的点击、输入
或其他副作用。人工可从最新 Browser State 和动作结果决定新的后续任务。

Protobuf 只在 `AgentActionOutcome` 增加字段 6—7；N−1 Node 保持零/空默认值，新 Control Plane
兼容旧结果。公开 OpenAPI 不变，仍为 245 Operations / 338 Schemas；本切片不增加数据库迁移。

## 验证

- Control Plane 541 项、Web 141 项、Agent/Reviewer/Vision/Outcome Worker 30 项，以及 Rust
  Workspace/Clippy/fmt、Go Provider、Operator、供应链、四语言 SDK 和 50k Coordinator Gate
  通过；
- Node 单测覆盖页面变化、四动作稳定上限和连续两份相同且 Network Fresh/Quiet 的稳定条件；
  Control Plane 测试覆盖新增 SKIPPED 终止语义、N−1 默认字段、新微批次元数据映射与非法
  边界拒绝；
- Protobuf additive 字段与 N/N−1 Gate 通过；公开契约生成/编译保持 245 Operations / 338
  Schemas；
- 完整 PostgreSQL/Redis/MinIO/mTLS/真实 Chromium Integration 以单个 19 Primitive Batch
  验证动作全部成功、微批次索引单调、至少五个微批次及稳定动作上限边界，并输出
  `agent_browser_dynamic_micro_batches=true`；其余 Agent Outcome、Challenge、恢复和迁移主链保持
  通过；
- Desktop test/lint/check 与 unsigned build 通过。

## 边界

- A06 仓库内通用动态微批次已关闭；站点特有动画、无穷流和业务事务仍可由 Application Adapter
  提供更强 Safe Point/Validator；
- 本切片只在“下一动作是否可继续”处组合已有 Route/Tab/Dialog/DOM/Network 证据。Layout、Focus
  和动画帧级稳定性的完整组合模型仍由 A20 跟踪，不因本切片提前关闭；
- 页面长期不稳定时选择终止而非自动重放，是有副作用动作的安全边界，不是透明恢复承诺。
