# Saved View Group/Tag 筛选持久化闭环

> 日期：2026-08-01
> 适用范围：Environment Saved View、Group/Tag 组合筛选、Web/Tauri 共用 UI
> 数据来源：正式 PostgreSQL、正式 Saved View API、Session 服务端筛选

## 本轮结论

Environment Saved View 已能持久保存并恢复 Workspace Group、最多 16 个 Workspace Tag
及 `ANY / ALL` 匹配方式。用户不再需要只靠 URL 保存这组筛选；保存、覆盖、读取和应用
都使用正式 PostgreSQL/API，前端不保存 Session 结果快照，也不使用 localStorage 或 Mock。

## 已完成

### 1. V067 安全扩展迁移

`environment_saved_views` 新增：

- 可空 `group_id`；
- 非空 `tag_ids JSONB`，默认空数组；
- 非空 `tag_match`，默认 `ANY`。

迁移仅执行 expand，不删除、重命名或收紧旧字段。旧行和旧客户端自然解析为无 Group、
无 Tag、`ANY`，不需要全表回填。Group 使用 `group_id + tenant_id` 复合外键，先
`NOT VALID` 后在线校验，并在 Group 删除时只把筛选列置空、保留 Saved View 和租户。

数据库函数 `is_valid_environment_saved_view_tag_ids` 强制验证：

- JSON 必须为数组；
- 最多 16 项；
- ID 必须符合 `tag_...` 格式；
- 不允许重复 ID；
- 少于两个 Tag 时不允许保存无意义的 `ALL`。

### 2. 租户引用与 API 契约

- Create/Update 在写入前复用 Workspace Group/Tag 服务检查同租户引用；
- 跨租户 Group/Tag 不会被保存，统一按不存在处理；
- Tag ID 在服务端去重、排序后持久化，保证语义相同的配置具有稳定表示；
- Idempotency、CAS Version、个人/Workspace RBAC 和 Audit 行为保持不变；
- Audit Detail 新增 Group ID、Tag ID 集合和匹配方式，不记录 Session 结果；
- OpenAPI 新增 `groupId`、`tagIds`、`EnvironmentSavedViewTagMatch`，请求字段保持可选，
  保证 N−1 客户端继续兼容。

### 3. Web/Tauri 共用恢复流程

- 保存和覆盖视图时提交当前 Group、Tag 和 ANY/ALL；
- 应用视图时同步恢复 `groupId/tags/tagMatch` URL 状态，再由现有 Session API 执行
  PostgreSQL 服务端筛选；
- 相同配置判断把 Tag 视为无序集合，不因 URL 中顺序不同产生重复视图；
- Saved View 摘要显示 Group、Tag 数量和匹配方式；
- 工具栏按钮增加稳定可访问名称，视觉上仍显示当前匹配视图名称；
- Viewer 可以读取、应用 Workspace 视图，但没有新建、覆盖或删除入口。

## 迁移运行手册

### 风险

- 新列带常量默认值；PostgreSQL 17 不需要对既有行执行应用层回填；
- Group 外键校验会短暂读取 Saved View 与 Group 索引，但表为低容量配置表；
- 没有为 JSONB 增加 GIN 索引，因为列表查询不按 Tag 反向检索 Saved View。

### 验证

- Flyway 空库执行 V001—V067；
- N−1 Gate 确认 V067 不含 Drop/Rename/Alter Column；
- Integration 直接读取 `group_id/tag_ids/tag_match` 并验证跨租户引用返回 404；
- 管理员真实 Chromium 完成“筛选 → 保存 → 清除 → 应用 → 恢复结果”；
- Viewer Chromium 验证只读权限边界。

### 回滚

应用可先回滚到 N−1：旧版本会忽略新增列，默认值继续保持新 Schema 可写。V067 字段和
校验函数在确认所有新版本实例退出前不删除；物理 Contract 应在独立后续版本中进行，
不与本轮发布合并。

## 可重复证据

- Saved View Application Service 单元测试通过，覆盖 Group/Tag 校验、稳定排序和 `ALL`；
- Web 19 个测试文件、61 项测试通过；API 测试断言新字段进入真实请求正文；
- OpenAPI、JSON Schema、Proto Contract 与 N−1 升级门禁通过；
- 完整 Integration 通过，PostgreSQL 真实值为 `groupId:ALL:2`；
- 管理员和 Viewer 真实 Chromium E2E 均通过；截图：
  `/tmp/agent-browser-cloud-session-flow-saved-view-filters.png`。

## 仍未完成

- Group/Tag 批量归属、批量移除等 Workspace 元数据 Mutation；
- Agent 大列表 N+1 和目标规模容量证书；
- OpenAPI TypeScript Client 自动生成与正式发布；
- 列表级 SSE、跨 Region Event Bus 及目标环境长稳。
