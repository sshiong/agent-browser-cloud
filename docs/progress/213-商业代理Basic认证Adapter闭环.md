# 商业代理 Basic 认证 Adapter 闭环

> 日期：2026-09-23
> 状态：仓库内通用 HTTP CONNECT / Basic Auth Adapter 已闭环；目标供应商账户、短期
> Secret Manager、配额/账单和目标环境长稳仍是部署 Gate

## 问题

此前 `credentialRef` 只用于精确匹配 Provider 身份，Network Helper 并不会解析用户名和密码，
Browser Node 最终也只能得到上游代理地址。需要 Basic Auth 的常见商业 HTTP CONNECT Provider
因此会返回 `407 Proxy Authentication Required`。把账号密码拼进 URL、启动参数、Control Plane
Catalog 或 Browser 命令行都会扩大泄漏面，不能作为修复。

## 实现

- 共享 `PROXY_PROVIDER_CONFIG_FILE` 保持版本 1，仍只包含不透明 `credentialRef`；控制面、数据库、
  API、审计和 N−1 Helper 不接触凭据文件路径或正文。
- 只有隔离 Network Helper 可读取 `PROXY_CREDENTIAL_CONFIG_FILE`。它用
  `providerId + credentialRef` 精确映射私有凭据文件，拒绝相对路径、重复键、未知 Provider、
  符号链接、空文件、超过上限或 other-readable 文件。
- 单个凭据文件是严格 JSON：`{"username":"…","password":"…"}`。用户名、密码有长度和控制字符
  限制，用户名不得包含冒号；解析缓冲、Basic 明文和运行时字段在释放时清零。
- Provider 出口探测由 Network Helper 自己携带 Basic Auth。Browser 只收到按需创建的
  `127.0.0.1` 回环 Relay；Relay 拒绝 Browser 自带的 `Proxy-Authorization`，再向上游注入权威
  Header，因此浏览器进程、CDP、Node IPC 和命令行都看不到商业账号密码。
- Relay 对普通 HTTP 请求和 HTTPS `CONNECT` 使用同一有界 Header 解析、5 秒连接/首包超时和
  64 KiB Header 上限；连接失败时 fail-closed，不回退直连。
- Kubernetes Browser Node 增加可选 `browser-node-proxy-provider-credentials` Secret 挂载；Secret
  不存在时环境变量不注入，既有无认证 Provider 保持兼容。

## 配置

Provider Catalog 仍使用原有格式：

```json
{
  "version": 1,
  "providers": [{
    "providerId": "commercial-a",
    "endpoint": "http://proxy.vendor.example:8080",
    "expectedExitIp": "203.0.113.10",
    "credentialRef": "vault://tenant-a/proxy/commercial-a"
  }]
}
```

Network Helper 专属映射：

```json
{
  "version": 1,
  "credentials": [{
    "providerId": "commercial-a",
    "credentialRef": "vault://tenant-a/proxy/commercial-a",
    "credentialFile": "/run/browsercloud/proxy-credentials/commercial-a.json"
  }]
}
```

启动 Network Helper 时设置：

```bash
PROXY_PROVIDER_CONFIG_FILE=/run/browsercloud/proxy-providers/providers.json
PROXY_CREDENTIAL_CONFIG_FILE=/run/browsercloud/proxy-credentials/config.json
```

Kubernetes Secret 需要包含：

- `config-file`：值为 `/var/run/browsercloud/proxy-credentials/config.json`；
- `config.json`：上述映射文档；
- 映射引用的一个或多个凭据 JSON 文件。

Secret 卷以 `0440` 挂载并由 Network Helper 的受控组读取。仓库不得提交真实账号、密码或
Provider Token。

## 验证

- `cargo test --locked --manifest-path apps/browser-node/Cargo.toml -p network-helper`：10 项通过；
- `cargo clippy --locked --manifest-path apps/browser-node/Cargo.toml -p network-helper --all-targets -- -D warnings`；
- Python fixture 编译与 Integration shell 语法检查；
- OrbStack 完整 `make test-integration` 通过。真实 TCP Fixture 对所有请求强制 Basic Auth，出口
  探测、活动/冷健康、Helper 崩溃重启和浏览器链路均通过，最终输出
  `proxy_commercial_basic_auth=true`、`proxy_direct_fallback=false` 和
  `network_helper_restart_recovered=true`。

## 剩余边界

- Vault/AWS Secrets Manager/Azure Key Vault/GCP Secret Manager 的短期动态凭据解析、续租、
  轮换和撤销；
- 供应商特有的 OAuth、签名 Header 或 HTTPS-to-proxy 传输；当前通用 Adapter 支持 HTTP
  Proxy Endpoint 上的 Basic Auth，目标站点的 HTTPS 仍通过标准 CONNECT 隧道；
- 真实 Bright Data、Oxylabs、Decodo 等账户 SLA、限流、DNS、区域、账单对账和故障长稳；
- 目标 Kubernetes CNI 防逃逸、Secret CSI/Workload Identity 与组织准入。

因此本闭环关闭“仓库内没有带认证商业代理数据面”的代码缺口，不等同于任一商业供应商或目标
云生产验收。
