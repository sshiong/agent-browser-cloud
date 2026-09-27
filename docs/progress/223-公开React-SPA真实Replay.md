# 公开 React SPA 真实 Replay

> 日期：2026-09-27
> 范围：公开浏览器测试站、真实 Chrome、结构化 Target、Hash 路由及浏览器本地状态

## 实现

- 固定授权 Replay Dataset 增加 `https://demo.playwright.dev/todomvc/`，只允许
  `demo.playwright.dev` 出口；测试只输入随机公开标记，不触及账号、个人信息或交易。
- Agent 通过正式 `NAVIGATE`、`TYPE_TEXT`、`PRESS_KEY`、`CLICK_TARGET` 与状态读取
  创建唯一待办、标记完成、打开 `#/completed`，再离开站点并重新打开该路由，核验
  `checked=true`。测试同时要求代理 CONNECT 记录命中精确主机。
- 真实回放发现输入沙箱发出的 Enter CDP 事件缺少虚拟键码。该站的 React 处理器使用
  `event.keyCode === 13`，所以此前文本留在输入框。现在为 Enter 等导航键的 down/up
  及失败释放补齐 `code`、Windows/native 虚拟键码，保留既有键盘账本与输入围栏。
- 该站将待办复选框设置为 `opacity: 0`。Node 继续拒绝直接操作不可见目标；State
  Collector 只投影关联非敏感 checkbox/radio 的标准 `label[for]`，不会由此开放
  文件输入或敏感控件标签。测试通过页面可见的“Mark all as complete”标签
  完成唯一待办，并从复选框的结构化 `checked` 状态验证结果。
- 公开站偶尔首次加载后 React 根节点为空。Replay 最多执行一次普通 Agent 导航重试，
  第二次仍无可交互输入框则失败；该恢复只针对站点初始化，不放宽动作或出口策略。

## 验证

- OrbStack preflight：`Running`、Docker context `orbstack`、`OS=OrbStack`。
- `cargo test --locked --manifest-path apps/browser-node/Cargo.toml -p input-sandbox`：9 项通过。
- `cargo test --locked --manifest-path apps/browser-node/Cargo.toml -p state-collector`：
  35 项通过，2 项需要单独真实 Chromium 配置而跳过；本轮 Replay 已运行真实 Chrome。
- Rust Workspace 严格 `cargo clippy --workspace --all-targets -- -D warnings` 通过。
- `REAL_URL_SKIP_BUILD=true make test-real-public-spa`：输出 `publicSpa=verified`，
  并核验精确主机 CONNECT 记录。
- `REAL_URL_SKIP_BUILD=true make test-real-url-agent`：完整 14 例通过，真实 Chrome
  `153.0.8010.54`，输出 `status=PASS` 与 `Real-URL Agent matrix passed`；
  登录、OTP、低风险 Challenge、Opaque Frame 与拒绝跨域动作均保留在同一 Gate。
- `make test-replay-gate`：7 项通过；Python 编译、Shell 语法和 `git diff --check` 通过。
- `make docs-check`：7 项及中英文 README 目录与本地链接检查通过。

## 边界

公开 TodoMVC 是浏览器测试示例。它验证了一条真实外部 React SPA 路径，但不证明
客户 SPA 的字段映射、权限、业务结果或规模覆盖，也不证明真实企业 IdP、支付或外部 OTP。
客户站点需单独授权 Dataset、Provider 凭据与结果契约。
