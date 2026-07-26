# Phase 5：PostgreSQL 短时不可用演练

> 状态：已完成  
> 日期：2026-07-26  
> 验收入口：`make test-postgres-outage`

## 关闭的缺口

大纲要求 PostgreSQL 短时不可用时：

1. API 返回 503；
2. Coordinator 暂停写操作；
3. 数据库恢复后可自动继续；
4. 已提交数据不丢失。

本轮将数据库连接异常从通用 `500 INTERNAL_ERROR` 收敛为稳定、脱敏的错误契约：

```json
{
  "code": "DATABASE_UNAVAILABLE",
  "message": "The authoritative database is temporarily unavailable",
  "details": {},
  "requestId": "..."
}
```

错误响应不包含 JDBC URL、数据库主机、用户名、SQL 或异常栈。

## 超时与恢复边界

Control Plane 新增可配置的 Hikari/JDBC 上界：

- `DATABASE_CONNECTION_TIMEOUT_MS`，默认 3000ms；
- `DATABASE_VALIDATION_TIMEOUT_MS`，默认 1000ms；
- `DATABASE_CONNECT_TIMEOUT_SECONDS`，默认 3s；
- `DATABASE_SOCKET_TIMEOUT_SECONDS`，默认 5s；
- TCP Keepalive 开启。

这使连接获取、连接建立和已经借出的连接在网络黑洞场景中都有界，不会让请求线程
无限等待。

## 真实演练流程

`tests/failure-injection/postgres-outage.sh` 会：

1. 启动临时 PostgreSQL 17、Redis 7 和真实 Control Plane；
2. 完成全部 17 个 Flyway Migration；
3. 创建并提交基线 Session；
4. 使用 `docker pause` 暂停 PostgreSQL，模拟连接仍存在但服务完全无响应；
5. 验证 Session 读取和创建写入都返回 503，且 Control Plane 进程仍存活；
6. 恢复 PostgreSQL；
7. 使用断库期间失败写入的同一 Idempotency Key 重试；
8. 验证重试成功，最终数据库严格只有断库前与恢复后两条 Session。

最终证据：

```text
postgres_outage_read_status=503 elapsed=3
postgres_outage_write_status=503
POSTGRES_OUTAGE_GAMEDAY_OK ... outage_seconds=3
```

## 仍未完成

1. 本演练是单 Control Plane、单 PostgreSQL 容器的短时网络黑洞，不等同于托管
   PostgreSQL 主备切换、DNS 切换、连接池风暴和长事务中断验收。
2. Coordinator Kill/新实例接管、Object Storage 超时仍未形成独立自动 GameDay。
3. 后续生产演练需记录连接池饱和度、API P99、恢复时间、失败请求数和数据库
   Failover Timeline，并绑定具体 Build/环境。
