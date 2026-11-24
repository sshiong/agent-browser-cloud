SET LOCAL lock_timeout = '5s';

-- Expand the list of allowed capabilities without changing existing grants or defaults.
ALTER TABLE agent_connections
    DROP CONSTRAINT chk_agent_connection_tools,
    ADD CONSTRAINT chk_agent_connection_tools CHECK (
        cardinality(allowed_tools) BETWEEN 1 AND 9 AND allowed_tools <@
        ARRAY['quota','list','get','create','start','stop','delete','operation','direct']::TEXT[]
    ) NOT VALID;

ALTER TABLE agent_connections VALIDATE CONSTRAINT chk_agent_connection_tools;
