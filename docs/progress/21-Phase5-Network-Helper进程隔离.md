# Phase 5：Network Helper 进程隔离

> 日期：2026-07-26
> 状态：Network Helper 进程级拆分与故障隔离已通过本地集成验收；完整 Node Helper
> Gate 尚未关闭。

## 本步完成

| 能力 | 实现 | 验收证据 |
|---|---|---|
| 独立进程 | `network-helper` 成为独立二进制；`node-agent` 不再链接代理实现，也不读取 Provider Endpoint/出口配置 | Rust 全工作区依赖与构建通过 |
| 固定 IPC | 版本化、64 KiB 上限、长度前缀 JSON、固定 `PING/BIND/VERIFY/RELEASE` 命令，无通用 Shell/Exec | 超长帧拒绝与协议 Round-trip 单测 |
| Fail-closed Client | 每次调用独立 Unix Socket、请求 ID 关联、Schema 校验、超时；缺少 Helper 时不允许直接网络回退 | 请求 ID 串包与超时单测；集成测试拒绝 Runtime 启动 |
| Peer Credential | Helper 从 Unix Socket 内核凭证读取 UID，只接受配置的 Node Agent UID；生产环境必须显式配置 | Linux/macOS 分平台实现并通过编译 |
| 最小化部署权限 | Kubernetes 中 Node Agent/Network Helper 使用 `11001/11002` 独立 UID、共同非特权 Group、`RuntimeDefault` seccomp、`drop: [ALL]`、只读根文件系统 | `kubectl kustomize` 清单校验 |
| Secret 边界 | Static Proxy Endpoint、Expected Exit、探测 URL 只进入 Network Helper；Node Agent 仅持有 Socket Path | Node Agent 依赖与环境读取检查 |
| 崩溃隔离与恢复 | 测试主动终止 Helper；Node Agent 继续存活、未产生 Chromium 子进程、控制面收到 `NODE_COMMAND_FAILED`；Helper 独立重启后原命令重试成功 | `make test-integration` 输出三项 Helper 证据 |
| 前后端回归 | Session、Proxy、Remote Desktop 与 Web Console 仍通过真实数据面 | `make test-e2e` 输出 `WEB_CONSOLE_E2E_OK` |

## 安全边界

- Node Agent 不能再构造 `StaticProxyNetworkHelper`，也无法取得 Provider Endpoint。
- IPC 不传递代理凭证；当前 Static Provider 本身明确拒绝 URL 内嵌凭证。
- Socket Frame 在分配 Payload 前验证长度，避免无界内存申请。
- Helper 返回给 Node Agent 的错误信息经过固定错误码和通用消息收敛，避免泄露 Provider
  细节。
- Unix Socket 文件为 `0660`；仅共享 Group 可连接，随后还必须通过内核 Peer UID 校验。
- Helper 不可用时，Runtime 在启动 Chromium 前失败，`ALLOW_DIRECT_NETWORK=false` 不被绕过。

## 验收结果

```text
network_helper_process_isolated=true
network_helper_failure_closed=true
network_helper_restart_recovered=true
proxy_direct_fallback=false
WEB_CONSOLE_E2E_OK
```

Rust Workspace 单元测试、完整 Control Plane/Node/PostgreSQL/Redis 集成测试和真实 Web
Console E2E 均已通过。

## 尚未完成

1. `storage-helper` 仍由 Node Agent 进程内调用；需按同一固定 IPC/独立 UID 模型拆分，
   并验证快照中断、损坏、超时和 Helper Kill。
2. GPU Helper 尚未实现；无 GPU 资源的当前 L1 路径应保持不下发 GPU 权限。
3. Network Helper 尚缺 AppArmor/SELinux/Landlock Profile、独立 Audit Identity 落库、
   Rate Limit 和生产集群中的跨 UID 拒绝实测。
4. 当前只有 Static Proxy；短期凭证引用、连接迁移、DNS/WebRTC/QUIC 防泄漏和真实
   Provider 故障演练仍未完成。
5. Kubernetes 清单已静态验证，但尚未在真实 Kata/CNI 集群执行 Pod Security、
   NetworkPolicy 和 Helper Restart GameDay。

## Gate 判定

大纲中“Network Helper 不与 Node Agent 同进程、固定 Unix Socket Schema、Peer
Credential、能力最小化、崩溃不带走 Node Agent”的应用与部署清单切片已完成。由于
Storage/GPU Helper、LSM Profile 和真实集群跨 UID 证据仍缺失，Phase 5 的完整
“Node Helper 权限拆分”Gate 仍保持未关闭。
