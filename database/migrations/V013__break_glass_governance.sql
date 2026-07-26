CREATE TABLE break_glass_requests (
    request_id          TEXT PRIMARY KEY,
    tenant_id           TEXT NOT NULL,
    ticket_id           TEXT NOT NULL,
    reason              TEXT NOT NULL,
    resource_type       TEXT NOT NULL,
    resource_id         TEXT NOT NULL,
    requested_scope     TEXT NOT NULL,
    state               TEXT NOT NULL,
    requested_by        TEXT NOT NULL,
    approved_by         TEXT,
    rejected_by         TEXT,
    revoked_by          TEXT,
    evidence_hash       TEXT,
    requested_at        TIMESTAMPTZ NOT NULL,
    approved_at         TIMESTAMPTZ,
    rejected_at         TIMESTAMPTZ,
    revoked_at          TIMESTAMPTZ,
    expires_at          TIMESTAMPTZ NOT NULL,
    reviewed_at         TIMESTAMPTZ,
    CONSTRAINT chk_break_glass_state CHECK (
        state IN ('REQUESTED', 'ACTIVE', 'REJECTED', 'REVOKED', 'EXPIRED')
    ),
    CONSTRAINT chk_break_glass_approver_separation CHECK (
        approved_by IS NULL OR approved_by <> requested_by
    ),
    CONSTRAINT chk_break_glass_expiry CHECK (
        expires_at > requested_at
        AND expires_at <= requested_at + INTERVAL '60 minutes'
    )
);

CREATE INDEX idx_break_glass_tenant_created
ON break_glass_requests(tenant_id, requested_at DESC);

CREATE INDEX idx_break_glass_active_expiry
ON break_glass_requests(expires_at)
WHERE state = 'ACTIVE';

COMMENT ON TABLE break_glass_requests IS
'Time-bound, dual-control emergency access requests; every transition is also appended to the tenant audit chain';
