# 语义元素围栏与 Batch 失败验证

日期：2026-09-03。承接 progress 165 的 A03/A04，属于明确有边界的增量修复。

## A03：不再把 DOM 路径当作足够的元素身份

State Collector 的 Element ID 现在绑定 DOM path、frame、role、非敏感名称、control type、
sensitive 分类、当前 Page URL 和活动 Tab。同一路径改成另一名称/用途，或跨 Route/Tab 后，
旧 Element ID 不能在 Batch 的最新 Target Revision 上被重绑定。

同时 target_ref 使用该身份，因此同 Revision 的 Region Resync 也不能让旧 target_ref
指向改名后的节点。输入值、focus、checked、selected 和 layout 坐标不参与稳定身份，正常
填写/勾选/布局变化不造成无谓失效；敏感目标名称和值不参与哈希。

4 项新增 Rust 回归覆盖语义变化、可变控件状态、当前 Registry 拒绝旧 ID/Ref、跨 Route/Tab。
真实 Chrome 回归在同一 DOM button 上修改 aria-label：旧 ID 拒绝，新 ID 可解析；同时更新
input.value 后其 Element ID 保持不变。临时 Profile 与浏览器仅用于测试，已清理。

边界：同名同类型按钮所在的业务行内容发生变化、页面刻意伪装名称、页面采集与实际派发间的
竞态，仍需要业务实体级语义绑定与 Outcome Verification。本修复不宣称“理解用户意图”，
也不把 A03 全部标为关闭。ID 属于不透明句柄；升级后调用方须重新 snapshot/find，不能持久
猜测 ID 编码。原 State/Target/Operation fencing 保持。

## A04：拒绝 Batch 的明确失败，不能由新 State 洗白

控制面此前只核对 actionOutcomes 的长度和 ID 次序；当 stopOnError=false 时，即使某项
FAILED/SKIPPED，整步仍可能被 VERIFIED。现要求每项 SUCCEEDED 且无 errorCode。
已知失败直接以 `BATCH_ACTION_FAILED` 结束本次 Step，不启动 Resync、不会推进后续步骤，
不会把“状态版本前进”当成“所有动作成功”。

4 项新增 Java 回归覆盖失败/跳过/未知状态、矛盾成功+错误、成功路径，以及已知失败不发
Resync/不续行。现有 Navigation/Native Dialog 验证保持。

边界：这是执行结果完整性，不是“保存已持久化”“订单已成立”等业务结果证明。
用户指定 Expected Outcome、独立 Outcome Verifier、结构化恢复和 Trace 仍在 A04/A08/A11/A16
后续清单，不应凭本修复宣称任务业务成功。

## 验证

- State Collector 29 项普通测试（含 4 项新回归）、Clippy 通过；真实 Chrome 专项通过。
- Rust 完整 Workspace 测试与 Clippy 通过；控制面完整 502 项测试/格式/检查通过。
- 最终 `make test lint build docs-check test-upgrade-compatibility` 通过。首次全量测试在
  Validation Worker 测试中遇到慢机正常心跳使固定请求数组断言失败；调整为校验业务转换
  顺序及心跳 Claim Token/Role，而非依赖执行快于一秒。未禁用心跳或放宽结果验证。
- 完整 PostgreSQL/Redis/MinIO/mTLS Integration 两轮通过；第二轮使用最终代码组合，
  包含 `agent_browser_advanced_actions=true` 和 `audit_chain_valid=true`。
- 上一提交 `a055d41` 的 GitHub Desktop `33719726392` 通过；CI `33719726436`
  已通过依赖安全扫描，但 Integration 在资源策略 PATCH 与后台写入竞争时出现
  `SessionResourcePolicyEntity` 乐观锁异常（HTTP 500）。该独立竞争问题继续修复，
  不把本地通过当作 GitHub 全绿。
- 正式 API 字段/Protobuf 未变化，仍为 240 Operations / 319 Schemas。
