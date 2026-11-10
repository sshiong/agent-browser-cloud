# 可选 MCP Gateway 开发计划

> 2026-11-10；用户已确认开始开发。此文为实施计划，不是已完成功能声明。

## 产品边界

默认核心部署不启动 MCP、不监听 MCP 端口、不要求 MCP 凭据。Compose 使用 mcp profile；
Kubernetes 使用独立 Deployment/Service 和启用开关。停用 Gateway 不删除浏览器环境。
Gateway 仅调用正式控制面能力；连接权限、撤销、配额、环境归属和审计由控制面执行。
禁止把 MCP 会话 ID、可修改请求头或 URL 中的参数当作连接身份。

## 使用流程

管理员创建 Agent 连接，配置名称、有效期、允许工具、可保留环境总数和同时运行数。
用户为客户端配置 MCP URL 并完成授权。Agent 可以查额度、列表、创建、查询、启动、停止、
删除环境和查询异步 Operation。默认仅可操作该连接创建的环境；跨连接共享需后续显式授权。
停止保存 Profile，删除环境与删除 Profile 分开；运行环境须先正常停止才能删除。

## 后端规则

每条连接持久保存 tenant、认证主体绑定、权限、上限、有效期和撤销状态。
创建先在 PG 事务内锁连接并预留总量；启动预留运行量。CREATING/STARTING/STOPPING 和
结果 UNKNOWN 不得因超时或 Gateway 断线直接回收名额。只有权威结果确认才释放。
同一幂等键匹配同一请求，重复调用不得再占名额；不同请求内容必须冲突。
配额只限制该连接，仍须经过租户整体容量、RBAC、资源调度和风险策略。

## 工具与部署

首批 browser_get_quota/list_environments/create_environment/get_environment/
start_environment/stop_environment/delete_environment/get_operation。
采用官方 MCP SDK、远程 Streamable HTTP；明确只读、幂等和破坏性 annotations，结构化
输出，分页有界。创建与生命周期返回 environmentId/operationId/status，Agent 可继续查询。
连接器复用同一 MCP 服务。浏览器 snapshot/find/inspect/act/wait/handoff 为下一批工具。
远程部署启用 HTTPS、Origin 校验与授权发现；本地默认仅绑定 loopback。
认证实现须核验 issuer/audience/subject/scopes，不能转发未经验证或目标资源不符的 token。

## 开发顺序与验收

1. PG 连接/额度/归属模型和并发预留规则：先真实 PG 测试，再接 API。
2. 管理 API、连接认证与正式 Session/Operation 同事务绑定；同步 OpenAPI、四语言 SDK。
3. Gateway 工具与协议测试；不提供绕过控制面额度的写工具。
4. Web/Tauri 共用连接管理界面与可撤销授权。
5. Compose profile、Kubernetes 开关、运维文档与真实 Agent 接入测试。

必须验证：默认部署无 MCP 服务；启用后可连；并行创建/启动不超限；超时重放不重复创建；
跨租户/跨连接拒绝；撤销立即拒绝；停止/删除只在权威完成后归还名额；Gateway 重启不丢
额度；关闭 Gateway 保留环境。现有默认 Compose/个人安全部署回归必须保持通过。

## 本轮实现状态

开发已开始：首批落地连接和环境名额的数据库结构及数据库级并发额度防线。
后续认证、正式 API/生命周期绑定、Gateway、管理 UI 和部署 profile 尚未完成，当前不可
对外宣称 MCP 已可接入。此附加功能不取消原十一项持续目标及现有 CI 修复工作。

迁移采用新建表与函数，不修改既有 Session 行。回退应用时保留表，禁止删除已有连接/名额
记录；新功能未接入口前不产生业务流量。上线需单独核对 Flyway 锁等待与实际表规模。

首批V139及make test-agent-connection-quota已完成真实PG并发创建/启动、撤销、过期、身份与删除态检查，见[progress315](../progress/315-可选MCP开发启动与连接额度数据库基础.md)。后续阶段尚未完成。
