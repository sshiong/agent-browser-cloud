# Phase 5：Key Rotation 治理

> 状态：双人治理、验证 Gate、证据和审计闭环已完成；外部 KMS/HSM Provider 的自动执行
> 仍属于生产化缺口。

## 已完成

- V015 `key_rotation_requests`：
  - 支持 Node mTLS、Runtime Signing、Profile KEK、Remote Desktop 和 Agent Capability；
  - 支持定期、人员变化、策略变化、疑似泄露和 Tenant 请求触发；
  - `REQUESTED → ROTATING → COMPLETED/REVOKED`；
  - 同一 Key Scope/旧 Key 只允许一个 Active Rotation；
  - 数据库约束禁止申请人自批、禁止新旧 Key ID 相同。
- 双人控制：
  - 只有 `PLATFORM_ADMIN` 可以发起和处理；
  - 本人审批返回 409，并使用独立事务保存拒绝证据；
  - 跨 Control Tenant 返回 404。
- 轮换语义：
  - 普通轮换保留明确的 Dual-read/Single-write 重叠窗口；
  - 重叠窗口结束前不能完成；
  - 疑似泄露自动取消旧验证器重叠窗口；
  - 完成前必须验证新 Key 写入、旧 Key 读取（非泄露场景）、明文链路拒绝和影响工作负载。
- 证据与审计：
  - 审批证据与完成证据分别生成 64 位 SHA-256 Hash；
  - Requested、Self-approval Denied、Approved、Verification Denied、Completed、Revoked
    均写入 `KEY_ROTATION`；
  - 八类 Phase 5 必需审计事件现已全部接入。
- Web Console：
  - 安全中心新增 Key Rotation 工作区；
  - 支持发起、第二管理员批准、撤销和提交验证证据；
  - Loading/Error/Empty、本人等待、进度和证据 Hash 状态均有明确反馈。

## 验证结果

集成测试把已有 Browser Node mTLS 证书重启轮换演练登记为治理证据，并确认：

- `public_tables=21`；
- `node_certificate_rotation=true`；
- `key_rotation_dual_approval=true`；
- `key_rotation_cross_tenant=404`；
- `key_rotation_verification_gate=true`；
- `key_rotation_audit=true`；
- Platform Control Tenant 的链包含五条预期 `KEY_ROTATION` 事件。

真实浏览器 E2E 从安全中心发起 Node mTLS 轮换，验证请求进入 `REQUESTED` 并明确显示
“等待另一位管理员”；页面无 Console Error 或未预期 HTTP Error。

## 尚未完成

1. 当前 Control Plane 管理决策与证据，尚未直接编排云 KMS/HSM、IdP JWKS 或外部 CA。
2. Runtime Signing 仍需要 Offline Root/Online Intermediate/短期 Release Signing 的真实
   密码学验签与泄露阻断演练。
3. Profile KEK 的 Background Rewrap、Dual-read/Single-write 进度需要接入真实对象存储。
4. Production 环境仍需验证所有 Provider 的回滚、限流、超时和权限边界。
