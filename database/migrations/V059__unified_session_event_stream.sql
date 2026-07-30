-- Durable, commit-ordered Session event envelopes.
--
-- V026 introduced the per-Session transactional cursor for resource events and V029 reused it
-- for application safety leases. This additive migration promotes that cursor to the canonical
-- Session change cursor and mirrors Browser Current State, Session row and audit changes into the
-- same stream. Existing resource writers remain rolling-compatible: their original BEFORE trigger
-- allocates the cursor and the AFTER triggers below copy the committed change into the envelope.

CREATE TABLE session_event_envelopes (
    tenant_id        TEXT NOT NULL,
    session_id       TEXT NOT NULL,
    stream_sequence  BIGINT NOT NULL CHECK (stream_sequence > 0),
    change_type      TEXT NOT NULL,
    entity_id        TEXT NOT NULL,
    occurred_at      TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (tenant_id, session_id, stream_sequence),
    CONSTRAINT fk_session_event_envelope_session
      FOREIGN KEY (session_id, tenant_id)
        REFERENCES sessions(id, tenant_id) ON DELETE CASCADE,
    CONSTRAINT chk_session_event_envelope_change_type
      CHECK (change_type IN (
        'SESSION',
        'BROWSER_STATE',
        'AUDIT_EVENT',
        'OPERATION',
        'AGENT_TASK',
        'RESOURCE_SAMPLE',
        'RESOURCE_EVENT',
        'SAFETY_LEASE_EVENT'
      ))
);

CREATE INDEX idx_session_event_envelopes_timeline
  ON session_event_envelopes (tenant_id, session_id, occurred_at DESC);

-- Preserve all changes that already had a canonical cursor. Historical Browser State and audit
-- rows are intentionally not replayed as if they were new; future writes are captured below.
INSERT INTO session_event_envelopes (
    tenant_id,
    session_id,
    stream_sequence,
    change_type,
    entity_id,
    occurred_at
)
SELECT tenant_id, session_id, stream_sequence, 'RESOURCE_SAMPLE', sample_id, observed_at
FROM session_resource_samples
UNION ALL
SELECT tenant_id, session_id, stream_sequence, 'RESOURCE_EVENT', event_id, occurred_at
FROM session_resource_events
UNION ALL
SELECT tenant_id, session_id, stream_sequence, 'SAFETY_LEASE_EVENT', event_id, occurred_at
FROM session_safety_lease_events;

CREATE FUNCTION append_session_event_envelope(
    envelope_tenant_id TEXT,
    envelope_session_id TEXT,
    envelope_change_type TEXT,
    envelope_entity_id TEXT,
    envelope_occurred_at TIMESTAMPTZ
)
RETURNS BIGINT
LANGUAGE plpgsql
AS $$
DECLARE
    allocated_sequence BIGINT;
BEGIN
    INSERT INTO session_resource_stream_cursors (tenant_id, session_id, last_sequence)
    VALUES (envelope_tenant_id, envelope_session_id, 1)
    ON CONFLICT (tenant_id, session_id)
    DO UPDATE SET last_sequence = session_resource_stream_cursors.last_sequence + 1
    RETURNING last_sequence INTO allocated_sequence;

    INSERT INTO session_event_envelopes (
        tenant_id,
        session_id,
        stream_sequence,
        change_type,
        entity_id,
        occurred_at
    )
    VALUES (
        envelope_tenant_id,
        envelope_session_id,
        allocated_sequence,
        envelope_change_type,
        envelope_entity_id,
        envelope_occurred_at
    );
    RETURN allocated_sequence;
END;
$$;

CREATE FUNCTION mirror_resource_sample_event_envelope()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    INSERT INTO session_event_envelopes (
        tenant_id, session_id, stream_sequence, change_type, entity_id, occurred_at
    )
    VALUES (
        NEW.tenant_id, NEW.session_id, NEW.stream_sequence,
        'RESOURCE_SAMPLE', NEW.sample_id, NEW.observed_at
    );
    RETURN NEW;
END;
$$;

