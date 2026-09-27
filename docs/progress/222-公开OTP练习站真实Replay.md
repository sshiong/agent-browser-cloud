# 公开 OTP 练习站真实 Replay

> 日期：2026-09-27
> 范围：公开授权测试站、真实 Chrome、一次性验证码输入、错误码与正确码结果

## 实现

- 固定 Replay Dataset 增加 Expand Testing 公开 OTP 练习站两例，分别要求错误码停在
  `/otp-verification`，正确码到达 `/secure`。Dataset 只列精确站点、允许域、动作与结果，
  不含邮箱或验证码。
- 真实 Browser Node/Chrome 经 Agent `NAVIGATE`、`SCROLL`、`TYPE_TEXT` 和
  `CLICK_TARGET` 运行。网站公布的测试邮箱与固定验证码先进入一次性 `USERNAME/OTP`
  Secret；出现 OTP Challenge 时，以绑定原 Task 的一次性响应续行。Task、Browser State、
  Dataset 和审计不保存验证码明文。
- 公开站的 OTP 输入框未声明标准 `autocomplete=one-time-code`。Node 仅从控件自身的
  `id/name/aria-label/placeholder` 识别受限 `one-time-code` 类别，仍隐藏输入框名称和值。
  检测器仅在可见、可用的敏感验证码输入框出现后暂停 Agent，入口页标题写有 OTP
  不再阻止先填邮箱。一次性响应后同一元素在同一路径上的滚动与重采，有 15 秒续行窗口；
  元素或路径变化立即重新检测。
- `make test-real-public-otp` 提供定向入口；完整 `make test-real-url-agent` 含新增两例。
  出口代理保持精确主机白名单及 CONNECT 证据要求。

## 验证

- OrbStack preflight：`Running`、Docker context `orbstack`、`OS=OrbStack`。
- Java Control Plane `test bootJar`（OrbStack Linux JDK）通过；Rust `node-agent` 构建通过。
- `cargo test --locked --manifest-path apps/browser-node/Cargo.toml -p state-collector`：
  35 项通过，2 项需单独真实 Chromium 配置而跳过；本次完整 Replay 已运行真实 Chrome。
- `python3 -m unittest discover -s tests/validation -p test_replay_gate.py`：6 项通过。
- `REAL_URL_SKIP_BUILD=true make test-real-public-otp`：真实 Chrome 验证错误码和正确码，
  输出 `practiceOtp=verified`；一次性响应与原 Task 绑定，验证码没有出现在 Task/State 响应。
- `REAL_URL_SKIP_BUILD=true make test-real-url-agent`：完整 13 例通过，真实 Chrome
  `153.0.8010.54`，输出 `status=PASS` 与 `Real-URL Agent matrix passed`。首次运行在
  新增 OTP 用例之前的 Selenium 公共表单触发 `PAGE_UNSTABLE`；第二次运行全量通过，
  保留首次失败日志用于外部页面波动分析。
- `make test-replay-gate` 六项、`make docs-check` 七项、Java `spotlessCheck`、
  Rust `cargo fmt --check`、Shell `bash -n` 和 `git diff --check` 均通过。

## 边界

练习站使用网站公布的固定验证码，不涉及真实短信、邮件或 TOTP 交付，也不代表企业 IdP、
支付交易或客户 SPA 已通过。真实客户系统仍需要授权 Dataset、Provider 凭据、结果契约与目标环境 Gate。
