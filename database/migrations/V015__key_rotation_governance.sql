CREATE TABLE key_rotation_requests (
    rotation_id             TEXT PRIMARY KEY,
    tenant_id               TEXT NOT NULL,
    key_scope               TEXT NOT NULL,
    old_key_id              TEXT NOT NULL,
    new_key_id              TEXT NOT NULL,
    rotation_trigger        TEXT NOT NULL,
    reason                  TEXT NOT NULL,
    requested_overlap_minutes INTEGER NOT NULL,
    state                   TEXT NOT NULL,
    requested_by            TEXT NOT NULL,
    approved_by             TEXT,
    completed_by            TEXT,
    revoked_by              TEXT,
    requested_at            TIMESTAMPTZ NOT NULL,
    approved_at             TIMESTAMPTZ,
    started_at              TIMESTAMPTZ,
    completed_at            TIMESTAMPTZ,
    revoked_at              TIMESTAMPTZ,
    overlap_until           TIMESTAMPTZ,
    progress_percent        INTEGER NOT NULL DEFAULT 0,
    new_key_write_verified  BOOLEAN,
    old_key_read_verified   BOOLEAN,
    plaintext_rejected      BOOLEAN,
    affected_workloads      INTEGER,
    verification_reference  TEXT,
    approval_evidence_hash  TEXT,
    completion_evidence_hash TEXT,
    CONSTRAINT chk_key_rotation_scope CHECK (
        key_scope IN (
            'NODE_MTLS',
            'RUNTIME_SIGNING',
            'PROFILE_KEK',
            'REMOTE_DESKTOP',
            'AGENT_CAPABILITY'
        )
    ),
    CONSTRAINT chk_key_rotation_trigger CHECK (
        rotation_trigger IN (
            'SCHEDULED',
            'PERSONNEL_CHANGE',
            'POLICY_CHANGE',
            'SUSPECTED_COMPROMISE',
            'TENANT_REQUEST'
        )
    ),
    CONSTRAINT chk_key_rotation_state CHECK (
        state IN ('REQUESTED', 'ROTATING', 'COMPLETED', 'REVOKED', 'FAILED')
    ),
    CONSTRAINT chk_key_rotation_separation CHECK (
        approved_by IS NULL OR approved_by <> requested_by
    ),
    CONSTRAINT chk_key_rotation_distinct_keys CHECK (
        old_key_id <> new_key_id
    ),
    CONSTRAINT chk_key_rotation_progress CHECK (
        progress_percent BETWEEN 0 AND 100
    ),
    CONSTRAINT chk_key_rotation_overlap CHECK (
        requested_overlap_minutes BETWEEN 0 AND 1440
    )
);

CREATE INDEX idx_key_rotation_tenant_created
ON key_rotation_requests(tenant_id, requested_at DESC);

CREATE UNIQUE INDEX idx_key_rotation_one_active
ON key_rotation_requests(key_scope, old_key_id)
WHERE state IN ('REQUESTED', 'ROTATING');

COMMENT ON TABLE key_rotation_requests IS
'Dual-control key rotation lifecycle with overlap, verification and immutable audit evidence';
