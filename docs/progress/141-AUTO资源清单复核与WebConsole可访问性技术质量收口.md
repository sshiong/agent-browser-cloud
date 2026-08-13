# AUTO 资源清单复核与 Web Console 可访问性技术质量收口

> 日期：2026-08-13
> 状态：外部 P0/P1 缺口清单已逐项代码复核；触控命中区、主题 Token 对比自动
> Gate 和 Session Detail 路由包拆分已完成；完整辅助技术矩阵与目标环境长稳
> 仍是生产 Gate

## 本轮目标

1. 对一份基于旧快照（约进度 41—53 时点）的 AUTO 资源与 Web Console 缺口清单
   逐项复核，确认哪些已真实落地、哪些仍未完成；
2. 复核今日 `ci` Workflow 两次失败的根因与修复状态；
3. 关闭[进度 53](53-当前剩余实现与前端技术质量审计.md)与
   [进度 33](33-当前未实现清单.md)中仍属仓库级代码缺口的前端技术质量项；
4. 同步更新追踪文档。

## 清单复核结论

以下条目在本轮以代码证据逐项复核，均已在此前进度中真实落地，不是仅有文档声明：

| 旧清单条目 | 代码证据 | 关闭进度 |
| --- | --- | --- |
| Browser Node 5 秒 Session 指标上报 | `node-agent` 健康探测循环按 `resource_report_interval_probes` 周期调用 `report_session_resources`，首个完整观测在第 5 次探测强制发布 | 42、47、49、52、54 |
| Resource Actuator（Cgroup/编码器/桌面码率/扩展权重） | `runtime-supervisor` 在线写 `cpu.max`、`memory.high`、`memory.max`、`pids.max` 并支持回滚；Encoder Slot、Remote Desktop 码率、Extension Weight 均入 ACK 链 | 42、47、52、54、69 |
| ACK/Operation 状态机 | `SessionResourceAdjustmentEntity` 持久化 `REQUESTED/EXECUTING/ACKNOWLEDGED/COMMITTED/FAILED/RECONCILED`，非法转移拒绝 | 127—130 |
| 扩缩容决策（缩容窗口/冷却/Hysteresis） | `SessionResourceApplicationService` 决策循环包含 `adjustmentCooldownSeconds`、缩容窗口与迟滞判断 | 46 |
| 达到上限完整处置（迁移/休眠/严格终止） | 自动休眠、跨 Node 迁移、`TERMINATE_STRICT` 均接真实 Operation/Node 执行链；成本上限与上限前降载在 V042 | 43、65 |
| Safe Point Aggregator | `SafePointApplicationService` 聚合拖拽/输入/上传/下载/表单/支付/账号安全/SPA Mutation/关键事务/Snapshot/Profile Flush | 48、50、136、137 |
| 跨 Node 迁移全链路 | Checkpoint → 排除源 Node → S3 Restore → State Resync → Business Recovery Ready Gate；双 Node + MinIO + CDP 数据面 E2E | 43、80、81 |
| Resource Event SSE | `api/session.ts`、`workspaceOverview.ts`、`notification.ts` 均为可续传 SSE；Resource/Safe Point/Migration 固定轮询已删除 | 44、84、108 |
| Tauri 2 桌面端 | `apps/desktop/src-tauri`、OS 安全存储、Updater 门禁、desktop Workflow 常绿 | 45 |
| Groups/Tags/全局搜索/通知中心/主题/用户菜单 | `TopContextBar` 挂载真实 `GlobalSearchDialog`、`WorkspaceNotificationCenter`、`ThemeSwitcher`、`UserMenu` | 55、57、86、89、90、91 |
| Environment/Profile Import、Saved View、Proxy Binding | 正式 PostgreSQL/API/UI | 77—79、82、85、94 |
| OpenAPI 四语言 SDK、Terraform Provider、Operator List/Watch、Validation/GameDay/Burn Rate、Hot Tenant、Agent Worker/Reviewer、Region Resync/流式 Snapshot/Backpressure | 对应目录与测试均在仓库内 | 63、101—105、109—120、116 |

清单中真正仍未完成的是既有追踪文档已明确列出的目标环境与组织
Gate：真实企业 IdP、Apple/Microsoft 签名、目标 Linux/云长稳矩阵、真实多
Region 复制切换、客户 Provider 凭据接入等，见
[进度 33](33-当前未实现清单.md)。

## CI 复核

- 今日 `ci` Workflow 两次失败（run 31703276852、31703948411）根因一致：
  Recording 权威清单接入后 `StopRuntime` 失去幂等性，failover 清理对未注册
  Recorder 的 Session 反复返回 `NODE_COMMAND_FAILED`，集成冒烟
  `stopping_failover_state` 无法收敛到 `TERMINATED`；
- 已由 `94277ee`（恢复 `unregister` 幂等 + 回归测试）修复；此后 `ci` 与
  `desktop` Workflow 均为绿色。

## 本轮已完成

### 触控命中区（WCAG 2.5.5）

- `src/index.css` 新增 `@media (pointer: coarse)` 规则：所有启用状态按钮以
  `::before` 伪元素把命中区扩展到至少 44×44 CSS px，桌面精确指针保持既有
  高密度视觉尺寸；
- 采用 `:where()` 保持零特异性，不覆盖显式 `absolute/fixed` 定位按钮；选用
  `::before` 是因为激活态标签按钮已用 `::after` 绘制下划线指示器。

### 主题 Token 对比自动回归 Gate

- 新增 `src/features/theme/tokenContrast.test.ts`：从真实 `index.css` 解析
  深色 `@theme` 与浅色 `html[data-theme='light']` Token（hex 与 OKLCH），
  按 WCAG 相对亮度断言 3 个文本 Token × 5 个表面 × 2 主题共 30 组对比
  ≥ 4.5:1，Token 劣化会直接使 `vitest`/CI 失败；
- 当前实测最低组合为深色 `text-muted` on `surface-3` 的 4.56:1，与进度 53
  人工实测值（5.40/5.01/5.87）一致。

### Session Detail 路由包拆分

- `SessionDetailPage` 将引入图表库的 `SessionResourcePanel` 改为
  `React.lazy` + `Suspense`（`LoadingPanel` 兜底），行为与属性不变；
- 路由块从 483.98 kB（gzip 130.43 kB）降至 64.43 kB（gzip 16.53 kB），
  资源面板与图表库成为独立按需块（420.49 kB，gzip 115.92 kB），详情页
  首屏不再被图表库阻塞。

## 已验证

```text
pnpm --dir apps/web-console lint   # 通过，0 warning
pnpm --dir apps/web-console test   # 22 个测试文件、104 项通过（含新增 31 项对比 Gate）
pnpm --dir apps/web-console build  # 通过，SessionDetailPage 64.43 kB / gzip 16.53 kB
```

## 尚未完成

1. 完整辅助技术矩阵仍是发布 Gate：全键盘操作、焦点陷阱、真实屏幕阅读器、
   200% 缩放和自动 axe/ARIA 全站回归；
2. 平台安全（Audit/Break-glass/Key Rotation/Secure Debug）、Nodes 容量与
   Enterprise 总览页仍使用有界固定轮询，属于既有“列表批量事件流”生产深度
   缺口，见进度 33 Web Console 第 4 条；
3. 目标环境与组织 Gate 不因本轮改变：真实企业 IdP、桌面签名、目标
   Linux/云长稳、真实多 Region、客户 Provider 凭据与发布签字。
