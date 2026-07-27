# Tauri 2 桌面端安全容器与发布基线

> 日期：2026-07-28
> 状态：macOS 本机编译与无安装包验证构建已完成；Windows 由新增 CI 验证；
> 平台代码签名、Notarization、真实更新源和桌面视觉/无障碍矩阵仍是发布 Gate

## 本轮完成

### 共享前端与平台边界

- 新增 `apps/desktop` Tauri 2 工程，直接复用 `apps/web-console` 的 React UI、
  API Client、权限和 Query 状态，不复制第二套业务页面。
- `PlatformAdapter` 隔离 Web 与 Tauri 能力。业务组件不直接访问 Rust Command、
  系统对话框、通知、外部浏览器、Deep Link 或 Updater。
- Web Adapter 不提供本机凭据和 Runtime 访问；Desktop Adapter 通过动态导入加载
  Tauri 插件，Web 构建不会同步装载桌面实现。
- 设置页新增真实客户端状态：平台/版本、发行包 Runtime、系统凭据库和用户主动触发的
  更新检查。更新失败明确显示错误，不用 Mock、计时器或伪版本状态。

### 桌面 OIDC 与 Secret

- Desktop OIDC 使用系统默认浏览器，不在 WebView 内输入企业密码。
- 回调使用 `agentbrowsercloud://auth/callback`；冷启动读取当前 Deep Link，运行中通过
 事件监听处理，重复 URL 在当前进程内去重。
- Windows 使用 Single Instance 的 Deep Link 转发；单实例插件在 Deep Link 插件前注册。
- `oidc-client-ts` 的 PKCE State、User 和 Refresh Token 通过 `StateStore` 写入
  macOS Keychain 或 Windows Credential Manager，不使用 `localStorage`、JSON 文件或
  Rust 进程内存冒充安全存储。
- 自定义 Rust Command 只接受 `oidc.` 前缀和受限字符/长度的键，值限制 256 KiB；
  Keyring 错误对 WebView 脱敏。
- User/State 各有独立索引，避免同名 OIDC Key 互相覆盖；新增单测覆盖读写、删除和损坏
  索引的 fail-closed 行为，以及并发写入不丢索引。

### 最小权限桌面容器

- Tauri `withGlobalTauri=false`、`freezePrototype=true`，主窗口绑定单一 Capability。
- Capability 只开放 App 基础、Deep Link、用户选择文件、通知、HTTPS 外部打开、
  OS 平台、进程重启、Updater、自定义凭据和本机 Runtime 状态。
- 未引入 Shell、文件系统、任意 HTTP Client 或 Remote Capability；外部导航在
  TypeScript 再次限制为 HTTPS。
- CSP 默认拒绝远端脚本、对象、Frame 和任意表单目标。开发构建只放行本地
  Control Plane/Remote Desktop；签名构建根据明确的 HTTPS API/IdP Origin 生成 CSP。
- 本机 Runtime 检查只验证发行包资源目录内的固定 `runtime/node-agent[.exe]`，
  不执行 Shell、不接受前端传入路径。
- 实现单实例、主窗口聚焦和系统托盘显示/退出；图标从项目 SVG 生成 macOS/Windows
  桌面资源。

### 更新、签名与 CI

- Updater 写操作只能由用户主动检查后执行，下载/安装由 Tauri Updater 验签，
  安装后使用 Process Plugin 重启。
- `build:signed` 缺少以下任一项都会 fail-closed：
  - Tauri Updater 私钥和公钥；
  - HTTPS Updater Endpoint；
  - HTTPS API Base；
  - HTTPS OIDC Authority；
  - OIDC Client ID；
  - macOS Developer ID、Apple ID/App-specific Password 和 Team ID，或 Windows
    Certificate Thumbprint 与 HTTPS Timestamp URL。
- 签名构建启用 `createUpdaterArtifacts`；模板化 Updater URL 保留
  `{{target}}`、`{{arch}}`、`{{current_version}}`，不会被 URL 编码破坏。
- 新增 macOS/Windows GitHub Workflow，安装独立锁定的 Web/Desktop 依赖，
  执行 Rust Format 和 `tauri build --no-bundle`，防止桌面壳长期失编。
- Makefile 增加 `install-desktop`、`lint-desktop` 和 `build-desktop`。

## 验证证据

本机 macOS 已通过：

```text
pnpm --dir apps/web-console lint
pnpm --dir apps/web-console test
  9 files / 33 tests passed
pnpm --dir apps/web-console build
cargo fmt --all --check --manifest-path apps/desktop/src-tauri/Cargo.toml
cargo test --locked --manifest-path apps/desktop/src-tauri/Cargo.toml
  2 tests passed
cargo check --locked --manifest-path apps/desktop/src-tauri/Cargo.toml
pnpm --dir apps/desktop build:unsigned
  Built application at apps/desktop/src-tauri/target/release/agent-browser-cloud-desktop
```

无 Secret 执行 `build:signed` 已实测拒绝，并逐项列出公共和平台签名缺失配置。这个失败是正确的
发布安全门禁，不是已签名发行证据。

## 仍未完成

1. 注入组织持有的 Apple Developer ID、Notarization/App Store Connect 凭据，
   完成签名、Notarization、Gatekeeper 安装和升级/回滚验收。
2. 注入组织持有的 Windows EV/OV 代码签名证书或 Azure Trusted Signing，
   完成 SmartScreen、安装/卸载和升级/回滚验收。
3. 创建隔离的 Tauri Updater 私钥、公钥轮换流程、真实 HTTPS 更新清单/制品存储，
   验证合法升级、篡改拒绝、降级策略和密钥撤销。
4. 在真实企业 IdP 注册桌面 Redirect URI，完成 PKCE、MFA/ACR、Refresh、
   Logout、Deep Link 冷/热启动和租户 Claim 联调。
5. Windows CI 首次运行结果、真实 Windows 机器 Credential Manager/Deep Link/
   Tray/Updater 验收；当前本机只证明 macOS 编译。
6. 桌面端 SSE 在断网、系统休眠/唤醒、网络切换和长期后台运行下的重连验收。
7. 1280×800、1440×900、Windows 缩放、全键盘、屏幕阅读器和视觉回归矩阵。
8. 若交付本机 Browser Node，仍需将签名 Runtime 作为 Tauri Resource 打包，并完成
   启停、升级、Crash Recovery 与权限隔离；当前只做存在性检查，不启动进程。

## 结论

“尚未创建 Tauri 2”这一代码缺口已关闭；桌面端现在有可编译的安全容器和明确的发布
门禁。它还不是可对外分发的已签名产品，Apple/Microsoft 信任链、真实更新基础设施、
真实 IdP 和桌面验收必须在目标环境继续完成。
