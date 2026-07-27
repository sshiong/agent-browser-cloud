# Safe Point、自动休眠与跨节点迁移闭环

> 日期：2026-07-28
> 状态：仓库内核心执行链和单元/契约/数据库验收完成；真实双 Browser Node + S3 全链路 E2E、业务专用 Validator 和外部事务信号生产者待完成

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
- 当前已经有真实生产者的是 Input Ledger、Operation、Agent Task 和 Durable Workflow。
  File Transfer、Form Submission、Payment/Security 等业务信号模型已保留，但对应业务
  子系统尚未全部实现生产者，因此不能宣称任意网站都已具备业务安全点识别。

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
- Web 全量测试和生产 Build 通过。
- OpenAPI、Buf、N/N-1 Expand-only Gate 通过。
- PostgreSQL 17 从 V023 实际迁移到 V025，Flyway `Schema version: 025`。

## 仍未完成

1. Renderer、Tab、主线程、Agent 延迟、State Diff、Profile I/O、Extension、
   Remote Desktop 和 Media 的真实细分指标。
2. State Collector 预算、Encoder Slot、Remote Desktop 码率和 Extension Weight
   在线执行器。
3. File Upload/Download、Form Submission、Payment/Security 和应用关键事务的真实
   Signal Producer/Lease；当前不会伪造这些状态。
4. 两个真实 Browser Node + S3-compatible Object Storage + 真实 Chromium 的迁移
   E2E、断点重试、源/目标 Node 故障注入和长期稳定性证书。
5. Tenant/Application-aware Business Recovery Validator 插件；默认 Validator 只能
   给出保守的通用浏览器状态结论。
6. PostgreSQL Resource/Migration Event SSE、`Last-Event-ID` 和断线恢复已完成；
   State/Audit 通用事件层与跨 Region Event Bus 尚未完成。
7. Tauri 2 容器、安全存储、签名和桌面验收。
