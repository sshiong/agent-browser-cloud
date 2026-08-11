# State Gateway Diff 与 Resync MVP

> 状态：Phase 3 / 9.4 主链路、事件背压、持久请求预算与自动 Full 循环断路器已完成；原生 Region 裁剪和流式 Snapshot 仍待开发。
> 日期：2026-08-11

## 已完成

### State Collector

- Current State 保留 Session、State Version、Target Revision、URL、Title、State Quality、
  Content Hash；当前 Collector 单快照最多采集 40 个交互 Target，超过时明确标记
  `DEPTH_LIMITED`（协议硬上限 500）。
- Target Ref 改为基于 DOM 路径的稳定 SHA-256 短引用；同一 Target Revision 内重复采集
  不再因 State Version 变化而全部失效。
- 导航或显式 Resync 会递增 Target Revision；普通内容变化只递增 State Version。
- 新增有界 `StateDiff`：包含 Base/Current Version、Upsert Target、Removed Target、
  URL、Title、Quality 和新 Hash。
- Diff 超过目标数量或序列化字节上限时生成 `DiffTruncated`，不再静默丢弃。

### Browser Node 与契约

- Protobuf 新增 `BrowserStateDiffEvent`、`DiffTruncatedEvent` 和
  `RequestStateResyncCommand`。
- Node 首次采集提交 Full Current State，后续内容变化提交 Diff；未变化的采集不产生
  空事件。
- Diff 事件和 Full State 一样先进入 SQLite Journal，再向 Control Plane 投递，保持
  At-least-once 与重启重投语义。
- Diff Truncated 后 Node 冻结该 Session 的增量提交，等待 Resync，避免在损坏基线上
  继续生成 Diff。
- `STATE_DIFF_MAX_BYTES` 默认 60,000 且不允许超过事件 Envelope 的安全预算；
  `STATE_DIFF_MAX_CHANGES` 默认 200。
- `STATE_EVENT_BACKLOG_LIMIT` 默认 64；持久待提交 Diff/Truncated 事件达到高水位时只生成
  一条 `DiffTruncated(BACKPRESSURE_LIMIT)`，先安装 Resync Barrier，再冻结增量采集，
  避免 Control Plane 快速返回 Full Resync 时发生竞态或继续放大队列。

### Control Plane

- Node Event Mapper 对 Diff Base/Current Version、Target、Truncated Reason 和
  Affected Root 做协议校验。
- Current State Repository 只在 Context Epoch 和 Base State Version 精确匹配时应用
  Diff；不匹配时保留最后快照但将质量标记为 `INVALID`。
- 收到 `DiffTruncated` 或 Base Gap 后自动写入 Full Resync Node Command，不依赖人工
  发现；命令通过 PostgreSQL Outbox 异步投递。
- 新增 `POST /api/v1/sessions/{sessionId}:resync-state`，支持 `FULL` 和 `REGION`
  请求、Tenant 校验、运行态 Gate、Idempotency-Key 和 `RESYNCING` 状态。
- Resync 完成前 Current State 显示 `RESYNCING`；只有新的 Full State Event 成功提交后
  才恢复 `COMPLETE` / `DEPTH_LIMITED`。
- Resync API 使用 PostgreSQL 权威幂等记录；同 Key/同请求返回原 Request ID，
  同 Key/不同请求返回幂等冲突。
- V082 新增加权 Resync Admission Ledger：Full 请求计 10 Token，Region 请求计 2 Token；
  默认五分钟 Session 上限 60、Tenant 上限 600，并使用 Tenant → Session 顺序的
  PostgreSQL 事务 Advisory Lock 保证多实例并发下不会超发。
- 自动 Full Resync 使用每 Session 60 秒 30 Token 的持久循环断路器。断路打开时保留
  `INVALID` 状态、提交触发事件但不继续排队命令，避免坏 Diff/Full 循环阻塞 Node Inbox；
  人工请求返回 `429`、`Retry-After`、稳定错误码、Scope 和 Request ID。
