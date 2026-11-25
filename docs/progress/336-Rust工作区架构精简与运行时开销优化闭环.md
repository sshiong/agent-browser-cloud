# Rust 工作区架构精简、构建加速与运行时开销优化闭环

> 完成日期：2026-11-25
> 验收环境：macOS OrbStack (`orbstack`, `Server=29.8.2`) + 真实 Chromium 156 容器

## 1. 重构与优化项（功能与稳定性 100% 不变）

| 编号 | 优化项 | 关键改动路径 | 效果 |
| --- | --- | --- | --- |
| **S1** | `[profile.release]` 优化 + Dockerfile 三阶段编译拆分 | `apps/browser-node/Cargo.toml`、`apps/browser-node/Dockerfile` | 启用 `opt-level = "s"`、`lto = "thin"`、`codegen-units = 1`、`strip = "symbols"`（保留 `unwind` 确保 Tokio 任务级容错）；将单 `build` 阶段拆为 `build-lite` / `build-services` / `build-node`，构建 `lite` 与轻服务不再编译 AWS S3 SDK / Tonic / LevelDB / `node-agent`，`build-lite` 耗时由 **342 s → 134 s（2.55× 加速）** |
| **S2** | 消除热路径 `reqwest::Client` 与全量 `sysinfo::System` 反复分配 | `crates/state-collector/src/{lib,dialog_monitor,safety_monitor}.rs`、`crates/runtime-supervisor/src/lib.rs`、`crates/input-sandbox/src/lib.rs`、`crates/lite-server/src/{runtime/mod.rs,bin/ctl.rs}` | CDP 发现/健康探测热路径由“每次请求重建 `reqwest::Client`（重复加载 TLS 根证书与连接池）”改为 `OnceLock<reqwest::Client>` 复用；`sysinfo::System::new()` 全部替换为 `new_with_specifics(RefreshKind::nothing()...)` 仅按需刷新内存或目标 PID |
| **S3** | 消除 SQLite 行映射重复代码 | `crates/lite-server/src/store.rs` | 抽出 `Self::map_token` 与 `Self::map_checkpoint`，消除 5 处重复 Token 映射闭包与 2 处 Checkpoint 映射闭包 |
| **S4** | 拆分巨型 `lite-server/src/runtime.rs`（2,994 行） | `crates/lite-server/src/runtime/{mod,profiles,network_policy,actions}.rs` | 拆分为 4 个高内聚子模块：会话生命周期与调度（`mod.rs`）、账号级 Profile 与协作（`profiles.rs`）、单次/永久内网策略（`network_policy.rs`）、动作/观察/截图（`actions.rs`），公开接口零变更 |
| **S5** | 拆分 `lite-server/src/mcp.rs`（1,612 行） | `crates/lite-server/src/{rest,mcp}.rs` | REST/SSE/CDP 代理路由独立为 `rest.rs`（1,156 行），`mcp.rs` 收敛为纯 MCP JSON-RPC 2.0 协议与 10 工具目录（476 行） |
| **S6** | Rust 1.100 CI 兼容 + 异步非阻塞 Checkpoint + SQLite 缓存/索引 + 长稳防泄漏 + Egress 超时/并发限流 | `crates/recording-privacy-scanner/src/{lib,qr}.rs`、`crates/lite-server/src/{runtime/profiles,runtime/mod,store,egress}.rs` | `chunks_exact(3)` 迁移为 `as_chunks::<3>().0.iter()` 修复 GitHub Actions Rust 1.100 Clippy；Profile 压缩/解压移入 `tokio::task::spawn_blocking` 不阻塞异步执行器；SQLite 限制 2 MiB 页缓存 + 新增 4 条复合索引 + 热路径 `prepare_cached`；`session_locks` 关闭/维护自动回收且 `audit_events` 有界保留 10,000 行；`egress.rs` 新增 10 秒读头/建连超时、`TCP_NODELAY`、零分配头过滤与单会话 128 并发连接信号量 |

## 2. OrbStack 实机实测数据对比

| 目标镜像 / 二进制 | 优化前镜像体积 | 优化后镜像体积 | 优化后 Strip 二进制体积 | 实机验证结论 |
| --- | --- | --- | --- | --- |
| `browsercloud-mcp-gateway` | 162 MB | **157 MB** | **4.0 MB** | Docker `--healthcheck` 实测 `healthy`；实时 RSS **4.88 MiB** |
| `browsercloud-operator` | 162 MB | **157 MB** | **3.9 MB** | 镜像与全单测通过 |
| `browsercloud-lite` | 1.16 GB | **1.15 GB** | **8.0 MB** | Docker `--healthcheck` 实测 `healthy`；实时 RSS **8.19 MiB**；真实 Chromium 156 端到端通过 `once`（休眠自动失效）vs `permanent`（跨休眠自动持久化）内网授权回归（`LIVE_CHROMIUM_ONCE_VS_PERMANENT_PASS`） |

## 3. 全工作区门禁确认

- Rust 全工作区（20 Crates）：`cargo fmt --all -- --check`、`cargo test --locked --workspace`、`cargo clippy --locked --workspace --all-targets -- -D warnings` — 全部通过（0 warning）。
- Compose 门禁：`make test-default-compose` — 12/12 通过。
- 文档与模块表：`make docs-generate && make docs-check` — 7/7 + 中英文 README 校验通过。
- OrbStack 临时 `:opt` 容器与镜像已全部清理（`0` 残留）。
