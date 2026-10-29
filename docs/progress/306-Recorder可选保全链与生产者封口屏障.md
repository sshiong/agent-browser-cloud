# Recorder 可选保全链与生产者封口屏障

> 日期：2026-10-29
> 基线：6f4fc767812fa7f11710ab04fba8627c4a405040
> 状态：Recorder可选路径代码、本地回归与完整Integration通过；PG权威准入/默认启用/交接仍缺。

## 录制链

RecordingSpec 增加内部可选 RecordingUploadContext，携带精确 Scope 与 Journal。
开始前核对 Tenant/Profile/Session、Operation前缀和正Epoch，使用其预定Recording ID；
默认Node注册显式传None，不增加环境开关、不宣告新能力，也不接受公共API直接配置。
该上下文本身不是PG授权，正式Node取得/核验权威准入仍未实现。

显式选择保全路径时，真实SegmentWriter在flush/sync后调用独立held upload，再
FULL事务保存Receipt，最后发送Helper ACK。任一步失败即停止写入链，不回退普通上传；
ACK失败时暂存文件保留。既有8帧有界队列与顺序提交保持，不引入无界异步上传。

Journal从经过校验的第0段回执读取Archive Scope Hash。新增客户端内部finalize入口
先持久准备原请求，再复用统一恢复/云证明校验，完成本地不可变结果提交后返回内部
Manifest元数据；没有额外重复RPC，也不把本地未提交的云成功当作已完成。

## 生产者屏障

write_segments只返回PendingRecording计数，不再提交Manifest。run_recording必须
等待capture与writer两个JoinHandle都成功完成，才统一finalize。writer先结束时先
通知停止capture并等待清理；writer panic也不提前返回遗留采集任务。capture/写入失败
不会发布完成Marker。正常路径和可选保全路径均使用此顺序。

这证明本进程两个任务的汇合，不是其他Node或旧版本Helper静默证明。重启后的自动
恢复调度、持久生产者生命周期和全局部署资格仍缺，不能把本地join当成跨Node可解除依据。

## 验证

- helper-client12、Journal16、Recorder19，共47项通过；常规运行有1项真实Chrome
  用例ignored，随后指定已安装Chrome单独执行，1项通过。新增4项：capture清理被
  人为延迟时writer完成也不能越过join；writer panic仍停止/等待capture；ACK拒绝时
  Journal已提交且源文件保留；封口RPC前准备已持久化且Summary返回前结果已提交。
- 新Recorder用例使用真实Unix Socket/SQLite、模拟Helper和合成段元数据，不是真实
  像素/OCR/MinIO保全录制端到端证明。单独Chrome用例验证导航/取消后的页面恢复与
  截图遮罩，不能替代保全录制端到端测试。
- OrbStack预检通过后，真实MinIO专用Gate完整14项库+4项IPC=18通过。此Gate覆盖
  Helper/Journal/统一恢复组件；未把新Recorder接真实Chrome→Helper→MinIO。
- 全Workspace/全部targets严格Clippy、格式/diff通过。
- 完整make test-integration最终成功，含6项无服务回归与正式Java/PG/Rust/MinIO链；
  advanced/high-level/actions、截图/文件、Recording播放、物理删除/版本删除、原生
  mTLS Hold与UNKNOWN拒绝、frame redaction等最终布尔断言为true。它使用受控CDP
  和隐私扫描协议夹具，不是客户网站、真实OCR或生产认证。

私有日志 /tmp/agentbrowser-recorder-hold-unit.log、
/tmp/agentbrowser-recorder-hold-chrome.log、/tmp/agentbrowser-recorder-hold-clippy.log、
/tmp/agentbrowser-recorder-hold-minio.log、
/tmp/agentbrowser-recorder-hold-integration-final.log。

## 首轮完整Integration失败与边界

首轮在高层WAIT获取snapshot处HTTP422失败，尚未进入后续Recording断言，没有SQL
死锁证据；curl -f没有保存错误体，确切拒绝原因未确认。未据此改动服务端安全门禁。

该测试现仅在422 + AGENT_BROWSER_PERCEPTION_REJECTED + BROWSER_STATE_STALE
精确匹配时有界等待新的权威snapshot（最多40次、每次请求最多10秒）；其他422、
409/503、网络错误立即失败，不拿旧Snapshot规划动作，不扩展为通用写入重试。
新增3项stub回归验证stale后成功、预算耗尽、其他拒绝/传输失败均不隐藏；加上已有
3项事务回滚重试回归，make test-integration先执行6项再走完整产品链。

最终完整重跑成功，但本轮stale重试次数为0，因此不能断定新增等待已复现并解决首轮
具体422。保留首轮失败日志 /tmp/agentbrowser-recorder-hold-integration.log；未拼接
两轮结果或跳过断言。未来再次出现非匹配拒绝仍须失败并定位。

## CI、清理与剩余

基线6f4fc76主CI40726646167及Desktop40726646122精确SHA所有Jobs成功（含Operator/
Windows/macOS）。Verify日志确认统一恢复失败续行、真实封口IPC、UNKNOWN原生
拒绝，以及Integration的3项无服务回归实际执行。progress305已补收尾。本提交8b27080主CI40817340480、Desktop40817340494
精确SHA所有Jobs成功；Verify日志已确认新生产者屏障、panic清理、ACK失败保留与
完整链原生UNKNOWN拒绝，未只依赖绿标。

本轮MinIO与两轮Integration的专用容器/网络均已回收，确认构建digest来源、无容器
引用后删除两个测试MinIO/mc镜像。原有8容器保留；另有一个2026-08-13创建的空
Integration网络，早于本任务测试，未将其冒认为本轮资源删除。无全局prune；见
/tmp/agentbrowser-recorder-hold-cleanup.json。

仍缺PG活动录制/上传权威准入、正式Node取得上下文与默认启用、真实Chrome→扫描→
保全录制端到端验收、恢复调度/预算、Seal到Hold显式安全承接、旧非感知写入器部署/
Placement围栏、跨Node及目标云Gate。原十一项其他真实外部系统、客户Replay、Linux/
Region/组织与许可证Gate继续保留，整体目标未完成，未满足V16生产发布。
