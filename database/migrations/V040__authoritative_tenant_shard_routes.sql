-- PostgreSQL-authoritative hot-tenant virtual partition routing.
--
-- The migration is strictly additive:
--   * N-1 code continues to ignore the new route tables and route_epoch column.
--   * New code lazily binds Sessions that were created by N-1 during a rolling deploy.
--   * Route migration fences the existing Coordinator term before a Session can move.

CREATE TABLE coordinator_tenant_routes (
    tenant_id                       TEXT PRIMARY KEY,
    state                           TEXT NOT NULL DEFAULT 'STABLE',
    active_virtual_partitions       INTEGER NOT NULL DEFAULT 1,
    active_route_epoch              BIGINT NOT NULL DEFAULT 1,
    pending_virtual_partitions      INTEGER,
    pending_route_epoch             BIGINT,
    active_migration_id             TEXT,
    version                         BIGINT NOT NULL DEFAULT 1,
    created_at                      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_coordinator_tenant_route_state
      CHECK (state IN ('STABLE', 'MIGRATING')),
    CONSTRAINT chk_coordinator_tenant_route_active_partitions
      CHECK (active_virtual_partitions BETWEEN 1 AND 256),
    CONSTRAINT chk_coordinator_tenant_route_active_epoch
      CHECK (active_route_epoch > 0),
    CONSTRAINT chk_coordinator_tenant_route_pending
      CHECK (
        (
          state = 'STABLE'
          AND pending_virtual_partitions IS NULL
          AND pending_route_epoch IS NULL
          AND active_migration_id IS NULL
        )
        OR
        (
          state = 'MIGRATING'
          AND pending_virtual_partitions BETWEEN 1 AND 256
          AND pending_route_epoch > active_route_epoch
          AND active_migration_id IS NOT NULL
        )
      )
);

CREATE TABLE coordinator_route_migrations (
    migration_id                    TEXT PRIMARY KEY,
    tenant_id                       TEXT NOT NULL
      REFERENCES coordinator_tenant_routes(tenant_id),
    source_route_epoch              BIGINT NOT NULL,
    target_route_epoch              BIGINT NOT NULL,
    source_virtual_partitions       INTEGER NOT NULL,
    target_virtual_partitions       INTEGER NOT NULL,
    state                           TEXT NOT NULL,
    total_sessions                  INTEGER NOT NULL DEFAULT 0,
    migrated_sessions               INTEGER NOT NULL DEFAULT 0,
    blocked_sessions                INTEGER NOT NULL DEFAULT 0,
    requested_by                    TEXT NOT NULL,
    request_id                      TEXT NOT NULL,
    failure_code                    TEXT,
    created_at                      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                      TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at                    TIMESTAMPTZ,
    CONSTRAINT chk_coordinator_route_migration_epochs
      CHECK (source_route_epoch > 0 AND target_route_epoch > source_route_epoch),
    CONSTRAINT chk_coordinator_route_migration_partitions
      CHECK (
        source_virtual_partitions BETWEEN 1 AND 256
        AND target_virtual_partitions BETWEEN 1 AND 256
      ),
    CONSTRAINT chk_coordinator_route_migration_state
      CHECK (state IN ('MIGRATING', 'COMMITTED', 'FAILED')),
    CONSTRAINT chk_coordinator_route_migration_counts
      CHECK (
        total_sessions >= 0
        AND migrated_sessions >= 0
        AND blocked_sessions >= 0
        AND migrated_sessions <= total_sessions
        AND blocked_sessions <= total_sessions
      ),
    CONSTRAINT chk_coordinator_route_migration_terminal
      CHECK (
        (state = 'MIGRATING' AND completed_at IS NULL AND failure_code IS NULL)
        OR
        (state = 'COMMITTED' AND completed_at IS NOT NULL AND failure_code IS NULL)
        OR
        (state = 'FAILED' AND completed_at IS NOT NULL AND failure_code IS NOT NULL)
      )
);

ALTER TABLE coordinator_tenant_routes
  ADD CONSTRAINT fk_coordinator_tenant_route_active_migration
  FOREIGN KEY (active_migration_id)
  REFERENCES coordinator_route_migrations(migration_id)
  DEFERRABLE INITIALLY DEFERRED;

CREATE UNIQUE INDEX uq_coordinator_route_migration_active_tenant
  ON coordinator_route_migrations(tenant_id)
  WHERE state = 'MIGRATING';

CREATE INDEX idx_coordinator_route_migration_reconcile
  ON coordinator_route_migrations(state, updated_at);

CREATE TABLE coordinator_session_routes (
    session_id                      TEXT PRIMARY KEY,
    tenant_id                       TEXT NOT NULL,
    route_epoch                     BIGINT NOT NULL,
    virtual_partition               INTEGER NOT NULL,
    shard_id                        INTEGER NOT NULL,
    bound_at                        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_coordinator_session_route_session
      FOREIGN KEY (session_id, tenant_id)
      REFERENCES sessions(id, tenant_id)
      ON DELETE CASCADE,
    CONSTRAINT chk_coordinator_session_route_epoch
      CHECK (route_epoch > 0),
    CONSTRAINT chk_coordinator_session_route_partition
      CHECK (virtual_partition BETWEEN 0 AND 255),
    CONSTRAINT chk_coordinator_session_route_shard
      CHECK (shard_id BETWEEN 0 AND 4095)
);

CREATE INDEX idx_coordinator_session_route_tenant_epoch
  ON coordinator_session_routes(tenant_id, route_epoch, session_id);

ALTER TABLE coordinator_ownership
  ADD COLUMN route_epoch BIGINT NOT NULL DEFAULT 1;

ALTER TABLE coordinator_ownership
  ADD CONSTRAINT chk_coordinator_ownership_route_epoch
  CHECK (route_epoch > 0) NOT VALID;

ALTER TABLE coordinator_ownership
  VALIDATE CONSTRAINT chk_coordinator_ownership_route_epoch;

COMMENT ON TABLE coordinator_tenant_routes IS
  'PostgreSQL authority for tenant virtual partitions and monotonically increasing Route Epoch';

COMMENT ON TABLE coordinator_session_routes IS
  'Per-Session immutable-at-an-epoch route binding used to migrate hot tenants incrementally';

COMMENT ON TABLE coordinator_route_migrations IS
  'Durable hot-tenant route migration progress and safe-point reconciliation state';

COMMENT ON COLUMN coordinator_ownership.route_epoch IS
  'Route Epoch fenced together with Coordinator Term; stale owners cannot heartbeat';
