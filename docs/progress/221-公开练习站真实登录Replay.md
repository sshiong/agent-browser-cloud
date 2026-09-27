# 公开练习站真实登录 Replay

> 日期：2026-09-27
> 范围：授权公开测试站、真实 Chrome、一次性敏感输入、错误密码与成功登录

## 实现

- 固定 Replay Dataset 增加 Expand Testing 公开练习登录的错误密码与成功登录两例。数据集仅保存站点、精确允许域、预期路径和动作类别，不保存密码。
- 真实 Browser Node/Chrome 通过 Agent `NAVIGATE`、`SCROLL`、`TYPE_TEXT`、`CLICK_TARGET` 执行。站点公开发布的测试账号与密码也必须先写入一次性 `USERNAME/PASSWORD` Secret，Agent Plan 不携带明文。
- 错误密码必须在 `/login` 的结构化 `alert` 中出现站点实际文案 `Your password is invalid!`；正确密码必须到达 `/secure` 并出现 `Logout`。输入后复核 Agent Task 和 Browser State 中不存在密码明文。
- `make test-real-public-login` 提供定向真实网站入口；完整 `make test-real-url-agent` 仍覆盖 Dataset 全部用例。出口代理仅允许精确主机 `practice.expandtesting.com`，并要求真实 CONNECT 证据。

## 验证

- macOS OrbStack preflight：`Running`、context `orbstack`、`OS=OrbStack`。
- `REAL_URL_SKIP_BUILD=true REAL_URL_LOGIN_ONLY=true make test-real-public-login`：真实 Chrome 完成公开导航、Selenium 表单、错误密码和成功登录，六例证据输出 `practiceLogin=verified`；连续两次通过。
- `REAL_URL_SKIP_BUILD=true make test-real-url-agent`：完整 11 例通过，输出 `Real-URL Agent matrix passed`。此前本机 Opaque Challenge 截图卡在 `CAPTURING`，同时 Storage Helper 周期 SQLite 在线备份每 30 秒超时；该矩阵不验证 Profile 同步，因此把该测试运行中的 Warm Tier 周期设为一小时，重跑全量通过。产品默认同步间隔未改。
- `python3 -m unittest discover -s tests/validation -p test_replay_gate.py`：5 项通过；`python3 -m py_compile tests/compatibility/real_url_agent_matrix.py`、`bash -n` 和 `git diff --check` 通过。

## 边界

练习站账号是网站公开的测试账号，不代表真实企业 IdP、短信/邮件 OTP、支付确认或客户 SPA Replay。生产目标环境与组织 Gate 保持未完成。
