# 公开 Selenium 表单真实 Chrome Replay

> 日期：2026-09-26
> 范围：真实网站覆盖、Agent 结构化输入、Replay 逐 case 证据

## 实现与结果

- 固定授权的公开 Replay Dataset 新增 Selenium 官方 Web Form。浏览器出口只允许精确 `www.selenium.dev` 与页面引用的静态资源 Host；没有客户数据或生产凭据。
- 真实 Chrome 153 中，Agent 通过 `NAVIGATE` 打开公开页面，按结构化 Target 输入无害测试标记，`SCROLL` 后以 `CLICK_TARGET` 提交原生 HTML 表单。测试同时验证输入未进入 Agent Task 响应、页面 State 保留输入、提交后到达 `/selenium/web/submitted-form.html` 且 URL 携带标记。只有全部断言完成，Replay Gate 才记录该 case。
- `REAL_URL_SKIP_BUILD=true` 仅用于已有当前构建产物时跳过重复构建；默认 Gate 仍先构建。此次 macOS 本地 Gradle 的 `protoc-gen-grpc-java:1.62.2:osx-aarch_64` 上游产物实际为 x86_64，故通过 OrbStack 构建当前 Control Plane JAR，再以本地 Cargo 构建 Node/Helper 执行 Gate。

## 验证

- OrbStack 预检：`Running`、context `orbstack`、`OS=OrbStack`。
- `make test-replay-gate`：4 项通过。
- `REAL_URL_SKIP_BUILD=true make test-real-url-agent`：真实 Chrome `153.0.8010.54` 完整 Gate 连续两次通过；最终 Runtime Validation 为 `PASSED`，Evidence Hash 为 `43ee4ead8db00133225b12163f6ad28acbfeb0f68e3578be575cda18d3bb7f2a`；Dataset Digest 为 `sha256:60c80eabf4a7ff23bcb825a29cafc57f7b6b9a55cb581c5048374aa023f16f93`。

## 边界与发现

- `practice.expandtesting.com/dynamic-controls` 另作探索样本：真实 Chrome 页面正常、Target 可见且点击完成，但 CDN 脚本曾因 `ERR_SSL_PROTOCOL_ERROR` 未加载，`jQuery` 与 `swapCheckbox` 未定义，按钮无业务效果。该站未计入通过的 Replay Dataset。此现象说明动作派发成功不等于站点业务结果成功。
- 本次只新增一个公开原生表单样本；真实企业 IdP、SMS/Email/TOTP、支付、客户 SPA Replay 和目标环境长稳仍是外部 Gate。
