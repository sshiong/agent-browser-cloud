-- Complete, payload-free invalidation projection for Enterprise Operations Overview.

CREATE TABLE enterprise_overview_events (
    stream_sequence BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id       TEXT,
    change_type     TEXT NOT NULL,
    occurred_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_enterprise_overview_event_type CHECK (
        change_type IN (
            'RUNTIME_VALIDATION',
            'COST_RATE',
            'MEDIA_QUOTA',
            'ERROR_BUDGET',
            'RELEASE_FREEZE',
            'SLA_EXCLUSION',
            'RETENTION',
            'LICENSE',
            'REGION',
            'RECOVERY_GAMEDAY',
            'COMPLIANCE'
        )
    )
);

CREATE INDEX idx_enterprise_overview_events_tenant_cursor
    ON enterprise_overview_events(tenant_id, stream_sequence);

CREATE INDEX idx_enterprise_overview_events_global_cursor
    ON enterprise_overview_events(stream_sequence)
    WHERE tenant_id IS NULL;

CREATE TABLE enterprise_overview_scheduled_invalidations (
    invalidation_key TEXT PRIMARY KEY,
    tenant_id        TEXT,
    change_type      TEXT NOT NULL,
    due_at           TIMESTAMPTZ NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_enterprise_overview_scheduled_event_type CHECK (
        change_type IN ('ERROR_BUDGET', 'RECOVERY_GAMEDAY')
    )
);

CREATE INDEX idx_enterprise_overview_scheduled_due
    ON enterprise_overview_scheduled_invalidations(due_at, invalidation_key);

CREATE FUNCTION append_enterprise_overview_event()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    row_data JSONB;
    event_tenant_id TEXT;
    previous_tenant_id TEXT;
BEGIN
    IF TG_OP = 'DELETE' THEN
        row_data := to_jsonb(OLD);
    ELSE
        row_data := to_jsonb(NEW);
    END IF;

    IF TG_NARGS > 1 THEN
        event_tenant_id := NULLIF(row_data ->> TG_ARGV[1], '');
        IF TG_OP = 'UPDATE' THEN
            previous_tenant_id := NULLIF(to_jsonb(OLD) ->> TG_ARGV[1], '');
            IF previous_tenant_id IS DISTINCT FROM event_tenant_id THEN
                INSERT INTO enterprise_overview_events(
                    tenant_id, change_type, occurred_at
                ) VALUES (previous_tenant_id, TG_ARGV[0], now());
            END IF;
        END IF;
    END IF;

    INSERT INTO enterprise_overview_events(tenant_id, change_type, occurred_at)
    VALUES (event_tenant_id, TG_ARGV[0], now());
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION schedule_enterprise_error_budget_expiry()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    source_event_id TEXT;
    source_tenant_id TEXT;
    window_size INTEGER;
    expiry_at TIMESTAMPTZ;
BEGIN
    IF TG_OP = 'DELETE' THEN
        source_event_id := OLD.event_id;
        source_tenant_id := OLD.tenant_id;
    ELSE
        source_event_id := NEW.event_id;
        source_tenant_id := NEW.tenant_id;
    END IF;
    DELETE FROM enterprise_overview_scheduled_invalidations
     WHERE invalidation_key = 'ERROR_BUDGET:' || source_event_id;

    IF TG_OP <> 'DELETE' THEN
        SELECT window_minutes INTO window_size
          FROM enterprise_slo_policies
         WHERE tenant_id = source_tenant_id;
        expiry_at := NEW.occurred_at + make_interval(mins => window_size);
        IF expiry_at > now() THEN
            INSERT INTO enterprise_overview_scheduled_invalidations(
                invalidation_key, tenant_id, change_type, due_at
            ) VALUES (
                'ERROR_BUDGET:' || NEW.event_id,
                NEW.tenant_id,
                'ERROR_BUDGET',
                expiry_at
            );
        END IF;
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION reschedule_enterprise_error_budget_expiries()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    DELETE FROM enterprise_overview_scheduled_invalidations
     WHERE tenant_id = NEW.tenant_id AND change_type = 'ERROR_BUDGET';

    INSERT INTO enterprise_overview_scheduled_invalidations(
        invalidation_key, tenant_id, change_type, due_at
    )
    SELECT
        'ERROR_BUDGET:' || event.event_id,
        NEW.tenant_id,
        'ERROR_BUDGET',
        event.occurred_at + make_interval(mins => NEW.window_minutes)
      FROM enterprise_service_level_events event
     WHERE event.tenant_id = NEW.tenant_id
       AND event.occurred_at + make_interval(mins => NEW.window_minutes) > now()
    ON CONFLICT (invalidation_key) DO UPDATE SET
        tenant_id = EXCLUDED.tenant_id,
        change_type = EXCLUDED.change_type,
        due_at = EXCLUDED.due_at;
    RETURN NEW;
