# 公开返回页离屏链接的标准 Agent 滚动回放

> 日期：2026-10-04
> 基线：a7f861e0a6726777214f008a33ad634083764718
> 范围：真实公开回放场景；生产代码、API/SDK、数据库、Node 可交互/域名/风险围栏不变。

## 实际失败与布局证据

progress 275 的最终公开全量在返回 Example 页后，原 45 秒状态等待失败；导航 Task
已经 verified，但唯一 link 的 visible/inViewport 均为 false。首轮缺少对应视口、
滚动和真实 DOM 边界，未把观察年龄约 14 秒或网络静默单独认定为失败原因。

同一代码的新建 Session 回放再次在该阶段失败，证据 /tmp/ab-login-probe.96qnvxwl/。
独立 CDP 的固定只读布局与正式 API 对齐：

| 属性 | 实际值 |
| --- | --- |
| Chrome | 154.0.8037.95 |
| CSS 视口 | 756 × 413 |
| 页面高度 | 695 |
| scrollX / scrollY | 0 / 0 |
| 唯一链接 Bounds | x=337, y=640.078125, width=82, height=18 |
| CSS display:none / visibility:hidden | false / false |
| API visible / inViewport / occluded | false / false / false |
| API visibilityReason | OUTSIDE_VIEWPORT |

Node 正确投影离屏目标。回放在返回 URL 后直接等待链接可见，未执行滚动；延长等待
不能把链接带入视口。AGING 另已在私有诊断包装器中识别，不作为放宽 FRESH 的理由。
这不解释 progress 274 更早的商品登录页空白，也不改写此前其他独立失败。

## 场景修正

返回页导航四步仍须 verified。随后使用既有正式 Agent Task/标准 SCROLL，精确允许域
仅 example.com，scrollDeltaY=600、maxActions=8、replanBudget=1；必须四步
GET_CURRENT_STATE/SCROLL/GET_URL/GET_PAGE_SUMMARY 全部 verified。

继续使用原 45 秒等待、FRESH/STABLE/完整或 Depth-limited 状态和 enabled/visible
链接要求，然后执行原跨域点击拒绝与非白名单计划拒绝。没有自动重发点击、没有以
CDP 或 Evaluate 执行滚动，没有改动域名名单、支付/Secret/实体身份门禁。
这一新增动作使用既有 Scene 的标准原语和核验路径，无需新的正式契约。

## 验证

- Replay 43 项与 N/N−1 兼容检查通过；Python 编译及 diff 检查通过。
- 首轮修正后完整公开 **17 例 / PASS / Gate exit 0**，Chrome 154.0.8037.95，
  /tmp/ab-login-probe.7l1q_d94/。逐 case 账本必须齐全后才可提交 Runtime Validation；
  正式 Validation PASSED/COMMITTED 与 Evidence Hash 断言、外部出口精确主机允许、
  跨域出口拒绝等原 Gate 都通过。
- 独立 DOM 确认 scrollY=282（页面底部），链接 y=358.078125、高 18，在 413 高视口内；
  未修改布局、viewport 或 visibility 属性。SCROLL Task 四步 verified 后，原业务、
  输入、Challenge、OIDC/PKCE 和两项拒绝结果继续通过。
- 第二轮相邻新建 Session 同样完整 **17 例 / PASS / Gate exit 0**，
  /tmp/ab-login-probe.gcrl48lw/；两轮均保留全部逐 case、Validation 与出口拒绝断言。
  本轮形成 **17 例 × 2 轮** 的连续完整本机证据，不作为目标 Linux/客户或长期稳定证书。

私有日志 /tmp/agentbrowser-return-example-scroll-{replay,public-full,public-repeat}.log。
每次 Docker Gate 前核验 Running / orbstack / OS=OrbStack。只读诊断输出固定枚举、
布尔、有限数字与 Bounds，不输出页面正文、URL Query、Token 或原始异常。

## 基线 CI

a7f861e 按完整 SHA 核验：主 CI 37154235065、Desktop 37154235119 均 completed/success，
Verify、Kubernetes Operator、Windows 与 macOS 全部成功。它对应 progress 275
截图修复，不替代本次场景提交的 CI。

## 剩余边界

真实 OTP 交付、企业 IdP 的 MFA/ACR/注销、支付/客户 SPA、目标 Linux/多 Node 长稳、
客户视觉数据集、目标云/供应商证明和许可证权利人决定继续保留。完整稳定区域
Event Watermarks/风险授权/动作/独立 Outcome 仍未闭环，新增商品 Adapter 的准备
窗口仍可达 90 秒，不计低延迟完成。整体十一项目标 active。
