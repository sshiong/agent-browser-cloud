-- Durable, monotonically ordered source for resumable Session resource SSE.
--
-- A PostgreSQL SEQUENCE is intentionally not used here. Cached sequence ranges can make a later
-- committed row receive a smaller value than the cursor already observed on another connection.
-- The per-Session cursor row is updated in the writer transaction, so the row lock serializes
-- allocations and prevents a committed change from appearing behind an acknowledged cursor.

CREATE TABLE session_resource_stream_cursors (
    tenant_id       TEXT NOT NULL,
    session_id      TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
    last_sequence   BIGINT NOT NULL DEFAULT 0 CHECK (last_sequence >= 0),
    PRIMARY KEY (tenant_id, session_id)
);

ALTER TABLE session_resource_samples
    ADD COLUMN stream_sequence BIGINT;

ALTER TABLE session_resource_events
    ADD COLUMN stream_sequence BIGINT;

-- Preserve pre-migration history with a deterministic per-Session order.
WITH ordered_changes AS (
    SELECT
        source,
        entity_id,
        tenant_id,
        session_id,
        ROW_NUMBER() OVER (
            PARTITION BY tenant_id, session_id
            ORDER BY occurred_at, source, entity_id
        ) AS stream_sequence
    FROM (
        SELECT
            'SAMPLE' AS source,
            sample_id AS entity_id,
            tenant_id,
            session_id,
            observed_at AS occurred_at
        FROM session_resource_samples
        UNION ALL
        SELECT
            'EVENT' AS source,
            event_id AS entity_id,
            tenant_id,
            session_id,
            occurred_at
        FROM session_resource_events
    ) existing_changes
),
sample_sequences AS (
    SELECT entity_id, stream_sequence
    FROM ordered_changes
    WHERE source = 'SAMPLE'
)
UPDATE session_resource_samples sample
SET stream_sequence = sequence.stream_sequence
FROM sample_sequences sequence
WHERE sample.sample_id = sequence.entity_id;

WITH ordered_changes AS (
    SELECT
        source,
        entity_id,
        tenant_id,
        session_id,
        ROW_NUMBER() OVER (
            PARTITION BY tenant_id, session_id
            ORDER BY occurred_at, source, entity_id
        ) AS stream_sequence
    FROM (
        SELECT
            'SAMPLE' AS source,
            sample_id AS entity_id,
            tenant_id,
            session_id,
            observed_at AS occurred_at
        FROM session_resource_samples
        UNION ALL
        SELECT
            'EVENT' AS source,
            event_id AS entity_id,
            tenant_id,
            session_id,
            occurred_at
        FROM session_resource_events
    ) existing_changes
),
event_sequences AS (
    SELECT entity_id, stream_sequence
    FROM ordered_changes
    WHERE source = 'EVENT'
)
UPDATE session_resource_events event
SET stream_sequence = sequence.stream_sequence
FROM event_sequences sequence
WHERE event.event_id = sequence.entity_id;

INSERT INTO session_resource_stream_cursors (tenant_id, session_id, last_sequence)
SELECT tenant_id, session_id, MAX(stream_sequence)
FROM (
    SELECT tenant_id, session_id, stream_sequence FROM session_resource_samples
    UNION ALL
    SELECT tenant_id, session_id, stream_sequence FROM session_resource_events
) existing_sequences
GROUP BY tenant_id, session_id;

ALTER TABLE session_resource_samples
    ALTER COLUMN stream_sequence SET NOT NULL;

ALTER TABLE session_resource_events
    ALTER COLUMN stream_sequence SET NOT NULL;

CREATE FUNCTION assign_session_resource_stream_sequence()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    INSERT INTO session_resource_stream_cursors (tenant_id, session_id, last_sequence)
    VALUES (NEW.tenant_id, NEW.session_id, 1)
    ON CONFLICT (tenant_id, session_id)
    DO UPDATE SET last_sequence = session_resource_stream_cursors.last_sequence + 1
    RETURNING last_sequence INTO NEW.stream_sequence;
    RETURN NEW;
END;
$$;

CREATE TRIGGER assign_resource_sample_stream_sequence
BEFORE INSERT ON session_resource_samples
FOR EACH ROW
EXECUTE FUNCTION assign_session_resource_stream_sequence();

CREATE TRIGGER assign_resource_event_stream_sequence
BEFORE INSERT ON session_resource_events
FOR EACH ROW
EXECUTE FUNCTION assign_session_resource_stream_sequence();

CREATE UNIQUE INDEX uq_session_resource_samples_stream_sequence
    ON session_resource_samples(tenant_id, session_id, stream_sequence);

CREATE UNIQUE INDEX uq_session_resource_events_stream_sequence
    ON session_resource_events(tenant_id, session_id, stream_sequence);

COMMENT ON TABLE session_resource_stream_cursors IS
    'Per-Session transactional cursor for durable resource samples and adjustment events';
