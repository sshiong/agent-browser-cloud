# Profile Warm Tier 应用感知恢复闭环

> 日期：2026-09-22
> 状态：SQLite/LevelDB 应用感知 Warm Tier 仓库代码项已完成；Multipart Resume 后由 progress 206
> 完成，跨 Region Restore 与目标云 KMS/IAM 仍未完成
> 实现提交：`94e1724 feat: add application-aware profile warm tier`

## 本轮关闭的缺口

原 Warm Tier v1 只对普通稳定文件生成内容寻址增量，SQLite 与 LevelDB 被列入
`deferredGroups`。该策略避免了损坏数据库，但 Runtime 非正常退出时只能恢复较旧的冷
Checkpoint，无法恢复已经提交到 WAL 或 LevelDB Manifest 的最新应用状态。

本轮在隔离 Storage Helper 内增加 application-aware v2：

- SQLite 识别同时覆盖 Cookies/History、`-wal/-shm/-journal`、`.sqlite` 以及通过文件头识别的
  无扩展名 Chromium 数据库（例如 `Default/Web Data`）；
- 对活动 SQLite 使用 `rusqlite::backup` 在线备份，将 WAL 的一致读事务合并为单个独立快照；
  提交前执行 `PRAGMA integrity_check`，记录 schema/user version、文件 Hash 和文件数屏障；
- LevelDB 按有界数据库根复制除 `LOCK` 外的 CURRENT/MANIFEST/log/SST 集合，复制前后重算
  元数据与内容 Hash；源在屏障期间变化则整批失败；
- 隔离副本必须通过 CURRENT 指针、MANIFEST 存在性、`paranoid_checks` 打开及全量迭代验证，
  验证后的精确文件集合、当前 Manifest 和聚合 Hash 才能进入提交；
- v2 Manifest 带 `applicationBarriers` 且不再允许数据库 `deferredGroups`。文件 Chunk、Manifest、
  `COMMITTED` 和 `LATEST` 仍保持 commit-last；旧 v1 Manifest 继续可读；
- 新 Session 先恢复冷 Checkpoint，只在 Warm Tier 的 Write Epoch 严格更新时应用 v2 精确文件集，
  随后再次打开并验证 SQLite/LevelDB。相同 Epoch 的干净 Stop Checkpoint 永远优先；
- 含延后数据库组的旧 v1 Warm Tier 不再把普通文件部分覆盖到冷 Checkpoint 上，避免生成从未真实
  存在过的跨事务 Profile；
- Node 保留原 `profileWarmTier=delta-journal-v1` 兼容标签，并新增
  `profileWarmTierApplicationAware=sqlite-leveldb-v1`，滚动部署期间不会把旧能力误报为新能力。

## 失败边界

- SQLite 文件头、在线备份、完整性校验或恢复证明任一失败时不推进 `LATEST`；
- LevelDB 缺 CURRENT/MANIFEST、文件复制期间变化、打开或迭代失败时不推进 `LATEST`；
- v2 Chunk、Manifest Hash、应用屏障或恢复后内容不一致时 Workspace 获取失败，失败 Workspace
  不会交给 Browser Runtime；
- 单次 50,000 文件、单文件 512 MiB、Profile 1 GiB 与 Warm Tier 新增内容 64 MiB 上限保持不变；
  超限不会退化为未经验证的原始数据库复制。

## 验收证据

- Storage Helper 新增并通过真实 WAL SQLite、真实 `rusty-leveldb`、无扩展名 `Web Data`、非正常
  Stop 后下一 Session 恢复、相同 Epoch 冷 Checkpoint 优先、旧 v1 部分清单拒绝、损坏 SQLite
  和缺 CURRENT LevelDB fail-closed 测试；Storage Helper 20 项执行测试通过，1 项需外部对象存储
  的测试按条件忽略；
- Rust Workspace 全量测试、全目标严格 Clippy 和 N/N−1 Gate 通过；
- OrbStack 完整 Integration 让 Fake Chromium 保持一个真实 WAL 模式 `Default/Web Data` 连接，
  检查 v2 Manifest、零 deferred group、SQLite 应用屏障和内容证明，输出
  `profile_warm_tier_application_aware=true`；同轮 19 个耐久 Workflow、恢复、mTLS、Agent、
  Recording 与对象存储链全部通过；
- `make ci` 完整通过，公开契约未变，仍为 253 Operations / 350 Schemas；
- OrbStack `make docker-build` 七个镜像全部通过；Browser Node 从干净 release 环境编译
  SQLite backup/LevelDB 依赖并完成录像隐私扫描器功能自检。

## 仍未完成

1. 大型 Cold Archive 的 Multipart Resume、上传重试账本和过期 Orphan 回收已由
   [进度 206](206-Profile大型归档Multipart-Resume闭环.md)完成；Warm Tier 日志合并与垃圾回收
   仍未完成；
2. 真实跨 Region Profile Restore、复制延迟/带宽/一致性证书与网络分区演练；
3. 目标云 KMS/HSM、Workload Identity、IAM Policy-as-Code、CSI 断盘和多 Node Linux 长稳；
4. Profile 对象保留期、目标云 Legal Hold/Object Lock 深度联动。

因此，本进度只关闭用户目标第 6 项中的 SQLite/LevelDB 应用感知处理，不把剩余目标环境和大规模
传输能力描述为已完成，也不改变“尚未通过 V16 全量生产发布 Gate”的产品结论。
