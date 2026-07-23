# Agent Browser Cloud Web 管理端 UI 设计与工程实现提示词
## Web 优先、跨平台复用、企业级真实数据版本

> 用途：将本文档整体交给 Claude Code、Codex、Cursor、Windsurf 或其他代码生成工具，用于开发 Agent Browser Cloud 的正式 Web 管理端。
>
> 当前阶段：**以 Web 管理端为主**。
>
> 后续阶段：基于相同 UI、业务组件、数据模型和 API Client，扩展为：
>
> - Windows 桌面客户端
> - macOS 桌面客户端
> - 可选 Linux 桌面客户端
>
> 桌面端采用 Tauri 2 封装同一套 React 前端代码，不重新开发一套界面。
>
> 这是正式企业项目，不是 UI Demo。必须连接真实后端、真实 PostgreSQL 数据、真实鉴权、真实 WebSocket/SSE 状态和真实错误处理。禁止把 Mock Data、localStorage 或临时内存数据库作为生产数据源。

---

# 一、项目定位

项目名称：

```text
Agent Browser Cloud Console
```

产品定位：

> Agent Browser Cloud 的统一 Web 控制台，用于管理浏览器 Session、Runtime、Profile、Proxy、Agent、远程桌面、扩展、自动化任务、安全事件、资源与审计。

该控制台不是普通浏览器启动器，也不是本地指纹浏览器的简单界面。

它需要服务于完整的云端 Browser Infrastructure：

- Session Coordinator
- Browser Node
- Chromium Runtime
- Profile Storage
- Proxy / Egress Gateway
- Browser State Engine
- Agent Orchestrator
- HumanTakeover
- Runtime Validation
- Extension Runtime
- Audit / Security
- Cost / Capacity
- Kubernetes Operator

---

# 二、核心开发原则

## 2.1 Web First

当前正式交付物是 Web 管理端。

Web 端需要支持：

- 企业内网部署
- 公有云部署
- 私有化部署
- 多租户
- 多 Workspace
- RBAC
- HTTPS
- WebSocket / SSE
- 远程桌面
- 长时间运行
- 大量 Session 管理

不要把当前项目描述为桌面应用。

## 2.2 Shared UI First

后续 Windows/macOS 客户端必须复用：

- 页面
- 业务组件
- Design Token
- 表格
- 表单
- API Client
- Domain Type
- 权限判断
- 国际化
- 状态模型
- 路由元数据
- 数据校验
- 错误处理

只允许平台相关能力通过 Adapter 区分。

## 2.3 Real Data First

禁止生产代码依赖：

- Mock Service Worker 作为真实接口
- localStorage 作为业务数据库
- IndexedDB 作为 Session 权威状态
- JSON 文件作为生产数据
- 内存 Map 作为服务端状态
- 浏览器前端自行伪造 Session 状态

允许 Mock 的场景仅限：

- Storybook
- 单元测试
- Visual Regression
- 独立开发 Fixture
- 后端暂未完成时的短期开发分支

生产构建中必须关闭 Mock。

## 2.4 Performance First

目标：

- 首屏资源小
- 按页面拆包
- 长列表虚拟化
- 减少全局状态
- 避免频繁重渲染
- 避免 Electron 的高内存开销
- 桌面端使用 Tauri 2
- 图表和远程桌面按需加载
- 大型日志和 Timeline 使用虚拟列表
- WebSocket 更新进行合并和限流

## 2.5 Enterprise Maintainability

必须做到：

- 明确模块边界
- 完整 TypeScript 类型
- OpenAPI 生成 API Client
- 统一错误模型
- 统一权限模型
- 统一审计
- 统一 Design System
- 统一测试
- 统一 CI/CD
- 支持 N/N-1 API 兼容
- 禁止页面直接拼接后端 URL
- 禁止组件直接调用 fetch

---

# 三、确定技术栈

## 3.1 前端核心

```text
React 19
TypeScript 5.x
Vite 7
pnpm Workspace
TanStack Router
TanStack Query
TanStack Table
TanStack Virtual
Zustand
React Hook Form
Zod
Radix UI
Tailwind CSS 4
Lucide Icons
Recharts
Framer Motion
i18next
Storybook
Vitest
Playwright
```

说明：

