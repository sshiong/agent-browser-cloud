# Progress 338 — Ops Validation/GameDay Runners 与供应链 Release Bundle 纯 Rust 化闭环（R8–R10）

> 日期：2026-11-26
> 范围：`apps/browser-node/crates/ops-workers/`、`apps/browser-node/Dockerfile`

## 1. 背景与目标

前序 R1–R7（见 [progress334](334-全运行时Rust化重构追踪清单.md)）已将线上 6 块 Python 运行时服务迁至纯 Rust，但进一步逐项排查后，发现运维与发布链路中仍遗留 3 个独立 Python 可执行脚本：

| 编号 | 原 Python 组件 | 纯 Rust 目标 | 产物大小（release） | 状态 |
| --- | --- | --- | --- | --- |
| **R8** | `apps/validation-worker/runtime_validation_runner.py` (334 行) | `ops-workers` `browsercloud-validation-runner` (`src/validation_runner.rs`) | **709 KB** | **DONE** |
| **R9** | `apps/gameday-worker/gameday_runner.py` (426 行) | `ops-workers` `browsercloud-gameday-runner` (`src/gameday_runner.rs`) | **3.1 MB** | **DONE** |
| **R10** | `tools/supply-chain/release_bundle.py` (276 行) | `ops-workers` `browsercloud-release-bundle` (`src/release_bundle.rs`) | **517 KB** | **DONE** |

至此，`ops-workers` 容器镜像完整包含 5 个纯 Rust 二进制（`browsercloud-gameday-worker`、`browsercloud-gameday-runner`、`browsercloud-validation-worker`、`browsercloud-validation-runner`、`browsercloud-release-bundle`），彻底消除 `ops-workers` 镜像对 Python Runner 的运行时依赖（零新增第三方 Crate）。

## 2. 关键实现与等价性保障

1. **R8 `browsercloud-validation-runner`**（[validation_runner.rs](../../apps/browser-node/crates/ops-workers/src/validation_runner.rs)）：
   - 100% 保持 CLI（`--browser`/`--browser-arg`/`--suite-catalog`/`--case-timeout-seconds`/`--allow-http`）与 Stdin/Stdout JSON 契约一致；
   - 启动浏览器前 fail-closed 校验目录规模（≤1 MiB）、授权声明（`containsProductionData/personalData/credentials=false`）、唯一合法 `allowedHosts`、合法用例 ID 与 URL 白名单；
   - 通过独立进程组（`process_group(0)`）与有界读写线程（DOM ≤4 MiB、stderr ≤1 MiB）执行 `--headless=new --dump-dom`，超时或超限立即杀进程组防止僵尸 Chromium 残留。
2. **R9 `browsercloud-gameday-runner`**（[gameday_runner.rs](../../apps/browser-node/crates/ops-workers/src/gameday_runner.rs)）：
   - 100% 保持 CLI、NDJSON 阶段流（`INJECTING`/`FAULT_INJECTED`/`OBSERVING`/`RECOVERING`/`VALIDATING`）与结果信封一致；
   - 严格校验 `0600`/`0400`/`0440` 非符号链接凭据文件、无重定向 HTTP/HTTPS（非 TEST 强制 HTTPS，TEST HTTP 仅限回环）、64 KiB 响应上限；
   - 捕获 `SIGTERM`/`SIGINT` 后中止注入/观测并强制执行至少 30 秒宽限期的 `RECOVERING` 清理，使用键排序紧凑 JSON 计算与 Python 版一致的 `sha256:` `runnerEvidenceHash`。
3. **R10 `browsercloud-release-bundle`**（[release_bundle.rs](../../apps/browser-node/crates/ops-workers/src/release_bundle.rs)）：
   - 100% 保持 `render`/`verify` CLI 与 `schemaVersion=3` 规范；
   - 强制 40 位小写 Git SHA、全部 8 组件 `sha256:` 镜像摘要锁定、SPDX JSON 校验、`production/kustomization.yaml` 逐字节校验与 `release-manifest.json` 规范化输出。

## 3. 验证证据

- **Rust 全工作区（20 Crates）**：`cargo fmt --all -- --check`、`cargo test --locked --workspace`（`ops-workers` 4/4 新增单测全通过）、`cargo clippy --locked --workspace --all-targets -- -D warnings` 全通过。
- **跨语言逐字节等价验证**：对相同 8 组件镜像与 SPDX SBOM 分别用 `python3 tools/supply-chain/release_bundle.py render` 与 `browsercloud-release-bundle render` 生成发布包，`diff -u` 比对 `production/kustomization.yaml` 与 `release-manifest.json` **0 字节差异**，且 Python↔Rust 双向 `verify` 互验通过。
- **OrbStack 实机镜像验收（`orbstack` context，`OS=OrbStack Server=29.8.2`，临时镜像已清理）**：
  - 构建 `apps/browser-node/Dockerfile --target ops-workers`：`SIZE=168MB`；
  - 容器内实测 `! command -v python3 && ! command -v python` 通过，5 个纯 Rust 二进制全部就位（`517 KB`–`3.7 MB`）。
