# Recording Legal Hold 的动态 Helper 能力准入

> 日期：2026-10-23
> 基线：3a903cc2d8524932b214dc1a233149d5f95f3f00
> 状态：协议能力探测已完成；控制面保全产品链仍未完成。

## 实现与语义

- Helper IPC Schema 保持 1。Ping 成功响应新增默认 false 的
  `recordingLegalHoldSupported`；只有本版本执行器存在且配置了 Object Archive 才为
  true。旧 Helper 缺字段、没有 Archive、拒绝响应均不能获得能力。
- Client 沿用 Schema/Request ID/成功状态校验，每次探测重新连接，不缓存上次结果。
- Node 每次容量心跳在 500 ms 外层预算内探测当前 Helper，覆盖配置较长 Socket 超时的
  情况。只在精确成功时设置 `recordingLegalHold=versioned-cas-journal-v1`；缺 Helper、
  旧响应、错误关联、断线或超时均设置 unavailable，覆盖既有同名 Label，保留其他 Label。
- 这是可派发协议的能力声明，不是目标云 IAM/Object Lock 准入证明，也不是 ON/OFF
  已执行回执。已有 Legal Hold 策略不会因声明出现而变成云端已生效；控制面尚无生产者。
- 正式 REST/OpenAPI/SDK、数据库和对象存储权限均未改动；未新增存储写重试。

## 验证

- Client 通过真实 Unix Socket 连续接收支持、旧版、禁用、错误 Request ID、错误 Schema
  和拒绝响应，验证同一 Client 不缓存能力，也不接受未关联的声明。
- Node 通过真实 Unix Socket 验证支持后换成旧版/错误关联/挂起即撤回能力，五秒 Helper
  超时被外层预算限制；Server 消失与 Helper 缺失均不可用，其他 Label 保留。
- 真实 Helper 没有 Archive 时仍可 Ping，但不会宣告 Hold。原 MinIO IPC 场景新增先
  探测再执行 ON→OFF，并保留错误 UID 拒绝、回执与解除后删除断言。
- Rust Workspace **250 passed / 0 failed / 14 ignored**；四个相关 Crate 全 Target
  严格 Clippy 通过。四项真实 MinIO Hold 测试单独执行，**4 passed / 0 failed**。
  N/N−1 Gate、格式和文档检查通过；没有把常规 ignored 当作环境测试通过。
- 新 Node 测试初次运行因 macOS 临时目录过长触发 SUN_LEN，已改用短的唯一 /tmp
  Socket 路径；这是测试夹具路径修复，生产路径未改动。

私有日志与退出记录：`/tmp/agentbrowser-hold-admission-workspace.log`、
`/tmp/agentbrowser-hold-admission-clippy-final.log`、
`/tmp/agentbrowser-hold-admission-minio.log`；兼容证据
`/tmp/agentbrowser-hold-admission-compatibility.json`。

## 精确基线 CI

3a903cc 的主 CI **39903853043**、Desktop **39903853063** 已读取最终精确 SHA
及全部 Job：Verify/Java 生成编译、Build、供应链、PostgreSQL、Integration、完整
Object Storage GameDay、Operator、Windows/macOS 全部成功。进度 290 已收尾。
本次新增能力探测仍需自己的 CI；旧提交成功不替代新提交。

## 下一阶段与保留边界

仍须接入 PostgreSQL 权威队列、控制面 Gateway/Worker、不可变请求与未知结果恢复、
策略 API/四语言 SDK/UI、删除授权及迟到录制联锁、完整新 RPC mTLS 验收。云端已保全
只能由精确对象版本集合的执行回执证明，不能由策略保存或能力 Label 推断。
目标账户/IAM、真实云 Legal Hold、跨 Region、客户数据与组织/许可证 Gate 保留。

## 精确提交 CI 收尾

aa2fdcc2cb355d14ee20f8a925f7f9f7ad01586d 的主 CI **39906058046**、Desktop
**39906058041** 已读取最终精确 SHA、全部 Job 成功；Verify、扫描、PostgreSQL、
Integration/完整 GameDay、Operator、Windows/macOS 全部通过。证据位于
`/tmp/agentbrowser-hold-admission-ci-final.json` 和对应 Desktop JSON。
