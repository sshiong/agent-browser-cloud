-- Tenant-authoritative Environment Import preview and execution ledger.
--
-- Import payloads contain only the explicit, validated environment contract.
-- They never store credentials, browser state, uploaded files or Session result snapshots.

CREATE TABLE environment_import_jobs (
    import_id          TEXT PRIMARY KEY,
    tenant_id          TEXT NOT NULL,
    owner_actor_id     TEXT NOT NULL,
    name               TEXT NOT NULL,
    schema_version     INTEGER NOT NULL,
    manifest_hash      TEXT NOT NULL,
    state              TEXT NOT NULL,
    total_count        INTEGER NOT NULL,
    ready_count        INTEGER NOT NULL,
    succeeded_count    INTEGER NOT NULL DEFAULT 0,
    created_at         TIMESTAMPTZ NOT NULL,
    updated_at         TIMESTAMPTZ NOT NULL,
    committed_at       TIMESTAMPTZ,
    version            BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_environment_import_job_tenant UNIQUE (import_id, tenant_id),
    CONSTRAINT chk_environment_import_id CHECK (
        import_id ~ '^imp_[a-zA-Z0-9]{16,32}$'
    ),
    CONSTRAINT chk_environment_import_owner CHECK (
        owner_actor_id ~ '^[A-Za-z0-9_-]{1,128}$'
    ),
    CONSTRAINT chk_environment_import_name CHECK (
        char_length(btrim(name)) BETWEEN 1 AND 96
    ),
    CONSTRAINT chk_environment_import_schema CHECK (schema_version = 1),
    CONSTRAINT chk_environment_import_manifest_hash CHECK (
        manifest_hash ~ '^[a-f0-9]{64}$'
    ),
    CONSTRAINT chk_environment_import_state CHECK (
        state IN ('VALIDATED', 'INVALID', 'EXECUTING', 'COMMITTED')
    ),
    CONSTRAINT chk_environment_import_counts CHECK (
        total_count BETWEEN 1 AND 25
        AND ready_count BETWEEN 0 AND total_count
        AND succeeded_count BETWEEN 0 AND total_count
    ),
    CONSTRAINT chk_environment_import_commit CHECK (
        (state = 'COMMITTED' AND committed_at IS NOT NULL AND succeeded_count = total_count)
        OR (state <> 'COMMITTED' AND committed_at IS NULL)
    )
);

CREATE TABLE environment_import_items (
    item_id             TEXT PRIMARY KEY,
    import_id           TEXT NOT NULL,
    tenant_id           TEXT NOT NULL,
    item_index          INTEGER NOT NULL,
    display_name        TEXT NOT NULL,
    request_payload     JSONB NOT NULL,
    request_hash        TEXT NOT NULL,
    validation_state    TEXT NOT NULL,
    validation_errors   JSONB NOT NULL DEFAULT '[]'::jsonb,
    execution_state     TEXT NOT NULL DEFAULT 'PENDING',
    session_id          TEXT,
    operation_id        TEXT,
    request_id          TEXT,
    created_at          TIMESTAMPTZ NOT NULL,
    updated_at          TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_environment_import_item_job
        FOREIGN KEY (import_id, tenant_id)
        REFERENCES environment_import_jobs(import_id, tenant_id)
        ON DELETE CASCADE,
    CONSTRAINT uq_environment_import_item_index UNIQUE (import_id, item_index),
    CONSTRAINT chk_environment_import_item_id CHECK (
        item_id ~ '^imi_[a-zA-Z0-9]{16,32}$'
    ),
    CONSTRAINT chk_environment_import_item_index CHECK (
        item_index BETWEEN 0 AND 24
    ),
    CONSTRAINT chk_environment_import_item_name CHECK (
        char_length(btrim(display_name)) BETWEEN 1 AND 96
    ),
    CONSTRAINT chk_environment_import_item_payload CHECK (
        jsonb_typeof(request_payload) = 'object'
    ),
    CONSTRAINT chk_environment_import_item_hash CHECK (
        request_hash ~ '^[a-f0-9]{64}$'
    ),
    CONSTRAINT chk_environment_import_item_validation CHECK (
        validation_state IN ('READY', 'INVALID')
        AND jsonb_typeof(validation_errors) = 'array'
    ),
    CONSTRAINT chk_environment_import_item_execution CHECK (
        execution_state IN ('PENDING', 'SUCCEEDED')
    ),
    CONSTRAINT chk_environment_import_item_result CHECK (
        (execution_state = 'PENDING' AND session_id IS NULL AND operation_id IS NULL)
        OR (
            execution_state = 'SUCCEEDED'
            AND session_id ~ '^ses_[a-zA-Z0-9]{16,}$'
            AND operation_id ~ '^op_[a-zA-Z0-9]{16,}$'
        )
    )
);

CREATE INDEX idx_environment_import_jobs_owner
ON environment_import_jobs (tenant_id, owner_actor_id, created_at DESC);

CREATE INDEX idx_environment_import_items_job
ON environment_import_items (tenant_id, import_id, item_index);

COMMENT ON TABLE environment_import_jobs IS
    'Validated, tenant-authoritative Environment Import execution ledger';

COMMENT ON TABLE environment_import_items IS
    'Explicit non-secret environment contracts and their real Session/Operation results';
