# 公开资源等级迁移为 Resource Template

> 完成日期：2026-07-29
> 状态：创建 SDK、正式 OpenAPI、Session/Placement、企业费率、成本解释和 Web 已移除公开 L0–L5；内部调度兼容维度继续保留

## 关闭的缺口

AUTO 创建向导虽然已经不显示固定等级，但正式 OpenAPI、四语言 SDK、Session 响应和
企业费率仍暴露 `resourceClass`。这会让调用方继续把 L1–L5 当成用户资源选项，并把
`L5` 与 `Native OS` 错误绑定。

本轮完成以下迁移：

1. TypeScript、Python、Go、Java SDK 的创建输入删除 `resourceClass`，默认提交
   `resourcePolicy: {"mode":"AUTO"}`，仍允许设置 AUTO 上限、最低模板和上限行为；
2. `CreateSessionRequest` 的正式 OpenAPI 不再声明 Legacy 字段；
3. Session List/Detail/Context 改为 `resourceTemplate`；
4. Browser Placement 对外只返回 `requestedTemplate` 和 `resolvedTemplate`，内部
   `requested/effective ResourceClass` 通过 Jackson `JsonIgnore` 保持调度使用；
5. 企业费率、成本解释和 Enterprise Operations UI 改为 `resourceTemplate`；
6. 初始费率版本从 `local-l1-v1` 等迁移为
   `local-standard-lite-v1`、`local-standard-v1`、`local-interactive-v1`、
   `local-heavy-v1` 和 `local-native-standard-v1`，历史成本快照引用同步更新。

## 内部与公开边界

公开模板：

```text
suspended-v1
standard-lite-v1
standard-v1
interactive-v1
heavy-v1
native-standard-v1
```

`Native OS` 仍只属于独立 `ExecutionEnvironment`。`native-standard-v1` 是 Native
执行环境可采用的内部资源模板，不是“L5 Native”用户等级。

Control Plane 内部暂时保留 `ResourceClass.L0–L5`，用于：

- N−1 数据库与 Node protobuf 兼容；
- 旧 Placement 顺序比较；
- 现有容量预算映射。

它不再出现在正式 OpenAPI、SDK 创建输入或公开 JSON 响应。后续可在独立破坏性迁移
窗口中删除内部兼容列和枚举，本轮不以破坏滚动升级的方式强删。

## V048 滚动升级

`V048__public_resource_template_pricing.sql` 采用 expand-only 路径：

1. 增加 `enterprise_cost_rates.resource_template`；
2. 按存量 `resource_class` 回填；
3. 增加 `BEFORE INSERT/UPDATE` Trigger，使 N−1 Control Plane 只写旧列时也自动生成
   模板；
4. 再设置 NOT NULL、`NOT VALID → VALIDATE` 约束和模板查询索引；
5. 不删除、重命名旧列或旧约束。

新 Control Plane 同时写旧兼容列和模板列；费率查询、成本解释和 Cost-aware
Placement 使用模板列。回滚到 N−1 后，旧代码仍可通过 `resource_class` 工作。

## 验收

- Control Plane 单元测试验证 Placement JSON 只含模板、不含内部等级；
- OpenAPI lint 验证不存在 `ResourceClass` Schema 和创建字段；
- 四 SDK 测试验证默认 AUTO 且请求体不含 `resourceClass`；
- Web lint、42 项单测验证新类型和企业费率展示；
- N/N−1 Gate 验证 V048 Trigger、回填、NOT NULL 与约束顺序；
- PostgreSQL/Browser Node Integration 验证 Session List/Detail、Placement、企业费率、
  成本解释和资源成本趋势全部使用模板名称。

N/N−1 Evidence Hash：
`c4d3ae198035addf951e00324c39e6489f869147758f8f3e70f44e4bba0ab6ab`。

## 仍需完成

1. Agent/Observer Screenshot/Evidence 正式对象数据面及成功截图频率执行器；
2. 在计划内破坏性版本中移除数据库和 Node 命令的内部 Legacy Class 字段；
3. 正式 OpenAPI 自动生成、签名并发布 SDK；当前四 SDK 仍为仓库内手写契约型客户端；
4. 目标环境费率校准、预算审批和 Billing 对账。