- React 生态成熟，适合企业长期维护。
- Vite 更适合 Web 与 Tauri 共用。
- 不选择 Next.js 作为核心，因为当前是管理控制台，不依赖 SEO，也需要与 Tauri 共享 SPA。
- 不使用 Electron，避免高内存和双 Chromium 开销。
- Tauri 2 只作为未来桌面容器，不侵入 Web 业务代码。

## 3.2 桌面端

```text
Tauri 2
Rust
Windows WebView2
macOS WKWebView
```

桌面端职责：

- 系统托盘
- 原生通知
- 文件选择
- 本地 Runtime 检测
- 本地 Browser Node 管理
- 本地 Secret 安全存储
- 自动更新
- 深度链接
- 启动本地辅助服务

桌面端不能复制 Web 页面。

## 3.3 后端契约

控制面以现有架构为准：

```text
Java 21
Spring Boot
PostgreSQL
Redis
WebSocket / SSE
OpenAPI 3.1
OAuth2 / OIDC
```

Browser Node：

```text
Rust
Tokio
gRPC / Protobuf
```

前端只通过正式 API 接入。

## 3.4 数据库

生产权威数据库：

```text
PostgreSQL
```

Redis 仅用于：

- 缓存
- 路由
- 短期状态
- 限流
- Pub/Sub
- 分布式协调辅助

Redis 不是权威数据库。

---

# 四、Monorepo 结构

```text
agent-browser-cloud/
├── apps/
│   ├── web-console/
│   ├── desktop/
│   ├── control-plane/
│   ├── browser-node/
│   └── cli/
│
├── packages/
│   ├── ui/
│   ├── design-tokens/
│   ├── domain/
│   ├── api-client/
│   ├── auth/
│   ├── permissions/
│   ├── platform/
│   ├── routing/
│   ├── forms/
│   ├── data-grid/
│   ├── realtime/
│   ├── observability/
│   ├── i18n/
│   ├── validation/
│   ├── testing/
│   └── eslint-config/
│
├── contracts/
│   ├── openapi/
│   ├── protobuf/
│   └── json-schema/
│
├── deploy/
│   ├── docker/
│   ├── kubernetes/
│   └── helm/
│
├── docs/
│   ├── ui/
│   ├── api/
│   ├── adr/
│   └── runbooks/
│
└── tests/
    ├── e2e/
    ├── integration/
    └── visual/
```

---

# 五、Web 与 Desktop 复用结构

## 5.1 Web App

```text
apps/web-console
```

负责：

- 浏览器路由
- OIDC 登录
- Web API
- WebSocket
- Web 远程桌面
- Web 下载
- 企业 Console

## 5.2 Desktop App

```text
apps/desktop
```

仅包含：

- Tauri 配置
- Rust Command
- Desktop Bootstrap
- 本地系统能力
- WebView 容器
- 自动更新
- 系统托盘

React 页面从共享包加载。

## 5.3 Platform Adapter

定义统一接口：

```ts
export interface PlatformAdapter {
  platform: "web" | "windows" | "macos" | "linux";

  openExternal(url: string): Promise<void>;

  selectFile(options: FileSelectOptions): Promise<SelectedFile[]>;

  saveFile(options: SaveFileOptions): Promise<string | null>;

  showNotification(notification: AppNotification): Promise<void>;

  getSecureValue(key: string): Promise<string | null>;

  setSecureValue(key: string, value: string): Promise<void>;

  getAppVersion(): Promise<string>;

  checkLocalRuntime?(): Promise<LocalRuntimeStatus>;
}
```

Web 实现：

```text
WebPlatformAdapter
```

Desktop 实现：

```text
TauriPlatformAdapter
```

业务组件禁止直接调用：

- `window.__TAURI__`
- Node.js API
- 浏览器特定 API
- Rust Command

必须经过 Platform Adapter。

---

# 六、前端应用架构

推荐 Feature-based Architecture：

