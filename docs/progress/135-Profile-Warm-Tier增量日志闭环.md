# Profile Warm Tier 增量日志闭环

> 日期：2026-08-13
> 状态：仓库内核心闭环已完成；应用感知数据库适配器与跨 Region Restore 未完成

## 本轮关闭的缺口

Profile 运行期间原来只有停止时 Checkpoint，缺少 V16 要求的 Region Warm Tier 增量保护。
本轮交付真实链路：

- Browser Node 默认每 30 秒触发一次轻量增量同步，可通过
  `PROFILE_WARM_TIER_SYNC_INTERVAL_SECONDS` 在 15—3600 秒内调整；
- Node 仅通过隔离的 Storage Helper IPC 操作 Profile，Control Plane 不接触 Profile
  字节、对象存储凭据或本地文件路径；
- Storage Helper 以 `Profile Write Epoch + Journal Sequence` 建立有序日志，两次元数据
  扫描形成事务屏障，屏障前后不一致时整次同步失败，不提交半成品；
- 普通稳定文件按 SHA-256 写入内容寻址 Chunk，未变化内容直接复用，删除项显式进入
  Manifest；单次最多 50,000 文件和 64 MiB 新内容；
- Manifest 先写 staging，再原子安装日志目录、写 `COMMITTED`，最后原子推进 `LATEST`；
  读取时重新校验 Manifest Hash、Marker、Chunk 大小和内容哈希；若断电发生在目录安装
  与 `COMMITTED/LATEST` 之间，下次同步会提升完整提交或删除不可见孤儿后重建；
- SQLite/Cookies/History 与 LevelDB 组不会被当作普通文件复制；当前显式记为
  `deferredGroups`，避免产生看似成功但事务不一致的 Warm 副本；
- Node 仅在 Helper 已提交后发布持久 `ProfileWarmTierSyncedEvent`，通过现有 mTLS、
  Node Journal、Session/Term/Context Fencing 和 Inbox 去重链进入 Control Plane；
- V096 只在 PostgreSQL 保存提交屏障、序号、计数、Hash、Node 与时间，不存 Profile
  内容。约束使用 `NOT VALID -> VALIDATE`，属于 expand-only 迁移；
- Session 正在停止时已完成的同 Context 屏障仍可入账；迁移/重启后的旧 Context 事件以
  `STALE_PROFILE_WARM_TIER_CONTEXT` 终态消费，避免形成 Node Journal 毒消息。

## 正式 API 与 Web / Tauri 共用 UI

- `GET /api/v1/profiles/{profileId}/warm-tier` 返回 PostgreSQL 权威的最新提交状态；
- 未产生首个屏障时明确返回 `AWAITING_FIRST_SYNC`，不伪造同步结果；
- Profile 页的“Warm Tier”面板展示写 Epoch、日志序号、上传字节、变更/删除、Chunk
  复用、延后数据库组、Node、提交时间、Transaction Barrier 和 Manifest SHA-256；
- React 页面和 API Client 位于 Web/Tauri 共用代码，不使用 Mock、localStorage 或固定
  定时器伪造变化；
- OpenAPI、Proto 和 TypeScript/Python/Go/Java SDK 同步为 200 Operations / 269 Schemas。

## 验收证据

- Storage Helper 12 项通过、1 项真实对象存储环境测试按条件忽略；新增用例验证稳定文件
  增量、Chunk 复用、删除记录、数据库组延后、Commit Marker、断电窗口恢复和写 Epoch
  Fencing；
- Node Agent 定向测试验证旧 Context Warm Tier 拒绝属于终态；
- Control Plane 全量单测通过，新增 Mapper、应用服务、Inbox 原子提交、停止竞态和迁移
  旧 Context 用例；
- Web API 全量 72 项单测通过；
- Buf、OpenAPI、四语言 SDK 与 N/N−1 兼容门禁通过，N/N−1 门禁固定 V096 expand-only
  约束和 Proto 1—13 字段号；
- `make test-integration` 以真实 PostgreSQL、MinIO、Browser Node、Storage Helper、事件流
  和 HTTP API 验证 `LIVE` 状态、至少一次真实字节上传及磁盘 `LATEST` 提交指针；最终输出
  `profile_warm_tier_delta_journal=true`、`public_tables=110`、`audit_chain_valid=true`。

## 仍未完成

1. SQLite Online Backup/WAL 与 LevelDB Snapshot/Manifest 的应用感知同步适配器；
2. Warm Tier 日志压缩/合并、垃圾回收和目标 CSI 性能/断盘长稳；
3. Multipart Resume、真实跨 Region Profile Restore 和全局带宽/一致性证书；
4. Profile 对象保留期、Legal Hold/Object Lock 深度联动；
5. 目标云 KMS/IAM、正式多 Node Linux 长稳及跨 Region 网络分区矩阵。
