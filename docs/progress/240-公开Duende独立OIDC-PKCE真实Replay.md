# 公开 Duende 独立 OIDC/PKCE 真实 Replay

> 日期：2026-10-02
> 范围：官方公开演示 IdP、独立临时 Client、Agent 点击与协议结果验证。

## 授权与流程

[Duende 官方演示](https://demo.duendesoftware.com/)明确提供 `interactive.public`
Authorization Code/PKCE Client 供自己的演示应用使用。官方
[DemoRedirectValidator](https://github.com/DuendeSoftware/demo.duendesoftware.com/blob/main/src/DemoRedirectValidator.cs)
允许演示回调 URI；该规则只作为测试授权依据，不复制到产品的 Redirect 校验。

既有 Agent 用一次性 Secret 输入公开 `bob/bob` 测试账号并验证站内登录；随后打开
仓库拥有的 Client 入口，点击页面可见的授权链接，沿用该浏览器的 IdP SSO Session。
Client 固定 issuer、Client ID、Discovery/Token/JWKS/UserInfo 精确 HTTPS 端点、
回调 URI、`openid profile` Scope、S256 和 `form_post`，不申请 API 或 Offline Access。

回调只接收有界 POST Body，核对一次性随机 State、有效期和可选返回 issuer，先消费
State 再兑换授权码。独立验证器使用当前 JWKS 验证 RS256 签名、issuer、audience/azp、
nonce、iat/exp/nbf，并要求 UserInfo 的 subject 与已签名 ID Token 一致且为公开测试
用户。再次兑换同一授权码必须收到 `400 invalid_grant`；完成后只返回五个证明布尔值。
授权码、PKCE verifier、Access/ID Token 仅经过内存与子进程 stdin，不保存到文件、
Plan、日志或公共 API；不返回 Refresh Token。

## Browser 边界与 HTTPS Fixture

原导航域名围栏拒绝了入口页到 IdP 的直接重定向；测试改为页面可见链接上的
`CLICK_TARGET`，两个精确域名在 Task/Dataset 中显式授权。产品 NAVIGATE 围栏保持原样。

HTTP 表单回调尝试最终进入 Chrome 错误页，未取得回调证明；最终采用本机 HTTPS
Fixture。每轮生成一天有效期的独立证书，私钥只放在私有临时目录并设为 `0600`。
测试启动器仅通过精确 SPKI 信任该临时公钥，公开 IdP 仍走正常 CA/TLS 校验。
Proxy 仅把授权的 `agent-controls.invalid:443` 映射到私有 loopback TLS Listener；
其他 Host 继续通过原公网地址检查。没有修改系统 Trust Store 或产品启动策略。

真实 IdP 登录页另确认密码框在视口外：回放先执行普通滚动，再重新读取可见 Target
并提交一次性密码；没有操作隐藏控件或依 DOM 顺序猜测目标。

完整矩阵第二轮发现 OTP 回放把“显示验证码框”和“显示提交按钮”拆为两个 Task。
第一个滚动已正确触发缺少 OTP 的人工协助，测试却只处理第二个 Task 的响应，因而超时。
现在合并为一个显示 Task，并复用已有 Challenge ID/精确 Target 的一次性 OTP 响应与原
Task 续行验证。产品 Challenge、Secret 和状态围栏均保持原样。

## 验证与边界

- Replay Gate **17 项**通过，包括 Dataset 的 issuer/client/callback/scope/mode
  篡改拒绝、错误/重复/过期 State、Discovery 端点替换、重复授权码兑换证明，以及
  明文回调在调用 Provider 前拒绝。
- Gate 内的独立 Node 验证器 **10 项**通过，涵盖正确签名、签名篡改、算法降级、
  issuer/audience/azp/nonce/时间错误、UserInfo subject 不一致及重复 Key 歧义。
- `REAL_URL_SKIP_BUILD=true make test-real-public-idp` 真实 Chrome **154.0.8037.95**
  通过，两个 Case 均有证据：`public-duende-idp-login` 与 `public-duende-oidc-code-pkce`。
- Python 编译、Shell 语法与 diff 检查通过。新增完整 **17 例**第一次运行在练习站
  登录入口因网络静默为零而失败，第二次在上述 OTP 显示 Task 等待中超时；均尚未执行
  到新 OIDC 用例。合并 OTP 显示 Task 后完整 **17 例通过**，Chrome 为
  `154.0.8037.95`，Validation ID 为 `val_a446c7428e2249459eb4`，Dataset SHA-256 为
  `60c6f170bfa2160a6bfbeb29bc465a70051c8b830fe36f7d0713728bfd930da3`。
  后续独立 Session 复跑结果另行核验，短时通过不替代小时级长稳。
- 基线 `7080525` 的 GitHub `ci` run `36961599621` 与 Desktop run `36961599592`
  已核对通过，包括完整 Integration、Object Storage/Recording GameDay、Kubernetes
  Operator E2E 和 Windows/macOS。新测试提交必须独立核验 CI。

这项证据覆盖公开演示的独立 OIDC Client 授权码交换与 SSO，不证明企业租户映射、
MFA/ACR、客户 Logout 联动或目标 IdP 接入。真实 OTP 交付、支付、客户 SPA/视觉数据集、
目标 Linux/云长稳和硬件 Codec 仍未完成。云原生 Legal Hold 继续需要独立治理身份、
审批范围与审计链，运行时 Storage Helper 不新增对象保留设置/解除权限。
