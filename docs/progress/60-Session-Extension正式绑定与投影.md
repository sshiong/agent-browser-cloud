# Session Extension 正式绑定与投影

> 完成日期：2026-07-28
> 数据库版本：V038
> 状态：创建时不可变绑定、旧数据回填、Placement 一致性、API/UI 投影和完整集成验收
> 已完成

## 本轮关闭的缺口

此前 `CreateSessionRequest.extensionIds` 会进入 `session_resource_demands` 和
`browser_placements`，Browser Node 也能按 Placement 加载可信扩展，但 Session 主记录
没有保存创建时的 Extension 集合。资源预留释放或 Placement 变化后，Session
List/Detail 无法回答“这个环境创建时绑定了哪些扩展”，因此还不是完整的创建/查询契约。

本轮将 Extension IDs 固化为 Session 不可变绑定。创建、资源需求和 Placement 使用同一
份规范化集合，Web 不从 Placement 或前端状态反推 Session 配置。

## PostgreSQL 权威模型

- V038 新增 `sessions.extension_ids JSONB NOT NULL DEFAULT '[]'`；
- 迁移从既有 `session_resource_demands.extension_ids` 确定性回填旧 Session；
- 约束要求 JSON Array 且最多 32 项，创建请求仍由 Bean Validation 校验 ID 格式；
- API 拒绝重复 ID；创建服务再做防御性去重和排序，然后同时写入 Session 和不可变
  资源需求；
- Session 终止和 Placement 释放不会删除创建绑定。

该列记录创建事实；`browser_placements.extension_ids` 仍是当前调度/执行事实。集成测试
要求新建 Session 的 Session、Demand 和 Placement 三份集合一致，避免双写漂移。

## API 与 Web Console

- Session List/Detail 新增可滚动兼容的 `extensionIds` 投影；
- OpenAPI 明确数组上限、唯一性、格式和不可变语义；
- 环境列表显示绑定扩展数量，Session Detail 显示真实 Extension ID；
- 创建向导继续从正式 Extension Profile API 选择扩展并提交同一
  `CreateSessionRequest.extensionIds`；
- Web/Tauri 共享同一 Session 类型、API Client 和组件，不增加桌面端独立状态。

## 验收证据

- Java 单元测试覆盖 Session 受控投影；
- Web API 测试覆盖 Extension 创建序列化，12 个测试文件/39 项测试、Lint 和生产构建
  通过；
- OpenAPI Redocly 校验通过；
- V038 N/N-1 Gate 证明只有带默认值的加法迁移，证据 Hash：
  `956459ed602ebc356d8d9294b5d0a406f5c6169fd28d84b2810818f60b5f8189`；
- 完整 PostgreSQL 17 + Browser Node Integration 先构造旧版 Session/Demand 并执行
  V038 回填，再验证新建 Session 的 Session/Demand/Placement 一致、List/Detail 返回
  `unknown.integration`、重复 ID 请求返回 400、Node 使用可信路径启动，终止后绑定仍
  保留；
- 针对远端首次拉取 PostgreSQL 镜像后的启动竞争，Integration 不再以
  `pg_isready` 后静默继续，而是等待真实 `SELECT 1` 成功；超时或容器提前退出会
  fail-closed 并输出 PostgreSQL/Redis 容器日志。本地修复后完整 Integration 再次通过。

## 明确未完成

1. 当前资源遥测仍是 Session 级 Extension 进程聚合，缺逐 Extension/Content Script
   归因；
2. 目标 Linux 委派 Cgroup v2 上真实 Chromium/企业扩展的多 Session 长稳、OOM/PSI
   和权重效果证书；
3. 受信 `RESTART_EXTENSION` Business Recovery 动作；
4. Session Ownership、Group/Tags 等大列表组合查询与 N+1 优化；
5. Extension 包签名、企业分发治理与生产组织 Gate。
