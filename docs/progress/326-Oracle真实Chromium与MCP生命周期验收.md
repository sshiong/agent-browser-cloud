# Oracle 真实 Chromium 与 MCP 生命周期验收

> 日期：2026-11-13；Control Plane 为 progress325 租约写放大修复镜像，其余部署见 progress323—324。
> 正式 API 268 Operations / 368 Schemas、V140 不变，本轮无产品代码变更。

用户明确允许为测试短暂停止原 `aitown-oracle-backend`，最长五分钟后恢复。通过 Termius
在停止前创建独立 systemd 270 秒恢复定时器，测试编排同时以 finally 恢复后端，避免 SSH
断线留下长期停机。只停止后端容器，不停止 `aitown-oracle-pg`，不改其数据、配置或重启策略。

停止后，权威 Node 心跳 I/O Full 从 3.89 下降至 0.91、0.18、0.04；正常恢复窗口满足后，
`pressureState=NORMAL`、`admissionState=OPEN`。未改 PSI 阈值或启用虚构容量 Bootstrap。
这为共享业务 I/O 造成准入关闭提供实际对照证据，但不等于共享磁盘容量长稳认证。

在实际 ARM64 MCP Gateway 容器内运行官方 Streamable HTTP 客户端，复用正式
Gateway → Java/PostgreSQL → mTLS Browser Node → 真实 `/usr/bin/chromium` 链。
使用专用短期连接，发现 9 个工具、读取配额、创建环境，分别启动和停止并通过
`browser_wait_operation` 校验 completed/succeeded，最后删除环境并确认 environmentsUsed=0。
脚本返回码 0，私有服务器日志 `/root/agentbrowser-smoke4.log` 的最小结果为：

```text
mcp_create=true
mcp_start=true
mcp_stop=true
mcp_lifecycle_arm_chromium=true
```

本次不是 CDP 夹具，不把 HTTP 接受或等待超时当成功。此证据覆盖单环境一次完整启停；
不涵盖登录 Cookie 跨启停、长稳、并发容量、页面动作或完整模型 Agent/Reviewer/Vision 任务。

测试编排总计 71 秒后恢复 aitown 后端，随后 docker inspect 确认后端与其数据库均 running；
确认恢复后取消临时恢复定时器。检查 active_smoke_connections=0、
undeleted_smoke_environments=0，未遗留测试连接权限和环境配额。原业务数据库未停止。
项目 11 服务继续运行，CP/PG/Redis/MCP 及四 Worker 健康，默认 Worker 健康/凭据检查 PASS。
未创建额外测试镜像、容器或卷，无全局资源清理。

当前 ARM 核心 MCP 生命周期已通过；共享业务恢复后仍可能重新触发真实压力保护，
本次短暂停机不能作为长期共存能力承诺。对象存储/OIDC、完整模型任务及 V16 生产 Gate
继续保留，不能由单次核心验收推导全功能部署完成。服务器私有配置与凭据不进入仓库。
