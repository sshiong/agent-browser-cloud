ALTER TABLE agent_tasks DROP CONSTRAINT chk_agent_task_state;
ALTER TABLE agent_tasks
    ADD CONSTRAINT chk_agent_task_state CHECK (
        state IN (
            'PLANNED',
            'AWAITING_CONFIRMATION',
            'BLOCKED',
            'RUNNING',
            'WAITING_FOR_HUMAN',
            'COMPLETED',
            'FAILED'
        )
    );

ALTER TABLE agent_tasks
    ADD COLUMN confirmation_id TEXT,
    ADD COLUMN confirmation_status TEXT,
    ADD COLUMN confirmation_expires_at TIMESTAMPTZ,
    ADD COLUMN confirmation_decided_at TIMESTAMPTZ,
    ADD COLUMN confirmation_actor_id TEXT,
    ADD COLUMN confirmation_evidence_hash TEXT,
    ADD COLUMN handoff_request_id TEXT,
    ADD COLUMN handoff_status TEXT,
    ADD COLUMN handoff_expires_at TIMESTAMPTZ,
    ADD COLUMN handoff_actor_id TEXT;

ALTER TABLE agent_tasks
    ADD CONSTRAINT chk_agent_confirmation_status CHECK (
        confirmation_status IS NULL
        OR confirmation_status IN ('PENDING', 'APPROVED', 'REJECTED', 'EXPIRED')
    ),
    ADD CONSTRAINT chk_agent_handoff_status CHECK (
        handoff_status IS NULL
        OR handoff_status IN ('PENDING', 'ACCEPTED', 'REJECTED', 'EXPIRED')
    );

CREATE UNIQUE INDEX uq_agent_confirmation_id
ON agent_tasks(confirmation_id)
WHERE confirmation_id IS NOT NULL;

CREATE UNIQUE INDEX uq_agent_handoff_request_id
ON agent_tasks(handoff_request_id)
WHERE handoff_request_id IS NOT NULL;

CREATE INDEX idx_agent_confirmation_expiry
ON agent_tasks(confirmation_expires_at)
WHERE confirmation_status = 'PENDING';

CREATE INDEX idx_agent_handoff_expiry
ON agent_tasks(handoff_expires_at)
WHERE handoff_status = 'PENDING';

COMMENT ON COLUMN agent_tasks.confirmation_evidence_hash IS
'Hash of immutable task/plan/actor confirmation evidence; no raw high-risk prompt is copied';
