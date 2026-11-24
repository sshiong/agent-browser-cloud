# 纯 Rust 轻量化 L0 单容器与 L0 / L1 / L2 分档部署重构

2026-11-25

依据轻量化与分档部署重构方案并坚持向 Rust 靠拢的原则，新增零 Java / 零 PostgreSQL / 零 Redis / 零 Python / 零模型 Key 依赖的纯 Rust L0 Lite 核心服务与纯 Rust 分档控制 CLI，并将 Browser Node 拆分为四类多阶段构建目标与 L0 / L1 / L2 三档 Compose 拓扑。

## 变更内容

1. **纯 Rust L0 Lite 核心服务（`apps/browser-node/crates/lite-server`，二进制 `browsercloud-lite`）**
   - 直接复用现有 Rust `runtime-supervisor`、`state-collector`、`input-sandbox` 与 `rusqlite` / `axum` / `tar` / `zstd`，不新增任何非 Rust 运行时依赖。
   - **SQLite 多 Agent Token 认证与严格隔离（`store.rs` / `security.rs`）**：首次启动自动用 OS CSPRNG 生成 `0600` 高熵初始 Token（仅存 SHA-256 哈希），支持多 Agent 独立 Token 创建、轮换、撤销与 `max_sessions` 配额；不同 Agent 之间严格禁止跨访问彼此 Session/Profile；内置 SSRF 防护（拦截 `file://`、`chrome://`、`javascript:`、`data:`、云 Metadata 与默认内网/回环地址）与 Host/Origin 校验，绝不对外暴露原始 CDP 端口。
   - **原生 Rust MCP Streamable HTTP（`mcp.rs`，`/mcp` JSON-RPC 2.0）+ REST API（`/api/v1/lite/*`）**：收敛提供 10 个核心工具：
     - `browser_create`、`browser_list`、`browser_navigate`
     - `browser_observe`（支持 `tree` / `find` / `inspect` / `diff` 增量 DOM 快照，降低重复 Token 消耗）
     - `browser_act` 与 `browser_act_batch`（支持 1–20 步顺序执行、逐步耗时与错误定位、屏外 `OUTSIDE_VIEWPORT` 自动滚入视口、原生 `<select>` 与点击展开自定义下拉框、30s 思考防误松拖动）
     - `browser_wait`（支持 `element_ready` / `dom_quiet` / `state_stable`）
     - `browser_screenshot`（返回安全 PNG，不暴露宿主机路径或 CDP）
     - `browser_profile`（`save` / `restore` / `list`，通过 `profile.rs` 排除 `Cache`/`Code Cache`/`GPUCache` 等临时缓存，保留 Cookies / Local Storage / IndexedDB 并做 SHA-256 Manifest 原子校验恢复）
     - `browser_close`（支持 `hibernate: true` 关闭 Chromium 保留 Profile 并在下次请求自动唤醒，以及后台空闲超时自动休眠与崩溃检测）
   - **分级背压状态机（`backpressure.rs`）**：实现 `NORMAL → DEGRADED → QUEUE_ONLY → NO_NEW_SESSIONS → RECOVERING → CLOSED` 六态机（EWMA + P95 窗口、进出迟滞、连续采样确认与恢复冷却），防止瞬时 I/O PSI 毛刺导致误拒新会话。

2. **Browser Node 四目标多阶段镜像（`apps/browser-node/Dockerfile`）**
   - `lite`：`browsercloud-lite` + Chromium + `chromium-sandbox` + `tini`（L0 单容器纯 Rust 完整服务，无 Xvfb/x11vnc、无 OpenCV/Tesseract）。
   - `core`：`node-agent` + `network-helper` + `storage-helper` + Chromium（L1/L2 默认无头核心镜像）。
   - `desktop`：`core` + `xvfb` + `x11vnc`（按需人工协作远程桌面）。
   - `vision`（默认终态）：兼容原有全量镜像与 `ENABLE_REMOTE_DESKTOP`/`ENABLE_RECORDING_SCANNER` 构建参数。

3. **L0 / L1 / L2 分档 Compose 与纯 Rust CLI `browsercloud-ctl`**
   - `deploy/compose/lite.yml`：L0 Lite 单容器纯 Rust 部署（默认监听 `127.0.0.1:8130`，含 Chromium seccomp 与低内存开关）。
   - `deploy/compose/personal.yml`：L1 Personal 单机托管层（Control Plane `-Xmx384m` + PostgreSQL + Web Console + 默认 `core` 无头 Browser Node，默认不启 4 个模型 Worker）。
   - `deploy/compose/team.yml`：L2 Team 团队与多节点扩展层（Control Plane `-Xmx768m` + 默认 `core` 节点可按需切 `desktop`/`vision` + 可选 `workers` Profile）。
   - `apps/browser-node/crates/lite-server/src/bin/ctl.rs`（二进制 `browsercloud-ctl`）与 `Makefile` 新增 `lite-config`/`lite-build`/`lite-up`/`lite-down`/`lite-token`/`personal-up`/`team-up` 目标；`deploy/compose/README.md` 同步更新。

## 验证结果

- Rust 全工作区：`cargo fmt --all`、`cargo test --locked --workspace`（含 `lite-server` 7 项与 `browsercloud-ctl` 2 项新增单测/集成测试）、`cargo clippy --locked --workspace --all-targets -- -D warnings` 全通过。
- Compose 分档与模块化验证：`make test-default-compose` 全部 12 项通过（原有 6 项 + 模块化小内存 3 项 + 新增 `tests/compose/test_tiered_deployments.py` L0/L1/L2 3 项）。
- 文档与 README 目录表：`make docs-generate && make docs-check` 通过；根目录 `docker-compose.yml` 与 OpenAPI（273/368）/ 数据库迁移（V141）均保持不变。
- 仍待真实环境实测项：同硬件下 L0 Lite vs L1/L2 真实网页负载 A/B 内存/PSS/cgroup Benchmark、72 小时实机长稳与多节点跨机调度演练。
