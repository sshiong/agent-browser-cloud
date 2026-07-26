ALTER TABLE audit_events
    ADD COLUMN sequence_no BIGINT,
    ADD COLUMN previous_event_hash TEXT,
    ADD COLUMN event_hash TEXT,
    ADD COLUMN request_id TEXT,
    ADD COLUMN retention_until TIMESTAMPTZ,
    ADD COLUMN legal_hold BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE tenant_audit_heads (
    tenant_id TEXT PRIMARY KEY,
    sequence_no BIGINT NOT NULL DEFAULT 0,
    head_hash TEXT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_tenant_audit_sequence CHECK (sequence_no >= 0)
);

CREATE UNIQUE INDEX uq_audit_events_tenant_sequence
ON audit_events(tenant_id, sequence_no)
WHERE sequence_no IS NOT NULL;

CREATE UNIQUE INDEX uq_audit_events_hash
ON audit_events(event_hash)
WHERE event_hash IS NOT NULL;

CREATE INDEX idx_audit_events_retention
ON audit_events(retention_until)
WHERE legal_hold = FALSE;

COMMENT ON TABLE tenant_audit_heads IS
'Serialized per-tenant audit chain head; locked before appending a tamper-evident event';

COMMENT ON COLUMN audit_events.event_hash IS
'SHA-256 over tenant sequence, previous hash and canonical redacted event fields';
