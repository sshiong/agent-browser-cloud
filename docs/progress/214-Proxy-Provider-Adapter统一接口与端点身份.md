# Proxy Provider Adapter 统一接口与端点身份

> 日期：2026-09-23
> 范围：V16 `ProxyProviderAdapter` 控制面 SPI、固定商业 HTTP Provider、供应商端点身份和滚动升级

## 本次关闭的缺口

此前商业 HTTP Proxy 已能在隔离 Network Helper 内完成 Basic Auth，但 Control Plane 仍直接从
Provider Catalog 创建 `proxy_allocations`，没有 V16 规定的统一 Adapter 边界；控制面的
`allocation_id` 也被隐式当成供应商端点身份。动态供应商以后若返回独立 Endpoint ID，健康、轮换、
释放和用量查询会缺少可靠关联。

本次增加正式 `ProxyProviderAdapter` SPI，包含：

- `capabilities()`；
- `allocate(request)`；
- `health(endpointId)`；
- `rotate(bindingId, policy)`；
- `release(endpointId)`；
- `usage(endpointId)`。

统一错误码覆盖 V16 要求的 `CAPACITY_EXHAUSTED`、`AUTH_FAILED`、`GEO_UNAVAILABLE`、
`RATE_LIMITED`、`ENDPOINT_UNHEALTHY`、`PROVIDER_OUTAGE`、`ROTATION_UNSUPPORTED` 和
`RELEASE_FAILED`，同时携带是否可重试。Adapter 只接收不透明 `credentialRef`，不接触用户名、
密码或 Token；`allocate/release` 明确要求对 Endpoint ID 幂等。

## 当前实现

`ConfiguredHttpProxyProviderAdapter` 为现有固定 HTTP/HTTPS CONNECT 商业出口提供第一个正式实现：

- 真实声明 HTTP、HTTPS CONNECT、Datacenter、固定 IPv4/IPv6 与粘性能力；
- 不虚构 Country/City/ASN、Rotation、Bandwidth Metering 或 Webhook 能力；
- Region/IP Family 不匹配以 `GEO_UNAVAILABLE` fail-closed；
- 不支持的轮换以 `ROTATION_UNSUPPORTED` 明确拒绝；
- `health()` 固定返回 `UNKNOWN`，要求继续以 Browser Node 主动出口探测作为权威证据；
- `usage()` 明确标记 `metered=false`，不把零字节冒充供应商计量结果。

Session 启动前的实际 Proxy Allocation 以及 Runtime 停止后的 Release 已通过 SPI，不再绕过
Adapter。Browser Runtime 仍只看到 Network Helper 创建的回环 Relay，供应商凭据边界不变。
Release 只有在 Adapter 成功确认后才把权威 Allocation 标记为 `RELEASED`；未知动态 Adapter
类型会返回可重试的 `RELEASE_FAILED` 并保持原状态，避免把供应商端资源泄漏伪装成释放成功。
固定 HTTP Endpoint 没有供应商端 Lease，因此当前 Catalog 已轮换时可从持久化 Allocation
重建同一无状态 Adapter，继续安全、幂等地完成释放。

## V132 供应商端点身份

V132 为 `proxy_allocations` 增加：

- `provider_endpoint_id`：Adapter 返回的供应商端点身份；
- `provider_adapter_type`：创建端点的 Adapter 类型。

二者与控制面 `allocation_id` 分离。为了避免对既有大表做阻塞式全量回填，并保持 N/N−1 滚动升级，
历史记录和旧版本实例在迁移后创建的记录可暂时没有 `provider_endpoint_id`，新版本 Release 会安全回退到
`allocation_id`。该列不会在 Expand 阶段直接改为 `NOT NULL`，避免旧实例写入失败；未来达到
Migration Floor 后再单独收紧。

## 验证

- `ConfiguredHttpProxyProviderAdapterTest`：能力真实性、无凭据泄漏、Region/IP Family Gate、
  规范化错误、权威健康和非计量用量；
- `StaticProxyApplicationServiceTest`：实际分配写入独立 Provider Endpoint ID 和 Adapter 类型；
  未知动态 Adapter 释放 fail-closed，固定 HTTP Provider 从 Catalog 移除后仍可释放；
- Control Plane Java 全量测试通过；
- `make test-upgrade-compatibility` 明确验证 V132 只做 N/N−1 可兼容的 Expand；
- OrbStack 完整 `make test-integration` 验证真实 PostgreSQL、商业 Basic Auth Relay、出口探测、
  分配与释放，并输出 `proxy_provider_adapter=true`。

## 仍未完成

这个切片建立了统一接口并让现有商业 HTTP 数据面通过它执行，但不等于所有商业供应商已经接入。
仍需：

- 支持供应商动态分配/轮换/释放 API 的隔离 Adapter Worker（通用 `REMOTE_HTTP_V1` Gateway
  协议和动态分配/释放运行链随后由 progress 215 完成；具体供应商插件仍未完成）；
- Vault/云 Secret Manager 短期凭据、续租、撤销与 Workload Identity；
- SOCKS5、Residential/ISP/Mobile、Country/City/ASN 选择和供应商 Webhook 主动复核；
- 真实供应商用量/账单对账、限流、错误映射、熔断及客户 SLA Replay；
- 达到 Migration Floor 后收紧 V132 暂时允许的 N−1 空值。

因此本进度关闭“没有统一 Provider Adapter SPI、控制面与供应商端点身份混用”的代码缺口，完整
V16 商业 Provider Adapter 和目标环境验收继续保持未完成。
