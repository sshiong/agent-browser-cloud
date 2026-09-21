# Opaque Frame 显式授权单击自动化闭环

> 日期：2026-09-21
> 范围：跨域托管 Challenge、精确 Origin 策略、受治理截图、Vision 单击、任务续行

## 问题

跨域、Sandbox 或不可读取 iframe 已能以 Opaque Frame 安全观察，但过去所有交互都固定转人工。
这对登录和支付输入是正确边界，却也使只要求点击一次的低风险托管验证无法自动完成。另一个真实
运行缺口是 `StateDiff` 虽能更新数据库状态，却没有进入与完整 `StateUpdated` 相同的 Challenge
检测链；截图 Evidence ID 也与 Browser Node/V110 的 32 位标识契约不一致。

## 实现

- Session Challenge Policy 增加显式 `opaqueFrameClickEnabled` 与精确 HTTP(S) Origin
  Allowlist。当前 Agent Task 的 `allowedDomains` 还必须包含 frame host，两层权限缺一不可。
- 只允许 `OPAQUE_FRAME_SINGLE_CLICK`：Vision Worker 最多返回一个左键 `CLICK`，不得携带文本、
  Secret、键盘、滑动、连续点击或任意 CDP。支付、转账、购买、账号安全决策和跨域输入继续
  Human Handoff。
- Control Plane 在截图前以 State/Hash/Version/Target Revision/Active Tab/frameRef/Bounds/
  Origin/Visual Anchor 形成精确围栏；Browser Node 立即重采真实 State，再次校验同一 frameRef 与
  Bounds 后才截取区域或执行边界内单击。
- Browser Node 通过 `opaqueFrameChallengeClick=state-fenced-click-v1` 显式声明能力；旧 Node、
  缺能力 Node 和 N-1 命令均 fail-closed。
- `StateDiff` 成功提交后会返回精确的新权威 State，并进入与完整状态相同的 Challenge 检测、任务
  暂停和自动化调度链。成功动作按持久 Run 的 Task identity 续行，避免采样游标前进导致事件重绑
  后原 Task 永久等待。
- Challenge Screenshot Evidence ID 统一为 `evd_` 加 32 位小写十六进制，与 V110 和 Node 的
  create-only 像素对象契约一致。
- V125 保存策略和新 Challenge 类型；OpenAPI、Protobuf、四语言 SDK、Web/Tauri 共用策略编辑器、
  Integration 和 N/N-1 Gate 已同步。

## 验证

- Chrome 153 真实 Gate 在父页异步加载另一 Origin 的 iframe；Node 投影稳定 `frameRef`，任务因
  Challenge 暂停，真实受治理截图进入 Vision Job，受控 Worker 返回一次区域内点击，iframe 通过
  `postMessage` 使父页标题变为 `Opaque challenge passed`，Run 与原 Task 均完成。
- 真实 Gate 同时验证精确 Origin、Task Domain、State/Tab/Hash/frameRef/Bounds 围栏，以及普通
  跨域点击和非 Allowlist 计划继续 fail-closed。
- Control Plane 定向测试覆盖策略、检测、StateDiff 权威处理、Evidence 命令与 32 位 Evidence ID；
  Browser Node 定向测试覆盖新能力、精确截图边界及边界外点击拒绝。
- OrbStack 完整 PostgreSQL/Redis/MinIO/mTLS/Chromium Integration 通过并输出
  `opaque_frame_challenge_click_capability=true`；`make ci` 全量通过 Java、Rust、Web 143 项、
  Worker、Compose/Personal Secure、OpenAPI、四语言 SDK 漂移、供应链、Operator、N/N-1、容量与
  SDK 发布检查。

## 边界

该闭环只提高显式授权、低风险、一次左键托管 Challenge 的覆盖率，不把 Opaque Frame 内部 DOM、
URL Path/Query 或像素暴露给 Agent。第三方 IdP/支付 iframe 的文本、密码、OTP、付款和账号决策仍
必须走站点专用受信 Adapter、一次性敏感输入链或 Human Handoff；真实客户授权 Replay、目标模型
准入与组织审批仍是生产 Gate。
