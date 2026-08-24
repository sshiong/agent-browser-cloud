-- State-fenced, tenant-scoped Agent Browser screenshot requests.
--
-- Pixels remain in the existing redacted Evidence/Object Storage data plane. PostgreSQL stores
-- only bounded request/result metadata plus the preallocated one-time access grant identity.
-- The migration is expand-only; N-1 applications ignore the new table and old Nodes safely
-- reject the additive CaptureAgentScreenshot command.

ALTER TABLE session_evidence
    ADD CONSTRAINT chk_session_evidence_kind_v3 CHECK (
        evidence_kind IN (
            'AGENT_ACTION_SUCCESS',
            'AGENT_ACTION_FAILURE',
            'AGENT_NAVIGATION_SUCCESS',
            'AGENT_NAVIGATION_FAILURE',
            'OBSERVER_MANUAL',
            'AGENT_SCREENSHOT'
        )
    ) NOT VALID;

ALTER TABLE session_evidence
    VALIDATE CONSTRAINT chk_session_evidence_kind_v3;

ALTER TABLE session_evidence
    DROP CONSTRAINT chk_session_evidence_kind;

ALTER TABLE session_evidence
    RENAME CONSTRAINT chk_session_evidence_kind_v3 TO chk_session_evidence_kind;

ALTER TABLE session_evidence_access_grants
    ADD CONSTRAINT chk_session_evidence_access_purpose_v2 CHECK (
        purpose IN (
            'INCIDENT_RESPONSE',
            'CHANGE_VALIDATION',
            'SUPPORT_DIAGNOSTICS',
            'COMPLIANCE_AUDIT',
            'AGENT_PERCEPTION'
        )
    ) NOT VALID;

ALTER TABLE session_evidence_access_grants
    VALIDATE CONSTRAINT chk_session_evidence_access_purpose_v2;

ALTER TABLE session_evidence_access_grants
    DROP CONSTRAINT chk_session_evidence_access_purpose;

ALTER TABLE session_evidence_access_grants
    RENAME CONSTRAINT chk_session_evidence_access_purpose_v2
        TO chk_session_evidence_access_purpose;

