-- Durable lifecycle for asynchronous AUTO resource adjustments.
-- The generic exclusive operation remains the Session write-fence; this ledger records the
-- resource-specific REQUESTED -> EXECUTING -> ACKNOWLEDGED -> COMMITTED/FAILED protocol.

CREATE TABLE session_resource_adjustments (
    operation_id       TEXT PRIMARY KEY REFERENCES exclusive_operations(operation_id),
    session_id         TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
    tenant_id          TEXT NOT NULL,
    state              TEXT NOT NULL,
    reason             TEXT NOT NULL,
    failure_code       TEXT,
    old_resources      JSONB,
    requested_resources JSONB NOT NULL,
    requested_at       TIMESTAMPTZ NOT NULL,
    executing_at       TIMESTAMPTZ,
    acknowledged_at    TIMESTAMPTZ,
    completed_at       TIMESTAMPTZ,
    updated_at         TIMESTAMPTZ NOT NULL,
    version            BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_resource_adjustment_state
        CHECK (state IN ('REQUESTED', 'EXECUTING', 'ACKNOWLEDGED', 'COMMITTED', 'FAILED')),
    CONSTRAINT ck_resource_adjustment_terminal
        CHECK (
            (state IN ('COMMITTED', 'FAILED') AND completed_at IS NOT NULL)
            OR (state NOT IN ('COMMITTED', 'FAILED') AND completed_at IS NULL)
        ),
    CONSTRAINT ck_resource_adjustment_failure
        CHECK (
            (state = 'FAILED' AND failure_code IS NOT NULL)
            OR (state <> 'FAILED' AND failure_code IS NULL)
        )
);

CREATE INDEX idx_resource_adjustments_session_requested
    ON session_resource_adjustments(session_id, requested_at DESC);

CREATE INDEX idx_resource_adjustments_active
    ON session_resource_adjustments(updated_at)
    WHERE state IN ('REQUESTED', 'EXECUTING', 'ACKNOWLEDGED');

COMMENT ON TABLE session_resource_adjustments IS
    'PostgreSQL-authoritative AUTO resource adjustment ACK lifecycle';
