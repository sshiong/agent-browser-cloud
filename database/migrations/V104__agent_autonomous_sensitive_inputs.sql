ALTER TABLE sessions
  ADD COLUMN agent_control_mode TEXT NOT NULL DEFAULT 'SAFE',
  ADD COLUMN agent_sensitive_input_max_attempts SMALLINT NOT NULL DEFAULT 3;

ALTER TABLE sessions
  ADD CONSTRAINT chk_sessions_agent_control_mode
    CHECK (agent_control_mode IN ('SAFE', 'AUTONOMOUS')) NOT VALID,
  ADD CONSTRAINT chk_sessions_agent_sensitive_input_attempts
    CHECK (agent_sensitive_input_max_attempts BETWEEN 1 AND 10) NOT VALID;
ALTER TABLE sessions VALIDATE CONSTRAINT chk_sessions_agent_control_mode;
ALTER TABLE sessions VALIDATE CONSTRAINT chk_sessions_agent_sensitive_input_attempts;

CREATE TABLE agent_input_secrets (
    secret_id          TEXT PRIMARY KEY,
    tenant_id          TEXT NOT NULL,
    session_id         TEXT NOT NULL,
    purpose            TEXT NOT NULL,
    sealed_value       TEXT NOT NULL,
    value_length       INTEGER NOT NULL,
    idempotency_key    TEXT NOT NULL,
    request_fingerprint TEXT NOT NULL,
    created_by         TEXT NOT NULL,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at         TIMESTAMPTZ NOT NULL,
    consumed_at        TIMESTAMPTZ,
    consumed_by_task   TEXT,
    CONSTRAINT fk_agent_input_secret_session
      FOREIGN KEY (session_id) REFERENCES sessions(id) ON DELETE CASCADE,
    CONSTRAINT chk_agent_input_secret_id
      CHECK (secret_id ~ '^ais_[A-Za-z0-9]{20,32}$'),
    CONSTRAINT chk_agent_input_secret_purpose
      CHECK (purpose IN ('USERNAME', 'PASSWORD', 'OTP')),
    CONSTRAINT chk_agent_input_secret_value_length
      CHECK (value_length BETWEEN 1 AND 2000),
    CONSTRAINT chk_agent_input_secret_idempotency_key
      CHECK (idempotency_key ~ '^[A-Za-z0-9._:-]{1,128}$'),
    CONSTRAINT chk_agent_input_secret_fingerprint
      CHECK (request_fingerprint ~ '^[a-f0-9]{64}$'),
    CONSTRAINT chk_agent_input_secret_expiry
      CHECK (expires_at > created_at),
    CONSTRAINT chk_agent_input_secret_consumption
      CHECK ((consumed_at IS NULL) = (consumed_by_task IS NULL)),
    CONSTRAINT chk_agent_input_secret_consuming_task
      CHECK (consumed_by_task IS NULL OR consumed_by_task ~ '^agt_[A-Za-z0-9]{16,32}$')
);

CREATE UNIQUE INDEX uq_agent_input_secret_idempotency
  ON agent_input_secrets(tenant_id, session_id, idempotency_key);

CREATE INDEX idx_agent_input_secrets_available
  ON agent_input_secrets(tenant_id, session_id, expires_at)
  WHERE consumed_at IS NULL;

COMMENT ON COLUMN sessions.agent_control_mode IS
  'SAFE requires existing sensitive-action gates; AUTONOMOUS permits purpose-bound one-time credential/OTP input without repeated confirmation.';
COMMENT ON TABLE agent_input_secrets IS
  'Tenant/session scoped, encrypted, one-time Agent input values. Plaintext is never returned or stored in plans/audit.';
