# Terraform Provider 正式 API、协议与签名发布闭环

> 日期：2026-08-11
> 状态：仓库内实现与验证已完成；公开 Registry 接入仍是组织 Gate

## 本轮完成

在 `deploy/terraform/provider` 新增独立 Go 1.22 Terraform Plugin Framework Provider，
通过 Protocol 6 暴露：

- `browsercloud_group`：正式 Control Plane API CRUD、Import，以及 Session 成员精确收敛；
- `browsercloud_tag`：正式 Control Plane API CRUD、Import，以及 Session 归属精确收敛；
- `browsercloud_workspace_settings`：读取 PostgreSQL 权威 Workspace 默认值。该 API 没有
  Delete 语义，因此建模为 Data Source，不用空 Destroy 伪造资源生命周期。

生产 Provider 强制 HTTPS Endpoint 和 OIDC Bearer Token。Local Header 身份必须显式设置
`local_development = true`，且只允许 `localhost` 或 loopback IP，同时要求 Tenant/Actor。
Client 不跟随 HTTP Redirect，避免 Authorization 跨源泄漏；响应体上限为 8 MiB，错误只
暴露 HTTP Status、稳定 Error Code 和 Request ID，不输出服务端正文或潜在敏感信息。
所有写请求使用由操作范围与规范 JSON 生成的稳定 SHA-256 Idempotency Key。

## 生命周期与一致性

Group/Tag 创建和更新先提交权威元数据，再以稳定顺序执行成员差异：先移除、后添加，
最终重新读取正式 API 并写回 Terraform State。创建已成功但成员调整失败时保留已创建资源
的部分 State，避免远端孤儿；Read 遇到 404 会从 State 移除，Delete 对重复 404 幂等。

Control Plane 当前没有 Group/Tag 单项 GET，但列表响应是租户范围、无分页的权威全集，
Provider 因此按 ID 从列表读取，不访问 PostgreSQL 或内部 Node/cgroup。

## CI 与发布供应链

- 根 `make build/test/lint/ci` 已纳入 Provider build、race test、Protocol Schema、vet 和 gofmt；
- 主 CI 固定 `actions/setup-go` commit，并按 `go.sum` 缓存；
- tag Workflow 校验 `VERSION` 与不可变 tag 完全一致；
- 由于 GoReleaser OSS 不支持 monorepo component-prefix tag，Workflow 在进入 GoReleaser
  前自行严格校验 `terraform-provider-vX.Y.Z`，再只从已提交 `VERSION` 注入 artifact version；
- GoReleaser 生成 Darwin/Linux/Windows、amd64/arm64、CGO-free 的确定性 ZIP；
- 发布生成 SHA-256 checksum，以组织 Terraform Registry GPG Identity detached-sign；
- GitHub OIDC 为 archives、checksum 和 signature 生成 Build Provenance；
- 所有 GitHub Actions 使用完整 commit SHA，供应链脚本阻断 mutable Action ref、缺签名或
  缺 Provenance 的改动。

## 可重复证据

```text
go -C deploy/terraform/provider test -race ./...
go -C deploy/terraform/provider vet ./...
go -C deploy/terraform/provider build -trimpath -o ../../../build/terraform-provider-browsercloud .
go run github.com/goreleaser/goreleaser/v2@v2.17.1 check
./tests/supply-chain/terraform_provider_release_test.sh
```

## 明确未完成

以下事项依赖组织身份和外部系统，不能由仓库伪造为完成：

1. 创建并拥有满足 Terraform Registry 命名规范的独立 Provider 仓库/Namespace；
2. 向 Registry 注册公开 GPG Key，并安全注入对应私钥和 Passphrase Secrets；
3. 开通 Terraform Registry Trusted Publishing，并实际发布/安装 `0.1.0`；
4. 用真实 Staging Token 执行 Terraform apply/import/refresh/destroy 与 N/N-1 升级演练；
5. 完成跨版本兼容、弃用周期、Rollback 和客户变更通知审批。

因此，仓库内 Terraform Provider 代码缺口已经关闭，但公开 Registry 和生产发布 Gate
仍保留在当前未实现清单中。
