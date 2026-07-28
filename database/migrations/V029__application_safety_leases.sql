-- Durable application-side Safe Point leases.
--
-- Browser/CDP signals cannot infer payment, account-security or application transaction
-- semantics. An application adapter acquires a short lease before such work, renews it while the
-- transaction remains active and releases it after the business commit/rollback is known.
--
-- The current lease row is optimized for Safe Point assessment. The append-only event table is
-- the auditable, resumable source for Web SSE invalidation. It reuses the per-Session cursor
-- introduced by V026, so changes remain commit ordered across resource and safety writers.

CREATE TABLE session_safety_leases (
    lease_id          TEXT PRIMARY KEY,
    session_id        TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
    tenant_id         TEXT NOT NULL,
    context_epoch     BIGINT NOT NULL,
    signal_type       TEXT NOT NULL,
    reason_code       TEXT NOT NULL,
    owner_actor_id    TEXT NOT NULL,
    state             TEXT NOT NULL DEFAULT 'ACTIVE',
    acquired_at       TIMESTAMPTZ NOT NULL,
    renewed_at        TIMESTAMPTZ NOT NULL,
    expires_at        TIMESTAMPTZ NOT NULL,
    released_at       TIMESTAMPTZ,
    version           BIGINT NOT NULL DEFAULT 0,
    CHECK (
        signal_type IN (
            'FILE_TRANSFER',
            'FORM_SUBMISSION',
            'PAYMENT_OR_SECURITY',
            'CRITICAL_TRANSACTION',
            'BUSINESS_RECOVERY_UNKNOWN'
        )
    ),
    CHECK (reason_code ~ '^[A-Z0-9_.-]{1,64}$'),
    CHECK (owner_actor_id ~ '^[A-Za-z0-9_-]{1,128}$'),
    CHECK (state IN ('ACTIVE', 'RELEASED', 'EXPIRED')),
    CHECK (expires_at > acquired_at),
    CHECK (
        (state = 'ACTIVE' AND released_at IS NULL)
        OR (state IN ('RELEASED', 'EXPIRED') AND released_at IS NOT NULL)
    )
);

CREATE INDEX idx_session_safety_leases_active
    ON session_safety_leases(session_id, context_epoch, expires_at)
    WHERE state = 'ACTIVE';

CREATE INDEX idx_session_safety_leases_timeline
    ON session_safety_leases(session_id, acquired_at DESC);

CREATE TABLE session_safety_lease_events (
    event_id          TEXT PRIMARY KEY,
    lease_id          TEXT NOT NULL REFERENCES session_safety_leases(lease_id) ON DELETE CASCADE,
    session_id        TEXT NOT NULL,
    tenant_id         TEXT NOT NULL,
    event_type        TEXT NOT NULL,
    expires_at        TIMESTAMPTZ NOT NULL,
    occurred_at       TIMESTAMPTZ NOT NULL,
    stream_sequence   BIGINT NOT NULL,
    CHECK (event_type IN ('ACQUIRED', 'RENEWED', 'RELEASED', 'EXPIRED'))
);

CREATE TRIGGER assign_safety_lease_event_stream_sequence
BEFORE INSERT ON session_safety_lease_events
FOR EACH ROW
EXECUTE FUNCTION assign_session_resource_stream_sequence();

CREATE UNIQUE INDEX uq_session_safety_lease_events_stream_sequence
    ON session_safety_lease_events(tenant_id, session_id, stream_sequence);

CREATE INDEX idx_session_safety_lease_events_lease
    ON session_safety_lease_events(lease_id, occurred_at);

COMMENT ON TABLE session_safety_leases IS
    'Short-lived owner-bound application business activity leases that block migration and hibernation';

COMMENT ON TABLE session_safety_lease_events IS
    'Append-only, commit-ordered safety lease changes used for audit and resumable Session SSE';
