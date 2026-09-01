-- Explicit, actor-bound transfers between the encrypted AgentClipboard and the RFB/X11
-- UserClipboard. Neither plaintext nor a second ciphertext copy is written to this ledger.
CREATE TABLE agent_clipboard_bridges (
    bridge_id                  TEXT PRIMARY KEY,
    tenant_id                 TEXT NOT NULL,
    session_id                TEXT NOT NULL,
    actor_id                  TEXT NOT NULL,
    connection_id             TEXT NOT NULL,
    direction                 TEXT NOT NULL,
    purpose                   TEXT NOT NULL,
    idempotency_key           TEXT NOT NULL,
    request_hash              TEXT NOT NULL,
    agent_clipboard_version   BIGINT NOT NULL,
    content_hash              TEXT NOT NULL,
    value_length              INTEGER NOT NULL,
    state                     TEXT NOT NULL,
    expires_at                TIMESTAMPTZ NOT NULL,
    completed_at              TIMESTAMPTZ,
    created_at                TIMESTAMPTZ NOT NULL,
    updated_at                TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_agent_clipboard_bridge_session
      FOREIGN KEY (session_id, tenant_id) REFERENCES sessions(id, tenant_id) ON DELETE CASCADE,
    CONSTRAINT uq_agent_clipboard_bridge_idempotency
      UNIQUE (tenant_id, actor_id, idempotency_key),
    CONSTRAINT chk_agent_clipboard_bridge_id CHECK (bridge_id ~ '^acb_[A-Za-z0-9]{20}$'),
    CONSTRAINT chk_agent_clipboard_bridge_connection
      CHECK (connection_id ~ '^rdc_[A-Za-z0-9]{20}$'),
    CONSTRAINT chk_agent_clipboard_bridge_direction
      CHECK (direction IN ('USER_TO_AGENT', 'AGENT_TO_USER')),
    CONSTRAINT chk_agent_clipboard_bridge_purpose
      CHECK (purpose IN ('OPERATOR_COPY', 'AUTOMATION_HANDOFF', 'HUMAN_ASSISTANCE')),
    CONSTRAINT chk_agent_clipboard_bridge_request_hash
      CHECK (request_hash ~ '^[a-f0-9]{64}$'),
    CONSTRAINT chk_agent_clipboard_bridge_version CHECK (agent_clipboard_version > 0),
    CONSTRAINT chk_agent_clipboard_bridge_content_hash
      CHECK (content_hash ~ '^[a-f0-9]{64}$'),
    CONSTRAINT chk_agent_clipboard_bridge_length CHECK (value_length BETWEEN 1 AND 2000),
    CONSTRAINT chk_agent_clipboard_bridge_state
      CHECK (state IN ('ISSUED', 'COMPLETED', 'EXPIRED')),
    CONSTRAINT chk_agent_clipboard_bridge_completion
      CHECK ((state = 'COMPLETED') = (completed_at IS NOT NULL)),
    CONSTRAINT chk_agent_clipboard_bridge_expiry CHECK (expires_at > created_at)
);

CREATE INDEX idx_agent_clipboard_bridges_session
  ON agent_clipboard_bridges(tenant_id, session_id, created_at DESC);

CREATE INDEX idx_agent_clipboard_bridges_expiry
  ON agent_clipboard_bridges(expires_at)
  WHERE state = 'ISSUED';

COMMENT ON TABLE agent_clipboard_bridges IS
  'Explicit purpose-bound AgentClipboard/UserClipboard bridge ledger; content is never persisted';
