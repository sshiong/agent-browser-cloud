# Phase 5：签名镜像与 Digest 发布链路

> 状态：GHCR Keyless 签名、SPDX Attestation、Digest 锁定发布包与可重复验证已完成；
> Offline Root/HSM、Admission 强制验证和生产回滚演练仍待完成。

## 已完成

- `.github/workflows/release-images.yml` 使用 GitHub OIDC 执行 Keyless Cosign 签名，
  私钥不进入仓库或 Runner Secret。
- Control Plane、Browser Node、Web Console 和 BrowserSession Operator 均：
  - 由精确 Source Commit 构建；
  - 推送到 GHCR；
  - 解析为 `repository@sha256:...`；
  - 生成 SPDX JSON SBOM；
  - 上传 Cosign Image Signature；
  - 上传 `spdxjson` Attestation。
- Release Manifest Schema v2 固化：
  - 40 位 Source Commit；
  - 四个镜像 Repository、Digest 和完整不可变引用；
  - 四份 SPDX SBOM 的路径、Media Type 和 SHA-256；
  - Production Kustomization 的 SHA-256。
- Manifest 使用 `cosign sign-blob --bundle` 签名；CI 使用证书 Identity、GitHub OIDC
  Issuer 和离线 Rekor Bundle 验证。
- CI 查询 GHCR 中四个镜像对应的 Signature/Attestation OCI 对象，拒绝缺失对象。
- Production Kustomize 渲染后逐项拒绝 Tag 或非法 Digest；当前六处工作负载镜像引用
  全部锁定到 64 位 SHA-256。
- 所有普通 CI 和 Release Workflow 第三方 Action 均固定到不可变 Commit SHA。
- Trivy 真实扫描发现并推动修复 `rustls-webpki 0.102.8` 高危 CRL Panic：
  Tonic/Prost 升级后解析到修复版 `rustls-webpki 0.103.13`。

## GitHub 验收证据

- Release Run：`30195955615`
- Source Commit：`2665fa0212b2a3a7978611e45734038d212e5597`
- Artifact：`production-release-2665fa0212b2a3a7978611e45734038d212e5597`
- 结果：所有 16 个业务步骤成功，包括构建、推送、SBOM、签名、Attestation、证据对象
  检查、签名 Manifest 验证、Kustomize 渲染和 Artifact 上传。

发布镜像：

| 组件          | 不可变 Digest                                                             |
| ------------- | ------------------------------------------------------------------------- |
| Control Plane | `sha256:9185e5e0d7719b7a07937ae96728f88c935ad47e231e95f31bc874c8e6f92704` |
| Browser Node  | `sha256:f667e055aa2e384c3c055fe19d4d57a69e5d9f0f53ab6245a7beb42d226bb82f` |
| Web Console   | `sha256:c9928e1aafbf4e927f6996354b20f4f755ab9716dda2af1e830fda13d2300326` |
| Operator      | `sha256:985654d2e8d7218bdd960180e444b544e1ac5176c9747c00c42ea87418b02482` |

下载 Artifact 后再次执行：

```bash
python3 tools/supply-chain/release_bundle.py verify --bundle <artifact-directory>
kubectl kustomize <artifact-directory>/production
```

两项均通过；Manifest 中四份 SPDX Evidence 的实际 Hash 与签名元数据一致。

## 防篡改测试

`tests/supply-chain/release_bundle_test.sh` 验证：

1. 四组件、四 SBOM 和完整 Source Commit 缺一不可；
2. `:latest` 或任意 Tag 不可进入 Production Bundle；
3. 修改 Manifest 内镜像 Digest 会被拒绝；
4. 修改签名清单绑定的任意 SBOM 会被拒绝；
5. 渲染出的六个工作负载镜像引用必须全部为 Digest。

## 尚未完成

1. Control Plane 的 Runtime Build Ed25519 Policy 与 OCI Cosign Evidence 尚未统一为同一个
   Registry Verification Provider。
2. 尚无 Offline Root/Online Intermediate/HSM 的长期生产密钥层级和泄露撤销演练。
3. 尚未通过 Kubernetes Admission Controller 在部署时强制验证 Identity、Issuer、
   Signature、Attestation 和受信 Release Manifest。
4. 尚未完成 N/N-1 兼容矩阵与真实生产回滚 GameDay。
5. GitHub Runner 报告部分上游 Action 使用 Node.js 20 Runtime；当前由 Runner 强制在
   Node.js 24 运行，后续需在上游发布新 Major 后重新固定 Commit SHA。

## Gate 判定

大纲中的“生成 SBOM、真实签名镜像、部署固定 Digest、签名发布证据可验证”切片已关闭。
Phase 5 完整 Exit Gate 仍受密钥层级、Admission、撤销/回滚、Helper LSM、Secure Debug
独立 Worker/录像和完整故障矩阵阻塞。
