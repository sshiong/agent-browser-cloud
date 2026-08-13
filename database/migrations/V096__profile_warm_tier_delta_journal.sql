-- Region Warm Tier delta journal control-plane projection. Profile bytes and CAS chunks stay on
-- the isolated Storage Helper volume; PostgreSQL stores only the committed transaction barrier and
-- bounded accounting metadata received through the fenced Node event stream.

CREATE TABLE profile_warm_tier_journal_commits (
    event_id              TEXT PRIMARY KEY,
    tenant_id             TEXT NOT NULL,
    session_id            TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
    profile_id            TEXT NOT NULL REFERENCES profiles(profile_id) ON DELETE CASCADE,
    node_id               TEXT NOT NULL,
    profile_write_epoch   BIGINT NOT NULL,
    journal_sequence      BIGINT NOT NULL,
    transaction_barrier   TEXT NOT NULL,
    changed_file_count    BIGINT NOT NULL,
    deleted_file_count    BIGINT NOT NULL,
    reused_chunk_count    BIGINT NOT NULL,
    uploaded_bytes        BIGINT NOT NULL,
    deferred_group_count  BIGINT NOT NULL,
    manifest_sha256       TEXT NOT NULL,
    committed_at          TIMESTAMPTZ NOT NULL,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_profile_warm_tier_sequence
        UNIQUE (tenant_id, profile_id, profile_write_epoch, journal_sequence),
    CONSTRAINT uq_profile_warm_tier_barrier
        UNIQUE (transaction_barrier)
);

ALTER TABLE profile_warm_tier_journal_commits
    ADD CONSTRAINT chk_profile_warm_tier_identity CHECK (
        event_id ~ '^evt_[a-zA-Z0-9_-]{1,124}$'
        AND session_id ~ '^ses_[a-zA-Z0-9_-]{1,124}$'
        AND node_id ~ '^node_[a-zA-Z0-9_-]{1,123}$'
        AND transaction_barrier ~ '^wtb_[a-zA-Z0-9_-]{8,192}$'
    ) NOT VALID,
    ADD CONSTRAINT chk_profile_warm_tier_sequence CHECK (
        profile_write_epoch > 0 AND journal_sequence > 0
    ) NOT VALID,
    ADD CONSTRAINT chk_profile_warm_tier_counts CHECK (
        changed_file_count >= 0 AND deleted_file_count >= 0
        AND reused_chunk_count >= 0 AND uploaded_bytes >= 0
        AND deferred_group_count >= 0
        AND changed_file_count + reused_chunk_count <= 50000
        AND uploaded_bytes <= 67108864
    ) NOT VALID,
    ADD CONSTRAINT chk_profile_warm_tier_hash CHECK (
        manifest_sha256 ~ '^[0-9a-f]{64}$'
    ) NOT VALID;

CREATE INDEX idx_profile_warm_tier_latest
    ON profile_warm_tier_journal_commits (
        tenant_id, profile_id, profile_write_epoch DESC, journal_sequence DESC
    );
CREATE INDEX idx_profile_warm_tier_session_time
    ON profile_warm_tier_journal_commits (tenant_id, session_id, committed_at DESC);

ALTER TABLE profile_warm_tier_journal_commits
    VALIDATE CONSTRAINT chk_profile_warm_tier_identity;
ALTER TABLE profile_warm_tier_journal_commits
    VALIDATE CONSTRAINT chk_profile_warm_tier_sequence;
ALTER TABLE profile_warm_tier_journal_commits
    VALIDATE CONSTRAINT chk_profile_warm_tier_counts;
ALTER TABLE profile_warm_tier_journal_commits
    VALIDATE CONSTRAINT chk_profile_warm_tier_hash;

COMMENT ON TABLE profile_warm_tier_journal_commits IS
    'Committed Region Warm Tier delta barriers and accounting metadata; Profile bytes remain outside PostgreSQL';
