-- Tenant-scoped, state-fenced Agent Browser JavaScript evaluations.
--
-- Script source is AES-GCM sealed inside the durable Node command and is never copied into this
-- table or ordinary audit rows. Results are bounded JSON returned only to the requesting actor.
-- Every request owns one short-lived exclusive Operation so PAGE_ACTION cannot race another
-- Agent, migration, upload or human input path. The migration is expand-only; N-1 applications
-- ignore the table and N-1 Nodes reject the additive command safely.

CREATE TABLE agent_browser_javascript_evaluations (
    evaluation_id                    TEXT PRIMARY KEY,
    tenant_id                        TEXT NOT NULL,
    session_id                       TEXT NOT NULL,
    actor_id                         TEXT NOT NULL,
    idempotency_key                  TEXT NOT NULL,
    request_hash                     TEXT NOT NULL,
    request_id                       TEXT NOT NULL,
    operation_id                     TEXT NOT NULL UNIQUE,
    command_id                       TEXT NOT NULL UNIQUE,
    node_id                          TEXT NOT NULL,
    coordinator_term                 BIGINT NOT NULL,
    context_epoch                    BIGINT NOT NULL,
    operation_epoch                  BIGINT NOT NULL,
    evaluation_mode                  TEXT NOT NULL,
    expression_sha256                TEXT NOT NULL,
    expression_bytes                 INTEGER NOT NULL,
    await_promise                    BOOLEAN NOT NULL,
    timeout_ms                       INTEGER NOT NULL,
    maximum_result_bytes             INTEGER NOT NULL,
    expected_state_version           BIGINT NOT NULL,
    expected_target_revision         BIGINT NOT NULL,
    expected_state_hash              TEXT NOT NULL,
    expected_active_tab_id           TEXT NOT NULL,
    state                            TEXT NOT NULL,
    result_type                      TEXT,
    result_json                      TEXT,
    result_bytes                     INTEGER,
    redacted_value_count             INTEGER,
    exception_class                  TEXT,
    exception_message                TEXT,
    error_code                       TEXT,
    state_version_after              BIGINT,
    target_revision_after            BIGINT,
    state_hash_after                 TEXT,
    active_tab_id_after              TEXT,
    duration_ms                      INTEGER,
    created_at                       TIMESTAMPTZ NOT NULL,
    updated_at                       TIMESTAMPTZ NOT NULL,
    completed_at                     TIMESTAMPTZ,
    version                          BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_agent_browser_evaluation_idempotency
        UNIQUE (tenant_id, actor_id, idempotency_key),
    CONSTRAINT fk_agent_browser_evaluation_session
        FOREIGN KEY (session_id, tenant_id) REFERENCES sessions(id, tenant_id) ON DELETE CASCADE,
    CONSTRAINT fk_agent_browser_evaluation_operation
        FOREIGN KEY (operation_id) REFERENCES exclusive_operations(operation_id),
    CONSTRAINT chk_agent_browser_evaluation_id
        CHECK (evaluation_id ~ '^aje_[A-Za-z0-9]{20}$'),
    CONSTRAINT chk_agent_browser_evaluation_command_id
        CHECK (command_id ~ '^cmd_[A-Za-z0-9]{20}$'),
    CONSTRAINT chk_agent_browser_evaluation_hashes
        CHECK (request_hash ~ '^[0-9a-f]{64}$'
            AND expression_sha256 ~ '^[0-9a-f]{64}$'
            AND expected_state_hash ~ '^[0-9a-f]{64}$'
            AND (state_hash_after IS NULL OR state_hash_after ~ '^[0-9a-f]{64}$')),
    CONSTRAINT chk_agent_browser_evaluation_fences
        CHECK (coordinator_term >= 0 AND context_epoch >= 1 AND operation_epoch >= 1
            AND expected_state_version >= 1 AND expected_target_revision >= 1),
    CONSTRAINT chk_agent_browser_evaluation_mode
        CHECK (evaluation_mode IN ('READ_ONLY', 'PAGE_ACTION')),
    CONSTRAINT chk_agent_browser_evaluation_input_bounds
        CHECK (expression_bytes BETWEEN 1 AND 16384
            AND timeout_ms BETWEEN 100 AND 5000
            AND maximum_result_bytes BETWEEN 1 AND 32768
            AND char_length(expected_active_tab_id) BETWEEN 1 AND 128
            AND expected_active_tab_id !~ '[[:cntrl:]]'),
    CONSTRAINT chk_agent_browser_evaluation_state
        CHECK (state IN ('EXECUTING', 'COMMITTED', 'FAILED')),
    CONSTRAINT chk_agent_browser_evaluation_output_bounds
        CHECK ((result_json IS NULL OR octet_length(result_json) <= maximum_result_bytes)
            AND (result_bytes IS NULL OR result_bytes BETWEEN 0 AND maximum_result_bytes)
            AND (redacted_value_count IS NULL OR redacted_value_count BETWEEN 0 AND 10000)
            AND (exception_class IS NULL OR char_length(exception_class) BETWEEN 1 AND 256)
            AND (exception_message IS NULL OR char_length(exception_message) BETWEEN 1 AND 2048)
            AND (error_code IS NULL OR error_code ~ '^[A-Z][A-Z0-9_]{2,127}$')
            AND (duration_ms IS NULL OR duration_ms BETWEEN 0 AND 30000)),
    CONSTRAINT chk_agent_browser_evaluation_result
        CHECK (
            (state = 'EXECUTING'
                AND result_type IS NULL AND result_json IS NULL AND result_bytes IS NULL
                AND redacted_value_count IS NULL
                AND exception_class IS NULL AND exception_message IS NULL AND error_code IS NULL
                AND state_version_after IS NULL AND target_revision_after IS NULL
                AND state_hash_after IS NULL AND active_tab_id_after IS NULL
                AND duration_ms IS NULL AND completed_at IS NULL)
            OR
            (state = 'COMMITTED'
                AND result_type IS NOT NULL AND result_json IS NOT NULL AND result_bytes IS NOT NULL
                AND redacted_value_count IS NOT NULL
                AND exception_class IS NULL AND exception_message IS NULL AND error_code IS NULL
                AND state_version_after >= expected_state_version
                AND target_revision_after >= 1
                AND state_hash_after IS NOT NULL AND active_tab_id_after IS NOT NULL
                AND duration_ms IS NOT NULL AND completed_at IS NOT NULL)
            OR
            (state = 'FAILED'
                AND result_type IS NULL AND result_json IS NULL AND result_bytes IS NULL
                AND redacted_value_count IS NULL AND error_code IS NOT NULL
                AND duration_ms IS NOT NULL AND completed_at IS NOT NULL)
        )
);

CREATE INDEX idx_agent_browser_evaluation_session
    ON agent_browser_javascript_evaluations (tenant_id, session_id, created_at DESC);

CREATE INDEX idx_agent_browser_evaluation_executing
    ON agent_browser_javascript_evaluations (session_id, created_at)
    WHERE state = 'EXECUTING';

COMMENT ON TABLE agent_browser_javascript_evaluations IS
    'State-fenced governed Runtime.evaluate ledger; script source is sealed in Outbox and audit stores hashes only';
