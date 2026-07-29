-- Real Agent screenshot evidence data plane and Node-acknowledged success sampling actuator.

ALTER TABLE browser_placements
    ADD COLUMN success_screenshot_sample_percent INTEGER NOT NULL DEFAULT 100;

ALTER TABLE browser_placements
    ADD CONSTRAINT chk_browser_placements_success_screenshot_sample_percent
    CHECK (success_screenshot_sample_percent BETWEEN 1 AND 100) NOT VALID;

ALTER TABLE browser_placements
    VALIDATE CONSTRAINT chk_browser_placements_success_screenshot_sample_percent;

CREATE TABLE session_evidence (
    evidence_id        TEXT PRIMARY KEY,
    event_id           TEXT NOT NULL UNIQUE,
    tenant_id          TEXT NOT NULL,
    session_id         TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
    evidence_kind      TEXT NOT NULL,
    task_id            TEXT NOT NULL,
    step_id            TEXT NOT NULL,
    command_id         TEXT NOT NULL,
    mandatory          BOOLEAN NOT NULL,
    result             TEXT NOT NULL,
    content_sha256     TEXT,
    content_bytes      BIGINT NOT NULL DEFAULT 0,
    object_key         TEXT,
    error_code         TEXT,
    captured_at        TIMESTAMPTZ NOT NULL,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_session_evidence_kind CHECK (
        evidence_kind IN (
            'AGENT_ACTION_SUCCESS',
            'AGENT_ACTION_FAILURE',
            'AGENT_NAVIGATION_SUCCESS',
            'AGENT_NAVIGATION_FAILURE'
        )
    ),
    CONSTRAINT chk_session_evidence_result CHECK (result IN ('COMMITTED', 'FAILED')),
    CONSTRAINT chk_session_evidence_content CHECK (
        (
            result = 'COMMITTED'
            AND content_sha256 ~ '^[0-9a-f]{64}$'
            AND content_bytes > 0
            AND object_key IS NOT NULL
            AND error_code IS NULL
        )
        OR
        (
            result = 'FAILED'
            AND content_sha256 IS NULL
            AND content_bytes = 0
            AND object_key IS NULL
            AND error_code IS NOT NULL
        )
    )
);

CREATE INDEX idx_session_evidence_tenant_session_time
    ON session_evidence(tenant_id, session_id, captured_at DESC, evidence_id DESC);

COMMENT ON COLUMN browser_placements.success_screenshot_sample_percent IS
    'Node-acknowledged sampling percentage for successful Agent screenshots; failures remain mandatory';

COMMENT ON TABLE session_evidence IS
    'Tenant-scoped metadata index for real CDP screenshots committed by Storage Helper; raw pixels remain in Object Storage';
