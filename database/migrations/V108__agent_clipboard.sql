-- AgentClipboard is deliberately separate from the VNC/X11 UserClipboard. Its content is
-- encrypted by the Control Plane and never forwarded through RFB unless a future explicit bridge
-- is approved and implemented.
CREATE TABLE agent_clipboards (
    session_id        TEXT PRIMARY KEY,
    tenant_id         TEXT NOT NULL,
    sealed_value      TEXT,
    content_hash      TEXT,
    value_length      INTEGER NOT NULL DEFAULT 0,
    version           BIGINT NOT NULL DEFAULT 0,
    updated_by        TEXT NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL,
    updated_at        TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_agent_clipboard_session
      FOREIGN KEY (session_id, tenant_id) REFERENCES sessions(id, tenant_id) ON DELETE CASCADE,
    CONSTRAINT chk_agent_clipboard_length CHECK (value_length BETWEEN 0 AND 2000),
    CONSTRAINT chk_agent_clipboard_version CHECK (version >= 0),
    CONSTRAINT chk_agent_clipboard_hash
      CHECK (content_hash IS NULL OR content_hash ~ '^[a-f0-9]{64}$'),
    CONSTRAINT chk_agent_clipboard_empty
      CHECK ((sealed_value IS NULL) = (content_hash IS NULL) AND (sealed_value IS NULL) = (value_length = 0))
);

CREATE INDEX idx_agent_clipboard_tenant
  ON agent_clipboards(tenant_id, updated_at DESC);

COMMENT ON TABLE agent_clipboards IS
  'Encrypted Agent-only clipboard; never aliases the real VNC/X11 UserClipboard';
