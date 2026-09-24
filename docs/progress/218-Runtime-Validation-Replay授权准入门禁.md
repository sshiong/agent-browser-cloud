# Runtime Validation Replay 授权准入门禁

> 日期：2026-09-24
> 范围：隔离 Validation Worker 的固定 Chromium Replay Runner

## 问题

Runtime Validation 已有 PostgreSQL 权威 Job、固定 Worker 命令、版本/能力矩阵及持久结果，
但 `suites.json` 的每个 case URL 只校验 HTTP(S) 语法。部署时若误放入未经授权的客户页面
或把后续 case 指向 catalog 声明范围外的 Host，Runner 可能先执行前面的 case，才发现错误。
这与 V16 Replay Dataset 不得含未经授权生产数据的准入要求不一致。

## 实现

- 每个 Dataset 必须声明授权依据、`containsProductionData=false`、`personalData=false`、
  `credentials=false` 和最多 100 个精确 `allowedHosts`。声明缺失或 Host 通配/格式异常时拒绝。
- 在启动任何 case 之前预检全部 case：最多 1000 个、唯一且有界的 ID、明确布尔 `required`、
  至少一个必需 case、HTTP(S) URL 的 Host 必须在 Dataset 授权范围内，Userinfo、Fragment、
  无效端口和未声明的 Case Capability 均拒绝。生产仍默认只允许 HTTPS；本地 HTTP 仍须显式
  `--allow-http`。
- 不修改正式 API、数据库、Proto 或生成 SDK；旧 catalog 必须先补授权声明再滚动部署新 Worker。
  Kubernetes 既有固定命令、只读 Secret 挂载和 NetworkPolicy 仍是实际执行边界。

## 验证与边界

- `make test-validation-worker` 的 9 项通过，包含正常固定浏览器 case、未授权 Host、生产数据、
  凭据、重复 case、空必需集、无效端口和未声明能力拒绝；后续 case 越界或引用未声明能力时
  `run_case` 完全未调用。
- `make docs-check` 与 `make ci` 全量通过，覆盖 Java/Rust/Web、四类 Worker、契约、四语言
  SDK、供应链、Operator、N/N−1 与容量证书。GitHub CI 结果见后续记录。
- 后续补丁将未声明的 Case Capability 校验移至整份 Dataset 预检。定向 Worker 9 项、
  `make docs-check` 与 `git diff --check` 通过；本机再次执行 `make ci` 时，Java Protobuf
  生成被上游 `protoc-gen-grpc-java:1.62.2:osx-aarch_64` 实际为 x86_64 的二进制阻断，
  与这次 Python 改动无关。全量结论以该补丁的 Linux GitHub CI 为准。
- Catalog 自述不构成客户授权证明，且 Chromium 重定向仍需部署网络出口策略阻断；客户站点
  数据集、真实 IdP/OTP/支付 Replay、Canary 阈值与回滚证据仍未完成。
