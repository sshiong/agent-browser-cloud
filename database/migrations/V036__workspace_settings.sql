-- Authoritative tenant Workspace Settings and immutable HumanTakeover binding.

CREATE TABLE workspace_settings (
  tenant_id TEXT PRIMARY KEY,
  workspace_name TEXT NOT NULL,
  default_runtime_build_id TEXT NOT NULL,
  default_region TEXT NOT NULL,
  default_human_takeover_enabled BOOLEAN NOT NULL DEFAULT TRUE,
  updated_by TEXT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  CONSTRAINT fk_workspace_settings_runtime
    FOREIGN KEY (default_runtime_build_id)
      REFERENCES runtime_builds(build_id),
  CONSTRAINT chk_workspace_settings_name
    CHECK (char_length(btrim(workspace_name)) BETWEEN 1 AND 96),
  CONSTRAINT chk_workspace_settings_region
    CHECK (default_region ~ '^[a-z0-9-]{1,32}$')
);

ALTER TABLE sessions
  ADD COLUMN human_takeover_enabled BOOLEAN NOT NULL DEFAULT TRUE;

COMMENT ON TABLE workspace_settings IS
  'Tenant-owned authoritative defaults for new Browser Sessions';

COMMENT ON COLUMN sessions.human_takeover_enabled IS
  'Immutable create-time binding controlling whether HumanTakeover can be acquired';