```text
apps/web-console/src/
├── app/
│   ├── bootstrap/
│   ├── providers/
│   ├── router/
│   ├── layouts/
│   └── error-boundary/
│
├── features/
│   ├── overview/
│   ├── sessions/
│   ├── environments/
│   ├── groups/
│   ├── proxies/
│   ├── runtimes/
│   ├── profiles/
│   ├── extensions/
│   ├── automation/
│   ├── agents/
│   ├── remote-desktop/
│   ├── logs/
│   ├── security/
│   ├── costs/
│   ├── nodes/
│   ├── audit/
│   └── settings/
│
├── shared/
│   ├── components/
│   ├── hooks/
│   ├── lib/
│   ├── constants/
│   └── types/
│
└── main.tsx
```

每个 Feature：

```text
sessions/
├── api/
├── components/
├── hooks/
├── pages/
├── schemas/
├── stores/
├── types/
└── utils/
```

禁止不同 Feature 直接导入对方内部文件。

通过：

- Domain Package
- Public Index
- Shared Contract

交互。

---

# 七、状态管理原则

## 7.1 Server State

使用 TanStack Query。

包括：

- Session 列表
- Session 详情
- Runtime
- Profile
- Proxy
- Agent Task
- Timeline
- Audit
- Cost
- Extension

不把服务端数据复制进 Zustand。

## 7.2 Client UI State

Zustand 仅保存：

- Sidebar 折叠
- 当前 Workspace
- 表格列设置
- Drawer 状态
- 临时筛选
- Theme
- Desktop 窗口偏好
- 未提交表单草稿

## 7.3 Form State

使用：

```text
React Hook Form + Zod
```

Schema 来自共享 Validation Package。

## 7.4 Real-time State

统一由：

```text
packages/realtime
```

管理：

- WebSocket 生命周期
- SSE
- 重连
- Epoch
- Sequence
- 去重
- Backpressure
- Event Batch
- Query Cache 更新

页面不得自行创建 WebSocket。

---

# 八、真实 API 接入要求

## 8.1 API Client

OpenAPI 自动生成：

```text
packages/api-client
```

页面不直接调用 fetch。

正确：

```ts
const session = useSessionQuery(sessionId);
```

错误：

```ts
fetch("/api/sessions/" + sessionId);
```

## 8.2 API Base URL

通过环境配置：

```text
VITE_API_BASE_URL
VITE_WS_BASE_URL
VITE_AUTH_ISSUER
VITE_AUTH_CLIENT_ID
```

禁止写死：

```text
localhost
127.0.0.1
某个生产域名
```

## 8.3 错误模型

统一：

```ts
export interface ApiError {
  code: string;
  message: string;
  requestId: string;
  retryable: boolean;
  details?: Record<string, unknown>;
  currentContextEpoch?: number;
  currentStateVersion?: number;
}
```

UI 显示：

- 用户可理解信息
- Request ID
- 重试按钮
- 查看详情
- 权限不足提示
- 状态过期提示

## 8.4 乐观更新

仅用于：

- 标签
- UI 偏好
- 低风险名称修改

禁止用于：

- 启动 Session
- 终止 Session
- Proxy 切换
- Profile Restore
- HumanTakeover
- Runtime Upgrade
- 高风险策略

这些操作必须等待后端 Operation 状态。

---

# 九、鉴权与权限

## 9.1 登录

使用：

```text
OAuth2 / OpenID Connect
```

支持：

- 企业 SSO
- OIDC
- 可选 SAML 通过 Identity Provider
- MFA
- Session Timeout
- Token Refresh

前端不保存长期 Refresh Token 到 localStorage。

Web：

- 优先 HttpOnly Secure Cookie
- 或经过安全审查的 OIDC PKCE

Desktop：

- 系统 Keychain / Credential Manager
- Tauri Secure Storage

## 9.2 RBAC

权限粒度：

```text
session.read
session.create
session.start
session.stop
session.takeover
session.terminate

profile.read
profile.restore
profile.delete

proxy.read
proxy.manage

runtime.read
runtime.install
runtime.promote

agent.read
agent.execute
agent.pause

security.read
security.manage

audit.read
billing.read
admin.manage
```

## 9.3 UI 权限

前端隐藏无权限入口，但后端仍必须强制校验。

使用：

```tsx
<Can permission="session.takeover">
  <HumanTakeoverButton />
</Can>
```

禁止只靠前端控制权限。

---

# 十、视觉风格

## 10.1 风格名称

```text
Neo-Industrial Observatory
新工业观测站
```

## 10.2 参考原则

参考 OpenBrowser 的信息结构：

