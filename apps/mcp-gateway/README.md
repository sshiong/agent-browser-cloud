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
