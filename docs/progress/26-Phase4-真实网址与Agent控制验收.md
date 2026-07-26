# Phase 4：真实网址与 Agent 控制验收

> 状态：已完成  
> 日期：2026-07-26  
> 入口：`make test-real-url-agent`

## 验收范围

本切片不再使用 Fake Chromium。测试会启动真实 Google Chrome、Browser Node、
Network/Storage Helper、mTLS Control Plane、PostgreSQL、Redis 与精确域名白名单代理，
并在完整 Agent 命令/事件链路上执行：

1. 导航并读取 `https://example.com/`；
2. 导航并读取 `https://www.w3.org/`；
3. 导航到确定性的授权控制夹具，执行真实 `TYPE_TEXT` 与 `SCROLL`；
4. 验证输入正文只以 SHA-256、长度和数据分类形成证据，Task API 不回显明文；
5. 点击 `example.com` 指向 IANA 的跨域链接，出口代理拒绝请求，Agent 以稳定错误失败；
6. 创建 URL 与 Allowlist 不匹配的计划，确认计划持久为安全阻断；
7. 正常终止真实 Chrome，并完成 Profile Checkpoint。

公开站点用于证明真实网络导航与读取；输入/滚动使用同一出口代理内的确定性授权页，
避免把第三方站点的可用性和 DOM 变化当成产品回归。所有浏览器流量仍必须经过代理，
未在精确 Allowlist 内的域名和私网/回环/保留地址均被拒绝。

## 真实运行发现并修复的问题

| 问题 | 影响 | 修复 |
| --- | --- | --- |
| CDP `Input.dispatchKeyEvent` 对非字符键发送 `"text": null` | 真实 Chrome 拒绝 Type Text，Fake CDP 未发现 | 非字符键完全省略可选 `text` 字段，并新增协议形状单测 |
| Click 后只验证 State Version，不验证最终 Domain | 目标链接可能把浏览器带离任务 Allowlist | 所有非 Navigate 动作都验证执行后 URL；未知/错误页 URL 稳定返回 `POST_ACTION_DOMAIN_NOT_ALLOWED` |
| `chrome-error://` 被当作普通 HTTP URL 解析 | 事件处理抛异常并重复投递，任务最终超时 | 状态 URL 解析改为 fail-closed，不让错误页异常逃逸事务 |
| macOS Chrome Profile 的 `RunningChromeVersion` 符号链接被视为未知持久数据 | 正常终止无法 Checkpoint | 仅将该已知运行时标记列入 Ephemeral；任意未知符号链接仍拒绝 |
| 出口探测夹具只返回 IP | Network Helper 无法解析 country/ASN，会话停在 STARTING | 夹具严格实现 `exitIp/country/asn` 契约 |

## 验收证据

已通过：

- `cargo test --locked --manifest-path apps/browser-node/Cargo.toml -p input-sandbox`
- `cargo test --locked --manifest-path apps/browser-node/Cargo.toml -p storage-helper`
- `./gradlew -p apps/control-plane test --tests io.browsercloud.application.AgentNavigationCompletionServiceTest`
- `make test-real-url-agent`

真实矩阵最终输出：

```json
{
  "status": "PASS",
  "publicUrls": ["https://example.com/", "https://www.w3.org/"],
  "verifiedControls": ["NAVIGATE", "READ", "TYPE_TEXT", "SCROLL"],
  "failClosed": ["CROSS_DOMAIN_CLICK", "NON_ALLOWLISTED_PLAN"]
}
```

## 仍未完成

1. 本测试证明的是自建精确 Allowlist Forward Proxy，不等于真实商业 Proxy Provider、
   短期凭据、地理出口和供应商故障切换验收。
2. Click 的允许域当前按 Task Allowlist 验证；更细粒度的单 Step 目标域、链接预解析和
   导航前 Policy Decision 仍属于后续 Action Validation DSL。
3. 登录、验证码、支付、提交表单等高风险动作未纳入自动化验收；它们必须经过独立确认、
   人工接管或明确禁止，不能由公开站点兼容测试绕过。
4. 外部页面耗时受公网影响；生产还需要可配置 Step Deadline、N/N-1 浏览器兼容矩阵和
   独立网络稳定性报告。

