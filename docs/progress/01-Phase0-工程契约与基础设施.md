# Phase 0：工程、契约与基础设施

> 状态：技术项基本完成；Owner 与设计评审签字属于组织 Gate，仓库不能代签。

## 已完成

- Gradle 8.9 Wrapper、Java 21 Toolchain、Cargo/Pnpm 锁文件和统一 Makefile。
- Protobuf 四个源文件，可由 Buf 生成 Java、Rust、TypeScript 代码。
- OpenAPI 3.1 与 Error Envelope JSON Schema 可校验。
- GitHub Actions 覆盖格式、静态检查、单元测试、Contract 检查、构建和集成烟雾测试。
- PostgreSQL 17 全新库可由 Flyway 10.22.0 创建，并通过 Hibernate Schema Validation。
- PostgreSQL/Redis、Control Plane、Browser Node、Web Console 均有 Dockerfile；Compose 不向宿主暴露 CDP/VNC。
- ADR-001—ADR-006 和开发 Seed 已存在。
- Web Console 已恢复 ESLint、Prettier、Vitest、Playwright 依赖与 CSP/Referrer 基线。

## 已验收命令

```bash
make ci
make build
make contracts
make test-integration
docker compose config
```

## 尚未完成

- Owner、威胁模型与设计评审的组织签字。
- SBOM、镜像签名、镜像扫描与固定 Digest。
- 正式 Release 流程、版本兼容策略自动验证和生产回滚演练。
- 容器镜像构建仍可能受本机 Docker Registry Mirror 网络状态影响。
