# Phase 5：可靠性、安全与审计

## 已完成并有测试证据

| 能力 | 实现 | 验收证据 |
|---|---|---|
| Durable Workflow Stage A | Workflow Record、Deadline、CAS Version、幂等、Stale Callback、Commit Marker、补偿、DLQ | 集成测试 4 个 Completed Workflow + 1 个故障注入 Dead Letter |
| OIDC/RBAC | 生产 JWT Principal 派生 Tenant/Actor；四级角色 | 单元测试与 401/403/跨租户集成测试 |
| Admin MFA | Admin JWT 必须包含 `amr=mfa` | `AdminMfaJwtValidatorTest` |
| 内部 mTLS | CP→Node、Node→CP 双向证书 | 临时 CA 全链路 + Node 证书轮换 |
| 审计 | 租户序列、Previous Hash、Event Hash、查询、脱敏、Retention/Legal Hold | 集成测试 `chainValid=true` |
| Runtime 供应链 | Stable、Validation、sha256 签名格式、SBOM Gate | Java 测试/集成启动；CI SBOM/Trivy |
| 安全治理 | Threat Model、Incident/Key Rotation Runbook | `docs/security/` |

## 尚未完成

1. Browser Node Helper 仍由 Node Agent 进程内链接，尚未完成独立 UID、固定 IPC、
   seccomp/Landlock 和崩溃隔离的 OS 级验收。
2. Break-glass 尚无双人审批、时间窗、撤销和完整 UI/API。
3. 故障矩阵尚缺 PostgreSQL 短时不可用、Object Storage 超时和 Coordinator 进程重启的
   自动 GameDay；现有覆盖包括 Chromium Kill、Node Kill/Restart、Redis 非权威、
   Proxy Circuit、Profile Corruption、Key-up Loss、DiffTruncated 和 Workflow DLQ。
4. Audit Retention 字段已落库，但删除 Receipt、签名导出 Manifest 和法规 Legal Hold
   工作流属于 Phase 7，尚未完成。
5. mTLS 已支持 CA 内证书轮换；在线信任根双写、CRL/SPIFFE 撤销尚未完成。

## Gate 判定

Phase 5 的应用层可靠性/身份/审计主链路已通过，生产 Exit Gate 仍因 OS 级 Helper
权限拆分、Break-glass 和完整故障演练保持“未关闭”。

