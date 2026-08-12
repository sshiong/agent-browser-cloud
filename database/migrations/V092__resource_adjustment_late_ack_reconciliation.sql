-- A timed-out resource adjustment may have already been applied by the Browser Node. When the
-- durable ACK arrives late, retain the original timeout and link a separate committed Operation
-- that reconciles PostgreSQL authority with the strictly verified Node allocation.

ALTER TABLE session_resource_adjustments
    ADD COLUMN reconciliation_operation_id TEXT
        REFERENCES exclusive_operations(operation_id),
    ADD COLUMN reconciled_at TIMESTAMPTZ;

ALTER TABLE session_resource_adjustments
    DROP CONSTRAINT ck_resource_adjustment_state,
    DROP CONSTRAINT ck_resource_adjustment_terminal,
    DROP CONSTRAINT ck_resource_adjustment_failure;

ALTER TABLE session_resource_adjustments
    ADD CONSTRAINT ck_resource_adjustment_state
        CHECK (state IN ('REQUESTED', 'EXECUTING', 'ACKNOWLEDGED', 'COMMITTED', 'FAILED', 'RECONCILED')),
    ADD CONSTRAINT ck_resource_adjustment_terminal
        CHECK (
            (state IN ('COMMITTED', 'FAILED', 'RECONCILED') AND completed_at IS NOT NULL)
            OR (state NOT IN ('COMMITTED', 'FAILED', 'RECONCILED') AND completed_at IS NULL)
        ),
    ADD CONSTRAINT ck_resource_adjustment_failure
        CHECK (
            (state IN ('FAILED', 'RECONCILED') AND failure_code IS NOT NULL)
            OR (state NOT IN ('FAILED', 'RECONCILED') AND failure_code IS NULL)
        ),
    ADD CONSTRAINT ck_resource_adjustment_reconciliation
        CHECK (
            (state = 'RECONCILED'
                AND failure_code = 'NODE_ACK_TIMEOUT'
                AND reconciliation_operation_id IS NOT NULL
                AND reconciled_at IS NOT NULL)
            OR (state <> 'RECONCILED'
                AND reconciliation_operation_id IS NULL
                AND reconciled_at IS NULL)
        );

COMMENT ON COLUMN session_resource_adjustments.reconciliation_operation_id IS
    'Committed compensating Operation that reconciled a late ACK after NODE_ACK_TIMEOUT';
