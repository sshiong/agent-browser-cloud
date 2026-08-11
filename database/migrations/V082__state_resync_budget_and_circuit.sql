-- PostgreSQL-authoritative State Resync admission ledger.
--
-- The ledger is intentionally append-only inside its retention window. Control Plane instances
-- acquire tenant/session transaction advisory locks before reading it, so concurrent Coordinators
-- cannot race past a shared budget. FULL and REGION consume different weighted tokens.

CREATE TABLE state_resync_requests (
    request_id          TEXT PRIMARY KEY,
    tenant_id           TEXT NOT NULL,
    session_id          TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
    mode                TEXT NOT NULL,
    source              TEXT NOT NULL,
    reason              TEXT NOT NULL,
    root_ref_hash       TEXT,
    token_cost          INTEGER NOT NULL,
    requested_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_state_resync_request_id
        CHECK (request_id ~ '^cmd_[a-zA-Z0-9]{16,}$'),
    CONSTRAINT chk_state_resync_mode
        CHECK (mode IN ('FULL', 'REGION')),
    CONSTRAINT chk_state_resync_source
        CHECK (source IN ('USER', 'AUTOMATIC')),
    CONSTRAINT chk_state_resync_reason
        CHECK (char_length(reason) BETWEEN 1 AND 128),
    CONSTRAINT chk_state_resync_root_ref_hash
        CHECK (root_ref_hash IS NULL OR root_ref_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT chk_state_resync_token_cost
        CHECK ((mode = 'FULL' AND token_cost = 10)
            OR (mode = 'REGION' AND token_cost = 2))
);

CREATE INDEX idx_state_resync_requests_session_budget
    ON state_resync_requests(session_id, requested_at DESC);

CREATE INDEX idx_state_resync_requests_tenant_budget
    ON state_resync_requests(tenant_id, requested_at DESC);

CREATE INDEX idx_state_resync_requests_automatic_circuit
    ON state_resync_requests(session_id, requested_at DESC)
    WHERE source = 'AUTOMATIC' AND mode = 'FULL';

COMMENT ON TABLE state_resync_requests IS
    'Bounded State Resync admission ledger and automatic Full Resync circuit evidence';
COMMENT ON COLUMN state_resync_requests.root_ref_hash IS
    'SHA-256 of REGION root_ref; raw selectors and target references are not retained';
