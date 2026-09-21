ALTER TABLE session_recordings
    ADD COLUMN deleted_at TIMESTAMPTZ,
    ADD COLUMN deletion_receipt_hash TEXT,
    ADD COLUMN deletion_proof_hash TEXT,
    ADD COLUMN deleted_object_count BIGINT,
    ADD CONSTRAINT chk_session_recording_physical_deletion CHECK (
        (deleted_at IS NULL
          AND deletion_receipt_hash IS NULL
          AND deletion_proof_hash IS NULL
          AND deleted_object_count IS NULL)
        OR
        (deleted_at IS NOT NULL
          AND deletion_receipt_hash ~ '^[0-9a-f]{64}$'
          AND deletion_proof_hash ~ '^[0-9a-f]{64}$'
          AND deleted_object_count > 0)
    ) NOT VALID;

ALTER TABLE session_recordings
    VALIDATE CONSTRAINT chk_session_recording_physical_deletion;

CREATE TABLE recording_retention_deletion_jobs (
    job_id                  TEXT PRIMARY KEY,
    tenant_id               TEXT NOT NULL,
    session_id              TEXT NOT NULL,
    recording_id            TEXT NOT NULL,
    state                   TEXT NOT NULL DEFAULT 'QUEUED',
    attempt                 INTEGER NOT NULL DEFAULT 0,
    maximum_attempts        INTEGER NOT NULL DEFAULT 10,
    execution_epoch         BIGINT NOT NULL DEFAULT 0,
    available_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at              TIMESTAMPTZ,
    completed_at            TIMESTAMPTZ,
    failure_code            TEXT,
    deletion_proof_hash     TEXT,
    deleted_object_count    BIGINT,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_recording_retention_deletion_identity
      UNIQUE (tenant_id, session_id, recording_id),
    CONSTRAINT fk_recording_retention_deletion_identity
      FOREIGN KEY (tenant_id, session_id, recording_id)
      REFERENCES session_recordings(tenant_id, session_id, recording_id),
    CONSTRAINT chk_recording_retention_deletion_state CHECK (
        state IN ('QUEUED', 'EXECUTING', 'RETRY', 'COMMITTED', 'FAILED')
    ),
    CONSTRAINT chk_recording_retention_deletion_attempt CHECK (
        attempt >= 0 AND maximum_attempts BETWEEN 1 AND 20 AND attempt <= maximum_attempts
        AND execution_epoch >= 0
    ),
    CONSTRAINT chk_recording_retention_deletion_result CHECK (
        (state = 'COMMITTED'
          AND completed_at IS NOT NULL
          AND failure_code IS NULL
          AND deletion_proof_hash ~ '^[0-9a-f]{64}$'
          AND deleted_object_count > 0)
        OR
        (state <> 'COMMITTED'
          AND deletion_proof_hash IS NULL
          AND deleted_object_count IS NULL)
    )
);

CREATE INDEX idx_recording_retention_deletion_ready
ON recording_retention_deletion_jobs(available_at, created_at, job_id)
WHERE state IN ('QUEUED', 'RETRY');

CREATE INDEX idx_session_recordings_physical_deletion_due
ON session_recordings(retention_until, created_at, recording_id)
WHERE legal_hold = FALSE AND deleted_at IS NULL;

COMMENT ON TABLE recording_retention_deletion_jobs IS
  'Authoritative retryable queue for retention-expired Recording object deletion; COMMITTED requires Node tombstone proof';

COMMENT ON COLUMN session_recordings.deletion_proof_hash IS
  'SHA-256 of the Storage Helper deletion tombstone; object coordinates are never exposed';
