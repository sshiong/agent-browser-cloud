# MCP 部署 CI 兼容与凭据缓存验收

> 2026-11-10；基线22cacd1；本机完整MCP重跑通过，新提交CI待核验。

22cacd1主CI42821227137在Verify失败，具体为Compose新测试的--no-interpolate
让Linux Compose将未展开的卷插值冒号当作卷分隔符；后续MCP专用Gate未执行。
Desktop42821226935双平台成功，Operator成功，不记主CI全通过。

测试改用非秘密隔离输入正常展开Compose，显式--profile mcp核验可选服务安全配置；
另在COMPOSE_PROFILES空值下查询默认services，断言无mcp-gateway。全部6项Compose
测试通过，无服务启动。连接创建响应新增Cache-Control:no-store及Pragma:no-cache，
完整测试检查响应包含no-store指令，兼容Spring附带的其他缓存指令。

Java回归通过；正式官方MCP客户端→Gateway→Java/PG/Node/Helper生命周期重跑通过，
含缓存头、并发额度、幂等重放、跨连接拒绝、撤销、Gateway重启环境保留。CDP仍为
显式测试夹具。首次断言过严要求缓存头整体等于no-store而失败，改为指令包含后重跑通过。
本轮未改变MCP工具范围、授权边界或公开契约（268操作/368 Schema）。

已清专用容器与网络，两自建闲置MinIO镜像按构建ID及无引用清理；原16容器和共享缓存
保留。目标Kubernetes/HTTPS域名、OAuth专用客户端适配仍不在本机验收范围。
