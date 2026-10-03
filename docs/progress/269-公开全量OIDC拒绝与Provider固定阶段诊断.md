# 公开全量 OIDC 拒绝与 Provider 固定阶段诊断

> 日期：2026-10-03
> 基线：`538db27e1ebbc65c8c31bbd0a830731c48236e0d`
> 范围：独立公开 OIDC Replay 的失败定位与敏感信息最小化。

## 本轮真实证据

progress 268 的新 Node 公开 LOGIN 六例通过后，完整十七例回放又在独立
OIDC/PKCE 回执等待失败，Gate exit 1。该轮已走过登录、固定码 OTP、SPA、购物车
与 Duende IdP；OIDC 授权动作本身完成，但测试 Relying Party 在 callback 记录
`oidc_rejected / OIDC_PROVIDER_CALL_FAILED`。最后 State 为 FRESH/COMPLETE、
document complete、STABLE、网络 quiet 30625 毫秒、Target 为零；Node 没有动作失败。

这轮不是 progress 267 的 Selenium 提交文档切换失败，也不能归因为 Target Revision。
原错误只给通用 Provider 类别，没有实际失败阶段或异常类型，不能反推为 TLS、换码、
JWKS、UserInfo、JWT 验证或代码重复兑换的具体问题。当前页空目标不等于 Node 渲染
缺陷或整轮成功；Relying Party 未获得证明，原断言保持拒绝。

## 固定诊断

隔离公开 Relying Party 为 Provider 请求与本地验证包装 Node 外的固定阶段：
DISCOVERY、TOKEN_EXCHANGE、JWKS、USERINFO、PROOF、CODE_REPLAY；失败类型只允许
TIMEOUT、TLS、TRANSPORT、INVALID_RESPONSE、PROCESS、OTHER。日志只从本地创建
的类型化错误取这两个字段，不含原异常、URL、请求/响应正文、授权码、Token、PKCE
verifier、UserInfo 或子进程输出。

旧 Proxy 对任意 `OIDC_` 前缀错误文本的宽泛接受改为精确既有错误码白名单；未知
文本和字段形状固定返回 `OIDC_PROVIDER_CALL_FAILED`。Discovery 的拒绝也记录同一
有限诊断。没有新增网络重试、重新兑换、增加截止时间或降低 TLS/Claims/PKCE 校验。
Callback 仍在第一次外部调用前消费，失败不补造成功 Proof。

## 验证

- 两项新增故障测试的 **五个场景**在旧客户端失败；覆盖四个 Provider 阶段与本地
  验证超时。新实现保留固定阶段/类型、不保存原异常文本或 Token，失败后第二次
  callback 仍被拒绝，不增加 Provider 调用次数。
- 新测试还覆盖未知 `OIDC_` 文本、错误字段形状、嵌套 Timeout/TLS 与 JSON 解析失败；
  完整 Replay Gate **35 tests / OK**，包含原 JWT 签名/issuer/audience/nonce/UserInfo
  攻击矩阵。Python 编译与 diff 检查通过。
- 真实 Chrome IdP/OIDC 定向复验 **2 cases / verified / Gate exit 0**。没有重现原
  Provider 请求失败，不能用这次通过追溯原失败类型或宣称十七例连续全量通过。
- 生产 Node、Control Plane、公开 API、SDK、模型/Proxy Provider 产品协议没有改变；
  本次代码只用于已授权公开测试 Relying Party 和其诊断。

失败证据：`/tmp/ab-login-probe.pu6dhzb7/`；新定向证据：
`/tmp/ab-login-probe.rwmqs8tw/`；私有日志：
`/tmp/agentbrowser-oidc-stage-{old,replay-final,public-idp}.log`。
本机 Docker Gate 前均核对 Running / orbstack / OS=OrbStack。

## CI 与剩余边界

`3a4ac3f` 的 CI `37127317532`、Desktop `37127317510`，以及 `538db27` 的 CI
`37128527293`、Desktop `37128527257` 均已按完整 head SHA 核验 success，含 Operator
与 Windows/macOS。它们不代表本次测试诊断代码的后续 CI。

公开十七例连续稳定性、计划到授权前的旧版本、稳定区域完整风险授权/Event Watermarks/
动作/独立 Outcome、客户实体 Adapter、真实 OTP 交付/企业 IdP/支付/客户 SPA、供应商
计算计费取消、目标环境与权利人许可证决策仍继续保留，整体十一项目标 active。
