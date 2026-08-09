# 独立 Agent Worker 队列与最小权限故障域闭环

> 日期：2026-08-09
>
> 对应迁移：`V079__agent_execution_worker_queue.sql`
>
> 范围：关闭 V16 Phase 4 中“独立无宿主权限 Agent Worker Sandbox、固定 IPC 和单独故障域”的仓库内实现缺口；不把 Reviewer、真实模型 Provider 或目标云生产准入误报为完成。

## 本轮结论

Agent Task 的生产执行入口已从 Control Plane 进程内直接执行改为 PostgreSQL 权威队列。
独立 Python Worker 只领取不透明的 `jobId/taskId`、一次性 Claim Token、Claim Epoch 和
Lease，并调用固定的 Claim、Start、Heartbeat、Drive、Fail 协议；它不会收到 Prompt、
Plan、网页正文、Capability Token、客户凭据或任意命令。真正的 Capability 校验、
Operation、Coordinator 路由、Outbox 和 Browser Node Tool 执行仍由 Control Plane
安全内核负责。

本地和测试环境默认保留旧的直接执行路径，以继续支持已有的确定性故障注入；生产
Kubernetes 清单显式启用外部 Worker，不会静默回退到进程内执行。

## 已实现

1. `V079` 将 Agent Task 增加 `QUEUED` 状态，并建立 `agent_execution_jobs` 和追加式
   `agent_execution_job_events`。Job 使用 PostgreSQL `FOR UPDATE SKIP LOCKED`、
   Attempt、Lease、Epoch 和 Token Hash 实现并发 Claim 与迟到 Worker 栅栏。
2. Claim Token 使用 256-bit CSPRNG 生成，数据库只保存 SHA-256；明文只在 Claim 响应
   返回一次。Job 结束或等待时清除 Worker ID、Token Hash 和 Lease。
3. Lease 到期会按有界 Attempt 重新入队，达到上限后进入稳定失败；所有
   `ENQUEUED/CLAIMED/STARTED/HEARTBEAT/WAITING/REQUEUED/COMMITTED/FAILED` 转换写入
   不可变事件账本。
4. `AGENT_WORKER` 成为独立角色。Worker API 在 Controller 内再次强制角色边界；普通
   Tenant Operator 返回结构化 `403 ROLE_FORBIDDEN`，不能领取或驱动全局队列。
5. `Drive` 先在短事务内校验 Claim 并释放 Task Queue，再在行锁外通过物理 Coordinator
   Route 执行 `AGENT_EXECUTE`，最后用新的短事务和 Epoch/Token 重新校验并投影结果，
   避免跨 Shard 命令与队列表之间形成长事务死锁。
6. 新 `apps/agent-worker` 为标准库 Python 进程：生产只接受 HTTPS，禁用系统 Proxy 和
   Redirect，限制响应体大小；Token 文件必须是绝对路径、普通非符号链接文件且权限为
   `0600/0440`。进程不包含 Shell、子进程或动态代码入口。
7. Kubernetes 使用 `agent-sandbox` Kata RuntimeClass、非 Root UID 65532、只读根文件
   系统、`RuntimeDefault` AppArmor/Seccomp、Drop ALL、无 ServiceAccount Token、无 Host
   Mount，只挂载身份、CA 和 16 MiB 临时目录；NetworkPolicy 仅放行 DNS 与 Control Plane。
8. Web/Tauri 共用 Automation UI 正式识别 `QUEUED` 和
   `PAUSED_BY_RESOURCE_POLICY`，显示隔离 Worker 排队边界，不伪造执行进度。
9. OpenAPI 新增五个固定 Worker Operation；四语言 SDK 当前为 185 个 Operation、243 个
   公开 Schema。TypeScript 为 300 个服务方法、32 个服务和 261 个 Model 文件。
10. Agent Worker 已加入 CI、Docker 构建、Release Digest、SBOM、签名、Provenance、
    Release Bundle 与发布后验签，签名组件数由七个增至八个。

## 验收证据

- Python Worker：5 项单元测试通过；
- Web：21 个文件、67 项测试、TypeScript 检查、Prettier 和生产构建通过；
- OpenAPI：Redocly lint 通过；TypeScript 与 Python/Go/Java 生成清单逐文件 Hash 校验通过；
- Upgrade Gate：V079 的 N/N-1 兼容、队列约束、Worker 契约、Sandbox 与网络边界通过；
- Kubernetes：`kubectl kustomize` 成功渲染 Agent Worker、Kata RuntimeClass 和默认拒绝网络；
- Supply Chain：八组件 Release Bundle 测试通过；
- PostgreSQL/Browser Node Integration：验证非 Worker 403、Task `QUEUED`、能力 Claim、
  错误 Token 409、正确 Start/Drive、真实 Python Worker `--once`、事件顺序、结果提交和
  Token/Worker 敏感字段清除。

## 仍未完成

1. Reviewer Agent、真实模型 Provider、模型选择/版本/成本/数据治理和大规模 Replay Matrix；
2. 无语义像素/OCR Validator、高级组合编排、Challenge Detection、一次性 HumanAssist、
   协作取消和跨 Region Workflow；
3. 目标云 Kata/LSM/CNI 的真实强制证书、OIDC Workload Identity 发行与撤销、跨 Pod/跨
   Region 长稳和容量压测；
4. 客户批准站点、Provider、业务凭据与事务安全点的生产联调。

因此，本轮关闭的是仓库内独立 Worker 调度与故障域，不代表 Phase 4 的高级 Agent 或
V16 全量生产 Gate 已完成。
