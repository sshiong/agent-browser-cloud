# Cloudflare 真实页面、Challenge 结构化点击与 Web E2E 闭环

> 日期：2026-09-16
> 状态：仓库实现与本机真实 Chromium 验收完成；不等同于绕过 Cloudflare 托管 CAPTCHA

## 问题

本轮开始时存在三处证据或实现缺口：

1. Docker/OrbStack Engine 无响应导致 CF 专项没有实际启动隔离容器，旧记录不能作为通过证据；
2. `SINGLE_CLICK` 虽被识别为低风险 Challenge，仍强制依赖截图、对象存储和 Vision Worker，实际验收由人工完成；
3. Web Console E2E 以全局同名“添加”定位按钮，Expected Outcome 与结构化动作按钮同时出现时产生歧义。

## 实现

### 真实 CF 页面证据

Real-URL Agent Matrix 增加 `https://www.cloudflare.com/cdn-cgi/trace`，并把
`www.cloudflare.com` 加入精确 Host 出口白名单。该用例由项目 Agent 在隔离 PostgreSQL、Redis、
Forward Proxy 和真实 Chromium 链路中导航并读取，而不是在宿主机用 `curl` 代替 Agent。

此证据仅证明项目 Agent 可通过受控出口访问并读取 Cloudflare 公共页面，不声称绕过 Cloudflare
Turnstile、Managed Challenge 或其他站点安全控制。

### 无人工的简单 Challenge

V122 为 Challenge Job 增加 `STRUCTURAL_CLICK` 执行类型。对于可安全绑定到单一、可见、启用且
非敏感 `button/checkbox` Target 的 `SINGLE_CLICK`，Control Plane 不再创建截图或 Vision Job，
而是直接派发包含 State Revision、Target Revision、Target Ref、精确 Bounds 和 Visual Anchor Hash
的结构化命令。Browser Node 在输入前重新验证全部围栏，然后复用既有 `CLICK_TARGET` 执行器。

旧 Node 会忽略新增字段并因空视觉动作列表而在输入前 fail-closed；图片选择、拼图和多轮 Challenge
继续沿用有界截图、脱敏和 Vision Worker 链路。失败预算耗尽后的 Human Handoff 语义不变。

Real-URL Matrix 新增仓库自有的授权简单 Challenge Fixture。真实 Chromium 中项目 Agent 自动点击
“Verify you are human”，页面标题变为 `Challenge passed`；验收同时断言一次尝试、一次 CLICK、
自动化 Run 完成，且 Challenge Event 没有进入人工 `AUTHORIZED` 状态。

### Web Console E2E

两个按钮分别使用 `添加 Expected Outcome` 和 `添加结构化动作` 的可访问名称，E2E 使用唯一角色
选择器。动作编辑器允许保留创建时 Target Revision，但要求 Element ID 在最新状态中仍可交互；
提交前仍会重新抓取 Browser State 并执行正式 JIT Rebind，不降低运行期状态围栏。

## 验收

- `make test-real-url-agent`：通过，真实 Chrome `153.0.8010.48`；公开 URL 包含 Example、W3C 与
  Cloudflare trace；`verifiedControls` 包含 `AUTOMATIC_SINGLE_CLICK_CHALLENGE`；跨域点击和非白名单
  Plan 均 fail-closed。
- `make test-e2e`：通过，输出 `real_web_console_e2e=true` 与 `viewer_rbac_e2e=true`。
- Web Unit：29 files / 141 tests 通过。
- Control Plane Challenge 定向测试、Browser Node `node-agent` 23 项及 V122 N/N-1 Gate 通过。
- 完整 `make ci`、Desktop test/lint 通过；完整 PostgreSQL/Redis/MinIO/mTLS/Chromium Integration
  成功重跑并验证 122 个迁移。首次 Integration 在无关的 Outcome Worker 模型调用窗口遇到一次
  `AGENT_OUTCOME_EVIDENCE_CHANGED`，成功重跑已越过同一场景并完成全部 Gate，不把首次失败隐去。

## 边界

- Cloudflare 验收目标是公开 trace 页面，不是第三方 CAPTCHA 绕过测试。
- 结构化自动点击只覆盖有精确安全 Target 的简单 `SINGLE_CLICK`；无安全 Target 时保持 fail-closed。
- 图片选择、拼图、多轮挑战仍需对象存储与 Vision Worker；其目标环境模型准入仍属于生产 Gate。
