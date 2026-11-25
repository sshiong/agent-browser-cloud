# Progress 337 — L0 自动休眠防打断、旧库 Profile 迁移、按 Profile 串行锁、内网管理边界与 L1 网关/输入等待时序闭环

> 日期：2026-11-25
> 范围：`apps/browser-node/crates/{lite-server,mcp-gateway,node-agent,recording-privacy-scanner}`、`apps/mcp-gateway/`

## 1. 修复清单与根因闭环

| 优先级 | 问题 | 根因 | 本次落地修复 |
| --- | --- | --- | --- |
| **P0 · CI Trivy** | `recording-privacy-scanner/src/pii.rs:248` 单测样例命中 Trivy Secret 规则（Stripe Key 前缀） | 单测夹具使用 `sk_live_...` 字面量 | 改为合成非 Stripe 样例 `api_key=test_secret_1234567890`，保留全部 PII 覆盖率 |
| **P1 · L0 自动休眠打断操作** | Agent 等待 30s 期间约 18s 触发 `maintenance_tick` 休眠并返回 `422` | `maintenance_tick` 仅比较 `last_activity_at`，未追踪在途操作且 `wait` 循环未刷新活跃时间 | 新增 `in_flight_ops` 与 RAII `ActiveOpGuard`，覆盖 `navigate/observe/act/act_batch/wait/screenshot/profile_op/CDP WS 代理`；`wait` 上限放宽至 120s 并每 5s 刷新活跃时间；`maintenance_tick` 检查在途计数并以 `profile_lock → session_lock` 非阻塞 `try_lock` 复核 |
| **P1 · 旧 SQLite 升级缺失 Profile** | 旧库升级后老 Session 仍可见，但 Admin Profile 列表为空、打开返回 `404` | `LiteStore::open()` 仅建表与 `ALTER TABLE`，未回填历史 `profile_id` 及其归属授权 | 新增幂等 `backfill_legacy_profiles_and_grants`：将 `browser_sessions`/`profile_checkpoints` 中缺失的 `profile_id` 回填至 `profiles`（归属管理员并合并历史 `allowed_private_hosts`），仅对新回填 Profile 创建 `profile_grants`（后续管理员撤销跨重启永久生效） |
| **P1 · Profile 保存/恢复缺共享锁** | `save/restore` 仅锁 `session_lock`，停 Chromium 后临时释放占用状态，并发 `open_profile` 可同时读写目录 | 缺少按 Profile 维度的并发串行锁 | 新增 `profile_locks`（规范锁序 `profile_lock → session_lock → capacity_lock`，杜绝死锁并自动清理空闲锁），串联 `open_profile`/`create_session`/`restore_profile_by_id`/`profile_op`/`ensure_running`/`close_session`，不同 Profile 保持 100% 并行 |
| **P1 · L0 内网授权管理边界未闭合** | 普通 `sessions` Token 可自授持久内网权限；自定义 `proxy_url` 绕过 `EgressGuardHandle` | 缺少 Token 级预配置内网范围、`admin` 权限边界及代理链式出站守卫 | 1) `agent_tokens` 新增 `allowed_private_hosts_json`（支持发 Token 时一次性配置与 `PATCH /tokens/:id` 实时同步运行中会话）；2) 所有内网授权/修改/撤销接口（REST/MCP/`create_session`/`open_profile` 显式追加）强制 `admin` scope，非管理员返回 `403`；3) `egress.rs` 支持 `http://` 与 `socks5(h)://` 上游代理链式转发，所有自定义代理必须先经 `EgressGuardHandle` 校验云元数据与内网白名单，未授权返回 `403` 且绝不触达上游代理 |
| **P2 · L1 上游故障误报 `401` 与输入等待判定过短** | Control Plane 停机时 Gateway `/healthz` 仍报 `200`、有效 Token 返回 `401`；L1 真人输入空闲判定仅 2s 且等待窗口过短（默认 10s / 上限 25s） | `/identity` 所有异常折叠为 `401`；`/healthz` 未探活上游；`HUMAN_INPUT_PRIORITY_IDLE` 硬编码 2s | 1) `mcp-gateway`（Rust 与 Python 参考同步）引入类型化 `GatewayApiError`，连接失败/超时/上游 `5xx` 统一返回 `503 CONTROL_PLANE_UNAVAILABLE`（`retryable: true` + `Retry-After: 2`），仅上游真实 `401` 返回 `401`；`/healthz` 使用非池化短连接探活上游（停机即返回 `503 DEGRADED`），另提供 `/livez`；2) `node-agent` `HUMAN_INPUT_PRIORITY_IDLE_SECONDS` 默认由 `2s` 放长至 **`12s`**（可配 `2..=300s`），`INPUT_WATCHDOG_IDLE_TIMEOUT_SECONDS` 默认由 `30s` 放长至 **`120s`**（可配 `15..=600s`）；`browser_wait_operation` 与 `browser_wait_action_task` 默认放长至 `30s`、上限放宽至 **`120s`**，且在等待真人输入时明确返回 `waitingForHumanInput: true` 与 `executionWait` |

## 2. 验证证据

- **Rust 全工作区（20 Crates）**：`cargo fmt --all`、`cargo test --locked --workspace`（`lite-server` 19/19、`mcp-gateway` 2/2 等全部 0 failed）、`cargo clippy --locked --workspace --all-targets -- -D warnings` 全通过。
- **Compose 校验**：`make test-default-compose` 12/12 通过。
- **OrbStack 实机容器验收（`orbstack` context，`OS=OrbStack Server=29.8.2`）**：
  - 实构 `abc-verify-lite:337`（真实 Chromium + `chromium-seccomp.json`）与 `abc-verify-mcp:337` 并运行端到端验证，全部通过后清理临时容器与镜像：
    - 注入旧版 SQLite（无 `profiles`/`profile_grants` 表）启动新版 L0：Admin 列出 `prf_legacy01` 并成功 `POST /profiles/prf_legacy01/open` 启动真实 Chromium（HTTP `200`）。
    - `idle_timeout_seconds=30` 会话空闲 `16s` 后发起 `25s` `wait`（总跨度 `41s > 30s`）：完整等待 `25.1s` 后干净返回 HTTP `200`（`WAIT_TIMEOUT`），未被休眠打断。
    - Admin 发 Token 时一次性配置 `allowedPrivateHosts: ["10.99.0.0/16"]`：非管理员自授返回 `403 SCOPE_FORBIDDEN`，Agent 新建会话自动继承该范围直接运行。
    - L1 `browsercloud-mcp-gateway` 在模拟上游 Control Plane 不可达时：`/healthz` 返回 `503 DEGRADED (upstream=DOWN)`，携带有效 Bearer Token 调用 `/mcp` 返回 `503 CONTROL_PLANE_UNAVAILABLE (retryable=true)` 而非 `401`。