CREATE FUNCTION mirror_resource_adjustment_event_envelope()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    INSERT INTO session_event_envelopes (
        tenant_id, session_id, stream_sequence, change_type, entity_id, occurred_at
    )
    VALUES (
        NEW.tenant_id, NEW.session_id, NEW.stream_sequence,
        'RESOURCE_EVENT', NEW.event_id, NEW.occurred_at
    );
    RETURN NEW;
END;
$$;

CREATE FUNCTION mirror_safety_lease_event_envelope()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    INSERT INTO session_event_envelopes (
        tenant_id, session_id, stream_sequence, change_type, entity_id, occurred_at
    )
    VALUES (
        NEW.tenant_id, NEW.session_id, NEW.stream_sequence,
        'SAFETY_LEASE_EVENT', NEW.event_id, NEW.occurred_at
    );
    RETURN NEW;
END;
$$;

CREATE FUNCTION append_browser_state_event_envelope()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
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

CREATE FUNCTION append_audit_event_envelope()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.session_id IS NOT NULL THEN
        PERFORM append_session_event_envelope(
            NEW.tenant_id,
            NEW.session_id,
            'AUDIT_EVENT',
            NEW.event_id,
            NEW.created_at
        );
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION append_session_row_event_envelope()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    PERFORM append_session_event_envelope(
        NEW.tenant_id,
        NEW.id,
        'SESSION',
        NEW.id,
        CASE WHEN TG_OP = 'INSERT' THEN NEW.created_at ELSE NEW.updated_at END
    );
    RETURN NEW;
END;
$$;

CREATE FUNCTION append_operation_event_envelope()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    operation_tenant_id TEXT;
BEGIN
    SELECT tenant_id
    INTO STRICT operation_tenant_id
    FROM sessions
    WHERE id = NEW.session_id;

    PERFORM append_session_event_envelope(
        operation_tenant_id,
        NEW.session_id,
        'OPERATION',
        NEW.operation_id,
        COALESCE(NEW.completed_at, NEW.created_at, now())
    );
    RETURN NEW;
END;
$$;

CREATE FUNCTION append_agent_task_event_envelope()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    PERFORM append_session_event_envelope(
        NEW.tenant_id,
        NEW.session_id,
        'AGENT_TASK',
        NEW.task_id,
        CASE WHEN TG_OP = 'INSERT' THEN NEW.created_at ELSE NEW.updated_at END
    );
    RETURN NEW;
END;
$$;

CREATE TRIGGER mirror_resource_sample_event_envelope
AFTER INSERT ON session_resource_samples
FOR EACH ROW
EXECUTE FUNCTION mirror_resource_sample_event_envelope();

CREATE TRIGGER mirror_resource_adjustment_event_envelope
AFTER INSERT ON session_resource_events
FOR EACH ROW
EXECUTE FUNCTION mirror_resource_adjustment_event_envelope();

CREATE TRIGGER mirror_safety_lease_event_envelope
AFTER INSERT ON session_safety_lease_events
FOR EACH ROW
EXECUTE FUNCTION mirror_safety_lease_event_envelope();

CREATE TRIGGER append_browser_state_event_envelope
AFTER INSERT OR UPDATE ON browser_states
FOR EACH ROW
EXECUTE FUNCTION append_browser_state_event_envelope();

CREATE TRIGGER append_audit_event_envelope
AFTER INSERT ON audit_events
FOR EACH ROW
EXECUTE FUNCTION append_audit_event_envelope();

CREATE TRIGGER append_session_row_event_envelope
AFTER INSERT OR UPDATE ON sessions
FOR EACH ROW
EXECUTE FUNCTION append_session_row_event_envelope();

CREATE TRIGGER append_operation_event_envelope
AFTER INSERT OR UPDATE ON exclusive_operations
FOR EACH ROW
EXECUTE FUNCTION append_operation_event_envelope();

CREATE TRIGGER append_agent_task_event_envelope
AFTER INSERT OR UPDATE ON agent_tasks
FOR EACH ROW
EXECUTE FUNCTION append_agent_task_event_envelope();

COMMENT ON TABLE session_event_envelopes IS
  'Canonical per-Session commit-ordered invalidation feed without business payload duplication';

COMMENT ON TABLE session_resource_stream_cursors IS
  'Per-Session transactional cursor shared by resource, state, audit and lifecycle events';
