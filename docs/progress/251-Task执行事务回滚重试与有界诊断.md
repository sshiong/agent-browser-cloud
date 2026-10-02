# Task 执行事务回滚重试与有界诊断

> 日期：2026-10-03
> 范围：Integration 的 false-success Task execute 请求与既有 API 错误契约。

## 依据

提交 `25091d7b63145f51680ffe14cf1bba2866a97655` 的 CI `37036521858` 在该请求收到
HTTP 503，原 curl 丢弃响应正文。同一 Servlet 线程临近日志出现审计头 SQLState
40P01 死锁与事务重试提示，但不能逐字确认历史响应的 JSON code，完整锁顺序也未还原。

正式 Handler 已将 SQLState 40P01/40001 映射为 HTTP 503、
`DATABASE_TRANSACTION_RETRY` 和 `details.retryable=true`；连接中断则返回
`DATABASE_UNAVAILABLE`，没有事务回滚重试证明。Reviewer 的执行排队在事务内完成，
重复已排队/待审核 Task 保持原任务，不直接重放 Browser 输入。

## 修改

- 仅替换上述一处 Integration 请求，保持原 Task、Tenant、Idempotency-Key 与后续
  Reviewer、Agent、独立 Outcome Verifier 的业务失败断言。
- 使用 loopback 专用 Python Helper：固定 POST、拒绝环境代理和 HTTP 重定向，
  只允许该 Task execute 路径，不接受 URL 凭据、Query 或 Fragment。
- 只有 HTTP 503、精确错误码和布尔 `retryable=true` 同时成立才重试；总尝试最多
  三次，退避分别为 0.1/0.2 秒，逐次保持完全相同的 Tenant、幂等键与请求体。
- 其他 HTTP 错误、畸形响应、连接中断及超时仍失败；没有对站点表单或 Browser
  输入增加重试，也没有放宽终态、权限、域名或状态围栏。
- 错误正文只在有界内存中解析，日志只输出固定类别、HTTP 状态和尝试次数，
  不输出原消息、URL 或正文。真正发生的事务重试输出固定次数诊断。
- 成功响应写入测试私有目录的 0600 文件，拒绝跟随文件符号链接。
- 无生产实现、API、数据库、OpenAPI、Protobuf 或 SDK 变更。

## 验证

- 五项真实 loopback HTTP 故障测试通过：连续两次明确回滚后成功、三个尝试耗尽、
  其他错误不重试、连接断开不重放及请求范围预检。精确比较每次请求，并验证
  私有错误正文不进入异常摘要。完整 Replay Gate 28 项通过。
- Python 编译与 `bash -n` 通过。
- 正式 Handler 定向 5 项通过，确认两个回滚 SQLState 的精确重试信号，以及
  连接中断没有该信号。Spotless 检查通过。
- 完整 OrbStack Integration 通过，退出码 0；保留独立业务失败判定、事件流、
  Recording、Profile、Proxy、审计链与后续治理断言。本轮没有输出事务重试诊断，
  不能声称实际数据库冲突被重试；回滚后的 Helper 行为由上述故障测试覆盖。
- 文档单测 7 项、双语 README 模块表与本地链接检查、差异空白检查通过。

日志：`/tmp/agentbrowser-execute-retry-gate.log`、
`/tmp/agentbrowser-execute-retry-contract-linux.log`、
`/tmp/agentbrowser-execute-retry-integration.log`。

macOS 强制重跑生成步骤被已知上游 x86_64 gRPC 产物阻断，本机无 Rosetta；
定向 Java 测试改用已配置的 OrbStack Linux ARM JDK。该次测试 XML 明确 5 项全通过，
后续 Spotless 因跨平台缓存中的绝对路径失败；macOS 单独强制重跑 Spotless 成功。
不安装系统组件。格式日志为 `/tmp/agentbrowser-execute-retry-format.log`。

## 剩余边界

该补丁不会修复或证明审计头死锁的完整成因，也不能把原失败 run 改记为成功。
后续集成通过若未触发该错误，也不能声称真实数据库重试已被运行覆盖。
提交 `3426ad17cf0d8737d1e952a79d477d485dfa8e47` 的 Desktop `37039629924` 已通过，
其 CI `37039630035` 仍在运行；本补丁提交后仍须独立核验。

完整 17 例公开 Replay 连续稳定性、客户 Adapter/Provider/SPA、真实 OTP/企业
IdP/支付、目标 Linux/云/多 Region/GPU/Legal Hold、模型供应商取消回执、组织
发布签字及权利人的许可证决定继续保留。
