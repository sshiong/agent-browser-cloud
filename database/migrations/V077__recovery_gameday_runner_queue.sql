ALTER TABLE enterprise_recovery_gamedays
    DROP CONSTRAINT chk_enterprise_gameday_state,
    ADD COLUMN execution_mode TEXT NOT NULL DEFAULT 'MANUAL',
    ADD COLUMN environment TEXT NOT NULL DEFAULT 'TEST',
    ADD COLUMN blast_radius JSONB NOT NULL DEFAULT '{"scope":"TEST_FIXTURE","maximumTargets":1,"targetIds":[]}'::jsonb,
    ADD COLUMN maximum_duration_seconds INTEGER NOT NULL DEFAULT 900,
    ADD COLUMN approval_request_id TEXT REFERENCES break_glass_requests(request_id),
    ADD COLUMN current_stage TEXT NOT NULL DEFAULT 'MANUAL',
    ADD COLUMN abort_requested BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN recovery_confirmed BOOLEAN,
    ADD COLUMN failure_code TEXT,
    ADD CONSTRAINT chk_enterprise_gameday_state CHECK (
        state IN ('QUEUED', 'RUNNING', 'PASSED', 'FAILED', 'ABORTED')
    ),
    ADD CONSTRAINT chk_enterprise_gameday_execution_mode CHECK (
        execution_mode IN ('MANUAL', 'AUTO')
    ),
    ADD CONSTRAINT chk_enterprise_gameday_environment CHECK (
        environment IN ('TEST', 'STAGING', 'PRODUCTION')
    ),
    ADD CONSTRAINT chk_enterprise_gameday_duration CHECK (
        maximum_duration_seconds BETWEEN 30 AND 7200
    ),
    ADD CONSTRAINT chk_enterprise_gameday_blast_radius CHECK (
        jsonb_typeof(blast_radius) = 'object'
        AND blast_radius ? 'scope'
        AND blast_radius ? 'maximumTargets'
        AND blast_radius ? 'targetIds'
        AND jsonb_typeof(blast_radius -> 'targetIds') = 'array'
        AND (blast_radius ->> 'scope') IN ('TEST_FIXTURE', 'TENANT', 'NAMESPACE', 'REGION')
        AND (blast_radius ->> 'maximumTargets')::integer BETWEEN 1 AND 100
        AND jsonb_array_length(blast_radius -> 'targetIds')
            <= (blast_radius ->> 'maximumTargets')::integer
    ),
    ADD CONSTRAINT chk_enterprise_gameday_production_approval CHECK (
        environment <> 'PRODUCTION' OR approval_request_id IS NOT NULL
    );

UPDATE enterprise_recovery_gamedays
SET current_stage = CASE state
    WHEN 'RUNNING' THEN 'MANUAL'
    WHEN 'PASSED' THEN 'COMMITTED'
    ELSE 'FAILED'
END;

CREATE TABLE recovery_gameday_jobs (
    gameday_id                 TEXT PRIMARY KEY REFERENCES enterprise_recovery_gamedays(gameday_id),
    scenario_code              TEXT NOT NULL,
    environment                TEXT NOT NULL,
    required_worker_capabilities JSONB NOT NULL DEFAULT '{}'::jsonb,
    state                      TEXT NOT NULL,
    current_stage              TEXT NOT NULL,
    attempt                    INTEGER NOT NULL DEFAULT 0,
    maximum_attempts           INTEGER NOT NULL DEFAULT 3,
    recovery_attempt           INTEGER NOT NULL DEFAULT 0,
    maximum_recovery_attempts  INTEGER NOT NULL DEFAULT 5,
    claim_owner                TEXT,
    claim_epoch                BIGINT NOT NULL DEFAULT 0,
    claim_token_hash           TEXT,
    lease_expires_at           TIMESTAMPTZ,
    last_heartbeat_at          TIMESTAMPTZ,
    available_at               TIMESTAMPTZ NOT NULL,
    abort_deadline             TIMESTAMPTZ NOT NULL,
    abort_requested            BOOLEAN NOT NULL DEFAULT FALSE,
    fault_injected             BOOLEAN NOT NULL DEFAULT FALSE,
    recovery_confirmed         BOOLEAN,
    failure_code               TEXT,
    result_hash                TEXT,
    created_at                 TIMESTAMPTZ NOT NULL,
    updated_at                 TIMESTAMPTZ NOT NULL,
    CONSTRAINT chk_recovery_gameday_job_state CHECK (
        state IN (
            'QUEUED', 'CLAIMED', 'EXECUTING', 'RECOVERY_REQUIRED',
            'RECOVERING', 'ACKED', 'COMMITTED', 'FAILED', 'ABORTED'
        )
    ),
    CONSTRAINT chk_recovery_gameday_job_stage CHECK (
        current_stage IN (
            'QUEUED', 'PREPARING', 'INJECTING', 'FAULT_INJECTED', 'OBSERVING',
            'RECOVERY_REQUIRED', 'RECOVERING', 'VALIDATING', 'COMMITTED',
            'FAILED', 'ABORTED'
        )
    ),
    CONSTRAINT chk_recovery_gameday_job_environment CHECK (
        environment IN ('TEST', 'STAGING', 'PRODUCTION')
    ),
    CONSTRAINT chk_recovery_gameday_job_attempts CHECK (
        attempt >= 0 AND maximum_attempts BETWEEN 1 AND 10 AND attempt <= maximum_attempts
        AND recovery_attempt >= 0
        AND maximum_recovery_attempts BETWEEN 1 AND 10
        AND recovery_attempt <= maximum_recovery_attempts
    ),
    CONSTRAINT chk_recovery_gameday_job_epoch CHECK (claim_epoch >= 0),
    CONSTRAINT chk_recovery_gameday_job_capabilities CHECK (
        jsonb_typeof(required_worker_capabilities) = 'object'
    ),
    CONSTRAINT chk_recovery_gameday_job_claim CHECK (
        (state IN ('CLAIMED', 'EXECUTING', 'RECOVERING')
          AND claim_owner IS NOT NULL
          AND claim_token_hash IS NOT NULL
          AND lease_expires_at IS NOT NULL
          AND last_heartbeat_at IS NOT NULL)
        OR state NOT IN ('CLAIMED', 'EXECUTING', 'RECOVERING')
    ),
    CONSTRAINT chk_recovery_gameday_job_abort_deadline CHECK (
        abort_deadline > created_at
    )
);

