# Agent Browser 文件上传下载权威闭环

> 日期：2026-08-24
> 状态：仓库内实现、本地完整 Gate 与 GitHub CI/Desktop Gate 均已通过

## 目标与边界

本切片补齐 Agent Browser 的粗粒度文件上传和下载观察能力。Agent 不操作系统文件选择器，
也不读取 Browser Node 本地路径：上传通过正式租户 API 进入当前 Session 的精确 Node，随后由
Browser Node 使用 CDP 把已验证文件设置到目标 `input[type=file]`；下载只投影真实浏览器事件
产生的安全元数据。

AUTONOMOUS 下的上传、下载等待、状态推进和有界失败重试保持静默。只有 OTP、设备确认等确实
需要真人提供的信息缺失时才通知一次；操作员可以把信息发给 Agent 代填，也可自愿进入 VNC，
系统不强制人工接管。文件能力不会绕过支付、账号安全或其他高风险确认。

## 上传权威链路

- `POST /api/v1/sessions/{sessionId}/agent/files` 接受单个 Multipart 文件，要求 `OPERATE`
  权限、幂等键和精确 `stateVersion/stateHash/targetRevision/elementId`；单文件上限 64 MiB；
- Control Plane 只在 PostgreSQL V109 保存租户、Session、上传状态、文件名、MIME、大小和
  SHA-256。文件字节、本地路径和页面 URL 不进入数据库、公共 API 或审计；
- 文件通过 mTLS streaming RPC 直接送往当前 Placement Node。Node 重验 Tenant、Session、
  Coordinator Lease、Term、Route Epoch 和 Browser Context，一次只允许一个 staging 流；
  每 Session 最多保留 32 个文件、总计 256 MiB；
- Node 使用 `0600` 临时文件、增量大小/SHA 校验、`fsync + atomic rename`，失败会删除残留
  part。上传 ID 和同 ID 元数据均严格校验，不允许利用文件名逃逸 staging 根目录；
- staging 成功后才创建持久 Operation/Outbox。Node Journal 执行 `AgentFileUploadCommand`，
  允许 State 单调前进但拒绝版本回退及同版本 Hash 冲突，并始终要求精确 Target Revision；
- State Collector 通过 `Runtime.evaluate` 定位受控隐藏 file input，再使用
  `DOM.describeNode + DOM.setFileInputFiles` 设置文件，不打开 OS chooser。CDP 成功及目标重采
  证据成立后，Operation 与 PostgreSQL 上传状态才提交；Runtime 停止/崩溃时清理 staging。

## 下载权威投影

- Browser Node 持续读取真实 `Network.responseReceived`、`Browser.downloadWillBegin` 和
  `Browser.downloadProgress`，把同一下载关联为稳定 `downloadId`；
- Full/Diff Browser State 只包含安全文件名、MIME、总大小、已接收大小、进度、状态和有界
  时间戳，不包含 URL、本地路径、响应 Header 或文件内容；历史有界为 32 条；
- 观察器断线时，正在下载的条目转为 `INTERRUPTED` 且 freshness 为 false，不把缺失事件冒充
  完成；Control Plane 把投影写入现有 PostgreSQL Browser State；
- List 与最多 30 秒的 Wait API 只重读 PostgreSQL，单实例最多 32 个并发等待，不轮询或直连
  Browser Node。Web 与 Tauri 复用同一个 API Client 和类型。

## 契约、兼容与验证

- OpenAPI 与 TypeScript/Python/Go/Java SDK 已同步为 `230 Operations / 306 Schemas`；
- Protobuf additive 增加 stage streaming RPC、上传命令/失败事件、Download Full/Diff 字段；
  N/N−1 Gate 锁定 V109、字段 tag 和旧 Node 缺少能力时的 fail-closed 行为；
- Browser Node 最终 `cargo fmt --check`、Workspace Clippy `-D warnings` 与 Workspace 测试通过；
  Web 共 117 项测试通过，Control Plane、Python Worker、Go Provider 及完整
  `make test/lint/build` 已通过；
- TypeScript SDK 覆盖 230 Operations，四语言 SDK 覆盖 230 Operations / 306 Schemas；
  OpenAPI、Desktop、N/N−1 Gate 均通过；
- 最新完整 PostgreSQL/Redis/MinIO/mTLS/Chromium Integration 输出
  `agent_browser_files=true`，显式验证隐藏文件输入、上传提交、三次静默重试、跨租户拒绝、
  下载完成/freshness/list/wait，以及数据库和审计不含文件内容或 Node 本地路径。
- 实现提交 `8663157`；首轮 CI 发现三语言 SDK 测试仍固定旧的 226 Operation 基线，修复提交
  `2b6ed3c` 将 Python/Go/Java 统一为 230 并以 `make test-sdk` 全量复验。最终 GitHub `ci`
  run `32704051504` 已通过 Verify、供应链、完整 Integration、Object Storage/Recording
  GameDay 与 Kubernetes Operator E2E；`desktop` run `32704051540` 的 Windows/macOS 均通过。

## 剩余边界

下一切片继续局部 Screenshot 和受治理 Evaluate；Select/Press/Drag/Drop/Swipe、通用
Mouse/Keyboard/Touch 与 AgentClipboard/UserClipboard 显式受控 Bridge 仍未完成。当前下载
能力是状态观察，不等于向 Agent 返回下载文件内容；若以后需要内容读取，必须新增用途绑定、
短期、一次性且可审计的授权链路。
