# 跨 Document 网络静默与未完成写入隔离

> 日期：2026-10-02
> 范围：Browser Node 内部 CDP 网络归属、当前页动作静默、全局事务保护。

## 复现与依据

进度 243 的公开回放在 SPA 输入前失败，同一标签页还保留旧练习站的 Socket.IO POST。
当时未采集 Loader 关联，不能把该请求视为已完成。本轮使用仅绑定 loopback 的自有
页面和真实 Chrome：第一页面发起合成 keepalive POST，本地服务暂不响应，随后导航到
第二页面。八秒只读观察得到两个已提交 Document，一个 POST 持续在途，其 Loader
与第二页面不同。没有访问外部站点、支付服务或客户数据。

[Chromium 官方 CDP 契约](https://raw.githubusercontent.com/ChromeDevTools/devtools-protocol/master/json/browser_protocol.json)
说明 `frameNavigated` 将 Frame 关联到已提交 Loader，而开始导航仍可能取消；同
Document 导航保持原 Loader，Worker 的请求 Loader 可以为空。因此归属不能仅凭
导航命令、页面 load、请求类型或持续时间推断。

## 修改

- 精确 CDP Session / Frame / Loader 建立内部 Document 关联，主 Frame 已提交导航
  才更新当前 Document；子 Frame 必须有已知父 Frame，才继承主 Document。
- 当前页动作静默只排除**已证明属于其他 Document**的请求。缺失 Frame/Loader、
  未知父 Frame、尚未提交的新 Loader 和 Worker 请求继续阻止静默。
- 请求先于 `frameNavigated` 到达时，只有随后精确 Frame/Loader 匹配才补足归属。
  恢复历史 Loader 时，其未完成请求自动重新计入当前页。
- 全局请求、上传、下载、表单、SPA 写入、支付/安全和关键事务计数不按 Document
  过滤。旧写入仍阻止全局网络就绪和相应安全点，导航不会结算该写入。
- Frame 上下文最多 512 个，单个身份最多 128 字节。缺失、异常、超限或主 Frame
  分离时退回全请求判断；Session 分离清除上下文。身份只在 Node 内存使用，未增加
  API、数据库、审计、Protobuf 或日志字段。

## 验证

- 完整 CDP WebSocket 回归在旧代码上失败；修改后新页 Quiet ≥250 ms，旧 POST
  的全局请求/SPA/支付/关键事务各为 1，全局 Quiet 为零。历史 Loader 恢复后同一
  请求重新阻塞当前页。
- 新增事件顺序、同 Loader、未知请求、子 Frame、历史恢复、上下文超限、非法身份
  和 Session 清理回归。Safety Monitor 的 13 项默认测试通过。
- 自有真实 Chrome keepalive POST 回归验证新页静默、全局 POST 保留及全局 Quiet=0
  同时成立。包含既有真实采集与 Tab 测试的 State Collector **49 项全部通过**。
- Rust Workspace **186 项通过、6 项环境测试默认忽略**；本轮显式执行了其中三项
  State Collector 真实 Chrome 测试。Rust 1.99 Workspace/all-targets 严格 Clippy
  通过，Node 已重建。
- Replay Gate 18 项、文档 Gate 7 项、双语 README 目录及链接检查通过。
- 前一提交 `b06c0520dd958a80691737cb7f8746946aba0cad` 的 CI `36980511909`
  与 Desktop `36980511921` 全部通过，含 Integration、Object Storage GameDay、
  Kubernetes Operator 与 Windows/macOS；不能代替本轮提交的 CI。

## 公开矩阵与剩余边界

重建 Node 后，以原白名单和 Dataset 复跑完整 17 例；只读 CDP 另采集散列后的
Frame/Loader 关联，不记录 URL Query、Header、POST 正文、OTP 或 Token。
第一轮完整 17 例已通过：Session `ses_01eb22819bfe444f`、Validation
`val_4624ec6c30ef409f844a`，Chrome `154.0.8037.95`，Dataset Digest
`sha256:60c6f170bfa2160a6bfbeb29bc465a70051c8b830fe36f7d0713728bfd930da3`。
日志位于私有目录 `/tmp/agentbrowser-full17-loader.rLDY70/`，观察器也已退出。
81 次观察中未捕获跨已提交 Document 的旧 POST，不能据此追溯历史失败或认定本轮
公开回放覆盖了该特定时序；该时序由自有真实 Chrome 和 CDP 回归独立验证。
第二个独立 Session `ses_c5f6ec04e6904c6b` **未通过**：OTP 入口页保持 `loading`，
稳定状态等待超时。最后 API State 为 COMPLETE/FRESH，但 Document 未就绪，Network
Quiet 约 13.4 秒，DOM/Layout/Focus/Route Quiet 均超过 40 秒，仍按既有规则拒绝推进。

该轮 52 次独立 CDP 观察中，28 次看到已提交旧 Loader 的练习站 Socket.IO POST
仍在途，最长约 113.7 秒。它确实属于前一个 Document；旧写入没有被判为完成。
UTC 08:40:50 的当前 OTP Document 已响应 200 但约 29.3 秒未完成，下一次观察出现
Document `loadingFailed`，未取得 `loadingFinished`。新页未就绪仍使本轮失败，不能
将网络静默当作 Document 已加载，也不能据此推定传输失败根因。

第二轮日志位于 `/tmp/agentbrowser-full17-loader-repeat.9nonVh/`，两个回放及观察器
均已退出，Harness 已清理运行资源。一次完整通过、一次失败，连续稳定性仍未达成。

此修改没有忽略 Socket.IO、放宽交易确认或自动重发失败输入。进度 241—243 的历史
请求没有完整 Loader 证据，不能统一追溯归因。当前 Document 内的长轮询、未证明
归属的请求和真实未完成写入仍可能阻塞自动化。完整 17 例连续通过、小时级/目标
Linux 长稳、真实企业 IdP、外部 OTP、支付、客户 SPA，以及云/供应商/许可证决策
仍是独立 Gate。
