# Local Compose 通用 HTTP(S) 模型 Provider 接入

> 日期：2026-09-20
> 状态：已实现并通过完整 CI 与定向回归

## 需求与边界

Local Compose 需要用 Base URL、Key、Model 三项直接连接 OpenAI 官方、第三方服务或本机/LAN 聚合
接口。Base URL 填到 `/v1`，由 Worker 自动补充 `/responses`。模型入口可能使用 HTTPS，也可能在可信
开发网络使用 HTTP；API Key 可能属于任意供应商，不应依赖 `sk-` 等前缀识别。

本能力只放宽 `local/test` 模型调用边界。非 local/test Worker 继续要求 HTTPS，并要求模型 Host 位于
显式 Allowlist；Personal Secure 和生产部署的传输安全边界不变。HTTP 会以明文传输 Authorization Key
和请求内容，操作员必须只在可信主机或网络使用。

## 实现

- Reviewer、Outcome Verifier 与 Vision Worker 的 local/test 模式接受任意域名或 IP 的固定
  `http(s)://.../v1` Base URL，统一规范化为 `/v1/responses`；旧完整 URL 继续兼容。
- 默认 Compose 预检同步接受 HTTP(S)，仍拒绝错误 Scheme、错误 Path、缺失配置、符号链接、空 Key
  文件和非 `0600/0400` 权限。
- 用户可直接填写 `LOCAL_AGENT_MODEL_API_KEY`；预检将其写入 Git 忽略、`0600` 的本地 Secret 文件，
  Worker 只挂载文件。原 `LOCAL_AGENT_MODEL_API_KEY_FILE` 方式继续兼容。Key 不校验供应商前缀，也不
  进入 Worker 容器环境变量。
- `LOCAL_AGENT_MODEL_REVISION` 改为可选并默认 `local-v1`，因此必填项只有 Base URL、Key、Model。
- `LOCAL_AGENT_MODEL_NAME` 直接作为 Responses `model` 发送；聚合别名可返回动态后端模型，只有显式
  配置 `LOCAL_AGENT_MODEL_RESPONSE_NAME` 时才锁定响应模型身份。
- 真实登录 Provider Gate 同步接受 HTTP(S)，无需为了可信 LAN 开发入口强制增加 TLS Relay。

## 验证

| Gate | 结果 |
| --- | --- |
| Agent Worker 单元测试 | 37 项通过；覆盖 HTTP IP、HTTP 域名、OpenAI HTTPS、第三方 HTTPS 与生产 HTTP 拒绝 |
| 默认 Compose 测试 | 5 项通过；直填 Key、Revision 缺省、HTTP/HTTPS 通过，错误 Scheme、Key 权限和输出预算拒绝 |
| `make docs-check` | 7 项工具测试与双语 README 链接/模块表检查通过 |
| `make ci` | 通过；Java、Rust/Clippy、Web 143 项、Worker、契约/SDK、供应链、Operator、N−1 与容量 Gate |

## 不变项

本改动不把 Local Compose 变成公网部署模板，不降低生产 Worker、Personal Secure、OIDC、mTLS、Profile
加密或浏览器出口策略，也不宣称任意第三方 Provider 已获得生产数据准入。
