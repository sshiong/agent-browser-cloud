-- Durable tenant-scoped Group/Tag membership mutation batches.
--
-- This is an additive expand migration. A dedicated ledger keeps N-1 lifecycle batch readers
-- isolated from the new metadata action enum during rolling deploys and rollback.

CREATE OR REPLACE FUNCTION is_valid_workspace_metadata_batch_tag_ids(candidate JSONB)
RETURNS BOOLEAN
LANGUAGE SQL
IMMUTABLE
STRICT
PARALLEL SAFE
AS $$
  SELECT CASE
    WHEN jsonb_typeof(candidate) <> 'array' THEN FALSE
    ELSE (
      SELECT
        count(*) <= 16
        AND count(*) = count(DISTINCT value)
        AND coalesce(bool_and(value ~ '^tag_[a-zA-Z0-9]{16,32}$'), TRUE)
      FROM jsonb_array_elements_text(candidate) AS element(value)
    )
  END
$$;

CREATE TABLE workspace_metadata_batch_operations (
    batch_operation_id           TEXT PRIMARY KEY,
    tenant_id                    TEXT NOT NULL,
    actor_id                     TEXT NOT NULL,
    action                       TEXT NOT NULL,
    selector                     JSONB NOT NULL,
    target_group_id              TEXT,
    target_tag_ids               JSONB NOT NULL DEFAULT '[]'::jsonb,
    reason                       TEXT NOT NULL,
    request_hash                 TEXT NOT NULL,
    idempotency_key              TEXT NOT NULL,
    cancellation_requested_at    TIMESTAMPTZ,
    cancellation_request_hash    TEXT,
    cancellation_idempotency_key TEXT,
    deadline_at                  TIMESTAMPTZ NOT NULL,
    created_at                   TIMESTAMPTZ NOT NULL,
    updated_at                   TIMESTAMPTZ NOT NULL,
    CONSTRAINT chk_workspace_metadata_batch_operation_id CHECK (
        batch_operation_id ~ '^mbop_[a-zA-Z0-9]{16,32}$'
    ),
    CONSTRAINT chk_workspace_metadata_batch_actor CHECK (
        actor_id ~ '^[A-Za-z0-9_-]{1,128}$'
    ),
    CONSTRAINT chk_workspace_metadata_batch_action CHECK (
        action IN ('ASSIGN_GROUP', 'REMOVE_GROUP', 'ASSIGN_TAGS', 'REMOVE_TAGS')
    ),
    CONSTRAINT chk_workspace_metadata_batch_selector CHECK (
        jsonb_typeof(selector) = 'object'
    ),
    CONSTRAINT chk_workspace_metadata_batch_group_id CHECK (
        target_group_id IS NULL
        OR target_group_id ~ '^grp_[a-zA-Z0-9]{16,32}$'
    ),
    CONSTRAINT chk_workspace_metadata_batch_tag_ids CHECK (
        is_valid_workspace_metadata_batch_tag_ids(target_tag_ids)
    ),
    CONSTRAINT chk_workspace_metadata_batch_target CHECK (
        (
          action IN ('ASSIGN_GROUP', 'REMOVE_GROUP')
          AND target_group_id IS NOT NULL
          AND jsonb_array_length(target_tag_ids) = 0
        )
        OR
        (
          action IN ('ASSIGN_TAGS', 'REMOVE_TAGS')
          AND target_group_id IS NULL
          AND jsonb_array_length(target_tag_ids) BETWEEN 1 AND 16
        )
    ),
    CONSTRAINT chk_workspace_metadata_batch_reason CHECK (
        char_length(reason) BETWEEN 8 AND 240
    ),
    CONSTRAINT chk_workspace_metadata_batch_request_hash CHECK (
        request_hash ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT chk_workspace_metadata_batch_idempotency CHECK (
        char_length(idempotency_key) BETWEEN 1 AND 128
    ),
    CONSTRAINT chk_workspace_metadata_batch_cancel_hash CHECK (
        cancellation_request_hash IS NULL
        OR cancellation_request_hash ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT chk_workspace_metadata_batch_cancel_idempotency CHECK (
        cancellation_idempotency_key IS NULL
        OR char_length(cancellation_idempotency_key) BETWEEN 1 AND 128
    ),
    CONSTRAINT chk_workspace_metadata_batch_cancel_pair CHECK (
        (
          cancellation_requested_at IS NULL
          AND cancellation_request_hash IS NULL
          AND cancellation_idempotency_key IS NULL
        )
        OR
        (
          cancellation_requested_at IS NOT NULL
          AND cancellation_request_hash IS NOT NULL
          AND cancellation_idempotency_key IS NOT NULL
        )
    ),
    UNIQUE (batch_operation_id, tenant_id),
    UNIQUE (tenant_id, idempotency_key)
);

CREATE TABLE workspace_metadata_batch_operation_items (
    batch_item_id       TEXT PRIMARY KEY,
    batch_operation_id  TEXT NOT NULL,
    tenant_id           TEXT NOT NULL,
    session_id          TEXT NOT NULL,
    ordinal             INTEGER NOT NULL,
    state               TEXT NOT NULL DEFAULT 'ACCEPTED',
    failure_code        TEXT,
    attempt             INTEGER NOT NULL DEFAULT 0,
    claim_owner         TEXT,
    claim_lease_until   TIMESTAMPTZ,
    next_attempt_at     TIMESTAMPTZ NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL,
    started_at          TIMESTAMPTZ,
    completed_at        TIMESTAMPTZ,
    CONSTRAINT chk_workspace_metadata_batch_item_id CHECK (
        batch_item_id ~ '^mbopi_[a-zA-Z0-9]{16,32}$'
    ),
    CONSTRAINT chk_workspace_metadata_batch_item_ordinal CHECK (
        ordinal BETWEEN 0 AND 99
    ),
    CONSTRAINT chk_workspace_metadata_batch_item_state CHECK (
        state IN ('ACCEPTED', 'EXECUTING', 'SUCCEEDED', 'FAILED', 'CANCELLED')
    ),
    CONSTRAINT chk_workspace_metadata_batch_item_failure CHECK (
        failure_code IS NULL OR char_length(failure_code) BETWEEN 3 AND 240
    ),
    CONSTRAINT chk_workspace_metadata_batch_item_attempt CHECK (
        attempt BETWEEN 0 AND 3
    ),
    CONSTRAINT chk_workspace_metadata_batch_item_claim_pair CHECK (
        (claim_owner IS NULL AND claim_lease_until IS NULL)
        OR (claim_owner IS NOT NULL AND claim_lease_until IS NOT NULL)
    ),
    CONSTRAINT fk_workspace_metadata_batch_item_operation
        FOREIGN KEY (batch_operation_id, tenant_id)
        REFERENCES workspace_metadata_batch_operations(batch_operation_id, tenant_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_workspace_metadata_batch_item_session
        FOREIGN KEY (session_id, tenant_id)
        REFERENCES sessions(id, tenant_id)
        ON DELETE CASCADE,
    UNIQUE (batch_operation_id, session_id),
    UNIQUE (batch_operation_id, ordinal)
);

CREATE INDEX idx_workspace_metadata_batch_operations_tenant_created
    ON workspace_metadata_batch_operations (tenant_id, created_at DESC, batch_operation_id);

CREATE INDEX idx_workspace_metadata_batch_items_claim
    ON workspace_metadata_batch_operation_items (state, next_attempt_at, claim_lease_until)
    WHERE state IN ('ACCEPTED', 'EXECUTING');

CREATE INDEX idx_workspace_metadata_batch_items_operation
    ON workspace_metadata_batch_operation_items (batch_operation_id, ordinal);

COMMENT ON TABLE workspace_metadata_batch_operations IS
    'Tenant-authoritative Group/Tag membership batch intent, target and idempotency ledger';
COMMENT ON TABLE workspace_metadata_batch_operation_items IS
    'Crash-recoverable per-Session metadata mutation state with bounded lease and retry';
