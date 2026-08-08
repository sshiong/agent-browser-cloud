# Agent Browser Cloud SDKs

当前提供四个无框架锁定的客户端：

- `typescript`：从正式 OpenAPI 自动生成 158 个 Operation/32 个服务的 Fetch Client，
  支持浏览器或 Node.js 18+、Bearer/OIDC、本地租户身份、独立多 Client 配置和完整
  API 类型；原少量便捷方法继续兼容。
- `python`：Python 3.10+，只使用标准库，保留后端结构化错误与 Request ID。
- `go`：Go 1.22+，只使用标准库，支持 Context、注入 HTTP Client、媒体资源请求、
  租户身份、幂等写和结构化错误。
- `java`：Java 21+，只使用 JDK HttpClient，支持可注入 Transport、媒体资源请求、
  租户身份、幂等写和结构化错误。

四者都不会自动绕过 Capability、Domain Allowlist、RBAC 或高风险确认。生产调用必须
使用短期 OIDC Access Token；`X-Tenant-Id`/`X-Actor-Id` 只用于显式本地开发模式。

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
javac -d sdks/java/build/classes $(find sdks/java/src -name '*.java' -print)
java -cp sdks/java/build/classes io.browsercloud.sdk.BrowserCloudClientTest
```
