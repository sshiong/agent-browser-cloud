# Proxy 主动健康探测与质量投影闭环

> 完成日期：2026-08-01
> 状态：仓库内真实 Node 探测、PostgreSQL、API、SSE 与 Web/Tauri 共享 UI 已闭环

## 本轮结论

按 `docs/outline/`、自动资源分配提示词和当前代码重新核验后，AUTO 资源主链并不存在
“仅有 UI、尚未连接后端”的问题：5 秒轻量采集、30 秒窗口决策、快扩慢缩、冷却与
Hysteresis、Cgroup/非 Cgroup 执行器、Operation/Node ACK、安全点、迁移、达到上限后的
Agent Pause/降载/休眠/受控终止以及统一 SSE 均已在进度 42—97 落地。本轮实际未闭环的
高优先级代码项是 Proxy Binding 的后台主动出口探测、质量计算和实时状态展示。

## 已完成

### 1. Browser Node 主动出口探测

- Browser Node 默认每 30 秒对运行中、已有 Binding 的 Session 发起一次轻量探测；周期可
  配置为 15—3600 秒，同一 Session 不允许探测重入。
- 探测继续通过隔离的 Network Helper 执行，Node Agent 不直接读取或解析 Proxy Secret。
- 探测完成后立即发送一条最小 `ReportSessionResources`，不等待下一次常规遥测；Control
  Plane 暂时不可达时保留最近观察，随下一次资源报告重投。
- Protobuf 只追加 31—34 字段，并使用有界错误码 `TIMEOUT / CIRCUIT_OPEN /
  EXIT_MISMATCH / HELPER_UNAVAILABLE / PROBE_FAILED`；Helper 原始错误和凭据不会跨越边界。
- Runtime 停止与 Node 关闭会清理探测状态和 in-flight 标记。

### 2. PostgreSQL 权威样本与质量决策

- V071 新增凭据无关的 `proxy_binding_health_samples`，以 Binding、Tenant、Allocation、
  Session、Node、来源和时间记录真实结果；三组复合外键保证租户和运行分配身份。
- 原始样本默认保留 7 天；按小时最多删除 10,000 条，避免一次清理形成长事务。
- Binding 维护成功/失败计数、连续成功/失败、成功率 EWMA、延迟 EWMA 和最近探测来源。
- 健康 Hysteresis 为连续 3 次失败才进入 `UNHEALTHY`，从不健康恢复需要连续 2 次成功；
  第一次恢复成功仍保留最后失败原因。
- 质量分采用透明公式：80% 成功 EWMA + 20% 延迟分，2 秒延迟耗尽；API 同时返回终身
  成功率、延迟 EWMA、样本数、连续失败数与数据新鲜截止时间。
- 健康写入使用原子 SQL，不递增管理员配置使用的 JPA `version`，避免后台探测与编辑
  Binding 产生伪 CAS 冲突。

### 3. API、实时更新和界面

- `GET /api/v1/proxy-bindings` 的 OpenAPI 和正式响应增加质量、新鲜度与探测字段；未向
  前端返回 Secret、节点 Cgroup 或探测原始错误。
- Proxy 页面删除固定 5 秒轮询，复用 Workspace 可续传 SSE；数据库只在首次健康结果、
  状态切换或间隔 5 分钟时广播一次失效事件，不为每个 30 秒样本制造事件洪泛。
- Binding Card 显示 `Quality / Latency EWMA / Success / 真实探测数 / 数据已过期 /
  失败码与连续失败数`，状态同时使用文本和颜色。
- 修复单 Binding 时网格剩余区域被边框底色填满，以及 Proxy 编辑器引用不存在的
  `btn-primary/btn-secondary` 导致按钮缺少禁用/交互样式的问题。
- 组件仍位于 React Web Console，共用 API Client、鉴权、Query 和状态逻辑，可由既有
  Tauri 2 容器直接复用。

## 验收证据

- Java 定向测试：探测身份、出口不匹配、任意错误文本拒绝、完整/不完整 Proto 观察；
- Rust Node Agent：探测错误归类、周期和重入保护；
- `make ci`：Java/Rust/Web、Buf、OpenAPI、N/N−1、供应链、Operator、50k 容量与四 SDK
  全量通过；V071 与 Proto 追加字段进入升级 Gate；
- 完整 PostgreSQL/Redis/MinIO/mTLS/三 Browser Node Integration 已验证真实
  `ACTIVE_EXIT_PROBE` 样本、API 质量字段和无 Secret 泄漏；
- Delta 复验：PostgreSQL 17 空库成功执行 V001—V071，Control Plane 连接真实数据库，
  Proxy API 返回质量投影；Web lint、21 个文件/66 项测试和生产构建通过；
- Playwright 连接真实 API 在 1440×1000 和 390×844 验收，页面无横向溢出，浏览器
  Console 为 0 error / 0 warning，断更超过新鲜期后明确显示“数据已过期”。

## 当前仍未完成

1. 目标云 Secret Manager 的短期凭据解引用、轮换、撤销与审计；
2. 商业 Proxy Provider Adapter、真实供应商 DNS/认证/限流/故障矩阵；
3. 未被 Session 使用的冷 Binding 独立探测 Worker后续已由
   [进度 100](100-Proxy冷Binding探测与分布式租约闭环.md)关闭；多 Provider 自动路由仍待完成；
4. Provider 价格、流量成本、信誉和地域可用性联合评分；
5. 目标 Linux/CNI 防直连逃逸、长期压力和真实 Provider GameDay 证书。

除已由进度 100 关闭的冷 Binding 探测外，其余项属于目标云集成和多供应商优化；
“运行中或冷 Binding 没有主动出口探测和质量状态”已经不再是仓库代码缺口。
