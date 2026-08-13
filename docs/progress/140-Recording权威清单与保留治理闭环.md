# Recording 权威清单与保留治理闭环

> 日期：2026-08-13
> 状态：仓库内代码、契约、分层测试与生产构建已完成；目标对象存储物理 WORM/删除执行仍待生产验收。

## 本轮完成

- Storage Helper 对录制 Segment、Segment Commit Marker 和最终 Manifest 使用 create-only
  对象写入；幂等重试只接受完全相同的字节，已有对象内容不同则 fail-closed，避免应用层覆盖；
- 最终 `COMMITTED` Manifest 返回并校验 SHA-256 与字节数，Recorder 保留 Pending Summary，
  只有 Node Journal 原子持久化事件后才确认并清理，失败重试不丢 Manifest；
- `StopRuntime` 将 `RuntimeStopped` 与 `SessionRecordingFinalized` 按顺序写入同一个 SQLite
  事务并同时关闭 Runtime Lease；资源策略关闭录制会先预留事件序号、最后执行录制停止，
  不会在后续 Actuator 失败时留下未入账 Manifest；
- Browser Crash 路径也会持久化并发布 Recording Finalized，再发布 Crash，录制不再因异常退出
  静默丢失；事件继续走 Node Journal、mTLS、Coordinator Fencing 与 Inbox 幂等链；
- 新增 V099 `session_recordings` 权威投影，持久化帧/分段/遮罩统计、Manifest Hash、Node、
  保留到期时间与 Legal Hold；Retention Policy 更新会同步作用于已有录制投影；
- 新增正式 `GET /api/v1/sessions/{sessionId}/recordings`，先校验 Session Tenant，再只返回
  公共元数据，绝不返回 `manifest_object_key` 或对象存储凭据；
- Web/Tauri 共用 Session 详情新增录制清单，展示真实 Hash、帧数、丢帧、分段、Policy
  Version、保留期和 Legal Hold，不使用 Mock、localStorage 或定时器伪造数据；
- OpenAPI、TypeScript/Python/Go/Java SDK 与 N/N−1 Gate 已同步到 201 Operations / 271 Schemas。

## 验证

```bash
cargo check -p node-agent -p node-journal -p session-recorder -p storage-helper
cargo test -p node-journal atomically_commits_command_result_and_runtime_lease_transition
cargo test -p session-recorder
./gradlew -p apps/control-plane test \
  --tests io.browsercloud.infrastructure.NodeEventMapperTest \
  --tests io.browsercloud.application.NodeEventIngestionServiceTest \
  --tests io.browsercloud.application.SessionRecordingApplicationServiceTest
pnpm --dir apps/web-console lint
pnpm --dir apps/web-console format:check
pnpm --dir apps/web-console build
make contracts-check
python3 tests/upgrade/n-minus-one-gate.py
```

## 尚未完成

1. 应用层 create-only 已阻止覆盖，但 S3/MinIO Bucket 的 Object Lock、Retention Mode、版本化、
   KMS Key 与跨账号删除保护必须在目标云基础设施中开启并产出验收证据；
2. 当前 Control Plane 已提供清单和治理投影，尚未提供 Recording purpose-bound 一次性播放
   Grant；不能直接暴露 Segment/Manifest URL，需实现短期授权、逐段完整性验证和访问审计；
3. Retention/Legal Hold 已约束元数据与通用删除 Receipt，但录制专用到期扫描器、对象逐段删除、
   删除结果回执及失败重试账本尚未实现；
4. 目标 Linux/正式 Chromium 的长录制、崩溃、磁盘满、Helper 重启、MinIO/S3 超时与大规模
   Session 并发长稳仍待执行；
5. 无语义视觉/OCR 敏感识别仍未完成；本轮继续使用已闭环的 DOM 语义帧级遮罩并 fail-closed。
