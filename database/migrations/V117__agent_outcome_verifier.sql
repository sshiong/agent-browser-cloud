-- Independent post-execution semantic Outcome Verifier. Provider credentials, capability tokens,
-- sealed payloads and raw Browser State are never persisted in this queue.

ALTER TABLE agent_tasks
    ADD COLUMN outcome_verification_status TEXT NOT NULL DEFAULT 'NOT_REQUIRED',
    ADD COLUMN outcome_verification_id TEXT,
    ADD COLUMN outcome_decision TEXT,
    ADD COLUMN outcome_reason_codes JSONB NOT NULL DEFAULT '[]',
    ADD COLUMN outcome_evidence_hash TEXT,
    ADD COLUMN outcome_deployment_id TEXT,
    ADD COLUMN outcome_model_name TEXT,
    ADD COLUMN outcome_model_revision TEXT,
    ADD COLUMN outcome_input_tokens INTEGER,
    ADD COLUMN outcome_output_tokens INTEGER,
    ADD COLUMN outcome_cost_micros BIGINT,
    ADD COLUMN outcome_latency_ms INTEGER,
    ADD COLUMN outcome_failure_code TEXT,
    ADD COLUMN outcome_completed_at TIMESTAMPTZ;

ALTER TABLE agent_tasks
    ADD CONSTRAINT chk_agent_outcome_status CHECK (
      outcome_verification_status IN (
        'NOT_REQUIRED', 'QUEUED', 'IN_REVIEW', 'VERIFIED', 'NOT_VERIFIED', 'FAILED'
      )
    ),
    ADD CONSTRAINT chk_agent_outcome_decision CHECK (
      outcome_decision IS NULL OR outcome_decision IN ('VERIFIED', 'NOT_VERIFIED')
    ),
    ADD CONSTRAINT chk_agent_outcome_evidence_hash CHECK (
      outcome_evidence_hash IS NULL OR outcome_evidence_hash ~ '^[a-f0-9]{64}$'
    ),
    ADD CONSTRAINT chk_agent_outcome_accounting CHECK (
      (outcome_input_tokens IS NULL OR outcome_input_tokens BETWEEN 0 AND 1000000)
      AND (outcome_output_tokens IS NULL OR outcome_output_tokens BETWEEN 0 AND 100000)
      AND (outcome_cost_micros IS NULL OR outcome_cost_micros >= 0)
      AND (outcome_latency_ms IS NULL OR outcome_latency_ms BETWEEN 0 AND 600000)
    ),
    ADD CONSTRAINT chk_agent_outcome_completion CHECK (
      (outcome_verification_status IN ('VERIFIED', 'NOT_VERIFIED', 'FAILED')
        AND outcome_completed_at IS NOT NULL)
      OR
      (outcome_verification_status IN ('NOT_REQUIRED', 'QUEUED', 'IN_REVIEW')
        AND outcome_completed_at IS NULL)
    );

ALTER TABLE agent_tasks
    ADD CONSTRAINT chk_agent_task_state_v5 CHECK (
      state IN (
        'PLANNED', 'QUEUED', 'AWAITING_REVIEW', 'AWAITING_CONFIRMATION', 'BLOCKED',
        'RUNNING', 'VERIFYING_OUTCOME', 'WAITING_FOR_HUMAN', 'PAUSED_BY_RESOURCE_POLICY',
        'COMPLETED', 'FAILED'
      )
    ) NOT VALID;
ALTER TABLE agent_tasks VALIDATE CONSTRAINT chk_agent_task_state_v5;
ALTER TABLE agent_tasks DROP CONSTRAINT chk_agent_task_state;
ALTER TABLE agent_tasks RENAME CONSTRAINT chk_agent_task_state_v5 TO chk_agent_task_state;

