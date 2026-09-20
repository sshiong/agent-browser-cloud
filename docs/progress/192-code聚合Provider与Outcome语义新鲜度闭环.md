# `code` 聚合 Provider 与 Outcome 语义新鲜度闭环

> 日期：2026-09-20
> 状态：真实聚合 Provider 登录矩阵已通过；生产 HTTPS Provider Gate 仍需部署环境完成

## 背景

运营方提供的是 OpenAI Responses 兼容的模型聚合入口，请求使用稳定路由别名 `code`，但响应可能由
GPT、GLM、MiniMax 等不同后端完成，因此响应中的实际模型名不保证等于请求别名。首次真实调用还证明，
部分兼容后端不会完整实现原生 `text.format=json_schema`，并可能把推理 token 计入输出预算。真实模型
延迟期间 Browser State 的采样游标仍会前进，旧的精确 State Version/Hash 比较会把语义不变的页面误判
为证据变化。

## 实现

1. Reviewer、Outcome Verifier 与 Vision Worker 仍只发送标准 Responses `model`、`input` 和
   `max_output_tokens`；移除非必要的 `temperature` 与 Provider 原生 JSON Schema 依赖。Prompt 要求最小
   JSON 对象，Worker 在本地严格校验字段集合、枚举、数量、坐标、置信度和动作预算，格式不符时
   fail-closed。
2. 请求模型名与响应模型身份分离：`LOCAL_AGENT_MODEL_NAME=code` 只表示聚合路由；默认接受合法的动态
   响应模型 ID。只有运营方显式配置 `LOCAL_AGENT_MODEL_RESPONSE_NAME` 时才强制响应模型完全匹配。
3. 三个模型 Worker 统一支持 1—300 秒超时和 64—4096 输出 token 预算；默认仍为 120 秒/512 token，
   Compose 预检拒绝越界配置。
4. V124 以可空 additive 列为 Outcome Job 保存最小化语义证据哈希；N−1 Writer 产生的空值继续走旧的
   精确 State/Input 围栏，避免滚动升级期间破坏旧实例。新模型返回时仍要求当前 Browser State 为新鲜、稳定、
   COMPLETE/DEPTH_LIMITED，但排除 State Version、采样时间、Network Quiet 计数等非语义游标；URL、标题、
   Target、Expected Outcome、执行证据或数据策略变化仍会拒绝旧结果。
5. 登录矩阵把意图写成可审核的业务目标：成功登录、预期的错误密码拒绝和错误密码却声称成功分别验证。
   Secret 输入步骤只验证页面仍可继续，密码正文继续不进入模型证据。

## 真实验证

本机 Provider 只提供 LAN HTTP。为不降低仓库默认 HTTPS fail-closed 约束，测试使用临时 localhost TLS
Relay 和临时 CA 转发到该入口；Key 以 0600 文件放在仓库外，未写入环境变量、日志或 Git。

| Gate | 结果 |
| --- | --- |
| Provider `/v1/models` 与最小 `/v1/responses` | 通过；`code` 路由可用，响应后端模型可动态变化 |
| `make test-real-login-agent-provider` | 通过；真实 Chrome 的成功登录、错误密码语义、假成功拒绝全部符合预期 |
| 三次最终 Outcome 调用 | 约 4.9s/4.3s/6.8s；均有真实 input/output token、request ID 与证据哈希 |
| `make test-real-login-agent` | 通过；fixture 模式保持 3 Session/3 Profile 与 12 次最小化请求 |
| `make test-agent-worker` | 36 项通过；含 Vision 动态后端模型与最小请求回归 |
| `make test-default-compose` | 4 项通过 |
| Control Plane 全量测试 | 通过；V124 在真实 PostgreSQL 登录 Gate 中完成迁移 |
| `make test-upgrade-compatibility` | 通过；V124 nullable N−1 Writer 与旧精确围栏被显式检查 |
| `make ci` | 通过；Java、Rust/Clippy、Web 143 项、Worker、契约/SDK、供应链、Operator 与容量 Gate |

## 边界

- 用户当前入口是明文 LAN HTTP，只适合本次受控本机验证；默认 Compose 和生产部署仍必须使用受信 HTTPS
  Provider 或正式受控网关。
- `modelRevision` 是运营方声明的部署/策略修订，不冒充聚合器实际选择的后端模型版本。动态响应模型只作
  合法身份与审计元数据；如需严格模型准入，应由网关固定路由并显式配置响应模型。
- 本切片不代表真实企业 IdP、客户站点、目标云模型准入或长期稳定性 Gate 已完成。
