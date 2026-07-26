# Phase 5：Runtime Ed25519 制品验签

> 状态：Production 准入的密码学验证器已完成；真实 Offline Root/Intermediate 签名流水线
> 和 Registry 制品下载校验仍待接入。

## 已完成

- V016 为 Runtime Build 增加不可变 `artifact_digest` 与 `signing_key_id`。
- Production 通过 `RUNTIME_SIGNING_PUBLIC_KEYS` 加载 Key ID 到 Ed25519 X.509 Public Key
  的受信映射，私钥不进入 Control Plane。
- 签名覆盖固定 Canonical Payload：`build_id|artifact_digest|sbom_url`。
- Runtime 启动与 Release Candidate Gate 会拒绝未知 Key、非法 Digest、被篡改的绑定、
  非法 Base64/Ed25519 签名以及非 HTTPS/OCI 的 SBOM Provenance。
- Runtime Registry API/UI 显示 Artifact Digest 与 Signing Key ID；Production 的
  `signatureVerified` 来自真实 Ed25519 验证结果，不再只是字符串存在性。

## 验证

Java 单元测试使用运行时生成的 Ed25519 Key Pair 覆盖：

1. 受信 Key 对精确 Artifact Digest 的有效签名通过；
2. 签名后篡改 Artifact Digest 返回 `RUNTIME_SIGNATURE_INVALID`；
3. 未知 Key ID 返回 `SIGNING_KEY_UNTRUSTED`。

本地 Seed 明确使用 `local-development` Key 与零 Digest，只供非生产集成测试；Production
不会接受该开发证据。

## 尚未完成

1. CI 还未从 Offline Root/Online Intermediate/短期 Release Signing Key 产生真实签名。
2. Control Plane 尚未下载 OCI Artifact 重新计算内容 Digest。
3. HSM/KMS 签名、Key Compromise Release Block 与真实回滚演练仍待完成。
