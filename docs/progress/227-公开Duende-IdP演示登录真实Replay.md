# 公开 Duende IdentityServer 演示登录真实 Replay

> 日期：2026-09-27

## 范围与结果

[Duende 官方交互登录示例](https://docs.duendesoftware.com/identityserver/quickstarts/2-interactive/)提供公开演示环境和测试用户。本轮把 `demo.duendesoftware.com` 的登录页固定为 Dataset case，使用网站公布的 `bob/bob` 测试用户。凭据只通过一次性 `USERNAME/PASSWORD` Secret API 输入，不写入 Dataset 或 Agent Plan。Replay Gate 固定 URL、精确 Host、期望路径和必经动作；出口代理也只放行该 Host。

真实 Chromium 153 的 Agent 依次导航、读取结构化 Username/敏感密码 Target、输入一次性 Secret、滚动显示 Login 按钮并点击。最终必须观察到同一 Host 的 `/diagnostics/` 和页面上的 `bob Logout`。仅在输入未被验证且返回 `STATE_STALE` 时有界重采并重新创建一次性 Secret；其他失败直接拒绝。未使用跨域 iframe 输入或支付动作。

## 验证

- OrbStack `Running`，Docker context `orbstack`，Daemon `OS=OrbStack`。
- `python3 -m unittest discover -s tests/validation -p test_replay_gate.py`：9 项通过，包含 URL/Host/路径/动作契约篡改拒绝。
- `REAL_URL_SKIP_BUILD=true make test-real-public-idp`：真实 Chrome 输出 `publicIdp=verified`，精确 Host CONNECT 断言通过。
- `REAL_URL_SKIP_BUILD=true make test-real-url-agent`：完整 16 例矩阵 `status=PASS`，Chrome `153.0.8010.54`，Validation ID `val_9bd92d5ebb7d4cf7a292`。

这是公开 IdP 演示站的**站内用户名密码登录**证据。它没有验证独立 OIDC Client 的授权码交换、企业租户映射、MFA/ACR、Logout 联动或真实客户 IdP。上述场景仍需系统所有者提供测试租户、授权、凭据及期望结果；当前 V16 生产发布状态不变。
