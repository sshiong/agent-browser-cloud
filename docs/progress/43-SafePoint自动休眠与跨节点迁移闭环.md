# Safe Point、自动休眠与跨节点迁移闭环

> 日期：2026-07-28
> 状态：仓库内核心执行链和双 Browser Node/Object Storage/CDP 数据面集成证书完成；
> CDP 浏览器活动、应用事务短 Lease、声明式应用 Ready Gate 和 Provider Evidence
> 平台协议已补齐；目标业务 Adapter 与目标环境长稳待完成

## 本轮完成

### Safe Point Aggregator

- 新增 V024 `session_safety_signals`，安全信号带 Tenant、Node、Context Epoch、来源、
  观察时间和过期时间；缺失或过期的 Node 信号返回 `UNKNOWN`，不会把“没有数据”
  误判为安全。
- Browser Node 每次 5 秒资源上报同时读取真实 Input Ledger：
  - 是否仍有按键/鼠标输入；
  - 是否处于 Active Drag；
  - 按键和鼠标按钮数量。
- Control Plane 聚合以下权威状态：
  - 当前 Node/Context 的 Input Ledger；
  - HumanTakeover 和其他 Active Exclusive Operation；
  - RUNNING Agent Task 与待 Human Handoff；
  - Snapshot/Profile/Durable Workflow；
  - 可扩展的 File Transfer、Form、Payment/Security、Critical Transaction 和
    Business Recovery 信号。
- 新增 `GET /api/v1/sessions/{id}/safe-point`。Session 详情显示 SAFE/BLOCKED/UNKNOWN、
  信号新鲜度和非纯颜色的阻塞原因。
- 当前已经有真实生产者的是 Input Ledger、CDP File Upload/Download、导航级 Form
  Submission、Operation、Agent Task 和 Durable Workflow。Payment/Security、SPA
  应用语义、关键业务事务和 Business Recovery Unknown 已有应用侧短 Lease Producer
  API，但仍需每个目标业务的 Adapter 主动 Acquire/Renew/Release，因此不能宣称任意
  网站都已自动具备完整业务安全点识别。证据见
  [CDP 浏览器活动 Safe Point](48-CDP浏览器活动SafePoint.md)和
  [应用业务安全点 Lease 闭环](50-应用业务安全点Lease闭环.md)。

### 达到上限的真实动作

- `WAIT_SAFE_POINT_MIGRATE` 和 `HIBERNATE` 在执行前都会重新读取 Safe Point；不允许迁移
  或休眠时降级为暂停 Agent、保留 Browser。
- `HIBERNATE` 不再只改 Policy 状态：
  - 创建真实 `HIBERNATE` Exclusive Operation；
  - Node 先执行 All-keys-up，再停止 Runtime；
  - Storage Helper 提交 Profile Checkpoint；
  - RuntimeStopped 回调提交 Operation，并将 Session 转为 `HIBERNATED`。
- `TERMINATE_STRICT` 继续要求 Platform Admin 和前端二次确认；达到上限后创建真实
  Termination Operation，而不是只显示 `CRITICAL`。
- `PAUSE_AGENT` 仍是默认行为；Browser、登录状态和 HumanTakeover 能力保持。

### 跨 Node 迁移与恢复

- 新增 V025 `session_migrations`，持久化以下阶段：
  `CHECKPOINTING → RESTORING → STATE_RESYNC → BUSINESS_VALIDATION →
  COMPLETED/DEGRADED/FAILED`。
- 目标 Placement 明确排除源 Node；没有第二个合格 Node 时保持可重试失败，不会把同
  Node 重启描述成跨 Node 迁移。
- 新 Node 通过心跳声明 `startRuntimeGenerationFloor=v1`；迁移目标的 PostgreSQL
  候选查询和服务层二次校验都会拒绝缺少该能力的 N−1 Node，避免旧 Node 忽略
  `minimum_browser_generation` 后产生世代回退。
- StartRuntime 契约新增 `profile_checkpoint_id`。目标 Storage Helper 从 S3-compatible
  Object Storage 读取远端 `COMMITTED` 和 `checkpoint.tar.zst`，验证：
  - Commit Marker Checkpoint ID；
  - Archive SHA-256；
  - Archive 字节数；
  - Tar 路径、条目类型、单文件/文件数/总归档边界；
  - Manifest Tenant/Profile/Checkpoint 身份；
  - 解包后的逐文件大小和 SHA-256。
- 恢复顺序为“先验证并激活本 Node 已提交 Checkpoint，缺失时再访问 Object
  Storage”；因此同 Node 休眠恢复不依赖 S3，跨 Node 恢复仍保持远端归档
  fail-closed。
