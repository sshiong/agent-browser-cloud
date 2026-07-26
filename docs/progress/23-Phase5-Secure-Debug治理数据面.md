# Phase 5：Secure Debug 治理数据面

> 日期：2026-07-26
> 状态：一次性、限时、最小化的 Secure Debug 治理数据面已通过数据库集成与真实 UI
> E2E；独立 Worker 故障域和像素级强制录像仍属于生产增强项。

## 本步完成

| 能力 | 实现 | 验收证据 |
|---|---|---|
| 一次性授权消费 | 一个 Break-glass Request 最多创建一个 Debug Session，数据库唯一约束防重放 | Java 单测 + PostgreSQL 集成 |
| 精确授权 | 只接受 `SESSION + SECURE_DEBUG + ACTIVE`，且只有原申请人可成为 Operator | 自批/审批人启动 409、非 Operator 读取 409 |
| 时限 | Debug Session 不超过 Grant 剩余时间，并额外限制最长 15 分钟 | 服务约束与单测 |
| 最小数据面 | 只返回 Session 状态、Runtime、Context/Browser/Network 版本、URL Origin、State Hash/质量和目标计数 | 契约测试与集成字段反向断言 |
| 敏感字段拒绝 | 不返回完整 URL/Query、标题、DOM、目标名称/坐标、截图、Cookie 或 Profile 内容 | OpenAPI 描述、Java 单测、HTTP 集成 |
| 单 Operator | 调试会话绑定原申请人；其他 Security Admin 的读取/结束尝试被拒绝并记录 | Java 单测 + 集成 409 |
| 逐次访问证据 | START、SNAPSHOT、拒绝、END、自动过期和 Grant 撤销形成独立 Previous Hash 链 | PostgreSQL 顺序/Previous Hash 验证 |
| 防篡改统一审计 | 每次授权检查、启动、读取、拒绝、结束和撤销追加 `ADMIN_ACCESS` | 集成审计链 `chainValid=true` |
| Fail-closed 撤销 | 每次读取重新验证 Break-glass；撤销后下一次读取立即拒绝并封存 Debug Session | 集成 `secure_debug_revocation_closed=true` |
| 自动关闭 | 5 秒扫描 Debug 到期和已撤销 Grant，进入 `EXPIRED/REVOKED` | Scheduler + 数据库状态机 |
| UI 闭环 | 安全中心可启动、读取最小快照、查看 Evidence Head 并结束 | `WEB_CONSOLE_E2E_OK` 与截图验收 |

## 数据边界

`SecureDebugSnapshot` 是固定目的投影，不是通用 State API。允许字段为：

```text
session.state
runtimeBuildId
contextEpoch
browserGeneration
networkRevision
url.origin
stateVersion
targetRevision
stateQuality
stateHash
target.counts
```

URL 使用解析后的 Scheme/Host/Port 重建 Origin；Path、Query、Fragment 和 User Info 不会
进入响应或访问记录。访问记录只保存字段投影名称和证据哈希，不保存 State 内容。

## 验收结果

```text
secure_debug_minimized=true
secure_debug_single_operator=true
secure_debug_cross_tenant=404
secure_debug_evidence_chain=true
secure_debug_revocation_closed=true
audit_chain_valid=true
audit_events=50
WEB_CONSOLE_E2E_OK
```

## 集成测试暴露并修复的问题

首次实现对带应用生成 ID 的新 JPA Entity 忽略了 `save` 返回的托管实例，导致 START 后
事件序号未持久更新。PostgreSQL 的 `(debug_session_id, sequence_no)` 唯一约束在首次快照
时正确拒绝重复序号。实现现始终继续使用 `save` 返回的托管实例，并保留数据库唯一约束
作为并发与重放的最终屏障。

## 尚未完成

1. 当前最小化数据投影在 Control Plane 应用进程内执行；尚未拆为独立 UID、固定 IPC、
   无宿主权限的 Secure Debug Worker。
2. 已有访问操作的不可变证据清单，但尚无远程桌面像素级强制录像、对象存储 WORM
   Retention、签名 Recording Manifest 和访问回放。
3. 尚需在真实 Kubernetes 集群验证 Worker 网络隔离、跨 UID、Worker Kill、录像存储
   超时和 Break-glass 撤销的 GameDay。
4. 法规 Legal Hold、删除 Receipt 和签名审计导出属于 Phase 7 Compliance 主线。

## Gate 判定

Break-glass 不再只是“授权事实”：授权已能消费为实际、最小化、可撤销且有逐次证据的
调试数据面。应用层治理闭环已经完成；在独立 Worker、强制录像/WORM 和真实集群故障
演练完成前，不宣称 Secure Debug 的全部生产隔离 Gate 关闭。
