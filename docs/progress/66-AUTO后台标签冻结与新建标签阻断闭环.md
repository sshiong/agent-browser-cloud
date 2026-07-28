# AUTO 后台标签冻结与新建标签阻断闭环

> 完成日期：2026-07-29  
> 状态：仓库内实现、真实 CDP、Node ACK、PostgreSQL、Web 与完整集成通过；逐扩展后台
> 暂停已由进度 67 关闭，Trace、录制和截图执行器仍待后续关闭

## 本轮关闭的缺口

`tabBudget` 此前只存在于 Placement、Node Command 和 ACK 字段中，Browser Node 没有
实际执行标签页限制。资源达到最大值时，系统虽会降低 State Collector、Remote
Desktop、Extension 和 Media 预算，但仍不能冻结后台标签，也不能阻止继续打开新标签。

本轮完成：

1. `V043` 在 `browser_placements` 持久 Node 已确认的
   `background_tabs_frozen` 和 `new_tabs_blocked`；
2. `StartRuntime`、`AdjustRuntimeResources` 和 `RuntimeResourcesAdjusted` 使用新增
   optional protobuf 字段，兼容 N/N−1 滚动升级；
3. Maximum Non-core Mitigation 明确下发“冻结后台标签”和“阻断新标签”，普通资源
   调整会解除策略；
4. Browser Node 只通过内部回环 CDP 执行，不把 Target ID 或 CDP 地址暴露给前端；
5. Control Plane 只有收到 Node 权威 ACK 后才更新 Placement、Resource Event 和 UI；
6. Session 重启时从 PostgreSQL Placement 恢复策略，不因 Runtime 重启静默失效。

## Browser Node 真实执行

### 后台标签冻结

Node 读取真实 Page Target，先执行固定表达式读取 `document.visibilityState`。只有结果为
`hidden` 才发送：

```text
Page.setWebLifecycleState(state = frozen)
```

冻结使用五秒短 Lease。Node 周期性将冻结 Target 临时恢复为 `active`，给用户切回标签页
的机会；仍处于后台的标签下一轮重新冻结，避免永久冻结用户已重新激活的页面。

### 新建标签阻断

策略提交瞬间存在的 Page Target 形成允许集合。Node 每秒读取 `/json/list`，发现策略
提交后出现的新 Target 时，通过 Browser CDP 执行：

```text
Target.closeTarget(targetId)
```

已有标签不会因为开启策略被关闭。Runtime 重启时会重新建立本代 Browser 的初始允许
集合，因此不会把恢复后的首个页面误判为违规新标签。

## Operation 与 ACK

执行链保持：

```text
RESOURCE_ADJUSTMENT REQUESTED
→ Node Command Outbox
→ Cgroup / State / RDP / Extension / Media 调整
→ CDP Tab Policy
→ RuntimeResourcesAdjusted
→ Control Plane 校验旧值、新值、Node 与 Operation
→ Placement + Resource Event COMMITTED
```

CDP 初始执行失败时，Node 不发送成功 ACK，并回滚本次 Cgroup、State Collector、Remote
Desktop 和 Tab Policy。持续监视失败会在 Node 日志中显式告警，资源遥测仍继续上报。

## API 与 Web

`GET /api/v1/sessions/{id}/resources` 的 `allocation` 新增：

- `backgroundTabsFrozen`
- `newTabsBlocked`

Session Resource Panel 直接显示“已由 Node 冻结/阻断”或正常状态，不根据策略原因猜测，
也不只使用颜色表达状态。

## 滚动升级

- V043 只新增带安全默认值的列，不删除、重命名或收紧旧列；
- 旧 Control Plane/Node 会忽略 protobuf unknown fields；
- 新 Control Plane 接收旧 Node ACK 时保留当前标签策略值；
- Start/Adjust/Event 的字段号分别为 `23−24`、`17−18`、`25−28`；
- N/N−1 Evidence Hash：
  `2469aa92a140288472059209ddcc427e96d3735ff3b5ffcea4fe9853918dbcea`。

## 验收证据

- Control Plane `check` 通过；
- Browser Node Workspace Test、Fmt、Clippy 通过；
- 新增真实 CDP 单测：
  - ACK 前发送 `Page.setWebLifecycleState(frozen)`；
  - 策略提交后对新 Target 发送 `Target.closeTarget`；
- Web format/lint、13 个测试文件/42 项测试、production build 通过；
- OpenAPI、Buf、N/N−1 Gate 通过；
- 完整 `tests/integration/smoke.sh` 通过：
  - V043 迁移成功；
  - 持续压力到达最大值后发出真实 Node Operation；
  - PostgreSQL 记录 `50:true:true`；
  - Resource API 返回相同的 Node ACK 状态；
  - Resource Event 记录两个标签执行器均为 `COMMITTED`；
  - 原有 Coordinator 故障接管、迁移、恢复、成本和审计链无回归。

## 仍需完成

1. 成功 Trace 动态采样已由
   [进度 68](68-AUTO成功Trace动态采样闭环.md)关闭；
2. 视频/Observer 录制停止与帧率执行器；
3. Agent/Observer 截图频率执行器；
4. 非关键 Extension 后台任务的逐扩展暂停已由
   [进度 67](67-AUTO非特权扩展后台暂停闭环.md)关闭；
5. 目标 Linux 多 Browser 长稳和真实用户切换标签矩阵；
6. 将企业费率公开契约中的 Legacy L1−L5 迁移为内部 Template/容量维度。
