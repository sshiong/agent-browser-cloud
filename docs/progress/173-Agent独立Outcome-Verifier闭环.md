# Agent 独立 Outcome Verifier 闭环

日期：2026-09-10。承接 progress 165 的 A11；本切片实现完成并通过完整 Integration。

## 动作成功不再等于任务成功

- Agent 执行完技术动作后不再直接进入 `COMPLETED`，而是进入持久
  `VERIFYING_OUTCOME`；Agent Worker Job 同步进入 `WAITING`，直到独立结果审核完成；
- V117 新增 `agent_outcome_verification_jobs/events` 和 Task Outcome 投影，保存队列、租约、
  尝试、判定、原因、模型版本、Token/成本/延迟与哈希证据，不保存页面正文、Secret、
  Capability、元素值、截图像素或完整 Browser State；
- `VERIFIED` 才使 Task 完成；`NOT_VERIFIED` 以稳定错误
  `AGENT_OUTCOME_NOT_VERIFIED` 使 Task 和等待中的 Agent Worker Job 一起失败，因此“点击成功但
  页面显示业务错误”不会再被当成任务完成。

## 独立 Worker 与确定性围栏

新增独立 `OUTCOME_VERIFIER_WORKER` 身份、Python 进程和 Kubernetes Deployment。Reviewer
只审核动作是否允许，Outcome Verifier 只根据 Task Goal、最小化执行证据以及最终结构化状态
判断目标是否达成，两者使用不同的持久队列、权限、协议与审计事件。

领取前要求最终 Browser State 新鲜、完整、深度未截断且页面活动为 `STABLE`。若执行完成后的
最终状态仍在收敛，队列会在首次领取前按 Task、Session、State Version、Target Revision 和
State Hash 原子重绑定；领取后任何证据变化、租约过期、Claim Epoch/Token、Worker、模型部署或
Revision 不匹配都 fail-closed。失败可按有界退避重试，过期租约由 Reaper 收敛，最多三次。
低于服务端置信度阈值的模型 `VERIFIED` 也会被降为未验证结果。

正式 API 增加 Claim/Start/Heartbeat/Complete/Fail 五个 Operation，OpenAPI 与四语言 SDK
同步到 245 Operations / 334 Schemas。Web/Tauri 共用任务详情显示 Outcome 状态、判定、原因、
证据哈希、模型版本、成本与延迟；等待期间统一恢复指令为 `WAIT`。

## 验证

- Control Plane 528 项、Web 140 项和 Agent Worker 24 项测试通过；Rust Workspace、Go
  Provider、格式、Lint 与生产 Build 通过；
- OpenAPI lint、TypeScript/Python/Go/Java SDK 生成、可重复性、编译、打包与发布清单通过，
  公开基线为 245 Operations / 334 Schemas；
- V117 N/N−1 Gate 覆盖 additive 字段/表、`VERIFYING_OUTCOME`、精确状态证据和 Claim 围栏；
- 完整 `make ci` 通过，包含供应链、Operator、50k Coordinator Capacity、契约、SDK 与文档
  漂移检查；Desktop 2 项测试、fmt/check 与 unsigned release build 通过；
- 完整 PostgreSQL/Redis/MinIO/mTLS/Chromium Integration 输出
  `agent_task_outcome_verification=true`：真实 Reviewer、Agent、Outcome 三个 Python Worker
  进程完成正向任务；手工 Claim 验证角色拒绝、错误 Token、模型输出预算、事件序列、成本和
  Claim Secret 清除；假成功任务的技术动作全部完成后，由独立结果判定
  `BUSINESS_ERROR_VISIBLE`，Task 与等待 Job 最终均为 `FAILED`；同轮继续通过高级动作、Dialog、
  Evaluate、截图、文件、恢复、Profile、资源和持久 Session 全链。

实现提交 `1ec2202` 推送后的首次 GitHub `ci` run `34466982871` 在代码、契约与测试完成后，
被当天更新的 Trivy 数据库发现 Terraform Provider 间接依赖 gRPC-Go `v1.83.1` 命中
`CVE-2026-84445`；修复版本为 `v1.83.2`。依赖已升级并由 Go race test、vet 和供应链发布检查
复验，未豁免或忽略该漏洞；后续 GitHub 复跑结果以最新提交为准。

## 边界

- A11 独立语义审核已关闭，但当前 Task 仍只提供自然语言 Goal；A04 所需的结构化 Expected
  Outcome / Intent Verification 契约、确定性断言与站点级 Validator 仍须独立实现。
- 本地固定模型 Fixture 只验证真实 Worker 进程、协议、权限、持久性和成功/失败收敛；目标环境
  模型准入、质量评估、成本预算与长期稳定性仍属于生产 Gate。
- Outcome Verifier 当前为可配置外部 Gate；关闭时保留既有完成语义，不能把未启用环境声称为
  已具备独立 Outcome 验证。
