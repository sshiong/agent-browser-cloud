# OpenAPI 自动生成 TypeScript SDK 闭环

> 完成日期：2026-08-08
> 状态：仓库内生成、漂移门禁、运行时、类型构建和发布包验收已完成；外部 Registry
> 签名发布仍属于发布组织 Gate

## 本轮结论

此前 `sdks/typescript` 只有少量手写方法和手写请求类型，OpenAPI 新增接口或修改字段时，
SDK 不会自动变化，也没有证据证明 SDK 覆盖正式契约。现在权威来源固定为
`packages/contracts/openapi/session-api.yaml`，生成结果覆盖全部 158 个唯一
`operationId`、32 个服务和 230 个 Schema Model。

## 已完成

1. `openapi-typescript-codegen@0.31.0` 固定在 SDK 自己的 lockfile，不借用 Web Console
   依赖；`pnpm --dir sdks/typescript run generate` 可从空依赖环境重复生成。
2. 生成 `BrowserCloudGeneratedClient` 实例客户端，每个实例拥有独立 Transport 与服务
   对象，避免全局 `OpenAPI` 配置造成多租户 Token/Base URL 串扰。
3. 生成的 Fetch Client 覆盖 Session、AUTO Resource、Proxy、Group/Tag、Agent、Audit、
   Enterprise 等全部服务；原 `BrowserCloudClient` 保留为向后兼容外观，并直接复用生成的
   `CreateSessionRequest`、`OperationResponse` 和完整创建字段。
4. 生成后统一补齐相对 import 的 `.js` 扩展，并使用 TypeScript `NodeNext` 构建；真实
   Node ESM 动态导入验证通过，不依赖 Bundler 猜测扩展名。
5. `generated-manifest.json` 绑定 OpenAPI SHA-256、生成器精确版本、完整源文件集合和每个
   文件 SHA-256；漂移门禁同时核对 operationId 集合、生成服务、Node ESM import 和
   Manifest，避免新增未跟踪文件绕过普通 `git diff`。
6. SDK 具备独立 `pnpm-lock.yaml`、`prepack` 构建、受限发布元数据和 `./generated`
   子路径；打包验收确认只包含 `dist` 与 `package.json`，不夹带源码。
7. GitHub 主 CI 安装 SDK 独立锁定依赖，并将生成漂移门禁纳入 `make ci`。

## 可重复证据

```bash
make sdk-typescript-generate
python3 tools/sdk/verify_typescript_sdk.py \
  packages/contracts/openapi/session-api.yaml \
  sdks/typescript/src/generated \
  sdks/typescript/generated-manifest.json
pnpm --dir sdks/typescript test
pnpm --dir sdks/typescript build
node tools/sdk/verify_typescript_package.mjs sdks/typescript
bash tests/sdk/typescript-package.sh
make test-sdk
```

关键输出：

```text
typescript_sdk_generated=true operations=158 service_methods=246 services=32
typescript_sdk_package=true esm=true isolated_clients=true
typescript_sdk_pack=true source_files=false
```

## 当前仍未完成

1. 将发布包实际推送到目标 npm Registry、OIDC Trusted Publishing、签名 Provenance、
   版本晋级和撤销流程；这需要目标组织 Registry 与发布审批权限。
2. Python、Go、Java SDK 仍是手写兼容客户端，尚未迁移到统一自动生成流水线。
3. 跨版本 SDK 兼容矩阵、公开 Release Notes 和客户升级演练仍待建立。
