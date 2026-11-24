# 模块化构建与小内存运行指南

针对 **2–4 GB 内存服务器 / 个人轻量单机** 以及 **按需裁剪构建组件** 场景，提供独立的模块化 Compose
拓扑（`deploy/compose/modular.yml`）、小内存调优叠加层（`deploy/compose/low-memory.yml`）和统一命令入口
`tools/local-dev/stack.sh`（也可通过 `Makefile` 的 `stack-*` 目标调用）。

> 原根目录 `docker-compose.yml` 保持不变，继续作为完整四 Worker 本地开发/验收基线。

## 一、常用预设

| 预设 (`--preset`) | 包含模块 | 空载常驻内存估算 | 适用场景 |
| --- | --- | --- | --- |
| `minimal-mcp` | Core + MCP + 小内存调优（不含 Web / VNC / 录制扫描 / 4 Worker） | **~420–550 MiB** | 纯外部 AI（Claude/Cursor/Codex）通过 MCP 操控浏览器，最省内存与镜像空间 |
| `direct-lite`（默认） | Core + MCP + Web 控制台 + VNC + 小内存调优（不含 4 Worker / 录制扫描） | **~480–650 MiB** | 2–4 GB VPS：外部 AI 直连 + Web 管理/按需 VNC 观察，无需配置外部模型 Key |
| `full` | Core + MCP + Web + VNC + 录制脱敏扫描器 + 4 托管 Worker（标准内存） | ~1.5–2.0 GiB | 完整托管 Reviewer / Outcome / Vision / 录制全功能 |

## 二、快速使用

```bash
# 1. 交互式菜单选择要构建/开放的功能（MCP / Web / VNC / 录制扫描 / 托管 Workers / 小内存模式）
./tools/local-dev/stack.sh --interactive build
./tools/local-dev/stack.sh --interactive up

# 2. 最轻纯 MCP 无头模式（不编 Web、不装 Xvfb/x11vnc、不装 OpenCV/Tesseract、不启 4 Worker）
./tools/local-dev/stack.sh --preset minimal-mcp build
./tools/local-dev/stack.sh --preset minimal-mcp up

# 3. 自由组合任意开关（示例：开 MCP + 开 Web + 关 VNC + 关录制 + 关托管 Worker + 开小内存）
./tools/local-dev/stack.sh --mcp --web --no-vnc --no-recording --no-workers --low-memory build
./tools/local-dev/stack.sh --mcp --web --no-vnc --no-recording --no-workers --low-memory up

# 4. 预览当前组合会构建哪些服务与内存上限（不启动任何容器）
./tools/local-dev/stack.sh --preset minimal-mcp config

# 5. 停止服务
./tools/local-dev/stack.sh down
```

也可直接通过 `Makefile` 调用：

```bash
make stack-config STACK_ARGS="--preset minimal-mcp"
make stack-build  STACK_ARGS="--preset direct-lite --no-vnc"
make stack-up     STACK_ARGS="--preset direct-lite --no-vnc --max-sessions 2"
make stack-down
```

## 三、可独立裁剪的构建与运行开关

| 参数 | 说明 | 关闭后节省的开销 |
| --- | --- | --- |
| `--mcp` / `--no-mcp` | 是否构建并开放 MCP Gateway（`8130`） | 不构建镜像、不监听 MCP 端口、省 ~45–96 MiB |
| `--web` / `--no-web` | 是否构建并开放 Web 管理端（`3000`） | 跳过前端构建、省 Nginx 容器 ~25–64 MiB |
| `--workers` / `--no-workers` | 是否构建并启动 4 个托管模型 Worker | 省 4 个 Python 容器（~250–350 MiB），且直连模式无需配置外部模型 Key |
| `--vnc` / `--no-vnc` | 是否在 Browser Node 镜像中安装并启用 Xvfb/x11vnc 远程桌面 | 跳过 Xvfb/x11vnc 安装，每会话省 ~80–150 MiB |
| `--recording` / `--no-recording` | 是否在 Browser Node 镜像中安装 OpenCV + Tesseract OCR 脱敏扫描器 | **镜像体积减少 ~700 MB**，构建更快 |
| `--low-memory` / `--no-low-memory` | 是否叠加 `low-memory.yml`（JVM SerialGC 288M、PG 32M buffers、Redis 48M、Chromium `BROWSER_LOW_MEMORY_MODE=true`） | 整套空载常驻压低至 ~450–650 MiB，且保留 Chromium 沙箱与 seccomp |
| `--max-sessions N` | 设置小内存节点允许并发运行的浏览器会话数（默认 `2`） | 防止并发过高触发宿主机 OOM |
| `--node-mem-mb N` | 设置 Browser Node 容器内存上限与认证内存（默认 `1536` MiB） | 按实际 VPS 内存精确封顶 |
| `--bind-host IP` | 端口绑定地址（默认 `127.0.0.1`） | 仅按需暴露到指定内网地址 |
