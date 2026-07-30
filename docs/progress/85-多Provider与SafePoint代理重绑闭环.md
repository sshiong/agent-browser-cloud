# 多 Provider 与 Safe Point 代理重绑闭环

> 日期：2026-07-30  
> 状态：仓库内正式闭环；目标云 Secret Manager、商业 Provider Adapter 和长期稳定性 Gate 待完成

## 本轮目标

关闭进度 82 之后仍存在的两个仓库级缺口：

1. Network Helper 只能使用单一静态 Provider，Session 无法绑定不同 Provider/Secret Ref；
2. 运行中的 Session 无正式代理重绑 Operation，不能按 Safe Point、Checkpoint、
   Restore、State Resync 和 Business Recovery 顺序受控切换出口。

实现遵守以下边界：

- Provider Credential 只传递不透明引用，不进入 Session API、日志或 Node Journal；
- 前端不能直接修改代理、节点或 cgroup；
- 重绑不是连接透明操作，必须明确 Browser 重启、连接重建和业务恢复风险；
- HumanTakeover、文件传输、表单提交、关键业务 Lease 或 Recovery Unknown 时
  fail-closed，不开始迁移；
- Web 与 Tauri 复用同一 React 组件、API Client、RBAC 和事件流。

## 已完成

### 1. Provider Catalog 与真实 Network Helper 路由

- Control Plane 与 Network Helper 支持版本化 `PROXY_PROVIDER_CONFIG_FILE`：
  `providerId + credentialRef` 精确映射到 Endpoint 和 Expected Exit IP；
- 配置文件要求绝对路径、普通非符号链接文件、安全权限、最大 1 MiB、版本 1、
  1—256 个 Provider，重复键和无效 Endpoint/IP 启动即失败；
- `StartRuntimeCommand` 以 additive optional 字段传递 Provider ID、Expected Exit IP
  和不透明 Credential Ref；N−1 只在目录中恰好一个 Provider 时允许空描述符兼容；
- Network Helper 为每个 Provider 独立维护 HTTP Client、熔断器和绑定状态，严格按
  Session 描述符选择，绝不回退直连；
- Control Plane 创建/更新 Proxy Binding 时校验 Provider、Credential Ref 和
  Expected Exit 是否确实存在于目录；多 Provider 时不允许隐式默认；
- Browser Node 心跳声明 `proxyProviderDescriptor=v1`。携带代理绑定的创建、恢复和
  迁移只选择声明能力的 Node，混合 N/N−1 集群无兼容节点时 fail-closed。

### 2. V060 Safe Point Proxy Rebind

- `session_migrations` 增加 `workflow_type`，区分 `NODE_MIGRATION` 与
  `PROXY_REBIND`，并持久化源/目标 Binding 快照、目标版本、Actor、Reason、
  Idempotency Key、Request ID 和恢复结果；
- 新增管理员 API：
  - `POST /api/v1/sessions/{id}/proxy-binding:rebind`
  - `GET /api/v1/sessions/{id}/proxy-rebind`
- 请求先锁定 Session、复核 Safe Point 和目标 Binding 版本，然后执行：

```text
Safe Point
→ Hibernate / Profile Checkpoint
→ 源 Proxy Release
→ 提交新的持久 Assignment
→ 优先同 Node 安全重启，必要时重新 Placement
→ Restore
→ State Resync
→ Business Recovery Validation
→ Agent Resume
```

- 只有 Session 已进入 `HIBERNATED` 且源 Runtime Allocation 已 `RELEASED` 时，
  才提交目标 Assignment；目标禁用、版本变化、Region 不匹配或目录不匹配均拒绝；
- 普通 Node Migration 在源代理释放后会从原持久 Assignment 创建新的 Runtime
  Allocation，不再复用已释放的 `pxy_…`；
- 写操作使用真实 Operation/Workflow，重复 Idempotency Key 返回同一工作流，
  相同 Key 不同目标返回冲突；Operator 无管理员权限返回 403。

