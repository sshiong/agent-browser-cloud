# Agent Browser Cloud 全运行时 Rust 化重构进度追踪

> 启动日期：2026-11-25
> 目标：在不破坏原有功能、OpenAPI/Protobuf 契约与现有 CI 测试的前提下，将剩余线上 Python 运行时组件逐一重构为纯 Rust，大幅降低镜像体积与常驻内存并提升易用性；每项完成后验证并原子提交 Git，全部完成后执行 OrbStack 实机验收。

## 总体进度总览

| 编号 | 模块 | 原实现（代码量） | 纯 Rust 目标模块 | 状态 | 验证与提交证据 |
| --- | --- | --- | --- | --- | --- |
| R1 | L1/L2 MCP Gateway（16 工具 + 双模式） | `apps/mcp-gateway/gateway.py` (297 行 Python) | `crates/mcp-gateway` (`browsercloud-mcp-gateway`) | **DONE** | `cargo test -p mcp-gateway` + clippy 0 warning + `mcp-gateway-rust.yml` |
| R2 | 录屏隐私脱敏扫描器（移除 OpenCV/NumPy/Python） | `apps/browser-node/scripts/recording_privacy_scanner.py` (334 行 Python) | `crates/recording-privacy-scanner` (`recording-privacy-scanner`) | **DONE** | `cargo test -p recording-privacy-scanner`（4/4）+ clippy 0 warning + `Dockerfile` 移除 Python/OpenCV/NumPy |
| R3 | 一站式纯 Rust CLI（`doctor`/`token`/`mcp-config`/`bench`） | `tools/local-dev/stack.sh` + `ctl.rs` | `crates/lite-server/src/bin/ctl.rs` (`browsercloud-ctl`) | **DONE** | `cargo test -p lite-server` + `browsercloud-ctl doctor` / `mcp-config` 实测 |
| R4 | 业务租约适配服务 Application Adapter | `apps/application-adapter/application_adapter.py` (582 行 Python) | `crates/application-adapter` (`browsercloud-application-adapter`) | **DONE** | `cargo test -p application-adapter` + clippy 0 warning + Docker target |
| R5 | Kubernetes BrowserSession Operator | `tools/browser-session-operator/browser_session_operator.py` (774 行 Python) | `crates/browser-session-operator` (`browsercloud-operator`) | **DONE** | `cargo test -p browser-session-operator` + clippy 0 warning + Docker target |
| R6 | GameDay 与 Validation 隔离 Worker/Runner | `apps/gameday-worker/` + `apps/validation-worker/` (1,771 行 Python) | `crates/ops-workers` (`browsercloud-gameday-worker`, `browsercloud-validation-worker`) | **IN_PROGRESS** | 进行中 |
| R7 | OrbStack 全量实机构建、实时内存/体积实测与全回归验收 | Docker (OrbStack) + Rust + Compose + Docs | 全工作区验收 | **PENDING** | 待开始 |

## 实机对比数据（R7 完成后填入真实 OrbStack 实测结果）

- `browsercloud-mcp-gateway` 镜像体积与空载 RSS：待实测
- `browser-node:vision`（去 Python/OpenCV/NumPy 后）镜像体积与 `--self-test`：待实测
- `browsercloud-application-adapter` 镜像体积与空载 RSS：待实测
- `browsercloud-operator` 镜像体积：待实测
- `browsercloud-ops-workers` 镜像体积：待实测
