ALTER TABLE runtime_builds
    ADD COLUMN release_channel TEXT NOT NULL DEFAULT 'UNRELEASED',
    ADD COLUMN disabled_at TIMESTAMPTZ,
    ADD COLUMN disabled_by TEXT,
    ADD CONSTRAINT chk_runtime_release_channel CHECK (
        release_channel IN ('UNRELEASED', 'CANARY', 'STABLE', 'DISABLED')
    );

UPDATE runtime_builds
SET release_channel = 'STABLE'
WHERE regression_status = 'STABLE'
  AND released_at IS NOT NULL;

CREATE TABLE runtime_release_requests (
    release_id          TEXT PRIMARY KEY,
    tenant_id           TEXT NOT NULL,
    build_id            TEXT NOT NULL REFERENCES runtime_builds(build_id),
    target_channel      TEXT NOT NULL,
    reason              TEXT NOT NULL,
    state               TEXT NOT NULL,
    requested_by        TEXT NOT NULL,
    approved_by         TEXT,
    rejected_by         TEXT,
    requested_at        TIMESTAMPTZ NOT NULL,
    decided_at          TIMESTAMPTZ,
    evidence_hash       TEXT,
    CONSTRAINT chk_runtime_release_target CHECK (
        target_channel IN ('CANARY', 'STABLE', 'DISABLED')
    ),
    CONSTRAINT chk_runtime_release_state CHECK (
        state IN ('REQUESTED', 'APPROVED', 'REJECTED')
    ),
    CONSTRAINT chk_runtime_release_separation CHECK (
        approved_by IS NULL OR approved_by <> requested_by
    )
);

CREATE INDEX idx_runtime_release_tenant_created
ON runtime_release_requests(tenant_id, requested_at DESC);

CREATE UNIQUE INDEX idx_runtime_release_one_pending
ON runtime_release_requests(build_id, target_channel)
WHERE state = 'REQUESTED';

COMMENT ON TABLE runtime_release_requests IS
'Dual-control Runtime promotion and disable decisions with immutable release evidence';
