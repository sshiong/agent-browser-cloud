# Worker 队列事务通知与有界长轮询闭环

日期：2026-09-14。承接 progress 165 的 A14；本切片把 Agent Executor、Reviewer、Outcome
Verifier、Vision、Runtime Validation 与 Recovery GameDay 六类 Worker 的固定空轮询改为
PostgreSQL 事务通知驱动的有界长轮询，同时保留超时、backoff 和 jitter 作为断线兜底。

## 权威队列与唤醒语义

- V120 只新增一个通用触发函数和六个行级触发器。Job 插入、进入可领取状态或
  `available_at` 变化时，在事务提交后向 `browsercloud_worker_jobs` 发布最小化的
  `queue:opaque_job_id`；通知不包含 Tenant、Task、Prompt、页面内容、Secret 或 Claim Token；
- Control Plane 每实例持有一个专用 PostgreSQL `LISTEN` 连接。每条通知只唤醒该实例对应队列的
  一个等待请求，避免单实例全部 Worker 同时争抢；PostgreSQL 跨实例广播仍可能使每个 Control
  Plane 各唤醒一个请求，最终由原有 `FOR UPDATE SKIP LOCKED`/租约围栏保证只领取一次；
- 通知只作提示，队列表仍是唯一权威状态。Claim 在注册等待前查询一次，使用 generation 消除
  “查询为空到注册等待”之间的丢通知窗口，并在收到通知或超时后再次查询；监听断线和重连会唤醒
  现有请求，随后以 500—2000ms jitter 重连；
- 六类 Worker 默认请求 `waitSeconds=15`。服务端限制为 0—25 秒；204、连接失败或未来
  `available_at` 尚未到期时，原有有界指数 backoff + equal jitter 继续生效。因此 PostgreSQL
  通知不可用不会丢任务，只会退化为有界重查。

## 契约、升级与回滚

六个既有 Claim Operation 增加可选 query 参数 `waitSeconds`，公开基线仍为 245 Operations /
338 Schemas。省略参数时默认 0，保持 N−1 客户端的立即返回语义；旧 Control Plane 会忽略 V120
通知，旧 Worker 也不会发送新参数。新 Control Plane 在 V120 上通过通知降低空查询，在通知链
失效时通过超时重查保持正确性。

V120 不增加列、不回填历史数据、不扫描或改写 Job 行，只在六张既有队列表上创建触发器。应用回滚
时可保留 V120：旧二进制不订阅该 Channel，通知没有副作用。若必须回滚 Schema，应在停止新版本
流量后删除六个触发器及 `notify_worker_job_ready()`；队列数据和租约状态不需回滚。

## 验证

- Control Plane 553 项通过；新增单测覆盖立即领取、通知唤醒后二次权威读取、丢通知超时后二次读取、
  generation 竞态保护及单实例一次只唤醒一个等待者；
- Worker 43 项通过（Agent/Reviewer/Outcome/Vision 31、Validation 8、GameDay 4），六类 Claim
  均断言 15 秒参数；完整 `make ci` 通过，包括 Web 141 项、Rust、Go、四语言 SDK、供应链、
  Operator、50k Capacity 与 V120 N/N−1 Gate；
- Desktop test/lint/unsigned build 通过；
- 完整 PostgreSQL/Redis/MinIO/mTLS/真实 Chromium Integration 通过：数据库确认六个触发器存在，
  Reviewer 在 Control Plane B 上先进入长轮询，任务由 Control Plane A 创建并提交事务后提前唤醒，
  在 10 秒门限内领取成功，输出 `worker_queue_long_poll_notify=true`。

## 边界

- A14 仓库内代码项关闭；本切片没有把 Job Payload 搬进通知，也没有改变 Claim Token、Lease、Epoch、
  Tenant/RBAC 或幂等语义；
- LISTEN 使用每个 Control Plane 一个专用数据库连接，需要在生产连接预算中计入；跨实例通知的争抢
  上界是 Control Plane 副本数，而不是 Worker 数；
- `useRecoveryGameDayEvents()` 的 UI timeline 轮询是独立读取链，不属于 Worker Job Claim，仍需
  完整、可续传的 timeline 事件源后单独替换。
