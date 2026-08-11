-- Multi-dimensional State Resync admission and actual-consumption settlement.
-- Existing V082 token limits remain as a coarse abuse/circuit guard. New requests additionally
-- reserve bytes and Browser collection CPU against Session, Tenant, Region and owning Node.

ALTER TABLE state_resync_requests
    ADD COLUMN node_id TEXT,
    ADD COLUMN region TEXT,
    ADD COLUMN region_weight_percent INTEGER NOT NULL DEFAULT 100,
    ADD COLUMN estimated_bytes BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN reserved_bytes BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN actual_bytes BIGINT,
    ADD COLUMN estimated_cpu_millis BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN reserved_cpu_millis BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN actual_cpu_millis BIGINT,
    ADD COLUMN budget_state TEXT NOT NULL DEFAULT 'RESERVED',
    ADD COLUMN settled_at TIMESTAMPTZ,
    ADD CONSTRAINT chk_state_resync_region_weight
        CHECK (region_weight_percent BETWEEN 25 AND 100) NOT VALID,
    ADD CONSTRAINT chk_state_resync_estimated_bytes
        CHECK (estimated_bytes BETWEEN 0 AND 524288) NOT VALID,
    ADD CONSTRAINT chk_state_resync_reserved_bytes
        CHECK (reserved_bytes BETWEEN 0 AND 524288) NOT VALID,
    ADD CONSTRAINT chk_state_resync_actual_bytes
        CHECK (actual_bytes IS NULL OR actual_bytes BETWEEN 1 AND 524288) NOT VALID,
    ADD CONSTRAINT chk_state_resync_estimated_cpu
        CHECK (estimated_cpu_millis BETWEEN 0 AND 300000) NOT VALID,
    ADD CONSTRAINT chk_state_resync_reserved_cpu
        CHECK (reserved_cpu_millis BETWEEN 0 AND 300000) NOT VALID,
    ADD CONSTRAINT chk_state_resync_actual_cpu
        CHECK (actual_cpu_millis IS NULL OR actual_cpu_millis BETWEEN 0 AND 300000) NOT VALID,
    ADD CONSTRAINT chk_state_resync_budget_state
        CHECK (budget_state IN ('RESERVED', 'SETTLED')) NOT VALID,
    ADD CONSTRAINT chk_state_resync_settlement
        CHECK ((budget_state = 'RESERVED' AND settled_at IS NULL)
            OR (budget_state = 'SETTLED' AND settled_at IS NOT NULL)) NOT VALID;

ALTER TABLE browser_state_snapshot_streams
    ADD COLUMN collection_cpu_millis BIGINT,
    ADD CONSTRAINT chk_browser_state_snapshot_collection_cpu
        CHECK (collection_cpu_millis IS NULL
            OR collection_cpu_millis BETWEEN 0 AND 300000) NOT VALID;

COMMENT ON COLUMN state_resync_requests.reserved_bytes IS
    'Admission-time worst-case byte reservation; replaced by actual_bytes after Node settlement';
COMMENT ON COLUMN state_resync_requests.actual_cpu_millis IS
    'Browser Runtime cgroup CPU time consumed by the Resync collection; null when unavailable';
COMMENT ON COLUMN state_resync_requests.region_weight_percent IS
    'PRIMARY/unknown=100, SECONDARY=75, DR=50 capacity weight captured at admission';

-- Existing requests intentionally remain unattributed to Node/Region and keep zero reservations.
-- Their bounded five-minute admission window expires naturally, avoiding a production table rewrite.
