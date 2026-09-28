# 公开 OTP 既有密文计划续行与 Replay 波动

> 日期：2026-09-29
> 范围：AUTONOMOUS 一次性 OTP 输入后的 Challenge 续行、公开真实 URL Replay。

## 发现与修复

重跑公开站点矩阵时，练习站的一次性 OTP `TYPE_TEXT` Step 已由 Node 和 Control Plane 验证，原 Task 却在随后的同一验证码框 Challenge 上进入 `WAITING_FOR_HUMAN`。原有“下一 Step 带密文则继续”规则没有覆盖**当前 Step 已经成功输入 OTP**的情况。

完成器现在只在以下证据同时成立时把该 OTP Challenge 记为 `RESOLVED` 并续行原 Task：Session 为 AUTONOMOUS；当前已验证 Step 为受敏感目标授权的 OTP `TYPE_TEXT`；Challenge 是同 Tenant/Session/Context Epoch、同 State Version/Target Revision/State Hash 的 OTP；前后 Target Ref 的稳定 Element ID 摘要相同。不同元素、旧状态、其他 Challenge 类型或未验证输入继续按原规则暂停。Challenge 事件保留在 PostgreSQL，Secret 内容不进入事件、API 或日志。

真实 URL 回放原先只凭 `COMPLETE` State 就提交部分动作，曾在 IdP 页面仍为 `CHANGING`、网络静默为 0 时连续触发 `STATE_STALE`。回放现在在相关登录和 OTP 动作前要求 `FRESH` 且 `STABLE` 的权威页面证据；失败日志只输出 Task/State 的最小诊断字段，避免完整 Plan/Target 列表淹没错误原因。Node 的动作稳定性围栏没有放宽。

## 验证与未关闭边界

- OrbStack 预检为 `Running`、Docker context `orbstack`、`OS=OrbStack`；Linux ARM JDK 21 构建的 Control Plane 全量 **608 项测试**、`spotlessCheck` 和 `bootJar` 通过。新增测试验证同元素、同状态的 OTP 解决，以及不同元素/State Hash 不得解决。
- 修改前真实回放复现 `TYPE_TEXT VERIFIED → WAITING_FOR_HUMAN`；修复后 `REAL_URL_SKIP_BUILD=true make test-real-public-otp` 的错误码和正确码均通过，输出 `practiceOtp=verified`。公开 Duende IdP 定向回放在等待 `STABLE` 后也通过。
- `make test-replay-gate` 九项、Python 编译、`make docs-check` 七项及 `git diff --check` 通过。
- **完整 16 例真实 URL 矩阵本轮未通过**。不同尝试分别在公开练习站遇到 `PAGE_UNSTABLE`/CDP 停止更新，在 SauceDemo 遇到登录页有标题但 45 秒内没有可执行 Target。定向通过不等于完整矩阵长稳。后续须区分第三方页面资源失败、Chromium/CDP 采样停顿与本平台状态恢复问题，并取得连续完整通过证据。

此修复不证明真实短信/邮件/TOTP 交付、企业 IdP、客户 SPA 或支付页面。对应系统所有者授权 Replay 与目标环境 Gate 继续保持未完成。
