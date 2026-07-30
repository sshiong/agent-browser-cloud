-- Durable per-actor appearance preferences shared by Web and Tauri clients.
--
-- The migration is additive. Existing actors resolve to SYSTEM without a backfill,
-- so N-1 application versions remain compatible and no identity directory scan is required.

CREATE TABLE workspace_user_preferences (
    tenant_id  TEXT NOT NULL,
    actor_id   TEXT NOT NULL,
    theme_mode TEXT NOT NULL DEFAULT 'SYSTEM',
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version    BIGINT NOT NULL DEFAULT 1,
    PRIMARY KEY (tenant_id, actor_id)
);

ALTER TABLE workspace_user_preferences
  ADD CONSTRAINT chk_workspace_user_preference_tenant
  CHECK (char_length(btrim(tenant_id)) BETWEEN 1 AND 128) NOT VALID,
  ADD CONSTRAINT chk_workspace_user_preference_actor
  CHECK (char_length(btrim(actor_id)) BETWEEN 1 AND 256) NOT VALID,
  ADD CONSTRAINT chk_workspace_user_preference_theme
  CHECK (theme_mode IN ('SYSTEM', 'DARK', 'LIGHT')) NOT VALID,
  ADD CONSTRAINT chk_workspace_user_preference_version
  CHECK (version >= 1) NOT VALID,
  ADD CONSTRAINT chk_workspace_user_preference_time
  CHECK (updated_at >= created_at) NOT VALID;

ALTER TABLE workspace_user_preferences
  VALIDATE CONSTRAINT chk_workspace_user_preference_tenant;
ALTER TABLE workspace_user_preferences
  VALIDATE CONSTRAINT chk_workspace_user_preference_actor;
ALTER TABLE workspace_user_preferences
  VALIDATE CONSTRAINT chk_workspace_user_preference_theme;
ALTER TABLE workspace_user_preferences
  VALIDATE CONSTRAINT chk_workspace_user_preference_version;
ALTER TABLE workspace_user_preferences
  VALIDATE CONSTRAINT chk_workspace_user_preference_time;

COMMENT ON TABLE workspace_user_preferences IS
  'Tenant and actor scoped UI preferences shared by authenticated Web and Tauri clients';
COMMENT ON COLUMN workspace_user_preferences.theme_mode IS
  'SYSTEM follows the current operating-system preference; DARK and LIGHT are explicit';
