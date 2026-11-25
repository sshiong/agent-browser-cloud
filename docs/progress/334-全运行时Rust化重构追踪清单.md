# Agent Browser Cloud 全运行时 Rust 化重构进度追踪

> 启动日期：2026-11-25
> 目标：在不破坏原有功能、OpenAPI/Protobuf 契约与现有 CI 测试的前提下，将剩余线上 Python 运行时组件逐一重构为纯 Rust，大幅降低镜像体积与常驻内存并提升易用性；每项完成后验证并原子提交 Git，全部完成后执行 OrbStack 实机验收。

## 总体进度总览

| 编号 | 模块 | 原实现（代码量） | 纯 Rust 目标模块 | 状态 | 验证与提交证据 |
| --- | --- | --- | --- | --- | --- |
| R1 | L1/L2 MCP Gateway（16 工具 + 双模式） | `apps/mcp-gateway/gateway.py` (297 行 Python) | `crates/mcp-gateway` (`browsercloud-mcp-gateway`) | **DONE** | `07e66757`；`cargo test -p mcp-gateway` + clippy 0 warning + `mcp-gateway-rust.yml` + OrbStack 实机镜像 **162 MB** / 空载 **2.395 MiB RSS**（`/healthz` UP、401/403 实测） |
| R2 | 录屏隐私脱敏扫描器（移除 OpenCV/NumPy/Python） | `apps/browser-node/scripts/recording_privacy_scanner.py` (334 行 Python) | `crates/recording-privacy-scanner` (`recording-privacy-scanner`) | **DONE** | `7f112a92` + 本次提交；纯 Rust Viola-Jones Haar Cascade + QR + PII/Luhn + Tesseract OCR；`browser-node:vision` **1.3 GB**、`NO_PYTHON`、`--self-test` 真实 `grace-hopper.jpg` + 合成 PII/QR 全通过 |
| R3 | 一站式纯 Rust CLI（`doctor`/`token`/`mcp-config`/`bench`） | `tools/local-dev/stack.sh` + `ctl.rs` | `crates/lite-server/src/bin/ctl.rs` (`browsercloud-ctl`) | **DONE** | `ad714a40`；`cargo test -p lite-server` + `browsercloud-ctl doctor` / `mcp-config` / `--rust-mcp` / `--rust-workers` 实测 |
| R4 | 业务租约适配服务 Application Adapter | `apps/application-adapter/application_adapter.py` (582 行 Python) | `crates/application-adapter` (`browsercloud-application-adapter`) | **DONE** | `3915a9bb`；`cargo test -p application-adapter` + clippy 0 warning + OrbStack `application-adapter` 镜像 **158 MB** + `--help` 实测 |
| R5 | Kubernetes BrowserSession Operator | `tools/browser-session-operator/browser_session_operator.py` (774 行 Python) | `crates/browser-session-operator` (`browsercloud-operator`) | **DONE** | `a8abb45e`；`cargo test -p browser-session-operator` + clippy 0 warning + OrbStack `browser-session-operator` 镜像 **162 MB** / 空载 **2.621 MiB RSS**（`/healthz` + 16 项 Prometheus `/metrics`） |
| R6 | GameDay 与 Validation 隔离 Worker/Runner | `apps/gameday-worker/` + `apps/validation-worker/` (1,771 行 Python) | `crates/ops-workers` (`browsercloud-gameday-worker`, `browsercloud-validation-worker`) | **DONE** | `869cb9d3`；`cargo test -p ops-workers` + clippy 0 warning + OrbStack `ops-workers` 镜像 **172 MB**（双二进制 `--help` 实测） |
| R7 | OrbStack 全量实机构建、实时内存/体积实测与全回归验收 | Docker (OrbStack) + Rust + Compose + Docs | 全工作区验收（20 crates） | **DONE** | 全 20 个 Rust Crate 测试 + `cargo fmt --check` + `cargo clippy -D warnings` + 5 组真实 OrbStack 容器镜像与空载 RSS 实测 + Compose 12 项 + `make docs-check` 全通过 |

