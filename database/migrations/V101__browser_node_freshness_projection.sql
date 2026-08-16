-- Durable Browser Node heartbeat freshness transitions for payload-free SSE invalidation.

CREATE TABLE browser_node_freshness_projections (
    node_id          TEXT PRIMARY KEY REFERENCES browser_nodes(node_id) ON DELETE CASCADE,
    freshness_state  TEXT NOT NULL,
    observed_at      TIMESTAMPTZ NOT NULL,
    transitioned_at  TIMESTAMPTZ NOT NULL,
    CONSTRAINT chk_browser_node_freshness_state CHECK (
        freshness_state IN ('FRESH', 'STALE')
    )
);

COMMENT ON TABLE browser_node_freshness_projections IS
    'Cross-Control-Plane deduplication state for Browser Node FRESH/STALE transitions';
