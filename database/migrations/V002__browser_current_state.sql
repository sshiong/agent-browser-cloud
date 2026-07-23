CREATE TABLE browser_states (
    session_id        TEXT PRIMARY KEY REFERENCES sessions(id) ON DELETE CASCADE,
    tenant_id         TEXT NOT NULL,
    context_epoch     BIGINT NOT NULL CHECK (context_epoch >= 0),
    state_version     BIGINT NOT NULL CHECK (state_version > 0),
    state_json        JSONB NOT NULL,
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_browser_states_tenant_updated
    ON browser_states (tenant_id, updated_at DESC);
