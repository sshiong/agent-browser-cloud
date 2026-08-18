-- Bounded visual Challenge automation. Existing HumanAssist remains available as the manual
-- fallback; this migration only adds opt-out Session policy and durable run/job projections.

ALTER TABLE sessions
  ADD COLUMN challenge_automation_enabled BOOLEAN NOT NULL DEFAULT TRUE,
  ADD COLUMN challenge_automation_max_attempts SMALLINT NOT NULL DEFAULT 3,
  ADD COLUMN challenge_automation_min_confidence DOUBLE PRECISION NOT NULL DEFAULT 0.85,
  ADD COLUMN challenge_automation_allow_multi_click BOOLEAN NOT NULL DEFAULT TRUE,
  ADD COLUMN challenge_automation_allow_slide BOOLEAN NOT NULL DEFAULT TRUE;

ALTER TABLE sessions
  ADD CONSTRAINT chk_sessions_challenge_automation_attempts
    CHECK (challenge_automation_max_attempts BETWEEN 0 AND 10) NOT VALID,
  ADD CONSTRAINT chk_sessions_challenge_automation_confidence
    CHECK (challenge_automation_min_confidence BETWEEN 0.5 AND 1.0) NOT VALID;
ALTER TABLE sessions VALIDATE CONSTRAINT chk_sessions_challenge_automation_attempts;
ALTER TABLE sessions VALIDATE CONSTRAINT chk_sessions_challenge_automation_confidence;

CREATE TABLE challenge_automation_runs (
    run_id                       TEXT PRIMARY KEY,
    tenant_id                    TEXT NOT NULL,
    session_id                   TEXT NOT NULL,
    task_id                      TEXT NOT NULL,
    current_challenge_event_id   TEXT NOT NULL REFERENCES challenge_events(challenge_event_id),
    state                        TEXT NOT NULL,
    attempt_count                SMALLINT NOT NULL DEFAULT 0,
    maximum_attempts             SMALLINT NOT NULL,
    minimum_confidence           DOUBLE PRECISION NOT NULL,
    allow_multi_click            BOOLEAN NOT NULL,
    allow_slide                  BOOLEAN NOT NULL,
    last_action                  TEXT,
    last_error_code              TEXT,
    created_at                   TIMESTAMPTZ NOT NULL,
    updated_at                   TIMESTAMPTZ NOT NULL,
    completed_at                 TIMESTAMPTZ,
    version                      BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_challenge_automation_run_session
      FOREIGN KEY (session_id, tenant_id) REFERENCES sessions(id, tenant_id) ON DELETE CASCADE,
    CONSTRAINT fk_challenge_automation_run_task
      FOREIGN KEY (task_id) REFERENCES agent_tasks(task_id) ON DELETE CASCADE,
    CONSTRAINT chk_challenge_automation_run_id
      CHECK (run_id ~ '^car_[A-Za-z0-9]{20}$'),
    CONSTRAINT chk_challenge_automation_run_state
      CHECK (state IN ('CAPTURING', 'ANALYZING', 'EXECUTING', 'COMPLETED', 'EXHAUSTED', 'ESCALATED', 'FAILED')),
    CONSTRAINT chk_challenge_automation_run_attempts
      CHECK (maximum_attempts BETWEEN 1 AND 10 AND attempt_count BETWEEN 0 AND maximum_attempts),
    CONSTRAINT chk_challenge_automation_run_confidence
      CHECK (minimum_confidence BETWEEN 0.5 AND 1.0),
    CONSTRAINT chk_challenge_automation_run_error
      CHECK (last_error_code IS NULL OR last_error_code ~ '^[A-Z][A-Z0-9_]{2,127}$'),
    CONSTRAINT chk_challenge_automation_run_completion
      CHECK ((state IN ('COMPLETED', 'EXHAUSTED', 'ESCALATED', 'FAILED')) = (completed_at IS NOT NULL))
);

CREATE UNIQUE INDEX uq_challenge_automation_run_active_task
  ON challenge_automation_runs(tenant_id, task_id)
  WHERE state IN ('CAPTURING', 'ANALYZING', 'EXECUTING');
CREATE INDEX idx_challenge_automation_run_session
  ON challenge_automation_runs(tenant_id, session_id, created_at DESC);

