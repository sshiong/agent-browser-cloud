# Proxy 业务结果学习、Profile 粘性与受约束探索闭环

> 日期：2026-09-23
> 数据库版本：V129—V130
> 状态：仓库内代码、契约、N/N−1、真实 PostgreSQL/Chromium Integration 闭环

## 本轮目标

在既有质量、信誉、成本、地域和容量确定性路由之上，关闭三个相互依赖的仓库级缺口：

1. 让独立 Outcome Verifier 的真实任务结果形成低权重、可审计的路由学习证据；
2. 对同一 Browser Profile 保持安全的出口粘性，降低登录/风控上下文无故漂移；
3. 用确定性、受硬门槛约束的小比例探索为样本不足的健康候选积累证据。

## 实现

- V129 新增最小化、append-only 的 `proxy_route_business_outcomes` 和聚合表
  `proxy_route_business_stats`。只有独立 Outcome Verifier 的最终 `VERIFIED/NOT_VERIFIED`
  决策可以写入；`verification_id` 与 Task 双重幂等，当前 Assignment 必须早于 Task 创建时间，
  因此任务中途 Rebind 不会被错误归因。
- 账本只保存 Tenant/Session/Task/Binding/Provider 身份、决策、有限原因码和时间，不保存 Goal、
  URL、页面内容、Credential、Capability 或模型输出。Provider 发生变化时旧统计立即失效，首次
  新结果会原子重置聚合，避免跨 Provider 污染。
- 少于 5 个样本固定使用中性 50 分；达到阈值后以带 2/2 先验的成功率和 0.1 alpha EWMA 混合。
  业务结果只占总路由分 10%，不会旁路启用状态、90 秒健康新鲜度、Region、Provider/Secret/出口
  身份、容量和最低质量等硬门槛。
- 同一 Browser Profile 最近使用的候选只有在仍满足全部硬门槛、且得分距最优不超过 8 分时才可
  以 5 分粘性权重优先；劣化或失效出口不会被粘性强留。
- 受约束探索使用 Tenant/Session 的稳定 5% bucket，可重复、可审计；只考虑质量至少 60、少于
  20 个业务样本、距最优不超过 8 分且非当前最优的候选。存在合格 Profile 粘性候选时不探索。
- Assignment 固化 `SCORE / PROFILE_STICKY / CONSTRAINED_EXPLORATION` 选择原因和候选快照；
  OpenAPI、四语言 SDK 与 Web/Tauri Session 详情展示业务结果分、样本数和选择原因。新增响应字段
  对 N−1 Server 保持 optional，历史候选缺字段按中性值兼容。
- V130 使用非事务 `CREATE INDEX CONCURRENTLY` 建立 Profile 粘性查询索引，避免在大 Sessions
  表上以普通建索引阻塞生产写入。

## 验证证据

- Control Plane 全量测试通过；定向测试覆盖稀疏样本中性、贝叶斯/EWMA 混合、Task 前后
  Assignment 围栏、Provider 变化隔离、Profile 粘性和稳定 5% 探索 bucket。
- Web Console 29 个文件、145 项测试通过，lint 与 production build 通过。
- OpenAPI/Redocly、TypeScript/Python/Go/Java SDK 生成、漂移与运行时包测试通过；公开契约保持
  **255 Operations / 354 Schemas**。
- V129/V130 N/N−1 Gate 通过，验证 additive migration、历史 AUTO `selection_reason=NULL`
  兼容、响应字段 optional 和并发索引非事务配置。
- OrbStack 完整 Integration 以真实 PostgreSQL 17、Redis、MinIO、mTLS、Browser Node 和 Chromium
  通过，明确输出 `proxy_business_outcome_learning=true`；同一专用 Binding 上 2 个 VERIFIED、
  1 个 NOT_VERIFIED 形成 `3:2:1:3` 聚合/账本一致性，并验证账本不存在敏感列。全套 Agent、
  Recording、Profile、Recovery、Resource、Coordinator 和安全 Gate 同轮通过。

## 保留边界

- 业务结果是相关性证据，不宣称单次失败必由 Proxy 导致，因此权重受限且稀疏样本保持中性。
- Challenge/站点黑名单的独立领域信号与自动隔离策略仍未实现，不能用普通任务失败冒充黑名单。
- 商业 Provider 认证 Adapter、目标云 Secret Manager、账单对账、动态价格、真实多 Region 容量和
  客户站点 Replay 仍是后续代码或目标环境 Gate。

