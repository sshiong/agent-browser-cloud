# Phase 7：企业运营核心验收

> 状态：仓库内核心功能和本地验收闭环；真实多 Region、目标云 KMS/HSM、外部发布系统联动
> 与正式 SDK Distribution 仍属于生产 Gate
> 日期：2026-08-09

## 已完成

1. `V019` 建立 Runtime Validation、版本化 Cost Rate、SLO/Event/Error Budget、
   Retention Policy、Region/DR 和 Recovery GameDay 权威数据。
2. Validation Run 绑定 Runtime Build、Suite Version、Environment Digest、Replay
   Dataset 和 Persona；Required Failure 会 Quarantine，Optional Failure 形成 Degraded
   证据，不会冒充通过。
3. Production-like Replay Dataset 明确授权来源、不含生产数据/PII/Credential，
   绑定 Dataset/Environment Digest 和真实 Chrome 版本，执行 Navigate、Read、
   Type、Scroll 以及跨域/非 Allowlist fail-closed。
4. Cost Rate Card 带版本和生效期；Placement 先通过安全/容量/Residency，再使用成本
   分数排序，Session Cost Breakdown 可按版本复算。
5. SLO、Service Event、SLA Exclusion 和 Error Budget 已可查询；外部服务事件可明确
   排除，不会隐式修改 SLA。
6. Retention Policy、Legal Hold 和 Residency Admission 已接入：Legal Hold 阻止删除，
   符合策略的删除生成防篡改 Receipt，错误 Region 在 Placement 前被拒绝。
7. Compliance Snapshot 覆盖八项控制；License Inventory 覆盖 Runtime、四 SDK 和
   Extension；Audit Export 对连续租户审计范围生成 HMAC-SHA256 签名 Manifest。
8. Recovery GameDay 记录目标/实测 RTO、RPO、数据损失和 Evidence Hash；Region 和
   DR Registry 提供 Authority/Failover 元数据。
9. `V020` 增加独立 Media Slot、租户 Media Quota、码率和成本，Extension 持续采样
   使用窗口 P95、最少样本数、分层频率和 CPU Budget。
10. `V021` 增加 SLA Exclusion、删除 Receipt、License Inventory 和签名 Audit
    Export。种子 License Evidence 使用规范事实的真实 SHA-256，不使用占位字符串。
11. Web Console 新增 Enterprise Operations 页面和角色 Gate，展示 Validation、
    Cost、Error Budget、Retention、SLA Exclusion、License、Region、GameDay 和
    Compliance 权威数据。
12. 提供 TypeScript、Python、Go、Java 四个依赖最小 SDK，并提供统一 `make test-sdk`。
13. 提供 AWS Terraform Module，包含网络、KMS、S3、RDS、Redis、EKS、IAM 和安全
    默认值；Terraform 1.9.8 已完成 `fmt -check`、`init -backend=false` 和 `validate`。
14. `V075` 已完成 Error Budget Burn Rate 到仓库内 Runtime Promotion 的自动冻结：
    PostgreSQL 权威状态、上下阈值 Hysteresis、稳定恢复、30 秒评估、申请/审批双 Gate、
    Emergency Disable 旁路、Audit、正式 API/UI 和 N/N-1 均已验收，详见
    [进度 109](109-Error-Budget-Burn-Rate自动发布冻结闭环.md)。

## 验收证据

- `make test-integration`：真实 PostgreSQL/Redis/mTLS 控制链路通过 V019—V021
  Flyway 迁移，并验证 Validation、Cost、SLO、SLA Exclusion、Legal Hold、删除
  Receipt、Residency、License、签名 Audit Export、Compliance 与 GameDay。
- 签名 Audit Export 不只检查长度；集成测试以独立 HMAC 复算并使用 Constant-time
  Compare 验证签名。
- `make test-real-url-agent`：Chrome `150.0.7871.182` 运行授权 Dataset，
  Dataset Digest `ce3d1754...46b28`，Environment Digest
  `823aa505...1c13`，Validation Evidence Hash
  `d6ceedbc...7191c`。
- `make test-sdk`：Python 2 项、TypeScript 2 项加 Build、Go 2 项和 Java Main Test
  全部通过。
- Terraform 1.9.8 Validate 通过；S3 Lifecycle 显式 `filter {}`，消除未来版本告警。
- OpenAPI/Redocly 和 Protobuf Contract Check 均通过。

## 仍未完成

1. Validation Worker 的隔离队列编排、全浏览器版本矩阵与大规模业务页面 Replay；
2. 将已完成的 Runtime Release Freeze Gate 接入目标组织真实外部发布流水线；
3. GameDay Runner 对真实基础设施执行故障注入并联动已完成的冻结 Gate；
4. 真实多 Region 数据/对象复制、流量切换和目标 RTO/RPO；
5. 目标云 KMS/HSM 的 Audit Export 签名、WORM Export Object 和外部审计系统；
6. Terraform Provider；当前交付是 Terraform Module；
7. 四 SDK 的目标包仓发布、签名、版本兼容和弃用策略；自动生成和 GitHub OIDC
   Provenance Release 已完成。

这些剩余项不影响仓库内 Phase 7 核心 API/UI/数据模型验收，但继续阻塞 V16 生产发布。
