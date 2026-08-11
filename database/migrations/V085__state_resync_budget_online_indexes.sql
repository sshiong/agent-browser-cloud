CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_state_resync_requests_region_budget
    ON state_resync_requests(region, requested_at DESC)
    WHERE region IS NOT NULL;

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_state_resync_requests_node_budget
    ON state_resync_requests(node_id, requested_at DESC)
    WHERE node_id IS NOT NULL;

COMMENT ON INDEX idx_state_resync_requests_region_budget IS
    'Rolling-window Region byte and Browser collection CPU admission';
COMMENT ON INDEX idx_state_resync_requests_node_budget IS
    'Rolling-window Browser Node byte and Browser collection CPU admission';
