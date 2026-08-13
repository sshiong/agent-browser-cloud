# Observer 手动截图与受控证据访问闭环

> 完成日期：2026-07-30
> 状态：真实 CDP 截图、Storage Helper 签名、PostgreSQL 治理账本、Outbox、
> 管理员 RBAC、Web/Tauri 共享 UI 和完整集成已闭环

## 关闭的缺口

进度 72 已完成 Agent Navigate/Action 的自动截图证据，但 Observer 仍不能由管理员
主动留证，已提交的原始像素也没有 Purpose-bound 的受控查看链路。本轮完成：

1. `POST /api/v1/sessions/{sessionId}/evidence:capture` 创建 PostgreSQL 权威 Capture
   Request，通过事务 Outbox 下发 `CaptureObserverScreenshot`，前端不直接调用 CDP；
2. Browser Node 仅接受固定的只读截图命令，使用真实
   `Page.captureScreenshot(format=jpeg)`，再由独立 Storage Helper 校验文件、大小和
   SHA-256 后 commit-last 上传 MinIO/S3；
3. HumanTakeover 活跃时 Control Plane 与 Node 双重 fail-closed，避免在人类连续输入
   期间自动截取；Capture 通过 `EXECUTING / COMMITTED / FAILED` 持久状态展示；
4. `POST /evidence/{evidenceId}/access-grants` 只允许管理员为
   Incident Response、Change Validation、Support Diagnostics 或 Compliance Audit
   创建五分钟 Purpose-bound Grant；
5. `POST /evidence-access-grants/{grantId}:redeem` 只允许同一操作者原子领取一次。
   Control Plane 以 mTLS 请求当前 Placement Node，Node 再要求 Storage Helper 对精确
   Evidence 对象生成 60 秒签名 GET；
6. API、数据库和 Audit 均不保存或返回 Object Key、Bucket Credential；短期 URL 只在
   Redeem 响应中出现，不进入日志和审计；
7. Session Detail 的 Evidence Card 使用真实 API 显示目的、Capture 状态、Request ID、
   Grant 和显式“打开证据”动作；Web 与 Tauri 共用 API Client、React Query、权限和
   `PlatformAdapter`，不使用 Mock、localStorage 或前端定时器伪造状态。

## 数据与安全边界

```text
Admin capture request
→ PostgreSQL capture request + Audit + Node Command Outbox
→ Browser Node fixed CDP screenshot
→ Storage Helper commit-last object
→ SessionEvidenceCaptured
→ capture request COMMITTED

Admin purpose grant
→ PostgreSQL one-time grant
→ same actor atomic claim
→ Control Plane mTLS
→ Node exact evidence validation
→ Storage Helper verifies COMMITTED + HEAD
→ 60-second signed GET
```

V062 新增：

- `session_evidence_capture_requests`：Capture 幂等、执行状态、命令和 Evidence 关联；
- `session_evidence_access_grants`：Purpose、操作者、五分钟有效期和一次性领取状态；
- `OBSERVER_MANUAL` Evidence 类型的安全扩展迁移；
- Evidence Tenant/Session 复合约束，以及 Grant 与 Evidence 作用域校验 Trigger。

失败不会被伪装为成功：Node Command 死信会把 Capture 置为 `FAILED`；对象不存在、
Marker 未提交、大小或哈希不匹配时 Storage Helper 拒绝签名；跨操作者、过期或重复
领取返回冲突，Operator/Viewer 返回权限错误。

## API 与 UI

- `POST /api/v1/sessions/{id}/evidence:capture`
- `GET /api/v1/sessions/{id}/evidence-captures/{captureId}`
- `POST /api/v1/sessions/{id}/evidence/{evidenceId}/access-grants`
- `POST /api/v1/sessions/{id}/evidence-access-grants/{grantId}:redeem`

写请求使用 `Idempotency-Key`，失败展示 Request ID。Capture 只在真实
`EXECUTING` 时进行短周期状态查询；Session 统一 SSE 到达后会使 Evidence/Capture
查询失效，不再对 Evidence 列表做固定五秒轮询。URL 只允许 HTTPS；本地开发额外允许
loopback HTTP MinIO，其他明文或非本地主机 URL 一律拒绝。

## 已完成验收

- Java 全量测试通过，新增治理服务测试覆盖 Outbox Capture、Purpose Grant、精确对象
  Redeem 和不持久化 URL；
- Rust Workspace `cargo check --locked --workspace --all-targets` 与全量测试通过；
- Web TypeScript、56 项测试、OpenAPI/Redocly 校验通过；
- V062 N/N−1 Gate 通过，固定 Expand/Validate/Contract 顺序和滚动升级事实；
- PostgreSQL 17、mTLS Control Plane/Browser Node、独立 Storage Helper 与 MinIO
  Integration 验证真实手动 JPEG 下载、哈希一致、跨操作者拒绝、一次性领取、RBAC，
  以及数据库/Audit 无签名 URL 泄漏；
- 同轮修复 Coordinator caller-cancellation 故障注入的等待竞态，使 Node Journal
  “已执行但事件未提交”断言不依赖 250ms 的调度时序。

## 仍需完成

1. 基础敏感语义识别与 Agent/Observer 截图不透明遮罩已由
   [进度 88](88-截图敏感区域遮罩与分类闭环.md)关闭；Site Policy 与 Recording 帧级遮罩
   已由进度 137、138 关闭，仍缺无语义视觉文本/OCR 分类；
2. Evidence 保留期与租户配额和既有 Legal Hold/WORM/Delete Receipt 的对象生命周期
   深度联动；
3. 目标 Linux 多 Session 下的截图吞吐、磁盘满、对象存储背压/网络分区和告警长稳；
4. 需要时把 Purpose Policy 细化到 Workspace/Site，而不扩大普通用户权限。
