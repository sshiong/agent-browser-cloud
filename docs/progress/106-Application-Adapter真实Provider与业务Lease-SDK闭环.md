# Application Adapter 真实 Provider 与业务 Lease SDK 闭环

> 完成日期：2026-08-08
> 状态：通用、最小权限 Application Adapter 运行组件、真实 HTTP Provider 调用、
> Provider Evidence 提交、业务 Lease SDK/CLI、签名发布制品入口和完整集成已完成；
> 目标客户 CRM/支付/IAM 凭据、字段映射与生产长稳仍是环境接入 Gate。

## 本轮关闭的缺口

此前平台已经具备 Provider Evidence 协议，但真实 Adapter 仍需自行解决凭据读取、
Provider 请求、字段提取、哈希、幂等和 Lease 调用；同时 `APPLICATION_ADAPTER` 实际
不能调用 Safety Lease API，Integration 用 `TENANT_OPERATOR` 代替，掩盖了权限断点。

本轮新增独立 `apps/application-adapter`：

- 通过真实 HTTPS GET 调用受控 CRM、支付或 IAM JSON API；
- 使用 RFC 6901 JSON Pointer 选择账号、Workspace、权限或业务实体值；
- Adapter 内部按确定性编码计算 SHA-256，只向 Control Plane 发送哈希；
- Provider 原始响应、原始业务值和 Bearer Token 不写文件、不打印、不提交到平台；
- `MATCH/MISMATCH` 由观察值哈希与已批准 Contract Revision 的期望哈希决定；
- 同一份观察使用完全相同的请求和幂等键安全重试，新观察使用新键；
- 提供 `lease-acquire/lease-renew/lease-release` SDK/CLI，覆盖文件传输、表单、支付/
  账号安全、关键事务和恢复未知状态。

## 安全边界

- Control Plane 与 Provider Token 只从权限不宽于 `0600`、最大 16 KiB 的挂载文件读取，
  可由 Secret Manager CSI 或短期 Workload Identity Token 投影提供；
- Provider Host 必须显式 Allowlist，拒绝 URL Userinfo、Fragment、Redirect、超大响应和
  非 JSON 响应；不继承环境 Proxy，超时最多 30 秒，响应最多 1 MiB；
- 默认只允许 HTTPS；HTTP 和本地身份 Header 只有 `APP_ENVIRONMENT=local|test` 时可用；
- 本地身份角色固定为 `APPLICATION_ADAPTER`，调用者不能改成 Admin；
- 新的 `PlatformRoles.APPLICATION_SIGNAL` 仅允许 Adapter 写入 Safety Lease，未把
  `APPLICATION_ADAPTER` 加入通用 `OPERATE`，因此不能获得 Session 运维能力；
- Provider/Control Plane 错误只暴露稳定错误码，不回显响应正文或 Token。

## 真实集成路径

Integration Smoke 启动独立 CRM-like HTTP Provider，要求正确 Bearer Token，并返回
`account-42` 与 Provider Request ID：

```text
真实 Browser/State 进入 BUSINESS_VALIDATION
→ 平台因缺 Provider Evidence 保持 MANUAL_RECOVERY_REQUIRED
→ Application Adapter 读取 0600 Provider Token
→ Allowlist Provider GET /api/v1/me
→ JSON Pointer /account/id
→ 本地 SHA-256，不上传 account-42
→ APPLICATION_ADAPTER 提交 MATCH Evidence
→ Ready Gate 重新校验
→ Migration COMPLETED / recoveryResult READY
```

同一套 Smoke 还以真正的 `APPLICATION_ADAPTER` 身份完成 Lease acquire、owner-bound
renew/release、Safe Point 阻断和统一 Session SSE，替换了此前错误使用的
`TENANT_OPERATOR` 测试身份；同时实际请求通用 Resource Policy Mutation 并断言 `403`，
证明新增角色没有获得 Session 运维权限。

## 发布供应链

- 新增无第三方 Python 依赖的非 Root Container；
- 主分支 CI 会实际构建 Adapter Container，Tag Release 再构建并推送正式镜像；
- Release Pipeline 构建 `agent-browser-cloud-application-adapter`；
- 生成 SPDX SBOM、Cosign Keyless Signature 和 SBOM Attestation；
- Release Bundle Schema 升级为 v3，将 Adapter Digest 与 Evidence 纳入签名 Manifest；
- `make test-application-adapter` 和 `make test` 固定执行 Adapter 测试。

## 可重复验收

已通过：

```text
python3 -m unittest discover -s apps/application-adapter -p 'test_*.py' -v
make contracts-check
make supply-chain-check
make test-integration
```

- 11 项 Adapter 测试覆盖私密 Token 文件、JSON Pointer、确定性哈希、Host Allowlist、
  Redirect 拒绝、响应上限、真实 Provider Bearer、哈希化 Evidence、MISMATCH fail-closed、
  同观察幂等重试、固定本地 Adapter 身份和 Lease 路径；
- OpenAPI 校验通过；Release Bundle 正向验证及 Tag/Tamper/SBOM 篡改反例通过；
- PostgreSQL/双 Control Plane/Browser Node/Chromium/MinIO 完整 Integration 通过，输出
  `application_safety_lease=true`、`application_business_recovery=true` 和
  `dual_node_migration=true`。

## 仍未完成

1. 为具体客户配置 CRM、支付、IAM 正式 Endpoint、短期 Token 投影、CA 与 JSON Pointer；
2. 目标业务在支付、账号安全、SPA/Form 和关键事务开始/结束位置接入 Lease SDK；
3. OAuth Token 自动刷新、mTLS Provider 认证以及 Provider 特有分页/签名协议；
4. 目标 Region 的 Provider 限流、DNS、网络分区、凭据撤销和长期稳定性证书；
5. 这些真实接入必须使用客户批准的 Staging 凭据，仓库 Fixture 不能替代生产证明。

因此，“平台只有 Evidence 接口，没有可部署 Adapter/Lease SDK”和“Adapter 角色不能写
业务 Lease”已不再是仓库代码缺口；具体 Provider 凭据与站点映射仍不能虚构完成。
