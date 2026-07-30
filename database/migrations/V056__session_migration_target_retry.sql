-- Expand-only migration for bounded cross-Node restore retries.
--
-- PostgreSQL 17 installs constant defaults as metadata-only changes. N-1 writers can omit every
-- new column and continue creating the pre-existing migration phases during a rolling deployment.

ALTER TABLE session_migrations
  ADD COLUMN target_attempt INTEGER NOT NULL DEFAULT 0,
  ADD COLUMN maximum_target_attempts INTEGER NOT NULL DEFAULT 3,
  ADD COLUMN failed_target_node_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
  ADD COLUMN target_cleanup_operation_id TEXT,
  ADD COLUMN last_target_failure_reason TEXT;

ALTER TABLE session_migrations
  ADD CONSTRAINT session_migrations_target_attempt_check
    CHECK (
      target_attempt BETWEEN 0 AND maximum_target_attempts
      AND maximum_target_attempts BETWEEN 1 AND 10
    ) NOT VALID,
  ADD CONSTRAINT session_migrations_failed_target_nodes_check
    CHECK (jsonb_typeof(failed_target_node_ids) = 'array') NOT VALID;

ALTER TABLE session_migrations
  VALIDATE CONSTRAINT session_migrations_target_attempt_check;

ALTER TABLE session_migrations
  VALIDATE CONSTRAINT session_migrations_failed_target_nodes_check;

ALTER TABLE session_migrations
  DROP CONSTRAINT session_migrations_phase_check;

ALTER TABLE session_migrations
  ADD CONSTRAINT session_migrations_phase_check CHECK (
    phase IN (
      'CHECKPOINTING',
      'PLACING_TARGET',
      'RESTORING',
      'TARGET_CLEANUP',
      'STATE_RESYNC',
      'BUSINESS_VALIDATION',
      'BUSINESS_RECOVERY_ACTION',
      'COMPLETED',
      'DEGRADED',
      'FAILED'
    )
  ) NOT VALID;

ALTER TABLE session_migrations
  VALIDATE CONSTRAINT session_migrations_phase_check;
