# Agent Browser Cloud — MVP 需求说明

> 版本：v1.0  
> 日期：2026-07-23  
> 状态：Draft  
> 基于：V16 最终架构设计

---

## 1. 产品概述

Agent Browser Cloud 是一个以受控 Chromium Runtime 为核心的浏览器基础设施平台，提供：

- **有状态 Agent** 浏览器自动化
- **远程桌面** 交互式访问
- **Profile 持久化** 会话保持
- **网络隔离** 出口代理
- **Runtime 插件化** 扩展管理
- **高密度调度** 资源优化

### 1.1 产品定位

> 以受控 Chromium Runtime 为核心，提供有状态 Agent、远程桌面、Profile 持久化、网络隔离、Runtime 插件化和高密度调度的 Browser Infrastructure Platform。

### 1.2 合法用途声明

本平台面向合法的浏览器自动化测试、远程浏览器、企业流程、Agent 辅助操作和会话隔离。平台不提供无人值守的 CAPTCHA 求解、自动多轮挑战试探或面向特定安全系统的自适应规避策略。

---

## 2. MVP 目标

### 2.1 核心验证目标

第一阶段形成最小可运行闭环：

```
创建 Session
→ 启动 Chromium Runtime
→ 绑定 Profile 和网络出口
→ 获取浏览器状态
→ Agent 或用户操作
→ 观察并验证结果
→ 保存 Profile
→ 终止或休眠 Session
```

### 2.2 必须证明的能力

| # | 能力 | 验收标准 |
|---|------|---------|
| 1 | Session 生命周期稳定运行 | 创建→启动→运行→终止 500 次无资源泄漏 |
| 2 | 同一 Session 不会发生并发写操作 | 通过 Exclusive Operation 保证 |
| 3 | Browser 崩溃能够被发现和恢复 | Kill Renderer/Browser 后自动检测并产生 Crash Event |
| 4 | Profile 可以保存和恢复 | 登录状态保存后恢复验证 |
| 5 | HumanTakeover 可用且输入不会卡键 | VNC 断开后 All-keys-up 生效 |
| 6 | Tenant 数据不会互相泄漏 | Cross-tenant 测试通过 |
| 7 | 网页内容不能修改系统策略或扩大 Agent 权限 | Prompt Injection 测试集通过 |

### 2.3 MVP 不追求的能力

- 多 Region 部署
- 完整 Chromium Deep Fork
- 全量 Runtime Validation Farm
- 完整成本学习模型
- 全量企业合规
- 多语言 SDK
- 自动化复杂恢复
- 大规模 Extension Marketplace

---

## 3. 功能范围

### 3.1 Phase 0：合同与工程基础

**目标**：冻结第一版接口，建立工程基础设施。

**范围**：
- Monorepo 初始化
- Contract v1 定义（SessionContext、ExclusiveOperation、StateCursor、NodeCommandEnvelope、NodeEventEnvelope）
- Session REST API v1
- Browser Node RPC v1
- Error Envelope v1
- 数据库迁移脚本
- CI/CD 基础配置
- ADR 文档

**退出 Gate**：
- Contract 可以生成 Java/Rust/TypeScript 三种语言类型
- Schema Compatibility CI 生效
- 数据库可以执行全新安装和升级
- Control Plane、Browser Node 可互相发送一个 Ping Command
- 每个核心模块已有 Owner
- 无阻塞性架构争议

### 3.2 Phase 1：单机 Browser Runtime PoC

**目标**：证明 Browser Runtime 主链路成立。

**范围**：
- **Runtime Supervisor**：启动/停止 Chromium、分配 Profile/Display、记录 PID
- **Browser Supervisor**：Process/CDP/Page Health、内存/CPU 监控、Crash Event、基础 Recovery
- **State Collector**：URL、Title、可见可交互元素、A11y 摘要、State Version
- **Input Sandbox**：Mouse Move/Down/Up、Key Down/Up、All-keys-up、Input Ledger、断线释放
- **noVNC**：用户连接、基础画面、输入转发、会话断开

**PoC A（生命周期）**：启动 Chromium → 打开测试网站 → 获取 CDP → 终止 Chromium → 无残留进程