CREATE INDEX idx_recovery_gameday_jobs_ready
ON recovery_gameday_jobs(environment, scenario_code, available_at, created_at)
WHERE state IN ('QUEUED', 'RECOVERY_REQUIRED');

CREATE INDEX idx_recovery_gameday_jobs_lease
ON recovery_gameday_jobs(lease_expires_at)
WHERE state IN ('CLAIMED', 'EXECUTING', 'RECOVERING');

CREATE INDEX idx_recovery_gameday_jobs_abort_deadline
ON recovery_gameday_jobs(abort_deadline)
WHERE state IN ('QUEUED', 'CLAIMED', 'EXECUTING', 'RECOVERY_REQUIRED', 'RECOVERING');

CREATE TABLE recovery_gameday_job_events (
    event_id                   TEXT PRIMARY KEY,
    gameday_id                 TEXT NOT NULL REFERENCES recovery_gameday_jobs(gameday_id),
    event_type                 TEXT NOT NULL,
    from_state                 TEXT,
    to_state                   TEXT NOT NULL,
    stage                      TEXT NOT NULL,
    worker_id                  TEXT,
    claim_epoch                BIGINT NOT NULL,
    attempt                    INTEGER NOT NULL,
    reason_code                TEXT,
    occurred_at                TIMESTAMPTZ NOT NULL,
    CONSTRAINT chk_recovery_gameday_event_attempt CHECK (
        claim_epoch >= 0 AND attempt >= 0
    )
);

CREATE INDEX idx_recovery_gameday_job_events_run
ON recovery_gameday_job_events(gameday_id, occurred_at, event_id);

CREATE TABLE recovery_gameday_workers (
    worker_id                  TEXT PRIMARY KEY,
    environments               JSONB NOT NULL,
    scenario_codes             JSONB NOT NULL,
    capabilities               JSONB NOT NULL,
    state                      TEXT NOT NULL,
    active_gameday_id          TEXT REFERENCES enterprise_recovery_gamedays(gameday_id),
    last_seen_at               TIMESTAMPTZ NOT NULL,
    registered_at              TIMESTAMPTZ NOT NULL,
    CONSTRAINT chk_recovery_gameday_worker_arrays CHECK (
        jsonb_typeof(environments) = 'array'
        AND jsonb_typeof(scenario_codes) = 'array'
        AND jsonb_array_length(environments) > 0
        AND jsonb_array_length(scenario_codes) > 0
    ),
    CONSTRAINT chk_recovery_gameday_worker_capabilities CHECK (
        jsonb_typeof(capabilities) = 'object'
    ),
    CONSTRAINT chk_recovery_gameday_worker_state CHECK (
        state IN ('ONLINE', 'BUSY', 'OFFLINE')
    )
);

COMMENT ON TABLE recovery_gameday_jobs IS
'Authoritative leased queue for isolated Recovery GameDay fault injection and recovery workers';

COMMENT ON TABLE recovery_gameday_job_events IS
'Immutable Recovery GameDay execution, abort, lease fencing and recovery timeline';

COMMENT ON TABLE recovery_gameday_workers IS
'Ephemeral liveness and fixed scenario capability projection for least-privilege GameDay workers';
