# 可选 MCP Gateway

默认不部署、不监听端口。连接管理位于 Web/Tauri「设置 → Agent 连接」。管理员设置环境总数、
运行数、有效期和工具权限，创建后取得一次性显示的Bearer凭据；只能管理该连接创建的环境。

## Compose

```sh
docker compose up -d                         # 默认核心，无MCP服务
docker compose --profile mcp up -d           # 启用Gateway
```

地址为 `http://127.0.0.1:8130/mcp`。客户端使用远程HTTP MCP，添加
`Authorization: Bearer <一次性凭据>`。这是连接级不透明凭据认证，并非OAuth自动发现；需要
OAuth交互授权的客户端须另行适配，不承诺所有平台均支持静态Bearer配置。

```json
{"mcpServers":{"browsercloud":{"url":"http://127.0.0.1:8130/mcp","headers":{"Authorization":"Bearer <credential>"}}}}
```

创建环境后单独调用启动；启动/停止返回Operation，用browser_get_operation查询结果。
不确定的写结果使用相同幂等键重放。运行中不能删除，先停止。停止会保存Profile，删除不删除
Profile。过渡和未知状态保留运行名额；只有控制面权威终态才归还。

在设置的 Agent 连接中勾选 **直接操作网页**，然后给外部 AI 使用新签发的连接凭据。
该权限可用于连接自己创建的运行中环境。调用顺序如下。

1. `browser_snapshot` 获取当前页面摘要、区域稳定性、`targets` 和 `stateCursor`；
   大页面优先使用 `browser_find`（按名称/文本值/角色/ID 搜索目标）和 `browser_inspect`
   （按精确游标读取指定元素）减少 Token 消耗。
2. 首次打开网页时，用 `browser_navigate` 提交目标 HTTP(S) URL，并用
   `browser_wait_action_task` 等待完成。
3. 读取新快照或搜索目标，用 `browser_act` 提交直连动作。每个目标动作使用
   `elementId` 作为 `targetRef`，并传入 `targetRevision`。支持点击/双击/右键/悬停、
   勾选/取消勾选、非敏感公共文本输入/填充/清空/下拉选择（`TYPE_TEXT`/`FILL`/
   `CLEAR_TARGET`/`SELECT_OPTION`，凭据/OTP/PII/敏感输入框仍拒绝）、按键（`PRESS_KEY`）、
   原子鼠标拖动（`DRAG_TARGET`：可传终点 `endTargetRef`，同起点终点即原位长按，也可传
   `deltaX`/`deltaY` 像素偏移以原子拖动滑块，避免多轮思考导致松开失败，最长 5000ms）、
   触控滑动（`SWIPE_TARGET`）、滚轮（`MOUSE_WHEEL`）、滚动（`SCROLL`）、等待（`WAIT_FOR`）、
   新建/切换标签页（`OPEN_TAB`/`SWITCH_TAB`）与关闭原生弹窗（`DISMISS_DIALOG`）。
4. 用 `browser_wait_action_task`（有界等待 1—25 秒）或 `browser_get_action_task` 检查任务
   和动作结果。等待超时不取消任务，继续查询原 `taskId`。页面变化时重新取快照。

直连操作由外部 AI 决定下一步，控制面负责校验并直接派发。敏感输入和需要模型审核的
动作返回明确拒绝，已有的人工确认与部署配置的结果验证继续生效。

关闭Gateway：`docker compose --profile mcp stop mcp-gateway`。不使用`down -v`，不会清理
核心服务或环境。撤销单个连接立即拒绝其后续请求，无需停止Gateway。

## 远程与Kubernetes

远程通过HTTPS反向代理，配置精确MCP_ALLOWED_HOSTS与MCP_ALLOWED_ORIGINS，保留Authorization，
不要把凭据放URL。Compose默认只绑定127.0.0.1，不直接暴露公网明文端口。
可用 `kubectl apply -k deploy/kubernetes/optional/mcp` 单独部署；该目录不在默认base中。
部署前替换示例域名、固定实际镜像版本/摘要、配置TLS Ingress与目标网络策略。
移除该独立Deployment/Service不删除核心数据库和浏览器环境。

CONTROL_PLANE_URL固定配置，禁止由工具调用者指定。非loopback HTTP上游仅在显式
MCP_ALLOW_INTERNAL_HTTP=true时允许，用于可信集群内部；外部上游必须HTTPS。
Gateway不存长期凭据，每个请求都向控制面验证连接；控制面不透明凭据只允许agent-access
路径，不能调用普通管理员API。工具权限在控制面逐次检查，不依赖模型自觉遵守。

## 开发验收

`make test-mcp-gateway` 使用官方客户端验证协议；`make test-mcp-container` 构建实际
Dockerfile，并在非root、只读容器中重复协议测试，结束时清理专用容器与随机镜像。
两者上游均为显式API夹具。`make test-mcp-integration` 另验正式Java/PG/Node生命周期
和并发额度（CDP为协议夹具）。macOS运行Docker测试前按根AGENTS.md核验OrbStack。

## 等待异步操作

首版8个工具之外，新增 `browser_wait_operation(operation_id, timeout_seconds=10)`，
一次等待1—25秒。返回 completed、waitTimedOut、succeeded 和最后一次operation投影；
只有COMMITTED表示成功，ABORTED/TIMED_OUT表示已结束但失败，未知状态继续等待。
等待到期不取消控制面操作，也不释放额度；继续使用原operationId查询，写请求保持原幂等键。
沿用operation权限与连接归属检查，每次轮询重新认证，不新增授权或绕过权限。
