# Environment Saved Views 正式闭环

> 完成日期：2026-07-30
> 数据库版本：V053
> 状态：PostgreSQL 权威配置、正式 API、RBAC、CAS、幂等、审计、Web/Tauri 复用组件
> 和真实端到端验收已完成

## 本轮关闭的缺口

环境页此前只有禁用的 Saved View 入口。V053 将“筛选与列配置”升级为正式租户领域，
不使用 `localStorage`、JSON、内存缓存或 Session 结果快照伪造保存成功：

```text
环境页当前筛选与列配置
→ PERSONAL 或 WORKSPACE Saved View
→ PostgreSQL 权威保存
→ Viewer/Operator/Admin 按权限读取或管理
→ 应用时回写正式 URL 查询参数和列状态
```

Saved View 只保存当前环境页已经支持的配置：

- 主视图：全部、运行中、已停止、异常；
- 精确 Session State；
- 最长 128 字符的服务端搜索条件；
- Runtime、Context、Operation 三列的显示状态。

不保存 Session 列表结果、分页游标、资源指标、Operation 状态或任何前端快照。

## PostgreSQL 权威模型

V053 新增 `environment_saved_views`：

- 以 `tenant_id` 隔离，以 `owner_actor_id` 标识 PERSONAL 所有者；
- `PERSONAL / WORKSPACE`、主视图、精确状态和字段长度均有数据库约束；
- PERSONAL 名称在同租户、同所有者内大小写不敏感唯一；
- WORKSPACE 名称在同租户内大小写不敏感唯一；
- `version` 由 JPA 乐观锁维护，更新时间和版本共同支持 CAS；
- 列表查询只返回当前 Actor 的 PERSONAL 与本租户 WORKSPACE，不跨租户泄露。

迁移是纯新增表和索引。N−1 Gate 已把 V053 纳入空库/升级兼容证据，不要求旧版本认识
新表。

## 正式 API、权限与审计

```text
GET    /api/v1/environment-saved-views
POST   /api/v1/environment-saved-views
PUT    /api/v1/environment-saved-views/{savedViewId}
DELETE /api/v1/environment-saved-views/{savedViewId}?expectedVersion={version}
```

- Viewer 可读取并应用可见视图；
- Operator 可创建和管理自己的 PERSONAL 视图；
- WORKSPACE 的创建、覆盖和删除只允许 Tenant/Security/Platform Admin；
- 修改他人的 PERSONAL 返回 404，避免暴露资源存在性；
- 写接口必须携带 `Idempotency-Key`，重放不重复创建、更新、删除或写审计；
- 更新和删除必须提交 `expectedVersion`，过期版本返回
  `409 SAVED_VIEW_VERSION_MISMATCH`；
- 大小写冲突名称返回 `409 SAVED_VIEW_NAME_ALREADY_EXISTS`；
- 创建、修改和删除进入租户审计链；搜索原文不写审计，只记录 SHA-256。

OpenAPI 已定义完整请求、响应、枚举、版本冲突和权限边界。

## Web Console 与未来 Desktop

环境工具栏新增 `EnvironmentSavedViews`：

- 展示真实 Loading、Error、Empty、Pending 和失败状态；
- 可创建 PERSONAL；管理员可创建 WORKSPACE；
- 可应用、以当前配置覆盖和两步确认删除；
- 清楚说明“保存筛选与列配置，不保存 Session 结果快照”；
- Viewer 只看到应用能力，不挂载写入控件；
- 状态同时使用文字、图标和边框，不只依赖颜色；
- 使用现有 React、TanStack Query、API Client、权限和设计 Token，Tauri 2 复用同一套
  组件与正式 API。

视觉保持 Neo-Industrial Observatory：扁平、紧凑、数据账本式层级，未引入独立桌面
实现或生产 Mock。

## 验收证据

已纳入：

```text
./gradlew -p apps/control-plane spotlessCheck test
pnpm -C apps/web-console test
pnpm -C apps/web-console lint
pnpm -C apps/web-console format:check
pnpm -C apps/web-console build
make contracts-check
python3 tests/upgrade/n-minus-one-gate.py
make test-integration
make test-e2e
```

覆盖重点：

- Java 单测覆盖 PERSONAL 规范化、Workspace 权限、不可见个人视图和版本冲突；
- Web API 测试覆盖身份 Header、GET/POST/PUT/DELETE 和幂等键；
- PostgreSQL Integration 覆盖重放、租户隔离、Viewer 可见性、403/404/409、删除和
  审计条数；
- Playwright 覆盖管理员创建 PERSONAL/WORKSPACE、两步删除，以及 Viewer 只能应用
  WORKSPACE 视图。

## 仍未完成

Saved View 已关闭，不再列为当前产品缺口。环境管理仍需：

1. Environment Import 已由[进度 78](78-Environment-Import正式闭环.md)关闭；
   Profile 内容/Checkpoint Import 已由
   [进度 79](79-Profile-Checkpoint-Import正式闭环.md)关闭；
2. 可复用 Proxy Binding 的 Secret 引用、租户权限、健康检查和绑定 Operation；
3. Group/Tags 批量生命周期、服务端组合过滤和大列表批量投影；
4. 环境“更多操作”、全局搜索、通知、主题和用户菜单；
5. OpenAPI 自动生成并发布 TypeScript Client；
6. 完整屏幕阅读器、200% 缩放、触控、桌面签名和 Windows 真实矩阵。
