# 双 Browser Node、对象存储与迁移集成闭环

> 日期：2026-07-30
> 状态：仓库级可重复集成证书完成；目标 Linux/Kubernetes 多节点故障矩阵与长期稳定性
> Gate 仍未完成

> 后续进展：目标 Restore 失败后的清理屏障、失败节点排除和三节点有界重试已在
> [进度 81](81-迁移目标清理与有界重试闭环.md) 完成。

## 本轮目标

为 AUTO 资源策略的 `WAIT_SAFE_POINT_MIGRATE` 建立可重复的双节点集成证据，避免只用
单 Node、数据库造数或直接调用内部服务证明迁移成功。

## 已完成

### 独立双节点数据面

- `tests/integration/smoke.sh` 现在启动两个独立 Browser Node：
  `node_integration` 与 `node_integration_b`。
- 两个 Node 使用不同的 gRPC、Remote Desktop、Runtime、Profile Workspace 和
  Storage Helper Unix Socket；共享同一 MinIO S3-compatible Object Storage。
- 创建并启动独立 AUTO Session 后，测试通过正式资源策略 API 将上限设为当前
  Allocation，并注入两个跨 60 秒窗口的持续高压样本。
- 首次达到上限必须先完成 Level 1 非核心降载及真实 Node ACK；第二个独立决策窗口才
  允许进入 Safe Point 迁移，避免把一次峰值或未完成 Operation 误判为迁移条件。
- 安全信号通过新增的 mTLS `ReportSessionResources` 验证程序上报。该程序不填 CPU、
  内存等资源字段，只更新当前 Node/Tenant/Context 的完整空闲输入账本，因此不会稀释
  持续压力窗口或绕过 Control Plane fencing。

### 跨节点 Browser Generation fencing

- 集成测试发现目标 Node 的本地 `browserGeneration` 从 1 重新开始，而 Control Plane
  正确拒绝不大于源节点世代的 `RuntimeStarted`，导致迁移停在 `RESTORING`。
- `StartRuntimeCommand` 新增向后兼容的 `minimum_browser_generation`；Control Plane
  下发已提交 Session 世代，目标 Node 在启动 Runtime 前恢复该下界，新的 Browser
  Generation 必须严格递增。
- 该修复没有放宽 Control Plane 的 `STALE_BROWSER_GENERATION` 检查，也没有把旧事件
  强制提交为成功。
- N−1 Node 会安全忽略新增字段，因此新 Node 固定通过容量心跳声明
  `startRuntimeGenerationFloor=v1`。迁移目标查询只锁定具备该能力、心跳新鲜且 Admission
  开放的 Node，并在 Java 服务层再次校验标签；普通 Session Placement 不受影响。
- 能力过滤在 PostgreSQL 的 64 个候选窗口之前执行，避免大型 N−1 集群把兼容 Node
  挤出查询上限。只有旧 Node 可用时返回
  `NO_MIGRATION_TARGET_WITH_GENERATION_FLOOR_CAPABILITY`，不会向旧 Node 下发 Restore。
- 集成测试会在源 Session 运行后额外注册一个容量充足、排序靠前但不声明该能力且
  gRPC 不可达的 N−1 形态 Node。迁移仍必须选择真实兼容 Node 并完成，以证明能力准入
  不是只存在于单元测试或文档中。

### 可验证的迁移终态

集成测试通过正式 API、Operation、Outbox/Inbox、Node Command 和 PostgreSQL 状态机
验证以下完整阶段：

```text
Level 1 Node ACK
→ SAFE_POINT_READY
→ MIGRATION_CHECKPOINTING
→ 排除源 Node 的目标 Placement
→ MIGRATION_RESTORING
→ MIGRATION_STATE_RESYNC
→ MIGRATION_BUSINESS_VALIDATION
→ MIGRATION_COMPLETED
```

最终断言包括：

- 源 Node 与目标 Node 不同；
- 迁移具有真实 Checkpoint ID、Resync Request ID 和 `recoveryResult=READY`；
- 同一 Checkpoint 的 `COMMITTED` Marker 同时存在于源、目标两个隔离 Storage
  Helper 根目录；
- Session 回到 `RUNNING`，权威 Placement 指向目标 Node，Context Epoch 递增；
- 资源时间线包含 Checkpoint、Restore、State Resync、Business Validation 和
  Completed 事件。

测试中的 Chromium 可执行程序是确定性 CDP test double，用于运行真实进程、端口、
WebSocket/CDP、Profile 和 State 数据面；它不替代目标 Linux 上正式 Chromium 制品的
长期压力证书。

## 已验证

```text
cargo test --locked --manifest-path apps/browser-node/Cargo.toml \
  -p runtime-supervisor -p node-agent --no-fail-fast
./gradlew -p apps/control-plane test --no-daemon
make test-integration
```

完整集成输出包含：

```text
dual_node_migration=true
```

并继续通过现有 mTLS、幂等、Coordinator Failover、Crash Recovery、Object Storage、
Profile Restore、Resource Actuator、SSE、Audit Chain 与 N/N−1 数据库夹具验收。

## 仍未完成

1. 目标 Linux/Kubernetes 上使用正式 Chromium、委派 Cgroup v2、真实多个 Browser
   Node 和生产形态对象存储的长时间压力与容量证书；
2. 源 Node 在 Checkpoint 前后宕机、目标 Node 进程中途宕机、对象存储分区、
   PostgreSQL 延迟及多 Coordinator 重复调度的完整故障矩阵；目标 Restore 启动失败、
   清理确认和换节点重试已在进度 81 关闭；
3. 真实 CRM/支付站点的业务安全 Lease Adapter、Provider Evidence 凭据和恢复验证；
4. 跨 Region State/Object Restore、KMS/IAM、流量切换与 RTO/RPO 证书。
