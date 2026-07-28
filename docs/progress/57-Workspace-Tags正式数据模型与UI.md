# Workspace Tags 正式数据模型与 UI

> 完成日期：2026-07-28
> 数据库版本：V035
> 状态：PostgreSQL 权威模型、租户隔离 API、Session 创建/查询投影、Web 管理和真实
> 集成已完成；Group/Tags 批量生命周期 Operation 与列表批量投影优化仍待完成

## 本轮关闭的缺口

此前创建环境向导允许输入逗号分隔标签，但只把结果写入 `sessions.metadata.tags`。
这些字符串没有租户级实体、复用关系、权限、幂等、审计或数据库外键，环境 API 也不会
返回权威标签。V035 将它替换为正式 Workspace Tag 领域模型。

## PostgreSQL 权威模型

- `workspace_tags` 保存 Tenant、名称、说明、颜色、创建人、时间和乐观锁版本；
- 同租户标签名称大小写不敏感唯一；
- `session_tag_assignments` 保存多对多 Session 归属、操作者和分配时间；
- `(session_id, tenant_id)` 与 `(tag_id, tenant_id)` 复合外键同时校验 Tenant，
  跨租户直接写数据库也会被 PostgreSQL 拒绝；
- 删除 Tag 只级联删除 Tag Assignment，不删除或终止 Browser Session；
- Session/Tag 重复归属使用 `ON CONFLICT DO NOTHING`，并发请求不会产生重复关系；
- V035 将旧 `metadata.tags` 逗号字符串确定性回填为 Tag 与 Assignment，保留升级前
  数据；旧字段暂不删除，以兼容 N/N-1 滚动窗口。

## 正式 API、权限与审计

```text
GET    /api/v1/tags
POST   /api/v1/tags
PUT    /api/v1/tags/{tagId}
DELETE /api/v1/tags/{tagId}
PUT    /api/v1/tags/{tagId}/sessions/{sessionId}
DELETE /api/v1/tags/{tagId}/sessions/{sessionId}
```

- 读取要求 Viewer，创建/更新/删除要求 Admin，归属与解除归属要求 Operator；
- 所有写操作使用 `Idempotency-Key`，相同键重放返回同一结果，内容冲突返回 409；
- 创建、修改、删除、初始分配、追加和移除均写入现有防篡改租户审计链；
- 并发同名创建/更新依靠数据库唯一索引最终裁决，并稳定映射为
  `WORKSPACE_TAG_REJECTED`，不返回裸数据库错误；
- 创建 Session 新增可选 `tagIds`，未知或跨租户 Tag 会令整个创建事务回滚；
- Session List/Detail 返回 `tagId/name/color` 受控投影，不暴露任意 Metadata。

## Web Console

- “分组与标签”页面新增正式 Tags 区域，支持 Loading/Error/Empty 状态；
- 支持创建、编辑、两步删除、给现有环境追加和移除标签；
- 创建向导移除自由文本标签，改为读取正式 Tag API 并最多选择 16 个 `tagIds`；
- 环境列表与 Session Detail 展示权威标签颜色和名称；
- Web 对升级期旧 Control Plane 未返回 `tags` 字段保持兼容，不生成 Mock 标签；
- Web/Tauri 继续复用同一 React 组件、API Client、权限与状态逻辑。

## 验收证据

- Java 单元测试覆盖规范化创建、租户 Tag 解析、去重初始分配和未知/跨租户拒绝；
- Web API 测试覆盖认证 Tenant Header、创建/分配/删除幂等键；
- Web ESLint、11 个测试文件/38 项测试和 Production Build 通过；
- OpenAPI Redocly 校验通过；
- V035 N/N-1 Gate 确认只新增表/索引/关系和确定性旧数据回填，证据 Hash：
  `d3db5bf66d076b685b18a8dc13d66961f78f964898f25ba4c871cfc5939de778`；
- 完整 PostgreSQL 17 + Browser Node Integration 通过，覆盖空库 35 个迁移、旧
  Metadata 回填、Tag 创建重放、Session 创建时归属、CRUD、API 跨租户空视图、数据库
  复合外键拒绝、Session List/Detail 投影和原有生命周期/恢复/故障矩阵。

## 明确未完成

1. 按 Group/Tags 批量 Start、Pause Agent、Migrate、Hibernate 与风险确认 Operation；
2. Groups/Tags 页面目前仍会按实体读取成员，规模化租户需要批量查询投影以关闭 N+1；
3. Environment Saved View、按 Tag 服务端组合过滤和批量选择；
4. 全局搜索、通知中心和主题；Workspace Settings 核心持久化已在进度 58 关闭；
5. OpenAPI 自动生成 TypeScript Client；本轮 API Client 仍是手写、契约测试覆盖。
