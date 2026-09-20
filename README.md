# Agent Browser Cloud

以受控 Chromium Runtime 为核心的浏览器基础设施平台。

> Phase 4 MVP、Phase 6 本机容量/N/N−1 和 Phase 7 企业运营核心已有仓库内验收证据，
> 但尚未通过 V16 全量生产 Gate，不能直接处理真实客户数据。功能边界与验证结果以
> [进度总览](docs/08-进度追踪.md)、[剩余清单](docs/progress/33-当前未实现清单.md)
> 和当前提交的 CI 为准。默认 Compose 是 localhost 开发环境，禁止直接反代公网；
> 请先阅读 [安全策略](SECURITY.md)。

## 快速开始

### 环境要求

- Java 21+
- Rust 1.86+
- Node.js 20+
- pnpm 8+
- Docker 24+
- Docker Compose 2.20+

macOS 本地 Docker Gate 统一使用 OrbStack；运行前确认 `orbctl status` 为 `Running`、
`docker context show` 为 `orbstack`，且 `docker info` 的 Operating System 为 `OrbStack`。
不得用 Docker Desktop 或 `desktop-linux` context 代替本仓库的本机验收。

首次运行先安装前端依赖：

```bash
make install
```

### 一键启动

```bash
# 克隆仓库
git clone https://github.com/sshiong/agent-browser-cloud.git
cd agent-browser-cloud

# 配置真实模型。Key 文件必须是绝对路径、非符号链接且权限为 0600/0400。
export LOCAL_AGENT_MODEL_API_KEY_FILE=/absolute/path/to/model-api-key
export LOCAL_AGENT_MODEL_ENDPOINT=https://your-model-provider.example/v1/responses
export LOCAL_AGENT_MODEL_NAME=your-reviewed-model-deployment
export LOCAL_AGENT_MODEL_REVISION=your-pinned-model-revision

# 启动本地开发服务及真实 Agent/Reviewer/Outcome/Vision Worker 进程
make compose-up

# 验证服务
curl http://localhost:8080/actuator/health
open http://localhost:3000
# 企业运营工作台
open http://localhost:3000/enterprise
```

`make compose-up` 会先拒绝缺失、宽权限或非 HTTPS 的模型配置，再构建并等待四个 Worker 首次成功
访问各自的权威长轮询队列。模型 Key 只经受限文件卷提供给 Worker，不进入其环境变量；不会使用
fixture 冒充真实模型。配置模板见
[deploy/docker/local-agent.env.example](deploy/docker/local-agent.env.example)。只需要数据库和 Redis
时仍可执行 `docker compose up -d postgres redis`。

真实浏览器登录与交互 Challenge 可单独复验：

```bash
# 真实 Chrome：正确密码、错误密码和假成功拒绝；每个 case 使用独立 Profile。
make test-real-login-agent

# 使用上方显式配置的真实 HTTPS Responses Provider 重跑同一 Outcome Gate。
make test-real-login-agent-provider

# Cloudflare 官方 forced-interactive 测试 sitekey；真实 headed Chrome 鼠标点击，不访问生产站点。
make test-turnstile-interactive
```

`test-real-login-agent-provider` 和默认 Compose 一样缺少私有 Key 文件时 fail-closed。测试不会读取或
复用 Codex/浏览器账户凭据。Turnstile Gate 只验证 Cloudflare 官方测试 widget 的真实交互，不代表
绕过生产 CAPTCHA；跨域 iframe 仍按 Opaque Frame 边界处理。

默认 Compose 仅限本机开发。需要单机个人部署时，使用独立的
[Personal Secure 部署层](deploy/personal-secure/)；它强制 OIDC、随机文件型 Secret、内部 mTLS、
受控代理出口、HTTPS 对象存储和完整 Agent/Reviewer/Outcome/Vision Worker 链，并且只绑定
loopback。它不是公网反代模板；远程访问应使用 SSH 本地端口转发或经过评审的私有网络。

### 分步启动

```bash
# 1. 启动基础设施
docker compose up -d postgres redis

# 2. 运行数据库迁移
make migrate

# 3. 启动 Control Plane（仅本地显式允许直连）
PROXY_ALLOW_DIRECT=true ./gradlew -p apps/control-plane bootRun

# 4. 先启动 Storage Helper
mkdir -p /tmp/browsercloud-helpers
STORAGE_HELPER_SOCKET=/tmp/browsercloud-helpers/storage.sock \
PROFILE_STORAGE_ROOT=/tmp/browsercloud-profile-storage \
  cargo run --manifest-path apps/browser-node/Cargo.toml --bin storage-helper &

# 5. 启动 Browser Node（仅本地显式允许直连）
ALLOW_DIRECT_NETWORK=true \
STORAGE_HELPER_SOCKET=/tmp/browsercloud-helpers/storage.sock \
PROFILE_STORAGE_ROOT=/tmp/browsercloud-profile-storage \
  cargo run --manifest-path apps/browser-node/Cargo.toml --bin node-agent

# 6. 启动 Web Console
cd apps/web-console && pnpm dev
```

