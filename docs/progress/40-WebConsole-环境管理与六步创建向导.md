# Web Console 环境管理与六步创建向导

> 日期：2026-07-27
> 状态：页面重构、真实创建链路、服务端搜索与 E2E 已完成；高级治理 API 仍有明确缺口
> 需求来源：`Agent-Browser-Cloud-环境管理UI修复与创建向导提示词.md`

## 本轮结论

环境管理页已从“页面标题、状态、搜索、创建按钮挤在一行，空数据仍展示巨大空表格”
重构为四个清晰层级：

1. Global Header：Workspace、Control Plane 在线状态、全局动作与当前身份；
2. Page Header：环境管理标题、说明和主创建动作；
3. Page Toolbar：四个主视图、服务端搜索、高级状态筛选、列显示和 Saved View 占位；
4. Content：有数据才展示表格，无数据展示独立引导区和 Runtime → Profile → Proxy →
   Workload 路径。

创建面板已从三步升级为六步。1440 宽度使用
`clamp(620px, 48vw, 760px)` 右侧抽屉，1280 及以下切换全屏；底部操作区固定，正文独立滚动。

## 已完成

### 页面结构与导航

- 侧边栏展开宽度 236px、折叠宽度 68px；1280 自动使用折叠布局；
- 导航项高度 36px，当前项使用 2px 左侧指示条和低饱和背景；
- 侧边栏“新建环境”从整行高饱和按钮改为轻量边框按钮；
- 环境页拥有独立 `<h1>`，Global Header 不再重复页面标题；
- 修复共用 TopContextBar 的标题语义回归，其他页面继续保留 `<h1>`；
- 输入控件提升到 40px/13px，正文和标签对比度、字号与间距重新分层；
- 移除全局通配选择器对 `margin-inline: auto` 的覆盖，1280 全屏向导内容可正确居中。

### 环境列表

- 主视图收敛为“全部 / 运行中 / 已停止 / 异常”；
- 精确状态放入高级筛选，可查询 CREATED、STARTING、HIBERNATED、FAILED 等次级状态；
- 搜索、视图、精确状态和分页同步到 URL；
- 新增租户隔离的服务端搜索 `GET /api/v1/sessions?q=...`，搜索 Session ID、展示名称、
  Profile、区域、资源等级和治理元数据；
- 搜索与状态筛选在 PostgreSQL 执行，不再只过滤当前页；
- 表头 sticky；Runtime、Context、Operation 三组列可即时显示/隐藏；
- 运行态使用左侧 2px Accent，DEGRADED/FAILED 使用图文状态和 Danger 指示；
- 无数据时完全不渲染 Table/Header/Pagination，改为独立引导内容；
- 有数据时保留真实 Start、详情跳转、Operation、Runtime/Node、Context 与分页。

### 六步创建

1. 基本信息：名称、分组、标签、说明、识别色；
2. Runtime 与 Profile：真实 Stable+Signed Runtime Registry、空 Profile、现有 Profile、
   最近 Checkpoint；不再要求普通用户填写原始 Profile ID；
3. 网络与区域：真实 Proxy Provider、真实 Region Admission；生产禁止 direct，
   开发环境才允许；
4. 工作负载与资源：仅 L1 Lite、L2 Standard、L3 Interactive、L4 Heavy；
   L2 为默认推荐，已删除 L5 Native；执行环境作为独立维度；
5. 扩展与 Agent：读取真实 Extension Profile，提交 Agent 策略、Human Takeover、
   Idle Timeout、Snapshot 和 Web3 声明；
6. 检查并创建：展示真实/策略字段边界，调用真实 Create Session API；失败保留表单和
   Request ID，成功显示 `Session CREATED` 和真实 Session ID，不伪造启动成功。

媒体等级已独立为 M0—M4，并映射到后端真实
`mediaWorkload/requestedMediaStreams/mediaBitrateKbps`。启用远程桌面时，UI 会推荐
L3/M2，但 Placement 和 Node 能力仍拥有最终裁决权。

### RBAC 与请求边界

