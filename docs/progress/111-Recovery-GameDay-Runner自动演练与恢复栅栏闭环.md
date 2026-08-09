# Recovery GameDay Runner 自动演练与恢复栅栏闭环

> 状态：仓库内代码、契约、隔离部署和真实 PostgreSQL 集成闭环；目标云故障控制器、真实
> 多 Region Authority/流量切换和外部发布冻结联动仍属于生产 Gate
> 日期：2026-08-09

## 本轮关闭的问题

此前 Recovery GameDay 只有管理员手工 `start/complete` 和最终 RTO/RPO 证据，没有真实
执行队列、独立故障域、故障注入阶段、租约栅栏，也不能保证 Worker 消失后先恢复再结束。
V077 将自动演练改造成：

```text
管理员提交固定场景 + 环境 + Blast Radius
→ PostgreSQL QUEUED Job
→ 固定目录 Worker 按能力 SKIP LOCKED Claim
→ Claim Token Hash + Epoch + Lease Fencing
→ INJECTING → FAULT_INJECTED → OBSERVING
→ finally RECOVERING → VALIDATING
→ RESULT_ACKED → Evidence/Run COMMITTED
```

故障注入后 Worker 租约丢失时，Job 不会普通重试，而是进入 `RECOVERY_REQUIRED`；旧 Token
立即失效，只允许新的 Recovery-only Claim 执行恢复。未确认恢复的结果不能提交。

## 已完成

1. `V077__recovery_gameday_runner_queue.sql` 扩展 GameDay 的执行模式、环境、Blast Radius、
   审批、时限、阶段、中止和恢复字段，新增 PostgreSQL 权威 Job、不可变事件和 Worker
   Liveness/能力投影。
2. 自动场景只允许仓库固定清单；Job 不能提交 URL、命令或 Runner 路径。TEST 只能使用
   `TEST_FIXTURE` Blast Radius；自动模式至少声明一个有界目标。STAGING/PRODUCTION 与
   资源范围分离，PRODUCTION 默认关闭，并要求目标 Region 精确 Scope 的有效 Break Glass
   双人审批，批准者不能启动执行。
3. Claim 使用 `FOR UPDATE SKIP LOCKED`；随机 256-bit Token 只返回一次，数据库只保存
   SHA-256。Worker ID、Claim Epoch、Token Hash 和 30—300 秒 Lease 共同栅栏，迟到
   Heartbeat/Stage/Result 返回 409。
4. 阶段单调推进；故障注入后任何失败、租约过期、平台中止或最长时限到达都必须先恢复。
   Recovery-only 有独立有界尝试次数并优先领取，避免普通任务饿死故障恢复。
5. Runner 只读取不可变本地 Catalog，禁用代理和重定向；非 TEST Controller 必须 HTTPS，
   TEST 明文只允许 Loopback。Controller Token 只接受当前 Owner 的 0600 或专用进程组
   0440 文件，响应、配置、stdout/stderr、等待时间均有界。
6. Runner 真实执行 Inject、故障状态探测、Observe、Recover、健康探测和 Evidence 读取；
   `SIGTERM`、超时和异常均走 `finally` 恢复。清理拥有额外 30 秒有界安全窗口，不会因
   业务执行 Deadline 已到而跳过恢复。
7. 新增独立 `GAMEDAY_WORKER` 最小角色。父 Worker 只把 Controller Token 文件路径传给
   子进程，不传 Control Plane Token 或 Secret 正文；子进程环境为显式 Allowlist，输出
   边读边限流，超时/中止终止整个进程组。
8. Kubernetes Deployment 使用专用 `gameday-sandbox` RuntimeClass、非 Root、只读根、
   RuntimeDefault AppArmor/Seccomp、Drop ALL、无 ServiceAccount Token、45 秒终止窗口和
   默认拒绝网络；Egress 仅到 Control Plane、DNS 和同时具备 Namespace/Pod 双标签的
   HTTPS GameDay Controller。基础 Catalog 故意为空，因此默认不会领取生产任务。
9. Secret、CA 和 Catalog 使用精确 `subPath` 文件挂载，和 Worker 的非 Symlink
   fail-closed 检查一致；不会把 Kubernetes 投影目录中的符号链接误当成可执行凭据。
10. 正式 API 新增单项查询、主动中止以及 Claim/Start/Heartbeat/Stage/Complete/Fail；
    OpenAPI/TypeScript/Python/Go/Java 已同步为 174 个 Operation、232 个 Schema，
    TypeScript 为 278 个服务方法、32 个服务。
11. 企业运营 UI 读取真实 Job，显示 AUTO/MANUAL、环境、Blast Radius、Stage、Attempt、
    Worker、Recovery Attempt、恢复确认、中止和失败原因，不模拟演练进度。
12. GameDay Worker 镜像进入 CI Build、GHCR Release、Syft SPDX、Cosign 签名/Attestation、
    Digest-locked Release Bundle 和发布后验证；签名组件镜像从 6 增至 7，因 Control Plane
    有两个 Workload，最终渲染包含 8 个 Digest-locked Workload Image Reference。

## 验收证据

- 4 项 Worker 单测通过，覆盖 Secret 权限、真实 Inject/故障探测/Recover/健康/Evidence
  生命周期、固定目录能力声明、父进程身份和 Secret 隔离、空目录不 Claim。
- Control Plane 全量测试、Web Lint/Build、OpenAPI Redocly、V077 N/N−1 Gate、Kubernetes
  Kustomize Render、Release Bundle Canonical Gate 和四语言确定性生成通过。
- PostgreSQL Integration 覆盖不匹配场景 204、能力 Claim、Start、伪 Token 409、五阶段
  单调推进、Recovery Confirmed 后 ACK/COMMIT 和不可变事件顺序；另覆盖故障注入后 Lease
  Expiry、旧 Token 迟到拒绝、Recovery-only 二次 Claim、恢复确认后安全 ABORT。
- 本机 Docker 构建命令已执行，但基础镜像解析被本机配置的 USTC Docker Mirror EOF
  阻断；GitHub CI 使用正式 Runner 重新构建该镜像，仓库不把本地镜像站故障伪报为通过。

## 仍未完成

1. 在目标云部署各固定场景对应的最小权限 Controller，接入真实 PostgreSQL/Redis/
   Object Storage/Proxy/Region 等故障接口，并分别完成安全评审、凭据轮换和 Blast Radius
   证书；仓库基础 Catalog 不包含真实目标。
2. 完成真实跨 Region Authority、数据库/对象复制、DNS/流量切换和长时间 RTO/RPO 演练；
   当前集成使用受控 TEST Fixture 与本地 DR Registry。
3. 将连续 GameDay 失败或 Recovery 未确认接入外部生产发布编排和组织 Pager；仓库内
   Runtime Release Freeze Gate 已存在，但外部系统与组织审批不在本仓库控制范围内。
4. 补齐 GameDay 事件分页/导出报表、场景级趋势和自动 Remediation Ticket；当前不可变
   事件和 Evidence Hash 已在 PostgreSQL 中保留，但 UI 只展示最新 Job 投影。
