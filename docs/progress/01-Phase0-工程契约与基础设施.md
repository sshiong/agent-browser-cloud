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
- GitHub Release Workflow 构建并推送四个 GHCR 镜像，生成 SPDX SBOM，执行 Keyless
  Cosign 签名/Attestation，并产出绑定 Source Commit、镜像 Digest、SBOM Hash 和
  Kubernetes Kustomization Hash 的签名发布包。

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
- N/N-1 版本兼容自动验证和生产回滚演练。
- Offline Root/HSM、签名密钥撤销以及 Admission 侧强制验证。
- 容器镜像构建仍可能受本机 Docker Registry Mirror 网络状态影响。
