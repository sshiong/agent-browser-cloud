# Recording Object Lock/WORM 生产基线

> 日期：2026-09-22
> 范围：AWS Terraform Archive Bucket、Control Plane 保留期围栏、真实 MinIO WORM Gate

## 结论

仓库级 Object Lock/WORM 基线已经闭环。AWS Archive Bucket 在创建时永久启用 Object Lock 和
Versioning，并默认为每个对象版本设置 30 天 `COMPLIANCE` 保留。生产 Control Plane 必须配置
至少 31 天的 Recording 策略下限；系统启动、企业 Retention Policy 写入和 Recording 默认保留期
三处共同 fail-closed，避免业务删除队列在对象仍不可变时反复尝试删除。

这项结论只证明仓库定义和本机兼容对象存储行为，不代表任何目标 AWS 账户已经执行 Terraform
Apply，也不代表目标云 IAM、原生 Legal Hold 或监管审批已验收。

## 实现

1. `aws_s3_bucket.archive` 创建时设置 `object_lock_enabled = true`，独立 Versioning 资源保持
   `Enabled`；`aws_s3_bucket_object_lock_configuration` 在 Versioning 后建立默认保留规则。
2. 默认模式为 `COMPLIANCE`、默认期限为 30 天，变量只接受 `COMPLIANCE/GOVERNANCE` 和
   1—3650 的整数天数。模块输出保留模式、对象保留天数和 Control Plane 所需的 `+1` 天策略下限。
3. Kubernetes Control Plane 设置
   `RECORDING_OBJECT_LOCK_POLICY_MINIMUM_RETENTION_DAYS=31`。生产环境若该值为零则拒绝启动；
   `REMOTE_DESKTOP_RECORDING` Retention Policy 低于下限时以稳定治理错误拒绝。
4. 新 Recording 的默认保留期取 30 天与 Object Lock 策略下限的较大值。已有更长业务保留期不被
   缩短；本地和测试环境默认保持零下限，不冒充 WORM 部署。
5. 真实 Versioning Gate 发现普通 `DeleteObject` 只会建立 Delete Marker、旧版本仍保留。Storage
   Helper 现通过 AWS S3 版本 API 分页列出 Recording 前缀的所有数据版本与 Delete Marker，验证
   每个 Key 仍属于 Manifest 有界集合，逐一携带 Version ID 删除；当前对象列表和版本列表均为空
   后才提交删除证明。独立 Helper 的 Tokio worker stack 显式限制为 8 MiB，以承载有界 S3 版本
   响应解析，并由真实进程 Integration 覆盖。
6. Terraform 文档明确运行时 Browser Node/Storage Helper Role 不得具有 Governance bypass、
   修改对象 Retention、Legal Hold 或 Bucket Object Lock 配置的权限。

## 验证

```text
./gradlew -p apps/control-plane spotlessApply test \
  --tests io.browsercloud.application.RecordingObjectLockPolicyTest \
  --tests io.browsercloud.application.SessionRecordingApplicationServiceTest
make test-terraform-module
orbctl status
docker context show
docker info --format 'Name={{.Name}} OS={{.OperatingSystem}} Server={{.ServerVersion}}'
make test-object-storage
make test-integration
docker build -f apps/browser-node/Dockerfile \
  -t agent-browser-cloud-browser-node:worm-version-delete-test .
```

定向 Java 测试验证生产零下限拒绝、31 天策略围栏及新 Recording 默认到期时间；Terraform 1.9.8
使用 AWS provider 5.100.0 完成 `fmt -check/init/validate`，契约测试验证 Object Lock、Versioning、
COMPLIANCE 默认值、依赖顺序、输出与 Kubernetes 配置一致。真实 OrbStack MinIO Gate 创建启用
Lock 的 Bucket、上传对象并确认提前删除失败且对象仍存在；完整 Integration 在启用 Object Lock/
Versioning、但不施加未到期保留的 Bucket 中验证 25 段对应 51 个当前对象及其全部历史版本均被
物理删除，只有版本列表为空后才形成 Receipt；同一 Gate 也验证历史未版本化 Bucket 兼容。固定
Rust 1.98 的 Browser Node release Docker 镜像已在 OrbStack 完整构建。关键输出：

```text
OBJECT_STORAGE_GAMEDAY_OK ... legacy_unversioned_compatible=true compliance_worm_delete_rejected=true
recording_retention_versioned_physical_deletion=true
```

## 剩余边界

- 目标 AWS 账户的 Plan/Apply、最小权限 IAM 与 Policy-as-Code、恢复和到期删除演练仍是生产 Gate。
- 目标云原生逐对象 Legal Hold 设置/解除需要独立治理身份、审批和审计链，不能由数据库布尔值替代。
- Recording 全帧 OCR 和非文本视觉敏感分类已在后续
  [progress 203](203-Recording全帧OCR与非文本视觉隐私闭环.md)闭环；客户视觉数据集 Replay 仍是生产 Gate。
- `COMPLIANCE` 期限内无法提前删除是刻意的监管语义；部署方必须在成本和法规审阅后选择期限。
