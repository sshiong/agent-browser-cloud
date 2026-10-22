# Recording 逐版本 Legal Hold 执行器与迟到 OFF 围栏

> 日期：2026-10-23
> 基线：b2b01c8d94e1a17a00d600b0567ca97f61236991
> 状态：对象存储执行层；尚未接入正式策略链，不计产品闭环。

## 为什么需要这一层

进度 286 修复数据库删除授权与保全承诺的竞态，但没有调用云端 Legal Hold。
[S3 PutObjectLegalHold](https://docs.aws.amazon.com/AmazonS3/latest/API/API_PutObjectLegalHold.html)
允许按 Version ID 设置 ON/OFF，其请求参数不包含业务 Epoch 条件。仅在客户端返回后
拒绝旧结果，不能阻止已发出的旧 OFF 稍后释放新 ON。

本次新增 `ObjectArchive::apply_recording_legal_hold`，使用现有 S3 Client 和精确
Recording 身份，不接受调用者提交对象路径。未新增 API/RPC/SDK 字段，也未声明
Browser Node 支持云端保全能力。

## 实现与边界

- 绑定 Tenant/Profile/Session/Recording、Manifest Hash/大小/Segment 数量，拒绝
  无版本、null Version ID、Delete Marker、越界对象、重复版本和不完整对象集合。
  同时覆盖精确前缀内的历史数据版本；当前集合和最终版本清单必须一致。
- 远端 `LEGAL_HOLD` 账本位于删除证据前缀，独立于 Recording 像素对象。初次 ON
  使用 create-only；后继操作用 ETag 条件更新，并要求递增 Epoch、不同 Operation
  ID 和精确前序 Proof。两个控制器只能有一个取得 PREPARED。
- 调用前持久化 PREPARED；之后才逐版本写 ON/OFF、读回、复查集合并提交 COMMITTED。
  写请求关闭 SDK 自动重试，避免隐藏的旧 OFF 越过成功重试。整个执行有 1—120 秒
  预算，每次存储调用还受已有超时约束。
- 未完成 PREPARED 不自动接管、重试或被新 Epoch 覆盖。它表示可能还有旧请求在
  服务端执行；没有提供自动解除该围栏的接口。重放已完成的相同请求会重新验证
  实际版本和 Hold 状态，然后返回原 Proof。
- 初始只允许 ON，且要求原状态为 OFF；已被外部主体设置的 Hold 不会被当成自己
  的 Hold 接管后释放。后继 OFF 需要前序已验证 Proof。成功结果只有安全元数据、
  Inventory Hash 与 Proof，不返回对象路径/Version ID；错误链压缩为固定错误码。
- [MinIO 当前固定版本源码](https://raw.githubusercontent.com/minio/minio/RELEASE.2025-04-22T22-12-26Z/cmd/object-handlers.go)
  对未设置 Hold 元数据的版本返回 `NoSuchObjectLockConfiguration`。只有另外确认
  Bucket 启用 Object Lock、精确版本 HEAD 存在且 Version ID 一致，才将它视为 OFF；
  其他错误不视为成功。目标 AWS 的实际错误语义和 IAM 仍需独立 Gate。

远端 CAS 只串行化使用这一账本的 Hold 执行器；它不是存储供应商的 Epoch 能力。
外部管理员并发写入、供应商在成功响应后仍执行旧写入、跨 Bucket 复制/切换、账本
版本删除/回滚均不在本地证明范围。需要目标账户 IAM 隔离及生产验收。

## 验证

- 两项无网络单测：请求边界和未完成账本不能产生 Proof。
- 真实 MinIO、启用 Object Lock 的独立桶：正常 ON/OFF、四个版本（三对象含历史
  数据版本）、保全期间版本删除拒绝、解除后正常物理删除、精确重放与旧 Epoch 拒绝。
- 两个独立 Client 并发初次 ON：只允许一个提交；错误前序 Proof、Manifest 与
  Tenant 拒绝，胜者状态仍可读回。
- 自有 loopback HTTP 故障代理延迟一个 OFF，客户端先超时；不同 Client 在此期间
  申请新 ON 被未完成账本拒绝。代理随后仍将 OFF 送至真实 MinIO，断言 HTTP 200、
  没有隐藏重试，且逐版本读回为一个 OFF、两个 ON。此时仍不能生成完成 Proof。
- 测试代理只处理自有桶，支持 Content-Length/chunked 响应、HEAD 无响应体、
  Expect 和连接关闭；不输出原始请求、签名、对象路径或响应正文。
- GameDay 默认流程已加入上述真实测试；`OBJECT_STORAGE_TEST_FOCUS=legal-hold`
  只供定位故障，不替代完整 `make test-object-storage`。
- Rust Workspace：243 passed / 0 failed / 13 ignored；环境测试由上述真实 Gate
  单独运行，不能把 ignored 当通过。严格 Storage Helper Clippy 与格式检查通过。

开发中首次真实测试发现 MinIO 缺元数据错误；代理阶段又发现查询参数分类、chunked
和 HEAD framing 缺陷，均在测试准备路径修正。此前失败不计云端能力通过，也不
追溯为生产问题。最终完整 `make test-object-storage` exit 0，包含 Multipart Resume、
Cross-Region Restore、旧无版本桶兼容、COMPLIANCE WORM 删除拒绝、三项 Hold 回归
和 500ms 故障恢复。最终单测/格式/严格 Clippy、兼容、文档检查通过；本次精确提交
仍须自己的 CI。

基线 b2b01c8 主 CI **39219842286**、Desktop **39219842284** 已重新读取精确 SHA
最终 JSON：Verify、扫描、PostgreSQL Gate、Integration/GameDay、Operator、Windows/
macOS 全成功。该基线证据不替代本次代码的 CI。

私有证据位于 `/tmp/agentbrowser-hold-*.log` 与对应 `.exit`；只汇报测试计数、固定
错误分类和安全 HTTP 状态，不公开原始存储请求。

## 必须继续完成

1. PostgreSQL 权威队列与明确 PENDING/UNKNOWN/VERIFIED 状态，及未知 Intent 的
   核对/恢复协议；不得把返回成功的策略写入当成已在云端保全。
2. Control Plane → Node mTLS RPC → Storage Helper 命令、能力准入、精确路由与
   Epoch，持久审计及安全 Proof 投影。
3. Hold 与删除授权共用的并发联锁、迟到录制/新增版本和 Region 切换处理。当前仅
   在 Hold 预检时拒绝已有删除 Tombstone，不能据此证明并发删除安全。
4. 正式 API/四语言 SDK/UI 的可见状态、错误与权限、目标 AWS Apply/IAM、真实
   Legal Hold/WORM 测试。执行器还未通过正式策略 API 调用，因此不能对用户宣称
   云端 ON/OFF 已完成。

以上属于原第五项目标的剩余工作；其他十项目标及客户 Dataset、真实外部系统、目标
环境、供应商取消回执、组织和许可证决策继续保留。总目标保持 active。
