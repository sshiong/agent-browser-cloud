-- V123: Website authentication health is independent from technical Profile restore readiness.

CREATE TABLE profile_site_session_health (
    health_id                           TEXT PRIMARY KEY,
    tenant_id                           TEXT NOT NULL,
    profile_id                          TEXT NOT NULL REFERENCES profiles(profile_id) ON DELETE CASCADE,
    site_origin                         TEXT NOT NULL,
    application_id                      TEXT,
    health_state                        TEXT NOT NULL,
    reason_code                         TEXT NOT NULL,
    source_session_id                   TEXT REFERENCES sessions(id) ON DELETE SET NULL,
    context_epoch                       BIGINT NOT NULL CHECK (context_epoch >= 0),
    state_version                       BIGINT NOT NULL CHECK (state_version >= 0),
    checked_at                          TIMESTAMPTZ NOT NULL,
    fresh_until                         TIMESTAMPTZ NOT NULL,
    authenticated_at                    TIMESTAMPTZ,
    reauth_required_at                  TIMESTAMPTZ,
    created_at                          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                          TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, profile_id, site_origin),
    CONSTRAINT chk_profile_site_session_health_state CHECK (
        health_state IN ('HEALTHY', 'REAUTH_REQUIRED', 'DEGRADED')
    ),
    CONSTRAINT chk_profile_site_session_health_freshness CHECK (fresh_until >= checked_at)
);

CREATE INDEX idx_profile_site_session_health_profile
ON profile_site_session_health(tenant_id, profile_id, updated_at DESC);

COMMENT ON TABLE profile_site_session_health IS
    'Profile-scoped website authentication observations derived from fenced Business Recovery evidence; independent from checkpoint restore readiness';
