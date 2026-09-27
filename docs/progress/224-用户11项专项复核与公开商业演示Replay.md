# 用户 11 项专项复核与公开商业演示 Replay

> 日期：2026-09-27
> 口径：仓库代码与可重复 Gate、目标环境 Gate、供应商或权利人决策分开判定。

## 逐项结论

| # | 仓库现状 | 尚需的独立证据或输入 |
| --- | --- | --- |
| 1 极端重复元素 | [197](197-Adapter业务实体身份围栏闭环.md) 已实现 Adapter HMAC 实体三元组与 Node 再哈希；缺身份的完全同名目标不可执行。 | 客户页面需提供稳定实体属性与授权 Replay；不以 DOM 位置猜测。 |
| 2 跨域 iframe | [198](198-Opaque-Frame显式授权单击自动化闭环.md)、[217](217-Cross-Origin-iframe受信边界Replay-Gate.md) 只放行双重授权的低风险托管 Challenge 单击；文本、Secret、支付等继续 Human Handoff。 | 具体 Provider 的身份、iframe/后端协议、精确动作契约、回执、凭据与测试授权；未具备时不开放通用 Bridge。 |
| 3 真实网站覆盖 | 真实 Chrome 已通过公开表单、练习登录、固定码 OTP、React SPA、Sauce Labs 购物车及后续 Duende IdentityServer 演示站内登录，见 [219](219-公开Selenium表单真实Chrome-Replay.md)、[221](221-公开练习站真实登录Replay.md)、[222](222-公开OTP练习站真实Replay.md)、[223](223-公开React-SPA真实Replay.md)、[227](227-公开Duende-IdP演示登录真实Replay.md)。 | 真短信/邮箱/TOTP、独立 OIDC Client/企业 IdP、真实支付与客户 SPA 的授权 Dataset、凭据和业务结果契约。公开演示不能替代。 |
| 4 外部模型取消 | [196](196-外部模型请求传输取消闭环.md) 已在 Lease 失效时关闭 Reviewer/Outcome/Vision 的客户端 HTTP(S) socket，停止本地等待和迟到回写。 | Provider 服务端推理与计费是否停止，须该供应商的 Cancel API 与计费回执证明。 |
| 5 Recording 治理 | [200](200-Recording用途绑定播放授权闭环.md)—[203](203-Recording全帧OCR与非文本视觉隐私闭环.md) 已完成一次性播放 Grant、对象物理删除、仓库 Object Lock 和逐帧 OCR/人脸/二维码复检。 | 客户视觉数据集扩展分类 Replay、目标账户 Object Lock Apply/IAM 和云原生 Legal Hold。 |
| 6 Profile 恢复 | [205](205-Profile-Warm-Tier应用感知恢复闭环.md)—[207](207-Profile跨Region只读恢复闭环.md) 已完成 SQLite/LevelDB、Multipart Resume 与仓库级跨 Region 只读恢复。 | 真实云复制、KMS/IAM、RPO/RTO 和区域流量切换。 |
| 7 远程桌面 | [208](208-远程桌面弱网动态合帧恢复闭环.md)、[209](209-远程桌面独立低分辨率视图闭环.md) 已验证弱网恢复、临时 JPEG 降质和逐连接服务端低分辨率；[225](225-远程桌面八协作者本机实链回归.md)—[226](226-八协作者真实WebConsole与noVNC链路回归.md) 补充八个独立 Actor 的 Gateway 与完整 Web/noVNC 短时回归。 | Viewer 展示帧龄/网络反馈驱动的自适应 FPS、目标 Linux 8 Client 小时级长稳与硬件 Codec。 |
| 8 Proxy 路由 | [211](211-Proxy业务结果学习Profile粘性与受约束探索闭环.md)—[216](216-动态Proxy端点Safe-Point轮换闭环.md) 已覆盖结果学习、站点 Challenge 隔离、粘性、受约束探索、统一 SPI、远程 Adapter 和安全轮换。 | 具体商业 Provider 插件、目标云 Secret、特有认证/账单与真实 SLA Replay。 |
| 9 生产设施 | 本机容量、Kind N/N−1、Operator E2E 和 CI 是已验证仓库 Gate。 | 目标 Linux/云多节点、GPU/Media、KMS/HSM、跨 Region、Pager/GameDay、组织发布签字仍未通过；不得处理真实客户数据。 |
| 10 环境管理 | [210](210-环境配置复制与无敏感数据导出闭环.md) 已提供正式配置复制与 schema-v1 无敏感数据导出。 | 该原始代码项已关闭；生产环境发布 Gate 仍独立。 |
| 11 许可证 | `apps/browser-node/Cargo.toml` 声明 MIT，`sdks/typescript/package.json` 声明 UNLICENSED，仓库无统一权利人决策。 | 权利人确定 MIT、其他明确许可证或维持 UNLICENSED；决定前不添加许可证文本或宣称开源授权。 |

## 可自行测试与需授权的边界

公开测试站、仓库合成 Fixture、OrbStack、Kind 和 CI 可按现有范围继续验证，无需客户凭据。
客户站点、企业 IdP、支付沙箱、目标云和商业 Proxy 需各系统所有者提供测试范围、环境身份、
凭据及允许的动作/结果契约；许可证需权利人选择。不得把公开演示的通过结论扩大为生产发布证据。

## 公开商业演示 Replay

[Sauce Labs 官方文档](https://docs.saucelabs.com/web-apps/automated-testing/playwright/selenium-grid/)
公开给出 SauceDemo 测试账号及登录、加入购物车示例。本轮只在 `www.saucedemo.com` 的
精确主机白名单内，用网站公布的账号经一次性 `USERNAME/PASSWORD` Secret 登录；
随后导航到站点公开的 Backpack 商品详情页，使用该页唯一的“Add to cart”按钮，
再进入 Cart 验证商品与 Checkout 入口。回放不点击 Checkout，也不提交订单或支付。

真实站首次回放发现 `input[type=submit]` 的“Login”文字仅在 `value` 属性中，原
State Collector 没有将其投影为按钮名称。修复仅对非敏感的 submit/button/reset
输入控件读取 `value` 作为可见按钮名，密码与普通文本输入仍由原敏感分类保护。
商品列表中的多个同名“Add to cart”按钮不按 DOM 位置猜测，回放进入有唯一按钮的商品详情页。

验证：OrbStack 为 `Running`、Docker context `orbstack`、`OS=OrbStack`；
State Collector 35 项通过、2 项需单独 Chromium 配置而跳过；`make test-replay-gate`
8 项通过；`REAL_URL_SKIP_BUILD=true make test-real-public-commerce` 真实 Chrome 输出
`publicCommerce=verified`，且出口代理记录精确 `www.saucedemo.com` CONNECT。
最终 `REAL_URL_SKIP_BUILD=true make test-real-url-agent` 完整 15 例通过，真实 Chrome
`153.0.8010.54` 输出 `status=PASS`；Rust Workspace 严格 Clippy、`make docs-check`
7 项、Python 编译、Shell 语法和 `git diff --check` 通过。
