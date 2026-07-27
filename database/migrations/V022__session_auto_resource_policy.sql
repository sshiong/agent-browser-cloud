-- Session-scoped automatic resource policy, authoritative telemetry and adjustment history.

CREATE TABLE session_resource_policies (
    session_id                              TEXT PRIMARY KEY REFERENCES sessions(id) ON DELETE CASCADE,
    tenant_id                               TEXT NOT NULL,
    mode                                    TEXT NOT NULL DEFAULT 'AUTO',
    execution_environment                   TEXT NOT NULL DEFAULT 'SYSTEM_MANAGED',
    minimum_template                        TEXT NOT NULL DEFAULT 'standard-v1',
    resolved_template                       TEXT NOT NULL DEFAULT 'standard-v1',
    maximum_cpu_millis                      INTEGER NOT NULL DEFAULT 4000 CHECK (maximum_cpu_millis > 0),
    maximum_memory_mib                      INTEGER NOT NULL DEFAULT 4096 CHECK (maximum_memory_mib > 0),
    maximum_cost_per_hour                   NUMERIC(12,4),
    scale_up_window_seconds                 INTEGER NOT NULL DEFAULT 60 CHECK (scale_up_window_seconds BETWEEN 30 AND 900),
    scale_down_window_seconds               INTEGER NOT NULL DEFAULT 1200 CHECK (scale_down_window_seconds BETWEEN 300 AND 86400),
    adjustment_cooldown_seconds             INTEGER NOT NULL DEFAULT 300 CHECK (adjustment_cooldown_seconds BETWEEN 60 AND 3600),
    allow_migration                         BOOLEAN NOT NULL DEFAULT TRUE,
    allow_hibernate                         BOOLEAN NOT NULL DEFAULT TRUE,
    block_migration_during_human_takeover   BOOLEAN NOT NULL DEFAULT TRUE,
    on_maximum_reached                      TEXT NOT NULL DEFAULT 'PAUSE_AGENT',
    status                                  TEXT NOT NULL DEFAULT 'OBSERVING',
    status_reason                           TEXT,
    last_evaluated_at                       TIMESTAMPTZ,
    last_adjusted_at                        TIMESTAMPTZ,
    created_at                              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                              TIMESTAMPTZ NOT NULL DEFAULT now(),
    version                                 BIGINT NOT NULL DEFAULT 0,
    CHECK (mode = 'AUTO'),
    CHECK (execution_environment IN ('SYSTEM_MANAGED','CONTAINER','ENHANCED_SANDBOX','MICROVM','NATIVE_OS')),
    CHECK (on_maximum_reached IN ('PAUSE_AGENT','WAIT_SAFE_POINT_MIGRATE','HIBERNATE','TERMINATE_STRICT')),
    CHECK (status IN ('STABLE','OBSERVING','SCALING_UP','SCALING_DOWN','AT_MAXIMUM','WAITING_SAFE_POINT','MIGRATING','AGENT_PAUSED','HIBERNATING','CRITICAL'))
);

CREATE INDEX idx_session_resource_policies_tenant
    ON session_resource_policies(tenant_id, updated_at DESC);

CREATE TABLE session_resource_samples (
    sample_id                       TEXT PRIMARY KEY,
    session_id                      TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
    tenant_id                       TEXT NOT NULL,
    node_id                         TEXT NOT NULL,
    cpu_percent                     NUMERIC(7,3) CHECK (cpu_percent BETWEEN 0 AND 100),
    memory_rss_mib                  INTEGER CHECK (memory_rss_mib >= 0),
    memory_psi_some_avg10           NUMERIC(7,4) CHECK (memory_psi_some_avg10 >= 0),
    renderer_count                  INTEGER CHECK (renderer_count >= 0),
    tab_count                       INTEGER CHECK (tab_count >= 0),
    main_thread_blocked_ms          INTEGER CHECK (main_thread_blocked_ms >= 0),
    agent_action_latency_ms         INTEGER CHECK (agent_action_latency_ms >= 0),
    state_diff_queue_depth          INTEGER CHECK (state_diff_queue_depth >= 0),
    profile_io_bytes_per_second     BIGINT CHECK (profile_io_bytes_per_second >= 0),
    extension_cpu_percent           NUMERIC(7,3) CHECK (extension_cpu_percent BETWEEN 0 AND 100),
    extension_memory_mib            INTEGER CHECK (extension_memory_mib >= 0),
    remote_desktop_frame_age_ms     INTEGER CHECK (remote_desktop_frame_age_ms >= 0),
    media_encoder_percent           NUMERIC(7,3) CHECK (media_encoder_percent BETWEEN 0 AND 100),
    danger_event                    TEXT,
    observed_at                     TIMESTAMPTZ NOT NULL,
    received_at                     TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (session_id, observed_at)
);

CREATE INDEX idx_session_resource_samples_window
    ON session_resource_samples(session_id, observed_at DESC);

CREATE TABLE session_resource_events (
    event_id                    TEXT PRIMARY KEY,
    session_id                  TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
    tenant_id                   TEXT NOT NULL,
    event_type                  TEXT NOT NULL,
    reason                      TEXT NOT NULL,
    old_resources              JSONB,
    new_resources              JSONB,
    decision_source            TEXT NOT NULL,
    operation_id               TEXT,
    request_id                 TEXT,
    result                     TEXT NOT NULL,
    occurred_at                TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_session_resource_events_timeline
    ON session_resource_events(session_id, occurred_at DESC);

COMMENT ON TABLE session_resource_policies IS
    'User-facing AUTO policy; resolved_template remains an internal scheduling detail';
COMMENT ON TABLE session_resource_samples IS
    'Real Browser Node session telemetry. The Control Plane never synthesizes samples';
COMMENT ON TABLE session_resource_events IS
    'Auditable resource policy, placement, pressure and adjustment timeline';
