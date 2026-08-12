-- Authoritative Browser Node remote desktop participant projection. VNC connections stay
-- collaborative with Agent execution; administrators may revoke one exact connection.

CREATE TABLE remote_desktop_participants (
    connection_id       TEXT PRIMARY KEY,
    tenant_id           TEXT NOT NULL,
    session_id          TEXT NOT NULL,
    context_epoch       BIGINT NOT NULL,
    actor_id            TEXT,
    access_mode         TEXT,
    view_only           BOOLEAN,
    state               TEXT NOT NULL,
    reason              TEXT NOT NULL,
    connected_at        TIMESTAMPTZ,
    disconnected_at     TIMESTAMPTZ,
    revoked_by          TEXT,
    revoke_requested_at TIMESTAMPTZ,
    observed_at         TIMESTAMPTZ NOT NULL,
    updated_at          TIMESTAMPTZ NOT NULL,
    version             BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_remote_desktop_participant_session
      FOREIGN KEY (session_id, tenant_id)
        REFERENCES sessions(id, tenant_id) ON DELETE CASCADE,
    CONSTRAINT chk_remote_desktop_participant_id
      CHECK (connection_id ~ '^rdc_[A-Za-z0-9]{20}$'),
    CONSTRAINT chk_remote_desktop_participant_epoch CHECK (context_epoch > 0),
    CONSTRAINT chk_remote_desktop_participant_access
      CHECK (access_mode IS NULL OR access_mode IN ('COLLABORATIVE', 'EXCLUSIVE_TAKEOVER')),
    CONSTRAINT chk_remote_desktop_participant_state
      CHECK (state IN ('CONNECTED', 'REVOKE_REQUESTED', 'REVOKED', 'DISCONNECTED')),
    CONSTRAINT chk_remote_desktop_participant_actor
      CHECK ((state IN ('CONNECTED', 'DISCONNECTED')) = (actor_id IS NOT NULL)
        OR state IN ('REVOKE_REQUESTED', 'REVOKED')),
    CONSTRAINT chk_remote_desktop_participant_time
      CHECK (updated_at >= observed_at)
);

CREATE INDEX idx_remote_desktop_participants_online
    ON remote_desktop_participants(tenant_id, session_id, observed_at DESC, connection_id)
    WHERE state IN ('CONNECTED', 'REVOKE_REQUESTED');

CREATE INDEX idx_remote_desktop_participants_timeline
    ON remote_desktop_participants(tenant_id, session_id, observed_at DESC, connection_id DESC);

ALTER TABLE session_event_envelopes
    ADD CONSTRAINT chk_session_event_envelope_change_type_v3
      CHECK (change_type IN (
        'SESSION', 'BROWSER_STATE', 'AUDIT_EVENT', 'OPERATION', 'AGENT_TASK',
        'RESOURCE_SAMPLE', 'RESOURCE_EVENT', 'SAFETY_LEASE_EVENT',
        'CHALLENGE_EVENT', 'HUMAN_ASSIST_INTENT', 'REMOTE_DESKTOP_PARTICIPANT'
      )) NOT VALID;
ALTER TABLE session_event_envelopes
    VALIDATE CONSTRAINT chk_session_event_envelope_change_type_v3;
ALTER TABLE session_event_envelopes DROP CONSTRAINT chk_session_event_envelope_change_type;
ALTER TABLE session_event_envelopes
    RENAME CONSTRAINT chk_session_event_envelope_change_type_v3
      TO chk_session_event_envelope_change_type;

CREATE FUNCTION append_remote_desktop_participant_envelope()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    PERFORM append_session_event_envelope(
      NEW.tenant_id, NEW.session_id, 'REMOTE_DESKTOP_PARTICIPANT',
      NEW.connection_id, NEW.updated_at
    );
    RETURN NEW;
END;
$$;

CREATE TRIGGER append_remote_desktop_participant_envelope
AFTER INSERT OR UPDATE ON remote_desktop_participants
FOR EACH ROW
EXECUTE FUNCTION append_remote_desktop_participant_envelope();

COMMENT ON TABLE remote_desktop_participants IS
  'Browser Node authoritative VNC participant lifecycle; no ticket nonce or credential persisted';
