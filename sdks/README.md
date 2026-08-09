# Agent Browser Cloud SDKs

当前提供四个无框架锁定的客户端：

- `typescript`：从正式 OpenAPI 自动生成 166 个 Operation/32 个服务的 Fetch Client，
  支持浏览器或 Node.js 18+、Bearer/OIDC、本地租户身份、独立多 Client 配置和完整
  API 类型；原少量便捷方法继续兼容。
- `python`：Python 3.10+，从正式 OpenAPI 生成 166 个 Operation 和 224 个原生类型，
  只使用标准库，保留后端结构化错误与 Request ID。
- `go`：Go 1.22+，从正式 OpenAPI 生成完整方法和原生 Struct/Enum；只使用标准库，
  支持 Context、注入 HTTP Client、租户身份、幂等写和结构化错误。
- `java`：Java 17+，从正式 OpenAPI 生成完整方法和 Record/Enum；只使用 JDK
  HttpClient，支持可注入 Transport、租户身份、幂等写和结构化错误。

四者都不会自动绕过 Capability、Domain Allowlist、RBAC 或高风险确认。生产调用必须
使用短期 OIDC Access Token；`X-Tenant-Id`/`X-Actor-Id` 只用于显式本地开发模式。
生成客户端只接受当前 Operation 在 OpenAPI 声明的非身份 Header；调用方不能覆盖
`Authorization`、`X-Tenant-Id` 或 `X-Actor-Id`，必填请求体也会在发出网络请求前校验。

## 生成与发布

`session-api.yaml` 是四语言唯一权威来源。Python/Go/Java 使用仓库内确定性生成器，
TypeScript 使用固定版本生成器；两个 Manifest 绑定契约和逐文件 SHA-256。正式版本统一
读取 `sdks/VERSION`，标签格式为 `sdk-v<version>`。

```bash
make sdk-typescript-check
make sdk-multilang-check
make test-sdk
make build-sdk-release
```

`build-sdk-release` 生成 TypeScript tgz、Python wheel、Go module proxy 三件套、Java
JAR/source JAR/POM、`SHA256SUMS` 和发布 Manifest。推送精确版本标签后，GitHub Actions
重新生成、测试并以 GitHub OIDC 生成 Provenance Attestation，再创建不可变 GitHub
Release。npm/PyPI/Maven Central 等组织 Registry 镜像需要各组织单独开通 Trusted
Publishing，不由客户端伪造发布结果。

验收：

```bash
PYTHONPATH=sdks/python python3 -m unittest discover -s sdks/python/tests
pnpm --dir sdks/typescript install --frozen-lockfile
pnpm --dir sdks/typescript run generate
pnpm --dir sdks/typescript test
pnpm --dir sdks/typescript build
node tools/sdk/verify_typescript_package.mjs sdks/typescript
bash tests/sdk/typescript-package.sh
cd sdks/go && go test ./...
javac --release 17 -d sdks/java/build/classes $(find sdks/java/src -name '*.java' -print)
java -cp sdks/java/build/classes io.browsercloud.sdk.BrowserCloudClientTest
java -cp sdks/java/build/classes io.browsercloud.sdk.generated.BrowserCloudGeneratedClientTest
```
