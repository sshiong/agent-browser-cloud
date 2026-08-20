-- Immutable-at-runtime Session identity specification and approval-backed change requests.

CREATE TABLE session_identity_specs (
    session_id              TEXT PRIMARY KEY,
    tenant_id               TEXT NOT NULL,
    spec_json               JSONB NOT NULL,
    spec_hash               TEXT NOT NULL,
    version                 BIGINT NOT NULL DEFAULT 1,
    locked_at               TIMESTAMPTZ NOT NULL,
    updated_at              TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_session_identity_spec_session
      FOREIGN KEY (session_id, tenant_id) REFERENCES sessions(id, tenant_id) ON DELETE CASCADE,
    CONSTRAINT chk_session_identity_spec_hash CHECK (spec_hash ~ '^[a-f0-9]{64}$'),
    CONSTRAINT chk_session_identity_spec_version CHECK (version > 0),
    CONSTRAINT chk_session_identity_spec_object CHECK (jsonb_typeof(spec_json) = 'object')
);

-- Historical Sessions receive the same normalized default object as newly created Sessions. The
-- stored SHA-256 is over the compact record field order produced by the Control Plane serializer,
-- so a semantically unchanged default request cannot create a fake change.
INSERT INTO session_identity_specs(
  session_id, tenant_id, spec_json, spec_hash, version, locked_at, updated_at
)
SELECT id, tenant_id,
       '{"userAgent":null,"timezone":null,"locale":null,"languages":[],"webRtcPolicy":"DEFAULT","dnsPolicy":"SYSTEM","viewportWidth":null,"viewportHeight":null,"screenWidth":null,"screenHeight":null,"deviceScaleFactor":null,"fingerprintProfile":null,"operatingSystemProfile":null}'::jsonb,
       'a3229301e34d71709d2cadb4b8b3355fb2131e8e6969e06bdf6b02f6d9bc3e8a',
       1, created_at, updated_at
FROM sessions;

CREATE TABLE session_identity_change_requests (
    request_id              TEXT PRIMARY KEY,
    tenant_id               TEXT NOT NULL,
    session_id              TEXT NOT NULL,
    expected_version        BIGINT NOT NULL,
    proposed_spec_json      JSONB NOT NULL,
    proposed_spec_hash      TEXT NOT NULL,
    reason                  TEXT NOT NULL,
    idempotency_key         TEXT NOT NULL,
    state                   TEXT NOT NULL DEFAULT 'PENDING',
    created_by              TEXT NOT NULL,
    decided_by              TEXT,
    created_at              TIMESTAMPTZ NOT NULL,
    decided_at              TIMESTAMPTZ,
    applied_at              TIMESTAMPTZ,
    CONSTRAINT fk_session_identity_change_session
      FOREIGN KEY (session_id, tenant_id) REFERENCES sessions(id, tenant_id) ON DELETE CASCADE,
    CONSTRAINT chk_session_identity_change_id
      CHECK (request_id ~ '^sicr_[A-Za-z0-9]{20}$'),
    CONSTRAINT chk_session_identity_change_version CHECK (expected_version > 0),
    CONSTRAINT chk_session_identity_change_hash CHECK (proposed_spec_hash ~ '^[a-f0-9]{64}$'),
    CONSTRAINT chk_session_identity_change_object
      CHECK (jsonb_typeof(proposed_spec_json) = 'object'),
    CONSTRAINT chk_session_identity_change_state
      CHECK (state IN ('PENDING', 'APPROVED', 'REJECTED', 'APPLIED', 'STALE')),
    CONSTRAINT chk_session_identity_change_decision
      CHECK (
        (state = 'PENDING' AND decided_by IS NULL AND decided_at IS NULL)
        OR (state <> 'PENDING' AND decided_by IS NOT NULL AND decided_at IS NOT NULL)
      ),
    CONSTRAINT chk_session_identity_change_applied
      CHECK ((state = 'APPLIED') = (applied_at IS NOT NULL))
);

CREATE UNIQUE INDEX uq_session_identity_change_pending
  ON session_identity_change_requests(tenant_id, session_id)
  WHERE state IN ('PENDING', 'APPROVED');

CREATE UNIQUE INDEX uq_session_identity_change_idempotency
  ON session_identity_change_requests(tenant_id, session_id, idempotency_key);

CREATE INDEX idx_session_identity_change_tenant
  ON session_identity_change_requests(tenant_id, created_at DESC, request_id DESC);

COMMENT ON TABLE session_identity_specs IS
  'Tenant-scoped immutable Runtime identity inputs; direct mutation is forbidden after creation';
COMMENT ON TABLE session_identity_change_requests IS
  'Approval-backed changes applied only at a safe Runtime restart boundary';
