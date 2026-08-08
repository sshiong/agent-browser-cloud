# Proxy 多 Provider 质量、成本与地域路由闭环

> 日期：2026-08-08
> 数据库版本：V073
> 状态：仓库内代码、契约、测试、真实 PostgreSQL 集成与浏览器验收完成

## 本轮目标

关闭进度 99、100 留下的“已有真实健康与质量数据，但 Session 仍不能在多个 Proxy
Provider 间自动选择”的代码缺口。生产路径继续以 PostgreSQL、正式 API、真实探测结果
和不可变 Assignment 为权威，不允许前端评分、Mock 路由或失败后直连。

## 已完成

1. `V073__proxy_provider_routing.sql` 为 Binding 固化 Provider 的每 GiB 成本、信誉分和
   最大并发容量，并为 Session Assignment 持久化选择模式、各评分因子和完整候选证据；
   迁移采用增量列、`NOT VALID → VALIDATE` 约束并进入 N/N−1 Gate。
2. Provider Catalog 支持 `regions`、`costPerGibUsd`、`reputationScore` 和
   `maxConcurrentSessions`；旧 v1 目录使用保守默认值继续兼容。Control Plane 返回具体
   Provider 列表，Network Helper 只消费网络授权字段，不把调度元数据扩散到数据面。
3. 多 Provider 下创建 Session 可省略 Binding，由服务端执行 AUTO 路由。候选必须同时
   满足启用、`HEALTHY`、探测新鲜度不超过 90 秒、区域驻留、Provider/Secret/出口精确
   匹配和容量未满；无候选时返回 `NO_HEALTHY_PROXY_ROUTE`，禁止直连回退。
4. 评分固定为质量 45%、信誉 20%、成本 15%、区域 10%、容量余量 10%，并使用稳定
   Tie-break；一次尖峰不会改变探测健康判定。候选 Profile 以稳定顺序悲观锁定，容量按
   非终态 Session Assignment 预留计算，显式绑定和 Rebind 最终提交也会再次校验容量。
5. Session API 投影 `EXPLICIT/AUTO`、总分、质量、信誉、成本、容量、选择时间和候选
   数量；详情返回完整候选证据，列表仅返回候选数量，避免宽 JSON 放大。OpenAPI 与自动
   生成 TypeScript SDK 已同步。
6. Web/Tauri 共用 UI 展示 Provider 的出口、成本、信誉、容量和地域；创建向导默认说明
   服务端自动路由，Session 详情展示路由原因和证据。真实浏览器验收同时发现并修复
   `CATALOG_CONFIGURED` 被误判为不可创建 Binding 的兼容问题。

## 验收证据

- Control Plane 全量 303 个 Java 测试通过；
- Web TypeScript、21 个 Vitest 文件/66 项测试通过；
- Network Helper Rust 测试通过；
- OpenAPI/Buf、TypeScript SDK 生成/构建/运行时/发布包、V073 N/N−1 Gate 通过；
- PostgreSQL 17、Redis、Browser Node、Network/Storage Helper、mTLS 的完整
  `make test-integration` 通过；
- Playwright 在真实 Control Plane API 与 PostgreSQL 上验证双 Provider 目录、Binding
  Provider 切换、成本/信誉/容量/地域联动、1024px 响应式抽屉，控制台 0 error、0 warning。

## 仍未完成

本轮关闭的是仓库内确定性基础路由，不等同于商业网络生产接入。仍需：

1. 目标云 Secret Manager 的短期解引用、轮换、撤销和商业 Provider 认证 Adapter；
2. DNS、认证失败、限流、Provider 整体故障、CNI 防逃逸与目标环境长稳矩阵；
3. Challenge/黑名单、Session 粘性、SLA/业务成功率学习和受约束探索/供应商多样性；
4. Provider 实际账单对账、动态价格与目标多 Region 容量联动。
