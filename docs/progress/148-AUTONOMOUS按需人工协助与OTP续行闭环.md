# AUTONOMOUS 按需人工协助与 OTP 续行闭环

> 日期：2026-08-19
> 状态：仓库实现、本地全量验证与远端 Workflow 全部完成。
> 实现提交：`dde38da feat: resume autonomous agents with human otp`

## 问题与最终语义

进度 147 已允许 AUTONOMOUS Agent 通过一次性密文输入账号、密码和 OTP，但自动视觉预算
耗尽、未配置自动化或确实缺少验证码时会把原 Task 直接置为结构化失败。这仍然把“暂时需要
一个人工输入”错误建模成任务终止，也无法让操作员把验证码发给 Agent 后继续原任务。

本切片将行为固定为：

- AUTONOMOUS 不逐动作请求授权，自动视觉与敏感输入按 Session 配置的有限预算自行执行；
- 只有自动路径确认不能继续时，才产生一次持久的人工协助请求；重复观察、重试失败或轮询
  不重复通知；
- 原 Agent Task 保持 `WAITING_FOR_HUMAN`，浏览器现场和 Challenge 绑定不丢失，不强制创建
  Human Takeover；
- 操作员可以把 OTP 发送给 Agent 代填，也可以主动进入协作远程桌面自行填写；两者都是
  可选响应方式；
- Agent 成功输入后提交短 Operation，并从原 Task 的持久检查点续行；输入代理失败后仍保留
  同一 Challenge 供再次提供值，不把原 Task 直接判死。

支付、转账、购买、修改密码、删除账号等决策仍使用独立高风险确认。AUTONOMOUS 不是绕过
站点安全策略、财务确认或租户/RBAC 门禁。

## PostgreSQL、幂等与敏感数据边界

V105 新增 `agent_challenge_input_intents`：

- 以 Tenant/Session/Task/Challenge/Secret/Actor 绑定一次响应，保存幂等键、Operation、Step、
  Target Revision、State Version、最大尝试次数、期限和终态；
- 表中只保存现有一次性 Secret 解开后按 Tenant/Task/Step AAD 重新封装的密文，不保存 OTP
  明文、裸哈希或可枚举值；
- `FOR UPDATE`、唯一幂等索引、单 Operation/Step 约束和 60 秒短期限保证并发、重放及恢复语义；
- `EXECUTING → COMMITTED/FAILED/EXPIRED` 进入现有 Session 单调事件信封，事件类型为
  `AGENT_HUMAN_INPUT`，Web/Tauri 共享 SSE 收到后重取正式 API；
- OTP Challenge 可新增当前敏感 textbox/combobox `target_ref`。迁移先以 `NOT VALID →
  VALIDATE` 扩展约束，并继续接受 N−1 写入的无 Target 历史 OTP 行。

一次性 OTP Secret 仍由 V104 的正式 API 创建和事务消费。提交响应要求 Tenant Operator、
`Idempotency-Key`、同 Tenant/Session、`AUTONOMOUS`、未过期 OTP、当前 Context/State/
Target Revision、可见且启用的敏感文本目标，以及正在运行的 Session。Viewer、跨租户、
过期或陈旧状态均 fail-closed。

## 执行、通知和续行

- `AgentTaskEntity.requestHumanAssistance` 只在当前 Task 已等待同一 Challenge 时记录
  `HUMAN_ASSISTANCE_REQUIRED:*`，重复调用不改变状态，因此只有首个请求写入
  `AGENT_HUMAN_ASSISTANCE_REQUESTED` 高信号审计/通知。
- 自动化内部耗尽记录为 `..._RESULT/NEEDS_HUMAN`；OTP 本地输入失败记录为
  `AGENT_HUMAN_INPUT_RESULT/RETRY_AVAILABLE`，不会被通用 `FAILED` 通知投影重复放大。
- 新 `POST /api/v1/challenges/{eventId}/input-responses` 接受一次性 `secretId`。Control
  Plane 创建只含 `challenge.input.once` 能力的短期 `HUMAN_ASSIST` Operation，并复用既有
  `AgentAction TYPE_TEXT` 契约；Browser Node 仍执行覆盖式、默认三次且可调 1—10 次的
  有界输入重试。
- Coordinator 只接受 Operation Epoch、Owner/Mode、Capability 和 `step_human_*` 全部匹配的
  `AGENT_TYPE_TEXT` 或失败回调。成功 State 先提交 Intent/Challenge/Operation，再恢复原
  Agent Task，避免新 State 再次被检测成同一个 OTP Challenge。
- 回调服务是 Node Event Ingestion 的必需依赖；配置缺失会阻止应用启动，不会静默关闭续行。

## Web/Tauri、OpenAPI 与四 SDK

Session Detail 共用 Challenge 卡在 AUTONOMOUS 等待 OTP 时显示密码式、一次性验证码输入框：

- “发送给 Agent 填写”先创建 OTP Secret，再提交 Challenge 响应；两个写操作使用不同幂等键；
- 成功后清空本地输入；失败显示正式 API Request ID/错误；
- “进入人工协作”继续保留，但只有操作员主动选择才进入，不是系统强迫接管；
- 文案明确自动模式只在真正需要人工时通知。

OpenAPI 和 TypeScript/Python/Go/Java SDK 已同步到 **213 Operations / 287 Schemas**。

## 验证证据与剩余边界

2026-08-19 本地结果：

- 定向 Java 测试覆盖 OTP Target 绑定、Task 等待/单次请求、Challenge 输入状态机、成功回调
  先于重新检测、失败回调不终止原 Task；
- `make test` 通过：Control Plane 446 项、Browser Node Rust Workspace、Web 115 项、
  Application Adapter 11 项、Validation Worker 8 项、GameDay Worker 4 项、Agent/Reviewer/
  Vision Worker 13 项及 Go Provider 全部绿色；
- `make lint`、`make build`、`make test-desktop`、`make contracts-check` 通过；契约仅保留既有
  Enterprise Overview 两条 unused component warning；
- `make sdk-typescript-check`、`make sdk-multilang-check` 通过，生成物无漂移；
- `make test-upgrade-compatibility` 通过，确认 V105 expand-only、历史 OTP 行兼容和新事件枚举；
- `make test-integration` 通过完整 PostgreSQL/mTLS/Chromium 主链，V105 被真实 Flyway 应用，
  并保持 Challenge 视觉自动化、Enterprise Overview SSE、租户隔离、Coordinator 恢复、
  Recording 与审计链断言。

GitHub `ci` run `32159504238` 已通过，覆盖 Verify、Build、四 Worker 镜像、SBOM/扫描、
Integration、Object Storage/Recording GameDay 与 Kubernetes Operator E2E；`desktop` run
`32159504071` 的 Windows/macOS 原生安全边界和验证二进制构建均通过。

当前没有把目标站点“收到 OTP 后服务端认证成功”的专项浏览器场景写成完成；已有证据覆盖
输入 Operation、状态路由、原任务续行状态机和完整平台回归。目标 IAM/邮箱/短信 Provider
主动获取验证码、供应商特有认证、真实客户页面和长期稳定性仍是生产集成 Gate。