- 左侧导航
- 环境列表
- Runtime 管理
- 扩展中心
- 本地设置
- 自动化入口

但视觉必须不同。

不得复制：

- 像素字体
- 粗黑描边
- 米白色卡片
- 原版表格
- 原版按钮
- 原版卡片比例
- CRT 扫描线
- 原版品牌

## 10.3 设计关键词

- 深色企业控制台
- 冷灰蓝
- 低饱和青绿
- 精细边框
- 轻网格
- 高信息密度
- 状态驱动
- 工业仪表感
- 清晰层级
- 低视觉噪音

---

# 十一、Design Token

## 11.1 Dark Theme

```css
:root {
  --bg-canvas: #0b1017;
  --bg-sidebar: #0d141d;
  --bg-surface-1: #111a24;
  --bg-surface-2: #16212d;
  --bg-surface-3: #1b2936;

  --border-subtle: rgba(148, 163, 184, 0.14);
  --border-default: rgba(148, 163, 184, 0.24);
  --border-strong: rgba(148, 163, 184, 0.36);

  --text-primary: #edf5f7;
  --text-secondary: #9fb0bd;
  --text-muted: #697b89;

  --accent-primary: #55d6be;
  --accent-primary-soft: rgba(85, 214, 190, 0.14);
  --accent-secondary: #7aa7ff;
  --accent-warning: #f0b86e;
  --accent-danger: #f2767d;
  --accent-success: #65d68a;
  --accent-purple: #a98cff;

  --radius-sm: 6px;
  --radius-md: 10px;
  --radius-lg: 14px;
}
```

## 11.2 字体

- 中文：MiSans / HarmonyOS Sans SC / Noto Sans SC
- 英文：Inter
- ID、日志、代码：JetBrains Mono

不得使用像素字体作为主字体。

## 11.3 边框

- 默认 1px
- Card 10px 圆角
- Drawer 14px
- Button 7px
- 不使用粗黑描边

---

# 十二、Web 总体布局

```text
┌──────────────────────────────────────────────────────────┐
│ Global Header                                            │
├──────────────┬───────────────────────────────────────────┤
│ Sidebar      │ Context Header                            │
│              ├───────────────────────────────────────────┤
│              │ Main Workspace                            │
│              │                                           │
└──────────────┴───────────────────────────────────────────┘
```

建议：

- Sidebar：240px
- Collapsed：72px
- Header：56px
- Content Max Width：不限制死宽
- 内容 Padding：24px
- Detail Drawer：400px
- 页面最小宽度：1180px

---

# 十三、导航结构

```text
工作区
- 总览
- 环境管理
- 分组与标签
- Browser Node

基础设施
- 代理与出口
- Runtime 与内核
- Profile 存储
- 扩展与应用

自动化
- Agent 任务
- 流程管理
- 运行记录
- Human Queue

开发者
- API
- MCP
- Webhook

运维与安全
- 远程桌面
- 运行日志
- 安全中心
- 审计
- 成本与容量

系统
- Workspace 设置
- 用户与权限
- 本地/桌面设置
```

---

# 十四、页面：总览

显示真实数据：

- Running Session
- Idle Session
- Failed Session
- Browser Node
- Agent Task
- Proxy 可用率
- Profile 存储
- 今日成本

数据来自：

```text
GET /api/v1/overview
GET /api/v1/metrics/summary
```

包含：

- Session 趋势
- Node 资源
- Agent 运行趋势
- 最近异常
- 最近 HumanTakeover
- Prompt Injection Block
- Snapshot Failure
- Proxy Provider 状态

禁止使用固定 Mock 数字。

加载时使用 Skeleton。

无数据时使用真实 Empty State。

---

# 十五、页面：环境管理

## 15.1 数据源

```text
GET /api/v1/sessions
POST /api/v1/sessions
POST /api/v1/sessions/{id}:start
POST /api/v1/sessions/{id}:terminate
```

## 15.2 表格

列：

- 环境
- 分组
- Runtime
- Profile
- Proxy
- Agent
- Extension
- Resource
- Operation
- 状态
- 最近活动
- 操作

支持：

- 服务端分页
- 服务端排序
- 服务端筛选
- Column Visibility
- Multi Select
- Batch Action
- Saved View
- URL Query 同步

