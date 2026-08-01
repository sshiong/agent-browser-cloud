-- Durable tenant-scoped batch lifecycle operations for Groups, Tags and explicit Session sets.
--
-- The header records intent and idempotency. Every item references a physically routed
-- coordinator command, so accepted work survives API/Control Plane restarts and exposes real
-- PENDING/EXECUTING/COMMITTED/FAILED state. No browser or profile content is stored here.

CREATE TABLE workspace_batch_operations (
    batch_operation_id         TEXT PRIMARY KEY,
    tenant_id                  TEXT NOT NULL,
    actor_id                   TEXT NOT NULL,
    action                     TEXT NOT NULL,
    selector                   JSONB NOT NULL,
    reason                     TEXT,
    request_hash               TEXT NOT NULL,
    idempotency_key            TEXT NOT NULL,
    cancellation_requested_at  TIMESTAMPTZ,
    cancellation_request_hash  TEXT,
    cancellation_idempotency_key TEXT,
    created_at                 TIMESTAMPTZ NOT NULL,
    updated_at                 TIMESTAMPTZ NOT NULL,
    CONSTRAINT chk_workspace_batch_operation_id CHECK (
        batch_operation_id ~ '^bop_[a-zA-Z0-9]{16,32}$'
    ),
    CONSTRAINT chk_workspace_batch_operation_actor CHECK (
        actor_id ~ '^[A-Za-z0-9_-]{1,128}$'
    ),
    CONSTRAINT chk_workspace_batch_operation_action CHECK (
        action IN ('START', 'PAUSE_AGENT', 'MIGRATE', 'HIBERNATE')
    ),
    CONSTRAINT chk_workspace_batch_operation_selector CHECK (
        jsonb_typeof(selector) = 'object'
    ),
    CONSTRAINT chk_workspace_batch_operation_reason CHECK (
        reason IS NULL OR char_length(reason) BETWEEN 8 AND 240
    ),
    CONSTRAINT chk_workspace_batch_operation_request_hash CHECK (
        request_hash ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT chk_workspace_batch_operation_idempotency CHECK (
        char_length(idempotency_key) BETWEEN 1 AND 128
    ),
    CONSTRAINT chk_workspace_batch_cancel_request_hash CHECK (
        cancellation_request_hash IS NULL
        OR cancellation_request_hash ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT chk_workspace_batch_cancel_idempotency CHECK (
        cancellation_idempotency_key IS NULL
        OR char_length(cancellation_idempotency_key) BETWEEN 1 AND 128
    ),
    CONSTRAINT chk_workspace_batch_cancel_pair CHECK (
        (cancellation_requested_at IS NULL
            AND cancellation_request_hash IS NULL
            AND cancellation_idempotency_key IS NULL)
        OR
        (cancellation_requested_at IS NOT NULL
            AND cancellation_request_hash IS NOT NULL
            AND cancellation_idempotency_key IS NOT NULL)
    ),
    UNIQUE (batch_operation_id, tenant_id),
    UNIQUE (tenant_id, idempotency_key)
);

CREATE TABLE workspace_batch_operation_items (
    batch_item_id       TEXT PRIMARY KEY,
    batch_operation_id  TEXT NOT NULL,
    tenant_id           TEXT NOT NULL,
    session_id          TEXT NOT NULL,
    ordinal             INTEGER NOT NULL,
    command_id          TEXT NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL,
    CONSTRAINT chk_workspace_batch_item_id CHECK (
        batch_item_id ~ '^bopi_[a-zA-Z0-9]{16,32}$'
    ),
    CONSTRAINT chk_workspace_batch_item_ordinal CHECK (
        ordinal BETWEEN 0 AND 99
    ),
    CONSTRAINT fk_workspace_batch_item_operation
        FOREIGN KEY (batch_operation_id, tenant_id)
        REFERENCES workspace_batch_operations(batch_operation_id, tenant_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_workspace_batch_item_session
        FOREIGN KEY (session_id, tenant_id)
        REFERENCES sessions(id, tenant_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_workspace_batch_item_command
        FOREIGN KEY (command_id)
        REFERENCES coordinator_commands(command_id)
        ON DELETE RESTRICT,
    UNIQUE (batch_operation_id, session_id),
    UNIQUE (batch_operation_id, ordinal),
    UNIQUE (command_id)
);

CREATE INDEX idx_workspace_batch_operations_tenant_created
    ON workspace_batch_operations (tenant_id, created_at DESC, batch_operation_id);

CREATE INDEX idx_workspace_batch_items_operation
    ON workspace_batch_operation_items (batch_operation_id, ordinal);

COMMENT ON TABLE workspace_batch_operations IS
    'Tenant-authoritative Group/Tag/Session batch lifecycle intent and idempotency ledger';
COMMENT ON TABLE workspace_batch_operation_items IS
    'One bounded Session target linked to its durable physically routed coordinator command';
