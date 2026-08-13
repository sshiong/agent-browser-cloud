-- Purpose-bound, actor-owned and one-time Profile checkpoint export grants. Signed URLs and
-- Object Storage credentials never enter PostgreSQL.

CREATE TABLE profile_export_access_grants (
    grant_id             TEXT PRIMARY KEY,
    tenant_id            TEXT NOT NULL,
    profile_id           TEXT NOT NULL REFERENCES profiles(profile_id) ON DELETE CASCADE,
    checkpoint_id        TEXT NOT NULL,
    checkpoint_epoch     BIGINT NOT NULL,
    actor_id             TEXT NOT NULL,
    purpose              TEXT NOT NULL,
    idempotency_key      TEXT NOT NULL,
    request_id           TEXT,
    state                TEXT NOT NULL,
    expires_at           TIMESTAMPTZ NOT NULL,
    created_at           TIMESTAMPTZ NOT NULL,
    redeem_started_at    TIMESTAMPTZ,
    redeemed_at          TIMESTAMPTZ,
    signer_node_id       TEXT,
    archive_sha256       TEXT,
    archive_size_bytes   BIGINT,
    error_code           TEXT,
    CONSTRAINT uq_profile_export_access_idempotency
        UNIQUE (tenant_id, actor_id, idempotency_key)
);

ALTER TABLE profile_export_access_grants
    ADD CONSTRAINT chk_profile_export_access_grant_id
        CHECK (grant_id ~ '^pxg_[a-zA-Z0-9]{16,}$') NOT VALID,
    ADD CONSTRAINT chk_profile_export_access_checkpoint
        CHECK (checkpoint_id ~ '^chk_[a-zA-Z0-9_-]{1,124}$' AND checkpoint_epoch > 0) NOT VALID,
    ADD CONSTRAINT chk_profile_export_access_purpose CHECK (
        purpose IN (
            'INCIDENT_RESPONSE',
            'SUPPORT_DIAGNOSTICS',
            'COMPLIANCE_EXPORT',
            'TENANT_BACKUP'
        )
    ) NOT VALID,
    ADD CONSTRAINT chk_profile_export_access_state CHECK (
        state IN ('ISSUED', 'REDEEMING', 'REDEEMED', 'FAILED')
    ) NOT VALID,
    ADD CONSTRAINT chk_profile_export_access_expiry CHECK (
        expires_at > created_at
        AND expires_at <= created_at + INTERVAL '5 minutes'
    ) NOT VALID,
    ADD CONSTRAINT chk_profile_export_access_result CHECK (
        (
            state = 'ISSUED'
            AND redeem_started_at IS NULL AND redeemed_at IS NULL
            AND signer_node_id IS NULL AND archive_sha256 IS NULL
            AND archive_size_bytes IS NULL AND error_code IS NULL
        ) OR (
            state = 'REDEEMING'
            AND redeem_started_at IS NOT NULL AND redeemed_at IS NULL
            AND signer_node_id IS NULL AND archive_sha256 IS NULL
            AND archive_size_bytes IS NULL AND error_code IS NULL
        ) OR (
            state = 'REDEEMED'
            AND redeem_started_at IS NOT NULL AND redeemed_at IS NOT NULL
            AND signer_node_id IS NOT NULL
            AND archive_sha256 ~ '^[0-9a-f]{64}$'
            AND archive_size_bytes > 0 AND error_code IS NULL
        ) OR (
            state = 'FAILED'
            AND redeem_started_at IS NOT NULL AND redeemed_at IS NOT NULL
            AND signer_node_id IS NULL AND archive_sha256 IS NULL
            AND archive_size_bytes IS NULL AND error_code IS NOT NULL
        )
    ) NOT VALID;

CREATE INDEX idx_profile_export_access_profile_time
    ON profile_export_access_grants(tenant_id, profile_id, created_at DESC);
CREATE INDEX idx_profile_export_access_expiry
    ON profile_export_access_grants(expires_at)
    WHERE state IN ('ISSUED', 'REDEEMING');

CREATE OR REPLACE FUNCTION enforce_profile_export_access_scope()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM profiles profile
        WHERE profile.profile_id = NEW.profile_id
          AND profile.tenant_id = NEW.tenant_id
          AND profile.latest_checkpoint_id = NEW.checkpoint_id
          AND profile.latest_checkpoint_epoch = NEW.checkpoint_epoch
    ) THEN
        RAISE EXCEPTION 'Profile export grant does not match the tenant checkpoint'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_profile_export_access_scope
    BEFORE INSERT OR UPDATE OF tenant_id, profile_id, checkpoint_id, checkpoint_epoch
    ON profile_export_access_grants
    FOR EACH ROW
    EXECUTE FUNCTION enforce_profile_export_access_scope();

ALTER TABLE profile_export_access_grants
    VALIDATE CONSTRAINT chk_profile_export_access_grant_id;
ALTER TABLE profile_export_access_grants
    VALIDATE CONSTRAINT chk_profile_export_access_checkpoint;
ALTER TABLE profile_export_access_grants
    VALIDATE CONSTRAINT chk_profile_export_access_purpose;
ALTER TABLE profile_export_access_grants
    VALIDATE CONSTRAINT chk_profile_export_access_state;
ALTER TABLE profile_export_access_grants
    VALIDATE CONSTRAINT chk_profile_export_access_expiry;
ALTER TABLE profile_export_access_grants
    VALIDATE CONSTRAINT chk_profile_export_access_result;

COMMENT ON TABLE profile_export_access_grants IS
    'Purpose-bound one-time Profile checkpoint export grants; signed URLs and credentials are never persisted';
