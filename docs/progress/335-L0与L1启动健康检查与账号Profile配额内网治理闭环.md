# L0/L1 默认启动、健康检查、账号级 Profile 授权、Token 配额与内网环境授权闭环

> 完成日期：2026-11-25
> 验收环境：macOS OrbStack (`orbstack`, `Server=29.8.2`) + 真实 Chromium 156 容器

## 1. 修复与新增能力总览

| 类别 | 问题 / 需求 | 实现与关键路径 | 实机验收结论 |
| --- | --- | --- | --- |
| **P0 · L1 默认启动** | `deploy/compose/low-memory.yml` 传入 `STATE_DIFF_MAX_BYTES=262144`，超出 `node-agent` 允许区间 `1024..=60000`；且 `personal.yml`/`team.yml` 默认 `core` 镜像继承了非空 `XVFB_PATH`/`X11VNC_PATH` | `low-memory.yml` 对齐为 `32768`；`personal.yml`/`team.yml` 默认清空 headless `XVFB_PATH`/`X11VNC_PATH`；`node-agent` 抽出 `validate_state_diff_limits` 单测；`tests/compose/test_tiered_deployments.py` 增加默认配置约束断言 | OrbStack `browser-node:core` 按 L1 默认环境变量全新启动成功，`Browser Node Agent gRPC server started address=0.0.0.0:9090` |
| **P1 · 两处健康检查** | `lite.yml` 使用 `/bin/sh` 执行 Bash 专有 `/dev/tcp`；`mcp-gateway-rust.yml` 继承了镜像中不存在的 `python` 探针，导致 HTTP 200 但容器被标为 `unhealthy` | `browsercloud-lite` 与 `browsercloud-mcp-gateway` 均内置零依赖纯 Rust `--healthcheck` 探针（直连回环 `/healthz`，校验 HTTP 200 与 `"status":"UP"`），并更新 `lite.yml` 与 `mcp-gateway-rust.yml` | OrbStack Docker 健康检查均实测转为 `healthy`（`L0_LITE_DOCKER_HEALTH=healthy`, `MCP_GATEWAY_DOCKER_HEALTH=healthy`） |
| **P1 · L0 Profile 满额保存与恢复** | 满额运行时保存/恢复先停 Chromium，但 SQLite 未更新状态，重启自检被自己名额挡住报 `NODE_MAX_SESSIONS_REACHED` | `LiteRuntime` 增加每 Session 生命周期锁与原子 `capacity_lock`；`stop_live_session` 同步将运行态回落至 `HIBERNATED`；`ensure_running_locked` 使用 `count_running_sessions_excluding(Some(self))` 并在启动失败时自动回滚 DB 状态防名额泄漏 | OrbStack `LITE_MAX_SESSIONS=1` 满额（1/1）启动真实 Chromium 156 后，`save` 与 `restore` 均成功重启且无 `NODE_MAX_SESSIONS_REACHED` |
| **账号级 Profile 归属、授权/撤销与同浏览器协作** | Profile 归管理账号所有；管理员可打开/恢复/授权/撤销，换 Agent 或 Token 可重授权保留登录态；管理员与 Agent 可进入同一运行浏览器协作，同一份 Profile 仅允许一个实例写入 | SQLite 新增 `profiles` 与 `profile_grants`（expand-only）；Agent 默认仅见已授权 Profile；`POST /api/v1/lite/profiles/:id/open` 已有运行实例时以 `collaborating=true` 接入同一浏览器，禁止并发创建第二写入者（HTTP `409 PROFILE_ALREADY_IN_USE`）；Checkpoint 按 Profile 维度授权读取，撤销后原 Agent 立即 403，重授权新 Agent 即保留登录态与 Checkpoint；同步扩展 MCP `browser_profile` 与 `browsercloud-ctl profile` | OrbStack 实测 `SINGLE_WRITER_409_PASS`、`SAME_BROWSER_COLLABORATION_PASS`、`REGRANT_AGENT_B_REAL_CHROMIUM_PASS` 全通过 |
| **按 Agent Token 隔离运行配额** | 单 Agent 用满额度仅拒绝其新启动请求；机器仍有容量时其他 Agent 可继续启动，已运行浏览器不受影响 | `browser_sessions` 新增 `owner_token_id`；`count_running_sessions_for_token` 仅统计该 Token 的活跃运行中会话（不把已休眠会话占死名额）；支持 `PATCH /api/v1/lite/tokens/:token_id` 与 `browsercloud-ctl token quota` 在线调整配额 | OrbStack `LITE_MAX_SESSIONS=2` 实测 Agent A（额度 1）满额后再启返回 `429 AGENT_SESSION_QUOTA_EXCEEDED`，A 已运行浏览器保持可用且 Agent B 成功启动真实 Chromium |
| **公网照常、内网分单次与永久授权** | 公网访问照常；自建内网后台支持**单次授权 (`once`)**（仅本次运行有效/可选 TTL，休眠或关闭自动失效，不写永久表）与**永久授权 (`permanent`)**（持久化到环境/Profile，休眠、重启、重开或换 Agent 重授权后自动生效不再重复询问）；未授权仅拒绝该次网络请求，浏览器和任务保持运行，并返回 `authorizationOptions: ["once", "permanent"]` | 新增纯 Rust 进程内回环 HTTP/CONNECT 出口守卫 `crates/lite-server/src/egress.rs`（带 DNS 解析防 Rebinding 与云元数据永久封禁）、`GET/PATCH /api/v1/lite/sessions/:id/network-policy`、`POST /network-policy/authorize`、`DELETE /network-policy/grants`、MCP `browser_profile` `authorize_private_host`/`revoke_private_host` 与 `browsercloud-ctl network show\|authorize\|revoke`，支持运行中动态生效免重启 Chromium | OrbStack 实测未授权回环请求返回 `403 PRIVATE_NETWORK_HOST_NOT_AUTHORIZED` 且浏览器继续存活、动态授权免重启立即访问成功；单测与集成测试验证 `once` 休眠自动清除、`permanent` 跨休眠/重开持久化且与 `once` 增量共存 |

## 2. 自动化回归与清理确认

- Rust 全工作区（20 Crates）：`cargo fmt --all -- --check`、`cargo test --locked --workspace`、`cargo clippy --locked --workspace --all-targets -- -D warnings` — 全部通过（0 warning）。
- Compose 门禁：`make test-default-compose` — 12/12 通过。
- 文档与模块表：`make docs-generate && make docs-check` — 7/7 + README 校验通过。
- OrbStack 临时 `:accept` 容器与镜像已全部自动清理（`0` 残留）。
