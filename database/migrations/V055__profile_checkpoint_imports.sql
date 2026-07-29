-- Transactional Profile/Checkpoint Import control ledger.
--
-- This is an expand-only migration. N-1 applications ignore the new table.
-- Rollback is disabling the API/UI and Node capability label; imported Profiles
-- remain valid ordinary Profiles and no destructive DDL is required.

CREATE TABLE profile_import_jobs (
    import_id              TEXT PRIMARY KEY,
    tenant_id              TEXT NOT NULL,
    owner_actor_id         TEXT NOT NULL,
    idempotency_key        TEXT NOT NULL,
    request_hash           TEXT NOT NULL,
    request_id             TEXT NOT NULL,
    operation_id           TEXT NOT NULL,
    profile_id             TEXT NOT NULL,
    profile_name           TEXT NOT NULL,
    profile_description    TEXT,
    runtime_build_id       TEXT NOT NULL,
    archive_sha256         TEXT NOT NULL,
    archive_size_bytes     BIGINT NOT NULL,
    state                  TEXT NOT NULL,
    node_id                TEXT,
    checkpoint_id          TEXT NOT NULL,
    checkpoint_epoch       BIGINT,
    profile_write_epoch    BIGINT,
    core_size_bytes        BIGINT,
    checkpoint_file_count  BIGINT,
    error_code             TEXT,
    created_at             TIMESTAMPTZ NOT NULL,
    updated_at             TIMESTAMPTZ NOT NULL,
    completed_at           TIMESTAMPTZ,
    version                BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_profile_import_idempotency
        UNIQUE (tenant_id, owner_actor_id, idempotency_key),
    CONSTRAINT chk_profile_import_id
        CHECK (import_id ~ '^pim_[a-zA-Z0-9]{16,32}$'),
    CONSTRAINT chk_profile_import_operation_id
        CHECK (operation_id ~ '^op_[a-zA-Z0-9]{16,32}$'),
    CONSTRAINT chk_profile_import_profile_id
        CHECK (profile_id ~ '^[A-Za-z0-9_-]{1,128}$'),
    CONSTRAINT chk_profile_import_profile_name
        CHECK (char_length(profile_name) BETWEEN 1 AND 128),
    CONSTRAINT chk_profile_import_runtime
        CHECK (runtime_build_id ~ '^[A-Za-z0-9_-]{1,128}$'),
    CONSTRAINT chk_profile_import_archive_hash
        CHECK (archive_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT chk_profile_import_request_hash
        CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT chk_profile_import_archive_size
        CHECK (archive_size_bytes BETWEEN 1 AND 268435456),
    CONSTRAINT chk_profile_import_checkpoint
        CHECK (checkpoint_id ~ '^chk_[A-Za-z0-9_-]{16,128}$'),
    CONSTRAINT chk_profile_import_state
        CHECK (state IN ('REQUESTED', 'UPLOADING', 'VALIDATING', 'COMMITTED', 'FAILED')),
    CONSTRAINT chk_profile_import_result
        CHECK (
            (
                state = 'COMMITTED'
                AND node_id IS NOT NULL
                AND checkpoint_epoch IS NOT NULL
                AND profile_write_epoch IS NOT NULL
                AND core_size_bytes IS NOT NULL
                AND checkpoint_file_count IS NOT NULL
                AND error_code IS NULL
                AND completed_at IS NOT NULL
            )
            OR (
                state = 'FAILED'
                AND error_code IS NOT NULL
                AND completed_at IS NOT NULL
            )
            OR (
                state IN ('REQUESTED', 'UPLOADING', 'VALIDATING')
                AND completed_at IS NULL
            )
        )
);

CREATE INDEX idx_profile_import_jobs_owner
    ON profile_import_jobs (tenant_id, owner_actor_id, created_at DESC);

CREATE INDEX idx_profile_import_jobs_profile
    ON profile_import_jobs (tenant_id, profile_id, created_at DESC);

COMMENT ON TABLE profile_import_jobs IS
    'Tenant/actor-owned Profile archive ingress and Storage Helper commit ledger; raw archive bytes never enter PostgreSQL';

COMMENT ON COLUMN profile_import_jobs.operation_id IS
    'Stable user-visible import Operation identifier; state is driven by the durable import job';

COMMENT ON COLUMN profile_import_jobs.archive_sha256 IS
    'Client-declared hash revalidated independently by Control Plane, Browser Node and Storage Helper';