CREATE TABLE agent_outcome_verification_jobs (
    job_id                       TEXT PRIMARY KEY,
    verification_id              TEXT NOT NULL UNIQUE,
    task_id                      TEXT NOT NULL UNIQUE REFERENCES agent_tasks(task_id),
    tenant_id                    TEXT NOT NULL,
    session_id                   TEXT NOT NULL,
    protocol_version             TEXT NOT NULL DEFAULT 'outcome-verifier-worker/v1',
    evidence_hash                TEXT NOT NULL,
    state_version                BIGINT NOT NULL,
    target_revision             BIGINT NOT NULL,
    state_hash                   TEXT NOT NULL,
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
    input_hash                   TEXT NOT NULL,
    decision                     TEXT,
    reason_codes                 JSONB NOT NULL DEFAULT '[]',
    confidence                   NUMERIC(5,4),
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
    CONSTRAINT fk_agent_outcome_session
      FOREIGN KEY (session_id, tenant_id) REFERENCES sessions(id, tenant_id),
    CONSTRAINT chk_agent_outcome_job_id CHECK (job_id ~ '^ojob_[A-Za-z0-9]{20}$'),
    CONSTRAINT chk_agent_outcome_verification_id CHECK (
      verification_id ~ '^out_[A-Za-z0-9]{20}$'
    ),
    CONSTRAINT chk_agent_outcome_protocol CHECK (
      protocol_version = 'outcome-verifier-worker/v1'
    ),
    CONSTRAINT chk_agent_outcome_job_hashes CHECK (
      evidence_hash ~ '^[a-f0-9]{64}$' AND state_hash ~ '^[a-f0-9]{64}$'
      AND input_hash ~ '^[a-f0-9]{64}$'
      AND (output_hash IS NULL OR output_hash ~ '^[a-f0-9]{64}$')
    ),
    CONSTRAINT chk_agent_outcome_job_state CHECK (
      state IN ('QUEUED', 'CLAIMED', 'EXECUTING', 'VERIFIED', 'NOT_VERIFIED', 'FAILED')
    ),
    CONSTRAINT chk_agent_outcome_job_attempt CHECK (
      attempt >= 0 AND maximum_attempts BETWEEN 1 AND 10 AND attempt <= maximum_attempts
    ),
    CONSTRAINT chk_agent_outcome_job_claim CHECK (
      (state IN ('CLAIMED', 'EXECUTING') AND worker_id IS NOT NULL
        AND claim_token_hash ~ '^[a-f0-9]{64}$' AND lease_expires_at IS NOT NULL)
      OR
      (state NOT IN ('CLAIMED', 'EXECUTING') AND worker_id IS NULL
        AND claim_token_hash IS NULL AND lease_expires_at IS NULL)
    ),
    CONSTRAINT chk_agent_outcome_job_decision CHECK (
      decision IS NULL OR decision IN ('VERIFIED', 'NOT_VERIFIED')
    ),
    CONSTRAINT chk_agent_outcome_job_confidence CHECK (
      confidence IS NULL OR (confidence >= 0 AND confidence <= 1)
    ),
    CONSTRAINT chk_agent_outcome_job_accounting CHECK (
      (input_tokens IS NULL OR input_tokens BETWEEN 0 AND 1000000)
      AND (output_tokens IS NULL OR output_tokens BETWEEN 0 AND 100000)
      AND (cost_micros IS NULL OR cost_micros >= 0)
      AND (latency_ms IS NULL OR latency_ms BETWEEN 0 AND 600000)
      AND maximum_output_tokens BETWEEN 64 AND 4096
      AND input_price_micros_per_mtok >= 0 AND output_price_micros_per_mtok >= 0
    ),
    CONSTRAINT chk_agent_outcome_job_completion CHECK (
      (state IN ('VERIFIED', 'NOT_VERIFIED', 'FAILED') AND completed_at IS NOT NULL)
      OR (state NOT IN ('VERIFIED', 'NOT_VERIFIED', 'FAILED') AND completed_at IS NULL)
    )
);

CREATE INDEX idx_agent_outcome_jobs_claim
ON agent_outcome_verification_jobs(available_at, created_at, job_id)
WHERE state = 'QUEUED';

CREATE INDEX idx_agent_outcome_jobs_lease
ON agent_outcome_verification_jobs(lease_expires_at, job_id)
WHERE state IN ('CLAIMED', 'EXECUTING');

CREATE TABLE agent_outcome_verification_events (
    event_id      BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    job_id        TEXT NOT NULL REFERENCES agent_outcome_verification_jobs(job_id),
    event_type    TEXT NOT NULL,
    state         TEXT NOT NULL,
    attempt       INTEGER NOT NULL,
    worker_id     TEXT,
    claim_epoch   BIGINT NOT NULL,
    decision      TEXT,
    failure_code  TEXT,
    occurred_at   TIMESTAMPTZ NOT NULL,
    CONSTRAINT chk_agent_outcome_event_type CHECK (
      event_type IN (
        'ENQUEUED', 'CLAIMED', 'STARTED', 'HEARTBEAT', 'VERIFIED',
        'NOT_VERIFIED', 'REQUEUED', 'FAILED'
      )
    ),
    CONSTRAINT chk_agent_outcome_event_state CHECK (
      state IN ('QUEUED', 'CLAIMED', 'EXECUTING', 'VERIFIED', 'NOT_VERIFIED', 'FAILED')
    ),
    CONSTRAINT chk_agent_outcome_event_decision CHECK (
      decision IS NULL OR decision IN ('VERIFIED', 'NOT_VERIFIED')
    ),
    CONSTRAINT chk_agent_outcome_event_failure CHECK (
      failure_code IS NULL OR failure_code ~ '^[A-Z][A-Z0-9_]{2,127}$'
    )
);

CREATE INDEX idx_agent_outcome_events_job
ON agent_outcome_verification_events(job_id, event_id);

COMMENT ON TABLE agent_outcome_verification_jobs IS
'Independent post-execution semantic verifier queue with exact evidence and model fencing';

COMMENT ON COLUMN agent_outcome_verification_jobs.input_hash IS
'Hash of the bounded, redacted semantic evidence reconstructed at claim and completion';