CREATE TABLE agent_browser_screenshot_requests (
    screenshot_id              TEXT PRIMARY KEY,
    tenant_id                  TEXT NOT NULL,
    session_id                 TEXT NOT NULL,
    actor_id                   TEXT NOT NULL,
    idempotency_key            TEXT NOT NULL,
    request_hash               TEXT NOT NULL,
    request_id                 TEXT NOT NULL,
    command_id                 TEXT NOT NULL UNIQUE,
    access_grant_id            TEXT NOT NULL UNIQUE,
    planned_evidence_id        TEXT NOT NULL UNIQUE,
    node_id                    TEXT NOT NULL,
    coordinator_term           BIGINT NOT NULL,
    context_epoch              BIGINT NOT NULL,
    capture_mode               TEXT NOT NULL,
    expected_state_version     BIGINT NOT NULL,
    expected_target_revision   BIGINT NOT NULL,
    expected_state_hash        TEXT NOT NULL,
    expected_active_tab_id     TEXT NOT NULL,
    element_id                 TEXT,
    requested_region_x         DOUBLE PRECISION,
    requested_region_y         DOUBLE PRECISION,
    requested_region_width     DOUBLE PRECISION,
    requested_region_height    DOUBLE PRECISION,
    state                      TEXT NOT NULL,
    evidence_id                TEXT REFERENCES session_evidence(evidence_id),
    error_code                 TEXT,
    captured_state_version     BIGINT,
    captured_target_revision   BIGINT,
    captured_state_hash        TEXT,
    captured_active_tab_id     TEXT,
    viewport_width             DOUBLE PRECISION,
    viewport_height            DOUBLE PRECISION,
    device_scale_factor        DOUBLE PRECISION,
    captured_region_x          DOUBLE PRECISION,
    captured_region_y          DOUBLE PRECISION,
    captured_region_width      DOUBLE PRECISION,
    captured_region_height     DOUBLE PRECISION,
    coordinate_space           TEXT,
    created_at                 TIMESTAMPTZ NOT NULL,
    updated_at                 TIMESTAMPTZ NOT NULL,
    completed_at               TIMESTAMPTZ,
    version                    BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_agent_browser_screenshot_idempotency
        UNIQUE (tenant_id, actor_id, idempotency_key),
    CONSTRAINT fk_agent_browser_screenshot_session
        FOREIGN KEY (session_id, tenant_id) REFERENCES sessions(id, tenant_id) ON DELETE CASCADE,
    CONSTRAINT chk_agent_browser_screenshot_id
        CHECK (screenshot_id ~ '^shot_[A-Za-z0-9]{20}$'),
    CONSTRAINT chk_agent_browser_screenshot_command_id
        CHECK (command_id ~ '^cmd_[A-Za-z0-9]{20}$'),
    CONSTRAINT chk_agent_browser_screenshot_grant_id
        CHECK (access_grant_id ~ '^egr_[A-Za-z0-9]{20}$'),
    CONSTRAINT chk_agent_browser_screenshot_planned_evidence_id
        CHECK (planned_evidence_id ~ '^evd_[0-9a-f]{32}$'),
    CONSTRAINT chk_agent_browser_screenshot_request_hash
        CHECK (request_hash ~ '^[0-9a-f]{64}$' AND expected_state_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT chk_agent_browser_screenshot_fences
        CHECK (coordinator_term >= 0 AND context_epoch >= 1
            AND expected_state_version >= 1 AND expected_target_revision >= 1),
    CONSTRAINT chk_agent_browser_screenshot_mode
        CHECK (capture_mode IN ('VIEWPORT', 'FULL_PAGE', 'ELEMENT', 'REGION', 'CHALLENGE_REGION')),
    CONSTRAINT chk_agent_browser_screenshot_input
        CHECK (
            (capture_mode IN ('VIEWPORT', 'FULL_PAGE')
                AND element_id IS NULL
                AND requested_region_x IS NULL AND requested_region_y IS NULL
                AND requested_region_width IS NULL AND requested_region_height IS NULL)
            OR
            (capture_mode = 'ELEMENT'
                AND element_id IS NOT NULL AND char_length(element_id) BETWEEN 1 AND 256
                AND element_id !~ '[[:cntrl:]]'
                AND requested_region_x IS NULL AND requested_region_y IS NULL
                AND requested_region_width IS NULL AND requested_region_height IS NULL)
            OR
            (capture_mode IN ('REGION', 'CHALLENGE_REGION')
                AND element_id IS NULL
                AND requested_region_x BETWEEN 0 AND 7680
                AND requested_region_y BETWEEN 0 AND 4320
                AND requested_region_width BETWEEN 1 AND 7680
                AND requested_region_height BETWEEN 1 AND 4320)
        ),
    CONSTRAINT chk_agent_browser_screenshot_state
        CHECK (state IN ('EXECUTING', 'COMMITTED', 'FAILED')),
    CONSTRAINT chk_agent_browser_screenshot_output_bounds
        CHECK (
            (viewport_width IS NULL OR viewport_width BETWEEN 1 AND 7680)
            AND (viewport_height IS NULL OR viewport_height BETWEEN 1 AND 4320)
            AND (device_scale_factor IS NULL OR device_scale_factor BETWEEN 0.25 AND 8)
            AND (captured_region_x IS NULL OR captured_region_x BETWEEN 0 AND 16384)
            AND (captured_region_y IS NULL OR captured_region_y BETWEEN 0 AND 16384)
            AND (captured_region_width IS NULL OR captured_region_width BETWEEN 1 AND 16384)
            AND (captured_region_height IS NULL OR captured_region_height BETWEEN 1 AND 16384)
            AND (coordinate_space IS NULL OR coordinate_space IN ('VIEWPORT', 'DOCUMENT'))
        ),
    CONSTRAINT chk_agent_browser_screenshot_result
        CHECK (
            (state = 'EXECUTING'
                AND evidence_id IS NULL AND error_code IS NULL
                AND captured_state_version IS NULL AND captured_target_revision IS NULL
                AND captured_state_hash IS NULL AND captured_active_tab_id IS NULL
                AND viewport_width IS NULL AND viewport_height IS NULL
                AND device_scale_factor IS NULL
                AND captured_region_x IS NULL AND captured_region_y IS NULL
                AND captured_region_width IS NULL AND captured_region_height IS NULL
                AND coordinate_space IS NULL AND completed_at IS NULL)
            OR
            (state = 'COMMITTED'
                AND evidence_id IS NOT NULL AND error_code IS NULL
                AND captured_state_version = expected_state_version
                AND captured_target_revision = expected_target_revision
                AND captured_state_hash = expected_state_hash
                AND captured_active_tab_id = expected_active_tab_id
                AND viewport_width IS NOT NULL AND viewport_height IS NOT NULL
                AND device_scale_factor IS NOT NULL
                AND captured_region_x IS NOT NULL AND captured_region_y IS NOT NULL
                AND captured_region_width IS NOT NULL AND captured_region_height IS NOT NULL
                AND coordinate_space IS NOT NULL AND completed_at IS NOT NULL)
            OR
            (state = 'FAILED'
                AND evidence_id IS NULL AND error_code IS NOT NULL
                AND captured_state_version IS NULL AND captured_target_revision IS NULL
                AND captured_state_hash IS NULL AND captured_active_tab_id IS NULL
                AND viewport_width IS NULL AND viewport_height IS NULL
                AND device_scale_factor IS NULL
                AND captured_region_x IS NULL AND captured_region_y IS NULL
                AND captured_region_width IS NULL AND captured_region_height IS NULL
                AND coordinate_space IS NULL AND completed_at IS NOT NULL)
        )
);

CREATE INDEX idx_agent_browser_screenshot_session
    ON agent_browser_screenshot_requests (tenant_id, session_id, created_at DESC);

CREATE INDEX idx_agent_browser_screenshot_executing
    ON agent_browser_screenshot_requests (session_id, created_at)
    WHERE state = 'EXECUTING';

CREATE TRIGGER trg_agent_browser_screenshot_scope
    BEFORE INSERT OR UPDATE OF evidence_id, tenant_id, session_id
    ON agent_browser_screenshot_requests
    FOR EACH ROW
    EXECUTE FUNCTION enforce_session_evidence_scope();

COMMENT ON TABLE agent_browser_screenshot_requests IS
    'State-fenced Agent Browser screenshot metadata; pixels, Object Storage paths and signed URLs are never persisted';
