# Recording 云端保全 Worker 与原请求恢复

> 日期：2026-10-23
> 基线：e5b50851204807780044c63d614f3551ffed289c
> 状态：V135 队列已接 Worker/Gateway；正式 API/UI 和真实完整链仍待验收。

## 实现

- Spring Worker 每 30 秒处理至多十项，唯一来源为 PostgreSQL 已提交的 V135 job。
  默认 cloud 标记仍关闭，现有 API 尚不能启用它；不把逻辑策略自动升级为云保全。
- 第一独立事务按 Record→Authority→Job 顺序领取，使用共享策略锁和 SKIP LOCKED，
  在任何 RPC 前提交 EXECUTING、attempt、started_at 与递增 lease_epoch。业务请求的
  operation_id、authority_epoch、模式、前序 Proof、Manifest 与执行预算完全保持。
- 第二独立事务重新锁定同一 Record/Authority/Job，校验活动请求、EXECUTING 与精确
  lease_epoch 后才调用正式 Gateway。行锁覆盖有界 RPC 与结果提交；旧 Worker 延迟
  恢复时不能越过已提交的新租约再次派发。
- 无协议能力的 QUEUED 请求保持 PENDING、30 秒后再查，不消耗执行尝试。超过两分钟
  的遗留 EXECUTING 可恢复，但仍使用原请求；新业务 Epoch 只能由策略变化生成。
- 派发后拒绝、坏回执、传输未知以及此前可能已派发的本次预检拒绝均保留 UNKNOWN。
  最多十次有界核对，耗尽仍为 UNKNOWN，明确 failure code，不伪造 FAILED、OFF、
  Receipt 或“远端无副作用”。S3 PREPARED 未知 Intent 不会由 Worker 覆盖或强行重试写入。
- 成功回执再次验证身份/Epoch/模式/Hash/版本计数/时间，提交 job Proof 后才投影 applied
  状态；由 V135 guard 校验其精确来自旧活动 job。当前 desired 已前进时保持 PENDING，
  再使用前序 Proof 排下一请求。只有当前 OFF 证明完成才释放数据库 legal_hold。
- 审计只含安全身份、Epoch、Hash、计数和固定错误码。审计或结果事务失败不会抹去先前
  已提交的 EXECUTING；之后只能沿原请求核对，不重发一个不同业务请求。

## 验证

原 `make test-recording-retention-order` 已扩展为两个真实 PostgreSQL 服务套件：
**14 项原删除测试 + 10 项新 Hold 测试 = 24 passed / 0 failed / 0 skipped**。Runner
逐套校验数量/失败/跳过并输出安全计数，CI 已沿既有必需步骤执行，不用常规 skip 代替。

新套件执行实际 V99/V128/V134/V135、真实 JdbcTemplate/两阶段 TransactionTemplate：

1. 另一个数据库连接在 Gateway 调用时可见已提交的租约；ON/OFF 与前序 Proof 闭环。
2. 响应丢失后保留原请求，先核对旧 ON，随后才排策略要求的 OFF。
3. 输入派发后进程崩溃保留 EXECUTING，恢复只增加租约，业务 Epoch/请求不变。
4. 能力缺失不派发、不消耗执行尝试。
5. 已耗尽的崩溃租约留 UNKNOWN，不再派发。
6. 最后一次核对仍未知时明确耗尽，不伪造失败或释放。
7. 坏回执不生成 applied Proof。
8. 审计失败使结果事务回滚，但不可撤销已提交的执行尝试。
9. 两个 Worker 不能并发执行同一个请求。
10. 真实 commit/cleanup 后暂停旧 Worker，新 Worker 提交第二租约；旧 Worker 恢复
    时 Gateway 调用数仍为零，只有新 Worker 最终派发一次。测试无需生产暂停钩子。

Gateway 在此为明确的合成回执夹具，不能据此声称真实 S3 副作用成功。常规 Java
**655 passed / 0 failed / 24 skipped**；这 24 项环境测试已由上面的专用 Gate 实际执行。
本地复用进度 292 实际 Linux 生成、源 Proto 未变化的 Java 产物，实际编译新 Worker
与测试；没有将本地 -x generateProto 宣称为新生成。本提交 Linux CI 仍需完整验证。
Java 格式、Shell 语法、N/N−1 与文档检查通过。

首轮测试的 Mockito 重设调用触发旧 Answer 接收 null，五项在夹具设置处失败；已改用
不调用旧 Answer 的 doAnswer，保留全部业务断言，之后专用套件全部通过。

私有证据：`/tmp/agentbrowser-hold-worker-postgres-v4.log` 与 `.exit`、
`/tmp/agentbrowser-hold-worker-java-final.log`、`/tmp/agentbrowser-hold-worker-compatibility.json`。

## 剩余范围

策略 API/四语言 SDK/UI、desired/applied/UNKNOWN 状态与权限、同状态核对/耗尽细节的
SSE 覆盖（当前 authority 状态转换有事件）、完整控制面/JPA 审计→
实际 Rust Node→MinIO mTLS 验收，以及目标云与批量规模 Gate。当前 RPC 仍绑定 job 的
原 Node，不能把故障后的跨 Node 重绑定当作已验证能力。PREPARED 结果长期未知时，
仍需明确的远端静默/核对证据才能进一步恢复，不能靠等待时间推断晚到 OFF 已消失。
本 Worker 不构成云保全产品全量完成或 V16 发布结论，原十一项目标继续保留。

e5b5085 主 CI **39909684661**、Desktop **39909684638** 已读取最终精确 SHA 与全部
Job 成功，包含新八项 SQL Gate、Integration/完整 GameDay、Operator、Windows/macOS；
进度 293 已收尾。本 Worker 提交须核验自己的 CI。

## 精确提交 CI 收尾

237920ef9c2786ada66703dc845b4bfde133801f 主 CI **39912590714**、Desktop
**39912590729** 最终精确 SHA、全部 Job 已核验成功，含十项 Worker/十四项删除/八项
SQL Gate、扫描、Integration/完整 GameDay、Operator、Windows/macOS。证据为
`/tmp/agentbrowser-hold-worker-ci-final.json` 和对应 Desktop JSON。API/SDK/UI 与同状态
job 事件后由 progress295 接入，完整真实链及目标云仍待验收。
