-- Persist Node-authoritative screenshot redaction evidence without breaking N-1 Nodes.

ALTER TABLE session_evidence
    ADD COLUMN redaction_state TEXT NOT NULL DEFAULT 'LEGACY_UNVERIFIED',
    ADD COLUMN redacted_region_count INTEGER NOT NULL DEFAULT 0;

ALTER TABLE session_evidence
    ADD CONSTRAINT chk_session_evidence_redaction_state CHECK (
        redaction_state IN (
            'LEGACY_UNVERIFIED',
            'MASKED',
            'NOT_REQUIRED',
            'FAILED_CLOSED'
        )
    ) NOT VALID,
    ADD CONSTRAINT chk_session_evidence_redaction_result CHECK (
        (
            redaction_state = 'LEGACY_UNVERIFIED'
            AND redacted_region_count = 0
        )
        OR
        (
            result = 'COMMITTED'
            AND redaction_state = 'MASKED'
            AND redacted_region_count > 0
        )
        OR
        (
            result = 'COMMITTED'
            AND redaction_state = 'NOT_REQUIRED'
            AND redacted_region_count = 0
        )
        OR
        (
            result = 'FAILED'
            AND redaction_state = 'FAILED_CLOSED'
            AND redacted_region_count = 0
        )
    ) NOT VALID,
    ADD CONSTRAINT chk_session_evidence_redacted_region_count CHECK (
        redacted_region_count BETWEEN 0 AND 10000
    ) NOT VALID;

ALTER TABLE session_evidence
    VALIDATE CONSTRAINT chk_session_evidence_redaction_state;

ALTER TABLE session_evidence
    VALIDATE CONSTRAINT chk_session_evidence_redaction_result;

ALTER TABLE session_evidence
    VALIDATE CONSTRAINT chk_session_evidence_redacted_region_count;

COMMENT ON COLUMN session_evidence.redaction_state IS
    'Browser Node screenshot masking result; LEGACY_UNVERIFIED is reserved for N-1 events';

COMMENT ON COLUMN session_evidence.redacted_region_count IS
    'Number of viewport regions covered before Page.captureScreenshot';
