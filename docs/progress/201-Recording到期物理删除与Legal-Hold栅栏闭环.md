# Recording 到期物理删除与 Legal Hold 栅栏闭环

> 日期：2026-09-22
> 状态：仓库内到期物理删除 Worker、对象级删除证明、幂等恢复及 PostgreSQL Legal Hold 原子栅栏已闭环；全帧 OCR/非文本视觉敏感分类、目标 Bucket Object Lock/WORM 与目标云原生 Legal Hold API 仍待完成。

## 问题

此前 Recording 的 Retention、Legal Hold、删除 Receipt 都是 PostgreSQL 治理投影，但到期后没有
权威 Worker 删除真实 Segment、Marker 与 aggregate Manifest。直接写一条删除 Receipt 会造成
“数据库声称已删除、对象仍然存在”的假闭环；而在检查 Legal Hold 后异步删除对象，又会留下
Hold 与对象删除并发竞态。

## 实现

- V128 为 `session_recordings` 增加原子删除结果列，并新增
  `recording_retention_deletion_jobs` 权威队列。迁移为纯扩展式：可空列、独立表、部分索引及
  `NOT VALID` 后单独验证的约束；没有回填、删除、重命名或破坏旧应用的读写路径。
- 定时 Worker 只领取 Retention 已到期、未上 Legal Hold、尚未物理删除的 Recording；以
  `FOR UPDATE OF job, recording SKIP LOCKED` 同时锁定队列与 Recording 权威行，并在 45 秒有界
  mTLS Node 调用期间保持锁。Legal Hold 更新因此只能严格排在删除前或提交后，不能插入
  “策略检查完成、对象删除尚未开始”的窗口。
- 新 Node 能力 `recordingRetentionDeletion=verified-prefix-tombstone-v1` 和 mTLS-only
  `DeleteRecordingObjects` RPC 将请求精确绑定 Job/Epoch/Tenant/Profile/Session/Recording、
  aggregate Manifest SHA-256/字节数和 Segment 数；旧 Node 缺少能力时 fail-closed 并重试。
- Storage Helper 在删除前重新读取 aggregate `COMMITTED`，验证其哈希、长度、Recording 与
  Segment 数，再完整列举并验证每个 Segment/Marker 的名称、序号、Marker 和对象长度。对象集合
  不完整或出现额外对象时拒绝执行。
- Helper 先在 Recording 前缀外 create-only 写入 `PREPARED` tombstone，再删除 Segment/Marker，
  最后删除 aggregate `COMMITTED` 并确认前缀为空；随后 create-only 写入 `COMMITTED` 删除
  tombstone。崩溃后的重试从 PREPARED 的剩余集合继续；若删除成功后的 Node 响应丢失，同一 Job 的
  单调更高 Epoch 会复用已提交 tombstone 并返回同一 proof，不会重新猜测已不存在的 Manifest。
- Control Plane 只接受精确 Node/Job/Epoch、64 位 proof hash、`2 × segmentCount + 1` 对象计数和
  合法完成时间；随后在同一事务写入 Recording 删除投影、proof-bound 企业删除 Receipt、队列终态
  与哈希审计。普通手工 Receipt API 不再允许 `REMOTE_DESKTOP_RECORDING`，避免无物理证明的伪造。
- Recording 列表、播放 Grant 创建/兑换/分页及 Retention 更新均排除已经物理删除的对象；数据库、
  API 与 Audit 不保存对象 Key、Bucket、URL 或 tombstone 正文。

## 验证

- `cargo test --locked --workspace --all-targets`：通过；Rust Workspace 无回归。
- `./gradlew -p apps/control-plane test`：通过；Control Plane 全量单测无回归。
- Buf breaking check 对 `main`：通过；新增 RPC 为 additive。
- `make test-upgrade-compatibility`：通过；验证 V128 additive schema、约束、能力门禁和 N−1 拒绝。
- OrbStack `make test-object-storage`：通过。真实 MinIO 完成 Recording、验证播放、删除三个真实对象、
  确认 Recording 前缀为空，并以同一 tombstone 幂等重试；500ms 故障路径仍有界返回。
- OrbStack `make test-integration`：通过。真实 PostgreSQL 17、mTLS Node、Storage Helper 与 MinIO
  链路先证明 Legal Hold 阻止删除，解除后删除 25 个 Segment 对应的 51 个对象；Job 为
  `COMMITTED`、proof 为 64 位哈希、Receipt 唯一、列表隐藏、aggregate Manifest `mc stat` 不存在，
  Audit 不含 URL、Signature 或对象 Key。输出 `recording_retention_physical_deletion=true`。

## 迁移、监控与回滚

- 扩展部署：先应用 V128，再部署具备新 RPC/Capability 的 Node，最后开启 Control Plane Worker。
  滚动期间旧 Node 不会收到删除请求；队列保留并退避重试。
- 监控 `QUEUED/RETRY` 的最老 `available_at`、`FAILED` 数、尝试次数、Node 能力覆盖率、Legal Hold
  更新等待和 Worker 延迟；任何异常都不得用手工 Recording Receipt 掩盖。
- 应用回滚：停止 Worker并回滚应用即可；保留 V128 列、队列和 tombstone，旧应用会忽略它们。
  已物理删除的对象不可由数据库回滚恢复，只能按备份/灾备流程恢复，因此删除提交前的 Retention、
  Legal Hold、Manifest 完整性和 Node 能力检查均为强制门禁。
- 物理 Contract 迁移不在本切片执行；只有新旧版本均不再使用这些列/表并满足审计保留后，才能另开
  迁移移除。

## 保留边界

本闭环证明仓库内 S3-compatible 对象到期后可真实删除且与 PostgreSQL Legal Hold 严格排序，但不把
普通 Delete API 冒充目标云 Object Lock/WORM 或监管型 Legal Hold。全帧 OCR、非文本视觉敏感分类、
目标 Bucket Versioning/Object Lock 保留模式、WORM 合规证明、目标云 KMS/IAM 和原生 Legal Hold
仍是独立生产 Gate；完成前不能宣称 V16 全量生产就绪。
