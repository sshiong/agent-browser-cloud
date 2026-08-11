ALTER TABLE state_resync_requests
    VALIDATE CONSTRAINT chk_state_resync_region_weight,
    VALIDATE CONSTRAINT chk_state_resync_estimated_bytes,
    VALIDATE CONSTRAINT chk_state_resync_reserved_bytes,
    VALIDATE CONSTRAINT chk_state_resync_actual_bytes,
    VALIDATE CONSTRAINT chk_state_resync_estimated_cpu,
    VALIDATE CONSTRAINT chk_state_resync_reserved_cpu,
    VALIDATE CONSTRAINT chk_state_resync_actual_cpu,
    VALIDATE CONSTRAINT chk_state_resync_budget_state,
    VALIDATE CONSTRAINT chk_state_resync_settlement;

ALTER TABLE browser_state_snapshot_streams
    VALIDATE CONSTRAINT chk_browser_state_snapshot_collection_cpu;
