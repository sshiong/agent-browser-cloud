# 测试 Chromium 子进程端口归属与启动诊断

> 日期：2026-10-04
> 基线：`054fd710b12706e82e98a4954a148f7e7776703e`
> 范围：测试夹具的 CDP socket 启动；没有修改生产 Browser Node、权限或公开 API。

## CI 实际结果

按完整 head SHA 核验：`054fd71` Desktop `37145307908` 的 Windows/macOS、主 CI
`37145307912` 的 Operator 均 success；主 CI 的 Verify failure。失败发生在 Replay 的
`test_snapshot_pair_reload_and_tab_have_authoritative_identities`，报告连接拒绝和通用
fixture startup failed。后续 Build、Integration 与 Object Storage GameDay 均 skipped，
不能把该提交计为完整 CI 通过。

原测试先由父进程绑定随机端口，再关闭预留 socket，随后交给子进程重新绑定；这一
交接存在竞争窗口。stderr 又被丢弃，未保留早退/超时/绑定错误类别。本轮没有原始
子进程失败证据，不能认定该次 CI 的唯一原因就是端口竞争，也没有盲目重跑失败 run。

## 修正

测试 fixture 支持 `--remote-debugging-port=0`：子进程直接持有 OS 分配的监听 socket，
绑定成功后发布私有 Profile 下的 `DevToolsActivePort`，模式 0600；CDP 元数据使用实际
端口。既有 Integration 固定端口方式继续受测。

socket 身份测试读取该文件并检查真实 `/json/list`，原五秒启动上限不变。子进程 stderr
写入私有临时文件；对外失败只含 FIXTURE_EXIT/STARTUP_DEADLINE、退出码，以及固定
PORT_IN_USE/ARGUMENT_REJECTED/UNKNOWN 分类，不输出原异常、参数、路径或正文。
没有新增启动重试或隐藏测试失败。

## 验证

- 旧 fixture 面对新的子进程端口归属测试，在原五秒预算内失败；新 fixture 通过。
  这证明旧夹具缺少动态端口发布，不追溯证明此前 CI 失败的具体原因。
- 两个同时存活的 fixture 使用不同端口，真实 CDP 地址与发布端口一致。
- 保持一个真实 socket 占用固定端口，fixture 提前退出；测试精确断言
  `FIXTURE_EXIT code=1 kind=PORT_IN_USE`，不泄露原错误。
- 完整 Replay **42 tests / OK**，Python 编译、shell 语法与 diff 检查通过。
- OrbStack 的 Linux/Python 3.13 容器在 network none、只读源码挂载下重复上述三项
  socket 回归二十次：**60 tests / OK**。它不是 GitHub Ubuntu/Python 3.12 runner 的替身。
- N/N−1 兼容检查与完整 OrbStack Integration **exit 0**；原固定端口启动、Profile
  生命周期与业务断言继续执行。测试期间并行编译的后续 Node 改动没有用于本次
  Integration 进程；本项证据只对应测试 fixture 修正。

私有日志：`/tmp/agentbrowser-fixture-port-{old-regression,replay,linux,integration}.log`。
Docker Gate 前核验 Running / orbstack / OS=OrbStack。

## 剩余边界

原失败 run 保持失败，新提交须单独核验完整 CI。公开十七例连续稳定性、旧文档迟到
请求的有界归属证明、完整稳定区域 Event Watermarks/风险授权/动作/独立 Outcome、
实际 OTP 交付、企业 IdP/支付、目标云/供应商与权利人许可证决定继续保留，整体目标
active。
