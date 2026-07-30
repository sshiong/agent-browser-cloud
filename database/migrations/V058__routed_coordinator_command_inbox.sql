-- Durable physical routing for Session Coordinator API, timer and workflow commands.
--
-- Commands enter this PostgreSQL inbox before application transactions begin. The active
-- coordinator_dispatch_workers membership and Rendezvous Hash select the only physical worker
-- allowed to claim a Session shard. Execution and result commit share one database transaction;
-- a crashed worker therefore cannot leave an application mutation committed without its command
-- result. Existing N-1 applications ignore this additive table.

CREATE TABLE coordinator_commands (
  command_id TEXT PRIMARY KEY,
  tenant_id TEXT NOT NULL,
  session_id TEXT NOT NULL,
  route_epoch BIGINT NOT NULL,
  coordinator_shard_id INTEGER NOT NULL,
  command_type TEXT NOT NULL,
  deduplication_key TEXT NOT NULL,
  payload JSONB NOT NULL,
  state TEXT NOT NULL DEFAULT 'PENDING',
  result JSONB,
  failure_code TEXT,
  attempt INTEGER NOT NULL DEFAULT 0,
  claim_owner TEXT,
  claim_lease_until TIMESTAMPTZ,
  next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  deadline_at TIMESTAMPTZ NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  started_at TIMESTAMPTZ,
  completed_at TIMESTAMPTZ,
  CONSTRAINT coordinator_commands_tenant_deduplication
    UNIQUE (tenant_id, deduplication_key)
);

ALTER TABLE coordinator_commands
  ADD CONSTRAINT coordinator_commands_session_fk
    FOREIGN KEY (session_id, tenant_id)
    REFERENCES sessions(id, tenant_id)
    ON DELETE CASCADE NOT VALID,
  ADD CONSTRAINT coordinator_commands_state_check
    CHECK (state IN ('PENDING', 'EXECUTING', 'COMMITTED', 'FAILED')) NOT VALID,
  ADD CONSTRAINT coordinator_commands_route_epoch_check
    CHECK (route_epoch > 0) NOT VALID,
  ADD CONSTRAINT coordinator_commands_shard_check
    CHECK (coordinator_shard_id BETWEEN 0 AND 4095) NOT VALID,
  ADD CONSTRAINT coordinator_commands_attempt_check
    CHECK (attempt BETWEEN 0 AND 20) NOT VALID,
  ADD CONSTRAINT coordinator_commands_claim_check
    CHECK (
      (claim_owner IS NULL AND claim_lease_until IS NULL)
      OR
      (claim_owner IS NOT NULL AND claim_lease_until IS NOT NULL)
    ) NOT VALID,
  ADD CONSTRAINT coordinator_commands_terminal_result_check
    CHECK (
      (state = 'COMMITTED' AND result IS NOT NULL AND failure_code IS NULL)
      OR (state = 'FAILED' AND result IS NULL AND failure_code IS NOT NULL)
      OR (state IN ('PENDING', 'EXECUTING') AND result IS NULL AND failure_code IS NULL)
    ) NOT VALID;

ALTER TABLE coordinator_commands
  VALIDATE CONSTRAINT coordinator_commands_session_fk;

ALTER TABLE coordinator_commands
  VALIDATE CONSTRAINT coordinator_commands_state_check;

ALTER TABLE coordinator_commands
  VALIDATE CONSTRAINT coordinator_commands_route_epoch_check;

ALTER TABLE coordinator_commands
  VALIDATE CONSTRAINT coordinator_commands_shard_check;

ALTER TABLE coordinator_commands
  VALIDATE CONSTRAINT coordinator_commands_attempt_check;

ALTER TABLE coordinator_commands
  VALIDATE CONSTRAINT coordinator_commands_claim_check;

ALTER TABLE coordinator_commands
  VALIDATE CONSTRAINT coordinator_commands_terminal_result_check;

CREATE INDEX idx_coordinator_commands_ready
  ON coordinator_commands (next_attempt_at, created_at, command_id)
  WHERE state = 'PENDING';

CREATE INDEX idx_coordinator_commands_session_history
  ON coordinator_commands (tenant_id, session_id, created_at DESC);

COMMENT ON TABLE coordinator_commands IS
  'Durable API/timer/workflow command inbox claimed by the physical worker owning a Session shard';
COMMENT ON COLUMN coordinator_commands.route_epoch IS
  'Authoritative Session Route Epoch captured when the command is submitted';
COMMENT ON COLUMN coordinator_commands.coordinator_shard_id IS
  'Authoritative logical Coordinator Shard used for physical Rendezvous worker selection';
COMMENT ON COLUMN coordinator_commands.deduplication_key IS
  'Tenant-scoped source identity; never contains credentials or raw user input';
