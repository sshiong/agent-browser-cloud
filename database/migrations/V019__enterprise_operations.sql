CREATE TABLE runtime_validation_runs (
    validation_id              TEXT PRIMARY KEY,
    build_id                   TEXT NOT NULL REFERENCES runtime_builds(build_id),
    suite_version              TEXT NOT NULL,
    environment_digest         TEXT NOT NULL,
    replay_dataset_id          TEXT NOT NULL,
    persona                    TEXT NOT NULL,
    state                      TEXT NOT NULL,
    required_tests             INTEGER NOT NULL DEFAULT 0,
    required_failures          INTEGER NOT NULL DEFAULT 0,
    optional_tests             INTEGER NOT NULL DEFAULT 0,
    optional_failures          INTEGER NOT NULL DEFAULT 0,
    declared_capabilities      JSONB NOT NULL DEFAULT '{}',
    observed_capabilities      JSONB NOT NULL DEFAULT '{}',
    optional_failure_codes     JSONB NOT NULL DEFAULT '[]',
    evidence_hash              TEXT,
    requested_by               TEXT NOT NULL,
    started_at                 TIMESTAMPTZ NOT NULL,
    completed_at               TIMESTAMPTZ,
    CONSTRAINT chk_runtime_validation_state CHECK (
        state IN ('RUNNING', 'PASSED', 'DEGRADED', 'FAILED')
    ),
    CONSTRAINT chk_runtime_validation_counts CHECK (
        required_tests >= 0 AND required_failures >= 0
        AND optional_tests >= 0 AND optional_failures >= 0
        AND required_failures <= required_tests
        AND optional_failures <= optional_tests
    )
);

CREATE INDEX idx_runtime_validation_build
ON runtime_validation_runs(build_id, started_at DESC);

CREATE TABLE enterprise_cost_rates (
    pricing_version            TEXT PRIMARY KEY,
    region                     TEXT NOT NULL,
    resource_class             TEXT NOT NULL,
    base_hourly_usd            NUMERIC(12,6) NOT NULL,
    cpu_core_hourly_usd        NUMERIC(12,6) NOT NULL,
    memory_gib_hourly_usd      NUMERIC(12,6) NOT NULL,
    desktop_hourly_usd         NUMERIC(12,6) NOT NULL,
    gpu_hourly_usd             NUMERIC(12,6) NOT NULL,
    media_hourly_usd           NUMERIC(12,6) NOT NULL,
    effective_at               TIMESTAMPTZ NOT NULL,
    created_by                 TEXT NOT NULL,
    created_at                 TIMESTAMPTZ NOT NULL,
    CONSTRAINT chk_enterprise_cost_resource_class CHECK (
        resource_class IN ('L0', 'L1', 'L2', 'L3', 'L4', 'L5')
    ),
    CONSTRAINT chk_enterprise_cost_nonnegative CHECK (
        base_hourly_usd >= 0 AND cpu_core_hourly_usd >= 0
        AND memory_gib_hourly_usd >= 0 AND desktop_hourly_usd >= 0
        AND gpu_hourly_usd >= 0 AND media_hourly_usd >= 0
    )
);

CREATE INDEX idx_enterprise_cost_lookup
ON enterprise_cost_rates(region, resource_class, effective_at DESC);

CREATE TABLE enterprise_slo_policies (
    tenant_id                  TEXT PRIMARY KEY,
    availability_target       NUMERIC(7,6) NOT NULL,
    latency_p95_target_ms      INTEGER NOT NULL,
    window_minutes             INTEGER NOT NULL,
    updated_by                 TEXT NOT NULL,
    updated_at                 TIMESTAMPTZ NOT NULL,
    CONSTRAINT chk_enterprise_slo_availability CHECK (
        availability_target >= 0.900000 AND availability_target < 1.000000
    ),
    CONSTRAINT chk_enterprise_slo_limits CHECK (
        latency_p95_target_ms BETWEEN 1 AND 600000
        AND window_minutes BETWEEN 60 AND 527040
    )
);

CREATE TABLE enterprise_service_level_events (
    event_id                   TEXT PRIMARY KEY,
    tenant_id                  TEXT NOT NULL,
    event_type                 TEXT NOT NULL,
    duration_seconds           INTEGER NOT NULL,
    latency_p95_ms             INTEGER,
    source                     TEXT NOT NULL,
    occurred_at                TIMESTAMPTZ NOT NULL,
    recorded_at                TIMESTAMPTZ NOT NULL,
    CONSTRAINT chk_enterprise_sle_type CHECK (
        event_type IN ('UNAVAILABLE', 'LATENCY_BREACH', 'HEALTHY')
    ),
    CONSTRAINT chk_enterprise_sle_duration CHECK (
        duration_seconds >= 0
        AND (latency_p95_ms IS NULL OR latency_p95_ms >= 0)
    )
);

CREATE INDEX idx_enterprise_sle_tenant_time
ON enterprise_service_level_events(tenant_id, occurred_at DESC);

