CREATE TABLE session_recordings (
    recording_id               TEXT PRIMARY KEY,
    event_id                   TEXT NOT NULL UNIQUE,
    tenant_id                  TEXT NOT NULL,
    session_id                 TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
    node_id                    TEXT NOT NULL,
    segment_count              BIGINT NOT NULL,
    frame_count                BIGINT NOT NULL,
    dropped_frames             BIGINT NOT NULL,
    redacted_frame_count       BIGINT NOT NULL,
    redacted_region_count      BIGINT NOT NULL,
    redaction_policy_version   INTEGER NOT NULL,
    manifest_object_key        TEXT NOT NULL,
    manifest_sha256            TEXT NOT NULL,
    manifest_bytes             BIGINT NOT NULL,
    started_at                 TIMESTAMPTZ NOT NULL,
    ended_at                   TIMESTAMPTZ NOT NULL,
    retention_until            TIMESTAMPTZ NOT NULL,
    legal_hold                 BOOLEAN NOT NULL DEFAULT FALSE,
    created_at                 TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_session_recordings_tenant_identity UNIQUE (tenant_id, session_id, recording_id),
    CONSTRAINT chk_session_recording_counts CHECK (
        segment_count >= 0 AND frame_count >= 0 AND dropped_frames >= 0
        AND redacted_frame_count >= 0 AND redacted_frame_count <= frame_count
        AND redacted_region_count >= 0
        AND (redacted_frame_count = 0 OR redacted_region_count > 0)
    ) NOT VALID,
    CONSTRAINT chk_session_recording_manifest CHECK (
        redaction_policy_version = 1
        AND manifest_sha256 ~ '^[0-9a-f]{64}$'
        AND manifest_bytes > 0
        AND ended_at >= started_at
        AND retention_until >= ended_at
    ) NOT VALID
);

ALTER TABLE session_recordings VALIDATE CONSTRAINT chk_session_recording_counts;
ALTER TABLE session_recordings VALIDATE CONSTRAINT chk_session_recording_manifest;

CREATE INDEX idx_session_recordings_tenant_session_time
ON session_recordings(tenant_id, session_id, ended_at DESC, recording_id DESC);

CREATE INDEX idx_session_recordings_retention
ON session_recordings(retention_until)
WHERE legal_hold = FALSE;

COMMENT ON TABLE session_recordings IS
  'Node-authoritative immutable recording manifest projection; frame bytes remain in Object Storage';
