-- V018__browser_density_admission.sql
-- Browser Resource Class、Extension Weight、Node Admission 与 Placement 权威状态。

CREATE TABLE browser_nodes (
    node_id                 TEXT PRIMARY KEY,
    region                  TEXT NOT NULL,
    grpc_target             TEXT NOT NULL,
    lifecycle_state         TEXT NOT NULL DEFAULT 'READY',
    admission_state         TEXT NOT NULL DEFAULT 'OPEN',
    certified_cpu_millis    INTEGER NOT NULL CHECK (certified_cpu_millis > 0),
    certified_memory_mib    INTEGER NOT NULL CHECK (certified_memory_mib > 0),
    certified_pid_count     INTEGER NOT NULL CHECK (certified_pid_count > 0),
    certified_gpu_slots     INTEGER NOT NULL DEFAULT 0 CHECK (certified_gpu_slots >= 0),
    safety_margin_percent   INTEGER NOT NULL DEFAULT 15
        CHECK (safety_margin_percent BETWEEN 10 AND 40),
    reserved_cpu_millis     INTEGER NOT NULL DEFAULT 0 CHECK (reserved_cpu_millis >= 0),
    reserved_memory_mib     INTEGER NOT NULL DEFAULT 0 CHECK (reserved_memory_mib >= 0),
    reserved_pid_count      INTEGER NOT NULL DEFAULT 0 CHECK (reserved_pid_count >= 0),
    reserved_gpu_slots      INTEGER NOT NULL DEFAULT 0 CHECK (reserved_gpu_slots >= 0),
    active_sessions         INTEGER NOT NULL DEFAULT 0 CHECK (active_sessions >= 0),
    max_sessions            INTEGER NOT NULL CHECK (max_sessions > 0),
    memory_psi_some_avg10   NUMERIC(7,4) NOT NULL DEFAULT 0,
    memory_psi_full_avg10   NUMERIC(7,4) NOT NULL DEFAULT 0,
    cpu_psi_some_avg10      NUMERIC(7,4) NOT NULL DEFAULT 0,
    io_psi_full_avg10       NUMERIC(7,4) NOT NULL DEFAULT 0,
    pressure_state          TEXT NOT NULL DEFAULT 'NORMAL',
    pressure_reason         TEXT,
    pressure_recovery_streak INTEGER NOT NULL DEFAULT 0 CHECK (pressure_recovery_streak >= 0),
    supports_desktop        BOOLEAN NOT NULL DEFAULT FALSE,
    supports_gpu            BOOLEAN NOT NULL DEFAULT FALSE,
    supports_native_os      BOOLEAN NOT NULL DEFAULT FALSE,
    isolation_capable       BOOLEAN NOT NULL DEFAULT FALSE,
    labels                  JSONB NOT NULL DEFAULT '{}',
    last_heartbeat_at       TIMESTAMPTZ NOT NULL,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    version                 BIGINT NOT NULL DEFAULT 0,
    CHECK (reserved_cpu_millis <= certified_cpu_millis),
    CHECK (reserved_memory_mib <= certified_memory_mib),
    CHECK (reserved_pid_count <= certified_pid_count),
    CHECK (reserved_gpu_slots <= certified_gpu_slots)
);

CREATE INDEX idx_browser_nodes_placement
    ON browser_nodes(region, lifecycle_state, admission_state, pressure_state, last_heartbeat_at);