- Viewer 不挂载创建向导，因此不会提前请求 Runtime/Enterprise/Extension 等管理数据；
- 只读角色继续隐藏创建、启动、终止、Agent、接管及管理导航；
- 创建向导仅在有操作权限时加载真实数据；
- Saved View 后续已在[进度 77](77-Environment-Saved-Views正式闭环.md)接入正式
  PostgreSQL/API/RBAC/CAS/审计；创建时复用 Proxy Binding 配置后续已在
  [进度 82](82-可复用Proxy-Binding创建时闭环.md)接入正式 PostgreSQL/API/UI，
  Allocation 仍保持每 Session 独立，不使用 Local Storage 或前端假数据伪造成功。

## 当前真实执行边界

| 向导字段 | 当前服务端行为 |
| --- | --- |
| Profile、Region、Resource Class | 权威创建字段，服务端直接执行 |
| Tabs、Agent actions/min、Remote Desktop、Web3 | 权威容量/能力声明，服务端直接校验或调度 |
| Media M0—M4 | 转换为真实媒体流数和码率预算后提交 |
| Extension IDs | 真实提交，未知/高风险扩展继续由服务端治理 |
| Runtime Build 选择 | 当前作为 `requestedRuntimeBuildId` 治理偏好；最终 Build 由后端策略选择 |
| Resource Template ID | 当前作为治理元数据；权威调度仍使用 Resource Class 与容量预算 |
| Execution Environment | 当前作为治理元数据；不会绕过 Node 隔离能力 |
| Group、Tags、Description、Accent | 持久化到受控 metadata；列表 API 当前只投影 displayName |
| Agent Policy、Idle、Snapshot、Takeover preference | 当前为治理元数据；操作所有权与审计仍由既有后端强制 |

UI 在检查页明确展示上述边界，没有把治理偏好描述为已经由 Runtime 或 Placement 强制执行。

## 仍未完成

1. Saved View 领域模型、API、租户共享与权限后续已在进度 77 关闭；
2. Environment Import 已在进度 78 关闭；Profile/Checkpoint Import 已在进度 79
   关闭，不再属于未完成项；
3. 创建时可安全复用的 Proxy Binding 配置已在进度 82 关闭；运行中 Rebind
   Operation、多 Provider、后台主动探测和目标云 Secret 解引用仍未完成；
4. Runtime Build、Resource Template、Execution Environment 和 Agent Policy 的一等
   Create Session 契约及调度强制执行；
5. Session 列表的 Group/Tags/Agent/Extension/Template 受控投影；
6. Region、Group、Resource、更新时间等组合式服务端筛选和服务端排序；
7. “已停止/异常”多状态分组查询；当前主视图分别使用 TERMINATED/DEGRADED，
   HIBERNATED/FAILED 通过精确状态筛选；
8. 超大列表 TanStack Virtual；当前已有服务端分页和 sticky header，但未启用虚拟滚动；
9. 环境级更多操作菜单；当前保留禁用入口，避免伪造操作；
10. OpenAPI 自动生成 TypeScript Client；当前仍使用集中式手写 API 模块；
11. 正式桌面视觉基线、全键盘/屏幕阅读器和目标浏览器矩阵。

## 验收证据

```text
pnpm --filter @browsercloud/web-console build
pnpm --filter @browsercloud/web-console lint
pnpm --filter @browsercloud/web-console test
./gradlew -p apps/control-plane test
make test-e2e
```

结果：

- Web Console production build 成功；
- ESLint 0 warning；
- Vitest 8 files / 26 tests 通过；
- Control Plane 全量测试通过；
- `WEB_CONSOLE_E2E_OK`；
- `WEB_CONSOLE_VIEWER_RBAC_OK`；
- `real_web_console_e2e=true`；
- `viewer_rbac_e2e=true`；
- `health={"status":"UP"}`；
- 1440×900、1280×800 手动 Playwright 视觉检查通过，六步可导航，Console Error 为 0；
- E2E 实际覆盖服务端名称搜索，确认 PostgreSQL 原生查询与分页排序可用。

完整 E2E 继续覆盖 Session 创建/启动/终止、Browser State/Resync、Proxy、noVNC 输入、
断线 release barrier、Agent、安全治理、Profile Checkpoint 和 Viewer/RBAC，证明本次 UI
改造未破坏既有主链路。
