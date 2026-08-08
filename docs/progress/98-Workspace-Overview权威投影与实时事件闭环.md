# Workspace Overview 权威投影与实时事件闭环

> 日期：2026-08-01
> 适用范围：Control Plane、PostgreSQL、OpenAPI、Web/Tauri 共享前端
> 数据原则：不使用 Mock、localStorage、JSON 文件或浏览器端全量聚合

## 本轮结论

顶层总览不再读取最多 100 条 Session 后在浏览器中统计。新的
`GET /api/v1/workspace-overview` 在一个 PostgreSQL 查询中生成当前认证 Workspace 的
权威快照；`GET /api/v1/workspace-overview/event-stream` 使用可续传 SSE 发送最小化
失效通知，前端收到通知后重新读取权威快照。

总览现在覆盖 Session、活跃 Operation、Browser Node、Proxy、Agent Task、当前每小时
资源成本与最近 24 小时安全信号。最近 Session 区域仍只读取 8 条轻量列表记录用于导航，
不再参与总数计算。

## 已完成

### 1. PostgreSQL 权威聚合

- 使用一个带 CTE 的有界 SQL 返回所有总览分区和当前事件 Cursor；
- Session、Operation、Proxy、Agent、成本与安全数据严格使用当前认证 Tenant；
- Browser Node 是平台级基础设施信息，仅 `PLATFORM_ADMIN` 返回真实容量；普通角色获得
  `visible=false` 和零值，不泄露跨租户 Node 数量、负载或预留资源；
- 成本使用 `session_resource_policies.current_hourly_cost`，缺少当前定价的活跃 Session
  单独计数，不在前端推测价格；
- 安全汇总只计算当前 Tenant 最近 24 小时 `WARNING / CRITICAL` 通知。

### 2. 可续传 Workspace SSE

- V070 建立 `workspace_overview_events` 全局单调 Cursor，支持 Tenant 事件与受控平台事件；
- Session、Operation、Agent Task 和资源调整通过既有事务事件信封镜像；Browser Node、
  Proxy、成本快照和安全通知通过数据库 Trigger 追加失效事件；
- 原始 5 秒资源样本不会触发 Workspace 级刷新，避免每个 Session 的遥测造成事件风暴；
- SSE 支持 `Last-Event-ID`、历史重放、Cursor 超前 Reset、15 秒 Heartbeat、全局和
  Tenant 级连接上限；
- 非 `PLATFORM_ADMIN` 的连接不会读取平台级 Browser Node 事件；SSE 响应不返回实体 ID
  或业务正文，只返回序号、变化类型、时间和是否为重放；
- Web/Tauri 共享 Hook 处理离线、指数退避重连和 Query 失效，不用定时器伪造数据变化。

### 3. Agent 暂停状态约束修复

代码已会在资源危险事件中写入 `PAUSED_BY_RESOURCE_POLICY`，但 V009 的 PostgreSQL
`chk_agent_task_state` 未允许该值。V070 使用 expand–validate–contract 顺序先增加
`chk_agent_task_state_v2`、验证历史数据，再替换旧约束，避免危险事件在生产写入时被
数据库拒绝，也不留下无约束窗口。

### 4. 总览 UI

- 删除“最多 100 条”“SSE 待接入”和缺少 Node/Proxy/Agent/成本/安全 API 的旧提示；
- 新增真实连接状态、数据库来源、租户隔离范围、生成时间和基础设施/负载摘要；
- 明确显示等待人工、缺少定价与严重安全信号，不只依赖颜色表达状态；
- 采用 `1 → 2 → 4` 指标卡响应式布局，Session 行在窄屏隐藏次要列，主要操作命中区不
  小于 44px；
- 增加内联 SVG favicon，真实浏览器控制台不再产生 favicon 404。

## 验收证据

- `make ci`：Java、Rust、Web、OpenAPI、Proto、N−1、50k Coordinator 容量证书和
  四语言 SDK 全部通过；
- Control Plane 新增聚合映射、断点重放、Cursor 校验和跨权限连接上限测试；
- Web 新增正式 Aggregate API 与跨 Chunk SSE 测试；全量为 21 个测试文件、66 项通过；
- `make test-integration`：真实 PostgreSQL 17 空库执行 70 个 Flyway Migration，验证
  Tenant 隔离聚合、Platform Node 投影、SSE 历史重放和 Agent 状态约束；
- Playwright 在 1440×1000 和 390×844 真实页面验收通过；最终控制台 0 error、0 warning；
- TypeScript/Vite Production Build、ESLint、Prettier、Redocly OpenAPI 和 N−1 Gate 通过。

## 仍未完成

- Overview SSE 仍是单 Region PostgreSQL 事件源；跨 Region Event Bus、目标云 Ingress
  慢客户端和长期连接容量证书未完成；
- 工作区通知中心在本轮仍使用独立的 15 秒正式 API 刷新，后续已由
  [进度 108](108-工作区通知可续传SSE闭环.md)替换为独立可续传通知流；Overview
  失效流不替代通知已读 Cursor 或外部通知渠道；
- `workspace_overview_events` 的目标环境保留期、分区与归档策略需要随生产 Retention
  Policy 和容量数据确定；当前查询与 Cursor 均有索引，但尚无目标规模长期存储证书；
- 本轮不关闭真实 IdP、KMS/HSM、多 Region、Linux 长稳、桌面签名和组织发布 Gate。

因此，本轮关闭的是顶层总览统计失真、缺少服务端投影和缺少实时更新的仓库代码缺口，
不等同于 V16 全量生产发布验收。
