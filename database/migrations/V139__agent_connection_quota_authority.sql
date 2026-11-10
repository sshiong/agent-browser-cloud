-- Optional Gateway state; core installs need no MCP process or credentials.
SET LOCAL lock_timeout = '5s';
CREATE TABLE agent_connections (
    connection_id TEXT PRIMARY KEY,
    tenant_id TEXT NOT NULL,
    display_name TEXT NOT NULL CHECK (length(display_name) BETWEEN 1 AND 128),
    principal_subject TEXT NOT NULL CHECK (length(principal_subject) BETWEEN 1 AND 512),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    maximum_environments INTEGER NOT NULL CHECK (maximum_environments BETWEEN 0 AND 10000),
    maximum_running INTEGER NOT NULL CHECK (maximum_running BETWEEN 0 AND 10000),
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (connection_id, tenant_id)
);
CREATE TABLE agent_connection_environments (
    reservation_id TEXT PRIMARY KEY,
    connection_id TEXT NOT NULL,
    tenant_id TEXT NOT NULL,
    request_key TEXT NOT NULL CHECK (length(request_key) BETWEEN 1 AND 128),
    request_sha256 TEXT NOT NULL CHECK (request_sha256 ~ '^[0-9a-f]{64}$'),
    session_id TEXT UNIQUE REFERENCES sessions(id),
    state TEXT NOT NULL CHECK (state IN ('CREATING','CREATED','STARTING','RUNNING','STOPPING','STOPPED','DELETING','DELETED','UNKNOWN')),
    running_reserved BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (connection_id, tenant_id) REFERENCES agent_connections(connection_id, tenant_id),
    UNIQUE (connection_id, request_key),
    CHECK (state <> 'DELETED' OR running_reserved = FALSE)
);
CREATE INDEX idx_agent_connection_environments_live
    ON agent_connection_environments(connection_id) WHERE state <> 'DELETED';

-- Every INSERT/UPDATE serializes quota decisions on the parent connection. The lock is held
-- until the caller's business transaction commits. Unknown outcomes keep their reservations.
CREATE FUNCTION enforce_agent_connection_quota() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE parent agent_connections%ROWTYPE; total_used BIGINT; running_used BIGINT;
BEGIN
    IF TG_OP = 'UPDATE' AND (NEW.connection_id <> OLD.connection_id
        OR NEW.tenant_id <> OLD.tenant_id OR NEW.reservation_id <> OLD.reservation_id
        OR NEW.request_key <> OLD.request_key OR NEW.request_sha256 <> OLD.request_sha256) THEN
        RAISE EXCEPTION 'AGENT_RESERVATION_IDENTITY_IMMUTABLE' USING ERRCODE = '23514';
    END IF;
    IF TG_OP = 'UPDATE' AND (OLD.state = 'DELETED' AND NEW.state <> 'DELETED'
        OR OLD.session_id IS NOT NULL AND NEW.session_id IS DISTINCT FROM OLD.session_id) THEN
        RAISE EXCEPTION 'AGENT_ENVIRONMENT_BINDING_IMMUTABLE' USING ERRCODE = '23514';
    END IF;
    SELECT * INTO STRICT parent FROM agent_connections WHERE connection_id=NEW.connection_id FOR UPDATE;
    IF parent.tenant_id <> NEW.tenant_id THEN
        RAISE EXCEPTION 'AGENT_CONNECTION_TENANT_MISMATCH' USING ERRCODE = '23514';
    END IF;
    IF (TG_OP = 'INSERT' OR (NEW.running_reserved AND NOT OLD.running_reserved))
        AND (NOT parent.enabled OR parent.expires_at <= now()) THEN
        RAISE EXCEPTION 'AGENT_CONNECTION_INACTIVE' USING ERRCODE = '23514';
    END IF;
    SELECT count(*), count(*) FILTER (WHERE running_reserved)
      INTO total_used, running_used FROM agent_connection_environments
      WHERE connection_id=NEW.connection_id AND state <> 'DELETED'
        AND reservation_id <> NEW.reservation_id;
    IF NEW.state <> 'DELETED' AND (TG_OP = 'INSERT' OR OLD.state = 'DELETED')
        AND total_used >= parent.maximum_environments THEN
        RAISE EXCEPTION 'AGENT_ENVIRONMENT_QUOTA_EXCEEDED' USING ERRCODE = '23514';
    END IF;
    IF NEW.running_reserved AND (TG_OP = 'INSERT' OR NOT OLD.running_reserved)
        AND running_used >= parent.maximum_running THEN
        RAISE EXCEPTION 'AGENT_RUNNING_QUOTA_EXCEEDED' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER agent_connection_quota_guard BEFORE INSERT OR UPDATE
    ON agent_connection_environments FOR EACH ROW EXECUTE FUNCTION enforce_agent_connection_quota();
