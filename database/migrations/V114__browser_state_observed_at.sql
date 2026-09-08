-- Control Plane receipt time of the last authoritative Browser Node sample.
-- Existing rows remain nullable so N-1 writers and rolling upgrades can use updated_at as a
-- conservative fallback without rewriting the whole current-state table.
ALTER TABLE browser_states
    ADD COLUMN observed_at TIMESTAMPTZ;

-- Observation heartbeats update only observed_at. Keep public session SSE change-only so a stable
-- page does not trigger a client refetch on every internal sampling cadence.
CREATE OR REPLACE FUNCTION append_browser_state_event_envelope()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'UPDATE'
       AND NEW.context_epoch = OLD.context_epoch
       AND NEW.state_version = OLD.state_version
       AND NEW.state_json = OLD.state_json THEN
        RETURN NEW;
    END IF;
    PERFORM append_session_event_envelope(
        NEW.tenant_id,
        NEW.session_id,
        'BROWSER_STATE',
        NEW.session_id || ':' || NEW.context_epoch || ':' || NEW.state_version,
        NEW.updated_at
    );
    RETURN NEW;
END;
$$;
