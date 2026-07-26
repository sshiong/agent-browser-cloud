# Phase 5：Object Storage 归档与超时演练

> 状态：S3 归档 Stage A 已完成
> 日期：2026-07-26
> 验收入口：`make test-object-storage`

## 已实现

Storage Helper 新增可选的 S3-compatible Object Archive Provider。Profile 仍在本地/Warm
Tier 完成事务性 Checkpoint，随后才执行 Cold Archive：

```text
Local Manifest + Local COMMITTED
→ checkpoint.tar.zst
→ remote manifest.json
→ remote COMMITTED（最后写入）
```

归档具有以下边界：

- 只打包已通过本地 Manifest、文件 Hash 和 Commit Marker 复验的 Checkpoint；
- Core 进入压缩包，Cache/Ephemeral 不进入；
- 远端路径绑定 Tenant、Profile 和 Checkpoint ID；
- `COMMITTED` 包含 Checkpoint/Write Epoch、Content Hash、Archive Hash 和字节数；
- 只有 Archive 和 Manifest 成功后才写远端 `COMMITTED`；
- Connect/Operation Timeout 分别有 100ms—60s 配置上界；
- 生产环境拒绝明文 HTTP Object Storage；
- S3 Access Key 只进入独立 Storage Helper，不进入 Node Agent；
- 归档失败使 Checkpoint IPC fail-closed，但不会删除或损坏本地已提交 Checkpoint；
- 重试会复用同一 Checkpoint，并以相同对象键覆盖未完成上传，最终再写 Commit Marker。

## 配置

- `OBJECT_STORAGE_ENABLED`
- `OBJECT_STORAGE_ENDPOINT`
- `OBJECT_STORAGE_BUCKET`
- `OBJECT_STORAGE_REGION`
- `OBJECT_STORAGE_ACCESS_KEY_ID`
- `OBJECT_STORAGE_SECRET_ACCESS_KEY`
- `OBJECT_STORAGE_PREFIX`
- `OBJECT_STORAGE_CONNECT_TIMEOUT_MS`
- `OBJECT_STORAGE_OPERATION_TIMEOUT_MS`

Compose/Kubernetes 基础清单显式保持 `OBJECT_STORAGE_ENABLED=false`。目标环境必须通过
Secret/Workload Identity Overlay 注入凭证后再开启，仓库不提供默认生产密钥。

## 真实 MinIO GameDay

`tests/failure-injection/object-storage-timeout.sh` 使用固定版本 MinIO 和 `mc`：

1. 创建真实 S3 Bucket；
2. 生成包含 Profile Core 的本地 Checkpoint；
3. 上传 `.tar.zst`、Manifest 和 Commit Marker；
4. 从 MinIO 逐一读取三个对象；
5. 暂停 MinIO 容器；
6. 以 500ms Operation Timeout 再次归档；
7. 验证操作在上界内失败，本地 Checkpoint 仍能通过完整性校验并重新打包。

```text
OBJECT_STORAGE_GAMEDAY_OK
commit_marker_last=true
timeout_ms=500
local_checkpoint_retryable=true
```

## 尚未完成

1. 当前是 Single PUT 压缩归档；大对象 Multipart Resume、Upload ID Journal、过期
   Orphan 清理和 API Cost 统计尚未实现。
2. 尚未实现从 Object Storage 下载、Archive Hash 复验和跨 Node/Region Restore。
3. 目标云 IAM/Workload Identity、KMS Envelope Encryption、Versioning、WORM、
   Cross-region Replication 和 Lifecycle Policy 尚未验收。
4. Kubernetes 基础清单默认关闭 Provider，生产 Overlay 与 Secret Rotation 待目标环境完成。
5. 尚未将 MinIO GameDay 加入默认 CI；它需要 Docker 镜像和容器权限。
