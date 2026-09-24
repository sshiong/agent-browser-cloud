# Cross-Origin iframe 受信边界 Replay Gate

> 日期：2026-09-24
> 范围：Opaque Frame、Challenge、Application Adapter、真实 Chrome Replay 证据记账

## 审计结论

Browser Node 只公开跨域 iframe 的 Origin、稳定 `frameRef`、Bounds 和新鲜度，不提供内部 DOM
或可执行 Target。现有 Challenge Policy 要求 Session 精确 Origin 与 Task Domain 双重授权，
Vision 仅能返回一次左键，Node 再校验 State/Hash/Tab/frameRef/Bounds。Application Adapter
目前只对外部 Provider 做受限 HTTPS 读、哈希业务证据和 Lease；没有第三方 iframe 动作协议、
Provider 身份到 Frame 的可信绑定或事务级结果回执。通用点击、文本或键盘 Bridge 无法在此基础上
证明动作语义与业务结果，因此本切片维持 Human Handoff/fail-closed 边界。

## 实现

- Replay Dataset 明确声明合成 Opaque Frame case：精确两个 Host、精确 iframe Origin、仅一次
  左键，以及文本、键盘、Secret、支付和账号安全动作禁区。
- `ReplayGate` 校验 Dataset 授权元数据、固定 case 集合与策略，并逐 case 记录真实运行断言通过。
  Runtime Validation 的 `requiredTests` 由已记录证据得出；缺失或重复 case 直接拒绝提交，避免
  仅用 Manifest 长度声称全部 case 已执行。
- 真实 Chrome 矩阵在合成跨域 iframe 上验证 Origin-only Frame 不在可执行 Target 中；现有
  State/Tab/Hash/frameRef/Bounds 围栏、Vision 单击、父页变化和 Task/Run 完成仍须全部通过才
  记录该 case。跨域跳转与未授权计划也必须观察到实际拒绝状态后才记为安全 case 通过。
- 该 Gate 只使用仓库自有合成 Fixture 和公开页面，不装载客户数据或第三方生产凭据；不增加
  API、RPC、数据库状态或跨域执行能力。

## 验证

- `make test-replay-gate`：授权元数据、敏感动作策略、case 缺失/重复的定向测试通过。
- OrbStack 预检为 `Running`、context `orbstack`、`OS=OrbStack`。真实 Chrome 153 的
  `make test-real-url-agent` 复跑通过，Runtime Validation 返回 `PASSED` 与
  `validationEvidenceHash=2efe423390868eb0795bfa683b5d6ad16481ac86501fac126f5ac26f42509730`；
  Dataset digest 为 `sha256:b41c9d2a4c8b6193d7234b586c8143a50e5b771712712f9cf02cafd8088dfccf`。
  首次运行在既有 Opaque Frame 截图链的 `CAPTURING` 阶段未产生 Vision Job，复跑未重现；
  该时序波动仍需后续长稳观察。
- `make ci` 全量通过，包含 Java/Rust/Web、四语言 SDK、OpenAPI/Protobuf、供应链、Operator、
  N/N−1、50k Coordinator Capacity 和新 Replay Gate。完整 OrbStack Integration 通过，输出
  `opaque_frame_challenge_click_capability=true`、`proxy_safe_endpoint_rotation=true`，并完成
  其余 PostgreSQL/Redis/MinIO/mTLS/Chromium 主链。GitHub CI 结果见后续记录。

## 待确认

具体 Provider 名称、其 iframe/后端协议、授权测试 Origin、服务身份与凭据、动作语义和业务
Outcome 回执；还需客户授权的 Replay 数据集及 Canary/回滚阈值。未经这些输入，不开放通用
Cross-Origin Provider Bridge。密码/OTP、支付、账号安全和任意键盘输入继续走既有一次性敏感
输入或 Human Handoff/独立高风险确认，不因 Replay Gate 改变。
