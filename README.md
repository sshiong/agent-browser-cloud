# Agent Browser Cloud

以受控 Chromium Runtime 为核心的浏览器基础设施平台。

> 当前状态：单 Region 工程 PoC 的 Chromium/CDP、State/Input、noVNC、Profile/Proxy、
> Agent、Crash Recovery、OIDC/RBAC/mTLS、哈希审计、Break-glass 与最小化 Secure
> Debug 主链路已可重复运行；GHCR 四镜像已具备 Keyless Cosign 签名、SPDX Attestation、
> SBOM Hash 绑定和 Digest 锁定发布包。容量证书、真实集群、独立 Debug Worker/录像和
> Phase 7 企业运营 Gate 尚未完成，当前版本不能直接用于生产或真实客户数据。

## 快速开始

### 环境要求

- Java 21+
- Rust 1.86+
- Node.js 20+
- pnpm 8+
- Docker 24+
- Docker Compose 2.20+

首次运行先安装前端依赖：

```bash
make install
```

### 一键启动

```bash
# 克隆仓库
git clone https://github.com/sshiong/agent-browser-cloud.git
cd agent-browser-cloud

# 启动所有服务
make compose-up

# 验证服务
curl http://localhost:8080/actuator/health
open http://localhost:3000
```

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

```
agent-browser-cloud/
├── apps/
│   ├── control-plane/          # Java 控制面
│   ├── browser-node/           # Rust 浏览器节点
│   ├── web-console/            # React 控制台
│   └── cli/                    # Rust CLI
├── packages/
│   ├── contracts/              # Protobuf/OpenAPI 契约
│   ├── policy-schemas/         # 策略 Schema
│   ├── sdk-typescript/         # TypeScript SDK
│   └── test-fixtures/          # 测试数据
├── database/
│   ├── migrations/             # 数据库迁移
│   └── seeds/                  # 种子数据
├── deploy/                     # 部署配置
├── docs/                       # 文档
├── tests/                      # 测试
├── tools/                      # 工具
├── Makefile                    # 构建命令
└── docker-compose.yml          # 本地服务编排
```

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

# 真实 Chromium 500 次生命周期容量证书
make test-browser-runtime-capacity \
  REAL_CHROMIUM_PATH="/absolute/path/to/chromium" \
  RUNTIME_CAPACITY_CYCLES=500

# Operator 单测；集群 E2E 需本机 Docker、kubectl 和 Kind
make test-kubernetes-operator
KIND_BIN=/path/to/kind make test-kubernetes-e2e
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
