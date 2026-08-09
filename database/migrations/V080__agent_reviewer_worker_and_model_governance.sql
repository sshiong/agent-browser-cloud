-- Independent Reviewer Agent queue and immutable model-governance evidence.
-- Provider credentials never cross this schema; only deployment identity, version, hashes and
-- bounded accounting metadata are persisted.

ALTER TABLE agent_tasks
    ADD COLUMN reviewer_status TEXT NOT NULL DEFAULT 'NOT_REQUIRED',
    ADD COLUMN reviewer_review_id TEXT,
    ADD COLUMN reviewed_plan_hash TEXT,
    ADD COLUMN reviewer_decision TEXT,
    ADD COLUMN reviewer_reason_codes JSONB NOT NULL DEFAULT '[]',
    ADD COLUMN reviewer_deployment_id TEXT,
    ADD COLUMN reviewer_model_name TEXT,
    ADD COLUMN reviewer_model_revision TEXT,
    ADD COLUMN reviewer_input_tokens INTEGER,
    ADD COLUMN reviewer_output_tokens INTEGER,
    ADD COLUMN reviewer_cost_micros BIGINT,
    ADD COLUMN reviewer_latency_ms INTEGER,
    ADD COLUMN reviewer_failure_code TEXT,
    ADD COLUMN reviewer_completed_at TIMESTAMPTZ;

ALTER TABLE agent_tasks
    ADD CONSTRAINT chk_agent_reviewer_status CHECK (
        reviewer_status IN (
            'NOT_REQUIRED', 'PENDING', 'QUEUED', 'IN_REVIEW',
            'APPROVED', 'REJECTED', 'FAILED'
        )
    ),
    ADD CONSTRAINT chk_agent_reviewer_decision CHECK (
        reviewer_decision IS NULL OR reviewer_decision IN ('APPROVE', 'REJECT')
    ),
    ADD CONSTRAINT chk_agent_reviewed_plan_hash CHECK (
        reviewed_plan_hash IS NULL OR reviewed_plan_hash ~ '^[a-f0-9]{64}$'
    ),
    ADD CONSTRAINT chk_agent_reviewer_consistency CHECK (
        (reviewer_status IN ('NOT_REQUIRED', 'PENDING', 'QUEUED', 'IN_REVIEW')
            AND reviewer_completed_at IS NULL)
        OR
        (reviewer_status IN ('APPROVED', 'REJECTED', 'FAILED')
            AND reviewer_completed_at IS NOT NULL)
    ),
    ADD CONSTRAINT chk_agent_reviewer_accounting CHECK (
        (reviewer_input_tokens IS NULL OR reviewer_input_tokens BETWEEN 0 AND 1000000)
        AND (reviewer_output_tokens IS NULL OR reviewer_output_tokens BETWEEN 0 AND 100000)
        AND (reviewer_cost_micros IS NULL OR reviewer_cost_micros >= 0)
        AND (reviewer_latency_ms IS NULL OR reviewer_latency_ms BETWEEN 0 AND 600000)
    );

ALTER TABLE agent_tasks
    ADD CONSTRAINT chk_agent_task_state_v4 CHECK (
        state IN (
            'PLANNED',
            'QUEUED',
            'AWAITING_REVIEW',
            'AWAITING_CONFIRMATION',
            'BLOCKED',
            'RUNNING',
            'WAITING_FOR_HUMAN',
            'PAUSED_BY_RESOURCE_POLICY',
            'COMPLETED',
            'FAILED'
        )
    ) NOT VALID;
ALTER TABLE agent_tasks VALIDATE CONSTRAINT chk_agent_task_state_v4;
ALTER TABLE agent_tasks DROP CONSTRAINT chk_agent_task_state;
ALTER TABLE agent_tasks
    RENAME CONSTRAINT chk_agent_task_state_v4 TO chk_agent_task_state;

