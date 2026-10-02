# Reviewer 执行入队与审计锁顺序收口

> 日期：2026-10-03
> 基线：`ec28bb8879b77fefe9c209e88648fa80e82d46ef`
> 范围：两条 Reviewer 入队路径与 PostgreSQL 锁交错 Gate。

## 代码缺口与修复

低风险确定性直达、独立 Reviewer APPROVE 两条路径原先都先调用审计 append，
取得 `tenant_audit_heads` 的排他锁，随后调用 Execution Worker enqueue。
enqueue 的 `agent_execution_jobs` INSERT 具有 Session 外键，仍会取得父行锁。
另一个持有 Session 排他锁、随后写审计的生命周期事务可以形成相反顺序。

本轮把两条路径的 enqueue 移到 appendAudit 前。Reviewer 状态、执行队列与
审计仍在原 `@Transactional` 内一起提交；拒绝路径、Plan Hash/Claim/Lease、
幂等键、审核判断和敏感输入门禁不变。未增加 API、数据库迁移或 SDK 契约。

## 可重复证据

- 低风险实际服务路径的顺序回归在修改前失败。修改后四项风险路由回归通过，
  含低风险直达和真实批准判断/精确 Payload Hash 围栏下的两条调用顺序检查。
  测试使用生产 Payload 计算与 RowMapper，不复制 Reviewer 判定逻辑。
- Java 全量 **624 项**通过，Spotless Java 通过。
- `tests/integration/audit_lock_order.py` 在 Integration 自有 PostgreSQL 中创建
  随机命名、明确隔离的最小 FK Fixture schema。只读 pg_stat_activity 确认
  Agent 连接实际等待父行锁，再释放对方审计步骤；旧顺序须得到恰好一个
  `40P01` 和一个成功。新顺序须两笔提交、一个执行 Job、审计序号 2。
  完成后 DROP 自有 schema，不读写正式业务表。单独 OrbStack PostgreSQL 17
  验证通过，并核对残留 Fixture schema 数量为零。
- 新 Gate 已接入 `tests/integration/smoke.sh`。初次引入时的 schema 建立失败
  不计通过；实现期间修正了 SQL 构造，并要求最终 PID 1 PostgreSQL 进程与
  SQL-ready 同时成立，避免把镜像临时初始化服务器当成稳定数据库。
  随后完整 OrbStack Integration 退出 0，输出 `audit_parent_lock_order=true`
  （JSON 布尔）、`agent_reviewer_risk_routing=true`、`audit_chain_valid=true`，
  以及原有 Recording 播放/物理删除、Outcome、Profile 和 Proxy 断言。

该 Fixture 证明所述锁环能够发生，Java 回归把顺序约束绑定到当前服务路径。
它不还原历史 CI `25091d7` 的完整锁图；不能据此宣布所有审计头死锁、所有
业务锁顺序或双 Coordinator 热点压力已经解决。

## Recorder CI 失败与诊断

请求证明修复 `517820e621f9aa8a27fe4f8d77c2826d1fb8648c` 的
[CI 37050160840](https://github.com/sshiong/agent-browser-cloud/actions/runs/37050160840)
与 [Desktop 37050160834](https://github.com/sshiong/agent-browser-cloud/actions/runs/37050160834)
完整成功，含 Integration、Recording GameDay、Operator E2E 和两个桌面平台。
后续文档提交 `ec28bb8` 的
[CI 37050710314](https://github.com/sshiong/agent-browser-cloud/actions/runs/37050710314)
却在 Recorder `recording_redaction_failure_acks_but_never_queues_the_raw_frame`
的 ready 接收得到 `RecvError`；Verify 失败，Operator 成功，
[Desktop 37050710368](https://github.com/sshiong/agent-browser-cloud/actions/runs/37050710368)
两个平台成功。两轮差异不得以另一轮成功覆盖。

该测试现在在 ready 失败时同时报告已有 capture result，使后续失败能显示
前置原因。仍严格要求 ready 成功、JPEG 解码失败、失败帧 ACK、原始帧不入队
与 capture_failed=true。本机 Recorder 10 项与相同失败用例连续 50 次通过，
没有重现 Linux CI 的前置失败，底层原因仍未确认；未增加捕获重试或跳过断言。

原始失败日志保存在 `/tmp/agentbrowser-ci-ec28bb8-failure/failed.log`（私有目录
0700、文件 0600）。本轮 Java、PostgreSQL 与 Recorder 证据位于
`/tmp/agentbrowser-reviewer-order-*.log`、`/tmp/agentbrowser-audit-order-proof.log`
及 `/tmp/agentbrowser-recorder-startup-repeat.log`。

## 剩余目标

十一项按 [252](252-十一项目标完成边界与SSE修复CI核验.md) 与
[254](254-Replay事务重试证明与OTP轮询复核.md) 的完整边界继续执行。
公开 17 例连续稳定性、长轮询的业务用途/升级证明、历史审计交错、Recorder
Linux CI 前置失败、客户/供应商接入、目标云/Linux/硬件、组织签字与许可证
决定仍未关闭。本轮改动推送后的 CI/桌面结果须按精确 SHA 单独核验。
