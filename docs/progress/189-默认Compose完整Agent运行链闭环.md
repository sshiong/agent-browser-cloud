# 默认 Compose 完整 Agent 运行链闭环

> 日期：2026-09-19
> 范围：A01 默认 localhost 开发 Compose 与真实外部 Worker 运行拓扑一致性。

## 问题

默认 `docker-compose.yml` 原来只启动 Control Plane、Browser Node 与 Web，控制面同时关闭外部
Agent Executor、Reviewer 和 Outcome Verifier 队列，也没有 Vision Worker。单元/集成测试虽能
逐个运行真实 Python 入口，但 `make compose-up` 的日常拓扑与正式 Worker 模式不同。

## 实现

- 默认 Compose 现在启动 Agent Executor、Reviewer、Outcome Verifier、Vision 四个独立非 root
  Python 进程，并显式开启控制面的三个外部队列；四组 deployment/model revision 与控制面完全
  对齐。
- Worker 继续拒绝非 loopback 明文 HTTP。四个进程通过 `network_mode: service:control-plane`
  共享本地网络命名空间，只访问 `http://127.0.0.1:8080`，没有为了 Compose 放宽认证或传输规则。
- 本切片当时要求显式 `/v1/responses` Endpoint、模型名、固定 Revision 和绝对路径 API Key 文件。
  后续 progress 194 已将 Local Compose 的必填项收敛为 Base URL、Key、Model：Base URL 填到 `/v1`
  后自动补 `/responses`，Revision 默认 `local-v1`，并支持任意域名/IP 的 HTTP(S) 模型入口；生产
  Worker 仍强制 HTTPS 与 Host Allowlist。预检继续拒绝缺失值、错误 Endpoint 和占位模型名；文件型
  Key 继续拒绝符号链接、空文件与宽权限。不提供内置模型 fixture 或假成功路径。
- 一次性 Secret Init 只把凭据复制到按 Worker 隔离、UID 65532、`0400` 的命名卷；模型 Key 不
  进入 Worker 环境变量。Agent Executor 不挂载模型 Key。Worker 文件读取器补齐 owner-only
  `0400` 支持，同时继续拒绝 world-readable 文件。每次 `make compose-up` 都先重建 Secret Init
  与四个无状态 Worker 容器，避免 Key 文件轮换后进程继续持有旧凭据。
- 四个 Worker 只有在至少一次成功访问各自权威长轮询队列后才原子写入 readiness 文件；Compose
  healthcheck 不再把“Python 进程尚未退出”误当成运行链可用。`make compose-up --wait` 后自动执行
  `compose-verify`，验证控制面、四 Worker 健康及环境中无模型 Key。
- `docker compose up -d postgres redis` 仍可用于只启动基础设施；默认 Compose 依旧只绑定
  localhost，不改变 progress 188 的 Personal Secure 与生产安全边界。

## 验证

- Agent Worker 32 项测试通过，新增 `0400` Secret 和“成功请求后才 ready、失败不 ready”回归。
- 当时默认 Compose 3 项契约/预检测试通过，覆盖完整四 Worker、队列开关、真实凭据要求、HTTPS 和
  文件权限；`docker compose config --quiet` 通过。
- 真实执行 `make compose-up` 构建并启动 PostgreSQL、Redis、Control Plane、Browser Node、Web
  与四个 Worker。第一次真实运行发现 Secret Init 缺 `FOWNER`，第二次发现 Worker 未接受更严格
  的 `0400`，均修复并增加回归；最终 Control Plane 与四 Worker 全部 `healthy`，
  `compose-verify` 输出 `default Compose Agent/Reviewer/Outcome/Vision chain: PASS`。
- 运行验收使用不可调用的临时凭据且队列为空，只证明真实进程、身份、长轮询、readiness 和 Secret
  传播链；没有把 fixture 或无效凭据冒充模型成功。使用运营方真实模型凭据执行 Reviewer/Outcome/
  Vision 任务仍属于具体部署环境 Gate。
- 完整 `make ci`、`make build`、Desktop test/lint/unsigned build 与 PostgreSQL/Redis/MinIO/mTLS/
  Chromium `make test-integration` 均通过；Integration 输出 `worker_queue_long_poll_notify=true`、
  `agent_task_outcome_verification=true`、`agent_reviewer_risk_routing=true`、
  `challenge_visual_pixel_privacy=true`、`audit_chain_valid=true`，公开契约保持 250 Operations /
  345 Schemas。
- 实现提交 `f7810af` 首轮 GitHub `desktop` run `35445798305` 成功；首轮 Linux CI 发现 BSD
  `stat -f` 在 GNU `stat` 上被解释为文件系统格式并返回成功，导致权限值误判。兼容修复
  `fbc5179` 改为先使用 GNU `stat -c`、失败后回退 BSD `stat -f`，本地重新通过完整 `make ci`。
  最终 GitHub `ci` run `35446270368`（Verify、供应链、完整 Integration、Object Storage/Recording
  GameDay、Kubernetes Operator E2E）及 `desktop` run `35446270328`（Windows/macOS）均成功。

## 边界

该切片关闭 A01 的仓库默认编排不一致代码项，但不把 Local Mode 变成公网安全部署，也不宣称任意
外部模型已获得生产准入。公网/个人远程使用必须采用 progress 188 的 Personal Secure 或正式
生产部署；真实 Provider 数据政策、模型版本、成本与客户 Replay 继续由目标环境验收。
