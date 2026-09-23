# Proxy 站点 Challenge 信号与临时隔离闭环

> 日期：2026-09-23
> 数据库版本：V131
> 状态：仓库内代码、契约、N/N−1 与真实 OrbStack Integration 闭环

## 本轮目标

把 Challenge Detection 形成独立于普通任务成功率的 Proxy 风险证据，并在调用方明确提供目标
站点时阻止已被该站点持续挑战的出口继续自动或显式分配；不能把 OTP、支付确认、单次 CAPTCHA
或普通任务失败误判为站点黑名单。

## 实现

- V131 新增 append-only `proxy_route_site_challenges`。只有置信度至少 0.9、已确认且属于
  `SINGLE_CLICK / OPAQUE_FRAME_SINGLE_CLICK / IMAGE_SELECTION / PUZZLE / MULTI_ROUND`
  的反自动化事件可写入；OTP、设备确认、支付和用户判断固定排除。
- 每条证据必须关联事件发生前已经存在的精确 Session Proxy Assignment，并固化当时的
  Binding/Provider；Challenge Event ID 幂等。账本只保存规范化站点域名的 domain-separated
  SHA-256，不保存 URL、Path、Query、页面内容、Goal、Credential 或模型输出。
- 新增可选 `CreateSessionRequest.proxyRoutingDomain`。它只接受裸 Hostname，URL、UserInfo 和非法
  IDN fail-closed；OpenAPI 与 TypeScript/Python/Go/Java SDK 已同步，旧客户端省略字段不受影响。
- 同一 Tenant/Site/Binding/Provider 在最近 30 分钟内必须来自至少三个不同 Session，才会成为
  临时隔离信号。单个 Session 重复触发不会达到门槛，也不会形成永久封禁。
- AUTO 路由在原有启用、健康新鲜度、Region、Provider 身份、容量和质量硬门槛之前排除被隔离
  候选；显式 Binding 同样拒绝并返回 `PROXY_BINDING_QUARANTINED_FOR_SITE`。若 Provider 已更换，
  旧 Provider 的证据不传播到新身份。
- Assignment 保存站点提示哈希以支持选择复现，Audit/Candidate Snapshot 只暴露布尔提示存在性和
  有界计数，不暴露原始站点。

## 验证证据

- Control Plane 全量测试通过；定向测试覆盖 IDN 规范化、拒绝 URL、三 Session 阈值、内部 Scheme
  排除、Domain Hash 最小化、Challenge→Proxy 交接、Provider 变化隔离以及 AUTO 候选排除。
- OpenAPI/Redocly 与四语言 SDK 生成、编译和运行时包测试通过；公开基线保持
  **255 Operations / 354 Schemas**。
- V131 N/N−1 Gate 验证迁移 expand-only、约束在线验证和新请求字段 optional。
- OrbStack 完整 Integration 在真实 PostgreSQL 17 中写入三个不同 Session 的站点 Challenge
  证据，第四个带相同站点提示的显式 Binding 创建请求返回 409 和精确隔离原因；账本敏感列检查为
  零，明确输出 `proxy_site_challenge_quarantine=true`。其后 Agent、Outcome、Recording、Profile、
  Recovery、Resource 和审计全套链路继续通过。

## 保留边界

- 没有 `proxyRoutingDomain` 时不会根据猜测的未来站点隔离出口；调用方需要在创建环境时提供已知
  目标 Hostname。运行中跨站导航不会擅自热切换网络身份，仍应在 Safe Point 休眠后受控 Rebind。
- 此策略是短时相关性隔离，不宣称 Challenge 一定由 Proxy 导致；不会永久封禁，也不会覆盖人工
  风险判断。
- 商业 Provider 认证 Adapter、目标云 Secret Manager、供应商账单、真实多 Region 容量和客户
  站点 SLA Replay 仍属于后续代码或目标环境 Gate。
