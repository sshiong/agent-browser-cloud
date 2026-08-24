-- PostgreSQL-authoritative Agent Browser file upload ledger.
--
-- File bytes stay on the direct mTLS Control Plane -> Browser Node stream. PostgreSQL stores
-- bounded metadata and the durable Operation correlation only. This migration is expand-only;
-- N-1 applications ignore the table and older Nodes reject the additive command/RPC.

CREATE TABLE agent_browser_file_uploads (
    upload_id             TEXT PRIMARY KEY,
    tenant_id             TEXT NOT NULL,
    session_id            TEXT NOT NULL,
    actor_id              TEXT NOT NULL,
    idempotency_key       TEXT NOT NULL,
    request_hash          TEXT NOT NULL,
    request_id            TEXT NOT NULL,
    operation_id          TEXT NOT NULL,
    node_id               TEXT NOT NULL,
    coordinator_term      BIGINT NOT NULL,
    context_epoch         BIGINT NOT NULL,
    operation_epoch       BIGINT NOT NULL,
    target_ref            TEXT NOT NULL,
    target_revision       BIGINT NOT NULL,
    base_state_version    BIGINT NOT NULL,
    base_content_hash     TEXT NOT NULL,
    filename              TEXT NOT NULL,
    mime_type             TEXT NOT NULL,
    content_sha256        TEXT NOT NULL,
    content_bytes         BIGINT NOT NULL,
    state                 TEXT NOT NULL,
    error_code            TEXT,
    state_version_after   BIGINT,
    created_at            TIMESTAMPTZ NOT NULL,
    updated_at            TIMESTAMPTZ NOT NULL,
    completed_at          TIMESTAMPTZ,
    version               BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_agent_browser_file_upload_idempotency
        UNIQUE (tenant_id, session_id, idempotency_key),
    CONSTRAINT fk_agent_browser_file_upload_session
        FOREIGN KEY (session_id, tenant_id) REFERENCES sessions(id, tenant_id) ON DELETE CASCADE,
    CONSTRAINT chk_agent_browser_file_upload_id
        CHECK (upload_id ~ '^afu_[A-Za-z0-9]{20}$'),
    CONSTRAINT chk_agent_browser_file_upload_operation
        CHECK (operation_id ~ '^op_[A-Za-z0-9]{16,32}$'),
    CONSTRAINT chk_agent_browser_file_upload_hashes
        CHECK (request_hash ~ '^[0-9a-f]{64}$'
            AND base_content_hash ~ '^[0-9a-f]{64}$'
            AND content_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT chk_agent_browser_file_upload_fences
        CHECK (coordinator_term >= 0 AND context_epoch >= 1 AND operation_epoch >= 1
            AND target_revision >= 1 AND base_state_version >= 1),
    CONSTRAINT chk_agent_browser_file_upload_filename
        CHECK (char_length(filename) BETWEEN 1 AND 255
            AND filename !~ '[/\\]' AND filename !~ '[[:cntrl:]]'),
    CONSTRAINT chk_agent_browser_file_upload_mime
        CHECK (char_length(mime_type) BETWEEN 1 AND 255 AND mime_type !~ '[[:cntrl:]]'),
    CONSTRAINT chk_agent_browser_file_upload_size
        CHECK (content_bytes BETWEEN 1 AND 67108864),
    CONSTRAINT chk_agent_browser_file_upload_state
        CHECK (state IN ('STAGING', 'EXECUTING', 'COMMITTED', 'FAILED')),
    CONSTRAINT chk_agent_browser_file_upload_result
        CHECK (
            (state = 'COMMITTED' AND state_version_after IS NOT NULL
                AND error_code IS NULL AND completed_at IS NOT NULL)
            OR (state = 'FAILED' AND error_code IS NOT NULL AND completed_at IS NOT NULL)
            OR (state IN ('STAGING', 'EXECUTING')
                AND error_code IS NULL AND state_version_after IS NULL AND completed_at IS NULL)
        )
);

CREATE INDEX idx_agent_browser_file_upload_session
    ON agent_browser_file_uploads (tenant_id, session_id, created_at DESC);

CREATE INDEX idx_agent_browser_file_upload_active
    ON agent_browser_file_uploads (session_id, operation_epoch)
    WHERE state IN ('STAGING', 'EXECUTING');

COMMENT ON TABLE agent_browser_file_uploads IS
    'Tenant-scoped Agent Browser file upload ledger; file bytes and Node staging paths are never persisted';
