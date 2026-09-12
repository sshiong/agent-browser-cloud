-- Local OCR/PII evidence for Challenge screenshots. OCR plaintext and pixels remain outside
-- PostgreSQL; only the fixed scanner version, SHA-256, redaction counts and zero remaining
-- sensitive-pattern assertion persist.

ALTER TABLE session_evidence
  ADD CONSTRAINT chk_session_evidence_kind_v4 CHECK (
    evidence_kind IN (
      'AGENT_ACTION_SUCCESS',
      'AGENT_ACTION_FAILURE',
      'AGENT_NAVIGATION_SUCCESS',
      'AGENT_NAVIGATION_FAILURE',
      'OBSERVER_MANUAL',
      'AGENT_SCREENSHOT',
      'CHALLENGE_SCREENSHOT'
    )
  ) NOT VALID;

ALTER TABLE session_evidence
  VALIDATE CONSTRAINT chk_session_evidence_kind_v4;

ALTER TABLE session_evidence
  DROP CONSTRAINT chk_session_evidence_kind;

ALTER TABLE session_evidence
  RENAME CONSTRAINT chk_session_evidence_kind_v4 TO chk_session_evidence_kind;

ALTER TABLE challenge_visual_jobs
  ADD COLUMN privacy_scan_version TEXT,
  ADD COLUMN ocr_text_hash TEXT,
  ADD COLUMN detected_sensitive_pattern_count INTEGER,
  ADD COLUMN pii_redacted_region_count INTEGER,
  ADD COLUMN remaining_sensitive_pattern_count INTEGER,
  ADD COLUMN capture_viewport_width DOUBLE PRECISION,
  ADD COLUMN capture_viewport_height DOUBLE PRECISION,
  ADD COLUMN capture_region_x DOUBLE PRECISION,
  ADD COLUMN capture_region_y DOUBLE PRECISION,
  ADD COLUMN capture_region_width DOUBLE PRECISION,
  ADD COLUMN capture_region_height DOUBLE PRECISION,
  ADD COLUMN captured_state_version BIGINT,
  ADD COLUMN captured_target_revision BIGINT,
  ADD COLUMN captured_state_hash TEXT,
  ADD COLUMN captured_active_tab_id TEXT;

ALTER TABLE challenge_visual_jobs
  ADD CONSTRAINT chk_challenge_visual_job_privacy_attestation
    CHECK (
      (privacy_scan_version IS NULL
        AND ocr_text_hash IS NULL
        AND detected_sensitive_pattern_count IS NULL
        AND pii_redacted_region_count IS NULL
        AND remaining_sensitive_pattern_count IS NULL)
      OR
      (privacy_scan_version = 'tesseract-pii-v1'
        AND ocr_text_hash ~ '^[a-f0-9]{64}$'
        AND detected_sensitive_pattern_count BETWEEN 0 AND 1000
        AND pii_redacted_region_count BETWEEN 0 AND 1000
        AND remaining_sensitive_pattern_count = 0
        AND ((detected_sensitive_pattern_count = 0) = (pii_redacted_region_count = 0)))
    ) NOT VALID;

ALTER TABLE challenge_visual_jobs
  VALIDATE CONSTRAINT chk_challenge_visual_job_privacy_attestation;

ALTER TABLE challenge_visual_jobs
  ADD CONSTRAINT chk_challenge_visual_job_capture_scope
    CHECK (
      (capture_viewport_width IS NULL AND capture_viewport_height IS NULL
        AND capture_region_x IS NULL AND capture_region_y IS NULL
        AND capture_region_width IS NULL AND capture_region_height IS NULL
        AND captured_state_version IS NULL AND captured_target_revision IS NULL
        AND captured_state_hash IS NULL AND captured_active_tab_id IS NULL)
      OR
      (capture_viewport_width BETWEEN 1 AND 7680
        AND capture_viewport_height BETWEEN 1 AND 4320
        AND capture_region_x BETWEEN 0 AND 16384
        AND capture_region_y BETWEEN 0 AND 16384
        AND capture_region_width BETWEEN 1 AND 2048
        AND capture_region_height BETWEEN 1 AND 2048
        AND captured_state_version > 0 AND captured_target_revision > 0
        AND captured_state_hash ~ '^[a-f0-9]{64}$'
        AND length(captured_active_tab_id) BETWEEN 1 AND 128
        AND capture_region_x + capture_region_width <= capture_viewport_width + 0.001
        AND capture_region_y + capture_region_height <= capture_viewport_height + 0.001)
    ) NOT VALID;

ALTER TABLE challenge_visual_jobs
  VALIDATE CONSTRAINT chk_challenge_visual_job_capture_scope;

COMMENT ON COLUMN challenge_visual_jobs.ocr_text_hash IS
  'SHA-256 of normalized local OCR output; OCR plaintext is never persisted or sent in APIs.';