- Restore 后请求真实 Full State Resync；默认 Business Recovery Validator 根据真实
  State Quality 和 URL 输出：
  `READY_DEFAULT_BROWSER_STATE_VALIDATOR`、`LOGIN_REQUIRED`、
  `MANUAL_RECOVERY_REQUIRED`、`DEGRADED_STATE_QUALITY` 或
  `BUSINESS_RECOVERY_UNKNOWN`。
- 绑定 `applicationId` 的 Session 会改用 V030 版本化 Application Recovery Contract；
  精确 Origin、Route、可访问性 Target 和 Extension 证据生成持久 Verdict。迁移
  `BUSINESS_VALIDATION` 只有在该 Verdict `ready=true` 时才能完成。未绑定应用的
  Session 仍使用上述保守默认 Validator。详见
  [应用感知 Business Recovery 与 Ready Gate](51-应用感知Business-Recovery-Ready-Gate.md)。
- 只有 `READY` 才把 `PAUSED_BY_RESOURCE_POLICY` Agent 恢复为可继续规划；Login、
  Manual、Unknown 或 Degraded 保持 Agent 暂停。
- 新增 `GET /api/v1/sessions/{id}/migration`，Session 详情显示源/目标 Node、
  Checkpoint、当前阶段和恢复判定。

## 验证

- Control Plane 全量测试通过，包含：
  - Safe Point 缺失信号 fail-closed；
  - 新鲜空闲 Input Ledger 判定 SAFE；
  - Active Input 阻塞；
  - Node gRPC 同时提交资源样本和安全观察；
  - Hibernation Operation/RuntimeStopped → HIBERNATED；
  - Business Recovery Ready/Login 判定。
- Browser Node/Storage 全工作区测试通过；新增跨存储根 Archive 安装和逐文件恢复测试。
- `make test-integration` 通过，实际覆盖 Storage Helper 重启、两次 Checkpoint、
  四次 Profile 恢复、Coordinator 多轮故障接管、Node 重启和 Runtime Crash Recovery；
  新增 Checkpoint ID 契约没有破坏现有恢复链。
- 进度 80 进一步启动两个独立 Browser Node、两套 Storage Helper/Runtime/Profile 根
  和共享 MinIO，验证 Level 1 ACK → Safe Point → Checkpoint → 跨 Node Restore →
  State Resync → Business Validation → Completed；源/目标 `COMMITTED` Marker、
  Placement 和 Context Epoch 均有持久化断言。测试还注册一个排序靠前但缺少
  Generation Floor 能力、gRPC 不可达的旧 Node，证明迁移不会误调度到 N−1 目标。
- Web 全量测试和生产 Build 通过。
- OpenAPI、Buf、N/N-1 Expand-only Gate 通过。
- 本里程碑最初由 PostgreSQL 17 从 V023 实际迁移到 V025；后续应用安全 Lease 已通过
  V029 继续做 Additive 扩展并进入同一集成与 N/N-1 Gate。

## 仍未完成

1. Renderer、Tab、主线程、Agent 延迟、State Diff、Remote Desktop、Browser/Profile
   I/O、Extension 和 x11vnc Media 的真实生产者已完成；仍缺硬件 Codec/GPU Helper
   和目标 Linux 长稳证书。
2. State Collector、Remote Desktop 码率、Encoder Slot 和 Extension Weight 在线
   执行器已完成；仍缺编码器级动态码率/封装/播放和目标环境证书。
3. File Upload/Download 和导航级 Form Submission 的 CDP Signal Producer 已完成；
   Payment/Security、SPA 应用语义和关键事务的通用 Lease API 已完成。仍缺目标业务
   Adapter/自动埋点；未接入的业务不会被伪装成已识别。
4. 仓库级两个独立 Browser Node + S3-compatible Object Storage + CDP 数据面迁移
   E2E 已完成；仍缺正式 Chromium/目标 Linux 下的断点重试、源/目标 Node 故障注入、
   网络分区和长期稳定性证书。
5. Application-aware 声明式 Validator、迁移 Ready Gate 和有界低风险动作执行器已
   完成；V039 又补齐受信 Extension 重启，进度 62 已完成契约作者 UI，进度 73—76
   已完成审批、不可变历史和 Provider Evidence 平台协议；仍缺目标站点 Adapter 与
   真实 Provider/API 凭据联调。
6. PostgreSQL Resource/Migration Event SSE、`Last-Event-ID` 和断线恢复已完成；
   State/Audit 通用事件层与跨 Region Event Bus 尚未完成。
7. Tauri 2 容器与 OS 安全存储已完成；仍缺签名发行、真实 Updater/IdP 和桌面端
   迁移/休眠视觉与网络恢复验收。
