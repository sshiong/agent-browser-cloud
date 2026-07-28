# Workspace Groups 正式数据模型与 UI

> 完成日期：2026-07-28
> 数据库版本：V033
> 验收口径：真实 PostgreSQL、正式 API、租户/RBAC、幂等审计和 Web 联调均通过后才计为完成。

## 本轮关闭的缺口

此前 Groups 页面直接读取开发 Fixture，生产构建隐藏路由，Session 与 Group 之间没有
权威关系，也无法把 Group 默认资源策略用于真实创建流程。本轮已移除这条生产 Mock
链路。

### PostgreSQL 权威模型

- 新增 `workspace_groups`，持久化租户、名称、说明、颜色、默认达到上限行为、
  Migration/Hibernate 开关、创建人、时间和乐观锁版本；
- 同租户 Group 名称按规范化值唯一；
- `sessions.group_id` 使用可空外键，删除 Group 时 `ON DELETE SET NULL`，不会删除或
  终止 Browser Session；
- 增加租户/更新时间和租户/Group/创建时间索引；
- V033 迁移已通过空库全迁移与 N/N-1 加法兼容 Gate。

### Control Plane 与契约

正式接口包括：

```text
GET    /api/v1/groups
POST   /api/v1/groups
PUT    /api/v1/groups/{groupId}
DELETE /api/v1/groups/{groupId}
PUT    /api/v1/groups/{groupId}/sessions/{sessionId}
DELETE /api/v1/groups/{groupId}/sessions/{sessionId}
```

- 所有读写均按 Tenant 隔离；
- 创建、更新、删除、归属和解除归属使用 Idempotency-Key；
- 写操作进入现有防篡改审计链并保留 Request ID；
- Group 管理由 Tenant Admin/Security Admin/Platform Admin 执行；
- `TERMINATE_STRICT` 默认策略仅 Platform Admin 可配置；
- 创建 Session 新增正式 `groupId` 字段；Group 不存在或跨租户时 fail closed；
- 未显式提交 `resourcePolicy` 时，Session 继承 Group 的 AUTO 默认策略和
  `standard-v1` 最低模板；显式 Session 策略优先，不被 Group 覆盖；
- Session 视图与协调器 Descriptor 均携带 `groupId`。

### Web Console

- Groups 路由在生产构建中正式可见，不再依赖 Fixture 开关；
- 页面从正式 API 展示 Group 数、已分组/未分组环境、成员状态和默认策略；
- 支持创建、编辑、两步删除确认、分配和解除环境归属；
- 创建环境向导从真实 Group API 读取选项，提交 `groupId`，并预览将继承的默认 AUTO
  策略；
- Viewer 只读，管理操作按现有身份角色显示；
- 删除了未被正式页面使用的 `src/mocks/data.ts`、`FixtureNotice` 和运行时 Fixture
  开关，避免生产代码再次回退到伪数据。

## 验收证据

- Web ESLint：通过；
- Web 单元测试：10 个文件、37 项通过；
- Web 生产构建：通过；
- Control Plane `spotlessApply check`：通过；
- OpenAPI Redocly lint：通过；
- V033 N/N-1 Upgrade Gate：通过；
- 完整 PostgreSQL + Browser Node Integration Smoke：通过，包含 Group 创建幂等、
  Session 归属、默认 `standard-v1` 策略继承、租户隔离和既有生命周期/迁移/恢复矩阵。

## 明确未完成

1. Tags 正式 PostgreSQL 模型、API 与 UI 已在
   [进度 57](57-Workspace-Tags正式数据模型与UI.md) 完成；
2. Group/Tags 批量 Start、Pause、Migrate、Hibernate 等生命周期 Operation 未实现；
3. Group/Tags 列表当前按实体查询成员，数量较大时需要批量投影，关闭 N+1；
4. Settings 持久化、全局搜索、通知中心和主题仍未完成；
5. Group 默认值只影响新建且没有显式策略的 Session；修改 Group 不会静默重写存量
   Session，存量批量变更需要独立 Operation、权限和风险确认。
