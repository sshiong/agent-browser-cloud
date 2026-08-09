# Python、Go、Java OpenAPI 生成与 SDK 供应链发布门禁

> 完成日期：2026-08-08
> SDK 版本：0.1.0
> 状态：仓库内四语言生成、测试、确定性打包、OIDC Provenance 与 GitHub Release
> 流水线已完成；目标语言 Registry 镜像和组织发布审批仍是外部 Gate

## 本轮结论

此前 TypeScript 已覆盖正式 OpenAPI，但 Python、Go、Java 仍只有少量手写兼容方法，
无法证明新增接口会同步进入 SDK，也没有统一版本和可验证发布物。本轮将三者接入同一
权威 OpenAPI，保留旧 Client 兼容外观，同时生成全量 Client、原生模型、漂移清单和
跨语言发布包。

## 已完成

1. `tools/sdk/generate_multilang_sdks.py` 读取 Redocly 生成的 OpenAPI 3.1 JSON Bundle，
   确定性生成 Python、Go、Java 的 166 个唯一 Operation 和 224 个公开 Component
   Schema；不维护第二份接口描述或生产 Mock。
2. Python 生成标准库 Client 与 `TypedDict/Literal`，Go 生成标准库 Client 与
   Struct/Enum，Java 17 生成 JDK HttpClient Client 与 Record/Enum；原手写兼容 Client
   不删除，现有调用不被强制迁移。
3. 三语言统一校验绝对 HTTP(S) Base URL、必填 Path、Query 白名单和必填请求体；仅接受
   OpenAPI 声明的非身份 Header。调用方不能覆盖 `Authorization`、`X-Tenant-Id` 或
   `X-Actor-Id`，本地租户身份与 OIDC Bearer 边界保持明确。
4. `sdks/generated-multilang-manifest.json` 绑定 OpenAPI SHA-256、生成器版本、Operation/
   Schema 数量、完整生成文件集合和逐文件 SHA-256；验证器按语言核对方法与模型全集，
   生成漂移进入 `make ci`。
5. `sdks/VERSION` 成为四语言统一版本；发布构建生成 TypeScript tgz、Python wheel、
   Go module proxy zip/mod/info、Java JAR/source JAR/POM、`SHA256SUMS` 和带 Contract/
   Git Commit 的 Release Manifest。构建前清理精确输出目录，Java Class 目录也不复用，
   避免删除源码后残留旧 Class 混入制品。
6. Python、Go、Java 真实 Transport/HTTP 测试覆盖 URL、身份、结构化错误、Query 白名单、
   身份 Header 拒绝、必填 Body 和模型可用性；发布包检查拒绝测试、缓存和源码误打包。
7. `.github/workflows/sdk-release.yml` 只接受 `sdk-v<sdks/VERSION>` 精确标签，固定所有
   Action 与 Python/Go/Java/Node/pnpm 版本，重新生成和测试后以 GitHub OIDC 生成
   Build Provenance Attestation，并创建不可变 GitHub SDK Release。

## 可重复证据

```bash
make sdk-multilang-generate
python3 tools/sdk/verify_multilang_sdks.py \
  build/sdk/session-api.json \
  packages/contracts/openapi/session-api.yaml .
make test-sdk
make build-sdk-release
(cd build/sdk-release && sha256sum -c SHA256SUMS)
```

关键输出：

```text
multilang_sdk_generated=true operations=166 schemas=224
multilang_sdk_verified=true operations=166 schemas=224 languages=python,go,java
multilang_sdk_release=true version=0.1.0 artifacts=8
```

## 当前仍未完成

1. npm、PyPI、Maven Central 等目标组织 Registry 的命名空间、Trusted Publishing、
   维护者审批、撤销与客户升级策略；当前正式分发面是带 OIDC Provenance 的 GitHub
   Release，不能把尚未开通的第三方 Registry 描述为已发布。
2. 跨版本 N/N−1 SDK/API 兼容矩阵、公开 Release Notes 模板、弃用周期和客户升级演练。
3. 真实客户 Token、目标 Staging 与各语言主流框架的长稳/代理/超时矩阵；仓库测试只证明
   客户端契约和发布物本身，不替代生产组织 Gate。
