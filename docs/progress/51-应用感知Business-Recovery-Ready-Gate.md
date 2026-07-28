# 应用感知 Business Recovery 与 Ready Gate

> 日期：2026-07-28
> 状态：声明式契约、PostgreSQL 权威状态、迁移 Ready Gate、有界低风险自动动作、
> API/Web 展示和真实集成已完成；站点专用 Adapter、Provider 级证明、契约作者 UI、
> 受信 Extension 重启已在 V039/进度 61 完成；目标双 Node 长稳仍待完成

## 目标和边界

通用 Browser State 只能判断页面是否可访问、是否疑似回到登录页，不能证明 CRM、
支付、账号安全等业务状态已经正确恢复。本轮增加 Tenant/Application 级恢复契约，让
迁移在恢复 Agent 前校验当前页面的应用语义。

契约是有界声明式 DSL，只支持：

- 精确 `http/https` Origin；
- Route Prefix；
- 可访问性 Target 的精确 Role/Name；
- Session Placement 中必须存在的 Extension ID；
- `COMPLETE` 或显式允许的 `DEPTH_LIMITED` State Quality。

Control Plane 不执行租户 JavaScript、正则表达式或远程脚本，也不把启发式结果描述为
业务证明。未配置应用契约的 Session 继续使用保守的默认 Validator。

## 已完成

### PostgreSQL 权威模型

- V030 新增版本化 `application_recovery_contracts`、
  `session_application_bindings` 和 append-only
  `business_recovery_validations`。
- 契约按 `(tenant_id, application_id)` 唯一；Session 创建时在同一事务绑定启用的
  契约，不使用 `localStorage`、JSON 文件或进程内状态。
- 绑定和校验记录通过 `(contract_id, tenant_id, application_id)` 组合外键锁定租户
  边界；当前 Context Epoch 和 State Version 随判定持久化。
- Verdict 明确区分 `READY`、`READY_WITH_WARNING`、`LOGIN_REQUIRED`、
  `PERMISSION_CHANGED`、`ACCOUNT_MISMATCH`、`APPLICATION_UNAVAILABLE`、
  `STATE_CHANGED` 和 `MANUAL_RECOVERY_REQUIRED`，不只依赖颜色表达。

### API、迁移和 Web

```text
GET  /api/v1/applications/recovery-contracts
GET  /api/v1/applications/{applicationId}/recovery-contract
PUT  /api/v1/applications/{applicationId}/recovery-contract
POST /api/v1/sessions/{id}/business-recovery:validate
GET  /api/v1/sessions/{id}/business-recovery
```

- 契约写入要求 Admin，手动校验要求 Operator，所有读取和写入都强制 Tenant 隔离。
- `expectedVersion` 提供乐观并发控制；相同配置的旧版本重放返回当前结果，不重复写入。
- Session 创建请求新增可选 `applicationId`。Web 创建向导从真实契约 API 选择应用；
  未选择时不伪造绑定。
- 迁移 `BUSINESS_VALIDATION` 阶段调用同一应用感知服务。只有持久 Verdict 的 `ready=true`
  才进入 `COMPLETED` 并恢复 Agent；Login、权限变化、账号不一致、未知或证据缺失均
  fail-closed。
- Session 详情新增 Business Recovery 卡片，展示 Verdict、Application、契约版本、
  Context/State Version、Evidence、Request ID，并支持真实幂等手动校验。

## 验证

- Control Plane 全量测试通过，覆盖契约规范化、非法 Origin、版本冲突/语义重放、
  READY/Login、旧 Context 拒绝、Extension 证据不可用 fail-closed 和 CDP 无名 Target
  null-safe 回归。
- Web 9 个测试文件、36 项测试、Lint 和生产 Build 通过。
- OpenAPI Redocly 校验零告警；V030 进入 N/N-1 additive Gate，证据 Hash：
  `e89f8aa0a151d728bc5734c0e98af151cefe4985e4af0979454ed2ad204680d1`。
- PostgreSQL 17 + Redis + Browser Node + 真实 Chromium 的 `make test-integration`
  通过，实际验证契约规范化、版本重放、跨 Tenant 404、Session 事务绑定、真实
  Browser State READY、校验幂等重放、最新结果读取、单条持久记录，以及迁移 Ready
  Gate 继续完成。
- 集成首次运行发现 CDP Target `name=null` 会触发 500；现已改为 null-safe 精确匹配并
  加入回归测试。这一结果说明验收使用的是真实状态树，不是前端或测试 Mock 曲线。

## 仍未完成

1. 各目标网站的契约作者 UI、支付/账号安全/SPA Adapter、可信业务埋点和 SDK 包装；
   平台仍不会自动理解任意网页业务语义。
2. `maximumAutoRecovery` 已在 V034 接入持久尝试预算、Reload/Refresh/受限导航、
   Node State ACK 与二次 Ready Gate，详见
   [进度 56](56-Business-Recovery有界自动动作闭环.md)。当前仍不自动点击或终止，
   受信 `RESTART_EXTENSION` 动作已在
   [进度 61](61-受信Extension自动恢复动作闭环.md)实现。
3. Account、Permission 和 Business Entity 的 Provider/API 级证明；当前仅支持契约中
   配置的 Route/Target/Extension 证据。
4. 两个真实 Browser Node + S3-compatible Object Storage 的迁移并发、网络分区、
   源/目标故障注入和长期稳定性证书。
5. State/Audit/Agent Step 统一事件层与跨 Region Event Bus；当前最新结果通过正式 API
   读取，资源 SSE 会触发相关查询失效刷新，但没有独立 Business Recovery 事件流。
