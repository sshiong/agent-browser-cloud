# Profile 跨 Region 只读恢复闭环

> 日期：2026-09-23
> 状态：仓库级 Cross-Region Profile Restore 数据路径完成；目标云复制、KMS/IAM、RPO/RTO 与流量切换仍是环境 Gate
> 实现提交：`60e9234 feat: restore profiles from authorized regions`

## 本轮关闭的缺口

此前 Storage Helper 只有一个 S3-compatible 客户端。即使控制面已经有 Region Registry、Residency
和 DR 准入，实际 Profile 恢复仍只能从本地 Region 的同一 Bucket 读取；把 endpoint 手工改到另一
Region 既没有目的地授权，也不能证明读取的是同一 Tenant/Profile/Checkpoint。

本轮增加显式、只读的跨 Region 恢复源：

- 主 Object Storage 仍是唯一写入端，Checkpoint、Recording、Evidence、导出和 Multipart 均不向
  副本写入；只有本地 Checkpoint 与主 Object Storage 都不可用时，恢复链才按配置顺序尝试副本；
- 每个副本配置必须给出唯一 `regionId`、`allowedDestinationRegionIds`、Endpoint、Bucket、Prefix
  和独立凭据文件；Storage Helper 的 `NODE_REGION` 必须在目的地 allowlist 中，否则启动即拒绝；
- 最多允许八个有序副本源。生产环境副本强制 HTTPS，Secret 只能从绝对路径、非符号链接、
  0600/0400 的私有文件读取；配置使用严格 JSON Schema 语义，未知字段 fail-closed；
- 每次候选恢复先读取 `COMMITTED`，再只允许固定的 `checkpoint.tar.zst.enc`；副本上的旧明文
  `TAR_ZSTD` 不允许跨 Region 恢复，也不会触发把迁移结果写回只读副本；
- Archive 大小/SHA-256、加密 Key ID、明文 Archive Hash/大小和 Envelope 内的 Tenant、Profile、
  Checkpoint 均与请求精确一致后才安装到本地，任一损坏或身份替换立即终止，不继续尝试较低优先级
  副本来掩盖完整性错误；
- 失败信息只报告尝试过的 Region ID，不把 Endpoint、Bucket、Object Key 或供应商错误正文带到
  上层。成功日志记录源/目的 Region 和是否使用副本，不记录凭据或对象坐标；
- Kubernetes 基线显式声明 Storage Helper 的 `NODE_REGION`，并提供不含真实 Secret 的副本配置
  示例；具体挂载和启用仍由目标环境 overlay 完成。

## 真实故障演练

`make test-object-storage` 在 OrbStack 中同时启动两个完全独立的 MinIO 实例：

1. 在 primary MinIO 创建并提交一个经过 Envelope Encryption 的 Profile Checkpoint；
2. 将 Archive、Manifest 和 COMMITTED 模拟复制到另一个 MinIO Bucket；
3. DR Helper 的主 endpoint 指向确定不可连接的地址，只给它一个 `primary-region -> dr-region`
   授权的只读恢复源；
4. Helper 从副本恢复同一 Checkpoint，重新打开 Workspace，并逐字节验证 Cookie fixture；
5. 将副本 COMMITTED 的对象名改为路径穿越形式，恢复 fail-closed；
6. 把允许目的地改为其他 Region，配置在创建客户端前即被拒绝。

最终输出包含：

```text
OBJECT_STORAGE_GAMEDAY_OK ... multipart_resume=true cross_region_restore=true ...
```

## 验收证据

- Storage Helper：21 个 library 单测和 7 个 binary 单测通过，两个真实 S3 用例按 Gate 独立执行；
- 全目标严格 Clippy 通过；Rust Workspace 全量测试通过；
- N/N−1 契约 Gate 通过。本切片未修改公开 OpenAPI、Proto、数据库 Schema 或 SDK；
- OrbStack 双 MinIO Object Storage GameDay 通过；
- 完整 OrbStack PostgreSQL/Redis/MinIO/mTLS/Chromium Integration 通过，覆盖跨 Node Profile
  恢复、Helper 故障恢复、加密导入导出、Warm Tier、Recording 和审计链；
- `make ci` 通过，覆盖 Java/Rust/Web、143 项 Web 测试、41 项 Worker 测试、Compose、Personal
  Secure、Terraform、OpenAPI、四语言 SDK、供应链、N/N−1 与容量 Gate；
- `make docker-build` 在 OrbStack 完整构建七个仓库镜像；Browser Node 从最终源码执行干净 Rust
  release 编译，Recording Privacy Scanner 同时通过镜像内功能自检；
- 高负载环境暴露 Integration fixture 的 4 秒进程启动窗口不足：最小 Python fixture 实测约 9.3 秒
  后才获得调度。仅 fixture/Helper readiness 上限增至 20 秒，并保留进程存活检查与失败日志；产品
  Runtime、Agent 动作、网络和 Object Storage 超时未放宽。

## 仍未完成

1. AWS/GCP/其他目标云的真实 Cross-Region Replication Rule、复制延迟、带宽、费用和一致性证明；
2. Control Plane 根据 DR Registry/RPO 实时选择副本并签发短期恢复授权，而不是部署期静态文件；
3. 目标云 KMS/HSM、Workload Identity、IAM Policy-as-Code、凭据轮换/撤销与目标账户 Apply；
4. Region 网络分区、复制滞后、旧副本拒绝、流量切换/回切和可审计 RTO/RPO GameDay；
5. Profile 对象保留期、Legal Hold/Object Lock 与跨 Region 删除/副本保留的深度联动。

因此，本进度关闭用户目标第 6 项中的仓库级跨 Region Restore 代码缺口，但不把两台本机 MinIO
冒充真实多 Region 生产认证，也不改变“尚未通过 V16 全量生产发布 Gate”的结论。
