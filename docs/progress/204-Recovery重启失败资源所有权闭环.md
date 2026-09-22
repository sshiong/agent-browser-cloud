# Recovery 重启失败资源所有权闭环

> 日期：2026-09-22
> 范围：Browser Node StartRuntime 回滚、Profile Writer、Proxy Binding、崩溃恢复

## 结论

崩溃恢复时的 replacement `StartRuntime` 可以复用原 Session 已持有的 Profile Writer
与 Proxy Binding。如果新 Chromium 在 CDP Ready 前失败，该 Start 不再把复用资源当成
自己新建的资源释放；权威 `StopRuntime` 仍可以使用原 Writer 完成 Checkpoint
和最终清理。

## 真实故障与根因

1. 完整 Integration 主动 `SIGKILL` Chromium 后触发自动恢复。
2. replacement Chromium 未在时限内达到 CDP Ready，Start 进入回滚。
3. 旧逻辑无条件释放 Storage Helper Writer 和 Proxy，但 `profile_workspaces` 仍保留该
   Session 的活动 Workspace。
4. 后续权威 Stop 依照该 Workspace 做 Checkpoint 时，Storage Helper 因 Writer 已被
   删除而返回 `ENOENT`，导致操作重试而无法收敛。

## 修复

`release_start_resources` 在回滚前先查询本 Node 的 `profile_workspaces`：

- 已存在 Session Workspace：说明 Start 复用了耐久 Session 资源，保留 Writer 和 Proxy，
  交由后续权威 Stop/Recovery 管理。
- 不存在 Session Workspace：说明资源由本次首启新建，按原逻辑释放 Writer
  和 Proxy。

这一判定保留了首次 Start 失败时的清理语义，同时修复恢复 Start 的所有权混淆。

## 验证

- Node Agent 27 项测试通过。
- OrbStack 完整 PostgreSQL/Redis/MinIO/mTLS/Chromium Integration 通过，故障链输出
  `automatic_crash_recovery=4`、`recovery_operation_committed=2`、
  `profile_checkpoint_epoch=2` 和 `profile_restore_starts=4`，且最终退出码为 0。
- 修复后的完整 `make ci` 通过。

## 剩余边界

该修复关闭单 Node 恢复重启失败时的资源所有权缺口，不替代 Warm Tier
SQLite/LevelDB 应用感知 Adapter、Multipart Resume、真实跨 Region Restore、目标云
KMS/IAM 和长稳 Gate。
