# 真实登录 Outcome 与官方 Turnstile 交互 Gate

> 日期：2026-09-20
> 实现提交：`594bf54 test: add real login and interactive challenge gates`
> 状态：仓库内登录/交互边界已闭环；真实外部模型 Provider 运行仍待凭据

## 背景

此前临时登录 runner 的第二个 Session 复用了同一 Profile，先触发 Profile 单 Writer Fence，因而
没有真正执行错误密码分支。登录 Outcome 使用本地模型 fixture，也不能证明外部 Provider 的实际
网络调用。Cloudflare 只验证过公共页面与仓库自有 Challenge，未发生官方交互 checkbox 的真实点击。

本切片把三项边界变成正式、可重复且不夸大结论的 Gate。

## 实现

### 1. 独立 Profile 的真实 Chrome 登录矩阵

`make test-real-login-agent` 启动 OrbStack 中的临时 PostgreSQL/Redis、本机 Control Plane、Browser
Node、真实 Chrome、隔离 Network/Storage Helper 和 Outcome Worker。三个 case 分别使用独立 Profile：

1. 正确 fixture 账号和密码，页面从登录页进入 Dashboard；
2. 错误密码，页面明确显示 `Invalid username or password`，该失败业务结果本身被正确验证；
3. 错误密码但 Expected Outcome 声明登录成功，最终必须以
   `AGENT_OUTCOME_NOT_VERIFIED` 和 `TECHNICAL_SUCCESS_WITH_UNSATISFIED_LOGIN_OUTCOME` 失败。

用户名和密码只通过一次性 `agent-input-secrets` 引用进入 `TYPE_TEXT`，任务、日志和测试摘要不输出
明文。每个输入/点击是独立持久 Task，动作后重新采集 State Version 与 Target Revision，避免用旧
DOM 围栏继续执行。共享 allowlist proxy 只接受仓库自有 `agent-controls.invalid` fixture，登录事件
只记录成功布尔值，不记录字段内容。

Outcome 摘要同时要求精确 model name/revision、正数 input token、非负 latency 和 evidence hash。
fixture 模式还检查所有请求均通过敏感字段最小化证明。

### 2. 显式真实 Provider 入口

`make test-real-login-agent-provider` 对同一真实 Chrome 矩阵启用外部 HTTPS Responses Provider，要求：

- `LOCAL_AGENT_MODEL_API_KEY_FILE` 是绝对路径、普通非符号链接、非空且权限为 0600/0400；
- `LOCAL_AGENT_MODEL_ENDPOINT` 为 HTTPS 且以 `/v1/responses` 结尾；
- model name 与 revision 必须显式固定。

配置缺失或不安全时在启动容器和浏览器前 fail-closed。它不会读取 Codex、浏览器或系统账户凭据。
当前机器没有上述独立模型 Key，因此该真实 Provider Gate 尚未执行；本轮 fixture 请求不能替代它。

### 3. Cloudflare 官方 forced-interactive checkbox

`make test-turnstile-interactive` 使用 Cloudflare 官方文档给出的 forced-interactive dummy sitekey
`3x00000000000000000000FF`，在 headed Chrome 中加载官方脚本。测试先保存未勾选截图并确认父页面
尚未收到 token，然后发送真实 mouse move/down/up。由于 widget 是跨域 Opaque Frame，若可访问性
snapshot 不提供 checkbox ref，测试只使用父页面 `.cf-turnstile` 的有界矩形计算 checkbox 坐标，
不读取、注入或修改跨域 iframe。最终必须收到官方测试 token、显示成功状态并保存点击后截图。

这是 Cloudflare 授权的测试 widget，不访问生产站点、不绕过访问控制，也不改变产品中
Opaque Frame 必须 Human Handoff 的安全策略。官方依据：
<https://developers.cloudflare.com/turnstile/troubleshooting/testing/>。

## 验证

| Gate | 结果 |
| --- | --- |
| `make test-real-login-agent` | 通过；3 Session/3 Profile，成功、错误密码与假成功拒绝均符合预期 |
| Outcome fixture 请求 | 12 次；模型身份、revision、token、latency、evidence hash 完整，Secret 未输出 |
| `make test-turnstile-interactive` | 通过；未勾选画面 → 真实鼠标点击 → 官方测试 token/成功画面 |
| `make test-agent-worker` | 32 项通过 |
| `make test-default-compose` | 3 项通过 |
| `make ci` | 通过；Java、Rust/Clippy、Web 143 项、Worker、契约/SDK、供应链、Operator、N−1 |
| 外部 Provider 缺配置预检 | 预期拒绝，启动任何运行时之前 fail-closed |
| GitHub `ci` run `35493322329` | 通过；Verify、镜像/供应链、完整 Integration、对象存储/录制 GameDay、Kind Operator E2E |
| GitHub `desktop` run `35493322318` | 通过；Windows 与 macOS 安全边界测试及 unsigned build |

截图和本地日志写入 `output/playwright/`，不进入 Git。临时容器在 Gate 退出时清理；Docker daemon
和 context 均为 OrbStack。

## 剩余边界

以下内容未完成，不能因本切片改写为已验收：

1. 使用运营方提供的独立 0600/0400 Key 实际运行 `make compose-up`、`make compose-verify` 和
   `make test-real-login-agent-provider`，取得真实外部 Provider request/evidence；
2. 真实企业 IdP/目标网站的客户授权 Replay、Session 撤销延迟与站点特有 Validator；
3. 生产 Cloudflare Challenge 不属于自动绕过目标，仍遵守 Opaque Frame/Vision/Human Handoff 策略。
