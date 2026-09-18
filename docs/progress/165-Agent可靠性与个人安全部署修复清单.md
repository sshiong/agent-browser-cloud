# Agent 可靠性与个人安全部署修复清单

日期：2026-09-03。起始代码：`917a8ff`。这是逐项实施账本，不是生产验收声明。

## 第一切片：Worker 退避、Vision 租约与认证环境边界

- 三个 Worker 共享有界指数退避和 equal jitter；默认空闲/失败窗口从 2 秒增长至
  30 秒，成功领取并处理任务后立即重置。心跳间隔不受空闲退避影响，`--once` 不等待。
- Vision 原来的心跳异常仅退出后台线程，主线程仍可调用模型/提交；现在下载后、模型调用后
  检查 lease-lost，失去租约后不再启动模型或发送 complete/fail。
- Agent/Reviewer/Vision 均在 finally 停止心跳，未预期异常也不遗留续租线程。
- 本地身份头仅对 `local` / `test` 启用；其他环境（含 staging、拼写错误和空值）使用
  OIDC 认证链，缺少 issuer 配置时启动失败，不回退到可伪造的身份头。

边界：已发出的 HTTP/模型请求并未被强制中断；服务端 Token/Epoch/Lease 仍负责最终
拒绝过期结果。本切片不声称完成 Browser Node 执行中取消，也不声称 Local Mode 可公网使用。
空闲唤醒最坏增加到配置上限；LISTEN/NOTIFY/long-poll 暂未实现。

验证：`make test-agent-worker` 21 项通过（含实际 loopback HTTP fixture）；认证边界
`SecurityEnvironmentTest`、`PlatformIdentityTest` 通过；控制面全量 498 项测试、
`spotlessApply test check` 与 N/N−1 Gate 通过。GitHub 推送/CI 结果随后记录。

## 用户问题逐项状态

