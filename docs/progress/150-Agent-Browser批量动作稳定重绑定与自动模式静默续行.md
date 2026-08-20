# Agent Browser 批量动作稳定重绑定与自动模式静默续行

> 日期：2026-08-20
> 状态：实现、本地完整验证与远端 Workflow 全部完成。
> 实现提交：`54b28ea fix: rebind autonomous action batches`

## 问题

进度 149 已让 `execute-actions` 在一个持久 Operation 中顺序执行多个动作，并在每一步后
重读真实 Browser State。不过旧批量协议只携带创建计划时的 `targetRef/targetRevision`。
当前一个 FILL/TYPE 动作会推进 Target Revision，后续 CLICK 若继续使用最初的短期引用，
可能被 Node 正确地判定为陈旧。这个失败不是人工信息缺失，不应在 AUTONOMOUS 模式中升级
为人工接管或重复授权。

## 实现

- Control Plane 在创建 Batch Plan 时，从租户和 Session 绑定的权威 Browser State 将每个
  `targetRef` 解析为稳定 `elementId`，并保存到持久 Step Input；找不到时保持 `null`，不伪造
  元素身份。
- `AgentActionPrimitive` 以 additive tag `12` 增加 `element_id`。N−1 Node 会忽略未知字段并
  延续旧围栏；升级后的 Node 在每个 Primitive 执行前，以该稳定 ID 和上一步产生的最新
  `targetRevision` 重新解析目标。
- 没有 `element_id` 的历史 Plan/旧 Control Plane 命令继续使用原始 `target_ref` 和
  `target_revision`，保持 fail-closed，不会被新 Node 放宽。
- 公共 Task View/OpenAPI 返回只读的 `elementId` 绑定证据；TypeScript、Python、Go、Java
  SDK 已同步。它不替代 `stateCursor`、Capability、Reviewer、Operation Epoch、租户/RBAC
  或 Node 可见/可交互性复核。

## AUTONOMOUS 与人工协作语义

本修复进一步固定现有规则：普通输入、点击、滑动、连续动作和有界失败定位由 Agent 自行
执行，状态推进造成的可恢复引用失效不得请求人工授权。只有缺少 Agent 无法自行取得的真人
信息或决定时才写一次人工协助通知，例如 OTP、设备确认、支付或账号安全决策。

OTP 可由操作员通过正式一次性密文 API 发给 Agent 代填，也可由操作员自愿进入 VNC 自行
填写；系统不强制接管。Challenge OCR/视觉动作仍按默认三次、可配置预算自动尝试，预算
真正耗尽后才通知一次。支付与破坏性账号操作仍保留独立高风险确认。

## 验证证据

- Control Plane `AgentApplicationServiceTest` 与 Agent Browser Fast Path 定向测试通过，覆盖
  每个批量动作保存稳定 Element ID，且公共 View 不泄露输入正文。
- Browser Node 新增测试同时覆盖“新协议按最新 Revision 重绑定”和“N−1/历史命令保持原
  围栏”两条路径；Rust 定向测试通过。
- Protobuf lint、OpenAPI lint、四 SDK 生成/验证通过，基线保持 **226 Operations / 301
  Schemas**。
- N/N−1 Gate 已增加 `AgentActionPrimitive.element_id = 12` 和 OpenAPI additive 字段断言，
  定向执行通过。
- `make test` 通过：Control Plane 456 项、Rust Workspace、Web 115 项、Application Adapter
  11 项、Validation Worker 8 项、GameDay Worker 4 项、Agent/Reviewer/Vision Worker 13 项
  及 Go Provider 全部绿色。
- `make lint`、`make build`、`make test-desktop`、`make contracts-check`、四 SDK 验证和
  `make test-upgrade-compatibility` 全部通过。
- `make test-integration` 通过完整 PostgreSQL/mTLS/真实 Chromium 主链，保持 Session、
  Coordinator、State、Agent/Reviewer/Challenge、Identity、Clipboard、Recording、企业运营
  事件流与租户/RBAC 回归全绿。

最终提交 `ec7b61f` 的 GitHub `ci` run `32368996758` 已通过，覆盖 Verify、供应链、
Integration、Object Storage/Recording GameDay 和 Kubernetes Operator E2E；`desktop`
run `32368997078` 的 Windows/macOS 原生安全边界与无签名验证构建均通过。

Dialog/Tab/File、局部 Screenshot、受治理 JS Evaluate 和其余高级键鼠 Primitive 仍按进度
149 的保留边界继续开发，不因本切片通过而提前关闭。
