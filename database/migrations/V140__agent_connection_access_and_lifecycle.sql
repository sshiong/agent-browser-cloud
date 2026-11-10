SET LOCAL lock_timeout = '5s';
ALTER TABLE agent_connections ADD COLUMN credential_sha256 TEXT UNIQUE
    CHECK (credential_sha256 ~ '^[0-9a-f]{64}$');
ALTER TABLE agent_connections ADD COLUMN allowed_tools TEXT[] NOT NULL DEFAULT
    ARRAY['quota','list','get','create','start','stop','delete','operation'];
ALTER TABLE agent_connections ADD CONSTRAINT chk_agent_connection_tools CHECK (
    cardinality(allowed_tools) BETWEEN 1 AND 8 AND allowed_tools <@
    ARRAY['quota','list','get','create','start','stop','delete','operation']::TEXT[]);

-- Canonical Session writes (including Web/SDK and Node events) must respect connection quota.
-- Transitional/failed states never release an uncertain runtime reservation.
CREATE FUNCTION reconcile_agent_environment_lifecycle() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    UPDATE agent_connection_environments e SET
      state = CASE WHEN NEW.deleted_at IS NOT NULL THEN 'DELETED'
        WHEN NEW.state IN ('HIBERNATED','TERMINATED') THEN 'STOPPED'
        WHEN NEW.state='CREATED' THEN 'CREATED'
        WHEN NEW.state='RUNNING' THEN 'RUNNING'
        WHEN NEW.state IN ('HIBERNATING','TERMINATING') THEN 'STOPPING'
        WHEN NEW.state IN ('STARTING','RECOVERING','DEGRADED') THEN 'STARTING'
        ELSE 'UNKNOWN' END,
      running_reserved = CASE WHEN NEW.deleted_at IS NOT NULL OR NEW.state IN ('HIBERNATED','TERMINATED','CREATED') THEN FALSE
        WHEN NEW.state IN ('STARTING','RUNNING','RECOVERING','DEGRADED','HIBERNATING','TERMINATING') THEN TRUE
        ELSE e.running_reserved END
      WHERE e.session_id=NEW.id AND e.state<>'DELETED';
    RETURN NEW;
END $$;
CREATE TRIGGER agent_environment_session_lifecycle AFTER UPDATE OF state,deleted_at ON sessions
    FOR EACH ROW EXECUTE FUNCTION reconcile_agent_environment_lifecycle();
