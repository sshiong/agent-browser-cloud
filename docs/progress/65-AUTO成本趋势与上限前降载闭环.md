# AUTO 成本趋势与上限前降载闭环

> 完成日期：2026-07-29  
> 状态：仓库内实现、N/N−1、真实 PostgreSQL/Node 集成通过；目标 Linux/云成本费率与
> 长期容量证书仍属于发布 Gate

## 本轮关闭的缺口

此前 `maximumCostPerHour` 只进入 Policy 契约和 PostgreSQL，资源决策没有消费它；
成本解释只能按请求即时计算，也没有形成 Session 长期趋势。资源达到 CPU/内存上限后
会直接进入暂停、迁移、休眠或严格终止，缺少一次真实的非核心预算降载。

本轮完成：

1. 每 5 分钟从真实 `browser_placements` 和版本化 `enterprise_cost_rates` 解析当前
   Session 小时成本；
2. PostgreSQL 持久 `session_resource_cost_snapshots`，Policy 保存当前成本、费率版本
   和最后计算时间；
3. `maximumCostPerHour` 真正进入决策：超限记录 Resource Event，并执行配置的
   Maximum Reached Policy；
4. 配置成本上限但费率/Placement 不可用时 fail-closed：状态进入 `CRITICAL`、保留
   Browser，不猜测成本；费率恢复后记录恢复事件；
5. 达到资源或成本上限时，先通过真实 `RESOURCE_ADJUSTMENT` Operation 向 Browser
   Node 下发一次非核心降载，再进入 Level 2+；
6. 创建向导和 Session Resource Policy Drawer 可配置每小时成本上限，详情卡显示真实
   当前成本、上限、费率版本和趋势。

## V042 数据模型

`V042__session_resource_cost_and_maximum_mitigation.sql` 以 expand-only 方式新增：

- `session_resource_policies.current_hourly_cost`
- `cost_pricing_version`
- `last_cost_evaluated_at`
- `maximum_mitigation_at`
- `maximum_mitigation_operation_id`
- `session_resource_cost_snapshots`

旧 Writer 不需要认识新增可空列。新增约束使用 `NOT VALID → VALIDATE`，没有删除、重命名
或收紧旧列；N/N−1 Gate 已将 V042 纳入 expand/validate/contract 检查。

## 真实成本决策

`SessionResourceCostTrendScheduler` 默认每 300 秒选取到期的 RUNNING/DEGRADED Session。
每次计算复用正式企业费率逻辑：

```text
真实 Placement
→ Region + Effective Resource Class
→ 生效中的版本化 Cost Rate
→ CPU / Memory / Desktop / GPU / Media 分项
→ 持久 Cost Snapshot
→ 与 maximumCostPerHour 比较
→ Maximum Reached Policy
```

成本上限未配置时仍形成可解释趋势，但不会改变资源状态。成本上限已配置而费率不可用
时，不会静默跳过，也不会直接终止 Browser。

## 上限前一次性非核心降载

CPU 和内存已到允许上限、或成本已经越界时，系统先检查当前是否存在排他 Operation，
然后最多执行一次：

- 降低 State Collector Budget；
- 降低 Remote Desktop Bitrate；
- 降低受信 Extension CPU Weight；
- 减少 Media Encoder Slot；
- 通过真实 CDP 冻结后台 Page Target；
- 以策略提交时的 Page Target 为允许集合，持续阻断新建标签；
- CPU/内存保持当前值，不伪造扩容。

该动作复用既有真实链路：

```text
REQUESTED
→ Node Command Outbox
→ Browser Node Actuator
→ Cgroup / State Collector / RDP / Extension / Media
→ RuntimeResourcesAdjusted ACK
→ COMMITTED / FAILED
```

`maximum_mitigation_operation_id` 防止每个 30 秒决策周期重复降载。若 ACK 后压力仍持续，
下一次决策才进入暂停 Agent、等待安全点迁移、休眠或严格终止。

## API 与 UI

`GET /api/v1/sessions/{id}/resources` 新增可空 `cost`：

- `currentHourlyCost`
- `maximumHourlyCost`
- `pricingVersion`
- `lastEvaluatedAt`
- `trend[]`

数据全部来自 PostgreSQL 快照；Web 不生成成本曲线。管理员高级资源设置新增每小时成本
上限，严格终止仍仅对 Platform Admin 展示并要求二次确认。

## 验收证据

- Control Plane 完整 `check` 通过；
- 新增成本超限暂停、费率缺失 fail-closed、上限前 Node 降载单测；
- Web format/lint、13 个测试文件/42 项测试、production build 通过；
- OpenAPI/Buf/JSON Contract Gate 通过；
- V042 N/N−1 Gate 通过，Evidence Hash：
  `a8556a4ff76f9e8e64a3531b26627719e916ef349c9f09280f28f388f0b20e6c`；
- Browser Node 全 Workspace Test、Fmt、Clippy 通过；
- Kustomize 渲染通过；
- 完整 `tests/integration/smoke.sh` 通过：
  - Flyway 创建 62 张公开表；
  - Scheduler 从真实 Placement/费率写入 Cost Snapshot；
  - Resource API 返回同一费率版本和非空真实趋势；
  - 原有在线资源调整、ACK、Safe Point、迁移、Coordinator 故障接管和 exactly-once
    证据无回归。

## 仍需完成

1. 后台 Tab 冻结和新建 Tab 阻断已由
   [进度 66](66-AUTO后台标签冻结与新建标签阻断闭环.md)关闭；Level 1 尚未覆盖成功
   Trace 采样、视频/Observer 录制与帧率、截图频率和逐 Extension 后台任务暂停；
2. 企业成本模型仍以内部 Legacy Resource Class 关联费率；应在兼容窗口内迁移为内部
   Template/容量维度，避免在企业 UI 暴露 L1−L5；
3. 目标云真实费率、账单校准、租户预算来源和长期成本准确性证书；
4. 成本越界后的低成本 Node 选择与安全点迁移需要目标多 Node/多 Region 验收；
5. Session Coordinator HTTP/Timer/Workflow 物理 Shard Pod 路由仍未完成。