CREATE TABLE extension_profiles (
    extension_id                TEXT PRIMARY KEY,
    display_name                TEXT NOT NULL,
    static_cpu_weight           INTEGER NOT NULL CHECK (static_cpu_weight >= 0),
    static_memory_weight        INTEGER NOT NULL CHECK (static_memory_weight >= 0),
    startup_weight              INTEGER NOT NULL CHECK (startup_weight >= 0),
    page_injection_weight       INTEGER NOT NULL CHECK (page_injection_weight >= 0),
    service_worker_weight       INTEGER NOT NULL CHECK (service_worker_weight >= 0),
    crypto_weight               INTEGER NOT NULL CHECK (crypto_weight >= 0),
    network_weight              INTEGER NOT NULL CHECK (network_weight >= 0),
    observed_multiplier         NUMERIC(6,3) NOT NULL DEFAULT 1.000
        CHECK (observed_multiplier BETWEEN 0.500 AND 8.000),
    confidence                  NUMERIC(5,4) NOT NULL DEFAULT 0.0000
        CHECK (confidence BETWEEN 0 AND 1),
    profile_state               TEXT NOT NULL DEFAULT 'PROBATION',
    web3                        BOOLEAN NOT NULL DEFAULT FALSE,
    service_worker              BOOLEAN NOT NULL DEFAULT FALSE,
    crypto                      BOOLEAN NOT NULL DEFAULT FALSE,
    privileged                  BOOLEAN NOT NULL DEFAULT FALSE,
    samples                     BIGINT NOT NULL DEFAULT 0 CHECK (samples >= 0),
    p95_cpu_millis              INTEGER,
    p95_memory_mib              INTEGER,
    last_profiled_at            TIMESTAMPTZ,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    version                     BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_extension_profiles_state
    ON extension_profiles(profile_state, updated_at);

CREATE TABLE session_resource_demands (
    session_id                  TEXT PRIMARY KEY REFERENCES sessions(id) ON DELETE CASCADE,
    tenant_id                   TEXT NOT NULL,
    requested_resource_class    TEXT NOT NULL,
    requested_tabs              INTEGER NOT NULL DEFAULT 1 CHECK (requested_tabs BETWEEN 1 AND 64),
    agent_actions_per_minute    INTEGER NOT NULL DEFAULT 0
        CHECK (agent_actions_per_minute BETWEEN 0 AND 600),
    remote_desktop              BOOLEAN NOT NULL DEFAULT FALSE,
    web3_workload               BOOLEAN NOT NULL DEFAULT FALSE,
    extension_ids               JSONB NOT NULL DEFAULT '[]',
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_session_resource_demands_tenant
    ON session_resource_demands(tenant_id, created_at);

CREATE TABLE browser_placements (
    session_id                  TEXT PRIMARY KEY REFERENCES sessions(id) ON DELETE CASCADE,
    tenant_id                   TEXT NOT NULL,
    node_id                     TEXT NOT NULL REFERENCES browser_nodes(node_id),
    requested_resource_class    TEXT NOT NULL,
    effective_resource_class    TEXT NOT NULL,
    extension_ids               JSONB NOT NULL DEFAULT '[]',
    unknown_extension_count     INTEGER NOT NULL DEFAULT 0 CHECK (unknown_extension_count >= 0),
    cpu_millis                  INTEGER NOT NULL CHECK (cpu_millis >= 0),
    memory_request_mib          INTEGER NOT NULL CHECK (memory_request_mib >= 0),
    memory_limit_mib            INTEGER NOT NULL CHECK (memory_limit_mib >= memory_request_mib),
    pid_limit                   INTEGER NOT NULL CHECK (pid_limit >= 0),
    tab_budget                  INTEGER NOT NULL CHECK (tab_budget >= 0),
    requires_desktop            BOOLEAN NOT NULL DEFAULT FALSE,
    requires_gpu                BOOLEAN NOT NULL DEFAULT FALSE,
    requires_native_os          BOOLEAN NOT NULL DEFAULT FALSE,
    requires_isolation          BOOLEAN NOT NULL DEFAULT FALSE,
    placement_score             INTEGER NOT NULL,
    state                       TEXT NOT NULL DEFAULT 'RESERVED',
    reason_codes                JSONB NOT NULL DEFAULT '[]',
    reserved_at                 TIMESTAMPTZ NOT NULL DEFAULT now(),
    activated_at                TIMESTAMPTZ,
    released_at                 TIMESTAMPTZ,
    version                     BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_browser_placements_node_state
    ON browser_placements(node_id, state);
CREATE INDEX idx_browser_placements_tenant_state
    ON browser_placements(tenant_id, state);
CREATE INDEX idx_browser_placements_extensions
    ON browser_placements USING GIN(extension_ids);

COMMENT ON TABLE browser_nodes IS
    'Browser Node certified capacity, live PSI pressure and admission state';
COMMENT ON TABLE extension_profiles IS
    'Observed Extension Weight profiles; unknown extensions remain in probation';
COMMENT ON TABLE session_resource_demands IS
    'Immutable-at-create Browser resource demand used by placement';
COMMENT ON TABLE browser_placements IS
    'Build-independent Browser placement reservation and lifecycle accounting';
