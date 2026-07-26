# Phase 5：可靠性、安全与审计

## 已完成并有测试证据

| 能力                     | 实现                                                                                                                  | 验收证据                                                           |
| ------------------------ | --------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------ |
| Durable Workflow Stage A | Workflow Record、Deadline、CAS Version、幂等、Stale Callback、Commit Marker、补偿、DLQ                                | 集成测试 4 个 Completed Workflow + 1 个故障注入 Dead Letter        |
| OIDC/RBAC                | 生产 JWT Principal 派生 Tenant/Actor；四级角色                                                                        | 单元测试与 401/403/跨租户集成测试                                  |
| Admin MFA                | Admin JWT 必须包含 `amr=mfa`                                                                                          | `AdminMfaJwtValidatorTest`                                         |
| 内部 mTLS                | CP→Node、Node→CP 双向证书                                                                                             | 临时 CA 全链路 + Node 证书轮换                                     |
| 审计                     | 租户序列、Previous Hash、Event Hash、查询、脱敏、Retention/Legal Hold                                                 | 集成测试 `chainValid=true`                                         |
| Runtime 供应链           | Stable、Validation、Artifact Digest、Signing Key ID、Ed25519 验签、GHCR Keyless 签名、SPDX Attestation、Digest 发布包 | Java 有效/篡改/未知 Key；Trivy；Release Run `30195955615`          |
| 安全治理                 | Threat Model、Incident/Key Rotation Runbook                                                                           | `docs/security/`                                                   |
| Break-glass 治理         | 工单、双人审批、5—60 分钟、自动撤销、事后 Review、独立拒绝审计事务                                                    | Java 单测 + 集成 409/ACTIVE/404/REVOKED/REVIEWED + Web E2E         |
| Secure Debug 治理数据面  | 一次性 Grant、最长 15 分钟、单 Operator、最小 State 投影、逐次证据链、撤销关闭                                        | Java 单测 + PostgreSQL 证据链/404/409/撤销集成 + Web E2E           |
| Runtime Release 治理     | Platform Admin、双人晋级/禁用、发布状态、证据哈希、跨控制租户隔离                                                     | Java 单测 + 集成禁用/404/审计                                      |
| Key Rotation 治理        | 五类 Key Scope、双人审批、重叠窗口、泄露快速路径、验证证据                                                            | Java 单测 + mTLS 轮换集成 + Web E2E                                |
| 审计类型扩展             | Prompt Security、Human Authorization、Admin Access、Profile Restore                                                   | 集成审计链验证                                                     |
| Network Helper 隔离      | 独立进程、固定有界 Unix IPC、Peer UID、独立容器 UID/seccomp/Capability Drop                                           | Rust 边界单测 + Helper Kill/Fail-closed/独立恢复集成测试 + Web E2E |
| Storage Helper 隔离      | 独立进程、固定 IPC、路径重算、Write Epoch 幂等 Checkpoint、独立 Profile 卷                                            | Rust 完整性/路径/幂等测试 + Checkpoint Kill/恢复集成测试 + Web E2E |
| PostgreSQL 短时不可用    | 有界连接/Socket Timeout、稳定脱敏 503、写暂停、恢复后同幂等键继续                                                     | `make test-postgres-outage` 真实暂停/恢复 PostgreSQL 容器           |

## 尚未完成

1. Network 与 Storage Helper 已从 Node Agent 拆为独立进程，并完成固定 IPC、Peer UID、
   不同容器 UID、seccomp/Capability Drop、独立 Profile 卷和进程崩溃隔离；GPU Helper
   尚未实现，且 AppArmor/SELinux/Landlock 与真实集群跨 UID 验收待补。
2. Break-glass 与最小化 Secure Debug 数据面已完成；独立 Secure Debug Worker、
   像素级强制录像、WORM Recording Manifest 与真实集群故障演练尚未实现。
3. PostgreSQL 短时不可用 GameDay 已完成；故障矩阵尚缺 Object Storage 超时和
   Coordinator 进程重启/接管的自动 GameDay。现有覆盖包括 Chromium Kill、
   Node Kill/Restart、Redis 非权威、Proxy Circuit、Profile Corruption、Key-up Loss、
   DiffTruncated 和 Workflow DLQ。
4. 八类必需审计事件已全部接入，并由集成测试验证完整哈希链。
   Audit Retention 字段已落库，但删除 Receipt、签名导出 Manifest 和法规 Legal Hold
   工作流属于 Phase 7，尚未完成。
5. mTLS 已支持 CA 内证书轮换；在线信任根双写、CRL/SPIFFE 撤销尚未完成。

## Gate 判定

Phase 5 的应用层可靠性/身份/审计主链路已通过，Network Helper 进程隔离切片已通过；
生产 Exit Gate 仍因 GPU Helper、LSM/真实集群验收、独立 Secure Debug Worker/录像、
Offline Root/HSM、OCI Admission 强制验证、回滚演练和完整故障矩阵保持
“未关闭”。