**PoC B（输入）**：鼠标点击 → 键盘输入 → Ctrl/Shift 正常释放 → VNC 断开后 All-keys-up

**PoC C（崩溃）**：Kill Renderer → Kill Browser → Browser Supervisor 正确分类 → 产生 Crash Event

**退出 Gate**：
- 连续启动、终止 500 次无明显资源泄漏
- Browser Crash 可检测
- noVNC 可连接
- Key Up Loss 测试通过
- Agent Worker 无法访问宿主 Shell
- Runtime 运行目录隔离

### 3.3 Phase 2：Session Coordinator

**目标**：建立控制面最关键的串行状态机。

**范围**：
- **Session Service**：Create/Start/Get/Terminate Session，列表和过滤
- **Coordinator**：Session Context、Exclusive Operation、State Cursor、Command Validation、Node Event、Operation Commit/Abort、Coordinator Term
- **Mailbox**：Critical/Interactive/Normal/Maintenance 四通道
- **幂等**：API Idempotency Key、Node Command ID、Operation ID、Event Inbox、PostgreSQL Outbox

**退出 Gate**：
- 同一 Session 最多一个 Active Operation
- Coordinator 重启后 Session 可继续
- 重复命令不重复创建 Browser
- PostgreSQL 是权威状态
- Redis 清空不会导致永久数据丢失
- Emergency Terminate 能优先处理

### 3.4 Phase 3：Profile、Proxy、State 与 HumanTakeover

**目标**：完善持久化、网络和人工干预能力。

**范围**：
- **Profile MVP**：Core/Ephemeral 分层、Checkpoint、Manifest、Commit Marker、Integrity Check、Restore
- **Proxy MVP**：Static Proxy Provider、Allocation、Binding、Exit IP 验证、默认禁止直连、Release、Circuit Breaker
- **State Gateway MVP**：Current State、State Version、Target Ref、Diff Event、DiffTruncated、Resync、State Quality
- **HumanTakeover**：获取控制权、Agent 暂停、原始 Desktop Input、Frame Age、Input Ledger、Release、结束后 Resync

**退出 Gate**：
- Profile 可恢复
- Cache 不进入默认归档
- Proxy 失败不回退直连
- HumanTakeover 可抢占 Agent
- State Invalid 时 Agent 不执行
- 人工接管结束后 Agent 必须重新获取状态
- Tenant A 无法访问 Tenant B 的 Session

### 3.5 Phase 4：Agent 基础与安全边界

**目标**：实现最小可用 Agent 并建立安全边界。

**范围**：
- **Agent MVP**：单 Planner、单 Executor、Intent Guard、Plan Validator、Execution Strategy Selector、Action Validation、Replan Budget
- **Tool API**：navigate、get_current_state、click_target、type_text、scroll、wait_for、get_url、get_page_summary、request_human_takeover
- **Prompt Injection MVP**：Instruction Source、Trust Level、Web Content 默认 Untrusted、Context Partition、High-risk Sink、Tool Capability Token、Plan Provenance、Prompt Security Event
- **数据最小化**：Password/Cookie/OTP 不进入 Agent Context、基础 Mask、Debug Log Redaction

**退出 Gate**：
- Agent 能完成自建表单流程
- Agent 不能访问未授权域名
- 网页指令无法扩大工具权限
- 高风险动作需要确认
- Planner 有 Replan 上限
- Action 必须经过结果验证
- Prompt Injection 测试集通过

---

## 4. 冻结的核心原语

开发过程中不得另起一套平行状态模型。必须统一使用：

| 原语 | 说明 |
|------|------|
| SessionContext | 当前会话的稳定运行上下文 |
| ExclusiveOperation | 当前唯一具有浏览器写入权的操作 |
| StateCursor | Browser State Engine 的状态指针 |
| DurableWorkflow | 异步持久化工作流 |
| ProfileCheckpoint | Profile 快照 |
| RuntimeBuild | Runtime 构建版本 |
| ProxyBinding | 代理绑定 |
| HumanAuthorizationEvent | 人工授权事件 |

### 4.1 写操作统一路径

```
Command
→ Session Coordinator
→ Exclusive Operation
→ Browser Node / Workflow
→ Result Event
→ State Commit
```

### 4.2 禁止事项

