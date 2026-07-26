ALTER TABLE agent_tasks
    ADD COLUMN pending_step_id TEXT,
    ADD COLUMN pending_tool_id TEXT,
    ADD COLUMN pending_content_hash TEXT,
    ADD COLUMN step_deadline_at TIMESTAMPTZ,
    ADD COLUMN executor_lease_owner TEXT,
    ADD COLUMN executor_lease_until TIMESTAMPTZ,
    ADD COLUMN replan_reason TEXT;

ALTER TABLE agent_tasks
    ADD CONSTRAINT chk_agent_pending_step_consistency CHECK (
        (pending_step_id IS NULL
            AND pending_tool_id IS NULL
            AND pending_state_version IS NULL
            AND pending_content_hash IS NULL
            AND step_deadline_at IS NULL)
        OR
        (pending_step_id IS NOT NULL
            AND pending_tool_id IS NOT NULL
            AND pending_state_version IS NOT NULL
            AND step_deadline_at IS NOT NULL)
    ),
    ADD CONSTRAINT chk_agent_executor_lease_consistency CHECK (
        (executor_lease_owner IS NULL AND executor_lease_until IS NULL)
        OR
        (executor_lease_owner IS NOT NULL AND executor_lease_until IS NOT NULL)
    );

CREATE INDEX idx_agent_tasks_recovery
ON agent_tasks(state, step_deadline_at, executor_lease_until)
WHERE state = 'RUNNING';

COMMENT ON COLUMN agent_tasks.pending_step_id IS
'Durable async Agent step checkpoint; cleared only after verified completion or terminal failure';

COMMENT ON COLUMN agent_tasks.executor_lease_until IS
'Short executor lease used by recovery workers; an expired lease may be reclaimed';
