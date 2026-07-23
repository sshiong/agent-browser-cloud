# Agent Browser Cloud UI 设计与生成提示词
## 参考 OpenBrowser 信息架构，但采用独立视觉语言

> 用途：将本文档整体交给 Claude Code、Codex、Cursor、Windsurf 或其他 Vibe Coding 工具，用于生成 Agent Browser Cloud 的桌面端管理界面。
>
> 目标：参考 OpenBrowser 的“本地浏览器工作区、左侧模块导航、环境列表、扩展中心、内核状态页”等信息组织方式，但不得直接复制原项目的颜色、像素风字体、粗黑描边、按钮样式、卡片比例或布局细节。
>
> 产品定位：这是一个面向 Agent Browser Cloud / Chromium Runtime Platform 的桌面控制台，用于管理浏览器会话、Profile、代理、Runtime、扩展、自动化、远程桌面、状态恢复和运行日志。
>
> UI 风格名称：**Neo-Industrial Observatory / 新工业观测站**

---

# 一、直接执行的总提示词

请创建一个名为 **Agent Browser Cloud Console** 的桌面端管理 UI。

项目采用：

- React
- TypeScript
- Vite
- Tailwind CSS
- Radix UI 或 shadcn/ui
- Lucide Icons
- Zustand
- React Router
- TanStack Table
- TanStack Query
- Recharts
- Framer Motion

当前阶段只实现前端 UI、交互状态和 Mock Data，不连接真实 Browser Node、Profile、Proxy 或 Agent API。

要求：

1. 所有页面均可正常导航。
2. 所有按钮具备 Hover、Pressed、Disabled、Loading 状态。
3. 表格支持搜索、筛选、排序、多选和批量操作。
4. 创建环境使用多步骤 Drawer 或 Dialog。
5. Session 详情页包含运行状态、远程桌面、Profile、代理、Runtime、Agent 和日志信息。
6. 使用真实产品文案，不使用大量 Lorem Ipsum。
7. 默认支持深色主题，同时预留浅色主题 Token。
8. 页面适配 1280×800、1440×900 和 1920×1080。
9. 桌面优先，不需要优先适配手机。
10. 避免生成营销官网，必须是高信息密度的桌面管理控制台。

---

# 二、参考界面的可借鉴部分

可以借鉴以下产品结构：

- 固定左侧导航栏；
- 顶部显示当前模块名称和系统状态；
- 环境列表是主要工作区域；
- 每个环境展示名称、分组、Runtime、网络、扩展数量和运行状态；
- 应用或扩展使用卡片网格；
- Runtime/内核设置页展示安装路径、版本、来源和能力状态；
- 支持批量新增、批量导入、启动、同步和编辑；
- 自动化、API、MCP、日志、设置作为独立模块。

不得复制：

- 原版米白与深绿的具体搭配；
- 像素游戏字体；
- 黑色超粗描边；
- 原版按钮外观；
- 原版导航图标位置；
- 原版表格列宽和分页样式；
- 原版应用卡片比例；
- CRT 扫描线或完全相同的网格背景；
- `OPENBROWSER LOCAL WORKSPACE` 品牌样式。

---

# 三、全新的视觉方向

## 3.1 设计关键词

- 深色桌面控制台
- 工业仪表感
- 云平台控制面
- 低饱和青绿色
- 冷灰蓝
- 半透明分层面板
- 精细边框
- 数据密度高
- 状态清晰
- 动画克制
- 工具感强
- 非传统企业后台
- 非像素游戏风
- 非玻璃拟态堆叠

## 3.2 整体感觉

界面像一个管理大量浏览器 Runtime、Session 和 Agent 的“观测站”。

不是普通 SaaS 后台，也不是游戏 HUD。

需要体现：

- Session 是活跃运行实体；
- Browser Node 是资源节点；
- Proxy 是网络出口；
- Profile 是可持久化状态；
- Agent 是受控执行器；
- HumanTakeover 是人工控制模式；
- Runtime 是可验证、可切换的 Chromium 构建。

