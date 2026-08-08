# Business Recovery 浏览器就绪证据闭环

> 完成日期：2026-08-08
> 状态：真实 `document.readyState`、持续 CDP Network 安静窗口、Toast/Dialog
> 瞬态阻断契约、迁移 Ready Gate、正式 API/UI、版本历史和 N/N−1 兼容已完成；
> 无语义像素/OCR、客户站点规则和目标 Region 长稳仍待接入。

## 本轮关闭的缺口

此前 Application Recovery Contract 已能验证 Origin、Route、Login/Permission/Account
语义目标、Required Target、Extension 和 Provider Evidence，但仍可能在以下时刻把页面
过早判为 Ready：

- 文档仍处于 `loading` 或 `interactive`；
- 页面主请求结束，但仍有真实 CDP Network 请求在途；
- 恢复后短暂出现阻断 Toast、Alert 或 Dialog；
- Browser Network 观察器断线，平台却把缺失证据误当作安静。

V074 和追加 Proto 字段把这些条件纳入同一个不可变 Recovery Contract Revision。

## Browser Node 权威证据

State Collector 现在从真实页面和 Browser 级 CDP 事件流采集：

- `documentReadyState`：由 `Runtime.evaluate` 读取 `document.readyState`；
- `networkQuietMillis`：最后一次 Network/Download 活动之后的连续安静时长；
- `networkEvidenceFresh`：Browser 下载域与至少一个 Page Network 域持续有效时才为真；
- `alert`、`status`、`dialog`、`alertdialog`：加入有界可访问目标采集。

任何观察器断线、没有权威时间戳或存在在途请求时，安静时长都失败关闭为 `0`。数值
最多保留 5 分钟，不运行每 Session 复杂预测模型，也不由前端定时器伪造。

2026-08-08 的完整迁移回归进一步修复了状态版本竞态：Network Quiet 仍返回真实有界
毫秒值，但用于决定是否发布新 Browser State 的哈希按 1 秒分桶，并在契约允许的最大
30 秒窗口处封顶；Freshness 作为独立失败关闭语义参与哈希。这样 0—30 秒的所有合法
阈值仍能被状态流观察，超过最大阈值后不会因时钟继续增长而持续作废严格绑定
`stateVersion` 的 Provider Evidence。

完整 State 与 Diff 分别使用 Proto 追加 Tag `11—13`；N−1 Node 不认识这些字段时会留下
空值、`0`、`false`，启用网络 Gate 的新 Control Plane 因此不会错误放行。

## Recovery Contract 与判定顺序

新增版本化字段：

```text
requireDocumentComplete
minimumNetworkQuietMillis  // 0—30000，0 表示关闭
transientBlockerTargets    // 最多 32 个精确 Role + Accessible Name
```

Ready Gate 在 Login、Permission、Account 判定之后执行：

1. 要求 Document Complete 但状态不是 `complete`：`STATE_CHANGED`；
2. 要求网络安静但 Observer 不权威：`MANUAL_RECOVERY_REQUIRED`；
3. 连续安静时间不足：`STATE_CHANGED`；
4. 命中声明式瞬态阻断目标：`STATE_CHANGED`；
5. 再继续 Route、Required Target、Extension 与 Provider Evidence 检查。

这套规则不接受 CSS Selector、正则、租户 JavaScript 或任意 CDP Method。Dialog/Toast
匹配使用 State Collector 输出的精确可访问 Role 与 Name。

## PostgreSQL、API 与控制台

- V074 为当前契约和不可变 Revision 同步增加三个字段，并更新快照 Trigger；
- 旧行默认关闭新增规则，滚动升级不改变既有行为；
- OpenAPI、TypeScript/Python/Go/Java SDK 同步公开契约字段；
- Browser State API 返回文档与网络观察证据，便于运维解释 Ready Gate；
- Web/Tauri 共用 Recovery Contract 编辑器新增 Document Complete、Network Quiet 和
  瞬态阻断目标设置；
- Diff/历史页面显示三个新字段，回滚仍创建新的 DRAFT 并重新审批。

控制台默认新建契约要求 Document Complete 和 1000 ms Network Quiet；管理员可以显式
设为 `0` 关闭网络窗口。正式 API 仍保持新增请求字段可选，兼容 N−1 Client。

## 验收范围

自动化覆盖：

- Observer 缺口和在途请求的网络证据失败关闭；
- 安静时长计算与 5 分钟上限；
- Document `interactive` 拒绝；
- Network Observer 不权威拒绝；
- 精确 Dialog 命中拒绝；
- Document Complete 且安静窗口满足后 Ready；
- Proto 映射、State/Diff 传播、OpenAPI 与四语言 SDK 漂移；
- V074 加法迁移、Revision 快照与 N/N−1 Tag 兼容；
- 完整 Integration 中真实 Browser State API 和绑定的精确 Contract Revision。
- 完整 PostgreSQL/双 Control Plane/Browser Node 回归覆盖 30 秒稳定点、严格
  Provider Evidence 绑定、Business Recovery 完成与双 Node Migration；对象存储
  Timeout/Recovery GameDay 同时通过。

## 仍未完成

1. 客户 CRM、支付和 IAM 的正式 Dialog/Alert 文案、路由及 Provider 字段映射；
2. 无语义 Canvas/图片文字的 OCR 或受控视觉分类 Validator；
3. Challenge/CAPTCHA Detection、一次性 HumanAssist 与站点级恢复策略；
4. 独立 Business Recovery 事件流和跨 Region Validation Worker 编排；
5. 目标 Linux/Region 的长连接、SSE、下载、网络分区和长期稳定性证书。

因此，Network、Toast/Dialog、Visual（可访问目标）、Login 和 Business Entity 的基础
声明式 Validator 已不再是仓库代码缺口；剩余是无语义视觉/OCR、高级组合编排与真实
客户站点接入，不能把 Fixture 验收表述为生产业务完成。
