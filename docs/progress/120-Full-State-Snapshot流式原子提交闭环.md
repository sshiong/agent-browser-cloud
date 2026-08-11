# Full State Snapshot 流式原子提交闭环

> 状态：仓库内协议、Node Journal、PostgreSQL 原子组装、升级门禁与真实集成已完成。
> 日期：2026-08-11

## 本轮目标

关闭显式 Full State Resync 仍使用单个 `BrowserStateUpdated` Event 的限制。在不改变
周期 State、Agent 动作确认和原生 Region Diff 链路的前提下，实现有界的
`Begin → Chunk → Commit` 协议，并保证网络中断、Node 重启、重复投递、乱序 Commit
和校验失败都不会把半份 Snapshot 暴露成权威 Browser State。

## 已完成

### Browser Node

- 显式 `FULL` Resync 将完整 `BrowserStateEvent` protobuf 序列化后切为 16 KiB 分块；
  单次最多 32 块、512 KiB，超限明确失败，不绕过事件载荷和内存预算。
- `BrowserStateSnapshotBegin` 声明 State/Target Version、块数、字节数、Snapshot Kind
  和整流 SHA-256；每个 `BrowserStateSnapshotChunk` 带序号与独立 SHA-256；最后用
  `BrowserStateSnapshotCommit` 重申不可变 Manifest。
- Node Journal 新增有序事件批次事务。Begin、全部 Chunk 和 Commit 在一个 SQLite
  `IMMEDIATE` 事务内持久化，并使用单调 `created_at_ms`；进程在发布中途退出后仍按
  Begin、Chunk、Commit 顺序重投。
- 首次直发在任一 Event 失败时立即停止，后续事件留在 Journal；不会让 Commit 主动
  越过失败分块。重复 Command 仍以原始 message ID 去重，派生事件 ID 和 Journal ID
  保持确定性。
- Snapshot Frame 纳入现有持久 State Event 高水位；创建前预留整个流的事件数，交付
  成功逐项扣减，Node 重启时从 SQLite 重建深度。默认 64 的队列不能被 Full Stream
  绕过。
- 周期 State、Agent 动作 Confirmation、HumanTakeover Barrier 和原生 Region Diff
  继续走原协议，降低 N/N-1 滚动升级与功能回归面。

### Control Plane / PostgreSQL

- V083 新增 `browser_state_snapshot_streams` Manifest 与
  `browser_state_snapshot_chunks` 临时分块表；数据库约束再次限制 32 块、512 KiB、
  每块 16 KiB、Hash 格式和合法状态机。
- Mapper 在业务事务之前校验 Session、Snapshot ID、版本、块边界和每块 SHA-256；
  Event Envelope 仍保持 64 KiB 上限。
- Assembler 对 Manifest 使用行锁，校验 Tenant、Coordinator Term、Context Epoch、
  Operation Epoch 和所有不可变字段。重复 Chunk 必须与已保存字节完全一致。
- Commit 先进入 `COMMIT_RECEIVED`。即使 Commit 早于最后一块到达，后续 Chunk 也会
  重新尝试组装；只有块数、顺序、逐块 Hash、总字节和整流 Hash 全部正确时才解析
  `BrowserStateEvent`。
- Manifest 的 Session/State Version/Target Revision 与 Snapshot 内容再次匹配后，
  在同一 PostgreSQL 事务中保存正式 Browser State、标记 `COMMITTED` 并删除临时块。
  任何正式 State 保存失败都会回滚 Commit 和清理，保留可重试数据。
- 同 Session/Context 的新 Begin 会取消旧的未完成流并清除旧块；十分钟未完成流标记
  `EXPIRED`，终态临时记录一天后清理。已提交、取消、过期或拒绝的迟到 Event 幂等
  接收但不会修改权威 State。
- Chunk 作为传输噪声不逐条写 Audit；Begin/Commit 写入 Snapshot ID、块数、字节数和
  State Version，避免 Audit 体量被大 DOM 放大。

## 验收证据

已通过：

```bash
cargo test --manifest-path apps/browser-node/Cargo.toml -p node-journal -p node-agent
./gradlew -p apps/control-plane test
python3 tests/upgrade/n-minus-one-gate.py
make test-integration
```

真实 Integration 会发起正式 `POST /sessions/{id}:resync-state` Full 请求，并验证：

- Browser State Version 提升且质量恢复为 `COMPLETE`；
- V083 Manifest 最终状态为 `COMMITTED`；
- 对应临时 Chunk 数为 0；
- 随后的原生 Region Resync、Agent、VNC、Crash Recovery、迁移和完整企业烟雾链继续通过。

## 仍未完成

- Resync Admission 已有请求 Token、实际字节与 Node 事件队列硬边界；尚缺把实际字节、
  采集 CPU、Region 权重和目标节点容量统一计费的多维全局预算。
- 当前 protobuf 已有 512 KiB 上限，因此未启用压缩；如后续引入压缩，必须同时加入
  解压后大小、压缩比炸弹和 CPU 预算，不能只降低网络字节。
- 目标 Linux 多 Node 长稳、网络分区、大 DOM 容量证书和跨 Region Event Bus 仍属于
  生产环境 Gate。
