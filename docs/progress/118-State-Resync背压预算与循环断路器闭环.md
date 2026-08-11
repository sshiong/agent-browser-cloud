# State Resync 背压、预算与循环断路器闭环

> 日期：2026-08-11
> 状态：代码、迁移、契约、单元测试、N/N−1 与完整 PostgreSQL Integration 已完成；后续流式 Snapshot 由进度 120 关闭，多维资源预算由进度 122 关闭。

## 问题

State Gateway 已具备有界 Diff、截断后自动 Full Resync 和人工 Full/Region 请求，但仍有
两个可放大故障的入口：

- Control Plane 或网络长期不可用时，Browser Node 会持续把 Diff 写入 SQLite Journal；
- 坏基线、坏页面或失败 Full 可能循环触发自动 Resync，人工请求也没有跨实例权威预算。

只限制单个 Diff 的字节和 Target 数量不能解决持久队列增长，也不能阻止多 Control Plane
实例并发超发恢复命令。

## 已完成

### Browser Node 高水位

- 新增 `STATE_EVENT_BACKLOG_LIMIT`，默认 64、允许范围 4—4096；
- 每次产生增量前统计该 Session 未确认的 Diff/Truncated Journal 深度；达到高水位时
  只生成一条 `DiffTruncated(BACKPRESSURE_LIMIT)`，随后冻结增量采集；
- Resync Barrier 在 Journal 持久化和事件发布前安装，避免 Control Plane 快速回送 Full
  Resync 命令时先清除、后覆盖 Barrier 的竞态；仅 Journal 写入失败时撤销本次 Barrier；
- 后续采集等待 Full Resync，不会重复生成 Truncated 事件或继续放大持久队列。

### PostgreSQL 权威预算

- V082 新建 `state_resync_requests` Admission Ledger，记录 Tenant、Session、请求来源、
  Full/Region 类型、加权 Token、时间和 Request ID；
- Full 请求计 10 Token，Region 请求计 2 Token；默认五分钟 Session 上限 60、Tenant
  上限 600；
- Admission 在同一事务内按 Tenant → Session 固定顺序获取 PostgreSQL Advisory Lock，
  多 Control Plane 实例并发时也不会检查后同时超发；
- 幂等重放在 Admission 前返回，不重复消耗预算；预算拒绝会回滚幂等 Claim；
- Region Root 只保存 SHA-256，不把原始 DOM Root 或页面语义写入治理账本；账本默认保留
  七天并由定时任务有界清理。

### 循环断路与 API

- 自动 Full Resync 使用每 Session 60 秒 30 Token 的持久断路窗口，即默认最多三次；
- 自动事件触发断路时，Current State 保持 `INVALID`，不再排队新命令，但触发 Node Event
  会正常提交，避免 At-least-once Inbox 永久重试同一坏事件；
- 人工请求超额返回 `429 Too Many Requests`，同时返回 `Retry-After`、
  `STATE_RESYNC_BUDGET_EXHAUSTED`、预算 Scope、重试秒数和 Request ID；
- 人工拒绝与自动断路均写入防篡改审计；人工拒绝由独立事务保存，自动断路与触发事件
  原子提交。工作流内部 Full Resync 明确按自动来源计入断路窗口，不能绕过治理。

## 验收证据

```bash
cargo test --locked --workspace --manifest-path apps/browser-node/Cargo.toml
cargo clippy --locked --workspace --all-targets \
  --manifest-path apps/browser-node/Cargo.toml -- -D warnings
./gradlew -p apps/control-plane test check
make contracts-check
make test-upgrade-compatibility
make test-integration
```

测试覆盖高水位只产生单一 Barrier、协议拒绝未知 Truncated Reason、用户与自动请求分别
进入正确预算、幂等重放不重复计费、自动断路 fail-closed、429 Header/错误结构、V082
加法迁移，以及真实 PostgreSQL 锁下饱和 Session 窗口后拒绝下一次 Full。

## 尚未完成

1. Full Snapshot 仍是单 Event；Chunk、Checksum、Commit Frame、压缩、取消与流式慢消费者
   背压尚未实现；
2. V082 治理的是请求加权 Token，尚未按 Snapshot 字节、CPU 时间、Region、目标节点容量
   或租户成本预算计费；
3. Region Resync 仍显式使用 Full Fallback，不能计为原生局部重建；
4. 目标 Linux 长时间 Control Plane 分区、超大页面和多 Session 并发压力证书仍需执行。
