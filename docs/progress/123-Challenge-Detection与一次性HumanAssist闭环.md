# Challenge Detection 与一次性 HumanAssist 闭环

> 日期：2026-08-12
> 状态：代码、契约、安全迁移、三栈全量测试、真实托管 E2E、PostgreSQL 与 Browser Node Integration 已完成；目标站点 Replay 和跨 Region 长稳待执行。

## 本轮目标

关闭 V16 中 Challenge Detection 与 HumanAssist 的权限隔离缺口，同时保持普通 VNC 与
Agent 协作语义：VNC 仅查看不会中断 Agent，真人真实输入优先；只有用户对当前 Challenge
明确授权后，平台才允许一次状态绑定的点击。平台不实现无人值守 CAPTCHA 求解或多轮试探。

## 已完成

### 1. 输入权隔离与 PostgreSQL 权威状态

- `ChallengeDetectionService` 只依赖权威 Browser State、PostgreSQL Repository 和 Audit，
  不持有 Input Broker、Node Command Gateway 或 Operation Capability；自动点击预算固定为 0；
- V087 建立 `challenge_events` 与 `human_click_intents`，记录 Tenant/Session/Context Epoch、
  State Version、Target Revision、置信度、最小化 Evidence、目标 Hash、授权/过期时间和状态；
- Human Click Intent 由数据库约束固定 `allowed_action_count=1`、`consumed_count<=1`，绑定
  Challenge、用户、Session、上下文、状态、目标、Bounds、视觉锚点、授权 Audit Event、
  Request ID、Idempotency Key 和短期 Expiry；
- Challenge/Intent 写入统一 Session Event Envelope，由现有可续传 SSE 驱动 Web/Tauri
  重新读取真实数据；Agent Task 保存当前 `challenge_event_id`；
- 既有大表的外键和事件类型约束采用 `NOT VALID → VALIDATE → 切换`，V088 使用非事务
  `CREATE INDEX CONCURRENTLY` 建立 Agent 等待查询索引。

### 2. 检测、分流与 Agent 生命周期

- 保守 Accessibility 信号只把可见、可用、非敏感的 Button/Checkbox 单击目标标记为
  `SINGLE_CLICK/CONFIRMED`；Evidence 不保存原始控件名称；
- OTP、图片选择、拼图、设备确认、多轮、支付确认及敏感验证码输入标记为
  `TAKEOVER_REQUIRED`，只能走显式 HumanTakeover；普通 Password 输入不会被误判成 OTP；
- Agent 动作返回的新权威 State 出现 Challenge 时，当前已验证 Step 先提交，Agent Operation
  随后结束，Task 进入 `WAITING_FOR_HUMAN`，不会继续发出下一次写操作；
- HumanAssist 点击完成后先提交旧 Operation，再对新 State 重新检测。若出现下一轮 Challenge，
  Agent 保持暂停并原子绑定新事件；只有没有新 Challenge 时才从持久 Checkpoint 创建新的
  Agent Operation 并续行；显式 HumanTakeover 结束时也使用其权威结束 State 重新检测，
  挑战已解除才恢复 Agent，仍有挑战则继续等待新事件。

### 3. Preview、用户授权与 Browser Node 单击栅栏

- 正式 API：
  `GET /api/v1/sessions/{id}/challenges`、`GET /api/v1/challenges/{id}`、
  `GET /api/v1/challenges/{id}/preview`、
  `POST /api/v1/challenges/{id}/assist-authorizations`；写接口要求 Operator、Actor、Tenant、
  Idempotency Key 和当前 Preview Hash；
- Preview Hash 绑定 Event、用户、Context Epoch、State Version、Target Revision、Target Ref
  和视觉锚点；State Invalid、状态/目标漂移、过期、已有 Operation 或 HumanTakeover 时拒绝；
- 用户确认后才创建独占 `Operation(mode=HUMAN_ASSIST)` 和一次性 Intent。Node Command 不允许
  前端直接构造，也不允许前端修改 cgroup/Input；
