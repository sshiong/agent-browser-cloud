-- Bounded staging for explicit FULL Browser State resync.
-- A stream is never visible as authoritative Browser State until every chunk and both SHA-256
-- checks pass in the Commit transaction. Payload bytes are temporary and removed after terminal
-- commit/expiry.

CREATE TABLE browser_state_snapshot_streams (
    snapshot_id       TEXT PRIMARY KEY,
    tenant_id         TEXT NOT NULL,
    session_id        TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
    coordinator_term  BIGINT NOT NULL,
    context_epoch     BIGINT NOT NULL,
    operation_epoch   BIGINT NOT NULL,
    state_version     BIGINT NOT NULL,
    target_revision   BIGINT NOT NULL,
    total_chunks      INTEGER NOT NULL,
    total_bytes       BIGINT NOT NULL,
    payload_sha256    TEXT NOT NULL,
    snapshot_kind     TEXT NOT NULL,
    status            TEXT NOT NULL DEFAULT 'RECEIVING',
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at        TIMESTAMPTZ NOT NULL DEFAULT now() + interval '10 minutes',
    committed_at      TIMESTAMPTZ,
    CONSTRAINT chk_browser_state_snapshot_id
        CHECK (char_length(snapshot_id) BETWEEN 1 AND 160),
    CONSTRAINT chk_browser_state_snapshot_fences
        CHECK (coordinator_term >= 0 AND context_epoch >= 0 AND operation_epoch >= 0),
    CONSTRAINT chk_browser_state_snapshot_versions
        CHECK (state_version > 0 AND target_revision > 0),
    CONSTRAINT chk_browser_state_snapshot_chunks
        CHECK (total_chunks BETWEEN 1 AND 32),
    CONSTRAINT chk_browser_state_snapshot_bytes
        CHECK (total_bytes BETWEEN 1 AND 524288),
    CONSTRAINT chk_browser_state_snapshot_sha
        CHECK (payload_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT chk_browser_state_snapshot_kind
        CHECK (snapshot_kind = 'FULL_RESYNC'),
    CONSTRAINT chk_browser_state_snapshot_status
        CHECK (status IN ('RECEIVING', 'COMMIT_RECEIVED', 'COMMITTED', 'CANCELLED', 'EXPIRED', 'REJECTED'))
);

CREATE UNIQUE INDEX uq_browser_state_snapshot_active_context
    ON browser_state_snapshot_streams(session_id, context_epoch)
    WHERE status IN ('RECEIVING', 'COMMIT_RECEIVED');

CREATE INDEX idx_browser_state_snapshot_expiry
    ON browser_state_snapshot_streams(expires_at)
    WHERE status IN ('RECEIVING', 'COMMIT_RECEIVED');

CREATE TABLE browser_state_snapshot_chunks (
    snapshot_id       TEXT NOT NULL REFERENCES browser_state_snapshot_streams(snapshot_id)
                           ON DELETE CASCADE,
    chunk_index       INTEGER NOT NULL,
    total_chunks      INTEGER NOT NULL,
    chunk_bytes       INTEGER NOT NULL,
    chunk_sha256      TEXT NOT NULL,
    payload           BYTEA NOT NULL,
    received_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (snapshot_id, chunk_index),
    CONSTRAINT chk_browser_state_snapshot_chunk_index
        CHECK (chunk_index BETWEEN 0 AND 31 AND chunk_index < total_chunks),
    CONSTRAINT chk_browser_state_snapshot_chunk_total
        CHECK (total_chunks BETWEEN 1 AND 32),
    CONSTRAINT chk_browser_state_snapshot_chunk_bytes
        CHECK (chunk_bytes BETWEEN 1 AND 16384 AND octet_length(payload) = chunk_bytes),
    CONSTRAINT chk_browser_state_snapshot_chunk_sha
        CHECK (chunk_sha256 ~ '^[0-9a-f]{64}$')
);

COMMENT ON TABLE browser_state_snapshot_streams IS
    'Bounded manifest for atomic Full Browser State snapshot assembly';
COMMENT ON TABLE browser_state_snapshot_chunks IS
    'Temporary checked Browser State protobuf chunks; deleted on commit, cancellation, or expiry';
