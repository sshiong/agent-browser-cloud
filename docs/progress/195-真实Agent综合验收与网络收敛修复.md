# 真实 Agent 综合验收与网络收敛修复

> 日期：2026-09-20
> 状态：仓库内缺陷已修复，真实 Chrome、外部 Provider 与完整 Integration 已通过

## 验收目标

本轮使用 OrbStack、真实 Google Chrome 153、仓库受控测试站点和运营方提供的 OpenAI Responses
兼容 `code` 聚合路由，验证当前 Agent 是否真的能够读取网页、点击、输入账号密码、处理低风险验证、
保存/恢复 Profile 与 Cookie、导入导出加密 Checkpoint、捕获/分析截图并正确记录模型响应。测试 Secret
仅通过进程环境或一次性密文引用使用，结果和文档不保存明文。

## 真实发现与修复

### 1. 稳定页面被旧 parser 网络项永久阻断

第一次运行 `make test-real-url-agent` 时，公开网页读取已成功，但受控表单的 `TYPE_TEXT` 以
`PAGE_UNSTABLE` 失败。诊断证据显示 DOM、Layout、Focus、Route 已静默约 5.5 秒，活动页却仍保留
7 个 `Image:parser` 请求且 `network_quiet_ms=0`。这些项目来自跨导航或代理拒绝时 CDP 未补发的
`loadingFinished/loadingFailed`，不是仍在变化的业务请求。

State Collector 现在在精确 Page 的 `Page.loadEventFired` 后收敛 Document 与 parser-bound 请求；
脚本 Fetch/XHR、上传、下载、表单提交和交易请求不受此规则影响，仍必须收到自己的终态并保持
fail-closed。定向测试固定“同 Page 清理 Document/Image parser、保留 script Fetch、不同 Page 不动”。
修复后同一真实 Chrome Gate 重跑通过。

### 2. Vision Provider Request ID 丢失

外部 Vision 请求成功返回 `ACT/CLICK`，但聚合 Provider 将请求身份放在 Responses JSON `id`，没有
`x-request-id` 响应头。Reviewer 已支持这种合法返回，Vision Worker 却只读取响应头，导致追踪字段为空。
Vision 现在与 Reviewer 一致：优先响应头，缺失时回退 JSON `id`，并以固定字符集和 256 字节上限过滤。

## 验证证据

| Gate | 结果 |
| --- | --- |
| 外部 Reviewer Provider 探测 | `/v1` 自动补 `/responses`；`code` 返回 `APPROVE/SAFE`，usage、请求 ID、输出哈希有效 |
| `make test-real-url-agent` | 首次真实复现缺陷；修复后 Chrome 153 通过网页读取、点击、`TYPE_TEXT`、滚动、自动单击 Challenge 与域名阻断 |
| `make test-real-login-agent-provider` | 三个独立 Session/Profile 通过；正确登录、错误密码预期失败和假成功拒绝均由真实 `code` Outcome 验证，Secret 未输出 |
| `make test-turnstile-interactive` | Cloudflare 官方 forced-interactive 测试控件在 headed Chrome 中真实点击并取得测试 token |
| 真实 Chrome Cookie Checkpoint | `Storage.setCookies` 写入持久/Session Cookie，`Browser.close`、Checkpoint、删除工作区、Restore 后两者均存在 |
| 外部 Vision Provider 探测 | 真实 Turnstile 测试截图返回单个 `CLICK`、0.99 置信度；usage、输出哈希和 JSON `id` 请求追踪均有效 |
| `make test-agent-worker` | 37 项通过，含 Vision JSON `id` 回归、OCR/PII 二次扫描与 Lease 丢失阻断 |
| `make test-integration` | 通过；Agent 高层/高级动作、微批、Outcome、Screenshot、Vision 像素隐私、Profile 加密导出/导入、可复用 Session 等均为 true |
| `make test-object-storage` | 通过；commit-last、500ms 有界超时和本地 Checkpoint 可重试 |
| `make ci` | 通过；Java、Rust Workspace/Clippy、Web 143 项、Worker、Compose、契约/四 SDK、供应链、Operator、N−1 与 50k 容量 Gate |

## 当前真实边界

- 平台支持受治理的 Profile/Checkpoint 导入导出和 Cookie 持久化恢复，但不向普通 Agent 暴露任意
  Cookie 注入或 `document.cookie` JavaScript 逃逸；这属于刻意的安全边界，不是缺失。导入归档包含
  Cookie/Session/LocalStorage 等敏感资料，必须走管理员、一次性授权和加密对象链。
- 密码和 OTP 只能走用途绑定、一次性消费的敏感输入 Secret。仓库 Integration 已验证 OTP 等待、
  提交、有限重试和原任务续行；本轮没有接入真实短信/邮箱/IAM Provider，因此“真实外部 OTP 获取后
  服务端登录成功”仍是部署/客户站点 Gate。
- Vision 自动化只允许低风险、精确 State/Tab/Region 围栏的 Challenge 动作。跨域 Opaque Frame、
  支付、账号安全决定或无法完成隐私证明的截图继续转人工，不允许模型任意操作。
- 本轮 LAN HTTP Provider 验收不等于生产准入；生产 Worker 仍要求 HTTPS、Host Allowlist、真实 IdP、
  目标云对象存储/KMS、多 Region 和长稳 Gate。
