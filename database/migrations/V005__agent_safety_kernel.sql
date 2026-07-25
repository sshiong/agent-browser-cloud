CREATE TABLE agent_tasks (
    task_id             TEXT PRIMARY KEY,
    tenant_id           TEXT NOT NULL,
    session_id          TEXT NOT NULL REFERENCES sessions(id),
    goal                TEXT NOT NULL,
    state               TEXT NOT NULL,
    risk_class          TEXT NOT NULL,
    intent_decision     TEXT NOT NULL,
    blocked_reason      TEXT,
    current_step        INTEGER NOT NULL DEFAULT 0,
    allowed_domains     JSONB NOT NULL DEFAULT '[]',
    plan                JSONB NOT NULL DEFAULT '{}',
    security_events     JSONB NOT NULL DEFAULT '[]',
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_agent_task_state CHECK (state IN ('PLANNED', 'BLOCKED')),
    CONSTRAINT chk_agent_intent_decision
        CHECK (intent_decision IN ('ALLOWED', 'CONFIRM_REQUIRED', 'FORBIDDEN'))
);

CREATE INDEX idx_agent_tasks_tenant_created
ON agent_tasks(tenant_id, created_at DESC);

CREATE INDEX idx_agent_tasks_session_created
ON agent_tasks(session_id, created_at DESC);

COMMENT ON TABLE agent_tasks IS
'Agent task plans and prompt-security audit evidence; raw external content is not retained';
