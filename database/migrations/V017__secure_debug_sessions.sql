CREATE TABLE secure_debug_sessions (
    debug_session_id        TEXT PRIMARY KEY,
    break_glass_request_id  TEXT NOT NULL UNIQUE REFERENCES break_glass_requests(request_id),
    tenant_id               TEXT NOT NULL,
    resource_type           TEXT NOT NULL,
    resource_id             TEXT NOT NULL,
    operator_id             TEXT NOT NULL,
    state                   TEXT NOT NULL,
    started_at              TIMESTAMPTZ NOT NULL,
    expires_at              TIMESTAMPTZ NOT NULL,
    ended_at                TIMESTAMPTZ,
    end_reason              TEXT,
    access_count            INTEGER NOT NULL DEFAULT 0,
    event_sequence          BIGINT NOT NULL DEFAULT 0,
    last_access_at          TIMESTAMPTZ,
    evidence_head_hash      TEXT,
    version                 BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT chk_secure_debug_state CHECK (
        state IN ('ACTIVE', 'ENDED', 'EXPIRED', 'REVOKED')
    ),
    CONSTRAINT chk_secure_debug_resource CHECK (
        resource_type = 'SESSION'
    ),
    CONSTRAINT chk_secure_debug_expiry CHECK (
        expires_at > started_at
    ),
    CONSTRAINT chk_secure_debug_counts CHECK (
        access_count >= 0 AND event_sequence >= 0
    )
);

CREATE TABLE secure_debug_access_events (
    access_event_id      TEXT PRIMARY KEY,
    debug_session_id     TEXT NOT NULL REFERENCES secure_debug_sessions(debug_session_id),
    tenant_id            TEXT NOT NULL,
    sequence_no          BIGINT NOT NULL,
    actor_id             TEXT NOT NULL,
    action               TEXT NOT NULL,
    result               TEXT NOT NULL,
    field_projection     TEXT NOT NULL,
    previous_event_hash  TEXT,
    evidence_hash        TEXT NOT NULL UNIQUE,
    occurred_at          TIMESTAMPTZ NOT NULL,
    UNIQUE (debug_session_id, sequence_no),
    CONSTRAINT chk_secure_debug_event_action CHECK (
        action IN (
            'START', 'SNAPSHOT', 'END', 'AUTO_EXPIRE', 'GRANT_REVOKED', 'ACCESS_DENIED'
        )
    )
);

CREATE INDEX idx_secure_debug_tenant_started
ON secure_debug_sessions(tenant_id, started_at DESC);

CREATE INDEX idx_secure_debug_active_expiry
ON secure_debug_sessions(expires_at)
WHERE state = 'ACTIVE';

CREATE INDEX idx_secure_debug_access_session
ON secure_debug_access_events(debug_session_id, sequence_no);

COMMENT ON TABLE secure_debug_sessions IS
'Single-use, time-bound Secure Debug sessions bound to an approved break-glass grant';

COMMENT ON TABLE secure_debug_access_events IS
'Append-only access recording manifest; it stores field projections and evidence hashes, never raw sensitive browser data';
