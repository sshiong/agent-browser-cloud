# Agent 执行取消与权威 Epoch 快速终止闭环

日期：2026-09-15。承接 progress 165 的 A15；本切片把人工取消、Operation 丢失/过期、
Step 失败以及 Context/Operation/Coordinator/Route Epoch 前进统一投影为 Browser Node 可立即执行的
动作终止信号，不再只依赖迟到结果被控制面拒绝。

## 权威取消链

- 新增幂等 `POST /api/v1/agent-tasks/{taskId}:cancel`。控制面先在同一 PostgreSQL 事务中写入
  `CancelAgentAction` Outbox，再将当前 Exclusive Operation 原子转为 `ABORTED`、Task 转为
  `FAILED/AGENT_TASK_CANCELLED` 并追加一次 Audit；同一 Idempotency-Key 重放返回同一终态；
- Step 失败、执行异常、恢复时发现 Operation 已丢失，以及 Operation deadline 过期也走同一
  `cancelPendingNodeAction` 入口。外部 Worker 自己的短 Lease 丢失仍由既有 Token/Epoch 拒绝迟到提交；
  只有控制面确认权威 Operation 不再有效时才终止已派发的浏览器动作，避免瞬时 Worker 重领误杀；
- `AGENT_CANCEL_V1` Coordinator 命令和 `CancelAgentAction` Node Outbox 各有独立高优先级 Claim，
  且使用两个线程的专用 `agent-cancel-*` 调度器；长 Node RPC、普通 Workflow 和通用定时任务不能占满
  取消执行槽。通知/轮询仍以 PostgreSQL 队列为权威，进程重启后可续投。

## Browser Node 栅栏与输入释放

- Node 为每个 Session 保存当前 AgentAction 的 Task ID、Context Epoch、Operation Epoch、Coordinator
  Term、Route Epoch 与 watch 取消句柄。精确 Task/Context/Operation 的显式取消会立即唤醒动作；
  `StopRuntime` 或更高的四元权威序列也会终止旧动作；
- 取消与动作注册乱序时，持久命令栅栏之后的内存 tombstone 在注册前后各检查一次，关闭
  “取消先到/注册后到”和“检查后/注册前”两个窗口。旧或 N−1 命令仍由既有 Route/Term 栅栏拒绝；
- 动作执行以 `tokio::select!` 同时等待真实 CDP 操作和取消信号。取消分支丢弃进行中的 future，调用
  Input Broker `release_all` 清理按键、鼠标和触控 ledger，并产出稳定的 `ACTION_CANCELLED` 失败证据；
- Task/Operation 已终止后，控制面会以 `STALE_AGENT_OPERATION` 拒绝迟到失败事件。Node 将该错误视为
  不可恢复的终态回执并标记 Journal 已投递，既不让迟到事件复活 Task，也不形成永久重投毒消息。

## 契约、升级与回滚

- Protobuf 只新增 `CancelAgentActionCommand`，旧 Node 对未知命令 fail-closed；新 Node 继续接受 N−1
  既有命令。Node Heartbeat 广告 `agentActionCancellation=authority-watch-v1`，新控制面在任何动作派发前
  要求该能力；因此滚动升级必须先升级 Node，版本顺序错误会以
  `AGENT_ACTION_CANCELLATION_UNAVAILABLE` 在副作用前 fail-closed。公开 API 只新增一个取消 Operation，
  基线为 246 Operations / 338 Schemas，四语言 SDK 与生成 Manifest 已同步；
- 本切片不新增数据库迁移。回滚应用时既有 Outbox/Coordinator 行仍保留原幂等与 deadline 语义；
  应先停止新版本流量并排空 `CancelAgentAction`，再回滚到不识别该命令的 Node，避免把安全取消降级成
  `UNSUPPORTED_COMMAND`。

## 验证

- Control Plane 553 项测试通过；Rust Workspace、Clippy 和 Node 定向测试覆盖显式 Task 取消、
  Context/Operation/Term/Route 前进、取消先到与稳定错误码；
- OpenAPI 校验、四语言 SDK 生成/漂移检查、完整 `make ci`、Desktop test/lint/unsigned build 与
  N/N−1 Gate 通过；
- 完整 PostgreSQL/Redis/MinIO/mTLS/真实 Chromium Integration 以 10 秒 `WAIT_FOR STATE_CHANGED`
  动作为探针：待原动作被真实 Node Claim 后调用取消 API，Node Journal 权威时间证明 3 秒内产生并
  投递 `ACTION_CANCELLED`；Task 为 FAILED、Operation 为 ABORTED、Audit 仅一条、取消重放相同，输出
  `agent_action_fast_cancellation=true`。

## 边界

- A15 仓库内通用代码项关闭；取消是权威控制信号，不保证第三方服务器已经撤销在取消前完成的网络
  副作用，因此高风险事务仍必须使用 Expected Outcome、Application Lease 和 Provider Evidence；
- 已经发出的外部模型或普通 HTTP 请求仍可能在客户端传输层继续占用连接，但过期 Token/Lease/Epoch
  不能提交结果。本切片保证的是 Browser Node 动作与输入 ledger 快速终止；
- 3 秒是完整集成 Gate 上限，不是对目标云网络的产品 SLA。目标多 Region、网络分区和压力长稳仍需
  独立生产验收。