CREATE TABLE challenge_visual_jobs (
    job_id               TEXT PRIMARY KEY,
    run_id               TEXT NOT NULL REFERENCES challenge_automation_runs(run_id) ON DELETE CASCADE,
    tenant_id            TEXT NOT NULL,
    session_id           TEXT NOT NULL,
    challenge_event_id   TEXT NOT NULL REFERENCES challenge_events(challenge_event_id),
    attempt_number       SMALLINT NOT NULL,
    capture_id           TEXT NOT NULL REFERENCES session_evidence_capture_requests(capture_id),
    evidence_id          TEXT REFERENCES session_evidence(evidence_id),
    state                TEXT NOT NULL,
    worker_id            TEXT,
    claim_token_hash     TEXT,
    claim_epoch          BIGINT NOT NULL DEFAULT 0,
    lease_expires_at     TIMESTAMPTZ,
    available_at         TIMESTAMPTZ NOT NULL,
    operation_id         TEXT REFERENCES exclusive_operations(operation_id),
    decision             TEXT,
    actions              JSONB,
    confidence           DOUBLE PRECISION,
    model_deployment_id  TEXT,
    model_revision       TEXT,
    provider_request_id  TEXT,
    input_tokens         INTEGER,
    output_tokens        INTEGER,
    latency_ms           INTEGER,
    output_hash          TEXT,
    failure_code         TEXT,
    created_at           TIMESTAMPTZ NOT NULL,
    started_at           TIMESTAMPTZ,
    completed_at         TIMESTAMPTZ,
    updated_at           TIMESTAMPTZ NOT NULL,
    version              BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_challenge_visual_job_session
      FOREIGN KEY (session_id, tenant_id) REFERENCES sessions(id, tenant_id) ON DELETE CASCADE,
    CONSTRAINT chk_challenge_visual_job_id CHECK (job_id ~ '^cvj_[A-Za-z0-9]{20}$'),
    CONSTRAINT chk_challenge_visual_job_attempt CHECK (attempt_number BETWEEN 1 AND 10),
    CONSTRAINT chk_challenge_visual_job_state
      CHECK (state IN ('CAPTURING', 'READY', 'CLAIMED', 'RUNNING', 'EXECUTING', 'COMPLETED', 'FAILED', 'ESCALATED')),
    CONSTRAINT chk_challenge_visual_job_claim
      CHECK (claim_token_hash IS NULL OR claim_token_hash ~ '^[a-f0-9]{64}$'),
    CONSTRAINT chk_challenge_visual_job_decision
      CHECK (decision IS NULL OR decision IN ('ACT', 'ESCALATE')),
    CONSTRAINT chk_challenge_visual_job_actions
      CHECK (actions IS NULL OR (jsonb_typeof(actions) = 'array' AND octet_length(actions::TEXT) <= 8192)),
    CONSTRAINT chk_challenge_visual_job_confidence
      CHECK (confidence IS NULL OR confidence BETWEEN 0 AND 1),
    CONSTRAINT chk_challenge_visual_job_hash
      CHECK (output_hash IS NULL OR output_hash ~ '^[a-f0-9]{64}$'),
    CONSTRAINT chk_challenge_visual_job_failure
      CHECK (failure_code IS NULL OR failure_code ~ '^[A-Z][A-Z0-9_]{2,127}$'),
    CONSTRAINT uq_challenge_visual_job_attempt UNIQUE (run_id, attempt_number)
);

CREATE INDEX idx_challenge_visual_jobs_claim
  ON challenge_visual_jobs(available_at, created_at, job_id)
  WHERE state = 'READY';
CREATE INDEX idx_challenge_visual_jobs_capture
  ON challenge_visual_jobs(capture_id)
  WHERE state = 'CAPTURING';
CREATE UNIQUE INDEX uq_challenge_visual_jobs_operation
  ON challenge_visual_jobs(operation_id) WHERE operation_id IS NOT NULL;

ALTER TABLE session_event_envelopes
  ADD CONSTRAINT chk_session_event_envelope_change_type_v4
    CHECK (change_type IN (
      'SESSION', 'BROWSER_STATE', 'AUDIT_EVENT', 'OPERATION', 'AGENT_TASK',
      'RESOURCE_SAMPLE', 'RESOURCE_EVENT', 'SAFETY_LEASE_EVENT',
      'CHALLENGE_EVENT', 'HUMAN_ASSIST_INTENT', 'REMOTE_DESKTOP_PARTICIPANT',
      'CHALLENGE_AUTOMATION'
    )) NOT VALID;
ALTER TABLE session_event_envelopes
  VALIDATE CONSTRAINT chk_session_event_envelope_change_type_v4;
ALTER TABLE session_event_envelopes DROP CONSTRAINT chk_session_event_envelope_change_type;
ALTER TABLE session_event_envelopes
  RENAME CONSTRAINT chk_session_event_envelope_change_type_v4
    TO chk_session_event_envelope_change_type;

CREATE FUNCTION append_challenge_automation_run_envelope()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    PERFORM append_session_event_envelope(
      NEW.tenant_id, NEW.session_id, 'CHALLENGE_AUTOMATION', NEW.run_id, NEW.updated_at
    );
    RETURN NEW;
END;
$$;

CREATE FUNCTION append_challenge_visual_job_envelope()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    PERFORM append_session_event_envelope(
      NEW.tenant_id, NEW.session_id, 'CHALLENGE_AUTOMATION', NEW.job_id, NEW.updated_at
    );
    RETURN NEW;
END;
$$;

CREATE TRIGGER append_challenge_automation_run_envelope
AFTER INSERT OR UPDATE ON challenge_automation_runs
FOR EACH ROW EXECUTE FUNCTION append_challenge_automation_run_envelope();

CREATE TRIGGER append_challenge_visual_job_envelope
AFTER INSERT OR UPDATE ON challenge_visual_jobs
FOR EACH ROW EXECUTE FUNCTION append_challenge_visual_job_envelope();

COMMENT ON COLUMN sessions.challenge_automation_max_attempts IS
  'Maximum automatic visual Challenge actions before operator notification; default three';
COMMENT ON TABLE challenge_automation_runs IS
  'Tenant-scoped bounded visual Challenge automation lifecycle bound to one paused Agent task';
COMMENT ON TABLE challenge_visual_jobs IS
  'Durable screenshot/OCR/vision jobs; pixels remain behind purpose-bound one-time evidence grants';
