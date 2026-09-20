# 真实 Chrome 初始状态与契约告警收口

> 日期：2026-09-20
> 实现提交：`f799e51`、`ccec50c`
> 结论：关闭独立审计发现的真实 Chrome 初始状态 P1 阻断，并收口五项 Redocly 契约告警；本项不改变“尚未通过 V16 全量生产发布 Gate”的总判断。

## 问题与根因

真实 Chrome 新 Session 会在新标签页内部 iframe 中暴露
`chrome-untrusted://new-tab-page`。Browser Node 原先把该值作为 Opaque Frame origin 上报，
Control Plane 则正确只接受 `http`/`https` origin，导致整个 `StateUpdated` 被
`INVALID_EVENT` 拒绝，Agent 在首个任务前无法取得可执行 State。

修复初始事件后，真实回归还暴露了两层稳定性问题：Browser Safety Monitor 把所有标签页和
Chrome Autofill/Optimization Guide 等浏览器后台服务流量合并为当前页网络活动；此外
`Page.loadEventFired` 后残留的 Document 请求可能没有匹配的 Network 终止事件。两者都会让
静态活动页长期保持 `networkQuietMillis=0`。网络 quiet 的合法单调推进又会更新 State Version，
使低风险 Challenge 结构化点击在 Target Revision、Bounds 和 Visual Anchor 都未变化时被旧的
截图式精确 State 围栏误拒绝。

OpenAPI 同时存在五项 lint 告警：两个 GET 缺少 4xx、一个设计为始终 409 的兼容 PUT 没有
2xx，以及两个只通过 `x-sse-event-schemas` 引用的 SSE payload 被 Redocly 1.34 误判未使用。

## 实现

- Opaque Frame 只投影 `http`/`https` origin；`chrome-untrusted`、`chrome-error`、`data`、
  `blob` 等内部或非 Web scheme 保留 frame identity、bounds 和 boundary reason，但 origin
  归一为 absent，且仍不可执行。Control Plane 继续拒绝 Node 显式提交的非 Web origin。
- Browser Safety Monitor 保留全浏览器事务安全点，同时按 Chromium Page Target 独立维护
  网络请求与 quiet window。页面动作只读取当前活动标签页证据，后台标签页不再错误阻断动作；
  浏览器服务发起的非 Document 流量不进入页面执行门。
- 每个 Page 会话同时启用 `Network` 与 `Page` CDP Domain；`Page.loadEventFired` 只收敛同一
  Page 会话残留的 Document 请求，其他 Fetch/XHR/上传/下载仍保持 fail-closed。页面稳定超时
  只输出版本、quiet 时长、资源/发起者类型等有界诊断，不记录 URL、Header 或正文。
- 结构化 Challenge 单击允许 State Version 单调前进，但仍要求同版本时 Hash 精确一致，并在
  输入前重新验证 Target Revision、Target、Bounds、角色、可见/启用状态和 Visual Anchor；
  截图/Vision 动作继续要求 State Version 与 Hash 完全相等。
- `Page.navigate` CDP 响应等待上限由 5 秒调整为 15 秒，吸收真实公网 TLS/代理建立的正常抖动，
  不改变目标域白名单或最终 URL 验证。
- OpenAPI 为两个读取接口补充正式 404，重新生成 TypeScript/Python/Go/Java SDK Manifest。
  `.redocly.lint-ignore.yaml` 仅保留三个带原因、精确 JSON Pointer 的例外：始终 409 的锁定接口，
  以及 Redocly 1.34 无法识别 extension refs 的两个 SSE schema；没有伪造 2xx 响应或删除 schema。
- Fake Chromium 在动态创建/关闭标签页时发布对应 attach/detach CDP 事件，使 Integration 与真实
  Chrome 使用相同 Page Target 网络映射语义。

## 验证

- `JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home REAL_CHROMIUM_PATH="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" make test-real-url-agent`
  通过，输出 `Real-URL Agent matrix passed with real Chrome and an exact-host egress allowlist.`；
  初始新标签页 State、真实公网导航、结构化表单输入、滚动及一次结构化 Challenge 均完成。
- `make test-integration` 通过，包含 `agent_browser_dynamic_micro_batches=true`、
  `agent_browser_high_level_tools=true`、`page_composite_stability=true`、
  `challenge_visual_automation=true`、文件/截图/JavaScript、取消、Outcome、租户隔离、mTLS、
  Profile/恢复与 AgentClipboard Bridge 等完整链。
- `make ci` 通过：Java、Rust Workspace/Clippy、Web、Worker、默认 Compose/Personal Secure
  契约、四语言 SDK、供应链、Operator、N/N-1 和 50k Coordinator Capacity 均通过。
- `make contracts-check` 通过，Redocly 无活动 warning，输出三个显式、带原因的 ignore。
- `NodeEventMapperTest`、State Collector 34 项（另 2 项显式真实浏览器 ignored）、Node Agent
  26 项及 `git diff --check` 通过。

## 剩余边界

本轮关闭的是已证实的仓库代码阻断与契约质量告警，不等于 V16 生产认证。真实企业 IdP、目标
Provider/KMS/云环境、多 Region、目标 Linux 长稳、Pager 到达及组织发布签字仍按现有生产 Gate
执行；`kind` 缺失也仍是当前本机 Kubernetes E2E 环境限制，而不是本轮伪造通过的项目。
