# Agent Browser 权威多标签页与跨页执行闭环

> 日期：2026-08-20
> 状态：仓库内实现与本地完整 Gate 已通过，GitHub Gate 待提交后验证

## 目标与边界

本切片把原先由单页 URL 临时推导的 `activeTab` 替换为 Browser Node 从 Chromium Page
Target 采集的权威多标签页状态，并在既有粗粒度 `execute-actions` 中增加：

- `OPEN_TAB`
- `SWITCH_TAB`
- `CLOSE_TAB`

这些操作属于 AUTONOMOUS 下可静默执行的普通浏览器动作。它们不会逐次请求人工授权；只有
OTP、设备确认、支付/账号安全决定等确实需要真人提供信息，或低风险 Challenge 的自动预算
耗尽时，才沿用既有“一次通知、原 Task 可续行”的协作规则。操作员可把 OTP 发给 Agent
代填，也可自愿通过 VNC 填写，系统不强迫接管。

本切片没有把 DOM `role=dialog` 冒充原生 JavaScript Dialog，也没有关闭 File、局部
Screenshot、受治理 Evaluate 和其他剩余高级 Primitive。

## 实现

### Browser Node 与权威状态

- State Collector 从回环 CDP `/json/list` 枚举最多 100 个真实 Page Target，投影稳定
  `tabId/url/title/active`；多个 Target 时用各 Page 的 `document.visibilityState` 判定唯一活动页，
  证据歧义时 fail-closed，不再选择列表第一项伪造活动页；
- `CurrentState/StateDiff/contentHash` 均包含 `tabs/activeTabId`，活动 Target 变化会轮换
  Target Revision，使旧页的短期 Target 引用失效；
- OPEN 使用 `Target.createTarget`，SWITCH 使用 `Target.activateTarget` 并等待真实可见，CLOSE
  使用 `Target.closeTarget` 并等待 Target 消失；禁止关闭最后一个 Page；
- OPEN 同时服从现有 `TabResourcePolicy`：新增标签阻断或 Tab Budget 耗尽时由 Node 拒绝，
  不允许 Agent 绕过 AUTO 资源治理；
- Input Sandbox 的 CDP Broker 可在活动页变化时安全 rebind：先在旧 Page 释放按键/按钮，
  保留单调 Input Ledger，再切换到新 Page 的回环 WebSocket。Agent Action、Challenge 与通用
  Input 入口均绑定当前权威活动 Target。

### Control Plane、权限与恢复

- Protobuf 以 additive tag 增加 `BrowserTabState`、`tabs/active_tab_id` 和 Action 的
  `tab_id/tab_url`；N−1 Diff 缺少新字段时保留上一个权威标签页投影；
- Node Event Mapper 对数量、ID 唯一性、唯一 active、activeTabId 一致性和有界元数据执行
  fail-closed 校验；PostgreSQL Browser State 继续作为控制面权威投影；
- Planner、Action Tool、Node 和完成验证四层校验 URL、允许域、现存 Tab、最后 Tab 保护、
  Capability Data Scope、State/Target Revision 与动作结果；OPEN/SWITCH 为 R1，CLOSE 为 R2；
- Capability 仍绑定 Tenant/Session/Task/Intent/Operation/Tool/初始授权域。跨允许域打开标签页
  后的 GET_URL/Page Summary 收尾要求令牌原域和当前域同时属于 Task 持久化允许域集合，既
  支持合法跨页完成，也不能借标签页扩大域权限；
- Snapshot 公开真实 `tabs/activeTab`；旧 N−1 State 没有新字段时明确返回空列表/null，不再
  用当前 URL 构造虚假 Tab。

### 契约、SDK 与集成 Fixture

- OpenAPI、Web/Tauri 共用类型及 TypeScript/Python/Go/Java SDK 均已同步；公开生成基线保持
  `226 Operations / 301 Schemas`；
- Fake Chromium 维护多个 Page Target、活动 Target、可见状态及 create/activate/close CDP
  语义，使 Integration 验证真实状态转换，而不是固定返回成功；
- Integration 使用独立 AUTO/INTERACTIVE Session，完整执行打开允许域新页、切回原页、
  再切到新页、关闭新页和终止 Session；同时精确核对新增 Profile 与持久 Workflow/Operation
  数量。

## 验证

- `make test` 通过：Control Plane 462 项、Rust Workspace、Web 115 项、四类 Python Worker
  和 Go Provider 全部通过；
- State Collector、Input Sandbox、Node Agent 定向 Rust 测试通过；真实 Runtime ignored
  Gate 在 Fake Chromium 上完成 open/switch/close、资源策略及状态采集；
- Control Plane 新增跨允许域成功与未授权域拒绝回归；
- `make lint`、`make build`、Desktop Test/Lint/无签名 Build、OpenAPI、四 SDK 生成与
  字节级漂移检查、N−1 Gate 均通过；
- 完整 PostgreSQL、Redis、MinIO、mTLS、Chromium CDP Integration 通过；显式验证真实
  open/switch/close、Profile 精确集合，以及 `durable_workflows=19` 的持久工作流闭环；
- GitHub `ci/desktop` 必须在提交推送后另行检查，未通过前不写成已通过。

## 剩余边界

下一切片先建立持续、可恢复的原生 `Page.javascriptDialogOpening/Closed` 权威状态，再实现
Accept/Dismiss/Prompt 动作；DOM Dialog 仍只是页面可访问目标。之后继续 File、局部
Screenshot、受治理 Evaluate、Select/Press/Drag/Drop/Swipe 和通用 Mouse/Keyboard/Touch。
