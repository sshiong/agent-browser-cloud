-- Durable Agent action-attempt history used for restart-safe loop detection.
-- Only hashes and bounded reason codes are retained; plaintext input and capability material are
-- deliberately excluded.
CREATE TABLE agent_action_attempts (
    attempt_id             TEXT PRIMARY KEY,
    tenant_id              TEXT NOT NULL,
    session_id             TEXT NOT NULL,
    task_id                TEXT NOT NULL REFERENCES agent_tasks(task_id) ON DELETE CASCADE,
    operation_id           TEXT NOT NULL,
    step_id                TEXT NOT NULL,
    tool_id                TEXT NOT NULL,
    action_descriptor_hash TEXT NOT NULL CHECK (length(action_descriptor_hash) = 64),
    base_state_version     BIGINT NOT NULL CHECK (base_state_version >= 0),
    base_state_hash        TEXT NOT NULL,
    attempt_signature      TEXT NOT NULL CHECK (length(attempt_signature) = 64),
    consecutive_count      SMALLINT NOT NULL CHECK (consecutive_count BETWEEN 1 AND 3),
    status                 TEXT NOT NULL,
    failure_code           TEXT,
    result_state_version   BIGINT CHECK (
      result_state_version IS NULL OR result_state_version >= 0
    ),
    result_state_hash      TEXT,
    created_at             TIMESTAMPTZ NOT NULL,
    completed_at           TIMESTAMPTZ,
    CONSTRAINT fk_agent_action_attempt_session
      FOREIGN KEY (session_id, tenant_id)
        REFERENCES sessions(id, tenant_id) ON DELETE CASCADE,
    CONSTRAINT chk_agent_action_attempt_status
      CHECK (status IN ('RESERVED', 'DISPATCHED', 'VERIFIED', 'FAILED', 'ABANDONED', 'LOOP_BLOCKED')),
    CONSTRAINT chk_agent_action_attempt_failure
      CHECK (
        (status IN ('FAILED', 'ABANDONED', 'LOOP_BLOCKED') AND failure_code IS NOT NULL)
        OR (status NOT IN ('FAILED', 'ABANDONED', 'LOOP_BLOCKED'))
      )
);

CREATE INDEX idx_agent_action_attempts_task_timeline
  ON agent_action_attempts (task_id, created_at DESC, attempt_id DESC);

CREATE INDEX idx_agent_action_attempts_signature
  ON agent_action_attempts (task_id, attempt_signature, created_at DESC);

COMMENT ON TABLE agent_action_attempts IS
  'Hash-only execution history for restart-safe Agent action loop detection';

COMMENT ON COLUMN agent_action_attempts.attempt_signature IS
  'SHA-256 of normalized action descriptor hash and authoritative pre-action state hash';
