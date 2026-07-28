CREATE TABLE workspace_groups (
    group_id TEXT PRIMARY KEY,
    tenant_id TEXT NOT NULL,
    name TEXT NOT NULL,
    description TEXT,
    color TEXT NOT NULL DEFAULT '#35D6BE',
    default_on_maximum_reached TEXT NOT NULL DEFAULT 'PAUSE_AGENT',
    default_allow_migration BOOLEAN NOT NULL DEFAULT TRUE,
    default_allow_hibernate BOOLEAN NOT NULL DEFAULT TRUE,
    created_by TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT chk_workspace_groups_id
      CHECK (group_id ~ '^grp_[a-zA-Z0-9]{16,32}$'),
    CONSTRAINT chk_workspace_groups_name
      CHECK (char_length(btrim(name)) BETWEEN 1 AND 96),
    CONSTRAINT chk_workspace_groups_description
      CHECK (description IS NULL OR char_length(description) <= 512),
    CONSTRAINT chk_workspace_groups_color
      CHECK (color ~ '^#[0-9A-Fa-f]{6}$'),
    CONSTRAINT chk_workspace_groups_maximum_policy
      CHECK (default_on_maximum_reached IN (
        'PAUSE_AGENT', 'WAIT_SAFE_POINT_MIGRATE', 'HIBERNATE', 'TERMINATE_STRICT'
      ))
);

CREATE UNIQUE INDEX uq_workspace_groups_tenant_name
  ON workspace_groups (tenant_id, lower(btrim(name)));

CREATE INDEX idx_workspace_groups_tenant_updated
  ON workspace_groups (tenant_id, updated_at DESC);

ALTER TABLE sessions
  ADD COLUMN group_id TEXT;

ALTER TABLE sessions
  ADD CONSTRAINT fk_sessions_workspace_group
    FOREIGN KEY (group_id) REFERENCES workspace_groups(group_id) ON DELETE SET NULL
    NOT VALID;

ALTER TABLE sessions
  VALIDATE CONSTRAINT fk_sessions_workspace_group;

CREATE INDEX idx_sessions_tenant_group
  ON sessions (tenant_id, group_id, created_at DESC);
