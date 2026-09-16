# Profile Checkpoint 应用层加密与滚动准入闭环

> 日期：2026-09-16
> 状态：A17 仓库内代码项关闭；目标云 KMS/HSM 与正式轮换演练仍是生产 Gate

## 本轮关闭的缺口

对象存储原先直接保存 `checkpoint.tar.zst`。即使 Bucket 自带服务端加密，拥有对象读取权限的
主体仍可取得 Cookie、Session、LocalStorage 等明文归档。本轮将所有新 Profile Cold Archive
改为 Storage Helper 内完成的应用层 Envelope Encryption：

```text
已验证 tar.zst
  └─ 随机 256-bit DEK + AES-256-GCM
       └─ 版本化 KEK 再封装 DEK
            └─ checkpoint.tar.zst.enc + COMMITTED(last)
```

- 每个 checkpoint 使用独立随机 DEK、独立 content nonce 和 wrap nonce；
- Tenant、Profile、Checkpoint、明文 SHA-256/字节数进入内容认证上下文，Key ID 进入封装认证上下文；
- `COMMITTED` 同时记录密文对象名/格式/哈希/大小、Key ID 与明文哈希/大小；恢复先验证密文
  Marker，再完成 AEAD 认证，最后复用原 tar/zstd 路径、文件、Manifest 与 Hash 校验；
- 密钥文件通过 `O_NOFOLLOW` 打开并复验 inode/device/size，拒绝 symlink、other-readable、
  group/other-writable、超过 64 KiB、未知字段、非法 Key ID 和非 32 字节密钥；密钥材料在内存
  生命周期结束时清零；
- Keyring 可同时保留最多 16 个历史 KEK，`activeKeyId` 只控制新写入。切换 Active Key 后旧
  checkpoint 仍可恢复，新 checkpoint 使用新 Key ID；删除历史密钥前必须确认没有引用；
- 旧 `checkpoint.tar.zst` 保持 read-compatible。首次恢复或导出时，在同一 Profile Helper 锁内
  先写密文、最后替换 Marker，再删除旧明文对象；失败不会先删可恢复的旧对象；
- 用途绑定一次性导出现在签发 `.tar.zst.enc` 密文对象 URL，不再通过预签名 URL 暴露存储明文；
  签名前会实际完成 AEAD 认证并核对封装内 Tenant/Profile/Checkpoint、Key ID 和明文摘要，合法的
  跨租户密文+Marker 替换也会拒绝；导入同时支持原 `.tar.zst` 与加密封装，并在 AEAD 认证后进入
  原安全导入链；临时解密缓冲在离开作用域时清零；
- Node 声明 `profileArchiveEncryption=aead-envelope-v1` 和
  `profileExport=presigned-encrypted-checkpoint-v1`。只要 Profile 已存在 checkpoint，普通启动和
  Migration 的 PostgreSQL Placement 查询均要求该能力，避免滚动升级期间选择无法读取密文的
  N−1 Node；Profile Import/Export 候选也做相同准入。

## 密钥配置

启用 `OBJECT_STORAGE_ENABLED=true` 时，Storage Helper 必须同时配置绝对路径
`PROFILE_ARCHIVE_KEYRING_FILE`。文件格式如下；值必须是 32 随机字节的标准 Base64，不得提交真实
生产密钥到仓库：

```json
{
  "activeKeyId": "profile-archive-2026-09",
  "keys": {
    "profile-archive-2026-09": "<base64-32-byte-key>",
    "profile-archive-previous": "<base64-32-byte-key>"
  }
}
```

Kubernetes Base 预留可选 Secret `browser-node-profile-archive-keyring`，键名为 `keyring.json`；
默认对象存储仍关闭，因此缺少 Secret 不影响基础清单。生产 Overlay 一旦打开对象存储，缺少或不安全
的 Keyring 会使 Storage Helper 启动失败。测试夹具仅用于 Integration，不是部署默认密钥。

## 验证证据

- Storage Helper 单测：随机封装、轮换读取、新 Key 写入、密文不含测试 Secret、密文篡改拒绝、
  历史 Key 缺失拒绝、Keyring 权限拒绝；
- 真实 MinIO GameDay：新对象为 `checkpoint.tar.zst.enc`；预签名下载是 AEAD 密文；旧明文对象会
  自动迁移并删除；迁移后可在新的本地 Store 解密恢复；跨租户合法密文替换在签名前拒绝；对象存储
  超时仍有界且本地 checkpoint 可重试；
- Control Plane 回归：已有 checkpoint 的 Profile 以数据库查询参数要求加密能力，并在服务层再次
  校验标签；
- 完整 Integration：真实 PostgreSQL/Redis/MinIO/mTLS/Chromium 链通过，输出
  `profile_checkpoint_export=true encrypted_archive=true encrypted_roundtrip_import=true`，并保持
  双 Node Migration、Storage Helper 重启恢复、幂等 checkpoint、可复用 Session 全部通过；
- Rust Workspace、Control Plane 全量测试、Web 141 项/Lint/Build、OpenAPI 246 Operations /
  338 Schemas、四语言 SDK 生成、N/N−1 Gate 与 Kubernetes Kustomize 渲染通过。

## 明确边界

1. 本轮保护 S3-compatible Cold Archive 与导出对象；正在运行的 Profile 和本地 Warm Tier 仍依赖
   节点磁盘/卷加密、Helper 隔离和宿主安全，不宣称成为应用层全盘加密；
2. 文件 Keyring 是可替换的仓库实现，不冒充目标云 KMS/HSM。生产仍需 Workload Identity、KMS
   GenerateDataKey/Decrypt、密钥禁用/轮换审计、灾备 Key Escrow 与恢复演练；
3. 加密导出只有保留对应历史 Key 的部署可导入。灾备必须把 Keyring/KMS 生命周期与对象备份一起
   治理，不能只备份 Bucket；
4. Recording、Screenshot Evidence 和 Audit Export 有各自的隐私/保留/签名边界，本切片不将它们
   混同为 Profile checkpoint。
