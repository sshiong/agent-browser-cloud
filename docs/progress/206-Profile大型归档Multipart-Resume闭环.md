# Profile 大型归档 Multipart Resume 闭环

> 日期：2026-09-22
> 状态：大型加密 Profile Cold Archive 的 Multipart Resume、重启续传和过期 Orphan 回收仓库代码项已完成；跨 Region Restore 与目标云 KMS/IAM 仍未完成
> 实现提交：`757f4c8 feat: resume large profile multipart uploads`

## 本轮关闭的缺口

原 Profile Cold Archive 无论大小都使用单次 PUT。网络中断或 Storage Helper 重启后只能重新压缩、
重新随机加密并从零上传；大 Profile 会重复消耗带宽、对象存储请求和恢复时间，也没有 Upload ID
账本可证明哪些分段已经安全落到服务端。

本轮在隔离 Storage Helper 内增加 S3-compatible Multipart Resume：

- 加密归档超过 8 MiB 时进入 multipart，每段 8 MiB，最多 10,000 段，并继续受 1 GiB Profile
  归档上限约束；小归档保持既有 Single PUT 兼容路径；
- 随机 Envelope Encryption 的结果先写入 Node 本地私有 spool，journal 再原子、fsync 持久化；
  重启必须重用完全相同的密文字节，不能重新加密后沿用旧 Upload ID；
- journal 精确绑定 Tenant、Profile、Checkpoint、对象 Key、明文/密文 SHA-256 与大小、加密 Key ID、
  Part Size、Upload ID、逐段 offset/size/SHA-256/ETag 和更新时间；跨 Checkpoint、路径逃逸、重复或
  畸形分段均 fail-closed；
- 每段成功后才更新本地 journal。恢复时先调用 ListParts，只信任同时匹配服务端 ETag、本地边界和
  本地分段 Hash 的记录；缺失或不可信段重新上传，可信段不重复传输；
- Complete 前按顺序构造精确 CompletedPart 列表。完整对象必须再通过 HEAD 的字节数和
  `browsercloud-sha256` 元数据复验；随后才写 `manifest.json` 和最终 `COMMITTED`，最后删除本地
  spool/journal。若 Complete 已生效但响应丢失，下次重试可由 HEAD 识别完整对象并继续 commit-last；
- 最近 24 小时且仍有本地 journal 的 Upload ID 在服务端扫描时受保护。超过 24 小时的本地任务会先
  Abort 旧 Upload、删除旧密文 spool，再以新密文和新 Upload ID 重建；没有本地账本的服务端
  multipart orphan 也会按 initiated 时间清理；
- 目标 AWS Terraform Bucket 的 `abort_incomplete_multipart_upload` 从 7 天收敛到 1 天，覆盖
  CreateMultipartUpload 已在服务端生效但响应丢失、Node 永不再访问该 Profile 的不可知 Upload ID。

## 安全与一致性边界

- 本地只暂存已经 Envelope Encryption 的归档，不新增明文 Profile 副本；spool 和 journal 使用现有
  Storage Helper 私有目录、原子写和目录同步机制；
- 服务端 ETag 只证明服务端分段身份，不能替代本地 SHA-256、精确 offset/size 和最终对象 Hash；
- Archive、Manifest 与 `COMMITTED` 仍严格 commit-last。上传失败、超时或 Helper 崩溃不会产生
  可恢复的伪提交；本地已提交 Checkpoint 保持可重试；
- 旧版本没有 multipart journal，不需要读取新格式；外部 OpenAPI、Proto、数据库 Schema 和四语言
  SDK 均未改变，滚动升级兼容边界保持不变；
- 本闭环不证明跨 Region 复制、目标云 KMS/HSM、Workload Identity、IAM Policy-as-Code、目标 CSI
  或多 Node Linux 长稳已经完成。

## 验收证据

- Storage Helper 单测验证 journal 与 Checkpoint/对象 Key/明密文 Hash 的精确绑定，并拒绝篡改的
  Part offset；执行测试 21 项通过，真实对象存储用例按条件独立运行；
- OrbStack 真实 MinIO GameDay 使用 11 MiB 不可压缩 Profile：第一段后注入中断，创建全新 Helper
  实例恢复；第二次在第二段后再次中断，证明 Upload ID 未变化且已完成段从 1 增至 2；随后完成对象、
  Manifest 和 COMMITTED 并清除本地账本；
- 同一真实 MinIO 用例创建另一 Checkpoint，将 journal 人工老化到保留期外；下一 Helper 先终止旧
  Upload，再生成不同 Upload ID 并成功完成，输出
  `OBJECT_STORAGE_GAMEDAY_OK ... multipart_resume=true ...`；
- Rust Workspace 全量测试、全目标严格 Clippy、N/N−1 Gate、Terraform Module 契约测试通过；
- OrbStack 完整 PostgreSQL/Redis/MinIO/mTLS/Chromium Integration 通过，包括 Storage Helper
  故障恢复、Profile 加密导入导出、应用感知 Warm Tier、Recording 物理删除和审计链；
- `make ci` 完整通过，公开契约保持 253 Operations / 350 Schemas；
- OrbStack `make docker-build` 七个镜像全部通过；Browser Node 在干净 release 容器内完成
  AWS S3/SQLite/LevelDB 与本次 Storage Helper 代码编译，录像隐私扫描器功能自检通过。

## 仍未完成

1. 真实跨 Region Profile Restore、复制延迟/带宽/一致性证书与网络分区演练；
2. 目标云 KMS/HSM、Workload Identity、IAM Policy-as-Code、CSI 断盘和多 Node Linux 长稳；
3. Warm Tier 长期日志合并/垃圾回收，以及 Profile 对象保留期与目标云 Legal Hold/Object Lock 深度联动；
4. 目标 AWS/S3-compatible 账户的真实 Apply、权限最小化和生命周期执行证据。

因此，本进度关闭用户目标第 6 项中的 Multipart Resume 代码缺口，但不把跨 Region、目标云密钥与
身份基础设施或正式生产验收描述为完成，也不改变“尚未通过 V16 全量生产发布 Gate”的产品结论。
