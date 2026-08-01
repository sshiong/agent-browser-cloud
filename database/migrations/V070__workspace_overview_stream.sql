-- Tenant-scoped Workspace Overview invalidation feed and the missing durable Agent pause state.

ALTER TABLE agent_tasks
    ADD CONSTRAINT chk_agent_task_state_v2 CHECK (
        state IN (
            'PLANNED',
            'AWAITING_CONFIRMATION',
            'BLOCKED',
            'RUNNING',
            'WAITING_FOR_HUMAN',
            'PAUSED_BY_RESOURCE_POLICY',
            'COMPLETED',
            'FAILED'
        )
    ) NOT VALID;
ALTER TABLE agent_tasks VALIDATE CONSTRAINT chk_agent_task_state_v2;
ALTER TABLE agent_tasks DROP CONSTRAINT chk_agent_task_state;
ALTER TABLE agent_tasks
    RENAME CONSTRAINT chk_agent_task_state_v2 TO chk_agent_task_state;

CREATE TABLE workspace_overview_events (
    stream_sequence BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id       TEXT,
    change_type     TEXT NOT NULL,
    entity_id       TEXT NOT NULL,
    occurred_at     TIMESTAMPTZ NOT NULL,
    CONSTRAINT chk_workspace_overview_event_type CHECK (
        change_type IN (
            'SESSION',
            'OPERATION',
            'AGENT_TASK',
            'RESOURCE_EVENT',
            'BROWSER_NODE',
            'PROXY',
            'COST',
            'SECURITY'
        )
    )
);

CREATE INDEX idx_workspace_overview_events_tenant_cursor
    ON workspace_overview_events(tenant_id, stream_sequence);

CREATE INDEX idx_workspace_overview_events_global_cursor
    ON workspace_overview_events(stream_sequence)
    WHERE tenant_id IS NULL;

CREATE FUNCTION append_workspace_overview_session_event()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.change_type IN ('SESSION', 'OPERATION', 'AGENT_TASK', 'RESOURCE_EVENT') THEN
        INSERT INTO workspace_overview_events(
            tenant_id, change_type, entity_id, occurred_at
        ) VALUES (
            NEW.tenant_id, NEW.change_type, NEW.entity_id, NEW.occurred_at
        );
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION append_workspace_overview_browser_node_event()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    INSERT INTO workspace_overview_events(
        tenant_id, change_type, entity_id, occurred_at
    ) VALUES (
        NULL, 'BROWSER_NODE', NEW.node_id, NEW.updated_at
    );
    RETURN NEW;
END;
$$;

CREATE FUNCTION append_workspace_overview_proxy_event()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    INSERT INTO workspace_overview_events(
        tenant_id, change_type, entity_id, occurred_at
    ) VALUES (
        NEW.tenant_id, 'PROXY', NEW.allocation_id, COALESCE(NEW.released_at, NEW.allocated_at)
    );
    RETURN NEW;
END;
$$;

CREATE FUNCTION append_workspace_overview_cost_event()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    INSERT INTO workspace_overview_events(
        tenant_id, change_type, entity_id, occurred_at
    ) VALUES (
        NEW.tenant_id, 'COST', NEW.snapshot_id, NEW.observed_at
    );
    RETURN NEW;
END;
$$;

CREATE FUNCTION append_workspace_overview_security_event()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.category = 'SECURITY' AND NEW.severity IN ('WARNING', 'CRITICAL') THEN
        INSERT INTO workspace_overview_events(
            tenant_id, change_type, entity_id, occurred_at
        ) VALUES (
            NEW.tenant_id, 'SECURITY', NEW.notification_id, NEW.created_at
        );
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER append_workspace_overview_session_event
AFTER INSERT ON session_event_envelopes
FOR EACH ROW
EXECUTE FUNCTION append_workspace_overview_session_event();

CREATE TRIGGER append_workspace_overview_browser_node_insert
AFTER INSERT ON browser_nodes
FOR EACH ROW
EXECUTE FUNCTION append_workspace_overview_browser_node_event();

CREATE TRIGGER append_workspace_overview_browser_node_update
AFTER UPDATE ON browser_nodes
FOR EACH ROW
WHEN (
    OLD.lifecycle_state IS DISTINCT FROM NEW.lifecycle_state
    OR OLD.admission_state IS DISTINCT FROM NEW.admission_state
    OR OLD.pressure_state IS DISTINCT FROM NEW.pressure_state
    OR OLD.active_sessions IS DISTINCT FROM NEW.active_sessions
    OR OLD.max_sessions IS DISTINCT FROM NEW.max_sessions
    OR OLD.reserved_cpu_millis IS DISTINCT FROM NEW.reserved_cpu_millis
    OR OLD.reserved_memory_mib IS DISTINCT FROM NEW.reserved_memory_mib
)
EXECUTE FUNCTION append_workspace_overview_browser_node_event();

CREATE TRIGGER append_workspace_overview_proxy_insert
AFTER INSERT ON proxy_allocations
FOR EACH ROW
EXECUTE FUNCTION append_workspace_overview_proxy_event();

CREATE TRIGGER append_workspace_overview_proxy_update
AFTER UPDATE ON proxy_allocations
FOR EACH ROW
WHEN (
    OLD.state IS DISTINCT FROM NEW.state
    OR OLD.session_id IS DISTINCT FROM NEW.session_id
)
EXECUTE FUNCTION append_workspace_overview_proxy_event();

CREATE TRIGGER append_workspace_overview_cost_event
AFTER INSERT ON session_resource_cost_snapshots
FOR EACH ROW
EXECUTE FUNCTION append_workspace_overview_cost_event();

CREATE TRIGGER append_workspace_overview_security_event
AFTER INSERT ON workspace_notifications
FOR EACH ROW
EXECUTE FUNCTION append_workspace_overview_security_event();

COMMENT ON TABLE workspace_overview_events IS
    'Payload-free tenant/global invalidation feed for authoritative Workspace Overview refresh';
