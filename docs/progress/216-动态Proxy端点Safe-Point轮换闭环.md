# 动态 Proxy 端点 Safe Point 轮换闭环

> 日期：2026-09-24
> 范围：同 Binding 动态端点轮换、工作流幂等、旧端点围栏、Web 操作入口与真实 Integration

## 关闭的缺口

progress 215 已实现 `REMOTE_HTTP_V1` 六操作协议，但业务运行链只使用了动态分配与释放；
`rotate` 仍只存在于接口和 Adapter 单测中。控制面拒绝把 Session 重绑到当前 Binding，Web 也过滤
当前 Binding，因此运营方无法在 Challenge、出口退役或供应商风险事件后安全轮换同一产品的端点。

本次把轮换接入既有 Safe Point Proxy Rebind 工作流：

1. 只有 RUNNING/DEGRADED Session、无排他工作流且达到 Safe Point 时才能请求；
2. 目标等于当前 Binding 时，控制面先读取供应商能力，`rotation=false` 明确
   `PROVIDER_ROTATION_UNSUPPORTED`，不会退化为假重绑；
3. Browser 先正常 HIBERNATE、Flush Profile/Checkpoint，旧供应商 Endpoint 成功释放并持久化
   `RELEASED` 后才调用 `rotate`；
4. 请求同时携带稳定的 Workflow ID 幂等键和 `expectedPreviousEndpointId`。前者允许协调器崩溃后
   精确重放，后者阻止 Gateway 在供应商当前端点已经变化时误轮换其他资源；
5. Gateway 必须返回相同 previous Endpoint ID、不同的新 Endpoint ID，以及与 Catalog 精确一致的
   Provider/Credential Reference；Endpoint Host/IP/协议仍经过 progress 215 的严格校验；
6. 新 Endpoint 先保存为新的 `proxy_allocations` 权威记录，再写入 Session Context。恢复启动直接
   使用该预分配端点，避免再次 `allocate` 覆盖轮换结果；
7. Browser 恢复后仍必须经过真实出口验证、State Resync 和 Business Recovery Validation，失败不
   回退直连，也不把 Provider API 成功冒充为业务恢复成功；
8. Web/Tauri 共用的 Proxy Identity 面板允许选择当前 Binding，并明确标记“轮换当前端点（需
   Provider 支持）”。

不同 Binding 的原有重绑语义不变：旧端点释放后更新 Assignment，下一次启动按目标 Binding 正常
动态分配。固定 `CONFIGURED_HTTP` Provider 继续拒绝同 Binding 轮换。

## 验证

- `RemoteHttpProxyProviderAdapterTest` 验证轮换的 Workflow Idempotency-Key 与
  `expectedPreviousEndpointId`；
- `ConfiguredHttpProxyProviderAdapterTest` 验证固定端点继续以规范化错误拒绝轮换；
- `StaticProxyApplicationServiceTest` 使用真实 loopback Adapter Server 验证只有源 Allocation 已
  RELEASED 后才轮换，且新 Endpoint/出口 IP/Adapter 类型写入独立 Allocation 并绑定 Session；
- Control Plane Java 全量测试、Web 145 项与 ESLint 通过；
- OrbStack 完整 `make test-integration` 使用真实 PostgreSQL、Chromium、Network Helper 与动态
  Adapter Fixture，连续完成“跨 Binding 重绑”和“同 Binding 轮换”。数据库证明两个旧 Allocation
  已 RELEASED、唯一新 Allocation 已 BOUND、轮换 Workflow 已 COMPLETED；Fixture 证明仅一次
  rotate、Workflow 级幂等键和 Credential Reference-only 请求，输出
  `proxy_safe_endpoint_rotation=true`。

## 剩余边界

该闭环完成通用动态 Provider 的安全端点轮换运行链，不代表具体商业供应商已准入。目标供应商仍需
实现 Gateway 插件及真实账户 OAuth/签名、Secret Manager/Workload Identity、限流与故障 Replay、
用量/账单对账、Webhook 后主动复核、Region/Product 熔断和客户 SLA 验收。