CREATE TABLE enterprise_retention_policies (
    tenant_id                  TEXT NOT NULL,
    data_class                 TEXT NOT NULL,
    retention_days             INTEGER NOT NULL,
    legal_hold                 BOOLEAN NOT NULL DEFAULT FALSE,
    residency_region           TEXT NOT NULL,
    updated_by                 TEXT NOT NULL,
    updated_at                 TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (tenant_id, data_class),
    CONSTRAINT chk_enterprise_retention_days CHECK (
        retention_days BETWEEN 1 AND 3650
    ),
    CONSTRAINT chk_enterprise_retention_class CHECK (
        data_class IN (
            'AUDIT', 'AGENT_EXECUTION', 'PROFILE_CHECKPOINT',
            'REMOTE_DESKTOP_RECORDING', 'SECURE_DEBUG'
        )
    )
);

CREATE TABLE enterprise_regions (
    region_id                  TEXT PRIMARY KEY,
    role                       TEXT NOT NULL,
    admission_state            TEXT NOT NULL,
    replication_lag_seconds    INTEGER NOT NULL,
    last_verified_at           TIMESTAMPTZ NOT NULL,
    updated_by                 TEXT NOT NULL,
    CONSTRAINT chk_enterprise_region_role CHECK (
        role IN ('PRIMARY', 'SECONDARY', 'DR')
    ),
    CONSTRAINT chk_enterprise_region_admission CHECK (
        admission_state IN ('OPEN', 'CLOSED', 'FAILOVER_READY')
    ),
    CONSTRAINT chk_enterprise_region_lag CHECK (replication_lag_seconds >= 0)
);

CREATE UNIQUE INDEX idx_enterprise_one_primary_region
ON enterprise_regions(role) WHERE role = 'PRIMARY';

CREATE TABLE enterprise_recovery_gamedays (
    gameday_id                 TEXT PRIMARY KEY,
    scenario                   TEXT NOT NULL,
    source_region              TEXT NOT NULL,
    target_region              TEXT NOT NULL,
    state                      TEXT NOT NULL,
    rto_target_seconds         INTEGER NOT NULL,
    rpo_target_seconds         INTEGER NOT NULL,
    observed_rto_seconds       INTEGER,
    observed_rpo_seconds       INTEGER,
    data_loss_records          INTEGER,
    evidence_hash              TEXT,
    started_by                 TEXT NOT NULL,
    started_at                 TIMESTAMPTZ NOT NULL,
    completed_at               TIMESTAMPTZ,
    CONSTRAINT chk_enterprise_gameday_state CHECK (
        state IN ('RUNNING', 'PASSED', 'FAILED')
    ),
    CONSTRAINT chk_enterprise_gameday_targets CHECK (
        rto_target_seconds BETWEEN 1 AND 86400
        AND rpo_target_seconds BETWEEN 0 AND 86400
    )
);

CREATE TABLE enterprise_compliance_snapshots (
    snapshot_id                TEXT PRIMARY KEY,
    tenant_id                  TEXT NOT NULL,
    framework                  TEXT NOT NULL,
    control_count              INTEGER NOT NULL,
    passing_controls           INTEGER NOT NULL,
    evidence_hash              TEXT NOT NULL,
    evidence                   JSONB NOT NULL,
    generated_by               TEXT NOT NULL,
    generated_at               TIMESTAMPTZ NOT NULL,
    CONSTRAINT chk_enterprise_compliance_counts CHECK (
        control_count > 0
        AND passing_controls BETWEEN 0 AND control_count
    )
);

CREATE INDEX idx_enterprise_compliance_tenant
ON enterprise_compliance_snapshots(tenant_id, generated_at DESC);

INSERT INTO enterprise_cost_rates(
    pricing_version, region, resource_class, base_hourly_usd,
    cpu_core_hourly_usd, memory_gib_hourly_usd, desktop_hourly_usd,
    gpu_hourly_usd, media_hourly_usd, effective_at, created_by, created_at
) VALUES
    ('local-l1-v1', 'local', 'L1', 0.010000, 0.040000, 0.006000, 0, 0, 0, now(), 'migration', now()),
    ('local-l2-v1', 'local', 'L2', 0.015000, 0.040000, 0.006000, 0, 0, 0, now(), 'migration', now()),
    ('local-l3-v1', 'local', 'L3', 0.020000, 0.040000, 0.006000, 0.010000, 0, 0, now(), 'migration', now()),
    ('local-l4-v1', 'local', 'L4', 0.030000, 0.040000, 0.006000, 0.010000, 0.500000, 0.050000, now(), 'migration', now()),
    ('local-l5-v1', 'local', 'L5', 0.050000, 0.040000, 0.006000, 0.010000, 0.500000, 0.050000, now(), 'migration', now());

INSERT INTO enterprise_regions(
    region_id, role, admission_state, replication_lag_seconds,
    last_verified_at, updated_by
) VALUES ('local', 'PRIMARY', 'OPEN', 0, now(), 'migration');

COMMENT ON TABLE runtime_validation_runs IS
'Build/environment/dataset-bound Runtime Validation Farm evidence';
COMMENT ON TABLE enterprise_cost_rates IS
'Versioned cost model used by scheduling and explainability';
COMMENT ON TABLE enterprise_slo_policies IS
'Tenant SLO and error-budget policy';
COMMENT ON TABLE enterprise_retention_policies IS
'Tenant retention, residency and legal-hold policy';
COMMENT ON TABLE enterprise_recovery_gamedays IS
'Measured RTO/RPO recovery exercises';
