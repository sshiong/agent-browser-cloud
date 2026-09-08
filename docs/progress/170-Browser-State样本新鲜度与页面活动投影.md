# Browser State 样本新鲜度与页面活动投影

日期：2026-09-08。承接 progress 165 的 A07；本切片已完成并通过完整 Gate。

## 权威采样时间

V114 为 `browser_states` 增加可空 `observed_at`。它记录控制面收到最后一个权威 Browser Node
全量或 Diff 样本的时间，因此不依赖 Node 墙钟。`invalidate` 和 `markResyncing` 只更新投影，
不会推进 `observed_at`，避免 observer gap 或 Resync 把旧页面伪装成刚采集。滚动升级中的旧行
使用原 `updated_at` 保守回退；迁移只增加可空列，不全表回填或锁表改非空。

稳定页面不会持续产生新的完整 State/Diff。Browser Node 因此新增 payload-minimal
`BrowserStateObserved`，只携带 Session、State Version、Target Revision 与 Content Hash。
Control Plane 在行锁内精确核对 Tenant、Context Epoch 及上述三项状态围栏后才推进
`observed_at`；任一不符即以 `STALE_BROWSER_STATE_OBSERVATION` 拒绝。Node Journal、mTLS Event
和 Inbox 保留其可靠投递语义；已被新 State 取代的旧 observation 会作为可安全丢弃的终态事件
收尾，不会永久阻塞 Journal。心跳按 15 秒合并，低于 30 秒 STALE 边界，同时避免把每次自适应
采样都变成数据库写入。合并计时只从控制面确认交付后开始；若 failover 把旧 Term 事件终结，Node
不会把它误当成新鲜证据，而会在新 Term 下立即补发。迁移中的 trigger 会忽略仅 `observed_at`
变化，因此稳定页面不产生公开 Session SSE/refetch 噪声。

## Agent 可消费的新鲜度

Browser State 正式 API 新增四个可选滚动兼容字段：

- `observedAt`：最后权威样本的控制面接收时间；
- `ageMillis`：响应生成时的样本年龄；
- `freshness`：10 秒内 FRESH、30 秒内 AGING，之后或证据不可用时 STALE；
- `pageActivity`：结合 `document.readyState`、连续 Network evidence 和 quiet time，保守归类
  CHANGING、SETTLING、STABLE 或 UNKNOWN。

结构化 `snapshot/find/inspect/act` 入口在 STALE 时以 `BROWSER_STATE_STALE` 拒绝，迫使调用方先
刷新状态，不能继续用旧 cursor 规划。Web/Tauri 共用的 Authority State 面板显示 freshness、
pageActivity 和 age，Agent/操作员可直接判断证据是否仍可用。

本轮只关闭 A07 的采样年龄和活动可见性；A20 所要求的 DOM/Layout/Focus/Route 组合稳定性和
A06 动态微批次仍独立跟踪，不以 document/network 的基础分类冒充完成。

## 验证

- 新增控制面时钟阈值、证据失效、页面活动三类单元回归；
- 新增旧样本 invalidation 不刷新 observedAt、Diff 推进 observedAt、心跳精确围栏及 15 秒合并
  的仓储/Node 回归；
- 新增 STALE 状态拒绝结构化 snapshot，以及 Web 新鲜度/年龄渲染回归；
- Control Plane 520 项、Rust Workspace（node-agent 20 项）、Web 140 项及 Python/Go Worker/
  Provider 测试通过；完整 Test/Lint/Build 通过；
- OpenAPI/Protobuf lint、四 SDK 生成（240 Operations / 320 Schemas）、README 文档检查和 V114
  N/N−1 additive Gate 通过；
- 最终完整 PostgreSQL/Redis/MinIO/mTLS/Chromium Integration 通过，输出
  `browser_state_freshness=true`；它覆盖超过旧 30 秒失败窗口后的 Agent Browser snapshot、
  `observed_at` 落库及现有 19 个持久 Workflow。第一次集成曾真实发现“稳定页面无新 Diff 而被
  判 STALE”；第二次发现 failover 期间“写入 Node Journal”不能等价于“控制面已确认观测”。最终
  改为受围栏、确认后计时、旧 Term 不计新鲜度且不触发公开 SSE 的 observation heartbeat；
  集成也显式等待 SIGSTOP/双 Coordinator 替换后的权威恢复，超时仍失败。最终完整集成通过
  （134 个 Node Inbox Event、70 个已发布命令、356 个有效审计事件）。
