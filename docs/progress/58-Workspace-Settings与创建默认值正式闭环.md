# Workspace Settings 与创建默认值正式闭环

> 完成日期：2026-07-28
> 数据库版本：V036
> 状态：Workspace 默认值、正式创建契约、RBAC、幂等、审计、Web 管理和
> PostgreSQL/Browser Node 集成已完成

## 本轮关闭的缺口

此前 Settings 页的工作区名称、默认 Runtime 和 HumanTakeover 只是带
`defaultValue` 的展示控件，保存按钮不会写入后端。创建向导虽然允许选择 Runtime 和
HumanTakeover，却把它们写入任意 `metadata`；Control Plane 启动 Session 时仍使用
进程级默认 Runtime，人工接管开关也不会被服务端执行。

本轮将这些字段升级为权威 Workspace Settings 和正式 Session 创建契约。前端不再以
本地状态、`localStorage`、JSON 或内存数据伪造设置已保存。

## PostgreSQL 权威模型

- V036 新增租户级 `workspace_settings`，保存工作区名称、默认 Runtime Build、默认
  Region、HumanTakeover 默认值、更新人、更新时间和版本；
- 默认 Runtime 使用外键指向 `runtime_builds`，写入前还必须通过现有稳定发布、
  回归稳定和供应链证据 Gate；
- `sessions.human_takeover_enabled` 是创建时不可变绑定，旧数据使用兼容默认值
  `TRUE`；
- 尚未建立租户覆盖时，GET 明确返回 `source=SYSTEM_DEFAULT`、`version=0`，不会为
  一次读取伪造一行持久数据；保存后返回 `source=WORKSPACE_OVERRIDE`；
- Resource Policy 的工作区基线继续是 `AUTO + PAUSE_AGENT`。普通用户不选择
  L1—L5，内部 Resource Template 仍只由 Control Plane 调度使用。

## 正式 API、权限与审计

```text
GET /api/v1/workspace-settings
PUT /api/v1/workspace-settings
```

- GET 使用 Viewer 读取权限，PUT 要求 Tenant/Security/Platform Admin；
- PUT 必须携带 `Idempotency-Key`，相同请求重放不增加版本，不重复写审计；
- 保存工作区设置会进入既有租户防篡改审计链，并携带 Request ID；
- OpenAPI 定义 Workspace Settings Request/View，并将 Runtime 和
  HumanTakeover 加入 `CreateSessionRequest`；
- 资源调整仍由 Resource Operation/Browser Node ACK 执行，Settings 不允许前端
  直接改节点资源。

## 创建与运行时语义

创建请求新增正式字段：

```json
{
  "runtimeBuildId": "runtime_local_chromium",
  "humanTakeoverEnabled": true
}
```

解析优先级为：

```text
创建请求显式值
→ Workspace 覆盖
→ 服务端系统默认值
```

- Runtime、Region 和 HumanTakeover 在创建时固化，之后修改 Workspace Settings
  不会重写存量 Session；
- 启动使用 Session 已绑定的 Runtime，不再无条件使用 Control Plane 进程默认值；
- N-1 版本创建且尚未绑定 Runtime 的旧 Session，在启动时回退到当前 Workspace/系统
  默认值，保留滚动升级兼容窗口；
- `humanTakeoverEnabled=false` 时，服务端拒绝人工接管并返回
  `409 HUMAN_TAKEOVER_DISABLED`，不只是在 Web 隐藏按钮；
- Group 或创建请求中的显式 AUTO Policy 仍按原优先级生效，不会被 Workspace
  系统基线覆盖。

## Web Console

- Settings 页接入真实 GET/PUT，支持 Loading、Error、Dirty、Pending、Success、
  Request ID 和重试状态；
- Runtime 下拉只展示发布通道稳定、回归稳定且签名验证通过的 Build；
- 工作区名称显示在侧边栏；Settings 页窄屏改为可横向滚动的分区导航，字段标签与控件
  已关联；
- 创建向导读取 Workspace 默认 Runtime、Region 和 HumanTakeover，并提交正式字段，
  不再把它们写入 `metadata`；
- Session Detail 显示 HumanTakeover 服务端能力；禁用时按钮不可操作，并解释服务端
  拒绝边界；
- Web 与 Tauri 继续复用同一 React 组件、API Client、权限和 Query Cache。

## 验收证据

- Java 单元测试覆盖透明系统默认、持久覆盖、稳定 Runtime Gate、幂等审计和禁用
  HumanTakeover 拒绝；
- Web API 测试覆盖认证 Tenant Header、GET、PUT 和幂等键；
- Web ESLint、12 个测试文件/39 项测试和 Production Build 通过；
- OpenAPI Redocly 校验通过；
- V036 N/N-1 Gate 确认只新增表和可空/带默认值字段，证据 Hash：
  `2f4b3c6d6223a9842e4b461a1244b3b5f5d52f1dd4497a0531831df9aa8450fa`；
- 完整 PostgreSQL 17 + Browser Node Integration 覆盖系统默认、租户覆盖、幂等重放、
  Viewer 越权拒绝、跨租户隔离、创建默认继承、正式 List/Detail 投影和禁用接管拒绝。

## 明确未完成

1. 创建时 Proxy Binding 已在进度 82 关闭；运行中 Rebind、多 Provider、主动探测、
   目标云 Secret 解引用和环境更多操作仍未完成；Saved View 已在
   [进度 77](77-Environment-Saved-Views正式闭环.md)关闭，Environment Import 已在
   [进度 78](78-Environment-Import正式闭环.md)关闭，Profile Import 已在
   [进度 79](79-Profile-Checkpoint-Import正式闭环.md)关闭；
2. 全局搜索、通知中心和主题切换；
3. Agent Policy 一等契约和执行强制已在
   [进度 59](59-Agent-Policy一等契约与执行强制.md)关闭；
4. Group/Tags 批量生命周期 Operation、服务端组合过滤和列表批量投影优化；
5. OpenAPI 自动生成并发布 TypeScript Client；
6. Settings/Enterprise/Session Detail 的完整 200% 缩放、屏幕阅读器、触控与浏览器矩阵；
7. 真实企业 IdP、桌面签名发行、目标 Linux/双 Node 和多 Region 生产 Gate。