- Admission Ledger 只持久化 Region Root 的 SHA-256，不保存原始页面语义；拒绝和断路事件
  写入防篡改审计，其中人工拒绝由独立事务保存；默认账本保留七天并定时清理。

### Web Console

- Session 详情 Browser State 面板新增 Full Resync、Region Root 输入和 Region
  请求入口。
- UI 明确提示 `INVALID/RESYNCING` 期间语义动作应保持冻结，并显示结构化请求错误。
- Full Resync 不做乐观完成；页面继续轮询权威 Current State，等待 Node Event 提交。

## 验收证据

已通过：

```bash
./gradlew -p apps/control-plane test
cargo test --locked --workspace --manifest-path apps/browser-node/Cargo.toml
pnpm --dir apps/web-console test
pnpm --dir apps/web-console lint
pnpm --dir apps/web-console build
make contracts-check
make test-integration
make test-e2e
```

测试覆盖：

- 同 DOM 路径在连续采集中保持稳定 Target Ref。
- 内容变化生成包含 Upsert/Remove 的有界 Diff。
- 目标数量超过预算时返回 `DiffTruncated(TARGET_LIMIT)`。
- 持久事件队列达到高水位时只返回一次 `DiffTruncated(BACKPRESSURE_LIMIT)`，并在 Full
  Resync 前保持增量冻结。
- Control Plane Base Version 不匹配时标记 `INVALID` 并自动排队 Full Resync。
- Session/Tenant 加权预算在 PostgreSQL 并发锁下精确阻断超额请求；幂等重放不重复计费，
  拒绝请求不残留幂等 Claim；自动 Full 循环断路时不重试消费触发事件。
- Region 请求缺失 Root 时拒绝；合法请求写入带 Context Epoch 的 Node Command。
- 集成链路从 Full v1 经真实 Diff 应用到 v2，再经过
  `RESYNCING → Full Resync v3 / COMPLETE`。
- Web E2E 通过真实详情页发起 Full Resync，并验证后端返回 `QUEUED`。

## 仍未完成

| 缺口 | 说明 |
|---|---|
| 原生 Region Snapshot | `REGION` 命令与 API 已存在，但 Collector 当前显式返回 `REGION_RESYNC_FULL_FALLBACK`，尚未只重建被替换 Root |
| Chunked Snapshot | Full Snapshot 仍是单 Event，尚无 Chunk、Checksum、Commit Frame、流式 Backpressure、Compression 和 Cancellation；当前高水位背压只保护持久 Diff 事件队列 |
| Resync Budget 深度 | V082 已完成 Session/Tenant 请求 Token 和自动 Full 循环断路；尚无按 Snapshot 字节、CPU、Region 及目标节点容量计费的多维 Token Bucket |
| Diff Buffer | 当前只保留 Node 内单份基线和 PostgreSQL Current State，尚无可观测的有界 Diff Buffer、Consumer Cursor 和重放 API |
| 状态动作 Gate | Phase 4 Plan Validator 已拒绝 Invalid/Resyncing 等不可执行质量；真正的 Executor/Tool Service Action Gate 与执行后验证仍待实现 |
| State Checkpoint | 尚无与 Profile、Browser Generation、Runtime Build 绑定的 State Checkpoint Epoch、Commit Marker 和恢复参考摘要 |
| 敏感数据治理 | Target 名称 Password 抑制及 Password/OTP/Payment/Account/Secret/PII 基础分类、Evidence 截图遮罩已由进度 88 补齐；Site Policy、无语义视觉分类、Recording 帧级遮罩、字段级保留期仍未闭环 |

## 结论

开发计划 9.4 的 Current State、State Version、Target Ref、Diff Event、DiffTruncated、
Full Resync、State Quality、事件高水位和持久请求预算已形成可重复的技术闭环。Region
Resync 目前只有协议与安全全量回退，不能计为原生区域重建完成；V16 的 Chunked/流式
传输、字节/CPU/Region 多维预算、Checkpoint 和 Agent 强制 Gate 仍属于后续生产能力。