### 3. Web/Tauri 共享资源组件

- Session Detail 新增 `ProxyRebindPanel`，展示当前 Assignment、可用目标、Safe Point、
  当前排他 Operation、真实 Workflow Phase、Request ID、失败原因与连接中断风险；
- HumanTakeover、活跃 Operation、Unsafe Safe Point、当前 Binding 相同或非管理员时
  禁用提交；
- 提交前二次确认 Browser 重启、TCP/WebSocket 重连、Profile Flush、State Resync
  和 Business Recovery；
- 统一 Session SSE 会刷新 Proxy Rebind、Session、Safe Point 和恢复状态；
  Remote Desktop 也挂载同一事件流，避免离开 Session Detail 后 Operation 停留在旧值；
- Automation 页面同样订阅统一事件流，Browser State Target Revision 变化时立即刷新；
  已起草的旧 Revision 动作继续 fail-closed，不会被前端静默改绑；
- 不生成 CPU、内存、代理健康或迁移进度 Mock。

### 4. Node 指令与事件可靠性加固

完整集成测试发现并关闭了三个已有可靠性缺口：

- gRPC 调用方超时/崩溃时，Node 指令任务会脱离请求生命周期继续完成副作用、
  Journal 提交和 Event 投递；并发重复命令返回 `COMMAND_IN_PROGRESS`，不伪造成功；
- Node Event 建立连接和发布有明确 5 秒总超时，Control Plane 不可达时进入持久重投；
- 首次投递与后台重投按 Event ID 单飞，并在锁内复查 SQLite Journal，防止同一个
  Crash/RuntimeStarted Event 并发提交两次导致 Operation Epoch 漂移。

故障注入测试也改为先排空旧 Node Command Outbox、等待目标命令被分发器认领，再终止
Control Plane，稳定验证“副作用已执行、结果未提交、换主后只执行一次”。

## 真实验收

本轮已通过：

- Control Plane Spotless 与全部 Java 测试；
- Browser Node 全 workspace 测试、Rustfmt、Clippy `-D warnings`；
- Web production build、54 项 Vitest、ESLint、Prettier；
- 真实 Web Console Playwright 全流程与 Viewer RBAC；长连接页面按 DOM/业务状态验收，
  不再错误等待 SSE 连接进入 `networkidle`；
- Tauri Cargo Check、2 项原生安全边界测试、无签名 Release 构建；
- OpenAPI/Buf、V060 空库迁移、N/N−1 Gate、Kubernetes Kustomize；
- PostgreSQL 17、Redis、MinIO、mTLS Control Plane、三 Browser Node、三套
  Storage Helper 和 Network Helper 完整烟测。

完整烟测真实断言：

- Operator 重绑返回 403；
- Admin 重绑与 Idempotency Replay 返回同一 Workflow；
- 源 Allocation 为 `RELEASED`、目标 Allocation 为 `BOUND`；
- 重绑完成 State Resync 和 Business Recovery；
- 既有双 Node Migration 在代理释放后创建新 Allocation，并完成目标故障清理、
  换节点 Restore 和最终 Placement；
- Coordinator 故障期间 Agent 副作用只执行一次。

## 仍未完成

以下能力不能因本轮完成而宣称生产就绪：

1. Vault/AWS Secrets Manager/Azure Key Vault/GCP Secret Manager 的短期凭据解析、
   轮换、撤销和最小权限身份；
2. 带认证上游的商业 Proxy Provider Adapter、配额/质量/成本模型和主动健康探测；
3. DNS 污染、Provider 单向故障、Credential 过期、出口漂移和跨 Region 长稳矩阵；
4. 目标 Linux/Kubernetes 多 Node 正式 Chromium、CNI 防逃逸和长时间 Rebind 压测；
5. 用户可见的 Provider 质量趋势、成本解释和告警；普通用户仍不应看到 Secret 正文。
