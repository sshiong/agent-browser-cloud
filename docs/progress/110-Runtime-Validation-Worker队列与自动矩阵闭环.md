# Runtime Validation Worker 队列与自动矩阵闭环

> 状态：仓库内代码、契约、部署和集成验收闭环；目标浏览器/OS Worker Fleet、客户批准
> Fixture 与大规模长期回归仍属于生产 Gate
> 日期：2026-08-09

## 本轮关闭的问题

此前 Runtime Validation 只有 PostgreSQL Run/Evidence 和管理员 `start/complete` API，
没有独立执行故障域，也没有可恢复的自动矩阵队列。本轮新增 V076，把自动验证改造成：

```text
管理员提交 Browser/OS/能力矩阵
→ PostgreSQL QUEUED Job
→ 精确能力 Worker SKIP LOCKED Claim
→ Claim Token + Epoch + Lease Fencing
→ 固定 Runner 执行授权 Replay Catalog
→ RESULT_ACKED
→ Validation Evidence 与 Job COMMITTED 同事务提交
```

## 已完成

1. `V076__runtime_validation_worker_queue.sql` 新增 Job、不可变事件和 Worker Liveness/能力
   投影；队列状态为 `QUEUED → CLAIMED → EXECUTING → ACKED → COMMITTED`，失败为
   `FAILED`，不以进程内存或 JSON 文件保存权威状态。
2. 新增自动矩阵 API，以及 Claim、Start、Heartbeat、Complete、Fail 六类 Worker API；
   只匹配精确 Browser Engine/Version、OS、Architecture 和能力集合。
3. Claim 使用 PostgreSQL `FOR UPDATE SKIP LOCKED`；随机 256-bit Token 只向 Claimant
   返回，数据库只存 SHA-256；Worker ID、Claim Epoch、Token Hash 和 Lease 共同栅栏，
   过期 Worker 的迟到结果返回 409，不能覆盖新执行结果。
4. 可重试失败和租约过期进入有界退避；尝试耗尽后 Job/Validation 原子失败、Runtime
   Build Quarantine。15 秒 Reaper 支持多 Control Plane 并发而不重复处理。
5. 增加独立 `VALIDATION_WORKER` 角色。生产 Worker 使用 Bearer JWT；本地身份 Header
   只在 `APP_ENVIRONMENT=local|test` 生效，不能获得 Session 运维权限。
6. 新增无第三方运行时依赖的独立 Worker。Job 不能提供命令；只执行部署固定的绝对
   Runner 路径和参数。Runner 环境是明确 Allowlist，不继承 Control Plane Token 或宿主
   Secret；stdout/stderr 边读取边限流，超时、输出超限或租约丢失立即终止进程组。
7. 新增固定 Chromium Runner：校验镜像内真实 Browser Version，只从只读授权 Catalog
   选择 Dataset/Case，执行真实 Headless Browser 页面验证；不会把 DOM、Cookie 或凭据
   回传 Control Plane。
8. Kubernetes Deployment 使用专用 Sandbox RuntimeClass、只读根文件系统、非 Root、
   RuntimeDefault AppArmor/Seccomp、Drop ALL、无 ServiceAccount Token 和默认拒绝网络；
   Egress 仅到 Control Plane、DNS 与显式标记的 Validation Fixture Namespace。
9. Worker JWT 文件只接受 0600，或当前专用 Pod `fsGroup` 的 0440；组写、非当前组读取
   和任何 Other 权限均 fail-closed。Control Plane CA 以只读 Trust Bundle 单独挂载。
10. Validation Worker 镜像已进入 CI Build、GHCR Release、Syft SPDX、Cosign 签名/
    Attestation、Digest-locked Release Bundle 与发布后验证；发布包组件从 6 增至 7。
11. Enterprise UI 显示真实 Browser/OS Matrix、Queue State、Attempt、Worker、失败原因，
    不模拟验证进度。
12. OpenAPI 当前为 166 个唯一 Operation、224 个公开 Schema；TypeScript 为 262 个服务
    方法、32 个服务和 241 个 Model 文件，Python/Go/Java 同步确定性生成。

## 验收证据

- 8 项 Validation Worker 单测覆盖 Secret 权限、最小角色、能力 Claim、无任务 204、固定
  Runner、父进程 Secret 隔离、非零退出、输出上限和真实版本矩阵。
- PostgreSQL Integration 覆盖错误版本无法 Claim、正确能力 Claim、Start、伪 Token
  拒绝、ACK/COMMIT、不可变事件顺序、自动矩阵、租约耗尽、Worker Offline 投影、Build
  Quarantine 和过期 Token 迟到提交拒绝。
- 真实 URL/真实 Chromium 矩阵已通过：Runner 以镜像内精确 Browser Version Claim Job，
  执行授权公开 Dataset，并提交 `COMMITTED` Evidence；验收同时覆盖导航、读取、输入、
  滚动、跨域拒绝和非 Allowlist 计划拒绝。Session 首份 State 尚未生成时合法返回 204，
  State Resync 窗口只等待 `COMPLETE|DEPTH_LIMITED`，不绕过 fail-closed 计划 Gate。
- Control Plane Test、Web Lint/Build、OpenAPI/Redocly、四语言 SDK 漂移、V076 N/N−1
  Additive Gate、Release Bundle Canonical Gate 和 Workflow YAML 解析通过。

## 仍未完成

1. 在目标基础设施部署 Chromium/Firefox/WebKit、Windows/macOS/多架构的精确版本 Worker
   Pool，并为每个镜像生成签名、SBOM、容量和隔离证书；仓库基线只交付 Linux Chromium。
2. 接入客户批准且不含生产凭据/PII 的真实业务 Fixture、正式 Replay Dataset 与大规模
   长期回归；当前 Bundled Runner 是通用执行器，不包含客户数据。
3. 将矩阵结论接入目标组织外部发布编排、审批和回滚；仓库内 Runtime Promotion Gate
   已可消费 Validation/Freeze 状态，但外部发布系统不在本仓库控制范围内。
4. GameDay 真实故障执行器、多 Region 切换与 Validation Matrix 联动仍未完成。