END;
$$;

CREATE FUNCTION schedule_enterprise_gameday_trend_expiry()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    source_id TEXT;
    expiry_at TIMESTAMPTZ;
BEGIN
    source_id := CASE WHEN TG_OP = 'DELETE' THEN OLD.gameday_id ELSE NEW.gameday_id END;
    DELETE FROM enterprise_overview_scheduled_invalidations
     WHERE invalidation_key = 'RECOVERY_GAMEDAY:' || source_id;

    IF TG_OP <> 'DELETE' THEN
        expiry_at := NEW.started_at + interval '90 days';
        IF expiry_at > now() THEN
            INSERT INTO enterprise_overview_scheduled_invalidations(
                invalidation_key, tenant_id, change_type, due_at
            ) VALUES (
                'RECOVERY_GAMEDAY:' || NEW.gameday_id,
                NULL,
                'RECOVERY_GAMEDAY',
                expiry_at
            );
        END IF;
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER append_enterprise_overview_validation_run
AFTER INSERT OR UPDATE OR DELETE ON runtime_validation_runs
FOR EACH ROW EXECUTE FUNCTION append_enterprise_overview_event('RUNTIME_VALIDATION');

CREATE TRIGGER append_enterprise_overview_validation_job
AFTER INSERT OR UPDATE OR DELETE ON runtime_validation_jobs
FOR EACH ROW EXECUTE FUNCTION append_enterprise_overview_event('RUNTIME_VALIDATION');

CREATE TRIGGER append_enterprise_overview_cost_rate
AFTER INSERT OR UPDATE OR DELETE ON enterprise_cost_rates
FOR EACH ROW EXECUTE FUNCTION append_enterprise_overview_event('COST_RATE');

CREATE TRIGGER append_enterprise_overview_media_quota
AFTER INSERT OR UPDATE OR DELETE ON tenant_media_quotas
FOR EACH ROW EXECUTE FUNCTION append_enterprise_overview_event('MEDIA_QUOTA', 'tenant_id');

CREATE TRIGGER append_enterprise_overview_media_placement_insert
AFTER INSERT ON browser_placements
FOR EACH ROW
WHEN (NEW.media_slots > 0 OR NEW.media_bitrate_kbps > 0)
EXECUTE FUNCTION append_enterprise_overview_event('MEDIA_QUOTA', 'tenant_id');

CREATE TRIGGER append_enterprise_overview_media_placement_update
AFTER UPDATE ON browser_placements
FOR EACH ROW
WHEN (
    (OLD.media_slots > 0 OR OLD.media_bitrate_kbps > 0
      OR NEW.media_slots > 0 OR NEW.media_bitrate_kbps > 0)
    AND (
        OLD.tenant_id IS DISTINCT FROM NEW.tenant_id
        OR OLD.state IS DISTINCT FROM NEW.state
        OR OLD.media_slots IS DISTINCT FROM NEW.media_slots
        OR OLD.media_bitrate_kbps IS DISTINCT FROM NEW.media_bitrate_kbps
    )
)
EXECUTE FUNCTION append_enterprise_overview_event('MEDIA_QUOTA', 'tenant_id');

CREATE TRIGGER append_enterprise_overview_media_placement_delete
AFTER DELETE ON browser_placements
FOR EACH ROW
WHEN (OLD.media_slots > 0 OR OLD.media_bitrate_kbps > 0)
EXECUTE FUNCTION append_enterprise_overview_event('MEDIA_QUOTA', 'tenant_id');

CREATE TRIGGER append_enterprise_overview_slo_policy
AFTER INSERT OR UPDATE OR DELETE ON enterprise_slo_policies
FOR EACH ROW EXECUTE FUNCTION append_enterprise_overview_event('ERROR_BUDGET', 'tenant_id');

CREATE TRIGGER reschedule_enterprise_overview_error_budget
AFTER INSERT OR UPDATE OF window_minutes ON enterprise_slo_policies
FOR EACH ROW EXECUTE FUNCTION reschedule_enterprise_error_budget_expiries();

CREATE TRIGGER append_enterprise_overview_service_level_event
AFTER INSERT OR UPDATE OR DELETE ON enterprise_service_level_events
FOR EACH ROW EXECUTE FUNCTION append_enterprise_overview_event('ERROR_BUDGET', 'tenant_id');