| ID | 项目 | 当前状态 / 下一证据 |
| --- | --- | --- |
| A01 | Compose 完整 Agent/Reviewer/Vision 链 | 已确认默认缺服务；待实现并以真实进程验收，模型凭据不得用 fixture 冒充 |
| A02 | Local Mode 公网风险 | progress 168 统一 CP/Node/Helper 非 local/test 安全要求；完整集成验证中，入口限制、随机凭据与安全部署层待实现 |
| A03 | DOM 复用后的语义目标稳定性 | progress 175 已闭环：最近业务实体键/行语义 hash-only 绑定 Element ID，同路径同名虚拟行复用旧 ID 拒绝，真实 Chrome 通过 |
| A04 | Expected Outcome / Intent Verification | progress 174 已闭环：V118 结构化结果声明、hash-only 持久化、精确最终 State 确定性判定及模型假成功覆盖均通过完整 Integration |
| A05 | Vision Canvas/图片/PDF 隐私 | progress 176 已闭环：精确状态围栏 Challenge Region、本地 OCR/PII 像素遮罩、二次复核、能力/证明契约及不可验证时 Human Handoff 均通过完整 Integration |
| A06 | 动态微批次 | progress 177 已闭环：同一 Batch 按 Route/Tab/Dialog/DOM/Network 变化或四动作上限切段，连续稳定后续行；超时跳过剩余动作且不重放副作用 |
| A07 | Browser State 新鲜度 | progress 170 已闭环：服务端 observedAt/age/FRESH-AGING-STALE、页面活动、稳定页 15 秒受围栏心跳及 STALE 拒绝规划；完整集成通过 |
| A08 | 统一恢复指令 | progress 169 已闭环：持久 Task 状态产生六类正式指令，API/四 SDK/UI/完整集成通过 |
| A09 | Action Attempt Signature / Loop Detection | progress 171 已闭环：V115 哈希账本、Task 行锁、第三次无进展动作执行前阻断及完整 Chromium 集成通过 |
| A10 | 结构化任务记忆 | progress 172 已闭环：V116 分离 Browser State、可变 Task State 与 append-only Execution History，跨刷新/重规划保留最小化语义证据；完整 Chromium 集成通过 |
| A11 | 独立 Outcome Verifier | progress 173 已闭环：独立权限/队列/Worker、最终状态精确围栏、假成功拒绝及完整 Chromium Integration 通过 |
| A12 | Reviewer 风险路由 | progress 178 已闭环：仅可信、无 taint/敏感输入/确认且 Task/Step 均为 R0/R1 的计划确定性旁路；R2+、风险低报或解析异常仍强制模型审核 |
| A13 | Prompt Injection 来源与权限传播 | progress 179 已闭环：外部来源永久 data-only，保留/重复 ID 拒绝，创建与每次执行按精确可信来源/Trust/taint 双重 fail-closed |
| A14 | Worker 空轮询 | progress 180 已闭环：六类 Worker 使用 V120 事务通知与 15 秒有界长轮询，丢通知/断线由权威重查及 backoff+jitter 兜底 |
| A15 | 取消/Lease/Epoch | progress 181 已闭环：人工取消、Operation 丢失/过期及权威 Epoch 前进会通过独立高优先级链快速终止 Node 动作、释放输入并终态排空迟到事件；V121 保证首次取消响应精确重放 |
| A16 | Agent Trace / Why Stuck | progress 169 已闭环：Web/Tauri 统一展示持久步骤/动作/验证/失败/下一决策，完整集成通过 |
| A17 | Profile 应用层加密 | progress 183 已闭环：新 checkpoint 使用版本化 KEK + 随机 DEK 的 AES-256-GCM Envelope；旧明文恢复/导出时原地迁移，导出保持密文、导入先认证解密，N−1 Node 按能力 fail-closed |
| A18 | 网站 Session Health / Reauth | progress 184 已闭环：按 Profile × 网站独立保存有围栏和新鲜度的登录健康；Reauth/降级持续到可信 READY，技术恢复不再冒充登录有效 |
| A19 | Opaque Cross-Origin Frame | progress 185 已闭环：显式 Origin-only Opaque Frame、freshness/State/Tab/Bounds 双重围栏的脱敏观察；Frame 永不成为可执行 Target，输入固定 Human Handoff |
| A20 | DOM/Layout/Network/Focus/Route 稳定性 | progress 186 已闭环：Node 连续采样四类隐私安全指纹并与 Network 组合，动作/微批/Outcome 统一 fail-closed；真实 Chrome 动态页面通过 |
| A21 | 高层 Agent 操作接口 | progress 187 已闭环：正式 `snapshot/find/inspect/act/wait/handoff`，旧 `execute-actions` 保留 deprecated 兼容；所有写入口复用 State/Task/Reviewer/Outcome 治理链 |
| A22 | Personal Secure 一键部署 | 待实现，不能复用开发身份后声称安全公网部署 |
| A23 | 文档漂移 CI | progress 166 已实现 Git 模块表生成/CI 校验及 README 本地链接检查，5 项测试通过 |
| A24 | LICENSE / SECURITY.md | SECURITY.md 已新增；Rust 标 MIT、TS SDK 标 UNLICENSED，统一授权须权利人确认 |

不修改用户未跟踪的 `agent-browser-cloud-before-rewrite.bundle` 和 `agent-browser-cloud/`。
progress 173 新增五个正式 Worker Operation 与十二个 Schema，Protobuf 未变化；公开基线为
245 Operations / 334 Schemas。A11 已关闭，A04 的结构化 Expected Outcome 仍单独跟踪。
progress 172 新增两个可选正式 API Schema，Protobuf 未变化；当时公开基线为
240 Operations / 322 Schemas。
progress 171 只增加内部 PostgreSQL 账本与稳定失败码，公开 API/Protobuf 未变化；A09 已关闭，
该切片本身不证明业务 Outcome；A11 后由 progress 173 关闭，当时独立跟踪的 A04/A20 后由
progress 174/186 关闭。

## 参考

- [Python threading.Event](https://docs.python.org/3/library/threading.html#event-objects)：
  心跳线程停止与租约丢失通知使用独立 Event，不以线程抛异常代替主流程取消信号。
- [Compose secrets](https://docs.docker.com/reference/compose-file/services/#secrets)：
  后续部署不能假定 file-backed secret 的 uid/gid/mode 能由 Compose 重映射。
