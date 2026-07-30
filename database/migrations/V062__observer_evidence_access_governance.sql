-- Administrator-requested Observer screenshots and purpose-bound, one-time evidence access.

ALTER TABLE session_evidence
    ADD CONSTRAINT chk_session_evidence_kind_v2 CHECK (
        evidence_kind IN (
            'AGENT_ACTION_SUCCESS',
            'AGENT_ACTION_FAILURE',
            'AGENT_NAVIGATION_SUCCESS',
            'AGENT_NAVIGATION_FAILURE',
            'OBSERVER_MANUAL'
        )
    ) NOT VALID;

ALTER TABLE session_evidence
    VALIDATE CONSTRAINT chk_session_evidence_kind_v2;

ALTER TABLE session_evidence
    DROP CONSTRAINT chk_session_evidence_kind;

ALTER TABLE session_evidence
    RENAME CONSTRAINT chk_session_evidence_kind_v2 TO chk_session_evidence_kind;

CREATE TABLE session_evidence_capture_requests (
    capture_id         TEXT PRIMARY KEY,
    tenant_id          TEXT NOT NULL,
    session_id         TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
    actor_id           TEXT NOT NULL,
    purpose            TEXT NOT NULL,
    idempotency_key    TEXT NOT NULL,
    command_id         TEXT NOT NULL UNIQUE,
    request_id         TEXT,
    state              TEXT NOT NULL,
    evidence_id        TEXT REFERENCES session_evidence(evidence_id),
    error_code         TEXT,
    created_at         TIMESTAMPTZ NOT NULL,
    completed_at       TIMESTAMPTZ,
    CONSTRAINT uq_session_evidence_capture_idempotency
        UNIQUE (tenant_id, actor_id, idempotency_key),
    CONSTRAINT chk_session_evidence_capture_id
        CHECK (capture_id ~ '^cap_[a-zA-Z0-9]{16,}$'),
    CONSTRAINT chk_session_evidence_capture_purpose CHECK (
        purpose IN (
            'INCIDENT_RESPONSE',
            'CHANGE_VALIDATION',
            'SUPPORT_DIAGNOSTICS',
            'COMPLIANCE_AUDIT'
        )
    ),
    CONSTRAINT chk_session_evidence_capture_state CHECK (
        state IN ('EXECUTING', 'COMMITTED', 'FAILED')
    ),
    CONSTRAINT chk_session_evidence_capture_result CHECK (
        (
            state = 'EXECUTING'
            AND evidence_id IS NULL
            AND error_code IS NULL
            AND completed_at IS NULL
        )
        OR
        (
            state = 'COMMITTED'
            AND evidence_id IS NOT NULL
            AND error_code IS NULL
            AND completed_at IS NOT NULL
        )
        OR
        (
            state = 'FAILED'
            AND error_code IS NOT NULL
            AND completed_at IS NOT NULL
        )
    )
);

CREATE INDEX idx_session_evidence_capture_session_time
    ON session_evidence_capture_requests(tenant_id, session_id, created_at DESC);

CREATE TABLE session_evidence_access_grants (
    grant_id            TEXT PRIMARY KEY,
    tenant_id           TEXT NOT NULL,
    session_id          TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
    evidence_id         TEXT NOT NULL REFERENCES session_evidence(evidence_id) ON DELETE CASCADE,
    actor_id            TEXT NOT NULL,
    purpose             TEXT NOT NULL,
    idempotency_key     TEXT NOT NULL,
    request_id          TEXT,
    state               TEXT NOT NULL,
    expires_at          TIMESTAMPTZ NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL,
    redeem_started_at   TIMESTAMPTZ,
    redeemed_at         TIMESTAMPTZ,
    signer_node_id      TEXT,
    error_code          TEXT,
    CONSTRAINT uq_session_evidence_access_idempotency
        UNIQUE (tenant_id, actor_id, idempotency_key),
    CONSTRAINT chk_session_evidence_access_grant_id
        CHECK (grant_id ~ '^egr_[a-zA-Z0-9]{16,}$'),
    CONSTRAINT chk_session_evidence_access_purpose CHECK (
        purpose IN (
            'INCIDENT_RESPONSE',
            'CHANGE_VALIDATION',
            'SUPPORT_DIAGNOSTICS',
            'COMPLIANCE_AUDIT'
        )
    ),
    CONSTRAINT chk_session_evidence_access_state CHECK (
        state IN ('ISSUED', 'REDEEMING', 'REDEEMED', 'FAILED')
    ),
    CONSTRAINT chk_session_evidence_access_expiry CHECK (
        expires_at > created_at
        AND expires_at <= created_at + INTERVAL '5 minutes'
    ),
    CONSTRAINT chk_session_evidence_access_result CHECK (
        (
            state = 'ISSUED'
            AND redeem_started_at IS NULL
            AND redeemed_at IS NULL
            AND signer_node_id IS NULL
            AND error_code IS NULL
        )
        OR
        (
            state = 'REDEEMING'
            AND redeem_started_at IS NOT NULL
            AND redeemed_at IS NULL
            AND signer_node_id IS NULL
            AND error_code IS NULL
        )
        OR
        (
            state = 'REDEEMED'
            AND redeem_started_at IS NOT NULL
            AND redeemed_at IS NOT NULL
            AND signer_node_id IS NOT NULL
            AND error_code IS NULL
        )
        OR
        (
            state = 'FAILED'
            AND redeem_started_at IS NOT NULL
            AND redeemed_at IS NOT NULL
            AND signer_node_id IS NULL
            AND error_code IS NOT NULL
        )
    )
);

CREATE INDEX idx_session_evidence_access_session_time
    ON session_evidence_access_grants(tenant_id, session_id, created_at DESC);

CREATE OR REPLACE FUNCTION enforce_session_evidence_scope()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM sessions session
        WHERE session.id = NEW.session_id
          AND session.tenant_id = NEW.tenant_id
    ) THEN
        RAISE EXCEPTION 'session scope does not match tenant'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.evidence_id IS NOT NULL AND NOT EXISTS (
        SELECT 1
        FROM session_evidence evidence
        WHERE evidence.evidence_id = NEW.evidence_id
          AND evidence.tenant_id = NEW.tenant_id
          AND evidence.session_id = NEW.session_id
    ) THEN
        RAISE EXCEPTION 'evidence scope does not match tenant and session'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_session_evidence_capture_scope
    BEFORE INSERT OR UPDATE OF evidence_id, tenant_id, session_id
    ON session_evidence_capture_requests
    FOR EACH ROW
    EXECUTE FUNCTION enforce_session_evidence_scope();

CREATE TRIGGER trg_session_evidence_access_scope
    BEFORE INSERT OR UPDATE OF evidence_id, tenant_id, session_id
    ON session_evidence_access_grants
    FOR EACH ROW
    EXECUTE FUNCTION enforce_session_evidence_scope();

COMMENT ON TABLE session_evidence_capture_requests IS
    'Durable, idempotent Observer screenshot request state; pixels remain on the Browser Node and Object Storage data plane';

COMMENT ON TABLE session_evidence_access_grants IS
    'Purpose-bound one-time evidence access grants; signed URLs and Object Storage credentials are never persisted';
