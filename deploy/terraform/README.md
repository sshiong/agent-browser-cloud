# Agent Browser Cloud Terraform

`modules/agent-browser-cloud` 交付 Phase 7 的 AWS 基础设施模块，复用已有 VPC/EKS，并创建：

- 相互隔离的 Control Plane 与 Browser Node EKS Node Group；
- Browser Node 专用 Taint、标签、IMDSv2 和 Cgroup v2 委派目录；
- Multi-AZ Aurora PostgreSQL、加密 Redis Cluster；
- KMS Multi-Region Key 与禁止公网访问、版本化的 S3 Archive Bucket；
- 单节点滚动上限和独立 Browser Node 实例类型。

模块不会创建或放宽公网入口、安全组或 IAM Role。调用方必须提供最小权限 Role、私有
Subnet 及仅允许 Control Plane 访问的 PostgreSQL/Redis Security Group。

验收：

```bash
terraform -chdir=deploy/terraform/modules/agent-browser-cloud fmt -check
terraform -chdir=deploy/terraform/modules/agent-browser-cloud init -backend=false
terraform -chdir=deploy/terraform/modules/agent-browser-cloud validate
```

生产 Apply 必须经过目标账户 Plan 审阅、Policy-as-Code、成本审批和恢复演练；本仓库
不会在本地或 CI 自动执行云资源变更。
