# Environment Import 正式闭环

> 完成日期：2026-07-30
> 数据库版本：V054
> 状态：PostgreSQL 权威预检/执行账本、正式 API、幂等、CAS、审计和 Web/Tauri
> 共享导入工作区已完成

## 本轮关闭的缺口

环境页原“导入配置”只是禁用占位。V054 将批量创建环境升级为正式领域能力：

```text
本地 JSON 清单（瞬时输入）
→ Control Plane 只读预检
→ PostgreSQL 保存清单哈希、逐项校验与版本
→ 操作者确认
→ 单事务创建全部 Session
→ 返回真实 Session ID / Operation ID / Request ID
```

导入不使用 `localStorage`、生产 Mock、内存 Job 或 JSON 文件保存执行结果。数据库只保留
显式非秘密创建契约；Cookie、密码、Token、上传文件和浏览器状态不允许进入导入模型。

## 预检与提交边界

正式 API：

```text
GET  /api/v1/environment-imports
GET  /api/v1/environment-imports/{importId}
POST /api/v1/environment-imports:preview
POST /api/v1/environment-imports/{importId}:commit
```

- 每个清单固定 `schemaVersion=1`，一次 1—25 个环境；
- 预检校验 Runtime 已发布、现有 Profile 属于当前租户、Group/Tags 存在、Application
  Recovery Contract 已启用并批准；
- 资源策略只接受 `AUTO`；`TERMINATE_STRICT` 仍要求 Platform Admin；
- 预检不会创建缺失 Profile、Session、Operation 或任何 Browser 资源；
- 提交要求预检全部通过并携带 `expectedVersion`，防止过期预检被静默执行；
- 提交复用正式 `SessionApplicationService.create`，每一项返回资源策略创建的真实
  Operation ID；
- 全部创建位于同一事务。任一项失败，Job、Session、Operation、Profile、Tag 与审计
  写入全部回滚，不发布部分成功；
- preview/commit 均要求 `Idempotency-Key`，重放返回同一权威 Job；
- Job 仅对同租户当前创建操作者可见，避免通过批量清单探测其他操作者数据；
- 预检和提交都写入租户哈希审计链，审计只记录清单哈希和计数，不记录秘密。

## 数据库与 N/N−1

V054 纯新增：

- `environment_import_jobs`：所有者、Manifest SHA-256、状态、计数、时间和乐观锁版本；
- `environment_import_items`：显式请求 JSONB、逐项哈希、校验错误和真实执行结果；
- 复合外键保证 Item 与 Job 租户一致；
- CHECK 约束限制状态机、ID、JSON 类型和已提交结果完整性；
- 没有删除、重命名或修改旧表，N−1 进程可直接忽略新表；
- 回滚方式是先禁用新 API/UI，再由后续受控迁移移除表；生产不执行破坏性即时回滚。

## Web Console 与 Desktop 复用

环境页新增右侧 `EnvironmentImportDrawer`：

- 顶部和空状态都提供正式“导入环境”入口；
- Web File API 只在用户选择后读取最长 256 KiB JSON，Web 与 Tauri WebView 复用；
- 页面展示上传、服务端预检、事务提交三个明确阶段；
- 校验失败逐项显示稳定错误码对应文案；
- 提交成功显示正式 Session ID 与 Operation ID；
- API 失败显示真实 Request ID；
- 最近记录来自正式列表 API，不在浏览器中保存历史；
- 状态同时使用文字、图标和边框，不只依赖颜色。

## 当前验收证据

已通过：

```text
./gradlew -p apps/control-plane spotlessApply test
pnpm -C apps/web-console test
pnpm -C apps/web-console build
make contracts-check
python3 tests/upgrade/n-minus-one-gate.py
make test-integration
make test-e2e
```

覆盖：

- Java：重复清单作为逐项错误持久化且预检零创建；提交发布真实 Session/Operation ID；
- Web API：身份 Header、preview/commit 幂等键和 expectedVersion；
- OpenAPI：四个端点、显式 Manifest、Job/Item 状态与错误边界；
- N−1：V054 纯新增表且无破坏性 DDL；
- PostgreSQL 17：V001—V054 空库迁移，HTTP 403/404/409、租户/Actor 隔离、
  preview/commit 重放，以及第一项已写入、第二项 Profile 租户冲突时 Session/Profile/
  Operation/Job 状态全部回滚；
- Playwright：管理员从文件预检、提交、读取真实 Session/Operation 和列表即时刷新；
  Viewer 不渲染创建或导入入口。管理员与 390px Viewer 流程均无 Console/HTTP 错误。

## 仍未完成

Environment Import 的核心闭环已关闭。以下仍是独立缺口：

1. Profile 内容导入、Checkpoint 上传与对象存储审计链；
2. 可复用 Proxy Binding 的 Secret 引用、健康检查、租户权限和绑定 Operation；
3. 导入超大清单的异步分批策略；当前有意限制为最多 25 个并采用全事务语义；
4. Group/Tags 批量生命周期、服务端组合过滤和列表批量投影；
5. 全局搜索、通知、主题、环境更多操作和 OpenAPI 自动生成 TypeScript Client；
6. 目标云、多 Node、桌面签名、Windows 与完整辅助技术生产矩阵。
