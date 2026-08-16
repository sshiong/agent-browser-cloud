# Browser Node 心跳新鲜度事件与轮询移除

> 日期：2026-08-17
> 状态：仓库实现、滚动升级 Gate 与完整 PostgreSQL/mTLS/Chromium Integration 已通过。

## 本轮完成

- 新增 V101 `browser_node_freshness_projections`，把每个 Node 的权威心跳状态投影为
  `FRESH / STALE`；表只保存跨 Control Plane 去重状态，不复制 Node 资源 Payload；
- 五秒轻量 Projector 以 60 秒权威新鲜度窗口扫描 PostgreSQL，通过
  `INSERT ... ON CONFLICT DO UPDATE ... WHERE state IS DISTINCT` 原子推进状态。多个
  Control Plane 同时扫描时只有一个实例能提交转换；稳定状态不写事件，也不会把每次五秒
  Heartbeat 扩散为事件洪流；
- Fresh/Stale 转换复用 V070 的 `workspace_overview_events` 全局 `BROWSER_NODE` 游标，
  继续使用已有可续传 SSE、`Last-Event-ID`、Reset、心跳、连接上限和跨实例发布机制；
- Workspace Overview 的 Browser Node 可见性与 `/browser-nodes` 既有 RBAC 对齐：
  `TENANT_ADMIN / SECURITY_ADMIN / PLATFORM_ADMIN` 可接收 Payload-free Node 变更，
  Viewer/Operator 仍看不到平台容量；
- Web/Tauri 共用 Nodes 查询删除五秒 `refetchInterval`。`BROWSER_NODE` 事件只使
  `browser-nodes` Query 失效并重新读取正式 API；断线或重连时明确提示数据可能过期，
  不在前端伪造心跳或 Node 状态；
- Workspace Overview 与 Nodes 对心跳新鲜度统一使用 60 秒窗口，和 Placement 的
  `NODE_HEARTBEAT_TTL` 保持一致，避免两个页面对同一 Node 给出相互矛盾的健康结论。

## 验证

```text
Java 全量测试：通过
Web：24 files / 110 tests，通过
Web Lint / Format / Production Build：通过
Contract / N/N-1 Gate：通过
完整 Integration：browser_node_freshness_sse=true
完整 Integration：public_tables=112、audit_chain_valid=true
```

完整集成在真实 PostgreSQL 中将投影置为 `STALE`，由定时 Projector 原子恢复为
`FRESH` 并证明事件计数只前进；随后以 `TENANT_ADMIN + Last-Event-ID` 重连续传，收到
`changeType=BROWSER_NODE` 且 `replayed=true`。同套测试继续通过 AUTO Resource ACK、
迟到对账、双 Node 迁移、Coordinator Failover、Profile、Recording 与审计链。

## 尚未完成

1. Enterprise Operations Overview 仍以 15 秒轮询读取多个治理域，尚无覆盖全部变更源的
   单调游标；不能用高信号 Notification 或 Security-only Audit 流冒充全量事件源；
2. Browser Node 事件的大规模慢客户端、Ingress 缓冲、跨 Region Event Bus 和目标 Linux
   多 Node 断连/恢复长稳仍属于生产环境 Gate；
3. Apple/Microsoft 签名、真实 IdP、目标云 Pager/KMS/IAM 等外部环境与组织 Gate 未改变。
