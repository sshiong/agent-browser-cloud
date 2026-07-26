# Phase 5：Runtime Release 治理

> 状态：发布与紧急禁用的双人治理、准入状态和审计闭环已完成；Validation Farm 与真实
> 制品密码学验签仍属于未关闭项。

## 已完成

- V014 Runtime Release 数据模型：
  - `runtime_builds.release_channel` 明确区分 `UNRELEASED/CANARY/STABLE/DISABLED`；
  - 记录 `disabled_at`、`disabled_by`；
  - `runtime_release_requests` 保存目标通道、原因、申请/审批 Actor、决策时间和证据哈希；
  - 数据库约束禁止申请人自批，并限制同一 Build/通道只有一个 Pending 决策。
- 平台权限：
  - 新增独立 `PLATFORM_ADMIN`；
  - Production JWT Role 映射、本地测试身份和 Admin MFA Gate 均已接入；
  - Tenant Admin 与 Security Admin 不能执行平台级 Runtime 发布。
- API：
  - `POST /api/v1/runtime-builds/{buildId}:promote`；
  - `POST /api/v1/runtime-builds/{buildId}:disable`；
  - Runtime Release Request 的 List/Approve/Reject；
  - 跨控制租户返回 404。
- 发布 Gate：
  - 晋级前要求 Build 已验证，且签名/SBOM 元数据存在；
  - 第二位 Platform Admin 才能执行最终晋级或禁用；
  - 禁用后 Registry 立即进入 `DISABLED`，后续 Session 启动准入拒绝该 Build。
- 审计：
  - 申请、自批拒绝、批准和拒绝写入 `RUNTIME_RELEASE`；
  - 自批拒绝使用独立事务，业务拒绝不丢审计；
  - 批准证据使用 64 位 SHA-256 Hash 固化 Build、通道和双 Actor。

## 可重复证据

```bash
./gradlew -p apps/control-plane test
pnpm --dir apps/web-console lint
pnpm --dir apps/web-console test
pnpm --dir apps/web-console build
make contracts-check
make test-integration
make test-e2e
```

集成输出确认：

- `public_tables=20`；
- `runtime_release_dual_approval=true`；
- `runtime_release_cross_tenant=404`；
- `runtime_release_audit=true`；
- Runtime Registry 返回 `releaseChannel=DISABLED`、禁用 Actor 和时间；
- Platform Control Tenant 的哈希链包含 Requested、Self-approval Denied、Approved 三条
  `RUNTIME_RELEASE` 事件。

## 尚未完成

1. Runtime Build 的 Create/Validate、隔离 Worker、Capability Snapshot、回归矩阵和
   Performance/Security Gate 仍属于 Phase 7 Validation Farm。
2. Production Policy 已执行受信 Key ID 与 Ed25519 密码学验证；Offline Root/Online
   Intermediate 的真实签名流水线与 OCI 内容 Digest 复算仍未完成。
3. Web Console 当前只展示 Release Channel；Platform Admin 的发布审批工作区尚未接入。
4. `KEY_ROTATION` 已完成治理与审计闭环；外部 KMS/HSM 自动执行仍是生产化缺口。
