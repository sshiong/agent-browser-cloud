-- Tenant-authoritative Environment Saved Views.
--
-- A Saved View stores only validated filter/column configuration. It never stores
-- Session result snapshots, credentials or browser data. Personal views are visible
-- only to their owner; Workspace views are tenant-visible and admin-governed.

CREATE TABLE environment_saved_views (
    saved_view_id          TEXT PRIMARY KEY,
    tenant_id              TEXT NOT NULL,
    owner_actor_id         TEXT NOT NULL,
    scope                  TEXT NOT NULL,
    name                   TEXT NOT NULL,
    primary_view           TEXT NOT NULL,
    session_state          TEXT,
    search_query           TEXT NOT NULL DEFAULT '',
    show_runtime_column    BOOLEAN NOT NULL DEFAULT true,
    show_context_column    BOOLEAN NOT NULL DEFAULT true,
    show_operation_column  BOOLEAN NOT NULL DEFAULT true,
    created_at             TIMESTAMPTZ NOT NULL,
    updated_at             TIMESTAMPTZ NOT NULL,
    version                BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT chk_environment_saved_view_id CHECK (
        saved_view_id ~ '^svw_[a-zA-Z0-9]{16,32}$'
    ),
    CONSTRAINT chk_environment_saved_view_owner CHECK (
        owner_actor_id ~ '^[A-Za-z0-9_-]{1,128}$'
    ),
    CONSTRAINT chk_environment_saved_view_scope CHECK (
        scope IN ('PERSONAL', 'WORKSPACE')
    ),
    CONSTRAINT chk_environment_saved_view_name CHECK (
        char_length(btrim(name)) BETWEEN 1 AND 64
    ),
    CONSTRAINT chk_environment_saved_view_primary CHECK (
        primary_view IN ('ALL', 'RUNNING', 'STOPPED', 'ABNORMAL')
    ),
    CONSTRAINT chk_environment_saved_view_state CHECK (
        session_state IS NULL OR session_state IN (
            'CREATED',
            'STARTING',
            'RUNNING',
            'DEGRADED',
            'HIBERNATING',
            'HIBERNATED',
            'RECOVERING',
            'TERMINATING',
            'TERMINATED',
            'FAILED'
        )
    ),
    CONSTRAINT chk_environment_saved_view_query CHECK (
        char_length(search_query) <= 128
    ),
    UNIQUE (saved_view_id, tenant_id)
);

CREATE UNIQUE INDEX uq_environment_saved_view_personal_name
ON environment_saved_views (
    tenant_id,
    owner_actor_id,
    lower(btrim(name))
)
WHERE scope = 'PERSONAL';

CREATE UNIQUE INDEX uq_environment_saved_view_workspace_name
ON environment_saved_views (
    tenant_id,
    lower(btrim(name))
)
WHERE scope = 'WORKSPACE';

CREATE INDEX idx_environment_saved_view_visible
ON environment_saved_views (
    tenant_id,
    scope,
    owner_actor_id,
    updated_at DESC
);

COMMENT ON TABLE environment_saved_views IS
    'Validated tenant-authoritative Environment filter and column presets';