---

# 四、设计 Token

## 4.1 深色主题

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

  --shadow-panel: 0 18px 50px rgba(0, 0, 0, 0.28);
  --radius-sm: 6px;
  --radius-md: 10px;
  --radius-lg: 14px;
}
```

## 4.2 浅色主题

浅色主题不要变成纯白后台。

```css
--bg-canvas: #eef2f1;
--bg-sidebar: #e3eae8;
--bg-surface-1: #f8faf9;
--bg-surface-2: #edf3f1;
--text-primary: #172126;
--text-secondary: #50616a;
--accent-primary: #138b79;
```

## 4.3 字体

推荐：

- 中文：`MiSans`、`HarmonyOS Sans SC`、`Noto Sans SC`
- 英文与数字：`Inter`
- 代码与 ID：`JetBrains Mono`

不得使用像素字体作为主字体。

## 4.4 圆角和描边

- 普通 Card：10px
- Dialog / Drawer：14px
- Button：7px
- Tag：999px 或 6px
- 边框一般为 1px
- 不使用参考图中的粗黑 3～5px 描边
- 重点组件可用内发光和细 Accent Border

## 4.5 背景纹理

主工作区使用非常淡的技术网格：

```css
background-image:
  linear-gradient(rgba(110, 150, 160, 0.035) 1px, transparent 1px),
  linear-gradient(90deg, rgba(110, 150, 160, 0.035) 1px, transparent 1px);
background-size: 32px 32px;
```

只作为氛围，不得影响文字阅读。

---

# 五、总体布局

## 5.1 桌面窗口

```text
┌──────────────────────────────────────────────────────────────┐
│ Window Title Bar / Workspace Header                          │
├──────────────┬───────────────────────────────────────────────┤
│ Sidebar      │ Top Context Bar                               │
│              ├───────────────────────────────────────────────┤
│              │ Main Workspace                                │
│              │                                               │
└──────────────┴───────────────────────────────────────────────┘
```

建议尺寸：

- Sidebar：232px
- Collapsed Sidebar：72px
- Top Context Bar：64px
- 内容区域 Padding：24px
- 右侧 Context Drawer：360～420px

## 5.2 标题栏

提供：

- Workspace 名称
- 当前连接状态
- Browser Node 状态
- 全局搜索
- 快捷创建按钮
- 通知
- 主题切换
- 用户菜单

## 5.3 左侧导航

顶部：

- 自定义 Logo
- 产品名：`Agent Browser`
- 副标题：`Runtime Console`
- Workspace Switcher

主要操作按钮：

```text
+ 新建浏览器环境
```

一级导航：

- 总览
- 环境管理
- 分组与标签
- 代理与出口
- Runtime 与内核
- Profile 存储
- 扩展与应用
- Agent 自动化
- 远程桌面
- API 与 MCP
- 运行日志
- 安全中心
- 本地设置

分组方式：

```text
工作区
自动化
开发者
运维与安全
```

当前激活项使用：

- Accent 左侧竖线
- 轻微 Accent 背景
- 图标发光
- 文字颜色提升

不要使用整块高饱和矩形高亮。

---

# 六、页面一：总览 Dashboard

页面标题：

```text
总览
浏览器会话、节点资源与 Agent 运行概况
```

顶部 KPI：

- 运行中 Session
- 空闲 Session
- Browser Node
- 今日 Agent 任务
- Profile 存储量
- Proxy 可用率

每个 KPI Card 包含：

- 主数字
- 小型趋势
- 与昨日对比
- 状态色
- 简单 Sparkline

下方模块：

- Session 活跃度折线图
- Node CPU、RAM、Browser Slot、Media Encoder
- Coordinator Mailbox Delay
- 最近异常时间线
- 快捷操作区

最近异常示例：

- Browser Crash
- Proxy Failure
- Profile Restore Warning
- State Resync
- Prompt Injection Block
- HumanTakeover

---

# 七、页面二：环境管理

这是最重要的页面。

## 7.1 顶部区

标题：

```text
环境管理
管理隔离浏览器环境、Runtime、Profile 与网络出口
```

筛选：

- 全部
- 运行中
- 已停止
- 异常
- HumanTakeover
- 未分组

主操作：

- 新建环境
- 批量新增
- 批量导入
- 启动选中项
- 停止选中项

搜索占位文案：

```text
搜索环境、分组、Profile、代理或标签
```

## 7.2 表格列

- 多选框
- 环境
- 分组
- Runtime
- Profile
- 网络出口
- Agent
- 扩展
- 资源
- 状态
- 最近活动
- 操作

环境单元格：

```text
[渐变色图标] 环境名称
               环境短 ID / 标签