## OrbStack 实机验收实测数据（2026-11-25，macOS OrbStack 29.8.2）

> 说明：所有容器均以非 root UID `65532:65532`、`--read-only`、`--cap-drop=ALL` 启动验证；测试结束后所有 `:accept` 临时容器与镜像均已清理。

| 组件 / 目标镜像 | 纯 Rust 镜像体积 | 空载常驻 RSS (`VmRSS`) | cgroup `memory.current` | 功能与安全验证结论 |
| --- | --- | --- | --- | --- |
| `browsercloud-mcp-gateway`（R1） | **162 MB** | **2.395 MiB** | 13.0 MiB | `/healthz` 返回 `UP`；未认证请求返回 HTTP `401`；非法 Origin 返回 HTTP `403`；16 工具 + `control-plane`/`lite` 双模式；相较 Python/Uvicorn（~65 MiB）常驻内存下降 **>96%** |
| `browser-node:vision`（R2） | **1.3 GB** | CLI 按帧执行 | — | `which python3` 返回 `NO_PYTHON`（彻底移除 `python3`/`python3-numpy`/`python3-opencv`）；`recording-privacy-scanner --self-test` 输出 `{"ready":true,"scanVersion":"tesseract-opencv-pii-face-qr-v2"}`，实测通过 `grace-hopper.jpg` 人脸定位遮罩零残留与合成 PII/QR OCR+视觉遮罩零残留 |
| `browsercloud-application-adapter`（R4） | **158 MB** | 短命 CLI / 服务端按需 | — | `attest` / `entity-identity` / `lease-acquire` / `lease-renew` / `lease-release`、`0600` 凭据权限校验、RFC 6901 JSON pointer、SHA-256/HMAC-SHA256 单元测试与镜像冒烟全通过 |
| `browsercloud-operator`（R5） | **162 MB** | **2.621 MiB** | 2.40 MiB | `/healthz` 与 16 项 `browsercloud_operator_*` Prometheus `/metrics` 实机存活；K8s Lease 选主、分页 LIST、NDJSON Watch（含 `410 Gone` 重列）、Finalizer 清理与 Control Plane 同步单测通过；相较 Python Operator（~60 MiB）常驻内存下降 **>95%** |
| `browsercloud-ops-workers`（R6） | **172 MB** | 双二进制合包 | — | 含 `browsercloud-gameday-worker` 与 `browsercloud-validation-worker`，覆盖固定绝对路径 Runner、1 MiB 有界输出/NDJSON、并发 Lease 心跳与取消/丢租强杀单测与镜像冒烟 |
| `browsercloud-ctl`（R3） | 复用 `lite-server` | 原生 CLI | — | `doctor` / `mcp-config --client claude\|cursor\|codex\|http` / `token list\|create\|rotate\|revoke` / `bench` / `--rust-mcp` / `--rust-workers` 全部验证 |

## 兼容性与未越界说明

- **零破坏性变更**：原 Python 服务文件、OpenAPI 契约（273 Operations / 368 Schemas）、Flyway 迁移（最新 V141）及既有 Python/Java/SDK/Compose CI 门禁全部保留，Rust 服务通过独立 Dockerfile target 与 Compose overlay（如 `deploy/compose/mcp-gateway-rust.yml`、`deploy/compose/workers-rust.yml`、`browsercloud-ctl --rust-mcp --rust-workers`）无缝启用；`browser-node:vision` 内部已切换至纯 Rust `recording-privacy-scanner` 且 100% 满足 `scanVersion == "tesseract-opencv-pii-face-qr-v2"` 契约。
- **零新增第三方依赖**：全工作区 20 个 Rust Crate 均复用已有锁定依赖，未引入任何新的外部语言或第三方 Crate。
- **仍属环境 Gate（不冒充通过）**：真实多节点 Kubernetes 集群下的 Operator 实网 Watch/Leader 切换压测、目标云环境 GameDay/Validation 端到端演练、72 小时长稳与 V16 生产发布组织签字仍归属目标环境/组织 Gate。
