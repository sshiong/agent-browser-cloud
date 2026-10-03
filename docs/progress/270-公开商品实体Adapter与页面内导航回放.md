# 公开商品实体 Adapter 与页面内导航回放

> 日期：2026-10-03
> 基线：`47e7e5c6cb7cc864ed0615a870223e47a9a6896a`
> 范围：授权公开 SauceDemo 商品回放与测试诊断；不改生产执行权限。

## 新失败证据

progress 269 后的完整十七例在商品详情入口失败，Gate exit 1。独立 CDP 确认
主文档详情导航 HTTP 404，随后页面 root 不存在、Target 为零；Node 没有动作失败，
OIDC 尚未到达。它与上一轮 OIDC Provider 请求失败不同，不能统一归因为 Revision
或 Node 渲染。旧入口方案随后商品定向一例通过，404 并非每次发生。

实际库存页具有唯一 `item_4_title_link` 和 `item_4_img_link`，标题为公开 Backpack
商品，两个链接属于同一商品卡片，href 为 `#`。这两项在绑定前被投影为同名按钮，
`interactive=false`；不能挑第一个或按坐标绕过歧义拒绝。

## 授权测试 Adapter

新增租户自有测试 Fixture，复用 Application Adapter 的 HMAC 实体属性生成函数。
临时随机身份 Key 只在测试进程内存中使用，不放入 JavaScript、State 或结果。脚本在
写入前检查精确 Origin/库存路径/主 Frame、唯一产品 ID、标签/标题、href、同一卡片和
已有身份冲突；失败零写入。绑定三项实体属性与表达标题链接含义的唯一可访问名称。

通过现有正式 PAGE_ACTION Evaluate API 提交，仍受 Actor/RBAC、意图、密封源码、精确
State/Tab/Operation 围栏约束。脚本仅写身份与名称，没有点击、导航、读取 Secret、网络
调用或改写 interactive/enabled。绑定后重新取快照，要求唯一、可交互、在视口内且未遮挡
的目标，再创建标准 CLICK_TARGET Task。详情 URL、Add to cart、购物车数量及页面结果
沿用原断言；没有进入 Checkout。

## 状态准备成本

首轮三次绑定被旧状态围栏拒绝；连续游标采样后仍发生 STATE_STALE。代码核实 Network
Quiet 哈希每秒按 Recovery Policy 档位变化，直到 30 秒。新准备阶段等待最后档位、四类
组件静默至少两秒，以及连续三次相同游标；不接受不同或更新 State 代替原签名围栏。

一轮 45 秒准备结束时，因商品资源较晚完成，网络静默只有约 20 秒；新增 Adapter
准备采样预算最终 90 秒，原导航/输入/Outcome 截止时间未变。只允许最多三次被明确状态围栏
拒绝的幂等注释重提，不重发点击或 Credential。该等待成本仍应改进，不能宣称低延迟
Evaluate 或计划创建到授权前的旧版本问题已经解决。

## 验证

- 新实体脚本覆盖十种拒绝情形：错误 Origin、路径、Frame、重复 ID、缺失图片、商品文字、
  href、卡片、标签与冲突身份；全部零写入。有效注释两次执行仍一致，链接未改变，私有
  Key 不进入表达式。
- Replay 固定错误诊断增加精确 Evaluation 围栏码，未知或带私有后缀的文本仍脱敏。
  完整 Replay Gate **37 tests / OK**；Python 编译和 diff 检查通过。
- 真实 Chrome 商品定向 **1 case / verified / Gate exit 0**：绑定后通过标准 Agent 点击、
  详情和购物车验证，绑定前两项同名目标保持不可执行。生产 Java/Rust、契约/SDK 未修改。
- 完整十七例复验 **Gate exit 1**：在练习站登录入口 NAVIGATION_FAILED，Node 固定诊断
  NAVIGATE_RESPONSE_TIMEOUT。独立 CDP 同一主 Frame 的新 Document 未取得 HTTP 响应，
  清理前约 15 秒 loadingFailed；不能确定上游、代理或 Browser Network Service 的具体根因。
  尚未到达新的商品步骤，不归为 Adapter 失败；定向通过不代表连续全量或目标 Linux 长稳。

失败证据：`/tmp/ab-login-probe.bqk0vi_v/`；旧入口定向通过：
`/tmp/ab-login-probe.fwsa6xyx/`；新 Adapter 定向通过：
`/tmp/ab-login-probe.xi4nbdv3/`。被拒绝的探索记录分别保存在
`j4z0xop_`、`kl50tdy8`、`fam3dziy`、`9xs112yl` 同前缀私有目录。
新全量失败证据：`/tmp/ab-login-probe.luvisv8x/`。
私有日志 `/tmp/agentbrowser-commerce-adapter-*.log`；Docker Gate 均核对
Running / orbstack / OS=OrbStack。

## CI 与剩余边界

`47e7e5c` 的 CI `37130025495` 与 Desktop `37130025480` 已按完整 head SHA
核验 success，含 Operator 与 Windows/macOS；不代表本次后续改动的 CI。

这是一个已授权公开商品 Adapter 示例；完全相同且没有可信业务实体键的页面仍须目标
Adapter，不能由几何或序号补造身份。完整稳定区域 Event Watermarks/风险授权/动作与
独立 Outcome、真实 OTP 交付/企业 IdP/支付/客户 SPA、供应商计算计费取消、目标云/生产
长稳及权利人许可证决策仍保留，整体十一项目标 active。