不要一次加载所有 Session。

## 15.3 批量操作

批量操作先向后端创建 Operation。

UI 显示：

- 接受
- 执行中
- 部分成功
- 失败
- 取消

不能直接假定全部成功。

---

# 十六、创建环境向导

步骤：

1. 基本信息
2. Runtime
3. Profile
4. Proxy
5. Persona / Region
6. Extension
7. Agent
8. Resource
9. Review

表单提交：

```text
POST /api/v1/sessions
```

服务器返回：

```text
session_id
operation_id
state
```

UI 跳转到 Session Detail 并等待 Operation Event。

表单草稿可以保存在前端，但不能伪造已创建 Session。

---

# 十七、页面：Session Detail

Tabs：

- Overview
- Remote Desktop
- Agent
- Browser State
- Profile
- Network
- Runtime
- Extension
- Timeline
- Debug

实时状态：

```text
WS /api/v1/sessions/{id}/events
```

或：

```text
SSE /api/v1/sessions/{id}/events
```

事件：

- SessionContextUpdated
- OperationTransitioned
- BrowserHealthChanged
- StateCursorUpdated
- AgentStepChanged
- ProxyBindingChanged
- ProfileCheckpointCreated
- HumanControlChanged
- SecurityEventCreated

UI 必须校验：

- sequence
- context_epoch
- operation_epoch

---

# 十八、远程桌面

Web 端支持：

- WebRTC
- noVNC fallback

桌面端复用相同 RemoteDesktopPanel。

显示：

- Frame Age
- RTT
- FPS
- Codec
- Media Class
- Input Owner
- Pressed Key
- Connection State

操作：

- 申请 HumanTakeover
- 释放控制
- Release All Keys
- 重新请求 Key Frame
- 调整质量
- 全屏

高 Frame Age 时：

- 显示 Stale
- 高风险点击限制
- 请求最新帧

---

# 十九、Runtime 页面

真实接口：

```text
GET /api/v1/runtime-builds
GET /api/v1/runtime-builds/{id}
POST /api/v1/runtime-builds/{id}:validate
POST /api/v1/runtime-builds/{id}:promote
POST /api/v1/runtime-builds/{id}:disable
```

显示：

- Chromium Version
- Build ID
- Security Tier
- Validation
- Capability
- Profile Compatibility
- Provider
- Patch
- Platform
- Architecture
- Release Channel
- Installed Node

---

# 二十、Profile 页面

接口：

```text
GET /api/v1/profiles
GET /api/v1/profiles/{id}
POST /api/v1/profiles/{id}/checkpoints
POST /api/v1/checkpoints/{id}:restore
DELETE /api/v1/profiles/{id}/cache
```

显示：

- Core Size
- Ephemeral Size
- Checkpoint
- Encryption
- Runtime Compatibility
- Restore Result
- Business Recovery Result
- Retention

Profile 恢复完成前不能显示 Ready。

---

# 二十一、Proxy 页面

接口：

```text
GET /api/v1/proxy-providers
GET /api/v1/proxies
POST /api/v1/proxy-allocations
POST /api/v1/sessions/{id}/proxy:rebind
```

显示：

- Provider
- Type
- Region
- Endpoint
- Latency
- Success Rate
- Stability
- Cost
- Circuit State
- Binding Count
- Quality Score

Provider Secret 永不展示明文。

---

# 二十二、Extension Center

正式项目中 Extension 数据来自后端 Registry：

```text
GET /api/v1/extensions
GET /api/v1/extensions/{id}
POST /api/v1/extensions/{id}:install
POST /api/v1/extensions/{id}:disable
```

显示：

- Name
- Version
- Permission
- Risk
- Isolation Profile
- Resource Weight
- Runtime Compatibility
- Installed Scope
- Continuous Profiling

不是静态写死应用卡片。

---

# 二十三、Agent 页面

显示真实：

- Goal
- Plan
- Step
- Strategy
- Tool Call
- Validation
- Replan
- Prompt Security
- Cost
- Token Usage
- Human Request

Prompt Injection Event 必须显示：

- Source
- Trust Level
- Blocked Action
- Rule
- Evidence
- Time

不显示未经脱敏的完整敏感正文。

---

# 二十四、日志与 Timeline

