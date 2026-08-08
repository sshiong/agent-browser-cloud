# Proxy 冷 Binding 探测与分布式租约闭环

> 完成日期：2026-08-08
> 状态：仓库内 PostgreSQL、多 Control Plane、mTLS Browser Node 与隔离 Network Helper
> 真实闭环；目标云 Secret Manager 和商业 Provider 仍待接入

## 本轮结论

进度 99 已让运行中 Session 的 Proxy Binding 持续产生真实出口健康样本，但一个已创建、
暂时没有运行 Session 的 Binding 不会被 Browser Node 观察。本轮关闭这个“冷 Binding”
缺口：Control Plane 不自行访问外网，也不读取 Secret；它以 PostgreSQL 租约选择到期
Binding，将不透明 Provider 描述经 mTLS 发送给具备能力的新 Browser Node，由隔离
Network Helper 对本机已配置 Route 做一次真实出口探测。

## 已完成

### 1. V072 PostgreSQL 权威调度

- `next_cold_probe_at` 持久化下次探测时间；成功默认 15 分钟、Provider 失败默认 2 分钟、
  无可用 Node 默认 30 秒后重试，均可在安全范围内配置。
- `cold_probe_lease_owner / cold_probe_lease_until` 与 `FOR UPDATE SKIP LOCKED` 保证多个
  Control Plane 副本只会 Claim 一次；单批最多 64，默认并发 8。
- 只选择 `enabled` 且不存在 `ALLOCATED / BOUND` Proxy Allocation 的 Binding，不与
  运行中 Session 的 30 秒主动探测重复。
- Claim 固化 Binding `version`；完成时再次行锁校验 Version、Lease 和有效期。配置修改
  Trigger 会立即撤销旧 Lease 并重排新探测，慢返回不能写入新配置。

### 2. 能力化 Browser Node RPC

- Proto 新增 `ProbeProxyBinding` mTLS 内部 RPC；请求只包含 Probe/Tenant/Binding ID、
  Provider ID、预期出口和不透明 `credential_ref`，响应只包含 Node、成功、延迟、出口和
  有界错误码。
- 只有配置了 Network Helper 的 Node 才声明
  `proxyColdProbe=network-helper-v1`；Control Plane 候选查询同时要求 Ready、Admission
  Open、Pressure Normal 和 45 秒内新鲜心跳，N−1 Node 不会收到未知 RPC。
- Node 以默认 4、最大 32 的信号量限制冷探测并发；Network Helper 建立唯一临时绑定、
  验证真实出口并在成功或失败后释放。Helper 不可用时 fail-closed，不允许回退直连。
- Node 不记录或返回原始 Helper 错误，只使用 `TIMEOUT / CIRCUIT_OPEN / EXIT_MISMATCH /
  HELPER_UNAVAILABLE / PROBE_FAILED`。

### 3. 统一质量账本

- V072 扩展 V071 样本来源为 `COLD_BINDING_PROBE`；冷样本明确要求 Allocation/Session
  为空，运行中样本仍必须同时绑定 Allocation 和 Session，数据库约束防止混淆来源。
- 冷探测与运行中探测共用 3 次失败降级、2 次成功恢复、成功率 EWMA、延迟 EWMA、质量
  分和 Workspace SSE 合并失效逻辑；UI 无需制造第二套状态。
- 健康写仍不推进管理员 JPA CAS Version；配置写和探测写通过 Revision/Lease 栅栏解耦。

## 验收证据

- Java：全量测试及本地 gRPC Gateway 成功、错误 Node 身份、无能力 Node fail-closed；
- Rust：全 Workspace 测试、格式检查、Node Agent 编译和有界错误归类；
- Contract：Buf/OpenAPI、生成 Java/Rust/TypeScript、V072/新 RPC N−1 Capability Gate；
- Integration：PostgreSQL 17 V001—V072、Redis、MinIO、双 Control Plane、三 Browser
  Node、mTLS 与隔离 Network/Storage Helper 全链通过；在创建任何 Session 前已验证真实
  `COLD_BINDING_PROBE` 成功样本，随后运行中 `ACTIVE_EXIT_PROBE` 和迁移链继续通过。

## 当前仍未完成

1. Vault/AWS Secrets Manager/Azure Key Vault/GCP Secret Manager 的短期解引用、轮换、
   撤销与审计；
2. 商业 Proxy Provider Adapter 和真实 DNS、认证、限流、供应商故障矩阵；
3. Provider 质量、流量成本、信誉与区域可用性的联合路由；
4. 目标 Linux/CNI 防直连逃逸、长期压力与真实 Provider GameDay 证书。
