-- Challenge Detection is observational only. A Browser write is possible only through a
-- user-authorized, single-use Human Assist intent bound to the current Session context and State.

CREATE TABLE challenge_events (
    challenge_event_id      TEXT PRIMARY KEY,
    tenant_id               TEXT NOT NULL,
    session_id              TEXT NOT NULL,
    context_epoch           BIGINT NOT NULL,
    state_version           BIGINT NOT NULL,
    target_revision         BIGINT NOT NULL,
    confidence              DOUBLE PRECISION NOT NULL,
    evidence                JSONB NOT NULL,
    suspected_type          TEXT NOT NULL,
    access_outcome          TEXT NOT NULL,
    target_ref              TEXT,
    target_summary          TEXT NOT NULL,
    visual_anchor_hash      TEXT,
    status                  TEXT NOT NULL,
    detected_at             TIMESTAMPTZ NOT NULL,
    authorization_deadline  TIMESTAMPTZ NOT NULL,
    expires_at              TIMESTAMPTZ NOT NULL,
    updated_at              TIMESTAMPTZ NOT NULL,
    version                 BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_challenge_event_session
      FOREIGN KEY (session_id, tenant_id)
        REFERENCES sessions(id, tenant_id) ON DELETE CASCADE,
    CONSTRAINT chk_challenge_event_id
      CHECK (challenge_event_id ~ '^chl_[A-Za-z0-9]{20}$'),
    CONSTRAINT chk_challenge_event_epoch
      CHECK (context_epoch > 0 AND state_version > 0 AND target_revision > 0),
    CONSTRAINT chk_challenge_event_confidence
      CHECK (confidence BETWEEN 0 AND 1),
    CONSTRAINT chk_challenge_event_evidence
      CHECK (jsonb_typeof(evidence) = 'object' AND octet_length(evidence::TEXT) <= 8192),
    CONSTRAINT chk_challenge_event_type
      CHECK (suspected_type IN (
        'SINGLE_CLICK', 'IMAGE_SELECTION', 'PUZZLE', 'OTP', 'DEVICE_CONFIRMATION',
        'MULTI_ROUND', 'USER_JUDGMENT', 'PAYMENT_CONFIRMATION', 'UNKNOWN'
      )),
    CONSTRAINT chk_challenge_access_outcome
      CHECK (access_outcome IN ('CHALLENGE_SUSPECTED', 'CHALLENGE_CONFIRMED')),
    CONSTRAINT chk_challenge_event_status
      CHECK (status IN (
        'SUSPECTED', 'CONFIRMED', 'AUTHORIZED', 'EXECUTING', 'RESOLVED',
        'FAILED', 'EXPIRED', 'SUPERSEDED', 'TAKEOVER_REQUIRED'
      )),
    CONSTRAINT chk_challenge_event_target
      CHECK (
        (suspected_type = 'SINGLE_CLICK' AND target_ref IS NOT NULL
          AND visual_anchor_hash ~ '^[a-f0-9]{64}$')
        OR
        (suspected_type <> 'SINGLE_CLICK' AND target_ref IS NULL
          AND visual_anchor_hash IS NULL)
      ),
    CONSTRAINT chk_challenge_event_deadlines
      CHECK (detected_at < authorization_deadline
        AND authorization_deadline <= expires_at
        AND updated_at >= detected_at)
);

CREATE UNIQUE INDEX uq_challenge_event_state_target
    ON challenge_events(
      tenant_id, session_id, context_epoch, state_version, target_revision,
      suspected_type, COALESCE(target_ref, '')
    )
    WHERE status IN ('SUSPECTED', 'CONFIRMED', 'AUTHORIZED', 'EXECUTING', 'TAKEOVER_REQUIRED');

CREATE INDEX idx_challenge_events_session_timeline
    ON challenge_events(tenant_id, session_id, detected_at DESC, challenge_event_id DESC);

CREATE INDEX idx_challenge_events_expiry
    ON challenge_events(expires_at, challenge_event_id)
    WHERE status IN ('SUSPECTED', 'CONFIRMED', 'AUTHORIZED', 'EXECUTING', 'TAKEOVER_REQUIRED');