```

Runtime 单元格：

```text
Chromium Stable 126
Independent Runtime
```

网络出口：

```text
Singapore · Residential
103.xxx.xxx.xxx
```

Agent：

```text
Idle
Running Step 3/7
Waiting Human
```

状态：

- Running
- Starting
- Stopped
- Recovering
- Degraded
- Failed

操作：

- 启动 / 打开
- 接管
- 更多菜单

## 7.3 表格风格

- 深色行
- 行间 1px 分隔
- Hover 时背景轻微抬升
- Sticky Header
- 运行 Session 左侧显示 Accent 状态线
- 异常 Session 显示细红色边框和 Warning Icon
- 支持 Compact / Comfortable Density

## 7.4 右侧详情抽屉

点击一行后打开 Context Drawer：

- 环境信息
- Runtime
- Profile
- Proxy
- Agent 状态
- 最近操作
- 快捷启动/停止/接管
- 打开完整详情

---

# 八、创建环境向导

使用宽 Drawer，不跳转到新页面。

步骤：

```text
1 基本信息
2 Runtime
3 Profile
4 网络
5 区域与环境
6 扩展
7 Agent 与资源
8 确认
```

## 8.1 基本信息

- 环境名称
- 分组
- 标签
- 描述
- 颜色或图标

## 8.2 Runtime

选项：

- Platform Stable Runtime
- Certified Runtime
- Custom Runtime
- Experimental Runtime

每张 Runtime Card 显示：

- Chromium 版本
- Build ID
- 平台
- Security Tier
- 验证状态
- Profile 兼容状态

## 8.3 Profile

- 新建空 Profile
- 从模板创建
- 恢复 Checkpoint
- 导入本地 Profile

明确显示：

- Core Data
- Cache 是否保留
- 加密状态
- 最近 Checkpoint

## 8.4 网络

- Direct 仅开发模式
- HTTP
- HTTPS
- SOCKS5
- Residential
- ISP
- Datacenter

显示：

- Provider
- 地区
- Sticky
- 延迟
- 费用估算
- 当前健康状态

## 8.5 区域与环境一致性

允许选择：

- OS Persona
- Locale
- Timezone
- Display
- GPU Backend
- Font Set
- Media Capability

文案：

```text
平台会优先保持 Runtime 声明与实际环境一致，不提供绝对不可识别保证。
```

## 8.6 扩展

支持：

- 推荐扩展
- 本地扩展
- Extension Set
- 权限风险提示
- Resource Weight

## 8.7 Agent 与资源

- Agent Enabled
- Resource Class
- Media Class
- HumanTakeover Enabled
- Snapshot Policy
- Idle Hibernate

---

# 九、页面三：Session 详情

## 9.1 顶部 Header

显示：

- 环境名称
- Session ID
- Running 状态
- Runtime Build
- Proxy 地区
- Browser Generation
- Context Epoch
- 当前 Operation

右侧操作：

- 打开远程桌面
- HumanTakeover
- 暂停 Agent
- 创建 Snapshot
- 终止

## 9.2 Tab

- 概览
- 远程桌面
- Agent
- Browser State
- Profile
- 网络
- Runtime
- 扩展
- Timeline
- 调试

## 9.3 概览

Cards：

- Browser Health
- Current Operation
- State Quality
- Proxy Binding
- Profile Write State
- Resource Usage

Timeline：

- Started
- Runtime Ready
- Proxy Bound
- Page Navigated
- Agent Action
- State Resync
- HumanTakeover
- Snapshot

## 9.4 远程桌面

```text
┌──────────────────────────────┬──────────────┐
│ Remote Desktop Canvas        │ Control Pane │
│                              │              │
└──────────────────────────────┴──────────────┘
```

右侧显示：

- Frame Age
- RTT
- FPS
- Codec
- Input State
- Pressed Keys
- Control Owner
- Release All Keys

画面状态：

- Live
- Delayed
- Stale
- Reconnecting

Stale 时显示半透明提醒：

```text
画面已延迟，高风险操作暂时受限
```

## 9.5 Agent Tab

显示：

- User Goal
- Current Plan
- Current Step
- Execution Strategy
- Trust Boundary
- Tool Calls
- Action Validation
- Replan Count
- Token / Cost

Plan Step：

```text
✓ Open CRM
✓ Search Customer
→ Open Order
○ Export Report
```

## 9.6 Browser State

显示：

- State Version
- State Quality
- Target Revision
- Current URL
- Target Graph
- Diff Rate
- Last Resync
- State Data Classification

可视化 Target Tree，但不要默认展示所有 DOM 文本。

## 9.7 Timeline

事件筛选：

- Control
- Agent
- Runtime
- State
- Network
- Profile
- Security
- Human

---

# 十、其他核心页面

## 10.1 分组与标签

左侧分组树，右侧详情：

- Session 数
- 默认 Runtime
- 默认 Proxy Policy
- 默认 Resource Class
- 默认 Extension Set
- 批量移动

## 10.2 代理与出口

表格列：

- 名称
- Provider
- 类型
- 地区
- Endpoint
- 延迟
- 稳定性
- 成本
- 当前绑定数
- 健康状态

Provider 详情显示：

- Adapter
- Capabilities
- Circuit Breaker
- 成功率
- 失败率
- 单位成本

增加 Selection Explainability：

```text
地理匹配       +24
稳定性         +20
历史成功率     +18
价格           -10
当前负载        -4
```

## 10.3 Runtime 与内核

顶部状态：

- Installed
- Active Build
- Chromium Version
- Security Tier
- Validation
- Update Available

Build 列表：

- Build ID
- Chromium Version
- Platform
- Architecture
- Provider Set
- Validation Status
- Profile Schema
- Installed Size
- Release Channel

操作：

- 安装
- 验证
- 设为默认
- Canary
- 回滚
- 删除

详情：

- Binary
- Manifest
- Capability Snapshot
- Compatibility Matrix
- Provider
- Patch Registry
- Performance
- Validation Results

## 10.4 Profile 存储

展示：

- Profile
- Core Size
- Cache Size
- Last Checkpoint
- Encryption Key Version
- Restore Status
- Runtime Compatibility

操作：

- 创建 Checkpoint
- 恢复
- 导出
- 删除 Cache
- Integrity Check

## 10.5 扩展与应用中心

顶部：

- 推荐
- 已安装
- 本地扩展
- Extension Set
- 高风险权限
- 搜索

Card 显示：

- Icon
- Name
- Description
- Category
- Version
- Security Class
- Resource Weight
- Installed Sessions
- Install Button

使用深色 Card、细边框、Hover 权限摘要，不使用大面积白底。

## 10.6 Agent 自动化

子项：

- 任务
- 流程
- 模板
- 运行记录
- Human Queue

任务列表：

- Task Name
- Goal
- Session
- Agent State
- Current Step
- Risk
- Cost
- Started At
- Result

流程节点：

- Navigate
- Wait
- Read State
- Click
- Type
- Validate
- Human Confirmation
- Snapshot
- Finish

## 10.7 API 与 MCP

展示：

- API Status
- Endpoint
- API Key
- MCP Server
- Connected Clients
- Rate Limit
- Recent Calls

代码示例 Tabs：

- cURL
- TypeScript
- Python
- Java

Secret 默认 Mask。

## 10.8 运行日志

高信息密度日志台：

- 时间
- Severity
- Session
- Component
- Event
- Request ID
- Operation ID

支持：

- Live Tail
- Pause
- Search
- Filter
- Export
- Redaction Indicator

## 10.9 安全中心

模块：

- Threat Events
- Prompt Injection
- Runtime Signature
- Extension Risk
- Cross-tenant Test
- Key Rotation
- Admin Access
- Incident

Prompt Injection Event 显示：

- 来源
- 信任等级
- 命中规则
- 被阻止动作
- Agent Run
- 时间
- 审计证据

## 10.10 本地设置

分区：

- General
- Appearance
- Runtime Path
- Storage
- Proxy
- API
- Update
- Diagnostics

Runtime 设置展示：

- 默认 Runtime
- 自定义路径
- 系统浏览器回退
- 安装状态
- 验证状态
- 路径检测

---

# 十一、组件清单

请创建以下可复用组件：

```text
AppShell
Sidebar
WorkspaceSwitcher
TopContextBar
PageHeader
PrimaryActionButton
StatusChip
MetricCard
ResourceMeter
DataTable
FilterBar
SearchInput
SegmentedFilter
BatchActionBar
EnvironmentAvatar
RuntimeBadge
ProxyBadge
ProfileBadge
AgentStatus
OperationStatus
HealthIndicator
Timeline
EventCard
ContextDrawer
CreateEnvironmentWizard
RemoteDesktopPanel
FrameAgeBadge
InputLedgerPanel
StateQualityBadge
TargetTree
ExtensionCard
RuntimeBuildCard
CostBreakdown
EmptyState
ErrorState
LoadingSkeleton
ConfirmDialog
DangerDialog
```

---

# 十二、状态规范

## Session

- Created
- Starting
- Running
- Idle
- Human Controlled
- Degraded
- Recovering
- Hibernated
- Stopping
- Stopped
- Failed

## Health

- Healthy：绿色
- Warning：琥珀
- Critical：红色
- Unknown：灰色
- Updating：蓝色

## Operation

- AgentInteractive
- HumanTakeover
- HumanAssist
- Snapshot
- Quiesce
- Recovery
- ProxyTransition
- Termination

技术状态可显示在 Detail 中，普通用户界面映射为更易懂的中文。

---

# 十三、动效

- Page Enter：150ms Fade + 4px Translate
- Card Hover：120ms Border / Background
- Drawer：220ms
- Dialog：180ms
- Status Pulse：仅 Starting / Recovering
- Live Indicator：低频呼吸
- 表格行不要大幅缩放
- 不使用夸张弹簧动画

---

# 十四、空状态与错误状态

## 无环境

```text
还没有浏览器环境
创建第一个隔离环境，配置 Runtime、Profile 和网络出口。
[新建环境] [导入配置]
```

## Runtime 未安装

```text
尚未安装可用 Runtime
安装经过验证的 Chromium Runtime 后才能启动环境。
[安装 Runtime]
```

## Proxy 不可用

```text
网络出口不可用
当前 Proxy Provider 无法建立连接，平台不会回退到本机直连。
[重新检测] [切换策略]
```

---

# 十五、可访问性

必须：

- 所有按钮可键盘聚焦
- Focus Ring 清晰
- 状态不能只依赖颜色
- 图标按钮有 Tooltip 和 aria-label
- 表格支持键盘选择
- Dialog 支持 Escape
- 对比度至少达到 WCAG AA
- Reduced Motion 模式
- 字号最低 12px，仅 Metadata 可用 12px

---

# 十六、Mock Data

创建至少：

- 12 个环境
- 4 个分组
- 8 个 Proxy
- 5 个 Runtime Build
- 12 个 Extension
- 10 个 Agent Task
- 20 条 Timeline Event
- 3 个 Browser Node
- 4 个 Profile Checkpoint

环境示例：

```text
CRM Singapore
Support Workspace
Research Session
Finance Review
Extension Test
Runtime Canary
```

状态需要混合：

- Running
- Starting
- Stopped
- Degraded
- HumanTakeover
- Recovering

---

# 十七、页面路由

```text
/
/overview
/environments
/environments/:id
/groups
/proxies
/runtimes
/profiles
/extensions
/automation/tasks
/automation/flows
/automation/runs
/automation/human-queue
/remote-desktop
/developer/api
/developer/mcp
/logs
/security
/settings
```

---

# 十八、文件目录建议

```text
src/
├── app/
│   ├── router.tsx
│   ├── providers.tsx
│   └── shell.tsx
├── components/
│   ├── ui/
│   ├── data/
│   ├── feedback/
│   └── layout/
├── features/
│   ├── overview/
│   ├── environments/
│   ├── sessions/
│   ├── proxies/
│   ├── runtimes/
│   ├── profiles/
│   ├── extensions/
│   ├── automation/
│   ├── remote-desktop/
│   ├── security/
│   └── settings/
├── mocks/
├── stores/
├── styles/
├── types/
└── utils/
```

---

# 十九、实现顺序

第一轮：

1. AppShell
2. Sidebar
3. TopContextBar
4. Design Token
5. Overview
6. Environment Table
7. Environment Detail Drawer
8. Create Wizard

第二轮：

9. Session Detail
10. Runtime
11. Proxy
12. Profile
13. Extension Center
14. Logs

第三轮：

15. Agent Automation
16. Remote Desktop Mock
17. Security Center
18. Settings
19. Light Theme
20. Responsive Polish

---

# 二十、验收标准

最终 UI 必须满足：

- 第一眼能看出是浏览器 Runtime / Session 管理工具；
- 信息密度高，但不凌乱；
- 与参考产品的信息架构有相似性；
- 与参考产品的视觉样式明显不同；
- 不使用像素风字体；
- 不使用粗黑描边；
- 不使用大面积浅米白表格；
- 环境列表、Runtime 页、应用中心具备完整 Mock 状态；
- 所有导航可用；
- 创建环境向导可完成；
- Session Detail 信息完整；
- 深色主题达到产品级完成度；
- 可以直接作为后续真实 API 接入的 UI 骨架。

---

# 二十一、负面提示词

不要生成：

- 原版 OpenBrowser 的像素复刻；
- Windows 95 风格；
- CRT 扫描线主视觉；
- 纯黑背景配荧光绿；
- 粗黑边框包围全部元素；
- 每个按钮都像游戏按钮；
- 大量发光文字；
- 低信息密度 Landing Page；
- 传统蓝白企业后台；
- 大面积玻璃拟态；
- 所有 Card 都悬浮；
- 过度圆角；
- 巨大标题；
- 无意义渐变；
- 假装所有状态都正常；
- 真实 Secret；
- 真实 Cookie；
- 真实用户数据。

---

# 二十二、一句话最终提示

> 请生成一个桌面优先、深色、新工业观测站风格的 Agent Browser Cloud 管理控制台，参考 OpenBrowser 的环境管理、Runtime、扩展中心和本地工作区信息架构，但使用独立的现代视觉语言：低饱和青绿色、冷灰蓝、精细 1px 边框、克制网格背景、现代无衬线字体、紧凑数据表格和状态驱动交互；不得复制原版像素字体、粗黑描边、米白卡片和具体布局。界面必须覆盖总览、环境管理、Session 详情、代理、Runtime、Profile、扩展、Agent 自动化、远程桌面、日志、安全中心和设置，并提供完整 Mock Data、深浅主题 Token、响应式桌面布局和可复用组件。
