CREATE INDEX idx_recovery_gameday_job_events_timeline
ON recovery_gameday_job_events(gameday_id, occurred_at DESC, event_id DESC);

CREATE TABLE recovery_gameday_report_exports (
    export_id                  TEXT PRIMARY KEY,
    gameday_id                 TEXT NOT NULL REFERENCES enterprise_recovery_gamedays(gameday_id),
    report_format              TEXT NOT NULL DEFAULT 'JSON',
    event_count                INTEGER NOT NULL,
    report                     JSONB NOT NULL,
    report_hash                TEXT NOT NULL,
    signature_algorithm        TEXT NOT NULL,
    signing_key_id             TEXT NOT NULL,
    signature                  TEXT NOT NULL,
    generated_by               TEXT NOT NULL,
    generated_at               TIMESTAMPTZ NOT NULL,
    CONSTRAINT chk_recovery_gameday_export_format CHECK (report_format = 'JSON'),
    CONSTRAINT chk_recovery_gameday_export_event_count CHECK (event_count >= 0),
    CONSTRAINT chk_recovery_gameday_export_report CHECK (jsonb_typeof(report) = 'object'),
    CONSTRAINT chk_recovery_gameday_export_hash CHECK (report_hash ~ '^[a-f0-9]{64}$'),
    CONSTRAINT chk_recovery_gameday_export_signature CHECK (
        signature_algorithm = 'HMAC-SHA256'
        AND signature ~ '^[a-f0-9]{64}$'
    )
);

CREATE INDEX idx_recovery_gameday_report_exports_run
ON recovery_gameday_report_exports(gameday_id, generated_at DESC, export_id DESC);

CREATE TABLE recovery_gameday_remediation_tickets (
    ticket_id                  TEXT PRIMARY KEY,
    gameday_id                 TEXT NOT NULL UNIQUE REFERENCES enterprise_recovery_gamedays(gameday_id),
    scenario                   TEXT NOT NULL,
    environment                TEXT NOT NULL,
    severity                   TEXT NOT NULL,
    state                      TEXT NOT NULL,
    reason_code                TEXT NOT NULL,
    summary                    TEXT NOT NULL,
    owner_id                   TEXT,
    resolution                 TEXT,
    created_by                 TEXT NOT NULL,
    created_at                 TIMESTAMPTZ NOT NULL,
    updated_by                 TEXT NOT NULL,
    updated_at                 TIMESTAMPTZ NOT NULL,
    resolved_at                TIMESTAMPTZ,
    CONSTRAINT chk_recovery_gameday_ticket_environment CHECK (
        environment IN ('TEST', 'STAGING', 'PRODUCTION')
    ),
    CONSTRAINT chk_recovery_gameday_ticket_severity CHECK (severity IN ('P1', 'P2', 'P3')),
    CONSTRAINT chk_recovery_gameday_ticket_state CHECK (
        state IN ('OPEN', 'ACKNOWLEDGED', 'RESOLVED')
    ),
    CONSTRAINT chk_recovery_gameday_ticket_resolution CHECK (
        (state = 'RESOLVED' AND resolution IS NOT NULL AND resolved_at IS NOT NULL)
        OR (state <> 'RESOLVED' AND resolved_at IS NULL)
    )
);

CREATE INDEX idx_recovery_gameday_remediation_open
ON recovery_gameday_remediation_tickets(state, severity, created_at DESC, ticket_id DESC)
WHERE state <> 'RESOLVED';

INSERT INTO recovery_gameday_remediation_tickets(
    ticket_id, gameday_id, scenario, environment, severity, state,
    reason_code, summary, created_by, created_at, updated_by, updated_at
)
SELECT
    'grt_' || substr(md5(g.gameday_id || ':V078'), 1, 20),
    g.gameday_id,
    g.scenario,
    g.environment,
    CASE WHEN g.environment = 'PRODUCTION' THEN 'P1'
         WHEN g.environment = 'STAGING' THEN 'P2'
         ELSE 'P3' END,
    'OPEN',
    COALESCE(g.failure_code, 'RECOVERY_OBJECTIVES_MISSED'),
    'Recovery GameDay requires remediation: ' || g.scenario,
    'migration-v078',
    COALESCE(g.completed_at, g.started_at),
    'migration-v078',
    COALESCE(g.completed_at, g.started_at)
FROM enterprise_recovery_gamedays g
WHERE g.state IN ('FAILED', 'ABORTED')
ON CONFLICT (gameday_id) DO NOTHING;

COMMENT ON TABLE recovery_gameday_report_exports IS
'Immutable signed JSON reports containing one Recovery GameDay run, job, timeline and remediation state';

COMMENT ON TABLE recovery_gameday_remediation_tickets IS
'Durable remediation ownership and resolution workflow automatically opened for unsuccessful Recovery GameDays';
