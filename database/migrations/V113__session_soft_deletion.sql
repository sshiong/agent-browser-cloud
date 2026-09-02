-- Tenant-scoped, auditable soft deletion for Environment management.
--
-- Session evidence and foreign-key children remain intact. Normal control-plane reads hide the
-- row after deletion, while the batch id makes an idempotent replay distinguishable from a new
-- delete request.

ALTER TABLE sessions
    ADD COLUMN deleted_at TIMESTAMPTZ,
    ADD COLUMN deleted_by TEXT,
    ADD COLUMN deletion_batch_id TEXT;

ALTER TABLE sessions
    ADD CONSTRAINT chk_session_soft_delete_fields CHECK (
        (deleted_at IS NULL AND deleted_by IS NULL AND deletion_batch_id IS NULL)
        OR
        (deleted_at IS NOT NULL AND deleted_by IS NOT NULL AND deletion_batch_id IS NOT NULL)
    ) NOT VALID;

ALTER TABLE sessions VALIDATE CONSTRAINT chk_session_soft_delete_fields;

CREATE INDEX idx_sessions_tenant_active_created
    ON sessions(tenant_id, created_at DESC, id)
    WHERE deleted_at IS NULL;

CREATE INDEX idx_sessions_deletion_batch
    ON sessions(tenant_id, deletion_batch_id)
    WHERE deletion_batch_id IS NOT NULL;
