# Personal Secure 单机部署闭环

> 日期：2026-09-19
> 范围：A22 仓库内单机个人安全部署层；不替代 V16 生产发布 Gate。

## 问题

默认 Compose 是开发环境：允许本地身份头、开发凭据和固定 Secret，也没有把 Executor、Reviewer、
Outcome Verifier、Vision Worker 作为真实隔离进程完整启动。把它直接放到公网反代后面会错误地把
开发便利性当成安全边界；只补 TLS 或登录页并不能修复该问题。

## 实现

- 新增独立 `deploy/personal-secure/`，不改变默认开发 Compose。唯一宿主机端口固定绑定
  `127.0.0.1`；核心网络为 Docker internal network，文档只允许本机访问、SSH 本地端口转发或
  经评审的私有网络，明确禁止公网反代。
- `personal-secure init/check/up/down` 形成一键入口。初始化生成彼此独立的 PostgreSQL、Redis、
  Remote Desktop Ticket、Agent Capability、Action Payload、Audit Signing 与 Profile Archive
  Key，并生成 Control Plane、Browser Node 和 Worker Gateway 的内部证书。Secret 通过受限宿主
  文件复制到按服务隔离的 named volume，不进入 Compose 环境或 Git。
- Control Plane 使用非 local 环境、OIDC Issuer + 专用 API Audience、RBAC、Redis 密码和文件型
  Secret；Browser Node 与 Control Plane 使用双向 TLS。Node、Control Plane 固定非 root UID，
  应用容器默认只读根文件系统、Drop All Capabilities 与 `no-new-privileges`。
- Agent Executor、Reviewer、Outcome Verifier、Vision Worker 以四个真实进程启动，使用精确单一
  Worker Role 的独立 JWT。bootstrap 校验 Issuer、Audience、Role 与至少一小时剩余有效期，正式
  Control Plane 再校验签名、Issuer、Audience、MFA/Role 规则。
- Browser Node 不接外网，只能经 Network Helper 与独立 Squid 出口；直连策略在控制面和 Node
  双重关闭。对象存储必须使用 HTTPS，Profile Checkpoint 继续使用已有 AES-256-GCM Envelope。
  Reviewer/Outcome/Vision 只接受 HTTPS 模型端点和精确 Host，Vision 另绑定截图对象 Host。
- Web 镜像补齐完整生产 OIDC 构建参数，并在构建阶段只替换 CSP 的 OIDC Origin；Nginx 的
  `$host/$uri` 等运行变量保持不变，使只读根文件系统无需启动期写配置即可正常启动。
- Node Ticket、Storage Helper S3 Secret、Control Plane 数据库/Redis/平台 Secret 新增文件读取，
  均拒绝相对路径、符号链接、空值或异常大小；公开 API、OpenAPI 与 Protobuf 未变化。

## 验证

- `make test-personal-secure`：4 项通过，覆盖 loopback-only 拓扑、完整四 Worker 链、OIDC CSP、
  Control Plane 文件 Secret、随机独立密钥、内部证书 SAN、JWT Issuer/Audience/单角色/有效期与
  `docker compose config --quiet`。
- Web Production 镜像在 `read_only + cap_drop=ALL + no-new-privileges` 下真实启动；HTTP 响应 CSP
  只增加配置的 OIDC Origin，Nginx `$host` 变量保持未展开。
- Agent Worker、Control Plane、Browser Node 与 Web Console 四类镜像均执行真实 Docker build；
  Rust 定向测试与完整 Workspace 测试通过。
- `make ci`、`make build`、`make test-desktop lint-desktop build-desktop` 和完整
  PostgreSQL/Redis/MinIO/mTLS/Chromium `make test-integration` 均通过；Integration 保持高层
  Agent 工具、Profile 加密、Prompt Injection 来源权威、Worker long-poll/notify 等既有能力。
- 实现提交 `8725d56` 已推送至 `main`；GitHub `ci` run `35438884436` 的 Verify、供应链、完整
  Integration、Object Storage/Recording GameDay 与 Kubernetes Operator E2E 全部成功，
  `desktop` run `35438884442` 的 Windows/macOS 均成功。

## 边界

该配置关闭 A22 的仓库代码产品化缺口，也为 A02 提供不复用 Local 身份的个人部署路径；它不把
Local Mode 变成公网安全模式，也不宣称单机 Compose 通过生产认证。真实企业 IdP/S3/模型/出口、
宿主机全盘加密、外部 Secret 轮换、备份恢复、KMS/HSM、多 Region、组织 Threat Review 与发布
审批仍是目标环境或组织 Gate。A01 的默认开发 Compose 完整 Worker 链随后由 progress 189 闭环。
