# Recording 保全策略 API、四语言 SDK 与 UI 状态闭环

> 日期：2026-10-24
> 基线：237920ef9c2786ada66703dc845b4bfde133801f
> 状态：策略/证明状态已接正式 API/SDK/Web/Tauri；真实完整产品链仍须验收。

## 正式契约与权限

- 原策略 PUT 新增 optional cloudHoldRequired：省略时保留已有配置，初次默认 false；
  仅允许 Recording 使用 true。沿用 ADMIN（Tenant/Security/Platform Admin）和当前
  Principal 的 Tenant/Actor，不接收客户端提交的 Tenant、Proof 或对象位置。
- 在既有全局策略锁与同一事务中保存期望、投影 authority/job、追加明确的
  SAVE_DESIRED_POLICY 审计。审计失败会回滚策略和队列投影；保存 SUCCEEDED 不表示
  云端 ON/OFF 成功。未完成全部当前 OFF 证明前拒绝关闭云端管理，V135 继续兜底。
- RetentionPolicy 增加 optional cloudHoldRequired、Recording 专属 recordingCloudHold。
  后者只含期望模式、PENDING/UNKNOWN/已验证 ON/OFF 数量及核对是否耗尽，不返回对象
  Key、URL、Version ID、像素或凭据。计数与耗尽判断在同一 SQL snapshot 中读取。
- LOGICAL_ONLY、NOT_REQUESTED、NO_RECORDINGS、PENDING、UNKNOWN、VERIFIED 显式区分。
  VERIFIED 要求当前托管、未删除集合全部匹配期望模式；从未托管的 OFF 不称为已验证，
  没有记录不称为成功，也不声明未托管对象或进行中录制字节已经受原生保全。
- OpenAPI 保持 **255 Operations**，增加 RecordingCloudHold 后为 **358 Schemas**。
  四语言生成模型、导出和 Manifest 已由生成器同步，未手工修改生成物。

## UI 与事件

- Web/Tauri 共享管理员表单可新建和编辑录制保留策略，展示当前期望与证明状态。
  保存后显示等待/未知，只有正式回执投影符合当前期望才显示“已验证（托管录制）”。
- 新建先保存逻辑策略并读取服务端能力；旧服务端无 summary 时不开放云开关。
  无权限不提供表单；待验证/未知/已验证 ON 仍存在时，关闭云端管理的控件不可用。
- UNKNOWN 耗尽明确提示“保全仍保留”，不渲染为失败或已解除。录制详情原 Legal Hold
  徽标改为“平台保全”，提示云端证明见企业运营，避免混淆平台删除联锁与云端回执。
- V136 对 job 的 state/attempt/failure_code 变化追加 payload-free RETENTION 事件，
  覆盖 authority 仍 UNKNOWN 时的核对/耗尽细节。沿用现有可续传 SSE 与正式 API 重取，
  未增加轮询。新增按 Tenant 查耗尽请求的部分索引。

## 验证证据

- 真实 PostgreSQL 专用 Gate **14 原删除 + 13 Hold/API = 27 passed / 0 failed /
  0 skipped**，执行 V99/V128/V134/V135/V136。新增用例验证旧请求省略字段保留配置、
  初次默认 false、PENDING 不等于已验证、OFF 证明后才可关闭、非法数据类拒绝、
  Tenant 隔离/空集合、同 UNKNOWN 状态下耗尽事件，以及策略/authority 随审计失败
  一起回滚。服务测试显式使用事务边界，未把普通对象的注解误当作已开启事务。
- 常规 Java **655 passed / 0 failed / 27 skipped**，环境用例由上面专用 Gate 实际
  执行。API 刚加入审计时曾误引用字段名导致编译失败，已更正为既有 auditService，
  没有绕过审计或减少断言。
- 前端 **150 Vitest passed**（含新增证明显示/权限/API 测试），附带 noVNC 检查、
  TypeScript/Vite 正式构建、ESLint/Prettier 通过。
- 真实 headless Chrome + Playwright 1.64.1 运行已提交的 owned UI 脚本：解除期望但
  保持等待→UNKNOWN/耗尽不释放→已验证 OFF 后才允许关闭管理，完整交互通过。
  API/SSE 明确为本机合成夹具，所有非本机请求阻断、Service Worker 禁用，不访问
  真实后端或账号。截图已目视检查，无页面异常。这不是实际 S3 或后台服务端到端。
- Buf/Redocly 契约检查、四语言生成/Manifest 一致性、四语言 SDK 测试与八件本地发布
  包构建通过；未发布到外部 Registry。Java 本地复用先前真实 Linux 生成且 Proto
  未变的产物，未将 -x generateProto 宣称为新生成。本提交 CI 仍须验证完整生成。

私有日志：`/tmp/agentbrowser-hold-api-postgres-v3.log`、
`/tmp/agentbrowser-hold-api-java-full.log`、`/tmp/agentbrowser-hold-ui-tests-final.log`、
`/tmp/agentbrowser-hold-ui-build-release.log`、`/tmp/agentbrowser-hold-ui-browser-final.log`、
`/tmp/agentbrowser-hold-api-contracts-sdk-check.log`、`/tmp/agentbrowser-hold-api-sdk-test.log`。
截图 `/tmp/agentbrowser-hold-ui-unknown-final.png`；Browser 脚本及独立可选依赖文件位于
`tests/e2e/`，make test-recording-hold-ui 接受已启动的本机测试 Console 端口。

## V136 部署与回退

Additive 新索引/事件函数/触发器，lock_timeout=5s、statement_timeout=30s；超时让
完整 Flyway 事务回滚，不删 job 或解除保全。索引在新 job 表上创建，不回写对象状态，
但仍须在目标规模核对表大小、写入率和锁等待；大型历史队列的在线索引方案须在目标
环境验证，不能把本机小型 Gate 当作生产索引耗时证书。回退应用时保留这些对象和
V135 联锁；旧 Worker 的状态写入也产生相同提示。不得通过删除未知请求完成回退。

## 未完成范围

1. 正式 Control Plane/JPA 审计→实际 Rust Node→Helper→MinIO 的完整 mTLS ON/OFF
   验收及失败/未知注入，本次 Gateway/SQL/UI 分层测试不能替代它。
2. 进行中录制字节在上传时生效的原生 Hold；当前托管集合从最终 Manifest 投影开始。
3. 原 Node 不可用后的正式重绑定、批量/长稳/规模，以及 PREPARED 长期未知时所需
   远端静默/核对证据；不能以时间流逝推断晚到 OFF 已消失。
4. 目标账户 IAM/Object Lock/Legal Hold、跨 Region、客户数据/视觉/外部供应商、组织
   和许可证决定，及原十一项目标其余 Gate。当前仍不作 V16 发布或客户数据处理结论。

基线 237920e 的主 CI **39912590714**、Desktop **39912590729** 最终精确 SHA、全部
Job 已核验成功，含十项 PG Worker、十四项删除与八项 SQL Gate、Integration/完整
GameDay、Operator、Windows/macOS；progress 294 已收尾。本 API 提交须自己的 CI。
