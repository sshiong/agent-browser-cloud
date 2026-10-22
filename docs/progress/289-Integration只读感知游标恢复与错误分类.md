# Integration 只读感知游标恢复与错误分类

> 日期：2026-10-23
> 基线：859cf9c8d2e575b06e4e504dd5ab2d768d98ef4c
> 范围：完整集成测试的只读感知契约；不修改生产动作或游标围栏。

## 真实失败与证据限制

CI **39819660187** 的 Verify（含修复后的 Recorder）、扫描、PostgreSQL Gate 和
Operator 通过；Desktop **39819660333** 的 Windows/macOS 均成功。但 Integration
在 `snapshot → inspect` 的 inspect 返回 HTTP 409。旧 `curl -f` 丢弃错误正文，
下游 JSON 解析因此失败，行号 4360；Object Storage GameDay 被跳过。

生产感知服务在游标不一致时返回 `STATE_CURSOR_STALE`，但本次旧日志没有保留
具体响应正文，不能追溯认定这一次 409 的确切业务原因，更不能称为 Legal Hold
或 Recorder 生产缺陷。

## 修改

- 只读观察对改为同一个 Python 进程执行，减少 Shell/子进程拆分带来的取样间隔。
- 只接受 HTTP 409、`AGENT_BROWSER_PERCEPTION_REJECTED`、
  `details.reason=STATE_CURSOR_STALE` 三项精确匹配，才获取新 Snapshot 后再次
  inspect，最多三次。Element ID、Role、Active Tab 必须保持；变化则失败。
- 未知 409、其他状态、传输失败、无效/超大响应、结构化字段错误直接失败；不以
  返回成功的 HTTP 状态代替原有 target/boolean 断言，并校验响应游标。
- 只允许自有 loopback Session 的 `/snapshot` 与 `/inspect`，不跟随重定向、不用
  环境代理。诊断仅输出固定类别、HTTP 状态和次数，不输出响应正文、URL 或身份。
- 生产 `requireCursor`、状态 freshness 和权限保持。CLICK/TYPE/支付/任何动作
  均不经过此只读测试恢复器，不重放已经派发的输入。

## 验证

八项真实 loopback HTTP 回归通过：旧游标后重采成功、未知 409 不重试、503 即使
含旧游标原因也不重试、断线不重试、三次上限、身份变化拒绝、字段错误拒绝和
越界 URL 在请求前拒绝。测试验证请求路径只有 snapshot/inspect。

完整 Replay Gate **51 项通过**，包含上述八项；OrbStack Integration 和本次精确
提交 CI 须分别记录结果。本机完整 OrbStack Integration 随后 exit 0，审计链有效；
本轮观察对没有触发重采，旧游标分支由定向 HTTP 回归证明。历史失败
流程未直接 rerun；本次源码改动先验证只读恢复契约，再执行完整 Gate。

私有证据：`/tmp/agentbrowser-hold-fixed-ci-final.json`、
`/tmp/agentbrowser-hold-fixed-ci-failure.log`、
`/tmp/agentbrowser-perception-integration.log`、`/tmp/agentbrowser-perception-replay.log`。

云端 Legal Hold 队列/RPC/API/UI/删除联锁/未知结果恢复、真实客户外部系统、目标云/
Linux、供应商回执、组织与许可证 Gate 仍未关闭。原十一项总目标保持 active。
