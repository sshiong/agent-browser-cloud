# Agent Browser Cloud SDKs

当前提供四个无框架锁定的客户端：

- `typescript`：浏览器或 Node.js 18+，支持 Bearer/OIDC、本地租户身份、幂等 Session、
  Agent Task、成本解释和企业运营查询。
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
pnpm -C apps/web-console exec vitest run ../../sdks/typescript/test
pnpm -C apps/web-console exec tsc -p ../../sdks/typescript/tsconfig.json
cd sdks/go && go test ./...
javac -d sdks/java/build/classes $(find sdks/java/src -name '*.java' -print)
java -cp sdks/java/build/classes io.browsercloud.sdk.BrowserCloudClientTest
```
