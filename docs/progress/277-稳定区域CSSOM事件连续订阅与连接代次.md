# 稳定区域 CSSOM 事件连续订阅与连接代次

> 日期：2026-10-07
> 基线：bddda54e5380dcaacb4251ecae17e8dca9053a20
> 范围：Node 稳定区域观察；完整区域动作授权与独立 Outcome 继续保留。

## 实际缺口

自有 Chrome 页面直接修改 CSSStyleSheet：插入规则后删除，以及修改 CSSStyleRule.style
后恢复。独立隔离 World 的 MutationObserver 均得到零条 Mutation，持续订阅的 CDP
CSS Domain 每项均得到两次 CSS.styleSheetChanged。CSSOM 修改不能由现有 DOM
属性/文本事件覆盖，也不能只比较两次完整采样的最终 Bounds。

在真实 State Collector 场景加入精确按钮 pointer-events 修改后恢复，再运行旧生产
代码，命令 977 的 CSSOM 插入/删除断言实际失败：按钮继续沿用旧区域 readiness。
前一版本测试先在新增 style 元素命令 978 失败；随后单独隔离 CSSOM 用例确认缺口，
未把首次 style 元素失败冒充 CSSOM 证明。第一次命令使用错误测试名，运行零项，
不作为验证证据。

同一独立探测还确认：input.value 修改后恢复不产生隔离 Mutation，checkbox.checked
修改后恢复未产生本次已订阅的 DOM/CSS 事件。此次没有声明解决这些属性的完整历史。

## 实现

- 每个 Session 只保留当前精确 Page endpoint、主 Frame/Loader 对应的观察连接；
  DOM.enable/CSS.enable 成功后连接跨采样保留，避免两次采样之间丢弃 CSS 事件。
- 所有 CSS Domain 事件保守增加样式序号；不依据 stylesheet URL、正文或名称豁免。
  序号与 Node 私有连接代次加入区域窗口指纹，CSS 改变或重连均须重新建立三样本/
  两秒窗口。连接代次使用有界、不回绕的原子分配，不接受页面提供的字段。
- 原隔离 World 仍只观察主 Frame、最多四十条路径，grantUniveralAccess=false。
  页面和 observer 双重主文档身份围栏保持；CSS 事件与区域观察重叠时拒绝本次证明。
- 单次观察保持原三秒统一截止时间；响应最多 128 KiB、最多 4096 条消息。订阅拒绝、
  连接关闭、取消、协议或预算错误丢弃连接及本次证明；下一次使用新代次。
  Runtime 注销释放连接。无候选不出具证明。
- 序号、代次、stylesheet 标识和事件正文不进入 Protobuf/API/PostgreSQL/常规 Audit。
  不新增公开契约，不开放区域动作，普通动作仍要求完整全页静默与既有风险授权。

## 验证

- 新增真实 WebSocket 回归：跨采样 CSS 事件、观察中事件拒绝、相同 JS nonce 下重连
  仍旋转连接代次、DOM/CSS 订阅拒绝和取消后不复用半完成连接。
- Tracker 既有回归扩展为 CSS 序号变化、连接代次变化和缺失代次均重建窗口。
- 真实 Chrome 已验证新增 stylesheet、CSSOM 插入/删除、规则属性修改/恢复会使
  readiness 失效，随后可重建。原动态状态区域、实体瞬态变化、动画、克隆替换、
  未知 POST 事务阻断和 Region Resync 清除证明仍通过。
- 四项真实 State Collector Chrome 回归、Replay 43 项与 N/N−1 兼容通过。
- 最终 Rust Workspace **240 passed / 0 failed / 9 ignored**；Rust 1.99 严格 Clippy
  通过。另一次真实 Node 单动作主文档身份回归通过，合计五项 Chrome 场景。
  原子代次分配首版因 Rust 1.99 弃用 fetch_update 未通过 Clippy；改为兼容 CAS
  循环后重新通过全量 Rust 与严格 Clippy，原失败日志保留。
- 完整 OrbStack Integration **exit 0**，audit_chain_valid/screenshot_evidence/
  recording_frame_redaction 均 true；Node/Helper 构建完成。每轮 Docker Gate 前核对
  Running / orbstack / OS=OrbStack。继续使用已记录的 macOS 本地 gRPC codegen
  workaround，未修改正式 Gate 断言或预算。
- 新代码公开完整 **17 例 / PASS / Gate exit 0**，Chrome 154.0.8037.98：
  全部逐 case 账本、Runtime Validation PASSED/COMMITTED/Evidence Hash、出口允许
  与拒绝、跨域点击和非白名单计划拒绝均通过。此次仅计一次新代码完整回放；
  progress 276 的两轮基线证据不并作新代码两轮连续通过。

私有日志：/tmp/agentbrowser-region-css-probe.log、
/tmp/agentbrowser-region-css-old-cssom-real.log、
/tmp/agentbrowser-region-css-new-real.log、
/tmp/agentbrowser-region-css-chrome-state.log、
/tmp/agentbrowser-region-css-replay.log。旧代码对照直接使用基线生产源码，只加入相同
真实回归；未复用异目录 Cargo workspace。恢复新源码后重新编译与验证。

最终全量和 Clippy 日志为 /tmp/agentbrowser-region-css-rust-final.log 与
/tmp/agentbrowser-region-css-clippy-final.log；Node 场景日志为
/tmp/agentbrowser-region-css-chrome-single.log。

最终 Integration 日志和退出码为 /tmp/agentbrowser-region-css-integration-resumed.log
及同名前缀 .exit；公开完整证据位于 /tmp/ab-region-css-public.9cnw780r/，
gate-resumed.log 与 gate-resumed.exit。此前两个工具句柄与 Gate/Node 进程均在中断
后消失，未产生最终证明，按中断未完成保留，才重新运行。首次私有公开包装器还因
/tmp 中旧 Chrome launcher 缺失在启动前 exit 1；后续改用实际 Chrome binary，
未改断言/截止时间。原日志均保留，不计作业务用例失败或通过。

## 提交前基线与剩余 Gate

bddda54 的 CI 37156416397 与 Desktop 37156416403 均 completed/success；Verify、
Integration、Object Storage GameDay、Operator、Windows、macOS 均成功。这不替代
本次提交的 CI。

无事件表单属性往返、已结束 Web Animation 历史、Canvas/媒体、Shadow Root/子 Frame
连续事件仍需补充；完整 V16 Event Watermarks、可信低风险授权、区域执行与独立
Outcome 尚未闭环。公开演示集十七例两轮证据见 progress 276，不替代实际 OTP 交付、
企业 MFA/支付、客户 SPA/视觉数据集、目标 Linux/云/多 Region/硬件长稳或许可证决定。
十一项整体目标保持 active。
