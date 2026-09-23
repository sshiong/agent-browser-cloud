# 动态 Proxy Provider 远程 Adapter 协议闭环

> 日期：2026-09-24
> 范围：V16 `REMOTE_HTTP_V1` 控制面 Adapter、动态 Endpoint 生命周期、服务身份与真实 Integration

## 关闭的缺口

progress 214 建立了统一六操作 SPI，但只有固定 HTTP Endpoint 实现；控制面仍把新 Allocation 的
Adapter 类型硬编码为 `CONFIGURED_HTTP`，也没有真正调用隔离供应商网关的协议。即使编写了供应商
插件，也无法把它返回的 Endpoint ID、出口地址和释放结果纳入权威 Session 生命周期。

本次增加 `REMOTE_HTTP_V1`：

- `capabilities / allocate / health / rotate / release / usage` 六操作通过隔离 HTTP Adapter Gateway；
- `allocate` 使用控制面 Allocation ID 作为 `Idempotency-Key`，`rotate` 使用 Binding ID，`release`
  使用供应商 Endpoint ID，供应商重试不会重复租用或释放错误资源；
- Control Plane 只发送不透明 `credentialRef`。供应商 OAuth、请求签名和 Secret Manager 解引用留在
  隔离 Gateway，Browser Runtime、Node IPC、数据库和普通审计不接触供应商 Secret 正文；
- 服务身份从绝对路径私有文件读取，拒绝符号链接、超大/多行或不安全权限文件；生产环境只接受
  HTTPS，local/test 仅额外允许 loopback HTTP；Java Client 固定不跟随重定向；
- 响应限制为 256 KiB 且必须为 JSON Object。Provider ID、Credential Reference、Endpoint URL、
  Endpoint Host 精确 Allowlist、Endpoint ID、IP Literal、枚举、计量值和时间戳全部在进入权威状态前
  重新校验，Gateway 不能把携带供应商凭据的 Network Helper 引向任意内网目标；
- 供应商错误映射到既有八类规范化错误，响应正文和供应商诊断不会进入异常文本；
- Adapter 成功返回的 Endpoint、Endpoint ID 与 expected exit IP 成为 Allocation 权威快照，Node
  仍通过真实出口探测验证，Catalog 中的预配置地址不再覆盖动态结果；
- Release 只有收到 Gateway 成功确认后才提交 `RELEASED`，Gateway 缺失或失败继续 fail-closed。

## 配置与滚动升级

Control Plane Provider Catalog v2 对动态 Provider 增加：

```json
{
  "version": 2,
  "providers": [{
    "providerId": "provider-a",
    "endpoint": "http://reserved-endpoint.internal:8080",
    "expectedExitIp": "203.0.113.10",
    "credentialRef": "vault://tenant/proxy/provider-a",
    "adapterType": "REMOTE_HTTP_V1",
    "adapterBaseUrl": "https://proxy-adapter.internal:8443",
    "adapterServiceTokenFile": "/var/run/browsercloud/proxy-adapter/token",
    "adapterAllowedEndpointHosts": ["egress.provider-a.example"]
  }]
}
```

Network Helper 继续读取数据面最小 v1 Catalog；Kubernetes 中 Control Plane 与 Browser Node 原本就
使用不同 Secret，可在相同挂载路径分别提供 v2 控制面文档和 v1 数据面文档。不得把
`adapterServiceTokenFile`、Endpoint Host Allowlist 或供应商控制面字段复制进 Network Helper Catalog。

V133 只扩展既有 `provider_adapter_type` Check Constraint，允许 `REMOTE_HTTP_V1`；没有回填热表、
修改列语义或收紧 V132 为 N−1 保留的空值，因此旧实例仍可滚动读取和写入
`CONFIGURED_HTTP`。

## 验证

- `RemoteHttpProxyProviderAdapterTest`：真实 loopback HTTP Server 覆盖六操作、Authorization、
  幂等键、能力/健康/计量解析、错误归一化、身份错配拒绝和生产 HTTP fail-closed；
- `StaticProxyApplicationServiceTest`：Provider Catalog v2 真实请求远程 Adapter，持久化
  `REMOTE_HTTP_V1`、供应商 Endpoint ID/Endpoint/出口 IP，并经同一 Adapter 完成释放；
- Control Plane Java 全量测试通过；
- `make test-upgrade-compatibility` 验证 V133 的 N/N−1 兼容约束；
- OrbStack 完整 `make test-integration` 使用真实 PostgreSQL、多个 Control Plane 重启、隔离
  Network Helper、带 Basic Auth 的上游 Proxy 和真实 `REMOTE_HTTP_V1` Fixture，验证动态分配、
  重绑释放、服务身份、幂等键与 Credential Reference-only 请求，输出
  `proxy_remote_provider_adapter=true`。

## 仍未完成

这个切片关闭通用动态供应商控制面协议和仓库级运行链，不冒充任何具体商业供应商已经生产准入。
仍需目标供应商插件、真实账户 OAuth/签名认证、云 Secret Manager/Workload Identity、供应商限流与
故障 Replay、真实用量/账单对账、Webhook 提示后的主动复核，以及 Provider/Region/Product 维度的
生产熔断和客户 SLA 验收。
