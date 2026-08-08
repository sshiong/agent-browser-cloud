-- Cold probes keep enabled, currently unallocated Binding profiles fresh without starting a
-- Browser Session. A short PostgreSQL lease prevents duplicate work across Control Plane replicas.

ALTER TABLE proxy_binding_profiles
    ADD COLUMN next_cold_probe_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    ADD COLUMN cold_probe_lease_owner TEXT,
    ADD COLUMN cold_probe_lease_until TIMESTAMPTZ;

ALTER TABLE proxy_binding_profiles
    ADD CONSTRAINT chk_proxy_binding_cold_probe_lease CHECK (
        (cold_probe_lease_owner IS NULL AND cold_probe_lease_until IS NULL)
        OR
        (
            cold_probe_lease_owner ~ '^prb_[a-zA-Z0-9_-]{8,64}$'
            AND cold_probe_lease_until IS NOT NULL
        )
    ) NOT VALID;

ALTER TABLE proxy_binding_profiles
    VALIDATE CONSTRAINT chk_proxy_binding_cold_probe_lease;

CREATE INDEX idx_proxy_binding_profiles_cold_probe_due
    ON proxy_binding_profiles(next_cold_probe_at, binding_profile_id)
    WHERE enabled;

CREATE FUNCTION reset_proxy_binding_cold_probe_after_configuration_change()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.provider_id IS DISTINCT FROM NEW.provider_id
       OR OLD.region IS DISTINCT FROM NEW.region
       OR OLD.expected_exit_ip IS DISTINCT FROM NEW.expected_exit_ip
       OR OLD.credential_ref IS DISTINCT FROM NEW.credential_ref
       OR OLD.enabled IS DISTINCT FROM NEW.enabled THEN
        NEW.cold_probe_lease_owner := NULL;
        NEW.cold_probe_lease_until := NULL;
        NEW.next_cold_probe_at := now();
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER reset_proxy_binding_cold_probe_after_configuration_change
BEFORE UPDATE ON proxy_binding_profiles
FOR EACH ROW
EXECUTE FUNCTION reset_proxy_binding_cold_probe_after_configuration_change();

ALTER TABLE proxy_binding_health_samples
    ALTER COLUMN allocation_id DROP NOT NULL,
    ALTER COLUMN session_id DROP NOT NULL;

ALTER TABLE proxy_binding_health_samples
    DROP CONSTRAINT chk_proxy_probe_source,
    ADD CONSTRAINT chk_proxy_probe_source CHECK (
        source IN ('RUNTIME_BIND', 'ACTIVE_EXIT_PROBE', 'COLD_BINDING_PROBE')
    ) NOT VALID,
    ADD CONSTRAINT chk_proxy_probe_context CHECK (
        (
            source = 'COLD_BINDING_PROBE'
            AND allocation_id IS NULL
            AND session_id IS NULL
        )
        OR
        (
            source IN ('RUNTIME_BIND', 'ACTIVE_EXIT_PROBE')
            AND allocation_id IS NOT NULL
            AND session_id IS NOT NULL
        )
    ) NOT VALID;

ALTER TABLE proxy_binding_health_samples
    VALIDATE CONSTRAINT chk_proxy_probe_source;

ALTER TABLE proxy_binding_health_samples
    VALIDATE CONSTRAINT chk_proxy_probe_context;

COMMENT ON COLUMN proxy_binding_profiles.next_cold_probe_at IS
    'Next eligible time for a cold Binding probe when no active Proxy allocation exists';

COMMENT ON COLUMN proxy_binding_profiles.cold_probe_lease_owner IS
    'Opaque per-attempt lease ID; prevents duplicate probes across Control Plane replicas';

COMMENT ON FUNCTION reset_proxy_binding_cold_probe_after_configuration_change() IS
    'Fences an in-flight old-revision result and immediately schedules the new configuration';
