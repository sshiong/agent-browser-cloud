-- Durable, bounded Business Recovery actions.
--
-- The action itself is selected from an enum owned by the Control Plane. Tenant
-- JavaScript, arbitrary CDP methods and arbitrary URLs are never persisted or sent.

ALTER TABLE application_recovery_contracts
  ADD COLUMN recovery_action TEXT NOT NULL DEFAULT 'NONE';

ALTER TABLE application_recovery_contracts
  ADD CONSTRAINT chk_application_recovery_action
    CHECK (recovery_action IN (
      'NONE',
      'RELOAD',
      'NAVIGATE_HOME',
      'REOPEN_KNOWN_ROUTE',
      'REFRESH_SESSION'
    )) NOT VALID;

ALTER TABLE application_recovery_contracts
  VALIDATE CONSTRAINT chk_application_recovery_action;

ALTER TABLE session_migrations
  DROP CONSTRAINT session_migrations_phase_check;

ALTER TABLE session_migrations
  ADD CONSTRAINT session_migrations_phase_check CHECK (
    phase IN (
      'CHECKPOINTING',
      'PLACING_TARGET',
      'RESTORING',
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

CREATE TABLE business_recovery_actions (
  action_id TEXT PRIMARY KEY,
  migration_id TEXT NOT NULL REFERENCES session_migrations(migration_id) ON DELETE CASCADE,
  session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
  tenant_id TEXT NOT NULL,
  contract_id TEXT NOT NULL,
  contract_version BIGINT NOT NULL,
  attempt_number INTEGER NOT NULL,
  action_type TEXT NOT NULL,
  target_url TEXT,
  base_state_version BIGINT NOT NULL,
  resulting_state_version BIGINT,
  state TEXT NOT NULL,
  command_message_id TEXT NOT NULL,
  error_code TEXT,
  deadline_at TIMESTAMPTZ NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  dispatched_at TIMESTAMPTZ,
  acknowledged_at TIMESTAMPTZ,
  completed_at TIMESTAMPTZ,
  updated_at TIMESTAMPTZ NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  CONSTRAINT chk_business_recovery_action_id
    CHECK (action_id ~ '^bra_[a-zA-Z0-9]{16,32}$'),
  CONSTRAINT chk_business_recovery_action_attempt
    CHECK (attempt_number BETWEEN 1 AND 10),
  CONSTRAINT chk_business_recovery_action_type
    CHECK (action_type IN (
      'RELOAD',
      'NAVIGATE_HOME',
      'REOPEN_KNOWN_ROUTE',
      'REFRESH_SESSION'
    )),
  CONSTRAINT chk_business_recovery_action_state
    CHECK (state IN (
      'REQUESTED',
      'EXECUTING',
      'ACKNOWLEDGED',
      'COMMITTED',
      'FAILED'
    )),
  CONSTRAINT chk_business_recovery_action_target
    CHECK (
      (action_type IN ('RELOAD', 'REFRESH_SESSION') AND target_url IS NULL)
      OR
      (action_type IN ('NAVIGATE_HOME', 'REOPEN_KNOWN_ROUTE')
        AND target_url ~ '^https?://')
    ),
  UNIQUE (migration_id, attempt_number),
  UNIQUE (command_message_id)
);

CREATE INDEX idx_business_recovery_actions_latest
  ON business_recovery_actions(migration_id, attempt_number DESC);

CREATE INDEX idx_business_recovery_actions_state
  ON business_recovery_actions(state, deadline_at);

COMMENT ON TABLE business_recovery_actions IS
  'Durable low-risk Business Recovery action attempts with Node execution acknowledgement';
