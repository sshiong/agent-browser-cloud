ALTER TABLE durable_workflows
    ADD COLUMN tenant_id TEXT,
    ADD COLUMN commit_marker TEXT,
    ADD COLUMN compensation_action TEXT NOT NULL DEFAULT 'NONE',
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT now();

UPDATE durable_workflows workflow
SET tenant_id = session.tenant_id
FROM sessions session
WHERE workflow.session_id = session.id
  AND workflow.tenant_id IS NULL;

ALTER TABLE durable_workflows
    ALTER COLUMN tenant_id SET NOT NULL;

CREATE UNIQUE INDEX uq_durable_workflow_tenant_idempotency
ON durable_workflows(tenant_id, idempotency_key)
WHERE idempotency_key IS NOT NULL;

CREATE UNIQUE INDEX uq_durable_workflow_operation
ON durable_workflows(operation_id, attempt);

CREATE TABLE workflow_dead_letters (
    dead_letter_id TEXT PRIMARY KEY,
    workflow_id TEXT NOT NULL REFERENCES durable_workflows(workflow_id),
    tenant_id TEXT NOT NULL,
    session_id TEXT NOT NULL,
    operation_id TEXT NOT NULL,
    reason TEXT NOT NULL,
    evidence JSONB NOT NULL DEFAULT '{}',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at TIMESTAMPTZ
);

CREATE INDEX idx_workflow_dead_letters_tenant
ON workflow_dead_letters(tenant_id, created_at DESC)
WHERE resolved_at IS NULL;

COMMENT ON COLUMN durable_workflows.commit_marker IS
'SHA-256 proof binding a terminal callback to workflow and fencing epochs';