支持：

- 服务端分页
- 时间范围
- Live Tail
- Filter
- Request ID
- Operation ID
- Session ID
- Component
- Severity
- Export

使用 TanStack Virtual。

禁止一次渲染几万条日志。

敏感字段由后端 Redact，前端再次避免展示 Secret。

---

# 二十五、安全中心

模块：

- Threat Event
- Prompt Injection
- Runtime Signature
- Extension Risk
- Cross-tenant Test
- Key Rotation
- Admin Access
- Incident
- GameDay
- Compliance

根据权限控制。

---

# 二十六、用户与权限

页面：

- Users
- Groups
- Roles
- Service Accounts
- API Keys
- SSO
- MFA
- JIT Access

禁止在前端生成真正 API Secret。

创建 Secret 后只显示一次，由后端生成。

---

# 二十七、成本与容量

显示：

- Session Cost
- Browser Node Cost
- Proxy Cost
- Storage Cost
- Media Cost
- Control Plane Cost
- Capacity Certificate
- Admission Status

成本来自 Metering API。

不使用前端估算替代账单数据。

---

# 二十八、数据加载规范

## 28.1 Loading

- Skeleton
- Row Skeleton
- Chart Placeholder
- 不使用全屏 Spinner 阻塞整个应用

## 28.2 Empty

区分：

- 没有数据
- 没有权限
- 筛选无结果
- 服务尚未部署
- 功能被策略关闭

## 28.3 Error

显示：

- 错误描述
- Request ID
- Retry
- Docs
- Contact Admin

## 28.4 Offline

Web 端网络断开：

- 显示离线 Banner
- 禁止危险写操作
- 保留只读缓存
- 自动重连
- 不伪造成功

Desktop 端本地 Node 可显示独立 Local Connectivity。

---

# 二十九、性能规范

## 29.1 Bundle

目标：

- 核心首屏尽量小于 350KB gzip
- Remote Desktop 独立 Chunk
- Chart 独立 Chunk
- Code Editor 独立 Chunk
- Extension Detail 独立 Chunk

## 29.2 List

超过 200 行：

- 虚拟列表
- 服务端分页
- 避免 DOM 全量渲染

## 29.3 Real-time

- 合并 50～200ms 内的状态事件
- Telemetry 低优先级
- 高频 Mouse Move 不进入 React State
- 视频帧不进入 React Store
- Timeline 批量追加

## 29.4 Render

- 表格 Cell memo
- 稳定 Query Key
- 避免在全局 Store 放大对象
- 避免 Context Provider 频繁更新
- 图表不可见时暂停刷新

## 29.5 Desktop

Tauri：

- 不启动第二套前端服务
- Release 使用静态资源
- Rust Command 异步
- 不在 WebView 中执行重计算
- 本地日志流限速
- 远程桌面解码按平台能力选择

---

# 三十、测试规范

## 30.1 Unit

- Permission
- Formatter
- Reducer
- Strategy
- Schema
- Error Mapping

## 30.2 Component

Storybook：

- 状态
- 空数据
- 权限
- Error
- Loading
- Dark/Light
- Compact Density

## 30.3 Integration

- API Client
- Query
- Form
- Auth
- WebSocket
- Epoch
- Sequence

## 30.4 E2E

Playwright：

- Login
- Create Session
- Start
- Open Detail
- HumanTakeover
- Terminate
- Runtime Validation
- Profile Restore
- Permission Denied
- Network Reconnect

## 30.5 Visual Regression

覆盖：

- 1280×800
- 1440×900
- 1920×1080
- Dark
- Light
- Windows Web
- macOS Desktop

---

# 三十一、生产环境配置

```text
VITE_API_BASE_URL
VITE_WS_BASE_URL
VITE_AUTH_ISSUER
VITE_AUTH_CLIENT_ID
VITE_APP_ENV
VITE_SENTRY_DSN
VITE_ENABLE_DESKTOP_FEATURES
```

环境：

- local
- development
- staging
- production

禁止把 Secret 放入 Vite 环境变量。

Vite 环境变量会进入前端 Bundle，只允许公开配置。

---

# 三十二、部署

## 32.1 Web

构建：

```text
pnpm build:web
```

部署：

