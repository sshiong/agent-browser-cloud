CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_agent_tasks_tenant_summary_cursor
    ON agent_tasks(tenant_id, created_at DESC, task_id DESC)
    INCLUDE (state);

COMMENT ON INDEX idx_agent_tasks_tenant_summary_cursor IS
    'Stable tenant-scoped keyset pagination for lightweight Agent task summaries';
