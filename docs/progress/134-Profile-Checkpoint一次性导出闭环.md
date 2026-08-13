# Profile Checkpoint 一次性导出闭环

> 日期：2026-08-13
> 状态：仓库内核心闭环已完成

## 本轮关闭的缺口

Profile 页原先只能创建和导入 Checkpoint，没有可审计的真实导出通道。本轮增加：

- `POST /api/v1/profiles/{profileId}/export-grants`：为当前管理员和精确最新
  Checkpoint 创建五分钟、用途绑定的授权；
- `POST /api/v1/profiles/{profileId}/export-grants/{grantId}:redeem`：仅允许原
  Actor 兑换一次，且兑换时 Profile 不得已前进到新 Checkpoint；
- V095 `profile_export_access_grants` 仅保存 Grant、用途、Actor、Checkpoint、
  签名 Node、归档哈希/大小和结果，不保存临时 URL 或对象存储凭据；
- Control Plane 只会选择声明 `profileExport=presigned-checkpoint-v1` 的新版健康
  Node，N−1 Node 不会被误调度；
- Browser Node 通过 Helper IPC 请求签名，S3 凭据始终只属于 Storage Helper；
- Storage Helper 在签名前重新验证 `COMMITTED` Marker、Checkpoint ID、SHA-256、
  声明大小、HEAD 大小和最大 256 MiB 边界，再流式读取完整对象重算哈希；
- 校验成功后只签发 60 秒 GET URL，Control Plane 完成审计后将 URL 仅放入当次
  HTTPS 响应。

## Web / Tauri 共享 UI

`ProfileExportDialog` 使用现有 React API Client、身份与平台适配层，Web 和 Tauri
复用同一份业务组件：

- 只有管理员可见导出入口；
- 必须选择租户备份、事件响应、支持诊断或合规导出用途；
- 明示 Cookie、登录态和站点数据风险，需要再次确认；
- 写操作等待真实 Grant/Redeem 响应，不伪造进度或下载结果；
- 展示真实归档大小和 SHA-256，失败展示后端 Request ID。

## 迁移和回滚边界

V095 为 expand-only 新表、新索引、新触发器与独立约束，约束按
`NOT VALID -> VALIDATE` 显式生效，不改写旧表字段或删除旧数据。N−1 应用会忽略新表。
回滚时关闭 API/UI 并不再发布 Node 能力标签，已生成的有效对象不需要破坏性
DDL；已签名 URL 会在 60 秒内自然失效。

## 验收证据

- Control Plane `spotlessCheck` 及 `ProfileExportGovernanceServiceTest` 通过；
- Browser Node / Helper Contracts / Helper Client / Storage Helper 定向测试通过；
- Web Lint、Prettier、71 项单测与 Production Build 通过；
- Buf/OpenAPI 契约校验和 N/N−1 Gate 通过；
- TypeScript/Python/Go/Java SDK 验证为 199 Operations / 268 Schemas；
- `make test-object-storage` 用真实 MinIO 验证 commit-last、对象完整性与签名 GET；
- `make test-integration` 启动真实 PostgreSQL、MinIO、Control Plane、Browser Node 和
  Storage Helper，验证本地生成的 `EMPTY` Checkpoint 可导出、跨 Actor 拒绝、
  一次性兑换、下载字节/哈希一致与 PostgreSQL 结果投影，输出
  `profile_checkpoint_export=true`、`health=UP`、`public_tables=109`、
  `audit_chain_valid=true`。

## 仍未完成

1. Warm Tier Delta Journal 与 Multipart Resume；
2. 真实跨 Region Profile Restore 和全局带宽/一致性证书；
3. Profile 对象保留期、Legal Hold 和对象锁的深度联动；
4. 目标云 KMS/IAM、外部 HSM 与正式密钥轮换演练；
5. 目标 Linux 多 Node 长稳、正式云对象存储故障和跨 Region 网络分区矩阵。
