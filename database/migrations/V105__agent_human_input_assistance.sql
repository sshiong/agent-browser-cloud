-- Durable operator-supplied OTP response for an AUTONOMOUS Agent that has exhausted automation.
-- The plaintext remains in the existing one-time agent_input_secrets envelope and is re-sealed for
-- the Node command. This table stores ciphertext and bounded metadata only.

CREATE TABLE agent_challenge_input_intents (
    intent_id            TEXT PRIMARY KEY,
    tenant_id            TEXT NOT NULL,
    session_id           TEXT NOT NULL,
    task_id              TEXT NOT NULL,
    challenge_event_id   TEXT NOT NULL,
    secret_id            TEXT NOT NULL,
    purpose              TEXT NOT NULL,
    target_ref           TEXT NOT NULL,
    target_revision      BIGINT NOT NULL,
    base_state_version   BIGINT NOT NULL,
    sealed_value         TEXT NOT NULL,
    value_length         INTEGER NOT NULL,
    maximum_attempts     SMALLINT NOT NULL,
    idempotency_key      TEXT NOT NULL,
    actor_id             TEXT NOT NULL,
    request_id           TEXT NOT NULL,
    operation_id         TEXT NOT NULL,
    step_id              TEXT NOT NULL,
    state                 TEXT NOT NULL DEFAULT 'EXECUTING',
    error_code            TEXT,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at            TIMESTAMPTZ NOT NULL,
    completed_at          TIMESTAMPTZ,
    version               BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_agent_challenge_input_session
      FOREIGN KEY (session_id) REFERENCES sessions(id) ON DELETE CASCADE,
    CONSTRAINT fk_agent_challenge_input_task
      FOREIGN KEY (task_id) REFERENCES agent_tasks(task_id) ON DELETE CASCADE,
    CONSTRAINT fk_agent_challenge_input_event
      FOREIGN KEY (challenge_event_id) REFERENCES challenge_events(challenge_event_id) ON DELETE CASCADE,
    CONSTRAINT fk_agent_challenge_input_secret
      FOREIGN KEY (secret_id) REFERENCES agent_input_secrets(secret_id) ON DELETE RESTRICT,
    CONSTRAINT chk_agent_challenge_input_id
      CHECK (intent_id ~ '^aci_[A-Za-z0-9]{20}$'),
    CONSTRAINT chk_agent_challenge_input_purpose
      CHECK (purpose = 'OTP'),
    CONSTRAINT chk_agent_challenge_input_target_revision
      CHECK (target_revision > 0),
    CONSTRAINT chk_agent_challenge_input_state_version
      CHECK (base_state_version > 0),
    CONSTRAINT chk_agent_challenge_input_length
      CHECK (value_length BETWEEN 1 AND 2000),
    CONSTRAINT chk_agent_challenge_input_attempts
      CHECK (maximum_attempts BETWEEN 1 AND 10),
    CONSTRAINT chk_agent_challenge_input_state
      CHECK (state IN ('EXECUTING', 'COMMITTED', 'FAILED', 'EXPIRED')),
    CONSTRAINT chk_agent_challenge_input_expiry
      CHECK (expires_at > created_at),
    CONSTRAINT chk_agent_challenge_input_terminal
      CHECK ((completed_at IS NULL) = (state = 'EXECUTING'))
);

CREATE UNIQUE INDEX uq_agent_challenge_input_idempotency
  ON agent_challenge_input_intents(tenant_id, session_id, idempotency_key);

CREATE UNIQUE INDEX uq_agent_challenge_input_operation
  ON agent_challenge_input_intents(operation_id);

CREATE UNIQUE INDEX uq_agent_challenge_input_step
  ON agent_challenge_input_intents(task_id, step_id);

CREATE INDEX idx_agent_challenge_input_expiry
  ON agent_challenge_input_intents(expires_at)
  WHERE state = 'EXECUTING';

-- V87 intentionally permitted target-bound input only for SINGLE_CLICK. Keep historical OTP rows
-- (which have no target) readable by N-1 instances while allowing new detectors to bind an OTP
-- textbox. The application still requires a non-null, current sensitive target before typing.
ALTER TABLE challenge_events
  ADD CONSTRAINT chk_challenge_event_target_v2
    CHECK (
      (suspected_type = 'SINGLE_CLICK' AND target_ref IS NOT NULL
        AND visual_anchor_hash ~ '^[a-f0-9]{64}$')
      OR
      (suspected_type = 'OTP' AND visual_anchor_hash IS NULL)
      OR
      (suspected_type NOT IN ('SINGLE_CLICK', 'OTP') AND target_ref IS NULL
        AND visual_anchor_hash IS NULL)
    ) NOT VALID;
ALTER TABLE challenge_events VALIDATE CONSTRAINT chk_challenge_event_target_v2;
ALTER TABLE challenge_events DROP CONSTRAINT chk_challenge_event_target;
ALTER TABLE challenge_events
  RENAME CONSTRAINT chk_challenge_event_target_v2 TO chk_challenge_event_target;

ALTER TABLE session_event_envelopes
  ADD CONSTRAINT chk_session_event_envelope_change_type_v5
    CHECK (change_type IN (
      'SESSION', 'BROWSER_STATE', 'AUDIT_EVENT', 'OPERATION', 'AGENT_TASK',
      'RESOURCE_SAMPLE', 'RESOURCE_EVENT', 'SAFETY_LEASE_EVENT',
      'CHALLENGE_EVENT', 'HUMAN_ASSIST_INTENT', 'REMOTE_DESKTOP_PARTICIPANT',
      'CHALLENGE_AUTOMATION', 'AGENT_HUMAN_INPUT'
    )) NOT VALID;
ALTER TABLE session_event_envelopes
  VALIDATE CONSTRAINT chk_session_event_envelope_change_type_v5;
ALTER TABLE session_event_envelopes DROP CONSTRAINT chk_session_event_envelope_change_type;
ALTER TABLE session_event_envelopes
  RENAME CONSTRAINT chk_session_event_envelope_change_type_v5
    TO chk_session_event_envelope_change_type;

CREATE FUNCTION append_agent_challenge_input_envelope()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    PERFORM append_session_event_envelope(
      NEW.tenant_id, NEW.session_id, 'AGENT_HUMAN_INPUT', NEW.intent_id,
      COALESCE(NEW.completed_at, NEW.created_at)
    );
    RETURN NEW;
END;
$$;

CREATE TRIGGER append_agent_challenge_input_envelope
AFTER INSERT OR UPDATE ON agent_challenge_input_intents
FOR EACH ROW EXECUTE FUNCTION append_agent_challenge_input_envelope();

COMMENT ON TABLE agent_challenge_input_intents IS
  'Tenant-scoped one-time operator OTP responses that let an AUTONOMOUS Agent continue its original paused task without forcing takeover.';
