-- Cross-node migration orchestration state. Each phase is restart-safe and externally auditable.

CREATE TABLE session_migrations (
    migration_id           TEXT PRIMARY KEY,
    session_id             TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
    tenant_id              TEXT NOT NULL,
    source_node_id         TEXT NOT NULL,
    target_node_id         TEXT,
    source_context_epoch   BIGINT NOT NULL,
    target_context_epoch   BIGINT,
    checkpoint_id          TEXT,
    hibernate_operation_id TEXT,
    restore_operation_id   TEXT,
    resync_request_id      TEXT,
    phase                   TEXT NOT NULL,
    recovery_result        TEXT,
    failure_reason         TEXT,
    created_at             TIMESTAMPTZ NOT NULL,
    updated_at             TIMESTAMPTZ NOT NULL,
    completed_at           TIMESTAMPTZ,
    version                BIGINT NOT NULL DEFAULT 0,
    CHECK (
        phase IN (
            'CHECKPOINTING',
            'PLACING_TARGET',
            'RESTORING',
            'STATE_RESYNC',
            'BUSINESS_VALIDATION',
            'COMPLETED',
            'DEGRADED',
            'FAILED'
        )
    )
);

CREATE UNIQUE INDEX uq_session_migrations_active
    ON session_migrations(session_id)
    WHERE phase NOT IN ('COMPLETED', 'DEGRADED', 'FAILED');

CREATE INDEX idx_session_migrations_reconcile
    ON session_migrations(phase, updated_at);

COMMENT ON TABLE session_migrations IS
    'Durable checkpoint, cross-node placement, restore, state resync and business recovery workflow';
