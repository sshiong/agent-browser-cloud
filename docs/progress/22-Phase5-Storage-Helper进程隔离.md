# Phase 5：Storage Helper 进程隔离

> 日期：2026-07-26
> 状态：Storage Helper 进程级拆分、Checkpoint 故障恢复与 Profile 独立卷已通过验收；
> 完整 Node Helper 生产 Gate 仍需 LSM 和真实集群证据。

## 本步完成

| 能力 | 实现 | 验收证据 |
|---|---|---|
| 独立进程 | `storage-helper` 成为独立二进制；Node Agent 不再链接 `storage-helper` | Cargo 依赖检查与全 Workspace 构建 |
| 固定 IPC | 版本化、64 KiB 上限、请求 ID、超时和固定 `PING/ACQUIRE/CHECKPOINT/RELEASE` | 协议 Round-trip、超长帧、超时和关联测试 |
| 路径边界 | Node 按 Tenant/Profile/Session 重算允许路径，拒绝 Helper 返回根目录外路径 | Helper Client 路径逃逸单测 |
| Peer 身份 | Unix Kernel Peer Credential 只接受配置的 Node Agent UID | macOS/Linux 实现与错误 UID 拒绝单测 |
| 单 Writer | Writer Lock、Write Epoch 与同 Session Helper 重启恢复保持不变 | 既有单 Writer测试 + Helper Restart 测试 |
| 幂等 Checkpoint | 同一 `profile_write_epoch` 和 Runtime Build 重试返回同一已验证 Checkpoint，不重复增加 Epoch | Checkpoint 重试单测；集成最终 Epoch 仍为 2 |
| 完整性 | Manifest、Commit Marker、文件数量/大小/SHA-256、Symlink 拒绝保持在 Helper 内 | Profile 完整性与损坏恢复测试 |
| 故障隔离 | Checkpoint 前 Kill Helper；Runtime 安全停止、Node Agent 存活、命令失败可重试；Helper 重启后终止提交成功 | `make test-integration` 四项 Storage Helper 证据 |
| 最小卷权限 | Profile 使用独立 PVC/Compose Volume；Storage Helper 不挂载 Node Journal/Runtime 卷 | Kustomize/Compose 配置验证 |
| 最小进程权限 | Kubernetes 使用 UID `11003`、共享受控 Group、只读根、RuntimeDefault seccomp、Drop ALL | Kustomize 渲染验证 |
| UI 回归 | Profile 恢复、Session、Agent 和远程桌面真实链路未退化 | `WEB_CONSOLE_E2E_OK` |

## 关键不变量

- IPC 不传输 Cookie、Profile 文件内容或任意宿主路径，只返回受校验的 Workspace 句柄和
  Checkpoint 摘要。
- Node/Chromium 使用 `0007` umask，Storage Helper 显式创建 `0770/0660` 目录和文件；
  共享 Group 仅存在于挂载了 Profile 卷的 Node/Storage 容器。
- Network Helper 虽使用相同 IPC Group，但不挂载 Profile 卷；Storage Helper 不挂载
  Network Secret、mTLS Secret 或 Node Journal 卷。
- Profile 操作使用固定 64 条 Hash Stripe 串行化同一 Profile，防止并发 Checkpoint
  破坏 Epoch，同时避免攻击者制造无界 Lock Map。
- Checkpoint Response 丢失后的重试会重新校验 Marker、Manifest 和所有文件 Hash，再返回
  原 Checkpoint。

## 验收结果

```text
profile_checkpoint_epoch=2
profile_restore_starts=4
storage_helper_process_isolated=true
storage_helper_checkpoint_failure_closed=true
storage_helper_restart_recovered=true
storage_checkpoint_idempotent=true
WEB_CONSOLE_E2E_OK
```

## 尚未完成

1. GPU Helper 尚未实现；当前 L1 无 GPU 路径保持不授予 GPU Device 权限。
2. Storage/Network Helper 尚缺 AppArmor/SELinux/Landlock Profile 和生产集群跨 UID
   GameDay 证据。
3. Object Storage/Warm Tier Provider 仍为本地/CSI PoC，尚缺真实对象存储超时、限流、
   Partial Upload、跨 Region Restore 与删除 Receipt。
4. Profile Business Ready 验证器、敏感字段分类、加密 Key 生命周期和法规删除流程仍未完成。
5. Helper Audit Identity 当前仅有结构化进程日志，尚未进入统一防篡改审计链。

## Gate 判定

Network 与 Storage 两个当前实际使用的 Helper 均已完成独立进程、固定 IPC、Peer UID、
最小容器权限和崩溃隔离。Phase 5 的 Helper 应用层切片已显著收敛，但在 GPU、LSM、
统一审计身份和真实集群验收完成前，不宣称整个生产 Gate 关闭。
