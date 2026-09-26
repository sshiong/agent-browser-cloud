# MinIO 测试镜像固定源码构建

> 日期：2026-09-26
> 范围：CI Integration、Object Storage GameDay、真实 Chrome Gate

## 触发证据

提交 `fa4f287` 的 GitHub `ci` run `36229204271` 两次在 Integration 启动前失败：runner 拉取
`quay.io/minio/minio:RELEASE.2025-04-22T22-12-26Z` 时收到 `unauthorized`。同一提交的
`desktop` run `36229204273` Windows/macOS 均成功；本地已有缓存的 MinIO 镜像使测试能通过，
因此缓存不能作为干净 runner 可重复性的证据。

## 实现

- 测试镜像引导脚本从 MinIO 官方 Go 模块的精确提交 `0d7408fc9969caf07de6a8c3a84f9fbb10a6739e`
  与 `mc` 的 `b00526b153a31b36767991a4f5ce2cced435ee8e` 构建 Linux 静态二进制；发布标签、
  Commit ID 和版本时间通过官方构建变量注入，容器启动时再次核验。
- Server 镜像以 `scratch` 为基础；`mc` 测试镜像以 Alpine 3.22 提供现有测试所需的 `/bin/sh`。
  只有本地缺少两张固定镜像时才构建；CI 在 Integration 前显式运行引导脚本。Integration、
  Object Storage GameDay 和真实 Chrome 测试也自行检查镜像，因此可在新机器独立运行。
- macOS 引导脚本先检查 OrbStack Running、Docker context `orbstack` 和 `OS=OrbStack`；
  根据 Docker daemon 架构选择 Linux amd64/arm64。测试脚本支持用环境变量指定隔离验证镜像，
  不覆盖已有缓存。

## 本地验证

- 固定源码在 OrbStack arm64 上构建，容器 `--version` 与预期标签、完整 Commit ID 一致；
  `mc` 镜像保留 `/bin/sh`，MinIO Server 健康检查通过。
- 用源码构建的两张隔离镜像运行 `make test-object-storage`，输出
  `OBJECT_STORAGE_GAMEDAY_OK`，涵盖 Multipart Resume、Cross-Region Restore、
  旧对象兼容及 COMPLIANCE WORM 删除拒绝。
- 同一镜像运行真实 Chrome 153 `make test-real-url-agent` 通过，Replay Dataset Digest
  `sha256:60c80eabf4a7ff23bcb825a29cafc57f7b6b9a55cb581c5048374aa023f16f93`，
  Validation Evidence Hash `965c7c2578b882c2e07c90f026c39bf71bcde2edb7609d3d9dbb1884e557b4cc`。
- 功能提交 `22d3740` 的 GitHub `ci` run `36231892787` 成功：干净 runner 完成固定源码镜像构建、
  Integration smoke test、Object Storage GameDay（输出 `OBJECT_STORAGE_GAMEDAY_OK`）和 Kubernetes
  Operator E2E。`desktop` run `36231892730` 的 Windows/macOS 均成功。

## 边界

这只恢复测试镜像的可重复获取，不更改生产对象存储选择或目标云 KMS/IAM、Legal Hold
和多 Region 发布 Gate。后续提交仍须分别核对对应 CI 结果。
