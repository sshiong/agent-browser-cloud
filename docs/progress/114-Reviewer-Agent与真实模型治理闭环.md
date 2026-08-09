# Reviewer Agent 与真实模型治理闭环

> 日期：2026-08-09
> 状态：仓库内 Reviewer 队列、OpenAI Responses Provider 协议、数据最小化、模型版本、成本审计、真实 Worker 进程和 PostgreSQL 集成已闭环；目标生产 Provider 凭据、云出口与客户 Replay 仍是部署 Gate。
> 范围：关闭 Phase 4 中 Reviewer Agent 和真实模型 Provider/模型治理的代码缺口，不把高级 Validator、大规模 Replay Matrix 或目标云生产准入误报为完成。

## 本轮关闭的缺口

1. V080 新增 PostgreSQL 权威 `agent_review_jobs` 队列、不可变
   `agent_review_job_events` 和 Agent Task Reviewer 投影；任务执行先进入
   `AWAITING_REVIEW`，只有同一 `planHash` 的审核批准才能创建 Agent Worker Job。
2. Claim 使用 `SKIP LOCKED`、256-bit 一次性 Token、只保存 SHA-256、Claim Epoch、
   Lease、Heartbeat、最大 Attempt 和过期 Reaper；旧 Token、迟到结果、错误模型版本、
   错误计划哈希和重复提交均失败关闭。
3. Reviewer 只接收 capability-free 的有界计划摘要：不包含 Capability Token、密封字段
   正文、Page State、Context Sources、客户凭据或可执行命令；目标只提供 Origin 和
   `targetRef` Hash。模型输入和输出只持久化 Hash，不持久化 Provider Prompt/Response。
4. 独立 `REVIEWER_WORKER` 调用固定、无重定向、禁代理的 `/v1/responses` Endpoint；
   非本地环境强制 HTTPS 和显式 Host Allowlist，Provider API Key 只从 Worker 的 `0600`
   Secret 文件读取，不进入 Control Plane、数据库、任务投影或日志。
5. 模型 Deployment ID、Provider Type、Model Name、Model Revision、Data Policy、最大输出
   Token 和输入/输出价格在排队时固化。Worker 领取和完成时再次核对 Deployment/Revision；
   Control Plane 按权威费率与 Provider Usage 计算 `costMicros`，记录 Token、延迟、
   Provider Request ID、输入/输出 Hash 和审核原因。
6. 模型必须返回受 JSON Schema 约束的 `APPROVE/REJECT`、固定 Reason Code 和 Confidence；
   Control Plane 再执行原因白名单、最低置信度、计划哈希和任务状态校验。低置信度不会
   自动放行，而是以 `MODEL_UNCERTAIN` 拒绝。
7. Kubernetes 使用独立 Reviewer 身份、Kata `agent-sandbox`、无 ServiceAccount Token、
   只读 RootFS、Drop All、RuntimeDefault AppArmor/Seccomp、CP/DNS/受控模型出口白名单。
   基础清单默认关闭 Reviewer 写入功能门，所有 Control Plane 升级完成后才显式开启，
   避免 N−1 副本读取不了 `AWAITING_REVIEW`。
8. Web/Tauri 共用 Automation UI 显示等待审核、模型/版本、Reason、Token、延迟和成本；
   不伪造模型结果或计费数据。
9. OpenAPI 新增五个固定 Reviewer Worker Operation。四语言 SDK 当前覆盖 190 个
   Operation、253 个公开 Schema；TypeScript 为 310 个服务方法、32 个服务和 272 个
   Model 文件。

## 验收证据

- Java：Spotless、编译和完整 Control Plane 测试通过；新增本地身份回归证明专用
  `REVIEWER_WORKER` 可认证，Tenant Operator 仍被 403 拒绝。
- Reviewer Worker：10 项 Agent/Reviewer Worker 单测通过，覆盖固定 Endpoint、生产 HTTPS、
  Host Allowlist、Secret 权限、结构化响应、版本/最大 Token 约束和失败关闭。
- Web：ESLint、Prettier、67 项单测、TypeScript 和 Vite Production Build 通过。
- Contract/SDK：Buf、Redocly、190 Operation/253 Schema 多语言生成与验证通过；V080
  N/N−1 Gate 通过，证据 Hash 为
  `65d1419eac5a8478c87a81ce32fe28760b99a5e7b7ea4bb06daee7822b4dd903`。
- Kubernetes：`kubectl kustomize deploy/kubernetes/base` 成功，Reviewer Deployment、
  Kata RuntimeClass 和默认拒绝/受控出口 NetworkPolicy 均可渲染。
- 完整 PostgreSQL 17 + 双 Control Plane + Browser Node Integration 通过：验证非法角色
  403、错误 Claim Token 409、人工 Reviewer、真实 Python Reviewer Worker、固定 HTTP
  Responses Fixture、结构化 JSON Schema、Provider Authorization、输入最小化、
  `ENQUEUED→CLAIMED→STARTED→APPROVED`、Token/成本、Hash、敏感 Claim 字段清除，随后
  真实 Agent Worker 完成执行；最终证据包含 `agent_reviewer=true` 和
  `reviewer_model_provider=true`。
- Worker/Reviewer 使用独立 Session 验证，不再污染主 Crash Recovery Session；测试同时
  暴露并关闭了物理 Shard 重平衡与逻辑 Owner Lease 的切换窗口。入口路由、PostgreSQL
  Command Inbox Claim、Claim Processor 和 `SessionCoordinator` 现在统一优先仍存活的
  逻辑 Owner，Owner Worker Lease 消失后才回退新物理 Shard。完整链路随后通过自动崩溃
  恢复、Node Restart、17 个已提交 Durable Workflow 和哈希审计链验收。

## 仍未完成

1. 在目标云 Secret Manager/Workload Identity 下配置正式 Provider Endpoint/API Key、
   私有 CA、DNS 与 Egress，并完成轮换、撤销、限流、429/5xx、超时和账单对账演练；
2. 客户批准站点与真实业务 Fixture 的大规模 Replay Matrix、模型升级离线评估、Canary、
   回滚阈值和长期质量/成本证书；
3. 无语义像素/OCR Validator、高级 All/Any/Sequence/Negative 组合验证、Challenge
   Detection、一次性 HumanAssist、协作取消和跨 Region Agent Workflow；
4. 目标云 Kata/LSM/CNI 强制证书、跨 Pod/跨 Region 长稳和组织模型风险/数据处理审批。

因此，本轮关闭的是仓库内 Reviewer 与真实 Provider 协议/治理执行链，不代表客户模型
凭据已上线，也不代表 Phase 4 全部高级 Agent 能力或 V16 生产发布 Gate 已完成。
