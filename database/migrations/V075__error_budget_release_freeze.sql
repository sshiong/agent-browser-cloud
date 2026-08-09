ALTER TABLE enterprise_slo_policies
    ADD COLUMN release_freeze_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN release_freeze_burn_rate_threshold NUMERIC(12,6) NOT NULL DEFAULT 1.000000,
    ADD COLUMN release_recovery_burn_rate_threshold NUMERIC(12,6) NOT NULL DEFAULT 0.500000,
    ADD COLUMN release_freeze_window_minutes INTEGER NOT NULL DEFAULT 60,
    ADD COLUMN release_recovery_stable_minutes INTEGER NOT NULL DEFAULT 30,
    ADD CONSTRAINT chk_enterprise_release_freeze_thresholds CHECK (
        release_freeze_burn_rate_threshold > 0
        AND release_freeze_burn_rate_threshold <= 1000
        AND release_recovery_burn_rate_threshold >= 0
        AND release_recovery_burn_rate_threshold < release_freeze_burn_rate_threshold
    ),
    ADD CONSTRAINT chk_enterprise_release_freeze_windows CHECK (
        release_freeze_window_minutes BETWEEN 5 AND 1440
        AND release_recovery_stable_minutes BETWEEN 1 AND 1440
    );

CREATE TABLE enterprise_release_freeze_states (
    tenant_id                  TEXT PRIMARY KEY REFERENCES enterprise_slo_policies(tenant_id),
    frozen                     BOOLEAN NOT NULL,
    phase                      TEXT NOT NULL,
    current_burn_rate          NUMERIC(18,6) NOT NULL,
    reason_code                TEXT NOT NULL,
    stable_since               TIMESTAMPTZ,
    frozen_at                  TIMESTAMPTZ,
    cleared_at                 TIMESTAMPTZ,
    evaluated_at               TIMESTAMPTZ NOT NULL,
    version                    BIGINT NOT NULL DEFAULT 1,
    CONSTRAINT chk_enterprise_release_freeze_phase CHECK (
        phase IN ('OPEN', 'FROZEN', 'RECOVERING')
        AND ((frozen = FALSE AND phase = 'OPEN')
          OR (frozen = TRUE AND phase IN ('FROZEN', 'RECOVERING')))
    ),
    CONSTRAINT chk_enterprise_release_freeze_burn CHECK (current_burn_rate >= 0),
    CONSTRAINT chk_enterprise_release_freeze_version CHECK (version >= 1)
);

INSERT INTO enterprise_release_freeze_states(
    tenant_id, frozen, phase, current_burn_rate, reason_code,
    stable_since, frozen_at, cleared_at, evaluated_at, version
)
SELECT tenant_id, FALSE, 'OPEN', 0, 'POLICY_DISABLED',
       NULL, NULL, NULL, now(), 1
FROM enterprise_slo_policies;

CREATE TABLE enterprise_release_freeze_events (
    freeze_event_id            TEXT PRIMARY KEY,
    tenant_id                  TEXT NOT NULL REFERENCES enterprise_slo_policies(tenant_id),
    transition                 TEXT NOT NULL,
    burn_rate                  NUMERIC(18,6) NOT NULL,
    threshold                  NUMERIC(12,6) NOT NULL,
    evaluation_window_minutes  INTEGER NOT NULL,
    reason_code                TEXT NOT NULL,
    occurred_at                TIMESTAMPTZ NOT NULL,
    CONSTRAINT chk_enterprise_release_freeze_transition CHECK (
        transition IN ('FROZEN', 'CLEARED')
    ),
    CONSTRAINT chk_enterprise_release_freeze_event_values CHECK (
        burn_rate >= 0 AND threshold >= 0
        AND evaluation_window_minutes BETWEEN 5 AND 1440
    )
);

CREATE INDEX idx_enterprise_release_freeze_events_tenant_time
ON enterprise_release_freeze_events(tenant_id, occurred_at DESC);

COMMENT ON TABLE enterprise_release_freeze_states IS
'Authoritative tenant release gate derived from bounded Error Budget burn-rate windows';

COMMENT ON TABLE enterprise_release_freeze_events IS
'Immutable automatic Runtime release freeze and clear transitions';
