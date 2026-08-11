# 原生 Region State Resync 原子替换闭环

> 状态：仓库内代码、契约、持久化栅栏和真实集成链已完成。
> 日期：2026-08-11

## 本轮目标

删除 `REGION_RESYNC_FULL_FALLBACK` 作为正常 Region 实现的限制，使 Region Resync
只重建指定 DOM Root，不让区域外 Target Ref 失效，并保证 Control Plane 不会把
区域结果应用到错误的 State Version、Context Epoch 或 Target Revision。

## 已完成

### Browser Node / State Collector

- `resync_region` 现在必须接收 Node 已提交的 `CurrentState` 基线；基线缺失、
  处于 Invalid/Resync Barrier、URL 改变、State Version 或 Target Revision 失配时拒绝
  Region，明确要求 Full Resync，不做静默全量伪装。
- Root 支持有界 CSS Selector、`document` 和当前 Target Ref。Selector 通过 JSON
  序列化后嵌入固定 `Runtime.evaluate` 程序，不允许请求方注入任意 JavaScript。
- Region 采集仅返回 Root 本身和子树内的交互目标；Node 用绝对 DOM Path
  删除旧区域目标、合并新目标，再重算整体 State Hash。
- 区域合并保持当前 Target Revision，区域外 Target Ref 不变；采集器对目标按
  DOM Path 规范排序，后续周期 Full 采集与 Region 结果产生同一 Hash，不会虚假
  抬升 Target Revision。
- Collector Cursor 仅在内容 Hash 变化、Agent 动作后的显式 Confirmation 或 Full Resync
  时推进 State Version；五秒轮询和 HumanTakeover Barrier 的无变化采集不再产生没有
  事件对应的“幽灵版本”，因此显式 Region 不会与后台轮询竞争后偶发
  `baseline stale`。Agent Confirmation 即使公开 DOM 未变化也推进 State Version，但不
  改变 Target Revision，继续提供动作完成提交屏障。
- 每个 Session 使用独立采集锁，避免五秒周期采集与显式 Region/Full 命令
  同时修改 Cursor 和 Target Registry。Runtime 注销保留单调 State Version，但清空页面
  身份并移除 Registry/采集锁；下一 Browser Generation 首帧因此强制提升 Target
  Revision，旧交互引用不会在 Crash Recovery 后重新变得可执行。

### 契约与 Control Plane

- `BrowserStateDiffEvent` 增加向后兼容的 `snapshot_kind=REGION_RESYNC` 和
  `requested_root_ref`；旧 Node 周期 Diff 的两字段保持空值。
- Region 结果复用现有 At-least-once Node Journal/Event 和 `baseStateVersion` Diff
  链路，不新建一套无法重放的侧路协议。
- Mapper 严格校验 Snapshot Kind、Root 长度/控制字符、版本和目标上限。
- `BrowserStateJpaRepository.findByIdForUpdate` 使用 PostgreSQL Pessimistic Write Lock；
  Repository 在同一事务内校验 Tenant、Context Epoch、Base State Version 和 Region
  Target Revision，再删除/新增目标并写回新版本。
- 基线失配继续走已有 `INVALID → AUTO FULL RESYNC` 回路；Region 来源、Root、
  Base/Current Version 写入租户防篡改 Audit Chain。

## 验收证据

已通过：

```bash
cargo test --locked --manifest-path apps/browser-node/Cargo.toml --workspace
./gradlew -p apps/control-plane test
make contracts
make test-e2e
make test-integration
```

重点覆盖：

- Full 基线内同时存在 Root 内/外目标；Region 删除旧目标、新增替换目标，
  区域外 Target Ref 保持不变。
- Region 后立即进行周期 Full 采集，整体 Hash、Target Revision 和目标集保持一致。
- 周期无变化采集不抬升 State Version；Agent 动作后的 Confirmation 即使公开 DOM
  未变化仍推进版本并保持 Target Revision，真实 Web/Agent E2E 可继续完成 `TYPE_TEXT`。
- Control Plane 映射并持久化 Region 元数据；Base Version 不匹配或 Region Target
  Revision 改变时不提交替换。
- 完整 PostgreSQL/Browser Node Integration 发起真实 `REGION(body)` 请求，观察
  State Version 提升，并从 Audit Chain 验证 `REGION_RESYNC:body`。

## 仍未完成

- Full Snapshot 的 Chunk/Checksum/Commit Frame、旧流取消/过期和端到端持久事件
  Backpressure 已由[进度 120](120-Full-State-Snapshot流式原子提交闭环.md)关闭；当前
  有 512 KiB 解码后硬上限，未启用需要额外压缩比与 CPU 防护的压缩。
- 实际 Snapshot 字节、Browser cgroup CPU、Region 和目标节点容量的多维预算已由
  [进度 122](122-State-Resync多维预算与实际结算闭环.md)关闭；仍缺目标 Linux 多 Node
  压力长稳和跨 Region 全局预算一致性证书。
- 目标 Linux 多 Node 长稳、网络分区与大 DOM Region 容量证书仍是生产 Gate。
