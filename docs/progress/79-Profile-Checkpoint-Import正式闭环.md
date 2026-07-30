# Profile / Checkpoint Import 正式闭环

> 日期：2026-07-30
> 状态：PostgreSQL Job、正式 API、mTLS 数据面、Storage Helper、对象存储和 Web/Tauri
> 共享导入工作区已完成

## 本轮关闭的缺口

Profile 页面和创建向导原先没有可执行的内容导入能力。V055 将 `.tar.zst` Checkpoint
导入升级为正式领域能力：

- `profile_import_jobs` 持久化租户、操作者、幂等键、请求哈希、Operation ID、目标
  Profile/Checkpoint、归档哈希/大小、Node、结果与错误状态；
- `POST /api/v1/profile-imports` 接收最多 256 MiB 的 multipart 归档；
- `GET /api/v1/profile-imports` 与 `GET /api/v1/profile-imports/{importId}` 返回当前
  操作者的真实 Job，不暴露其他操作者或其他租户记录；
- Control Plane 按 `profileImport=checkpoint-stream-v1` 能力标签选择健康 Node，通过
  mTLS client-streaming gRPC 上传，不把归档字节写入 PostgreSQL；
- Browser Node 按连续 Offset、声明大小和 SHA-256 写入 Profile Volume 有界临时区；
- Storage Helper 以 `O_NOFOLLOW` 重新打开文件，独立复核大小/SHA，只接受 `core/`
  下的普通文件/目录，拒绝路径逃逸、符号链接、重复路径、超限文件和解压炸弹；
- Helper 忽略来源 Manifest 的 Tenant/Profile 身份，重新生成当前租户、目标 Profile
  与 Checkpoint 的 Manifest，commit-last 写入 S3 兼容对象存储；
- 对象提交成功后，Control Plane 才在一个事务中创建 `TECHNICAL_READY` Profile、
  提交 Job 并追加哈希审计事件。

导入结果不使用生产 Mock、`localStorage`、JSON 文件或 JVM/浏览器内存 Job。对象存储
失败不会伪造成功；相同幂等键和相同请求返回原 Import/Operation/Checkpoint ID，不同
请求哈希返回 409。

## 安全与滚动升级边界

- V055 是 expand-only 新表；N−1 应用忽略它。回滚方式是关闭 API/UI 和 Node 能力标签，
  已导入 Profile 仍是普通有效 Profile，不执行破坏性 DDL；
- 只有同时启用 Storage Helper 对象存储和 Node `OBJECT_STORAGE_ENABLED=true` 时，
  Node 才发布导入能力；S3 凭据只属于 Storage Helper；
- Node 与 Helper 使用共享 Profile Volume，但 IPC 仍按 Kernel Peer UID 校验；
- Client、Control Plane、Node 和 Helper 分别校验同一个 SHA-256；Control Plane 的
  multipart 临时文件只属于有界请求入口，不进入业务数据库或归档模型；
- 新 gRPC RPC 是附加能力，旧 Node 不发布标签，因此不会被新 Control Plane 选中；
- 来源归档的 Cookie、Storage 与登录态属于敏感 Profile Core，导入权限等同于 Profile
  管理权限；API、审计和对象存储策略必须继续使用租户边界。

## Web / Tauri 共享体验

`ProfileImportDrawer` 由现有 React UI 和 API Client 提供，Web 与未来 Tauri Desktop
复用同一组件：

- 从 Profile 页面工具栏、空状态和创建 Session 的 Profile 步骤进入；
- 只接受 `.tar.zst`，浏览器分块计算 SHA-256 并显示真实进度；
- Runtime 只列出正式 Stable Build；
- 提交期间禁止重复关闭/重复提交；
- 显示真实 Import、Operation、Checkpoint、Node、大小、文件数和 Request ID；
- 最近导入读取正式 API，不伪造进度或资源状态；
- 失败展示后端稳定错误码与 Request ID。

UI 延续 Neo-Industrial Observatory：高信息密度、明确提交路径和可核验标识，不使用
渐变、装饰性卡片或仅靠颜色表达状态。

## 可重复验收

```text
cargo fmt --all -- --check
cargo test --workspace
./gradlew -p apps/control-plane spotlessCheck test
pnpm --dir apps/web-console format:check
pnpm --dir apps/web-console lint
pnpm --dir apps/web-console test
pnpm --dir apps/web-console build
python3 tests/upgrade/n-minus-one-gate.py
make test-integration
```

实测结果：

- Rust Workspace 全测通过；包含安全归一化导入、路径/大小限制与 Helper IPC 测试；
- Java 全测通过；新增正常流式提交、非法哈希、Node 拒绝和已提交幂等重放覆盖；
- Web 15 个测试文件、52 项测试、Lint、Prettier 和 Production Build 通过；
- N/N−1 Gate 覆盖 V055、Profile Import gRPC 字段编号、能力标签和 OpenAPI 新端点；
- 完整 Integration 使用 PostgreSQL 17、Redis、mTLS Control Plane/Browser Node、
  独立 Storage Helper 和固定版本 MinIO，验证真实 multipart 上传、对象提交、Profile
  与审计落库，以及相同 Import/Operation/Checkpoint ID 的幂等重放；原集成矩阵保持
  全部通过。

## 仍未完成

Profile / Checkpoint Import 的仓库内核心闭环已关闭。以下是独立剩余项：

1. 创建时可复用 Proxy Binding 已在进度 82 关闭；多 Provider、目标云 Secret
   解引用、后台主动健康探测和运行中 Rebind Operation 仍未完成；
2. Profile 导出下载、Purpose-bound 授权、敏感内容提示、保留期和 Legal Hold；
3. Multipart Resume、Warm Tier Delta Journal、跨 Region Restore、目标云 KMS/IAM；
4. Group/Tags 批量生命周期、服务端组合过滤和大列表批量投影；
5. 目标 Linux 多 Node 长稳、桌面签名、Windows 和完整辅助技术生产矩阵。
