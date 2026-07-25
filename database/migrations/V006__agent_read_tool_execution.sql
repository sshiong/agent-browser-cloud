ALTER TABLE agent_tasks DROP CONSTRAINT chk_agent_task_state;
ALTER TABLE agent_tasks
    ADD CONSTRAINT chk_agent_task_state
        CHECK (state IN ('PLANNED', 'BLOCKED', 'RUNNING', 'COMPLETED', 'FAILED'));

ALTER TABLE agent_tasks
    ADD COLUMN operation_id TEXT REFERENCES exclusive_operations(operation_id),
    ADD COLUMN execution_results JSONB NOT NULL DEFAULT '[]',
    ADD COLUMN last_error TEXT,
    ADD COLUMN execution_started_at TIMESTAMPTZ,
    ADD COLUMN execution_completed_at TIMESTAMPTZ;

CREATE TABLE tool_capability_uses (
    token_id            TEXT PRIMARY KEY,
    tenant_id           TEXT NOT NULL,
    session_id          TEXT NOT NULL REFERENCES sessions(id),
    task_id             TEXT NOT NULL REFERENCES agent_tasks(task_id),
    tool_id             TEXT NOT NULL,
    used_at             TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_tool_capability_uses_task
ON tool_capability_uses(task_id, used_at);

COMMENT ON TABLE tool_capability_uses IS
'Authoritative single-use ledger for Agent Tool Capability Tokens';