## 项目结构

模块表由 Git 跟踪文件生成，新增/删除模块后先暂存文件，再执行 `make docs-generate`。
CI 的 `make docs-check` 会拒绝模块表漂移和 README 本地链接失效。

<!-- BEGIN GENERATED MODULES -->

| 目录 | Git 跟踪的模块 |
| --- | --- |
| `apps/` | [agent-worker](apps/agent-worker/)、[application-adapter](apps/application-adapter/)、[browser-node](apps/browser-node/)、[control-plane](apps/control-plane/)、[desktop](apps/desktop/)、[gameday-worker](apps/gameday-worker/)、[validation-worker](apps/validation-worker/)、[web-console](apps/web-console/) |
| `packages/` | [contracts](packages/contracts/) |
| `sdks/` | [go](sdks/go/)、[java](sdks/java/)、[python](sdks/python/)、[typescript](sdks/typescript/) |
| `database/` | [migrations](database/migrations/)、[online-migrations](database/online-migrations/)、[seeds](database/seeds/) |
| `deploy/` | [docker](deploy/docker/)、[kubernetes](deploy/kubernetes/)、[personal-secure](deploy/personal-secure/)、[terraform](deploy/terraform/) |
| `tools/` | [browser-session-operator](tools/browser-session-operator/)、[docs](tools/docs/)、[local-dev](tools/local-dev/)、[sdk](tools/sdk/)、[supply-chain](tools/supply-chain/) |

<!-- END GENERATED MODULES -->

`docs/` 保存架构与验证证据，`tests/` 保存跨组件 Gate，`Makefile` 是统一检查入口，
`docker-compose.yml` 为开发编排。当前默认 Compose 尚未完整启动 Agent/Reviewer/Vision
三个独立 Worker，不能用进程内执行的成功代替外部 Worker 验收；专项修复见
[A01—A24 实施账本](docs/progress/165-Agent可靠性与个人安全部署修复清单.md)。

## 文档

- [MVP 需求说明](docs/01-MVP需求说明.md)
- [正式 API/消息契约](docs/02-正式API消息契约.md)
- [数据库详细设计](docs/03-数据库详细设计.md)
- [任务拆分与排期](docs/04-任务拆分与排期.md)
- [测试计划与用例](docs/05-测试计划与用例.md)
- [本地开发指南](docs/06-本地开发指南.md)
- [工程规范](docs/07-工程规范.md)
- [进度追踪](docs/08-进度追踪.md)
- [合规审计与整改记录](docs/09-合规审计与整改记录.md)
- [架构与实施大纲](docs/outline/)

## 开发

```bash
# 构建所有组件
make build

# 运行所有测试
make test

# 代码检查
make lint

# 格式化代码
make fmt

# 生成 Contract
make contracts

# 契约校验与真实进程烟雾测试
make contracts-check
make test-integration
make test-e2e
make test-real-url-agent
make test-sdk

# 真实 Chromium 500 次生命周期容量证书
make test-browser-runtime-capacity \
  REAL_CHROMIUM_PATH="/absolute/path/to/chromium" \
  RUNTIME_CAPACITY_CYCLES=500

# Operator 单测；集群 E2E 需本机 Docker、kubectl 和 Kind
make test-kubernetes-operator
KIND_BIN=/path/to/kind make test-kubernetes-e2e
make test-upgrade-compatibility
```

## API 文档

启动 Control Plane 后访问：

- Swagger UI: http://localhost:8080/swagger-ui.html
- OpenAPI: http://localhost:8080/v3/api-docs

本地列表/详情 API 还需携带开发租户保护头：

```bash
curl -H 'X-Tenant-Id: tenant-local' \
  http://localhost:8080/api/v1/sessions
```

`X-Tenant-Id` 只用于本地防止误读，不构成认证。生产环境必须启用 Bearer 身份并由已认证 Principal 派生 Tenant。

## 技术栈

| 层级         | 技术选型              |
| ------------ | --------------------- |
| 控制面       | Java 21 + Spring Boot |
| Browser Node | Rust + Tokio          |
| Web Console  | React + TypeScript    |
| 数据库       | PostgreSQL 17         |
| 缓存         | Redis 7               |
| 内部协议     | Protobuf              |
| 外部 API     | OpenAPI 3.1           |