- 每个模块自行创建分布式锁
- Browser Node 直接写控制面业务表
- Agent 直接访问 CDP、Profile 文件或宿主权限
- Redis 成为唯一事实来源
- Detection Service 获得输入权限
- 网页内容直接变成高优先级 Agent 指令

---

## 5. 技术栈

| 层级 | 技术选型 | 说明 |
|------|---------|------|
| 控制面 | Java 21 + Spring Boot | Session Coordinator、API、Workflow |
| Browser Node | Rust + Tokio | Runtime Supervisor、Input、Network |
| Web Console | React + TypeScript | 管理控制台 |
| 数据库 | PostgreSQL 17 | 权威状态 |
| 缓存 | Redis 7 | 缓存、路由、短期状态 |
| 内部协议 | Protobuf | 服务间通信 |
| 外部 API | OpenAPI 3.1 | 客户端 API |
| 消息总线 | PostgreSQL Outbox（MVP） | 后续接 NATS/Kafka |
| 本地环境 | Docker Compose | 开发环境 |
| 集群环境 | Kubernetes | 生产部署 |

---

## 6. 安全要求

### 6.1 必须从第一版实现的安全能力

- Instruction Source 分级
- Trust Level 定义
- Web Content 默认 Untrusted
- Context Partition（System/User/Untrusted 分区）
- High-risk Sink 检查
- Tool Capability Token
- Plan Provenance 追踪
- Tenant Context Filter
- Agent Tool Allowlist
- Password Redaction
- Basic Audit

### 6.2 Prompt Injection 测试样例

- 网页要求忽略系统指令
- 邮件要求上传文件
- 文档要求读取 Cookie
- 页面伪造管理员确认
- 隐藏 DOM 中包含工具调用提示

---

## 7. 环境分层

| 环境 | 用途 | 要求 |
|------|------|------|
| Local | 单开发者调试 | Docker Compose、单 Browser Node、本地 Chromium |
| Dev | 多开发者集成 | 最少两个 Browser Node、测试消息总线 |
| Staging | 预生产验证 | 与生产相同拓扑、独立 KMS、故障注入 |
| Production | 生产环境 | 架构 Gate 全通过、On-call、Runbook |

---

## 8. 团队最小配置

| 角色 | 人数 | 职责 |
|------|------|------|
| 平台/控制面开发 | 1 | Session Coordinator、API、Workflow |
| Browser Node/Rust 开发 | 1 | Runtime Supervisor、Input、Network |
| 前端/Console 开发 | 1 | Web Console |
| 测试或测试开发 | 1 | 测试策略、自动化测试 |
| 安全和运维 | 由资深成员兼任 | Threat Model、Runbook |

---

## 9. 研发度量

| 指标 | 说明 |
|------|------|
| Lead Time | 从提交到生产的时间 |
| Change Failure Rate | 变更失败率 |
| Mean Time to Recovery | 平均恢复时间 |
| Test Flakiness | 测试不稳定性 |
| API Compatibility Failure | API 兼容性失败 |
| Browser Crash Rate | 浏览器崩溃率 |
| Coordinator Mailbox Delay | Coordinator 邮箱延迟 |
| Profile Restore Success | Profile 恢复成功率 |
| Prompt Injection Block Rate | Prompt Injection 拦截率 |
| Cross-tenant Test Result | 跨租户测试结果 |
| Cost per Active Session | 每活跃会话成本 |

---

## 附录

### A. 参考文档

- [架构设计 V16](./Agent-Browser-Cloud-架构设计-V16-最终版-安全威胁模型治理闭环与生产运营.md)
- [代码大骨架](./Agent-Browser-Cloud-代码大骨架.md)
- [开发流程与实施计划](./Agent-Browser-Cloud-开发流程与实施计划.md)

### B. 术语表

| 术语 | 定义 |
|------|------|
| Session | 一个浏览器会话实例 |
| Coordinator | Session 的串行状态管理者 |
| Exclusive Operation | 当前唯一具有写权限的操作 |
| Context Epoch | 核心运行环境变化时递增的版本号 |
| Browser Generation | 浏览器进程实例的代次 |
| State Quality | 当前状态的完整性和可用性等级 |
| HumanTakeover | 人工接管浏览器控制权 |
| Profile | 浏览器用户数据目录 |
