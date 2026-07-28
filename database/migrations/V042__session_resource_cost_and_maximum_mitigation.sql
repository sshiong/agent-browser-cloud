-- Five-minute authoritative cost trend and one-shot non-core mitigation before maximum handling.

ALTER TABLE session_resource_policies
    ADD COLUMN current_hourly_cost NUMERIC(12,6),
    ADD COLUMN cost_pricing_version TEXT,
    ADD COLUMN last_cost_evaluated_at TIMESTAMPTZ,
    ADD COLUMN maximum_mitigation_at TIMESTAMPTZ,
    ADD COLUMN maximum_mitigation_operation_id TEXT;

ALTER TABLE session_resource_policies
    ADD CONSTRAINT ck_session_resource_policy_current_cost
        CHECK (current_hourly_cost IS NULL OR current_hourly_cost >= 0) NOT VALID,
    ADD CONSTRAINT ck_session_resource_policy_maximum_mitigation
        CHECK (
            (maximum_mitigation_at IS NULL AND maximum_mitigation_operation_id IS NULL)
            OR
            (maximum_mitigation_at IS NOT NULL AND maximum_mitigation_operation_id IS NOT NULL)
        ) NOT VALID;

ALTER TABLE session_resource_policies
    VALIDATE CONSTRAINT ck_session_resource_policy_current_cost;

ALTER TABLE session_resource_policies
    VALIDATE CONSTRAINT ck_session_resource_policy_maximum_mitigation;

CREATE TABLE session_resource_cost_snapshots (
    snapshot_id       TEXT PRIMARY KEY,
    session_id        TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
    tenant_id         TEXT NOT NULL,
    node_id           TEXT NOT NULL,
    pricing_version   TEXT NOT NULL,
    hourly_cost       NUMERIC(12,6) NOT NULL CHECK (hourly_cost >= 0),
    observed_at       TIMESTAMPTZ NOT NULL,
    UNIQUE (session_id, observed_at)
);

CREATE INDEX idx_session_resource_cost_snapshots_timeline
    ON session_resource_cost_snapshots(session_id, observed_at DESC);

COMMENT ON TABLE session_resource_cost_snapshots IS
    'Authoritative five-minute Session cost trend resolved from Placement and versioned enterprise rates';
COMMENT ON COLUMN session_resource_policies.maximum_mitigation_operation_id IS
    'One-shot real Node resource adjustment attempted before maximum policy escalation';
