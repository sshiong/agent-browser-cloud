# Recording 用途绑定播放授权闭环

> 日期：2026-09-21
> 状态：仓库内 purpose-bound 播放 Grant、完整 mTLS/对象存储链及四语言 SDK 已闭环；全帧 OCR/非文本视觉敏感分类、Object Lock/WORM、到期物理删除 Worker 和目标云 Legal Hold 仍待完成。

## 问题

此前 Recording 具备逐帧遮罩、不可变 Segment/Marker/Manifest、PostgreSQL 保留期与 Legal Hold
投影，但公共 API 只能查看安全元数据。管理员若要调查事故，只能绕过控制面直接接触对象存储坐标，
无法证明访问用途、Actor、租户、Session、Recording、有效期和实际读取对象保持一致，也没有一次性
兑换和可审计的短期访问窗口。

## 实现

- V127 新增 `session_recording_playback_grants`，以复合外键绑定
  `(tenant_id, session_id, recording_id)`，保存 Purpose、Actor、一次性状态、五分钟签发/访问窗口和
  signer Node；表中没有 URL、对象 Key、Credential 或像素字段。迁移只有新表、约束和索引，不回填、
  不改写旧行，可由旧应用忽略。
- 正式 API 增加创建、一次兑换和有界分页三个 Admin-only 入口。Purpose 限定为事件响应、支持诊断、
  合规审计或安全调查；同一 Actor 的 Idempotency-Key 只能绑定同一 Session/Recording/Purpose。
- Control Plane 在签发、兑换和每次分页时重新检查 Tenant、Actor、Retention/Legal Hold 与 Node
  Capability。兑换只能从 `ISSUED` 原子进入 `REDEEMING` 一次；成功形成五分钟 Actor-bound 访问
  窗口，失败则烧毁 Grant，避免换对象或重放。
- Browser Node 新增 mTLS-only `PresignRecordingPlayback`；Storage Helper 重新读取并校验 aggregate
  `COMMITTED` Manifest 的 SHA-256/长度/计数/时间，再逐段校验 COMMITTED Marker、Object HEAD、
  顺序和脱敏摘要，最后签发 60 秒 GET URL。每页最多 24 段，既受 64 KiB Helper IPC 上限约束，也
  允许下载中断后在访问窗口内重取合法页。
- 返回只含 Manifest/Segment 哈希、计数、时间和短期 URL；URL 不进入 PostgreSQL、Audit 或普通日志。
  非 local/test 环境只接受 HTTPS URL，本地 MinIO 只允许 loopback/`.local` HTTP。
- OpenAPI、Proto、TypeScript/Python/Go/Java SDK 与 Manifest 同步，公开基线更新为
  **253 Operations / 350 Schemas**。

## 验证

- `SessionRecordingPlaybackApplicationServiceTest`：通过；覆盖 Purpose/Actor/Recording 绑定、60 秒
  有界签名、一次兑换提交、完整性拒绝后烧毁 Grant，以及 URL 不进入 Store 接口。
- `cargo test --locked --workspace`、`cargo clippy --locked --workspace --all-targets -- -D warnings`：
  通过；Rust Workspace 无回归。
- OrbStack `make test-object-storage`：通过；真实 MinIO 先拒绝错误 Manifest Hash，再校验正确
  Manifest/Segment Marker、签发 URL并下载出哈希一致的 Segment；500ms 故障路径仍有界返回。
- OrbStack `make test-integration`：通过。V127 在真实 PostgreSQL 17 应用后，测试写入 25 个不可变
  脱敏 Segment，经 Control Plane → mTLS Node → Storage Helper → MinIO 返回 24+1 两页 URL；跨
  Actor 与重复兑换均为 409，Retention 到期阻断分页，Legal Hold 恢复访问，账本无 URL 列且 Audit
  无 URL/Signature 泄漏。输出 `recording_playback_access=true`。
- `make test`、`make lint`、`make contracts-check`、Buf breaking check、四语言 SDK 验证及
  `make test-sdk`：通过。

## 迁移与回滚

- 扩展部署：先应用 V127，再部署理解新 RPC/API 的 Control Plane 与 Node；旧版本不会读取新表。
- 回滚应用：回滚到旧应用即可停止签发，不删除 V127 表，已经签出的 URL 最多 60 秒、访问窗口最多
  五分钟后自然失效。
- 物理收缩：仅在确认没有旧/新应用使用该表并完成审计保留后，另开后续迁移删除；本轮不包含破坏性
  Contract 步骤。

## 保留边界

本闭环解决的是“谁、为何、在何时读取哪一份已提交 Recording”的授权与完整性问题，不把既有逐帧
DOM 敏感遮罩冒充全帧 OCR 或非文本视觉分类。Object Lock/WORM、对象版本治理、到期物理删除 Worker、
目标云 KMS/IAM 和真实对象 Legal Hold API 仍是独立代码/部署 Gate；未完成前仍不能据此宣称 V16
生产就绪。
