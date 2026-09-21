CREATE TABLE session_recording_playback_grants (
    grant_id             TEXT PRIMARY KEY,
    tenant_id            TEXT NOT NULL,
    session_id           TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
    recording_id         TEXT NOT NULL,
    actor_id             TEXT NOT NULL,
    purpose              TEXT NOT NULL,
    idempotency_key      TEXT NOT NULL,
    request_id           TEXT,
    state                TEXT NOT NULL,
    expires_at           TIMESTAMPTZ NOT NULL,
    created_at           TIMESTAMPTZ NOT NULL,
    redeem_started_at    TIMESTAMPTZ,
    redeemed_at          TIMESTAMPTZ,
    access_expires_at    TIMESTAMPTZ,
    signer_node_id       TEXT,
    error_code           TEXT,
    CONSTRAINT uq_recording_playback_grant_idempotency
      UNIQUE (tenant_id, actor_id, idempotency_key),
    CONSTRAINT fk_recording_playback_grant_recording_identity
      FOREIGN KEY (tenant_id, session_id, recording_id)
      REFERENCES session_recordings(tenant_id, session_id, recording_id)
      ON DELETE CASCADE,
    CONSTRAINT chk_recording_playback_grant_identity
      CHECK (grant_id ~ '^rgr_[0-9a-f]{32}$'),
    CONSTRAINT chk_recording_playback_grant_purpose
      CHECK (purpose IN (
        'INCIDENT_RESPONSE', 'SUPPORT_DIAGNOSTICS', 'COMPLIANCE_AUDIT',
        'SECURITY_INVESTIGATION'
      )),
    CONSTRAINT chk_recording_playback_grant_state
      CHECK (state IN ('ISSUED', 'REDEEMING', 'REDEEMED', 'FAILED')),
    CONSTRAINT chk_recording_playback_grant_lifecycle
      CHECK (
        expires_at > created_at
        AND (redeem_started_at IS NULL OR redeem_started_at >= created_at)
        AND (redeemed_at IS NULL OR redeemed_at >= created_at)
        AND (access_expires_at IS NULL OR access_expires_at > redeemed_at)
        AND (state NOT IN ('REDEEMED', 'FAILED') OR redeemed_at IS NOT NULL)
        AND (state <> 'REDEEMED' OR access_expires_at IS NOT NULL)
      )
);

CREATE INDEX idx_recording_playback_grants_expiry
ON session_recording_playback_grants(expires_at)
WHERE state = 'ISSUED';

CREATE INDEX idx_recording_playback_access_expiry
ON session_recording_playback_grants(access_expires_at)
WHERE state = 'REDEEMED';

COMMENT ON TABLE session_recording_playback_grants IS
  'Purpose/tenant/session/recording/actor-bound one-time playback grants; signed URLs are never persisted';
