-- Durable safety observations used by the Safe Point Aggregator.
-- Absence or expiration of a required Browser Node observation is never treated as safe.

CREATE TABLE session_safety_signals (
    signal_id       TEXT PRIMARY KEY,
    session_id      TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
    tenant_id       TEXT NOT NULL,
    node_id         TEXT,
    context_epoch   BIGINT NOT NULL,
    signal_type     TEXT NOT NULL,
    source          TEXT NOT NULL,
    active          BOOLEAN NOT NULL,
    details         JSONB NOT NULL DEFAULT '{}'::jsonb,
    observed_at     TIMESTAMPTZ NOT NULL,
    expires_at      TIMESTAMPTZ NOT NULL,
    updated_at      TIMESTAMPTZ NOT NULL,
    version         BIGINT NOT NULL DEFAULT 0,
    UNIQUE (session_id, signal_type, source),
    CHECK (
        signal_type IN (
            'ACTIVE_INPUT',
            'ACTIVE_DRAG',
            'FILE_TRANSFER',
            'FORM_SUBMISSION',
            'PAYMENT_OR_SECURITY',
            'SNAPSHOT',
            'PROFILE_FLUSH',
            'CRITICAL_TRANSACTION',
            'BUSINESS_RECOVERY_UNKNOWN'
        )
    ),
    CHECK (expires_at >= observed_at)
);

CREATE INDEX idx_session_safety_signals_assessment
    ON session_safety_signals(session_id, expires_at DESC);

COMMENT ON TABLE session_safety_signals IS
    'Expiring, source-attributed observations for fail-closed migration and hibernation safety decisions';
