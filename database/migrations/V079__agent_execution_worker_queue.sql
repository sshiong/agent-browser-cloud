-- External Agent Worker dispatch queue. The worker receives no plan, prompt or customer data;
-- it only leases a task identifier and asks the Control Plane safety kernel to drive it.

ALTER TABLE agent_tasks
    ADD CONSTRAINT chk_agent_task_state_v3 CHECK (
        state IN (
            'PLANNED',
            'QUEUED',
            'AWAITING_CONFIRMATION',
            'BLOCKED',
            'RUNNING',
            'WAITING_FOR_HUMAN',
            'PAUSED_BY_RESOURCE_POLICY',
            'COMPLETED',
            'FAILED'
        )
    ) NOT VALID;
ALTER TABLE agent_tasks VALIDATE CONSTRAINT chk_agent_task_state_v3;
ALTER TABLE agent_tasks DROP CONSTRAINT chk_agent_task_state;
ALTER TABLE agent_tasks
    RENAME CONSTRAINT chk_agent_task_state_v3 TO chk_agent_task_state;

CREATE TABLE agent_execution_jobs (
    job_id                  TEXT PRIMARY KEY,
    task_id                 TEXT NOT NULL UNIQUE REFERENCES agent_tasks(task_id),
    tenant_id               TEXT NOT NULL,
    session_id              TEXT NOT NULL REFERENCES sessions(id),
    request_idempotency_key TEXT NOT NULL,
    protocol_version        TEXT NOT NULL DEFAULT 'agent-worker/v1',
    state                   TEXT NOT NULL,
    attempt                 INTEGER NOT NULL DEFAULT 0,
    maximum_attempts        INTEGER NOT NULL DEFAULT 3,
    worker_id               TEXT,
    claim_epoch             BIGINT NOT NULL DEFAULT 0,
    claim_token_hash        TEXT,
    lease_expires_at        TIMESTAMPTZ,
    available_at            TIMESTAMPTZ NOT NULL,
    started_at              TIMESTAMPTZ,
    completed_at            TIMESTAMPTZ,
    failure_code            TEXT,
    created_at              TIMESTAMPTZ NOT NULL,
    updated_at              TIMESTAMPTZ NOT NULL,
    CONSTRAINT chk_agent_execution_job_id CHECK (job_id ~ '^ajob_[A-Za-z0-9]{20}$'),
    CONSTRAINT chk_agent_execution_job_protocol CHECK (protocol_version = 'agent-worker/v1'),
    CONSTRAINT chk_agent_execution_job_state CHECK (
        state IN ('QUEUED', 'CLAIMED', 'EXECUTING', 'WAITING', 'COMMITTED', 'FAILED')
    ),
    CONSTRAINT chk_agent_execution_job_attempt CHECK (
        attempt >= 0 AND maximum_attempts BETWEEN 1 AND 10 AND attempt <= maximum_attempts
    ),
    CONSTRAINT chk_agent_execution_job_claim CHECK (
        (state IN ('CLAIMED', 'EXECUTING')
            AND worker_id IS NOT NULL
            AND claim_token_hash ~ '^[a-f0-9]{64}$'
            AND lease_expires_at IS NOT NULL)
        OR
        (state NOT IN ('CLAIMED', 'EXECUTING')
            AND worker_id IS NULL
            AND claim_token_hash IS NULL
            AND lease_expires_at IS NULL)
    ),
    CONSTRAINT chk_agent_execution_job_completion CHECK (
        (state IN ('COMMITTED', 'FAILED') AND completed_at IS NOT NULL)
        OR (state NOT IN ('COMMITTED', 'FAILED') AND completed_at IS NULL)
    )
);

CREATE INDEX idx_agent_execution_jobs_claim
ON agent_execution_jobs(available_at, created_at, job_id)
WHERE state = 'QUEUED';

CREATE INDEX idx_agent_execution_jobs_lease
ON agent_execution_jobs(lease_expires_at, job_id)
WHERE state IN ('CLAIMED', 'EXECUTING');

CREATE INDEX idx_agent_execution_jobs_tenant_created
ON agent_execution_jobs(tenant_id, created_at DESC, job_id DESC);

CREATE TABLE agent_execution_job_events (
    event_id     BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    job_id       TEXT NOT NULL REFERENCES agent_execution_jobs(job_id),
    event_type   TEXT NOT NULL,
    state        TEXT NOT NULL,
    attempt      INTEGER NOT NULL,
    worker_id    TEXT,
    claim_epoch  BIGINT NOT NULL,
    failure_code TEXT,
    occurred_at  TIMESTAMPTZ NOT NULL,
    CONSTRAINT chk_agent_execution_job_event_type CHECK (
        event_type IN (
            'ENQUEUED', 'CLAIMED', 'STARTED', 'HEARTBEAT', 'WAITING',
            'REQUEUED', 'COMMITTED', 'FAILED'
        )
    ),
    CONSTRAINT chk_agent_execution_job_event_state CHECK (
        state IN ('QUEUED', 'CLAIMED', 'EXECUTING', 'WAITING', 'COMMITTED', 'FAILED')
    ),
    CONSTRAINT chk_agent_execution_job_event_attempt CHECK (attempt >= 0),
    CONSTRAINT chk_agent_execution_job_event_failure_code CHECK (
        failure_code IS NULL OR failure_code ~ '^[A-Z][A-Z0-9_]{2,127}$'
    )
);

CREATE INDEX idx_agent_execution_job_events_job
ON agent_execution_job_events(job_id, event_id);

COMMENT ON TABLE agent_execution_jobs IS
'Leased and fenced dispatch queue for the data-minimized external Agent Worker protocol';

COMMENT ON COLUMN agent_execution_jobs.claim_token_hash IS
'SHA-256 of a single-use 256-bit claim token; plaintext is returned once and never persisted';

COMMENT ON TABLE agent_execution_job_events IS
'Append-only lifecycle audit for external Agent Worker claims and fenced transitions';