- Nginx
- CDN
- Kubernetes
- Static Object Hosting + API Gateway

需要：

- HTTPS
- CSP
- HSTS
- SameSite Cookie
- X-Frame-Options / frame-ancestors
- Cache-Control
- Source Map 受控上传

## 32.2 Desktop

构建：

```text
pnpm build:desktop
```

产物：

- Windows MSI / NSIS
- macOS DMG / App
- 代码签名
- 公证
- 自动更新

Desktop 使用同一 Design Token 和 Feature Package。

---

# 三十三、禁止事项

禁止：

- 为 Web 和 Desktop 分别写两套页面
- 生产环境使用 Mock Data
- 生产环境使用 localStorage 保存业务权威数据
- 页面直接调用 fetch
- 页面直接创建 WebSocket
- 在组件中硬编码权限
- 在 UI 中硬编码 Runtime/Proxy 状态
- 把所有状态放进 Zustand
- Electron
- 前端生成 Secret
- 显示 Cookie、Password、OTP
- 使用全局超级管理员 Token
- 忽略后端 Operation 状态
- 使用固定假延迟模拟执行
- 假装写操作已立即成功
- 无分页加载所有日志
- 复制 OpenBrowser 原 UI

---

# 三十四、第一阶段实现范围

第一阶段只完成：

- 登录
- App Shell
- 总览
- 环境列表
- 创建环境
- Session Detail
- Start / Stop / Terminate
- Runtime 列表
- Profile 列表
- Proxy 列表
- HumanTakeover 基础入口
- Timeline
- 权限控制
- 真实 API Client
- WebSocket/SSE 基础
- 错误处理
- Dark Theme

暂缓：

- 完整 Flow Editor
- 完整 Cost Learning
- 完整 Runtime Test Farm UI
- 完整 Compliance
- 多 Region 拓扑
- 高级 Desktop 本地管理
- 完整 Mobile

---

# 三十五、编码生成提示词

请基于本文档创建 Agent Browser Cloud 的正式 Web 管理端。

要求：

1. 使用 React、TypeScript、Vite、TanStack Router、TanStack Query、TanStack Table、TanStack Virtual、React Hook Form、Zod、Radix UI、Tailwind CSS。
2. 使用 pnpm Workspace Monorepo。
3. 当前主要应用为 `apps/web-console`。
4. 未来桌面应用为 `apps/desktop`，使用 Tauri 2。
5. 将 Design System、Domain Type、API Client、Permission、Platform Adapter 抽到共享 Packages。
6. Web 和 Desktop 必须复用相同 Feature 页面。
7. 不创建生产 Mock Data。
8. API 必须通过 OpenAPI 生成 Client。
9. PostgreSQL 是后端权威数据库，前端不直接访问数据库。
10. 使用 OIDC 和 RBAC。
11. 使用 WebSocket/SSE 接收 Session、Operation、Browser Health 和 Agent 状态。
12. 所有写操作显示真实 Operation 生命周期。
13. 使用服务端分页和虚拟列表。
14. 创建现代深色企业控制台 UI。
15. 参考 OpenBrowser 的信息架构，但不得复制其视觉样式。
16. 创建可维护的 Feature-based Architecture。
17. 提供真实环境配置、错误处理、权限控制、Loading、Empty、Offline 和 Retry 状态。
18. 所有代码必须可通过 TypeScript Strict、ESLint、Vitest 和 Playwright。
19. 不要为了演示生成临时数据库、JSON Server、Firebase 或内存后端。
20. 后端未完成的接口只生成 TypeScript Contract 和明确的 `NotImplemented` 页面状态，不在生产代码伪造成功数据。

---

# 三十六、最终验收标准

项目需要达到：

- Web 管理端可独立部署
- 可连接正式 Java Control Plane
- 可通过真实 API 管理 Session
- 可接收实时 Operation Event
- 可进行 RBAC 权限控制
- 页面没有生产 Mock 数据
- Web 与 Tauri Desktop 复用超过 85% 的前端代码
- Desktop 只增加平台 Adapter 和原生能力
- 低内存、按需加载
- 大表格和日志不卡顿
- UI 与 OpenBrowser 风格明显不同
- 支持长期企业维护
- 支持 Windows/macOS 扩展
- 不需要未来重新推翻技术栈
