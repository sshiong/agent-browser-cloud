-- Safe Point controlled proxy rebind reuses the durable checkpoint/restore workflow.
-- New columns are nullable or have defaults so N-1 Control Planes can continue node migrations.

ALTER TABLE session_migrations
  ADD COLUMN workflow_type TEXT NOT NULL DEFAULT 'NODE_MIGRATION',
  ADD COLUMN source_proxy_allocation_id TEXT,
  ADD COLUMN source_proxy_binding_profile_id TEXT,
  ADD COLUMN target_proxy_binding_profile_id TEXT,
  ADD COLUMN target_proxy_binding_version BIGINT,
  ADD COLUMN requested_by TEXT,
  ADD COLUMN request_reason TEXT,
  ADD COLUMN idempotency_key TEXT,
  ADD COLUMN request_id TEXT;

ALTER TABLE session_migrations
  ADD CONSTRAINT session_migrations_workflow_type_check
    CHECK (workflow_type IN ('NODE_MIGRATION', 'PROXY_REBIND')) NOT VALID,
  ADD CONSTRAINT session_migrations_proxy_rebind_snapshot_check
    CHECK (
      (workflow_type = 'NODE_MIGRATION'
        AND target_proxy_binding_profile_id IS NULL
        AND target_proxy_binding_version IS NULL
        AND idempotency_key IS NULL)
      OR
      (workflow_type = 'PROXY_REBIND'
        AND target_proxy_binding_profile_id IS NOT NULL
        AND target_proxy_binding_version IS NOT NULL
        AND requested_by IS NOT NULL
        AND request_reason IS NOT NULL
        AND idempotency_key IS NOT NULL
        AND request_id IS NOT NULL)
    ) NOT VALID,
  ADD CONSTRAINT session_migrations_target_proxy_binding_fk
    FOREIGN KEY (target_proxy_binding_profile_id, tenant_id)
    REFERENCES proxy_binding_profiles(binding_profile_id, tenant_id)
    ON DELETE RESTRICT NOT VALID;

ALTER TABLE session_migrations
  VALIDATE CONSTRAINT session_migrations_workflow_type_check;

ALTER TABLE session_migrations
  VALIDATE CONSTRAINT session_migrations_proxy_rebind_snapshot_check;

ALTER TABLE session_migrations
  VALIDATE CONSTRAINT session_migrations_target_proxy_binding_fk;

CREATE UNIQUE INDEX uq_session_proxy_rebind_idempotency
  ON session_migrations(tenant_id, idempotency_key)
  WHERE workflow_type = 'PROXY_REBIND';

CREATE INDEX idx_session_proxy_rebind_latest
  ON session_migrations(session_id, created_at DESC)
  WHERE workflow_type = 'PROXY_REBIND';

COMMENT ON COLUMN session_migrations.workflow_type IS
  'NODE_MIGRATION or Safe Point controlled PROXY_REBIND checkpoint/restore workflow';
