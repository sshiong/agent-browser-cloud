# Challenge 视觉自动化与人工兜底闭环

> 日期：2026-08-18  
> 状态：仓库实现、本地验证与远端 Workflow 均通过。

> 后续说明：本文件记录 V103 当时的边界；安全/自动双模式、账号/密码/OTP 一次性密文
> 输入及“失败通知但不强迫人工接管”已由
> [进度 147](147-Agent安全与自动模式及一次性敏感输入闭环.md)取代相关结论。

## 目标

把原先“检测到 Challenge 后一律等人工”的保守行为改成自动化优先：低风险可见验证在
语义目标不可用时获取脱敏截图，由隔离 OCR/视觉 Worker 定位，并执行点击、连续点击或
滑动。默认最多尝试三次，Session 操作员可在 0—10 次范围调整；无法可靠处理或预算耗尽
后写入高信号通知，Agent 保持 `WAITING_FOR_HUMAN`，由操作员接管。

OTP、设备确认、用户主观判断、支付确认和账号安全决策不进入自动动作集合，仍要求显式
人工处理。本切片没有放宽 Agent 域名、Capability、真人输入优先级或 VNC 协作边界。

## 权威状态与恢复

- V103 为现有及新 Session 增加默认开启、三次预算、0.85 最低置信度、连续点击和滑动
  开关；迁移只做 expand/default/validate，不删除旧字段或协议。
- `challenge_automation_runs` 保存租户、Session、Agent Task、当前 Challenge、尝试数、
  冻结策略和终态；同一 Task 只允许一个活跃 Run。
- `challenge_visual_jobs` 保存截图 Capture/Evidence 引用、Worker Claim Token 哈希、
  Claim Epoch、租约、固定模型版本、归一化动作和结果；Worker 崩溃后最多三轮领取，
  `FOR UPDATE SKIP LOCKED` 防止多个 Control Plane 重复领取。
- Run/Job 的每次变化都写入现有 Session 单调事件游标的
  `CHALLENGE_AUTOMATION` payload-free Envelope。Web/Tauri 共用可续传 SSE 失效正式
  Query；断线沿用 Session Detail 的数据可能过期提示。
- 自动处理终态使用 `AGENT_CHALLENGE_AUTOMATION_FAILED` 审计事件。现有通知 Trigger
  将 `_FAILED` 事件投影成租户级 CRITICAL 通知，因此预算耗尽不会静默。

## 截图与 Worker 边界

- 截图复用已验证的 Observer Evidence 链：Node 先执行 Password/OTP/Payment/
  Account/Secret/PII 遮罩，再由 Storage Helper 提交对象；数据库和公共 Session API
  不保存或返回对象路径。
- `VISION_WORKER` 只能领取固定 `challenge-vision-worker/v1` Job。下载地址来自
  Purpose-bound、同 Actor、一次性、短期 Evidence Grant；生产 URL 必须 HTTPS 且 Host
  显式 Allowlist。
- Worker 只调用固定 `/v1/responses`，输出严格 JSON Schema：`ACT/ESCALATE`、置信度和
  最多八个归一化 `CLICK/SLIDE` 动作；不具备任意 CDP、键盘、文本或 Secret 能力。
- Kubernetes 使用独立 Deployment、只读根文件系统、无 ServiceAccount Token、全
  Capability Drop 和默认拒绝 NetworkPolicy，只允许 Control Plane、模型出口、Evidence
  下载出口及 DNS。

## Browser Node 执行栅栏

每次尝试创建新的 `CHALLENGE_AUTOMATION` Exclusive Operation。Node 在实际输入前再次
校验 Session/Run/Job ID、Operation Epoch、State Version/Hash、动作坐标和总预算；真人
接管或最近真人 VNC 输入拥有优先权并拒绝自动动作。全部动作先验证再执行，避免“前半段
已经点击、后半段才发现非法”的部分执行。连续点击间隔 120 ms；滑动使用十个插值步，
Viewport 边界会钳制到可点击像素。动作后必须采集新的权威 Browser State，仍有 Challenge
则重新截图进入下一尝试，消失后才恢复原 Agent Task。

## API、Web 与 SDK

- 新增 Session Policy GET/PUT、当前 Run GET，以及视觉 Worker Claim/Start/Heartbeat/
  Complete/Fail 五个端点；公开契约为 **211 Operations / 283 Schemas**。
- TypeScript、Python、Go、Java 四 SDK 与 Manifest 均由 OpenAPI 重新生成。
- Session Detail 显示自动尝试预算、实时阶段和 `attempt/current`，允许操作员调整；自动
  处理失败后展示明确原因和人工接管入口。Web 和 Tauri 复用同一组件与订阅逻辑。
- Session SSE Client 同时补齐了原先已在数据库产生但客户端校验器漏收的
  `CHALLENGE_EVENT`、`HUMAN_ASSIST_INTENT`、`REMOTE_DESKTOP_PARTICIPANT`，并加入新的
  `CHALLENGE_AUTOMATION`。

## 验证证据

- Java 全量 439 项、Node Event Mapper/Ingestion/Challenge Detection 定向测试通过；
- Rust Workspace 测试、`cargo fmt --check` 与 `cargo clippy -D warnings` 通过，Node 新增
  有界点击/滑动/总交互预算断言；
- Web 全量 113 项（Session API 27 项）、Build、Lint 通过；
- Agent/Reviewer/Vision Worker 共 13 项 Python 测试通过；
- OpenAPI/Protobuf Lint、四 SDK 测试与统一发布包通过；
- N/N-1 Gate 包含 V103 expand-only、视觉 Worker 隔离部署及新增 Protobuf 消息断言；
- 完整 PostgreSQL/mTLS/Chromium Integration 已真实执行 V103；新增集成断言覆盖默认三次、
  Operator 可调、Viewer 写拒绝、跨租户 404、非 Worker Claim 403 和 Worker 空队列 204。
- 实现提交 `9625f57`、供应链 Gate 修复提交 `a8e2268` 已推送 `main`；最终 GitHub `ci`
  run `32139754379`（含 Verify、供应链、Integration、Object Storage/Recording GameDay、
  Kubernetes Operator E2E）与 `desktop` run `32139754412`（Windows/macOS）均通过。

## 未关闭边界

本切片提供 Challenge 定位与有界输入，不等于通用像素安全分类器。无语义页面的 OCR 敏感
信息分类、客户站点高级组合规则、大规模 Replay/Canary、目标模型供应商准入及目标环境
长稳仍是生产 Gate；项目仍不得据此处理真实客户数据。
