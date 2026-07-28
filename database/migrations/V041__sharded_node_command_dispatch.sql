-- Expand-only migration for shard-aware Node Command dispatch.
--
-- Rollout order:
--   1. Apply V041 and deploy the new Control Plane while Browser Nodes still accept legacy
--      route_epoch=0 envelopes.
--   2. Drain legacy unpublished Node Commands.
--   3. Enable NODE_REQUIRE_ROUTE_EPOCH on Browser Nodes.
--
-- N-1 writers leave these columns NULL and N dispatchers continue to drain them through the
-- compatibility path. No existing column or index is removed in this release.

ALTER TABLE outbox_events
  ADD COLUMN route_epoch BIGINT,
  ADD COLUMN coordinator_shard_id INTEGER,
  ADD COLUMN dispatch_owner TEXT,
  ADD COLUMN dispatch_lease_until TIMESTAMPTZ;

CREATE TABLE coordinator_dispatch_workers (
    worker_id                       TEXT PRIMARY KEY,
    started_at                      TIMESTAMPTZ NOT NULL,
    heartbeat_at                    TIMESTAMPTZ NOT NULL,
    lease_until                     TIMESTAMPTZ NOT NULL,
    CONSTRAINT chk_coordinator_dispatch_worker_id
      CHECK (length(worker_id) BETWEEN 1 AND 200),
    CONSTRAINT chk_coordinator_dispatch_worker_lease
      CHECK (lease_until >= heartbeat_at)
);

CREATE INDEX idx_coordinator_dispatch_workers_lease
  ON coordinator_dispatch_workers(lease_until, worker_id);

ALTER TABLE outbox_events
  ADD CONSTRAINT chk_outbox_route_epoch
  CHECK (route_epoch IS NULL OR route_epoch > 0) NOT VALID;

ALTER TABLE outbox_events
  ADD CONSTRAINT chk_outbox_coordinator_shard
  CHECK (coordinator_shard_id IS NULL OR coordinator_shard_id BETWEEN 0 AND 4095) NOT VALID;

ALTER TABLE outbox_events
  ADD CONSTRAINT chk_outbox_route_binding_complete
  CHECK ((route_epoch IS NULL) = (coordinator_shard_id IS NULL)) NOT VALID;

ALTER TABLE outbox_events
  ADD CONSTRAINT chk_outbox_dispatch_lease_owner
  CHECK (
    (dispatch_owner IS NULL AND dispatch_lease_until IS NULL)
    OR (dispatch_owner IS NOT NULL AND dispatch_lease_until IS NOT NULL)
  ) NOT VALID;

ALTER TABLE outbox_events VALIDATE CONSTRAINT chk_outbox_route_epoch;
ALTER TABLE outbox_events VALIDATE CONSTRAINT chk_outbox_coordinator_shard;
ALTER TABLE outbox_events VALIDATE CONSTRAINT chk_outbox_route_binding_complete;
ALTER TABLE outbox_events VALIDATE CONSTRAINT chk_outbox_dispatch_lease_owner;

COMMENT ON COLUMN outbox_events.route_epoch IS
  'Route Epoch captured atomically when a Node Command enters the transactional Outbox';
COMMENT ON COLUMN outbox_events.coordinator_shard_id IS
  'Physical dispatch partition derived from the authoritative Session route';
COMMENT ON COLUMN outbox_events.dispatch_owner IS
  'Short-lived shard dispatcher claim owner; never the Session Coordinator owner';
COMMENT ON COLUMN outbox_events.dispatch_lease_until IS
  'Expiry for crash-recoverable SKIP LOCKED Node Command dispatch claims';
COMMENT ON TABLE coordinator_dispatch_workers IS
  'Leased physical dispatch workers used by Rendezvous Hash to assign Coordinator Shards';
