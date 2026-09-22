# Agent Browser Cloud Terraform

`modules/agent-browser-cloud` 交付 Phase 7 的 AWS 基础设施模块，复用已有 VPC/EKS，并创建：

- 相互隔离的 Control Plane 与 Browser Node EKS Node Group；
- Browser Node 专用 Taint、标签、IMDSv2 和 Cgroup v2 委派目录；
- Multi-AZ Aurora PostgreSQL、加密 Redis Cluster；
- KMS Multi-Region Key 与禁止公网访问、版本化且启用 Object Lock 的 S3 Archive Bucket；
- 单节点滚动上限和独立 Browser Node 实例类型。

模块不会创建或放宽公网入口、安全组或 IAM Role。调用方必须提供最小权限 Role、私有
Subnet 及仅允许 Control Plane 访问的 PostgreSQL/Redis Security Group。

Archive Bucket 创建时永久启用 Object Lock，默认对每个新对象版本应用 30 天
`COMPLIANCE` 保留。生产 Control Plane 的
`RECORDING_OBJECT_LOCK_POLICY_MINIMUM_RETENTION_DAYS=31` 必须与模块输出
`recording_policy_minimum_retention_days` 保持一致；额外一天覆盖 Segment 上传时间、Recording
结束时间和时钟偏差。业务 Retention 可以更长，但不能短于该下限。

Browser Node/Storage Helper 的 IAM Role 不得包含 `s3:BypassGovernanceRetention`、
`s3:PutObjectRetention`、`s3:PutObjectLegalHold` 或 Bucket Object Lock 配置修改权限。默认
`COMPLIANCE` 模式本身也不能通过 bypass 提前删除；若组织显式改为 `GOVERNANCE`，必须由独立
Policy-as-Code 证明运行时 Role 没有 bypass 权限。Object Lock 只能保护对象版本，目标云原生
Legal Hold 的设置/解除仍必须由独立治理身份和审批链完成。

到期物理删除需要只对 Archive Bucket 授予 `s3:ListBucketVersions` 和
`s3:DeleteObjectVersion`；Storage Helper 会列举 Recording 前缀的所有数据版本与 Delete Marker，
逐个携带 Version ID 删除并在写入删除证明前再次确认版本列表为空。只授予普通
`s3:DeleteObject` 会在版本化 Bucket 中留下受保护的历史版本，不能满足物理删除语义。

验收：

```bash
terraform -chdir=deploy/terraform/modules/agent-browser-cloud fmt -check
terraform -chdir=deploy/terraform/modules/agent-browser-cloud init -backend=false
terraform -chdir=deploy/terraform/modules/agent-browser-cloud validate
```

生产 Apply 必须经过目标账户 Plan 审阅、Policy-as-Code、成本审批和恢复演练；本仓库
不会在本地或 CI 自动执行云资源变更。
