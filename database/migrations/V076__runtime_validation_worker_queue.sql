CREATE TABLE runtime_validation_jobs (
    validation_id                 TEXT PRIMARY KEY
                                      REFERENCES runtime_validation_runs(validation_id)
                                      ON DELETE CASCADE,
    browser_engine                TEXT NOT NULL,
    browser_version               TEXT NOT NULL,
    operating_system              TEXT NOT NULL,
    architecture                  TEXT NOT NULL,
    required_worker_capabilities  JSONB NOT NULL DEFAULT '{}',
    state                         TEXT NOT NULL DEFAULT 'QUEUED',
    attempt                       INTEGER NOT NULL DEFAULT 0,
    maximum_attempts              INTEGER NOT NULL DEFAULT 3,
    available_at                  TIMESTAMPTZ NOT NULL,
    claim_owner                   TEXT,
    claim_epoch                   BIGINT NOT NULL DEFAULT 0,
    claim_token_hash              TEXT,
    lease_expires_at              TIMESTAMPTZ,
    last_heartbeat_at             TIMESTAMPTZ,
    failure_code                  TEXT,
    result_hash                   TEXT,
    created_at                    TIMESTAMPTZ NOT NULL,
    updated_at                    TIMESTAMPTZ NOT NULL,
    CONSTRAINT chk_runtime_validation_job_state CHECK (
        state IN ('QUEUED', 'CLAIMED', 'EXECUTING', 'ACKED', 'COMMITTED', 'FAILED')
    ),
    CONSTRAINT chk_runtime_validation_job_attempts CHECK (
        attempt >= 0 AND maximum_attempts BETWEEN 1 AND 10 AND attempt <= maximum_attempts
    ),
    CONSTRAINT chk_runtime_validation_job_claim CHECK (
        (
          state IN ('CLAIMED', 'EXECUTING', 'ACKED')
          AND claim_owner IS NOT NULL
          AND claim_token_hash IS NOT NULL
          AND lease_expires_at IS NOT NULL
        )
        OR state IN ('QUEUED', 'COMMITTED', 'FAILED')
    ),
    CONSTRAINT chk_runtime_validation_job_target CHECK (
        browser_engine ~ '^[a-z0-9_.-]{1,64}$'
        AND browser_version ~ '^[A-Za-z0-9_.+-]{1,64}$'
        AND operating_system ~ '^[a-z0-9_.-]{1,64}$'
        AND architecture ~ '^[a-z0-9_.-]{1,32}$'
    )
);

CREATE INDEX idx_runtime_validation_jobs_ready
ON runtime_validation_jobs(
    available_at,
    created_at,
    validation_id
)
WHERE state = 'QUEUED';

CREATE INDEX idx_runtime_validation_jobs_lease
ON runtime_validation_jobs(lease_expires_at, validation_id)
WHERE state IN ('CLAIMED', 'EXECUTING');

CREATE TABLE runtime_validation_job_events (
    event_id                       TEXT PRIMARY KEY,
    validation_id                 TEXT NOT NULL
                                        REFERENCES runtime_validation_runs(validation_id)
                                        ON DELETE CASCADE,
    event_type                    TEXT NOT NULL,
    from_state                    TEXT,
    to_state                      TEXT NOT NULL,
    worker_id                     TEXT,
    claim_epoch                   BIGINT,
    attempt                       INTEGER NOT NULL,
    reason_code                   TEXT,
    occurred_at                   TIMESTAMPTZ NOT NULL,
    CONSTRAINT chk_runtime_validation_job_event_state CHECK (
        to_state IN ('QUEUED', 'CLAIMED', 'EXECUTING', 'ACKED', 'COMMITTED', 'FAILED')
    ),
    CONSTRAINT chk_runtime_validation_job_event_attempt CHECK (attempt >= 0)
);

CREATE INDEX idx_runtime_validation_job_events_run
ON runtime_validation_job_events(validation_id, occurred_at, event_id);

CREATE TABLE runtime_validation_workers (
    worker_id                      TEXT PRIMARY KEY,
    browser_engine                 TEXT NOT NULL,
    browser_versions               JSONB NOT NULL,
    operating_system               TEXT NOT NULL,
    architecture                   TEXT NOT NULL,
    capabilities                   JSONB NOT NULL,
    state                          TEXT NOT NULL DEFAULT 'ONLINE',
    active_validation_id           TEXT
                                        REFERENCES runtime_validation_runs(validation_id)
                                        ON DELETE SET NULL,
    last_seen_at                   TIMESTAMPTZ NOT NULL,
    registered_at                 TIMESTAMPTZ NOT NULL,
    CONSTRAINT chk_runtime_validation_worker_state CHECK (
        state IN ('ONLINE', 'BUSY', 'DRAINING', 'OFFLINE')
    ),
    CONSTRAINT chk_runtime_validation_worker_target CHECK (
        browser_engine ~ '^[a-z0-9_.-]{1,64}$'
        AND operating_system ~ '^[a-z0-9_.-]{1,64}$'
        AND architecture ~ '^[a-z0-9_.-]{1,32}$'
    )
);

COMMENT ON TABLE runtime_validation_jobs IS
'PostgreSQL-authoritative, leased and fenced queue for isolated Runtime Validation Workers';
COMMENT ON TABLE runtime_validation_job_events IS
'Immutable Runtime Validation Worker transition evidence';
COMMENT ON TABLE runtime_validation_workers IS
'Last-known capability and liveness projection for least-privilege Validation Workers';
