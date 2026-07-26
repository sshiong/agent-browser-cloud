# Profile MVP 与恢复 PoC

> 状态：Phase 3 / 9.1 技术 MVP 已完成；9.2 的 Technical Ready 已完成，业务恢复判定仍待开发。

## 已完成

- 独立 `storage-helper` 进程实现租户隔离目录：
  `tenants/{tenant}/profiles/{profile}`。
- Node Agent 只通过固定有界 Unix IPC 请求 Acquire/Checkpoint/Release，并校验返回的
  Workspace 必须位于精确的 Tenant/Profile/Session 根目录。
- Profile Core 与 Ephemeral 分盘；Chromium `--user-data-dir` 指向 Core，
  `--disk-cache-dir` 指向 Ephemeral。
- 默认检查点排除 `Cache`、`Code Cache`、`GPUCache`、`ShaderCache` 和
  `Crashpad`。
- 每个 Profile 同时只允许一个 Session Writer；同一 Session 的 Node 重启可复用
  未检查点工作区，避免丢失崩溃恢复期间的本地变化。
- Profile Write Epoch 随新的 Writer 世代递增。
- Runtime 停止后生成完整文件 Manifest、单文件 SHA-256、聚合 Content Hash，
  再提交 `COMMITTED` Marker 和 `LATEST` 指针。
- Restore 只接受已提交检查点，并校验 Manifest 身份、Marker、文件数量、文件大小、
  路径安全和每个文件的 SHA-256。
- Profile 独立卷使用 Node/Storage 专用 Group 权限（Unix 目录 `0770`、文件 `0660`）；
  Network Helper 不挂载该卷。文件与目录在原子重命名后执行同步，降低越权读取和掉电
  丢失提交记录的风险。
- 已知 Chromium 临时 Singleton 符号链接会被排除；未知符号链接会使检查点失败，
  防止归档越过 Profile 根目录读取宿主文件。
- 损坏检查点不会被标记为恢复成功；测试覆盖篡改文件后恢复失败和 Writer Lock 回收。
- Runtime Stop Event 携带 Profile、Checkpoint Epoch、Write Epoch、Core 大小、
  文件数和恢复来源；从未启动的 Session 可以空停止，不伪造检查点。
- Control Plane 新增 Profile JPA 映射、Flyway V003、租户隔离的 Create/List/Get API，
  并验证停止事件中的 Profile 必须与 Session 绑定一致。
- Session 创建时自动确保 Profile 元数据存在；同一 Profile ID 不可被另一租户复用。
- Web Console Profile 页面已从 Fixture 切换到真实 API，支持创建、搜索、桌面表格、
  移动卡片、检查点和 Core 指标，以及 Loading/Empty/Error 状态。

## 恢复 PoC

集成测试使用受控假 Chromium 在 Core 写入持久状态：

```text
Session A 启动（starts=1）
→ Chromium Crash Recovery（starts=2）
→ Browser Node 重启对账（starts=3）
→ Session A 停止并提交 Checkpoint Epoch 1
→ Session B 绑定同一 Profile
→ Restore Epoch 1 后启动（starts=4）
→ Session B 停止并提交 Checkpoint Epoch 2
```

验收结果：

- `profile_checkpoint_epoch=2`
- `profile_restore_starts=4`
- `storage_helper_process_isolated=true`
- `storage_helper_checkpoint_failure_closed=true`
- `storage_helper_restart_recovered=true`
- `storage_checkpoint_idempotent=true`
- 第二次恢复来源为 `TECHNICAL_READY`
- Profile 跨租户访问为 `403`
- Checkpoint 目录存在 `COMMITTED`

## 测试证据

```bash
cargo test -p storage-helper
cargo clippy -p storage-helper --all-targets -- -D warnings
./gradlew -p apps/control-plane test
pnpm --dir apps/web-console lint
pnpm --dir apps/web-console test
pnpm --dir apps/web-console build
make test-integration
make test-e2e
```

真实 Web E2E 额外覆盖：

1. 终止运行中的 Session 后在 Profile 页面观察 `epoch 1`；
2. 通过真实创建对话框创建 Profile；
3. 直接终止从未启动的 Session，不生成伪造检查点；
4. 既有 noVNC、断线清键和 Session 生命周期无回归。

## 仍未完成

- 自建登录站点的真实 Cookie 登录态恢复验证。
- `LOGIN_REQUIRED`、`BUSINESS_READY`、`DEGRADED` 和 `MANUAL_RECOVERY` 业务验证器。
- SQLite/LevelDB/Cookie/Extension State 的 Application-aware Adapter 与事务屏障。
- Profile Delta Journal、Warm Tier、对象存储 Archive、断点续传与跨 Node/Region 恢复。
- Profile 数据加密、KMS Key Version、导出、删除证明和 Legal Hold。
- Writer Process/Context/Operation 绑定、陈旧锁回收与多 Node 分布式所有权治理。
- Disk Full、WAL/Manifest/Chunk/Marker/Key 丢失等完整 Corruption Injection Matrix。
- Flush Soft Deadline、Hang Detection 冲突治理、RPO/RTO 与容量/成本 Gate。
