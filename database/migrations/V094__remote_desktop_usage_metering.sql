-- Idempotent per-connection VNC egress metering and versioned cost attribution. Browser Nodes
-- report monotonic counters; the Control Plane persists only positive deltas in this ledger.

ALTER TABLE enterprise_cost_rates
    ADD COLUMN remote_desktop_egress_gib_usd NUMERIC(12,6) NOT NULL DEFAULT 0;

ALTER TABLE enterprise_cost_rates
    ADD CONSTRAINT chk_enterprise_cost_remote_desktop_egress
      CHECK (remote_desktop_egress_gib_usd >= 0) NOT VALID;
ALTER TABLE enterprise_cost_rates
    VALIDATE CONSTRAINT chk_enterprise_cost_remote_desktop_egress;

ALTER TABLE remote_desktop_participants
    ADD COLUMN forwarded_bytes BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN quota_wait_millis BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN throttled_batches BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN egress_cost_usd NUMERIC(18,9) NOT NULL DEFAULT 0,
    ADD COLUMN unpriced_forwarded_bytes BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN last_cost_pricing_version TEXT,
    ADD COLUMN last_egress_gib_usd NUMERIC(12,6);

ALTER TABLE remote_desktop_participants
    ADD CONSTRAINT chk_remote_desktop_participant_usage
      CHECK (
        forwarded_bytes >= 0 AND quota_wait_millis >= 0 AND throttled_batches >= 0
        AND egress_cost_usd >= 0 AND unpriced_forwarded_bytes >= 0
      ) NOT VALID,
    ADD CONSTRAINT chk_remote_desktop_participant_usage_pricing
      CHECK (
        (last_cost_pricing_version IS NULL AND last_egress_gib_usd IS NULL)
        OR
        (last_cost_pricing_version IS NOT NULL AND last_egress_gib_usd IS NOT NULL
          AND last_egress_gib_usd >= 0)
      ) NOT VALID;
ALTER TABLE remote_desktop_participants
    VALIDATE CONSTRAINT chk_remote_desktop_participant_usage;
ALTER TABLE remote_desktop_participants
    VALIDATE CONSTRAINT chk_remote_desktop_participant_usage_pricing;

CREATE TABLE remote_desktop_usage_ledger (
    event_id             TEXT PRIMARY KEY,
    connection_id        TEXT NOT NULL,
    tenant_id            TEXT NOT NULL,
    session_id           TEXT NOT NULL,
    actor_id             TEXT NOT NULL,
    delta_forwarded_bytes BIGINT NOT NULL,
    delta_quota_wait_millis BIGINT NOT NULL,
    delta_throttled_batches BIGINT NOT NULL,
    pricing_version      TEXT,
    egress_gib_usd       NUMERIC(12,6),
    attributed_cost_usd  NUMERIC(18,9) NOT NULL,
    observed_at          TIMESTAMPTZ NOT NULL,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_remote_desktop_usage_ledger_delta
      CHECK (
        delta_forwarded_bytes >= 0 AND delta_quota_wait_millis >= 0
        AND delta_throttled_batches >= 0 AND attributed_cost_usd >= 0
      ),
    CONSTRAINT chk_remote_desktop_usage_ledger_pricing
      CHECK (
        (pricing_version IS NULL AND egress_gib_usd IS NULL)
        OR (pricing_version IS NOT NULL AND egress_gib_usd IS NOT NULL
          AND egress_gib_usd >= 0)
      )
);

CREATE INDEX idx_remote_desktop_usage_ledger_session
    ON remote_desktop_usage_ledger(tenant_id, session_id, observed_at DESC, event_id);
CREATE INDEX idx_remote_desktop_usage_ledger_actor
    ON remote_desktop_usage_ledger(tenant_id, actor_id, observed_at DESC, event_id);

COMMENT ON TABLE remote_desktop_usage_ledger IS
  'Idempotent Browser Node RFB egress deltas and versioned actor cost attribution retained independently from short-lived participant presence; no ticket credential stored';
COMMENT ON COLUMN remote_desktop_participants.forwarded_bytes IS
  'Monotonic bytes successfully written by the RFB fan-out path for this exact connection';
