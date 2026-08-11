# State Resync 多维预算与实际结算闭环

> 日期：2026-08-12
> 状态：代码、安全迁移、定向测试、全量 CI、真实托管 E2E 与完整 PostgreSQL/Browser Node Integration 已完成。

## 本轮目标

在既有 Session/Tenant Token 防滥用和循环断路器之外，把 State Resync 的实际数据面成本纳入 PostgreSQL 权威预算：

- Full 和 Region 请求在下发前预留有界字节与 Browser cgroup CPU；
- 同时按 Session、Tenant、当前 Node 和 Region 四个维度准入；
- Node 回传真实 Snapshot 字节和采集期间 cgroup CPU 增量后结算；
- N−1 Node 未提供 CPU 指标时继续使用准入时的保守预留，不伪造测量值；
- Placement、Node 心跳或 Region 容量失效时 fail-closed，不向未知容量节点继续发起 Resync。

## 已完成

### 1. 四维权威准入

`StateResyncAdmissionService` 在统一事务中按 Tenant → Region → Node → Session 的固定顺序获取 PostgreSQL advisory transaction lock，并在锁后重新读取 Placement：

- Session/Tenant 保留原有五分钟加权 Token 上限；
- Full 默认预留 512 KiB / 2,000 ms，Region 默认预留 128 KiB / 500 ms；
- Node 字节/CPU 上限根据最新认证 vCPU 容量计算；
- Region 汇总所有 READY、OPEN、NORMAL 且心跳新鲜的 Node；
- PRIMARY/未知、SECONDARY、DR 分别使用 100%、75%、50% 容量权重；
- Node 非 READY、Admission 关闭、压力异常、心跳过期或企业 Region 关闭时拒绝请求；
- 锁等待期间 Placement 或 Region 发生变化时重新拒绝，不把旧容量用于新节点。

Ledger 同时保存估算值、保守预留、实际值、Node、Region、容量权重和结算状态。滚动窗口使用 `actual_*`；尚未结算或旧 Node 缺少 CPU 测量时使用 `reserved_*`，保证失败关闭。

### 2. 真实数据面结算

- Full Resync 在 Browser Node 读取 Browser cgroup 累计 CPU 前后值，随 Snapshot Begin 上报 `collection_cpu_millis`；实际字节使用 Manifest 的 `total_bytes`。
- Region Resync 同样采集 cgroup CPU，并通过新增的 `resync_request_id` 与 Admission Ledger 精确关联；实际字节取真实序列化 Diff payload 大小。
- 结算仅允许 Tenant、Session、Mode 与请求 ID 全部匹配，并仅把 `RESERVED` 原子更新为 `SETTLED`；重复事件保持幂等。
- 结算审计记录实际字节、CPU 来源、Node、Region 和 Request ID。CPU 未提供时标记 `RESERVED_FALLBACK`，不声称是 Browser 测量。

### 3. N/N−1 兼容

协议只追加字段：

- `BrowserStateSnapshotBeginEvent.collection_cpu_millis = 9`；
- `BrowserStateDiffEvent.resync_request_id = 16`；
- `BrowserStateDiffEvent.collection_cpu_millis = 17`。

N−1 Full Begin 没有 CPU 字段时可正常结算字节并保留 CPU 预留。N−1 Region Diff 没有 `resync_request_id` 时，Control Plane 只允许从已认证命令派生的 `evt_cmd_*` Event ID 恢复请求 ID；其他来源仍拒绝，避免把任意事件结算到其他请求。

## 数据库迁移运行手册

生产表规模和持续写入率需要在目标环境变更前实测，仓库证据不能替代生产锁预算。迁移采用 expand → online index → validate：

1. `V084` 只追加列、默认值和 `NOT VALID` Check Constraint；不执行历史 `UPDATE`，既有请求在五分钟窗口自然过期，避免表回写。
2. `V085` 使用 `CREATE INDEX CONCURRENTLY IF NOT EXISTS` 建立 Region/Node 滚动窗口索引，Flyway 配置 `executeInTransaction=false`。
3. `V086` 在独立版本验证 V084 约束。目标环境应先观察 V084/V085 的锁等待、复制延迟和索引有效性，再推进 V086。

上线前验证：

```sql
SELECT version, success
FROM flyway_schema_history
WHERE version IN ('84', '85', '86')
ORDER BY installed_rank;

SELECT indexrelid::regclass, indisvalid, indisready
FROM pg_index
WHERE indexrelid IN (
  'idx_state_resync_requests_region_budget'::regclass,
  'idx_state_resync_requests_node_budget'::regclass
);

SELECT conname, convalidated
FROM pg_constraint
WHERE conname LIKE 'chk_state_resync_%'
   OR conname = 'chk_browser_state_snapshot_collection_cpu';
```

回滚原则：旧版本应用会忽略新增列，必要时回滚应用并保留 additive Schema；已执行的 Flyway 迁移不删除、不改写，也不在事故窗口直接删列或索引。发现问题时以前向修复迁移停止新准入或修正约束，待滚动窗口清空后再处理收缩。

如果 V085 被中断且 `pg_index.indisvalid=false`，不得继续 V086。先用 `DROP INDEX CONCURRENTLY` 仅删除对应的无效新索引，执行 Flyway repair 后重新运行 V085，并再次确认 `indisvalid/indisready`；不要删除业务表、旧列或已成功的迁移记录。

## 验收覆盖

- Java：四维预留持久化、Session 字节拒绝、自动循环断路、锁等待后 Placement 改变、真实结算与审计、N−1 映射和非法事件拒绝；
- Rust：Browser cgroup 单调累计 CPU 的毫秒换算与向上取整；
- 升级门禁：V084 无历史回填且约束为 `NOT VALID`、V085 在线索引与非事务配置、V086 约束验证、Proto 字段编号稳定；
- Integration：真实 PostgreSQL、Control Plane、Browser Node 下的 Full/Region 请求均进入 `SETTLED`，实际字节不超过预留，并具有 Node/Region 归属。

## 仍未完成

- 目标 Linux 多 Node 压力长稳、真实 Cgroup 委派和跨 Node/Region 容量竞争证书；
- 生产 PostgreSQL 表规模下的在线索引耗时、复制延迟、锁等待和 V086 验证窗口实测；
- 跨 Region Event Bus/数据库复制后的全局预算一致性；当前权威预算仍以单一 PostgreSQL 写域为边界；
- 目标 Prometheus/Alertmanager/Pager 对 Node/Region Resync 预算拒绝、保守 CPU 回退和长期异常结算的告警到达演练。
