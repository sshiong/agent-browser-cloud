# Recovery GameDay 治理报表、趋势与整改工单闭环

> 状态：仓库内代码、正式契约、真实 PostgreSQL 集成和 Web/Tauri 共用 UI 已闭环；目标云
> Controller、真实跨 Region 演练及外部发布/Pager 联动仍属于生产 Gate
> 日期：2026-08-09

## 本轮关闭的问题

进度 111 已完成自动 GameDay Runner、故障注入和强制恢复，但运营人员仍缺少可分页的
不可变事件、可验签报告、场景趋势和失败后的整改责任闭环。本轮新增 V078 治理模型，将
一次演练从“有执行证据”推进为：

```text
GameDay 不可变事件
→ Keyset 分页审阅
→ 生成 SHA-256 + HMAC-SHA256 签名报告
→ 失败/中止自动创建 Remediation Ticket
→ ACKNOWLEDGED → RESOLVED
→ 90 天场景趋势与未关闭工单聚合
```

## 已完成

1. `V078__recovery_gameday_governance.sql` 新增事件倒序游标索引、不可变签名报告和一场
   演练一张整改工单；对已有 `FAILED/ABORTED` GameDay 自动回填工单，保持升级兼容。
2. 事件 API 使用 `(occurred_at, event_id)` Base64URL Keyset Cursor，单页最多 200 条，
   Cursor 绑定 GameDay ID；错误、跨 GameDay 和篡改 Cursor 均返回 400，不使用 Offset。
3. 报告从 PostgreSQL 权威 Run、Job、完整事件时间线和整改工单生成，保存规范 JSON、
   SHA-256、HMAC-SHA256、Key ID 与生成者；导出记录不可变，前端不自行拼接或伪造。
4. 自动 Runner 结果未达到目标，或 Job 最终失败/中止时，服务端幂等创建 `P1/P2/P3`
   整改工单；状态只允许 `OPEN → ACKNOWLEDGED → RESOLVED`，确认必须指定 Owner，关闭
   必须填写 Resolution，每次变更进入签名 Audit 链。
5. 新增 90 天可配置趋势，按场景和环境返回通过、失败、中止、Recovery 未确认、通过率、
   P95 RTO/RPO 和未关闭工单数；聚合直接读取 PostgreSQL，不在浏览器生成假趋势。
6. 企业运营页新增最新 GameDay 事件时间线、签名报告生成/校验元数据、场景趋势和整改
   工单状态；Platform Admin 可在原位确认归属并填写关闭证据，其他角色只读。组件和
   API Client 位于共享 React Web 层，可被 Tauri 2 直接复用。
7. 正式 OpenAPI 与 TypeScript/Python/Go/Java SDK 同步为 180 个 Operation、238 个公开
   Schema；TypeScript 为 290 个服务方法、32 个服务和 255 个 Model 文件。

## 验收证据

- Control Plane 全量单测通过，包含 Cursor 往返、畸形 Cursor 和跨资源 Cursor 拒绝。
- Web 21 个测试文件、67 项测试、Lint 和生产 Build 通过。
- Redocly OpenAPI、四语言确定性 SDK 生成/测试和 V078 N/N−1 additive Gate 通过。
- 完整 PostgreSQL/Redis/MinIO/双 Control Plane/Browser Node Integration 通过，真实验证：
  事件两页无重复、篡改 Cursor 400、报告 HMAC 验签、报告读取、失败自动建单、确认/关闭、
  场景趋势和四条签名 Audit 事件。

## 仍未完成

1. 在目标云部署固定场景的最小权限故障 Controller，并完成真实数据库、对象存储、Proxy
   和 Region 故障权限/凭据/Blast Radius 证书。
2. 完成真实跨 Region Authority、数据库与对象复制、DNS/流量切换和长期 RTO/RPO 演练。
3. 将连续失败、Recovery 未确认和未关闭 P1 工单接入外部生产发布系统、Pager/工单平台
   与组织值班流程；仓库内当前只提供权威 API、Audit 和 Release Freeze Gate。
4. 目标云生产报告需要 KMS/HSM 非对称签名、密钥轮换与长期归档；本地验收使用可替换的
   HMAC Key，不能替代生产密钥治理。
