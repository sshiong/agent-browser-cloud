# Phase 4—7：本地最终验收与生产 Gate

> 日期：2026-07-26
> 结论：仓库内 Phase 4 MVP、Phase 5 核心安全链、Phase 6 Stage B/N/N-1 和 Phase 7
> 核心运营能力可重复验收；V16 全量生产 Gate 未关闭。

## 已关闭的本地 Gate

| 阶段 | 已通过 |
| --- | --- |
| Phase 4 | Agent 计划/执行/确认/Handoff、真实 UI、授权公网 URL、Navigate/Read/Type/Scroll、Replay Dataset 和 Validation Evidence |
| Phase 5 | OIDC/RBAC/mTLS、Durable Workflow、防篡改审计、Break-glass、Key Rotation、Runtime Release/签名、Helper 隔离、数据库/对象存储/换主/远程桌面故障演练 |
| Phase 6 | 50k Coordinator Stage A、真实 Chrome 500 次顺序与并发 4 证书、Placement/Cgroup/PSI/Media/Extension、多 Node 路由、Operator N/N-1 Kind 滚动回滚 |
| Phase 7 | Validation/Replay/Compatibility、Cost/SLO/Retention/Compliance/Residency/GameDay/DR Registry、License/Audit Export、Terraform Module、四 SDK、企业 UI |

## 必跑命令

```bash
make ci
make test-integration
make test-e2e
make test-real-url-agent
KIND_BIN=/tmp/agentbrowser-kind-v0.32.0 make test-kubernetes-e2e
```

容量证书因耗时和真实浏览器依赖单独执行：

```bash
REAL_CHROMIUM_PATH="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" \
  make test-browser-density-capacity
```

## 仍打开的生产 Gate

1. 目标 Linux/目标云：Cgroup/PSI、CNI/CSI、KMS/IAM、LSM、Kata、Media/GPU；
2. 真实企业 IdP、HSM/Offline Root、OCI Admission 和证书撤销；
3. Hot Tenant、多 Coordinator/Browser Node 长稳和真实多 Region 故障切换；
4. Business Recovery Validator、流式跨 Region State/Profile；
5. Burn Rate/GameDay/Alertmanager/Pager 与真实发布系统联动；
6. Terraform Provider、正式 SDK Distribution；
7. Owner/RACI、Threat Review、Residual Risk、Staging 和发布审批签字。

因此对外表述必须使用“仓库内/本地验收通过”，不得表述为“V16 已生产就绪”。
