# MCP 有界等待操作与首次完整 CI 验证

> 日期：2026-11-12；基线9d326fd10ff911bd3428f283ed75cc33f3ea4855

精确SHA主CI43160973474全成功：Verify、漏洞扫描、PG配额、MCP协议/真实容器/
正式生命周期与后续集成均执行成功，Operator成功。Desktop43160973405双平台成功。
此结果取代progress321的新提交待验状态，但不等于V16全量生产Gate通过。

新增第9个工具browser_wait_operation：1—25秒整体deadline覆盖HTTP读取和轮询睡眠，
每次读取复用operation权限、连接归属及凭据认证。COMMITTED仅此状态成功；ABORTED和
TIMED_OUT为失败终态；ACTIVE/未知状态保留等待。返回结构化completed/waitTimedOut/
succeeded/operation；等待超时不取消原操作、不释放名额、不自动重发写请求。
不新增控制面接口、数据库迁移或授权，契约保持268操作/368 Schema。

官方MCP客户端协议2项测试通过：9工具调用、认证/Origin/撤销、状态转换到三个终态、
未知状态等待超时、无效超时拒绝及等待仅发GET。完整Java/PG/Node/Helper生命周期
回归通过，并新增验证真实启动Operation经等待工具返回完成成功。CDP仍为显式协议
夹具，不冒充本轮真实Chrome验证。容器构建回归由前置9d326fd CI确认；新代码须独立CI。

测试容器/网络自动清理，两项本轮构建MinIO镜像标签在无容器引用后清理，其他18容器
保留。页面操作工具和OAuth专用客户端适配继续作为后续范围，不扩大当前连接权限。