- Browser Node 再次检查 Session/ID/Hash 格式、单击次数、HumanTakeover、近期真实 VNC
  输入优先窗口、State Quality/Version、Target Revision、目标存在性、Button/Checkbox 角色、
  Visible/Enabled/Non-sensitive、精确 Bounds 与跨 Java/Rust 一致的 SHA-256 视觉锚点；
- 成功只产生一个 `HUMAN_ASSIST` State Update；失败产生有界错误事件并终结 Intent/Operation，
  不重放点击、不增加自动重试。新的点击必须重新预览和重新授权。

### 4. Web/Tauri 共用 UI 与公开契约

- Session Detail 新增 `ChallengeAssistCard`，展示真实事件状态、目标摘要、事件/状态/目标版本、
  自动点击预算 0、Preview Bounds、一次点击风险提示、执行状态和历史时间线；
- 多步骤挑战只提供“请求显式人工接管”，单击挑战必须经过“预览当前目标 → 用户确认”；
  状态文本不只依赖颜色，断线/错误保留后端 Request ID；
- React Query 接入正式 API，Session SSE 收到 `CHALLENGE_EVENT/HUMAN_ASSIST_INTENT` 后失效重取；
  没有 Mock、localStorage、固定 Timer 或伪造状态；
- OpenAPI、Proto、Java/Rust/TypeScript 生成契约和 TypeScript/Python/Go/Java SDK 已同步，
  当前公开契约为 194 个 Operation、260 个 Schema。

## 验收证据

```bash
make test
make lint
pnpm --dir apps/web-console build
make contracts-check
make sdk-typescript-generate sdk-multilang-generate
make test-sdk
make test-e2e
make test-integration
```

- Java：检测分流、普通密码不误判、一次性 Entity、Agent Challenge 重绑定、Node Event 映射、
  HumanAssist 后仍有挑战时禁止误恢复；
- Rust：Java/Rust 视觉锚点固定向量一致，Browser Node/Remote Desktop 全量测试通过；
- Web：69 项测试、ESLint、Prettier 和 Production Build 通过；
- SDK：Python、TypeScript、Go、Java 的 194 Operation 运行时/打包测试通过，260 个公开
  Schema 与 34 个 TypeScript Service 的生成 Manifest 无漂移；
- 托管 E2E：真实 Web、PostgreSQL、Redis、Control Plane、Browser Node、noVNC 和 Agent
  计划/执行链通过；普通 VNC 观察与真人输入优先不切断 Agent；
- Integration：88 个 Flyway 迁移全部应用，Hibernate Schema Validate、真实 PostgreSQL、
  Control Plane、Browser Node、双 Coordinator/迁移/故障接管/Agent/审计链完整通过；最终
  `health={"status":"UP"}`、`public_tables=105`、`audit_chain_valid=true`。
- E2E 暴露的 Snapshot 过期清理 JDBC `Instant` 类型歧义已改为显式 SQL `Timestamp`；
  修复后定时清理与后续 Full State/Agent 质量门禁均通过真实运行验证。

## 尚未完成

1. 无语义像素/OCR Challenge 检测、客户批准站点的分类准确率 Replay、误报/漏报阈值和
   生产模型变更 Gate；当前仅是保守 Accessibility 信号，不承诺识别所有 Challenge；
2. 目标 Linux 正式 Chromium/x11vnc 下 VNC 输入竞争与 HumanAssist 的长稳、组合输入法和
   多客户端容量证书；
3. 参与者在线列表、管理员撤销、单编码器 Fan-out、跨 Region Desktop Relay 与 Agent
   Workflow；
4. 客户 CRM/支付/IAM 的业务事务 Adapter、站点级 Challenge Policy 和生产合规审批；
5. 生产 PostgreSQL 表规模下 V087 约束验证、V088 在线索引的锁等待/复制延迟窗口实测。
