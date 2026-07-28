# Online database operations

这里存放不能安全地放进持有 PostgreSQL Advisory Lock 的启动 Flyway 流程、但可独立在线
执行的幂等操作。

## V041 Node Command Shard Claim 索引

前置条件：所有实例已完成 V041，且发布值班确认数据库没有长事务阻塞 DDL。

```bash
psql "$POSTGRES_CONNECTION_URL" \
  -v ON_ERROR_STOP=1 \
  -f database/online-migrations/create_outbox_node_command_shard_claim_index.sql
```

验证：

```sql
SELECT indexrelid::regclass, indisvalid, indisready
FROM pg_index
WHERE indexrelid =
  'idx_outbox_node_command_shard_claim'::regclass;
```

必须同时得到 `indisvalid=true` 和 `indisready=true`。执行失败可原命令重试；索引暂时缺失
不会影响正确性，Dispatcher 会使用既有未发布事件索引，但大规模 Shard Claim 查询性能
可能下降。

需要回滚该性能优化时使用独立连接执行：

```sql
DROP INDEX CONCURRENTLY IF EXISTS idx_outbox_node_command_shard_claim;
```

不要在 Flyway 事务、显式事务块或共享发布事务中运行上述 concurrent index 命令。
