-- Active Proxy health observations are deliberately separated from Binding configuration.
-- Health writes must not advance the Binding optimistic-lock version or invalidate an
-- administrator's in-flight configuration edit.

ALTER TABLE proxy_binding_profiles
    ADD COLUMN probe_success_count BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN probe_failure_count BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN consecutive_probe_successes INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN consecutive_probe_failures INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN probe_success_ewma NUMERIC(6,5),
    ADD COLUMN probe_latency_ewma_ms NUMERIC(12,3),
    ADD COLUMN last_probe_session_id TEXT,
    ADD COLUMN last_probe_node_id TEXT;

ALTER TABLE proxy_binding_profiles
    ADD CONSTRAINT chk_proxy_binding_probe_counts CHECK (
        probe_success_count >= 0
        AND probe_failure_count >= 0
        AND consecutive_probe_successes >= 0
        AND consecutive_probe_failures >= 0
    ) NOT VALID,
    ADD CONSTRAINT chk_proxy_binding_probe_ewma CHECK (
        (probe_success_ewma IS NULL OR probe_success_ewma BETWEEN 0 AND 1)
        AND (probe_latency_ewma_ms IS NULL OR probe_latency_ewma_ms >= 0)
    ) NOT VALID;

ALTER TABLE proxy_binding_profiles
    VALIDATE CONSTRAINT chk_proxy_binding_probe_counts;

ALTER TABLE proxy_binding_profiles
    VALIDATE CONSTRAINT chk_proxy_binding_probe_ewma;

CREATE TABLE proxy_binding_health_samples (
    probe_id            TEXT PRIMARY KEY,
    binding_profile_id  TEXT NOT NULL,
    tenant_id           TEXT NOT NULL,
    allocation_id       TEXT NOT NULL,
    session_id          TEXT NOT NULL,
    node_id             TEXT NOT NULL,
    source              TEXT NOT NULL,
    succeeded           BOOLEAN NOT NULL,
    latency_ms          INTEGER,
    observed_exit_ip    TEXT,
    failure_code        TEXT,
    observed_at         TIMESTAMPTZ NOT NULL,
    received_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_proxy_probe_source CHECK (
        source IN ('RUNTIME_BIND', 'ACTIVE_EXIT_PROBE')
    ),
    CONSTRAINT chk_proxy_probe_result CHECK (
        (
            succeeded
            AND observed_exit_ip IS NOT NULL
            AND failure_code IS NULL
        )
        OR
        (
            NOT succeeded
            AND observed_exit_ip IS NULL
            AND failure_code IS NOT NULL
            AND failure_code ~ '^[A-Z][A-Z0-9_]{1,63}$'
        )
    ),
    CONSTRAINT chk_proxy_probe_latency CHECK (
        latency_ms IS NULL OR latency_ms BETWEEN 0 AND 30000
    )
);

ALTER TABLE proxy_binding_health_samples
    ADD CONSTRAINT proxy_binding_health_samples_profile_fk
        FOREIGN KEY (binding_profile_id, tenant_id)
        REFERENCES proxy_binding_profiles(binding_profile_id, tenant_id)
        ON DELETE CASCADE NOT VALID,
    ADD CONSTRAINT proxy_binding_health_samples_session_fk
        FOREIGN KEY (session_id, tenant_id)
        REFERENCES sessions(id, tenant_id)
        ON DELETE CASCADE NOT VALID,
    ADD CONSTRAINT proxy_binding_health_samples_allocation_fk
        FOREIGN KEY (allocation_id)
        REFERENCES proxy_allocations(allocation_id)
        ON DELETE CASCADE NOT VALID;

ALTER TABLE proxy_binding_health_samples
    VALIDATE CONSTRAINT proxy_binding_health_samples_profile_fk;

ALTER TABLE proxy_binding_health_samples
    VALIDATE CONSTRAINT proxy_binding_health_samples_session_fk;

ALTER TABLE proxy_binding_health_samples
    VALIDATE CONSTRAINT proxy_binding_health_samples_allocation_fk;

CREATE INDEX idx_proxy_binding_health_samples_profile_time
    ON proxy_binding_health_samples(tenant_id, binding_profile_id, observed_at DESC);

CREATE INDEX idx_proxy_binding_health_samples_retention
    ON proxy_binding_health_samples(received_at);

CREATE FUNCTION append_workspace_overview_proxy_health_event()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    INSERT INTO workspace_overview_events(
        tenant_id, change_type, entity_id, occurred_at
    ) VALUES (
        NEW.tenant_id, 'PROXY', NEW.binding_profile_id,
        COALESCE(NEW.last_health_checked_at, now())
    );
    RETURN NEW;
END;
$$;

CREATE TRIGGER append_workspace_overview_proxy_health_event
AFTER UPDATE ON proxy_binding_profiles
FOR EACH ROW
WHEN (
    OLD.health_state IS DISTINCT FROM NEW.health_state
    OR OLD.last_health_checked_at IS NULL
    OR OLD.last_health_checked_at < NEW.last_health_checked_at - INTERVAL '5 minutes'
)
EXECUTE FUNCTION append_workspace_overview_proxy_health_event();

COMMENT ON TABLE proxy_binding_health_samples IS
    'Seven-day bounded, credential-free Browser Node exit probes used for Proxy quality decisions';

COMMENT ON COLUMN proxy_binding_profiles.probe_success_ewma IS
    'EWMA alpha=0.2; used with latency EWMA to derive a transparent 0..100 quality score';

COMMENT ON FUNCTION append_workspace_overview_proxy_health_event() IS
    'Coalesces Proxy UI invalidations to initial/state-transition/5-minute updates instead of publishing every probe';