CREATE TRIGGER schedule_enterprise_overview_error_budget_expiry
AFTER INSERT OR UPDATE OR DELETE ON enterprise_service_level_events
FOR EACH ROW EXECUTE FUNCTION schedule_enterprise_error_budget_expiry();

CREATE TRIGGER append_enterprise_overview_release_freeze
AFTER INSERT OR UPDATE OR DELETE ON enterprise_release_freeze_states
FOR EACH ROW EXECUTE FUNCTION append_enterprise_overview_event('RELEASE_FREEZE', 'tenant_id');

CREATE TRIGGER append_enterprise_overview_sla_exclusion
AFTER INSERT OR UPDATE OR DELETE ON enterprise_sla_exclusions
FOR EACH ROW EXECUTE FUNCTION append_enterprise_overview_event('SLA_EXCLUSION', 'tenant_id');

CREATE TRIGGER append_enterprise_overview_retention
AFTER INSERT OR UPDATE OR DELETE ON enterprise_retention_policies
FOR EACH ROW EXECUTE FUNCTION append_enterprise_overview_event('RETENTION', 'tenant_id');

CREATE TRIGGER append_enterprise_overview_license
AFTER INSERT OR UPDATE OR DELETE ON enterprise_license_inventory
FOR EACH ROW EXECUTE FUNCTION append_enterprise_overview_event('LICENSE');

CREATE TRIGGER append_enterprise_overview_region
AFTER INSERT OR UPDATE OR DELETE ON enterprise_regions
FOR EACH ROW EXECUTE FUNCTION append_enterprise_overview_event('REGION');

CREATE TRIGGER append_enterprise_overview_gameday
AFTER INSERT OR UPDATE OR DELETE ON enterprise_recovery_gamedays
FOR EACH ROW EXECUTE FUNCTION append_enterprise_overview_event('RECOVERY_GAMEDAY');

CREATE TRIGGER schedule_enterprise_overview_gameday_trend_expiry
AFTER INSERT OR UPDATE OF started_at OR DELETE ON enterprise_recovery_gamedays
FOR EACH ROW EXECUTE FUNCTION schedule_enterprise_gameday_trend_expiry();

CREATE TRIGGER append_enterprise_overview_gameday_job
AFTER INSERT OR UPDATE OR DELETE ON recovery_gameday_jobs
FOR EACH ROW EXECUTE FUNCTION append_enterprise_overview_event('RECOVERY_GAMEDAY');

CREATE TRIGGER append_enterprise_overview_gameday_remediation
AFTER INSERT OR UPDATE OR DELETE ON recovery_gameday_remediation_tickets
FOR EACH ROW EXECUTE FUNCTION append_enterprise_overview_event('RECOVERY_GAMEDAY');

CREATE TRIGGER append_enterprise_overview_compliance
AFTER INSERT OR UPDATE OR DELETE ON enterprise_compliance_snapshots
FOR EACH ROW EXECUTE FUNCTION append_enterprise_overview_event('COMPLIANCE', 'tenant_id');

INSERT INTO enterprise_overview_scheduled_invalidations(
    invalidation_key, tenant_id, change_type, due_at
)
SELECT
    'ERROR_BUDGET:' || event.event_id,
    event.tenant_id,
    'ERROR_BUDGET',
    event.occurred_at + make_interval(mins => policy.window_minutes)
  FROM enterprise_service_level_events event
  JOIN enterprise_slo_policies policy ON policy.tenant_id = event.tenant_id
 WHERE event.occurred_at + make_interval(mins => policy.window_minutes) > now()
ON CONFLICT (invalidation_key) DO NOTHING;

INSERT INTO enterprise_overview_scheduled_invalidations(
    invalidation_key, tenant_id, change_type, due_at
)
SELECT
    'RECOVERY_GAMEDAY:' || gameday_id,
    NULL,
    'RECOVERY_GAMEDAY',
    started_at + interval '90 days'
  FROM enterprise_recovery_gamedays
 WHERE started_at + interval '90 days' > now()
ON CONFLICT (invalidation_key) DO NOTHING;

COMMENT ON TABLE enterprise_overview_events IS
    'Payload-free tenant/global monotonic invalidation feed covering every Enterprise Overview source';

COMMENT ON TABLE enterprise_overview_scheduled_invalidations IS
    'Durable future invalidations for sliding Error Budget and 90-day Recovery GameDay windows';
