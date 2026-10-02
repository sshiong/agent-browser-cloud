# 公开 Replay 代理隧道关闭与 Rust 1.99 兼容

> 日期：2026-10-02
> 范围：公开 Replay 测试出口代理、Browser Node/Desktop 的宏依赖。

## CONNECT 隧道收尾

公开 Replay 使用精确 Host Allowlist 的测试出口代理。原代理在上游 EOF 或 30 秒
空闲超时后退出 relay，但 `HTTP/1.1` Handler 仍会尝试在原 TCP 连接上读取下一条
HTTP 请求。CONNECT 之后的连接已经属于加密隧道；上游关闭而浏览器侧未关闭，会使
Chromium 继续等待结束。

代理现在在成功建立 CONNECT 时设置 `close_connection`，relay 结束后由 HTTP
Server 关闭浏览器侧 TCP。默认 30 秒超时、Host/公网地址校验和拒绝直连的策略保持原样。
两个真实 loopback TCP 回归分别验证双向数据传输后上游 EOF 和空闲超时：原代码两项
均因浏览器侧未收到 EOF 超时，修复后两项通过。测试只缩短自身的 relay 空闲时间。

此缺陷已被独立复现；不能因此认定历史所有 `XHR:script` 在途现象均由它导致。

## Rust 1.99 的宏兼容

提交 `bd7092f` 的 GitHub `ci` run `36960200015` 在 Rust 1.99 严格 Clippy 中
失败，错误来自 `async-trait` 0.1.91 展开时重复添加 `must_use`，涉及 Runtime
Supervisor 与 Tonic 生成 RPC。该提交的 Desktop run `36960200016` 已通过
Windows/macOS 验证。

Browser Node 最低 `async-trait` 版本与 Browser Node/Desktop 两份 Lockfile 更新为
0.1.92，其他依赖不变。上游 [0.1.92 发布说明](https://github.com/dtolnay/async-trait/releases/tag/0.1.92)
明确包含生成代码的 `double_must_use` 修复；保留严格 `-D warnings`，不新增 lint 豁免。

## 验证与剩余边界

- Replay Gate **11 项**通过，包含上述两个 TCP 收尾回归；Python 编译与 diff 检查通过。
- 升级依赖后的本机 Rust 1.97 Workspace 严格 Clippy、Rust Workspace **178 项**
  （另有五项须独立环境启用）与 Desktop **2 项**安全边界测试通过，文档检查七项通过。
- 修复代理后，Chrome **154.0.8037.95** 的完整 **16 例单 Session** 公开矩阵
  **连续两次通过**，两次运行各创建独立 Session，
  Dataset Hash 仍为 `8789fd8a82ddd58017716de8389818d7c564684e00f4399565889cd3d175f21d`。
  登录、错误密码、错误/正确 OTP、React SPA、购物车、Duende 站内登录与拒绝用例保持
  原授权契约、精确结果校验和边界；没有扩大跨域动作权限。
- 两次 Validation 分别为 `val_1a757145c82d4f279903` 与 `val_955431ad656341af850b`，
  Evidence Hash 分别为 `6eb4b739d27d49e1ef87cd3118920af0928ddd84537da33b67a39c6415957990`
  与 `59006f78e6fd65ba201c109aa6fbd00113a8dbff591b25388c257e5f5976ef3f`。
  两次短时全量通过不是小时级长稳或目标 Linux 的证书。
- 本机 Rust 1.99 严格 Clippy 与本次提交 CI 仍需核对。

公开固定 OTP 不是短信/邮件/TOTP 交付证明；Duende 站内登录不是独立 OIDC Client
授权码交换或企业租户接入。真实支付、客户 SPA、目标 Linux/云长稳、云 KMS/IAM、
原生 Legal Hold、硬件 Codec、供应商取消计费和许可证权利人决策仍未完成。
