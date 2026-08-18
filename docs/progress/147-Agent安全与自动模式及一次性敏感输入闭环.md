# Agent 安全与自动模式及一次性敏感输入闭环

> 日期：2026-08-18
> 状态：仓库实现与本地全量验证完成；远端 Workflow 证据在推送后补记。
> 实现提交：`04568d0 feat: add autonomous agent sensitive inputs`

## 目标与模式语义

本切片关闭“登录、密码或验证码一律强迫人工接管”的产品缺陷。Session 现在具有两个明确
模式：

- `SAFE`：兼容原有行为。敏感输入框、账号/密码/OTP 意图和密文引用继续 fail-closed；
- `AUTONOMOUS`：操作员一次切换模式后，Agent 可经正式 API 创建并消费账号、密码和 OTP
  的一次性密文引用，不再逐动作请求人工确认。人工 VNC 仍是随时可加入的协作能力，不是
  自动任务的必经步骤。

支付确认、转账、购买和修改密码/删除账号等破坏性账号决策仍走独立高风险确认。自动模式
授权的是登录凭据输入，不等于关闭财务、审计、域名、Capability、租户或真人输入优先级
门禁。

## PostgreSQL 与密文边界

- V104 以 expand-only 方式给 Session 增加 `agent_control_mode` 和默认三次、1—10 可调的
  `agent_sensitive_input_max_attempts`；默认 `SAFE` 保持 N−1 客户端和既有 Session 行为。
- `agent_input_secrets` 只保存租户/Session、`USERNAME/PASSWORD/OTP` 用途、AES-256-GCM
  密文、期限、一次性消费状态和 HMAC 请求指纹；不保存明文或可离线枚举 OTP 的裸 SHA。
- 创建 API 要求 `Idempotency-Key`。同键同请求返回同一 `secretId`，同键异请求返回 409；
  Viewer 写入为 403，跨租户不可见。OTP 默认三分钟，账号/密码默认十五分钟，调用方最长
  只能指定三十分钟；消费或到期一小时后清除密文。
- 创建 Agent Task 时只接受 `secretId + CREDENTIAL/OTP`。Control Plane 在同一事务中
  `FOR UPDATE` 一次性消费，解开引用密文后立即以 Tenant/Task/Step AAD 重新封装；事务失败
  会回滚消费。Plan、API、审计、Agent Worker 和 Vision Worker 均看不到明文。
- 敏感 Step 返回的 `payloadHash` 基于随机化密文而非低熵 OTP 明文，避免通过公开任务视图
  枚举验证码。

## 执行、重试和人工协作

- Planner、Prompt Security、Action Tool 与 Browser Node 四层都校验当前 Session 必须仍为
  `AUTONOMOUS`，数据分类必须是 `CREDENTIAL/OTP`，Target Revision、State、域名和一次性
  Capability 必须匹配；中途切回 `SAFE` 会在执行前拒绝旧计划。
- Protobuf 以新 tag 14/15 追加 `allow_sensitive_target/maximum_attempts`。旧 Node 忽略新字段
  并继续拒绝敏感输入；新 Node 收到旧 Control Plane 的零值时按一次普通输入执行，因此
  N/N−1 均 fail-closed 且普通 TypeText 不回归。
- Browser Node 对输入代理的瞬时失败执行 1—10 次有界重试，默认三次。每轮都重新点击、
  `Ctrl+A` 并覆盖字段，不会把密码或 OTP 连续追加；认证服务明确拒绝某个值时不会盲目
  重放，以免触发账号锁定。
- 若 Challenge 后的下一计划 Step 已绑定敏感密文，自动模式直接继续，不进入人工等待。
  视觉 Challenge 沿用 V103 的截图/OCR/点击/连续点击/滑动预算。无可用密文、禁用自动化
  或预算耗尽时，Task 以结构化原因失败并写 `_FAILED` 审计/租户通知，而不是强迫进入人工
  接管；操作员仍可自愿打开协作 VNC。

## API、Web/Tauri 与 SDK

- 新增 `POST /api/v1/sessions/{sessionId}/agent-input-secrets`；明文 `value` 为 write-only，
  返回值只有 `secretId/purpose/expiresAt/consumed`。
- Challenge Policy GET/PUT 以向后兼容的可选请求字段增加 `controlMode` 和敏感输入重试数；
  旧客户端原请求仍有效。
- Session Detail 的共享 Challenge 卡提供安全/自动模式和输入重试次数；Automation 页面在
  `CREDENTIAL/OTP` 分类下只接受 `ais_…` 引用并允许选择敏感文本框，不提供明文密码表单。
  Web 与 Tauri 复用相同组件、API Client 和现有 Session SSE 失效链。
- OpenAPI 与 TypeScript/Python/Go/Java SDK 已重新生成到 **212 Operations / 285 Schemas**。

## 验证证据与未关闭边界

定向测试覆盖 SAFE/AUTONOMOUS 意图差异、敏感 Target 一次性密文消费、API 不回显明文、
默认三次投影、HMAC 指纹和 N/N−1 新字段；Integration 增加默认 SAFE、切换 AUTONOMOUS、
Viewer 403、幂等重放与冲突 409，并通过完整 PostgreSQL/mTLS/Chromium 主链。2026-08-18
本地最终结果：

- `make test`：Control Plane 442 项、Browser Node Rust Workspace、Web 114 项、Application
  Adapter 11 项、Validation Worker 8 项、GameDay Worker 4 项、Agent/Reviewer/Vision Worker
  13 项及 Go Provider 全部通过；
- `make lint`、`make build`、`make test-desktop` 全部通过；
- `make contracts-check`、`make sdk-typescript-check`、`make sdk-multilang-check` 通过，正式
  契约为 212 Operations / 285 Schemas；
- `make test-upgrade-compatibility` 通过，确认 V104 expand-only、SAFE 默认值和 Protobuf
  tag 14/15 additive；
- `make test-integration` 通过，输出 `challenge_visual_automation=true`，并同时保持
  Enterprise Overview SSE、租户隔离、协调器恢复、录制与审计链等既有断言。

远端 GitHub `ci` 与 `desktop` Run ID/结论在最终推送后补记；在两者通过前只称“本地
验证完成”，不称远端发布 Gate 已通过。

本切片不实现从目标企业 IAM/邮箱/短信供应商主动获取凭据或 OTP；它提供受控的一次性
输入 API，真实 Provider 凭据、字段/事务映射和供应商特有认证仍是现有生产集成 Gate。
无语义 OCR 敏感分类、支付自动决策和绕过站点安全策略也仍未开放。
