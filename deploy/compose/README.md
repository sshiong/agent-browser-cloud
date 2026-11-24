# 分档部署（L0 Lite / L1 Personal / L2 Team）与按需构建指南

面向不同资源规模提供三个独立部署档位，并配套纯 Rust 控制 CLI `browsercloud-ctl` 与独立多阶段镜像目标（`lite` / `core` / `desktop` / `vision`）。

> 原根目录 `docker-compose.yml` 保持不变，继续作为完整四 Worker 本地开发/验收基线。

## 一、三档部署总览

| 档位 | Compose 文件 | 核心组件 | 默认不启动 | 适用场景 |
| --- | --- | --- | --- | --- |
| **L0 Lite（纯 Rust 单容器）** | `deploy/compose/lite.yml` | `browsercloud-lite`（Rust + SQLite + Headless Chromium + 原生 10 工具 MCP `/mcp` + REST API） | Java Control Plane、PostgreSQL、Redis、4 Python Worker、Web、VNC、OpenCV/Tesseract | 个人开发者 / 1–2 GB VPS / 外部 AI（Codex、Claude Code、Cursor）一条命令接入多浏览器，**免配置任何模型 API Key** |
| **L1 Personal** | `modular.yml` + `low-memory.yml` + `personal.yml` | Control Plane（`-Xmx384m`）、PostgreSQL（低内存）、Redis（48M）、Browser Node（默认 `core` 无头镜像）、Storage Helper、Web Console、可选 MCP | 4 托管 Worker、VNC、录制扫描 | 单机长期托管、Web 管理端、多用户/配额与审计 |
| **L2 Team** | `modular.yml` + `team.yml` | Control Plane（`-Xmx768m`）、PostgreSQL、Redis、Browser Node（默认 `core`，可切 `desktop`/`vision`）、Web Console、MCP Gateway | 4 托管 Worker（按需 `--workers` 开启） | 小团队多 Agent 并发与多节点扩展 |

## 二、快速启动

### L0 Lite（最轻：单容器纯 Rust，零 Java / PG / Redis / Python）

```bash
# 1. 构建并启动单容器 L0 Lite（仅编译 Rust + Chromium）
make lite-up
# 或：docker compose -f deploy/compose/lite.yml up -d --build

# 2. 获取首次自动生成的 0600 高熵 Bearer Token
make lite-token

# 3. 外部 MCP 客户端连接配置（支持多 Agent 各自独立 Token）
# URL: http://127.0.0.1:8130/mcp
# Header: Authorization: Bearer <token>
# 暴露 10 个收敛工具：browser_create / browser_list / browser_navigate /
# browser_observe (tree|find|inspect|diff) / browser_act / browser_act_batch /
# browser_wait / browser_screenshot / browser_profile / browser_close
```

### 使用纯 Rust CLI `browsercloud-ctl` 按档位与开关构建/启停

```bash
# L0 Lite（默认）
cargo run --locked --manifest-path apps/browser-node/Cargo.toml --bin browsercloud-ctl -- --tier lite up

# L1 Personal（默认 core 无头镜像 + Web + MCP + 低内存，不含 VNC/录制/4 Worker）
cargo run --locked --manifest-path apps/browser-node/Cargo.toml --bin browsercloud-ctl -- --tier personal up

# L1 Personal + 按需开启 VNC 远程桌面（自动切换 Browser Node target=desktop）
cargo run --locked --manifest-path apps/browser-node/Cargo.toml --bin browsercloud-ctl -- --tier personal --vnc build

# L2 Team + 关闭 MCP + 开启 4 托管 Worker
cargo run --locked --manifest-path apps/browser-node/Cargo.toml --bin browsercloud-ctl -- --tier team --no-mcp --workers config
```

## 三、Browser Node 多阶段镜像目标（`apps/browser-node/Dockerfile`）

| Target (`--target`) | 内容 | 说明 |
| --- | --- | --- |
| `lite` | `browsercloud-lite` + Chromium + chromium-sandbox + tini | L0 单容器纯 Rust 完整多浏览器服务 |
| `core` | `node-agent` + `network-helper` + `storage-helper` + Chromium | L1/L2 默认无头节点，不含 Xvfb/x11vnc 与 OpenCV/Tesseract |
| `desktop` | `core` + `xvfb` + `x11vnc` | 按需启用人工协作远程桌面 |
| `vision`（默认终态） | `core` + 可选 VNC + OpenCV + Tesseract OCR 脱敏扫描器 | 兼容原有全量镜像与 `ENABLE_*` 裁剪参数 |

## 四、交互式模块化脚本（兼容入口）

`./tools/local-dev/stack.sh` 与 `make stack-*` 继续保留用于交互式选择与自定义组合（`--preset minimal-mcp | direct-lite | full`、`--mcp/--no-mcp`、`--web/--no-web`、`--workers/--no-workers`、`--vnc/--no-vnc`、`--recording/--no-recording`、`--low-memory/--no-low-memory`、`--max-sessions`、`--node-mem-mb`、`--bind-host`）。
