# Agent 统一恢复指令与 Why Stuck 投影

日期：2026-09-07。承接 progress 165 的 A08/A16；本切片仓库内闭环已完成。

## 统一恢复决策

Agent Task 正式投影新增可选 `recoveryGuidance`，只能由控制面根据 PostgreSQL 持久 Task
状态和已记录的 Reviewer、Replan、执行等待、阻断及失败证据生成。指令固定为：

- `RETRY`：同一受控阶段允许显式重试；
- `REFRESH`：现有 State/Target 证据过期，先重采状态；
- `REPLAN`：语义或路线变化，需要基于新状态重新规划；
- `WAIT`：队列、Reviewer、资源策略或真人输入优先等系统内等待；
- `HUMAN`：缺少 OTP、确认或其他必须由人提供的决定；
- `TERMINAL`：策略禁止、Prompt Injection 来源阻断或不可恢复失败。

`automatic` 明确控制面是否已经处理该决定，防止客户端在队列或 State Resync 期间重复点击。
原因码去空白并限制到 256 字符，避免异常文本破坏对外契约。完成或正常运行的任务不虚构恢复
动作。字段保持可选，以兼容 N−1 控制面滚动升级。

## Why Stuck / Trace

Web 与 Tauri 共用的任务详情在同一视图展示持久 `currentStep`、pending tool/step、计划验证规则、
已验证执行结果、阻断/失败证据，以及新的下一步指令和原因。终止、人工和自动处理使用不同
视觉状态；自动处理时明确提示不要重复操作。新增服务端六类映射/原因边界测试及 React 静态
渲染回归，覆盖自动 REFRESH 和非自动 TERMINAL。

本切片没有把策略 Reviewer 冒充 Outcome Verifier，也没有完成业务 Expected Outcome、循环检测
或跨 Replan 的业务记忆；这些仍分别由 A04/A09/A10/A11 跟踪。

## 契约与验证

- OpenAPI 保持 240 Operations，新增 `AgentRecoveryGuidance` 后为 320 Schemas；
- TypeScript/Python/Go/Java SDK 与生成 Manifest 已同步；内部 Protobuf 未变化；
- 控制面 512 项、Web 139 项、Rust Workspace、全部 Worker/Go Provider、完整
  Test/Lint/Build、契约 lint、四 SDK 无漂移、README 门禁与 N/N−1 均通过；
- 完整 PostgreSQL/Redis/MinIO/mTLS/Chromium Integration 通过，包含 Agent Reviewer、真实
  Reviewer Provider fixture、Agent Browser 高级动作、截图/文件/JS、Challenge Vision、
  Profile 恢复、资源策略真实并发与审计链，最终 `audit_chain_valid=true`、Audit 349 条。

A08/A16 的仓库内代码项据此关闭；真实目标模型与完整 Worker Compose 运行仍由 A01 单独验收。