CREATE TABLE agent_review_jobs (
    job_id                       TEXT PRIMARY KEY,
    review_id                    TEXT NOT NULL UNIQUE,
    task_id                      TEXT NOT NULL UNIQUE REFERENCES agent_tasks(task_id),
    tenant_id                    TEXT NOT NULL,
    session_id                   TEXT NOT NULL REFERENCES sessions(id),
    execution_idempotency_key    TEXT NOT NULL,
    protocol_version             TEXT NOT NULL DEFAULT 'reviewer-worker/v1',
    plan_hash                    TEXT NOT NULL,
    state                        TEXT NOT NULL,
    attempt                      INTEGER NOT NULL DEFAULT 0,
    maximum_attempts             INTEGER NOT NULL DEFAULT 3,
    worker_id                    TEXT,
    claim_epoch                  BIGINT NOT NULL DEFAULT 0,
    claim_token_hash             TEXT,
    lease_expires_at             TIMESTAMPTZ,
    available_at                 TIMESTAMPTZ NOT NULL,
    deployment_id                TEXT NOT NULL,
    provider_type                TEXT NOT NULL,
    model_name                   TEXT NOT NULL,
    model_revision               TEXT NOT NULL,
    data_policy                  TEXT NOT NULL,
    maximum_output_tokens        INTEGER NOT NULL,
    input_price_micros_per_mtok  BIGINT NOT NULL,
    output_price_micros_per_mtok BIGINT NOT NULL,
    decision                     TEXT,
    reason_codes                 JSONB NOT NULL DEFAULT '[]',
    confidence                   NUMERIC(5,4),
    input_hash                   TEXT NOT NULL,
    output_hash                  TEXT,
    provider_request_id          TEXT,
    input_tokens                 INTEGER,
    output_tokens                INTEGER,
    cost_micros                  BIGINT,
    latency_ms                   INTEGER,
    started_at                   TIMESTAMPTZ,
    completed_at                 TIMESTAMPTZ,
    failure_code                 TEXT,
    created_at                   TIMESTAMPTZ NOT NULL,
    updated_at                   TIMESTAMPTZ NOT NULL,
    CONSTRAINT chk_agent_review_job_id CHECK (job_id ~ '^rjob_[A-Za-z0-9]{20}$'),
    CONSTRAINT chk_agent_review_id CHECK (review_id ~ '^rev_[A-Za-z0-9]{20}$'),
    CONSTRAINT chk_agent_review_protocol CHECK (protocol_version = 'reviewer-worker/v1'),
    CONSTRAINT chk_agent_review_plan_hash CHECK (plan_hash ~ '^[a-f0-9]{64}$'),
    CONSTRAINT chk_agent_review_input_hash CHECK (input_hash ~ '^[a-f0-9]{64}$'),
    CONSTRAINT chk_agent_review_output_hash CHECK (
        output_hash IS NULL OR output_hash ~ '^[a-f0-9]{64}$'
    ),
    CONSTRAINT chk_agent_review_state CHECK (
        state IN ('QUEUED', 'CLAIMED', 'EXECUTING', 'APPROVED', 'REJECTED', 'FAILED')
    ),
    CONSTRAINT chk_agent_review_attempt CHECK (
        attempt >= 0 AND maximum_attempts BETWEEN 1 AND 10 AND attempt <= maximum_attempts
    ),
    CONSTRAINT chk_agent_review_claim CHECK (
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
    CONSTRAINT chk_agent_review_decision CHECK (
        decision IS NULL OR decision IN ('APPROVE', 'REJECT')
    ),
    CONSTRAINT chk_agent_review_confidence CHECK (
        confidence IS NULL OR (confidence >= 0 AND confidence <= 1)
    ),
    CONSTRAINT chk_agent_review_accounting CHECK (
        (input_tokens IS NULL OR input_tokens BETWEEN 0 AND 1000000)
        AND (output_tokens IS NULL OR output_tokens BETWEEN 0 AND 100000)
        AND (cost_micros IS NULL OR cost_micros >= 0)
        AND (latency_ms IS NULL OR latency_ms BETWEEN 0 AND 600000)
        AND maximum_output_tokens BETWEEN 64 AND 4096
        AND input_price_micros_per_mtok >= 0
        AND output_price_micros_per_mtok >= 0
    ),
    CONSTRAINT chk_agent_review_completion CHECK (
        (state IN ('APPROVED', 'REJECTED', 'FAILED') AND completed_at IS NOT NULL)
        OR (state NOT IN ('APPROVED', 'REJECTED', 'FAILED') AND completed_at IS NULL)
    )
);

CREATE INDEX idx_agent_review_jobs_claim
ON agent_review_jobs(available_at, created_at, job_id)
WHERE state = 'QUEUED';

CREATE INDEX idx_agent_review_jobs_lease
ON agent_review_jobs(lease_expires_at, job_id)
WHERE state IN ('CLAIMED', 'EXECUTING');

CREATE INDEX idx_agent_review_jobs_tenant_created
ON agent_review_jobs(tenant_id, created_at DESC, job_id DESC);

CREATE TABLE agent_review_job_events (
    event_id      BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    job_id        TEXT NOT NULL REFERENCES agent_review_jobs(job_id),
    event_type    TEXT NOT NULL,
    state         TEXT NOT NULL,
    attempt       INTEGER NOT NULL,
    worker_id     TEXT,
    claim_epoch   BIGINT NOT NULL,
    decision      TEXT,
    failure_code  TEXT,
    occurred_at   TIMESTAMPTZ NOT NULL,
    CONSTRAINT chk_agent_review_event_type CHECK (
        event_type IN (
            'ENQUEUED', 'CLAIMED', 'STARTED', 'HEARTBEAT',
            'APPROVED', 'REJECTED', 'REQUEUED', 'FAILED'
        )
    ),
    CONSTRAINT chk_agent_review_event_state CHECK (
        state IN ('QUEUED', 'CLAIMED', 'EXECUTING', 'APPROVED', 'REJECTED', 'FAILED')
    ),
    CONSTRAINT chk_agent_review_event_decision CHECK (
        decision IS NULL OR decision IN ('APPROVE', 'REJECT')
    ),
    CONSTRAINT chk_agent_review_event_attempt CHECK (attempt >= 0),
    CONSTRAINT chk_agent_review_event_failure_code CHECK (
        failure_code IS NULL OR failure_code ~ '^[A-Z][A-Z0-9_]{2,127}$'
    )
);

CREATE INDEX idx_agent_review_job_events_job
ON agent_review_job_events(job_id, event_id);

COMMENT ON TABLE agent_review_jobs IS
'Fenced Reviewer Agent queue with immutable deployment/version and token-cost governance evidence';

COMMENT ON COLUMN agent_review_jobs.claim_token_hash IS
'SHA-256 of a one-time claim token; plaintext is returned once and never persisted';

COMMENT ON COLUMN agent_review_jobs.input_hash IS
'Hash of the capability-free and sealed-payload-free review request; raw provider prompts are not persisted';

COMMENT ON TABLE agent_review_job_events IS
'Append-only lifecycle audit for independent Reviewer Agent execution';
