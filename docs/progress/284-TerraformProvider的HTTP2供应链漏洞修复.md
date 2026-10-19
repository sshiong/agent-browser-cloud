# Terraform Provider 的 HTTP/2 供应链漏洞修复

> 日期：2026-10-19
> 基线：68d526b830aad25caa581282ba1351086b3290cc
> 范围：原十一项中的生产供应链 Gate；恢复被真实扫描阻断的主 CI。

## 实际阻断

68d526b 的主 CI **38113596008** 最终 failure：Verify/Build 已完成，在
`Scan source and dependencies` 发现 HIGH **CVE-2026-78669**，Provider 间接依赖
`golang.org/x/net v0.58.0`，扫描器标记修复版本 **v0.60.0**。后续 Integration 和
Object Storage GameDay 没有执行，不能把本机通过写成该提交 CI 成功。
同提交 Operator E2E 与 Desktop **38113596120** 的 Windows/macOS 均成功。

[Go 官方修复提交](https://github.com/golang/net/commit/cc7d34fd2dd5864bcafe3e5e0a84919240be98c0)
确认该漏洞涉及 HTTP/2 初始流控窗口反复调整产生的过量 CPU 工作。
[v0.60.0 的官方 go.mod](https://github.com/golang/net/blob/v0.60.0/go.mod) 要求 Go 1.26。
[Go 官方发行列表](https://go.dev/dl/) 本次核对的受支持稳定版本为 1.27.2、1.26.9。

## 修复

- Provider 的 x/net 升到 **v0.60.0**；按其依赖图同步 x/sys **v0.48.0**、
  x/text **v0.42.0**，不扩大到无关依赖的 latest 升级。
- Provider 的最低 Go 版本设为 **1.26.9**，同时满足 x/net 的新要求及已修补标准库
  的构建要求。主 CI 和 Provider 发布流程同步使用 **1.26.x**，README 同步说明。
- 使用 Go 工具链执行 `go mod tidy`，清理旧版本校验条目；`go mod verify` 通过。
  没有手工编造依赖 Hash、忽略 CVE、降低 HIGH/CRITICAL 门槛或跳过扫描。
- 正式 API、Provider 的资源/协议、版本标签和签名发布流程保持原契约。

## 验证

- Go 1.26.9 Provider `go test -race -json ./...`：**12 passed / 0 failed**。
- 同工具链 Go SDK：**4 passed / 0 failed**；Provider `go vet ./...` 通过。
- `CGO_ENABLED=0`、`-trimpath` 六个构建组合通过：darwin/linux/windows × amd64/arm64。
  构建写入本轮私有临时目录，没有创建标签、签名或发布外部 Release。
- 两项现有供应链发布检查通过；本机 Trivy 与新提交 CI 结果待收尾。

私有证据：`/tmp/agentbrowser-http2-dependency-upgrade.log`、
`/tmp/agentbrowser-http2-tidy.log`、`/tmp/agentbrowser-http2-mod-verify.log`、
`/tmp/agentbrowser-http2-provider-tests.jsonl`、`/tmp/agentbrowser-http2-sdk-tests.jsonl`、
`/tmp/agentbrowser-http2-vet.log`、`/tmp/agentbrowser-http2-cross-build.log`。
各进程退出码与同前缀 `.exit` 对齐，不向文档复制原始 API 响应或凭据。

## 剩余范围

此处关闭一个实际供应链阻断，不等于目标云/HSM/多 Region、真实 OTP/MFA/支付、客户
SPA/视觉 Dataset 或完整区域产品化通过。原十一项中的外部环境、供应商协议/取消
回执、组织签字和权利人许可证决定仍需独立证据；总目标保持 active。