CREATE TABLE human_click_intents (
    intent_id                 TEXT PRIMARY KEY,
    challenge_event_id        TEXT NOT NULL REFERENCES challenge_events(challenge_event_id),
    tenant_id                 TEXT NOT NULL,
    user_id                   TEXT NOT NULL,
    session_id                TEXT NOT NULL,
    context_epoch             BIGINT NOT NULL,
    state_version             BIGINT NOT NULL,
    target_revision           BIGINT NOT NULL,
    allowed_region            JSONB NOT NULL,
    allowed_target_ref        TEXT NOT NULL,
    visual_anchor_hash        TEXT NOT NULL,
    allowed_action_count      INTEGER NOT NULL DEFAULT 1,
    consumed_count            INTEGER NOT NULL DEFAULT 0,
    authorization_event_id    TEXT NOT NULL REFERENCES audit_events(event_id),
    operation_id              TEXT,
    request_id                TEXT NOT NULL,
    idempotency_key           TEXT NOT NULL,
    state                     TEXT NOT NULL,
    expires_at                TIMESTAMPTZ NOT NULL,
    created_at                TIMESTAMPTZ NOT NULL,
    consumed_at               TIMESTAMPTZ,
    completed_at              TIMESTAMPTZ,
    error_code                TEXT,
    updated_at                TIMESTAMPTZ NOT NULL,
    version                   BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_human_click_intent_session
      FOREIGN KEY (session_id, tenant_id)
        REFERENCES sessions(id, tenant_id) ON DELETE CASCADE,
    CONSTRAINT fk_human_click_intent_operation
      FOREIGN KEY (operation_id) REFERENCES exclusive_operations(operation_id),
    CONSTRAINT chk_human_click_intent_id
      CHECK (intent_id ~ '^hint_[A-Za-z0-9]{20}$'),
    CONSTRAINT chk_human_click_intent_epoch
      CHECK (context_epoch > 0 AND state_version > 0 AND target_revision > 0),
    CONSTRAINT chk_human_click_intent_region
      CHECK (jsonb_typeof(allowed_region) = 'object' AND octet_length(allowed_region::TEXT) <= 1024),
    CONSTRAINT chk_human_click_intent_anchor
      CHECK (visual_anchor_hash ~ '^[a-f0-9]{64}$'),
    CONSTRAINT chk_human_click_intent_budget
      CHECK (allowed_action_count = 1 AND consumed_count BETWEEN 0 AND 1),
    CONSTRAINT chk_human_click_intent_state
      CHECK (state IN ('AUTHORIZED', 'EXECUTING', 'COMMITTED', 'FAILED', 'EXPIRED')),
    CONSTRAINT chk_human_click_intent_consumption
      CHECK (
        (state = 'AUTHORIZED' AND consumed_count = 0 AND consumed_at IS NULL
          AND completed_at IS NULL AND error_code IS NULL)
        OR
        (state = 'EXECUTING' AND consumed_count = 1 AND consumed_at IS NOT NULL
          AND operation_id IS NOT NULL AND completed_at IS NULL AND error_code IS NULL)
        OR
        (state = 'COMMITTED' AND consumed_count = 1 AND consumed_at IS NOT NULL
          AND operation_id IS NOT NULL AND completed_at IS NOT NULL AND error_code IS NULL)
        OR
        (state = 'FAILED' AND consumed_count = 1 AND consumed_at IS NOT NULL
          AND operation_id IS NOT NULL AND completed_at IS NOT NULL
          AND error_code ~ '^[A-Z][A-Z0-9_]{2,127}$')
        OR
        (state = 'EXPIRED' AND consumed_count = 0 AND consumed_at IS NULL
          AND completed_at IS NOT NULL AND error_code = 'HUMAN_ASSIST_EXPIRED')
      ),
    CONSTRAINT chk_human_click_intent_deadline
      CHECK (created_at < expires_at AND updated_at >= created_at)
);

CREATE UNIQUE INDEX uq_human_click_intent_tenant_idempotency
    ON human_click_intents(tenant_id, idempotency_key);

CREATE UNIQUE INDEX uq_human_click_intent_operation
    ON human_click_intents(operation_id)
    WHERE operation_id IS NOT NULL;

CREATE UNIQUE INDEX uq_human_click_intent_active_event
    ON human_click_intents(challenge_event_id)
    WHERE state IN ('AUTHORIZED', 'EXECUTING');

CREATE INDEX idx_human_click_intent_expiry
    ON human_click_intents(expires_at, intent_id)
    WHERE state IN ('AUTHORIZED', 'EXECUTING');

ALTER TABLE agent_tasks ADD COLUMN challenge_event_id TEXT;
ALTER TABLE agent_tasks
    ADD CONSTRAINT fk_agent_task_challenge_event
      FOREIGN KEY (challenge_event_id) REFERENCES challenge_events(challenge_event_id) NOT VALID;
ALTER TABLE agent_tasks VALIDATE CONSTRAINT fk_agent_task_challenge_event;

ALTER TABLE session_event_envelopes
    ADD CONSTRAINT chk_session_event_envelope_change_type_v2
      CHECK (change_type IN (
        'SESSION', 'BROWSER_STATE', 'AUDIT_EVENT', 'OPERATION', 'AGENT_TASK',
        'RESOURCE_SAMPLE', 'RESOURCE_EVENT', 'SAFETY_LEASE_EVENT',
        'CHALLENGE_EVENT', 'HUMAN_ASSIST_INTENT'
      )) NOT VALID;
ALTER TABLE session_event_envelopes
    VALIDATE CONSTRAINT chk_session_event_envelope_change_type_v2;
ALTER TABLE session_event_envelopes DROP CONSTRAINT chk_session_event_envelope_change_type;
ALTER TABLE session_event_envelopes
    RENAME CONSTRAINT chk_session_event_envelope_change_type_v2
      TO chk_session_event_envelope_change_type;

CREATE FUNCTION append_challenge_event_envelope()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    PERFORM append_session_event_envelope(
      NEW.tenant_id, NEW.session_id, 'CHALLENGE_EVENT', NEW.challenge_event_id, NEW.updated_at
    );
    RETURN NEW;
END;
$$;

CREATE FUNCTION append_human_assist_intent_envelope()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    PERFORM append_session_event_envelope(
      NEW.tenant_id, NEW.session_id, 'HUMAN_ASSIST_INTENT', NEW.intent_id, NEW.updated_at
    );
    RETURN NEW;
END;
$$;

CREATE TRIGGER append_challenge_event_envelope
AFTER INSERT OR UPDATE ON challenge_events
FOR EACH ROW EXECUTE FUNCTION append_challenge_event_envelope();

CREATE TRIGGER append_human_assist_intent_envelope
AFTER INSERT OR UPDATE ON human_click_intents
FOR EACH ROW EXECUTE FUNCTION append_human_assist_intent_envelope();

COMMENT ON TABLE challenge_events IS
  'Input-free challenge classifications derived from authoritative Browser State';
COMMENT ON TABLE human_click_intents IS
  'User-authorized, state-bound, single-use Human Assist click budget; automatic retry is forbidden';
